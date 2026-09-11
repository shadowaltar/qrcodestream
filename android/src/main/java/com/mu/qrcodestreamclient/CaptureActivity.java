package com.mu.qrcodestreamclient;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.mu.qrcodestream.core.Envelope;
import com.mu.qrcodestream.core.FountainDecoder;
import com.mu.qrcodestream.core.Frame;
import com.mu.qrcodestream.core.Protocol;
import com.mu.qrcodestreamclient.databinding.ActivityCaptureBinding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CaptureActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_NAME = "com.mu.qrcodestreamclient.EXTRA_FILE_NAME";
    public static final String EXTRA_QUEUE_MAX = "com.mu.qrcodestreamclient.EXTRA_QUEUE_MAX";
    public static final String EXTRA_TARGET_WIDTH = "com.mu.qrcodestreamclient.EXTRA_TARGET_WIDTH";
    public static final String EXTRA_TARGET_HEIGHT = "com.mu.qrcodestreamclient.EXTRA_TARGET_HEIGHT";

    public static final int DEFAULT_DECODE_QUEUE_MAX = 64;
    public static final int MIN_DECODE_QUEUE_MAX = 1;
    public static final int MAX_DECODE_QUEUE_MAX = 512;

    private static final long OVERLAY_UPDATE_INTERVAL_MS = 100L;
    private static final int MAX_POOLED_BUFFERS = 16;
    private static final long MLKIT_TIMEOUT_MS = 1500L;

    private ActivityCaptureBinding binding;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private BarcodeScanner barcodeScanner;

    /** CameraX analyzer executor: copies the ROI and closes the buffer as fast as possible. */
    private ExecutorService analyzerExecutor;
    /** Decode workers: ML Kit then ZXing on the copied ROI, away from the camera pipeline. */
    private ExecutorService decodeExecutor;
    /** Growable hand-off buffer from the analyzer to the decoders (bounded by decodeQueueMax). */
    private LinkedBlockingDeque<LuminanceFrame> decodeQueue;
    /** Reused NV21 buffers (Y + neutral chroma) to avoid per-frame allocation. */
    private final ConcurrentLinkedQueue<byte[]> bufferPool = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pooledBuffers = new AtomicInteger();

    /** Guards {@link #decoder} and completion state shared between decode workers and the UI. */
    private final Object decodeLock = new Object();

    private volatile FountainDecoder decoder;
    private volatile boolean completed;
    private volatile boolean failed;
    private long lastOverlayUpdate;
    private byte[] pendingData;
    private String fileName;

    private int decodeQueueMax = DEFAULT_DECODE_QUEUE_MAX;
    private int decodeWorkerCount = 1;
    private int targetWidth;
    private int targetHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCaptureBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        if (fileName == null || fileName.isEmpty()) {
            fileName = "capture.capture";
        }
        decodeQueueMax = Math.max(MIN_DECODE_QUEUE_MAX, Math.min(MAX_DECODE_QUEUE_MAX,
                getIntent().getIntExtra(EXTRA_QUEUE_MAX, DEFAULT_DECODE_QUEUE_MAX)));
        targetWidth = getIntent().getIntExtra(EXTRA_TARGET_WIDTH, 0);
        targetHeight = getIntent().getIntExtra(EXTRA_TARGET_HEIGHT, 0);

        binding.backButton.setOnClickListener(v -> finish());
        binding.retryButton.setVisibility(View.GONE);
        binding.statusMessage.setText(CaptureWriter.downloadDisplayPath(fileName));

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int roiSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        binding.roiOverlay.setLayoutParams(new FrameLayout.LayoutParams(roiSide, roiSide, Gravity.CENTER));

        barcodeScanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build());

        analyzerExecutor = Executors.newSingleThreadExecutor(run -> {
            Thread thread = new Thread(run, "qr-analyzer");
            thread.setDaemon(true);
            return thread;
        });
        decodeQueue = new LinkedBlockingDeque<>();
        decodeWorkerCount = Math.max(1,
                Math.min(2, Runtime.getRuntime().availableProcessors() - 1));
        decodeExecutor = Executors.newFixedThreadPool(decodeWorkerCount, run -> {
            Thread thread = new Thread(run, "qr-decode");
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < decodeWorkerCount; i++) {
            decodeExecutor.execute(this::decodeLoop);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            showError(getString(R.string.camera_permission_missing), false, false);
            return;
        }

        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

                ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888);
                if (targetWidth > 0 && targetHeight > 0) {
                    analysisBuilder.setResolutionSelector(new ResolutionSelector.Builder()
                            .setResolutionStrategy(new ResolutionStrategy(
                                    new Size(targetWidth, targetHeight),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build());
                }
                imageAnalysis = analysisBuilder.build();
                imageAnalysis.setAnalyzer(analyzerExecutor, this::analyze);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
                binding.progressOverlay.setText(R.string.scanning);
            } catch (Exception e) {
                showError(getString(R.string.camera_error, String.valueOf(e.getMessage())), false, false);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * CameraX analyzer callback. Copies only the centered ROI (a square as wide as the screen's
     * shorter side) plus neutral chroma, then closes the camera buffer immediately so decoding
     * happens off the camera pipeline.
     */
    private void analyze(ImageProxy imageProxy) {
        try {
            if (completed || failed) {
                return;
            }
            ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            int side = Math.min(width, height);
            int left = (width - side) / 2;
            int top = (height - side) / 2;
            int ySize = side * side;

            byte[] buffer = obtainBuffer(ySize + ySize / 2);
            toLuminance(plane.getBuffer(), width, height,
                    plane.getRowStride(), plane.getPixelStride(), left, top, side, buffer);
            Arrays.fill(buffer, ySize, buffer.length, (byte) 0x80);

            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            offerLatest(new LuminanceFrame(buffer, side, side, rotation));
        } catch (RuntimeException e) {
            // Transient per-frame failures are expected while aiming; ignore them.
        } finally {
            imageProxy.close();
        }
    }

    private void offerLatest(LuminanceFrame frame) {
        decodeQueue.offerLast(frame);
        while (decodeQueue.size() > decodeQueueMax) {
            LuminanceFrame dropped = decodeQueue.pollFirst();
            if (dropped != null) {
                releaseBuffer(dropped.data);
            }
        }
    }

    private void decodeLoop() {
        MultiFormatReader fastReader = new MultiFormatReader();
        MultiFormatReader robustReader = new MultiFormatReader();
        Map<DecodeHintType, Object> fastHints = hints(false);
        Map<DecodeHintType, Object> robustHints = hints(true);

        while (!Thread.currentThread().isInterrupted()) {
            LuminanceFrame frame;
            try {
                frame = decodeQueue.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (!completed && !failed) {
                    String text = decodeText(frame, fastReader, fastHints, robustReader, robustHints);
                    if (text != null) {
                        synchronized (decodeLock) {
                            onDecoded(text);
                        }
                    }
                }
            } catch (RuntimeException e) {
                // Transient per-frame failures are expected while aiming; ignore them.
            } finally {
                releaseBuffer(frame.data);
            }
        }
    }

    /** ML Kit first (fast, robust), then ZXing as a fallback. */
    private String decodeText(LuminanceFrame frame,
                              MultiFormatReader fastReader,
                              Map<DecodeHintType, Object> fastHints,
                              MultiFormatReader robustReader,
                              Map<DecodeHintType, Object> robustHints) {
        String mlkitText = runMlKit(frame);
        if (mlkitText != null) {
            return mlkitText;
        }
        return runZxing(frame, fastReader, fastHints, robustReader, robustHints);
    }

    private String runMlKit(LuminanceFrame frame) {
        BarcodeScanner scanner = barcodeScanner;
        if (scanner == null) {
            return null;
        }
        InputImage image = InputImage.fromByteBuffer(
                ByteBuffer.wrap(frame.data), frame.width, frame.height,
                frame.rotationDegrees, ImageFormat.NV21);

        final AtomicReference<String> text = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        if (barcode.getRawValue() != null) {
                            text.set(barcode.getRawValue());
                            break;
                        }
                    }
                })
                .addOnCompleteListener(task -> latch.countDown());
        try {
            if (!latch.await(MLKIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return text.get();
    }

    private static String runZxing(LuminanceFrame frame,
                                   MultiFormatReader fastReader,
                                   Map<DecodeHintType, Object> fastHints,
                                   MultiFormatReader robustReader,
                                   Map<DecodeHintType, Object> robustHints) {
        PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                frame.data, frame.width, frame.height, 0, 0, frame.width, frame.height, false);
        try {
            return fastReader.decode(
                    new BinaryBitmap(new GlobalHistogramBinarizer(source)), fastHints).getText();
        } catch (ReaderException ignored) {
            // fall through to the robust attempt
        }
        try {
            return robustReader.decode(
                    new BinaryBitmap(new HybridBinarizer(source)), robustHints).getText();
        } catch (ReaderException ignored) {
            return null;
        }
    }

    private static Map<DecodeHintType, Object> hints(boolean tryHarder) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        if (tryHarder) {
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        }
        return hints;
    }

    private byte[] obtainBuffer(int size) {
        byte[] buffer;
        while ((buffer = bufferPool.poll()) != null) {
            pooledBuffers.decrementAndGet();
            if (buffer.length == size) {
                return buffer;
            }
        }
        return new byte[size];
    }

    private void releaseBuffer(byte[] buffer) {
        if (buffer != null && pooledBuffers.get() < MAX_POOLED_BUFFERS) {
            bufferPool.offer(buffer);
            pooledBuffers.incrementAndGet();
        }
    }

    private void onDecoded(String text) {
        Frame frame = Protocol.parseFrame(text);
        if (frame == null) {
            return;
        }
        FountainDecoder target = decoder;
        if (target == null) {
            target = new FountainDecoder(frame.getChunkLen(), frame.getTotal());
            decoder = target;
        }
        if (target.add(frame)) {
            postOverlay(target, false);
        }
        if (target.isComplete()) {
            completed = true;
            postOverlay(target, true);
            byte[] payload = target.payload();
            if (payload != null) {
                processPayload(payload);
            }
        }
    }

    private void postOverlay(FountainDecoder target, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastOverlayUpdate < OVERLAY_UPDATE_INTERVAL_MS) {
            return;
        }
        lastOverlayUpdate = now;
        String text = getString(R.string.progress_format,
                target.receivedCount(), target.solvedCount(),
                target.sourceBlockCount(), (int) Math.round(target.progress() * 100.0));
        mainHandler.post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                binding.progressOverlay.setText(text);
            }
        });
    }

    private void processPayload(byte[] payload) {
        Envelope envelope;
        try {
            envelope = Protocol.parseEnvelope(new String(payload, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            showError(getString(R.string.integrity_error, String.valueOf(e.getMessage())), true, false);
            return;
        }
        if (!envelope.verify()) {
            showError(getString(R.string.integrity_error, getString(R.string.integrity_mismatch)), true, false);
            return;
        }
        byte[] data = envelope.getData();
        try {
            String location = CaptureWriter.write(this, fileName, data);
            mainHandler.post(() -> showDone(location));
        } catch (IOException e) {
            pendingData = data;
            showError(getString(R.string.write_error, String.valueOf(e.getMessage())), false, true);
        }
    }

    private void showDone(String location) {
        stopCamera();
        binding.roiOverlay.setVisibility(View.GONE);
        binding.previewView.setVisibility(View.GONE);
        binding.progressOverlay.setVisibility(View.GONE);
        binding.completionPanel.setVisibility(View.VISIBLE);
        binding.statusTitle.setText(R.string.status_done);
        binding.statusMessage.setText(getString(R.string.saved_to, location));
        binding.retryButton.setVisibility(View.GONE);
        binding.retryButton.setOnClickListener(null);
        binding.backButton.setVisibility(View.VISIBLE);
    }

    private void showError(String message, boolean allowRescan, boolean allowRetrySave) {
        mainHandler.post(() -> {
            failed = true;
            stopCamera();
            binding.roiOverlay.setVisibility(View.GONE);
            binding.previewView.setVisibility(View.GONE);
            binding.progressOverlay.setVisibility(View.GONE);
            binding.completionPanel.setVisibility(View.VISIBLE);
            binding.statusTitle.setText(R.string.status_error);
            binding.statusMessage.setText(message);
            binding.retryButton.setVisibility(View.GONE);
            binding.retryButton.setOnClickListener(null);
            if (allowRetrySave) {
                binding.retryButton.setText(R.string.retry_save);
                binding.retryButton.setVisibility(View.VISIBLE);
                binding.retryButton.setOnClickListener(v -> retrySave());
            } else if (allowRescan) {
                binding.retryButton.setText(R.string.retry_scan);
                binding.retryButton.setVisibility(View.VISIBLE);
                binding.retryButton.setOnClickListener(v -> resetScan());
            }
            binding.backButton.setVisibility(View.VISIBLE);
        });
    }

    private void retrySave() {
        byte[] data = pendingData;
        if (data == null) {
            resetScan();
            return;
        }
        binding.retryButton.setEnabled(false);
        new Thread(() -> {
            try {
                String location = CaptureWriter.write(this, fileName, data);
                mainHandler.post(() -> {
                    binding.retryButton.setEnabled(true);
                    pendingData = null;
                    showDone(location);
                });
            } catch (IOException e) {
                mainHandler.post(() -> {
                    binding.retryButton.setEnabled(true);
                    binding.statusMessage.setText(getString(R.string.write_error, String.valueOf(e.getMessage())));
                });
            }
        }).start();
    }

    private void resetScan() {
        synchronized (decodeLock) {
            decoder = null;
            pendingData = null;
            completed = false;
            failed = false;
            lastOverlayUpdate = 0L;
        }
        clearQueue();
        binding.completionPanel.setVisibility(View.GONE);
        binding.roiOverlay.setVisibility(View.VISIBLE);
        binding.previewView.setVisibility(View.VISIBLE);
        binding.progressOverlay.setVisibility(View.VISIBLE);
        binding.retryButton.setEnabled(true);
        startCamera();
    }

    private void clearQueue() {
        LuminanceFrame frame;
        while ((frame = decodeQueue.pollFirst()) != null) {
            releaseBuffer(frame.data);
        }
    }

    private void stopCamera() {
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
        if (analyzerExecutor != null) {
            analyzerExecutor.shutdown();
            analyzerExecutor = null;
        }
        if (decodeExecutor != null) {
            decodeExecutor.shutdownNow();
            decodeExecutor = null;
        }
        if (barcodeScanner != null) {
            barcodeScanner.close();
            barcodeScanner = null;
        }
        bufferPool.clear();
        pooledBuffers.set(0);
        mainHandler.removeCallbacksAndMessages(null);
    }

    /** Copies the sub-rectangle (left, top, side x side) of the Y plane into {@code out}. */
    private static void toLuminance(ByteBuffer buffer, int width, int height,
                                    int rowStride, int pixelStride,
                                    int left, int top, int side, byte[] out) {
        int output = 0;
        for (int row = 0; row < side; row++) {
            int rowOffset = (top + row) * rowStride + left * pixelStride;
            if (pixelStride == 1) {
                for (int col = 0; col < side; col++) {
                    out[output++] = buffer.get(rowOffset + col);
                }
            } else {
                for (int col = 0; col < side; col++) {
                    out[output++] = buffer.get(rowOffset + col * pixelStride);
                }
            }
        }
    }

    /** A copied ROI frame: Y plane followed by neutral chroma (NV21 layout), plus rotation. */
    private static final class LuminanceFrame {
        final byte[] data;
        final int width;
        final int height;
        final int rotationDegrees;

        LuminanceFrame(byte[] data, int width, int height, int rotationDegrees) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.rotationDegrees = rotationDegrees;
        }
    }
}
