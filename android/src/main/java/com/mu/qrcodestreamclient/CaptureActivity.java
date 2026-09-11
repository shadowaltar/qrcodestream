package com.mu.qrcodestreamclient;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.mu.qrcodestream.core.Envelope;
import com.mu.qrcodestream.core.FountainDecoder;
import com.mu.qrcodestream.core.Frame;
import com.mu.qrcodestream.core.Protocol;
import com.mu.qrcodestreamclient.databinding.ActivityCaptureBinding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CaptureActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_NAME = "com.mu.qrcodestreamclient.EXTRA_FILE_NAME";

    private static final long OVERLAY_UPDATE_INTERVAL_MS = 100L;

    private ActivityCaptureBinding binding;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MultiFormatReader reader = new MultiFormatReader();

    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private ExecutorService analyzerExecutor;

    private volatile FountainDecoder decoder;
    private volatile boolean completed;
    private volatile boolean failed;
    private long lastOverlayUpdate;
    private byte[] pendingData;
    private String fileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCaptureBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        if (fileName == null || fileName.isEmpty()) {
            fileName = "capture.capture";
        }

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        binding.backButton.setOnClickListener(v -> finish());
        binding.retryButton.setVisibility(View.GONE);
        binding.statusMessage.setText(CaptureWriter.downloadDisplayPath(fileName));

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

                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();
                analyzerExecutor = Executors.newSingleThreadExecutor();
                imageAnalysis.setAnalyzer(analyzerExecutor, this::analyze);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
                binding.progressOverlay.setText(R.string.scanning);
            } catch (Exception e) {
                showError(getString(R.string.camera_error, String.valueOf(e.getMessage())), false, false);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy imageProxy) {
        try {
            if (completed || failed) {
                return;
            }
            ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            byte[] luminance = toLuminance(plane.getBuffer(), width, height,
                    plane.getRowStride(), plane.getPixelStride());

            LuminanceSource source =
                    new PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            Result result;
            try {
                result = reader.decode(bitmap);
            } catch (ReaderException e) {
                result = null;
            }
            if (result != null) {
                onDecoded(result.getText());
            }
        } catch (RuntimeException e) {
            // Transient per-frame failures are expected while aiming; ignore them.
        } finally {
            imageProxy.close();
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
        decoder = null;
        pendingData = null;
        completed = false;
        failed = false;
        lastOverlayUpdate = 0L;
        binding.completionPanel.setVisibility(View.GONE);
        binding.previewView.setVisibility(View.VISIBLE);
        binding.progressOverlay.setVisibility(View.VISIBLE);
        binding.retryButton.setEnabled(true);
        startCamera();
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
        mainHandler.removeCallbacksAndMessages(null);
    }

    private static byte[] toLuminance(ByteBuffer buffer, int width, int height,
                                      int rowStride, int pixelStride) {
        byte[] luminance = new byte[width * height];
        if (rowStride == width && pixelStride == 1) {
            buffer.get(luminance, 0, luminance.length);
            return luminance;
        }
        int output = 0;
        for (int row = 0; row < height; row++) {
            int rowOffset = row * rowStride;
            for (int col = 0; col < width; col++) {
                luminance[output++] = buffer.get(rowOffset + col * pixelStride);
            }
        }
        return luminance;
    }
}
