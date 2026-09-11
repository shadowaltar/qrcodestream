package com.mu.qrcodestream.emitter;

import com.mu.qrcodestream.core.FountainEncoder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * Interactive emitter window: pick a file with the native OS open dialog, choose the QR version
 * and frame rate, then Start/Stop the animated QR stream. Frames are encoded on a background
 * thread and painted on the EDT, so the requested frame rate is not limited by rasterisation.
 */
public final class EmitterWindow {

    public static final int MIN_QR_VERSION = 10;
    public static final int MAX_QR_VERSION = 40;
    public static final int DEFAULT_QR_VERSION = 20;

    public static final int MIN_FPS = 10;
    public static final int MAX_FPS = 25;

    /** Reserved characters for the {@code blockCode/chunkLen/total|} frame header. */
    private static final int HEADER_RESERVE = 32;

    private final CliOptions options;
    private final int initialFps;

    private volatile QrRenderer renderer;

    private final JFrame frame = new JFrame("QRCodeStream Emitter");
    private final ImagePanel imagePanel = new ImagePanel();
    private final JLabel fileLabel = new JLabel("No file selected");
    private final JLabel statusLabel = new JLabel("Open a file to begin.");
    private final JButton openButton = new JButton("Open\u2026");
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JSlider versionSlider =
            new JSlider(MIN_QR_VERSION, MAX_QR_VERSION, DEFAULT_QR_VERSION);
    private final JSlider fpsSlider;
    private final JLabel versionValueLabel = new JLabel();
    private final JLabel fpsValueLabel = new JLabel();

