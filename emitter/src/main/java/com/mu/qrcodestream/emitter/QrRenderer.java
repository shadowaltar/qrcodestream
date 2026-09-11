package com.mu.qrcodestream.emitter;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.decoder.Version;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** Renders protocol frame strings as high-contrast QR images using ZXing core only. */
public final class QrRenderer {

    /** QR quiet-zone width in modules. ZXing requires at least 4. */
    public static final int QUIET_ZONE_MODULES = 4;

    private final QRCodeWriter writer = new QRCodeWriter();
    private final ErrorCorrectionLevel ec;
    private final int scale;
    private final int qrVersion;

    public QrRenderer(ErrorCorrectionLevel ec, int scale) {
        this(ec, scale, 0);
    }

    /**
     * @param qrVersion force a specific QR version (1..40), or {@code 0} to let ZXing pick the
     *                  smallest version that fits
     */
    public QrRenderer(ErrorCorrectionLevel ec, int scale, int qrVersion) {
        if (ec == null) {
            throw new IllegalArgumentException("ec must not be null");
        }
        if (scale <= 0) {
            throw new IllegalArgumentException("scale must be > 0");
        }
        if (qrVersion < 0 || qrVersion > 40) {
            throw new IllegalArgumentException("qrVersion must be 0..40");
        }
        this.ec = ec;
        this.scale = scale;
        this.qrVersion = qrVersion;
    }

    /**
     * Largest source-chunk length (raw bytes) whose Base64 form still fits in the given QR
     * version at the given error-correction level, after reserving room for the
     * {@code blockCode/chunkLen/total|} frame header.
     */
    public static int maxChunkLen(int qrVersion, ErrorCorrectionLevel ec, int headerReserve) {
        Version version = Version.getVersionForNumber(qrVersion);
        int dataCodewords = version.getTotalCodewords()
                - version.getECBlocksForLevel(ec).getTotalECCodewords();
        int charCountBits = qrVersion <= 9 ? 8 : 16;
        int maxBytes = (dataCodewords * 8 - 4 - charCountBits) / 8;
        int budget = maxBytes - headerReserve;
        if (budget <= 4) {
            return 1;
        }
        int chunkLen = (budget / 4) * 3;
        while (chunkLen > 1 && 4 * ((chunkLen + 2) / 3) + headerReserve > maxBytes) {
            chunkLen--;
        }
        return Math.max(1, chunkLen);
    }

    /** Encodes {@code text} and returns the rendered image (one pixel per module * scale). */
    public BufferedImage render(String text) {
        return toImage(encode(text), scale);
    }

    /**
     * Encodes {@code text} at one pixel per module (including the quiet zone). The interactive
     * window uses this and scales it up at paint time, so frames can be swapped quickly without
     * re-rasterising a huge image on the Event Dispatch Thread.
     */
    public BufferedImage renderModules(String text) {
        return toImage(encode(text), 1);
    }

    private BitMatrix encode(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ec);
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE_MODULES);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        if (qrVersion > 0) {
            hints.put(EncodeHintType.QR_VERSION, qrVersion);
        }
        try {
            return writer.encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new IllegalStateException("Failed to encode QR frame", e);
        }
    }

    /** Renders {@code text} and writes it to {@code file} as a PNG. */
    public void writePng(String text, Path file) throws IOException {
        writePng(render(text), file);
    }

    /** Writes an already-rendered image to {@code file} as a PNG. */
    public static void writePng(BufferedImage image, Path file) throws IOException {
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("No PNG image writer available for " + file);
        }
    }

    static BufferedImage toImage(BitMatrix matrix, int scale) {
        int modulesWide = matrix.getWidth();
        int modulesHigh = matrix.getHeight();
        BufferedImage image =
                new BufferedImage(modulesWide * scale, modulesHigh * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(Color.BLACK);
            for (int y = 0; y < modulesHigh; y++) {
                for (int x = 0; x < modulesWide; x++) {
                    if (matrix.get(x, y)) {
                        g.fillRect(x * scale, y * scale, scale, scale);
                    }
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }
}