    private final Timer timer;
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(run -> {
        Thread thread = new Thread(run, "qr-render");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean rendering = new AtomicBoolean(false);
    private final AtomicInteger nextIndex = new AtomicInteger(0);

    private volatile FountainEncoder encoder;
    private byte[] fileBytes;
    private long playbackStartNanos;
    private int framesShown;

    public EmitterWindow(CliOptions options) {
        this.options = options;
        this.initialFps = clamp(EmitterDefaults.FPS, MIN_FPS, MAX_FPS);
        this.renderer = new QrRenderer(EmitterDefaults.EC, EmitterDefaults.SCALE, DEFAULT_QR_VERSION);
        this.fpsSlider = new JSlider(MIN_FPS, MAX_FPS, initialFps);
        this.timer = new Timer(Math.max(1, 1000 / initialFps), e -> scheduleRender());
        buildUi();
    }

    private void buildUi() {
        fileLabel.setPreferredSize(new Dimension(420, 24));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        controls.add(openButton);
        controls.add(fileLabel);
        controls.add(startButton);
        controls.add(stopButton);

        startButton.setEnabled(false);
        stopButton.setEnabled(false);

        openButton.addActionListener(e -> chooseFile());
        startButton.addActionListener(e -> startPlayback());
        stopButton.addActionListener(e -> stopPlayback());

        versionSlider.setMajorTickSpacing(5);
        versionSlider.setPaintTicks(true);
        versionSlider.setPreferredSize(new Dimension(260, versionSlider.getPreferredSize().height));
        versionValueLabel.setPreferredSize(new Dimension(28, 20));
        versionValueLabel.setText(String.valueOf(DEFAULT_QR_VERSION));
        versionSlider.addChangeListener(e -> {
            versionValueLabel.setText(String.valueOf(versionSlider.getValue()));
            if (!versionSlider.getValueIsAdjusting()) {
                onVersionChanged(versionSlider.getValue());
            }
        });

        fpsSlider.setMajorTickSpacing(5);
        fpsSlider.setPaintTicks(true);
        fpsSlider.setPreferredSize(new Dimension(200, fpsSlider.getPreferredSize().height));
        fpsValueLabel.setPreferredSize(new Dimension(28, 20));
        fpsValueLabel.setText(String.valueOf(initialFps));
        fpsSlider.addChangeListener(e -> {
            fpsValueLabel.setText(String.valueOf(fpsSlider.getValue()));
            onFpsChanged(fpsSlider.getValue());
        });

        JPanel sliders = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        sliders.add(new JLabel("QR version:"));
        sliders.add(versionSlider);
        sliders.add(versionValueLabel);
        sliders.add(new JLabel("     FPS:"));
        sliders.add(fpsSlider);
        sliders.add(fpsValueLabel);

        JPanel bottom = new JPanel(new GridLayout(2, 1));
        bottom.add(statusLabel);
        bottom.add(sliders);

        int side = (int) (Math.min(
                Toolkit.getDefaultToolkit().getScreenSize().width,
                Toolkit.getDefaultToolkit().getScreenSize().height) * 0.68);
        imagePanel.setPreferredSize(new Dimension(Math.max(320, side), Math.max(320, side)));

        frame.setLayout(new BorderLayout());
        frame.add(controls, BorderLayout.NORTH);
        frame.add(imagePanel, BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                shutdown();
            }
        });
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    /** Shows the window; pre-selects {@code --input} when provided. */
    public void show() {
        frame.setVisible(true);
        if (options.input != null) {
            loadFile(options.input);
        }
    }

    private void chooseFile() {
        FileDialog dialog = new FileDialog(frame, "Select a file to transmit", FileDialog.LOAD);
        dialog.setVisible(true);
        String name = dialog.getFile();
        String directory = dialog.getDirectory();
        if (name == null || directory == null) {
            return;
        }
        loadFile(Paths.get(directory, name));
    }

    private void loadFile(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame,
                    "Cannot read file:\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        stopPlayback();
        fileBytes = bytes;
        fileLabel.setText(path.getFileName().toString());
        fileLabel.setToolTipText(path.toAbsolutePath().toString());
        rebuildEncoder();
    }

    private void rebuildEncoder() {
        if (fileBytes == null) {
            return;
        }
        int chunkLen = QrRenderer.maxChunkLen(
                versionSlider.getValue(), EmitterDefaults.EC, HEADER_RESERVE);
        FountainEncoder built =
                FountainEncoder.forFile(fileBytes, chunkLen, EmitterDefaults.REDUNDANCY);
        encoder = built;
        nextIndex.set(0);
        imagePanel.setImage(null);
        startButton.setEnabled(true);
        updateIdleStatus();
    }

    private void onVersionChanged(int version) {
        renderer = new QrRenderer(EmitterDefaults.EC, EmitterDefaults.SCALE, version);
        if (fileBytes != null) {
            rebuildEncoder();
        }
    }

    private void onFpsChanged(int fps) {
        timer.setDelay(Math.max(1, 1000 / fps));
        if (encoder != null && !timer.isRunning()) {
            updateIdleStatus();
        }
    }

    private void updateIdleStatus() {
        if (encoder == null) {
            statusLabel.setText("Open a file to begin.");
            return;
        }
        int fps = fpsSlider.getValue();
        statusLabel.setText(String.format(
                "%s \u2014 %,d bytes, v%d, %d B/frame, %d blocks, %d frames  |  %s @ %d fps",
                fileLabel.getText(), fileBytes.length, versionSlider.getValue(), encoder.chunkLen(),
                encoder.sourceBlockCount(), encoder.frameCount(),
                rateText(encoder.chunkLen() * (double) fps), fps));
    }

    private void startPlayback() {
        if (encoder == null) {
            return;
        }
        nextIndex.set(0);
        framesShown = 0;
        playbackStartNanos = System.nanoTime();
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        openButton.setEnabled(false);
        versionSlider.setEnabled(false);
        timer.setDelay(Math.max(1, 1000 / fpsSlider.getValue()));
        timer.start();
        scheduleRender();
    }

    private void stopPlayback() {
        timer.stop();
        stopButton.setEnabled(false);
        openButton.setEnabled(true);
        versionSlider.setEnabled(true);
        startButton.setEnabled(encoder != null);
    }

    private void scheduleRender() {
        FountainEncoder current = encoder;
        if (current == null || !rendering.compareAndSet(false, true)) {
            return;
        }
        int index = nextIndex.get();
        nextIndex.set((index + 1) % current.frameCount());
        final FountainEncoder encoderRef = current;
        renderExecutor.submit(() -> {
            BufferedImage image;
            try {
                image = renderer.renderModules(encoderRef.frameAt(index));
            } catch (RuntimeException ex) {
                rendering.set(false);
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (encoder == encoderRef) {
                    framesShown++;
                    double elapsedSeconds = (System.nanoTime() - playbackStartNanos) / 1_000_000_000.0;
                    double actualFps = elapsedSeconds > 0 ? framesShown / elapsedSeconds : 0.0;
                    imagePanel.setImage(image);
                    statusLabel.setText(String.format(
                            "%s \u2014 v%d  frame %d/%d  |  %s @ %.1f fps",
                            fileLabel.getText(), versionSlider.getValue(),
                            index + 1, encoderRef.frameCount(),
                            rateText(encoderRef.chunkLen() * actualFps), actualFps));
                }
                rendering.set(false);
            });
        });
    }

    /** Formats the raw optical channel rate: {@code chunkLen} bytes per displayed frame. */
    private static String rateText(double bytesPerSecond) {
        double kbps = bytesPerSecond * 8.0 / 1000.0;
        return String.format("~%.0f kbps", kbps);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void shutdown() {
        timer.stop();
        renderExecutor.shutdownNow();
        frame.dispose();
    }

    /** Panel that scales the small module image to fit, with nearest-neighbour crispness. */
    private static final class ImagePanel extends JPanel {

        private transient BufferedImage image;

        ImagePanel() {
            setBackground(Color.WHITE);
            setDoubleBuffered(true);
        }

        void setImage(BufferedImage image) {
            this.image = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                int panelWidth = getWidth();
                int panelHeight = getHeight();
                double factor = Math.min(
                        panelWidth / (double) image.getWidth(),
                        panelHeight / (double) image.getHeight());
                int drawWidth = (int) Math.round(image.getWidth() * factor);
                int drawHeight = (int) Math.round(image.getHeight() * factor);
                int x = (panelWidth - drawWidth) / 2;
                int y = (panelHeight - drawHeight) / 2;
                g2.setColor(Color.WHITE);
                g2.fillRect(0, 0, panelWidth, panelHeight);
                g2.drawImage(image, x, y, drawWidth, drawHeight, null);
            } finally {
                g2.dispose();
            }
        }
    }
}
