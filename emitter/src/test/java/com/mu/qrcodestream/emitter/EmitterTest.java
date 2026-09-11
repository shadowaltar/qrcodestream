package com.mu.qrcodestream.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.mu.qrcodestream.core.FountainEncoder;
import com.mu.qrcodestream.core.Protocol;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmitterTest {

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void rendererProducesHighContrastImage() {
        QrRenderer renderer = new QrRenderer(ErrorCorrectionLevel.L, 4);
        FountainEncoder encoder =
                FountainEncoder.forFile(randomBytes(1000, 1), Protocol.DEFAULT_CHUNK_LEN, 2.0);

        BufferedImage image = renderer.render(encoder.frameAt(0));

        assertTrue(image.getWidth() > 0, "image width must be positive");
        assertTrue(image.getHeight() > 0, "image height must be positive");
        boolean hasBlack = false;
        boolean hasWhite = false;
        for (int y = 0; y < image.getHeight() && !(hasBlack && hasWhite); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                if (rgb == 0x000000) {
                    hasBlack = true;
                } else if (rgb == 0xFFFFFF) {
                    hasWhite = true;
                }
                if (hasBlack && hasWhite) {
                    break;
                }
            }
        }
        assertTrue(hasBlack, "QR image should contain black modules");
        assertTrue(hasWhite, "QR image should contain a white quiet zone");
    }

    @Test
    void renderedQrDecodesBackToFrame() throws Exception {
        QrRenderer renderer = new QrRenderer(ErrorCorrectionLevel.L, 4);
        FountainEncoder encoder =
                FountainEncoder.forFile(randomBytes(1200, 3), Protocol.DEFAULT_CHUNK_LEN, 2.0);
        String frame = encoder.frameAt(0);

        BufferedImage image = renderer.render(frame);
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        BinaryBitmap bitmap =
                new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
        Result result = new MultiFormatReader().decode(bitmap);

        assertEquals(frame, result.getText());
    }

    @Test
    void headlessModeWritesNonEmptyPngFrames(@TempDir Path outputDir) throws IOException {
        QrRenderer renderer = new QrRenderer(ErrorCorrectionLevel.L, 2);
        FountainEncoder encoder = FountainEncoder.forFile(randomBytes(500, 2), 256, 2.0);

        int written = HeadlessEmitter.writeFrames(encoder, renderer, outputDir, 2);

        assertEquals(2, written);
        for (int i = 0; i < written; i++) {
            Path frame = outputDir.resolve(String.format("frame-%06d.png", i));
            assertTrue(Files.exists(frame), "missing frame " + frame);
            assertTrue(Files.size(frame) > 0, "frame should be non-empty: " + frame);
        }
    }

    @Test
    void cliParsesDefaults() {
        CliOptions options = CliOptions.parse(new String[] {"--input", "x.bin"});

        assertEquals("x.bin", options.input.toString());
        assertNull(options.headlessDir);
        assertEquals(-1, options.maxFrames);
    }

    @Test
    void cliParsesOverrides() {
        CliOptions options = CliOptions.parse(new String[] {
            "--input", "x.bin",
            "--headless", "out",
            "--frames", "4"
        });

        assertEquals("x.bin", options.input.toString());
        assertEquals("out", options.headlessDir.toString());
        assertEquals(4, options.maxFrames);
    }

    @Test
    void cliAllowsMissingInputForUiMode() {
        CliOptions options = CliOptions.parse(new String[] {});

        assertNull(options.input);
    }

    @Test
    void cliRejectsUnknownOption() {
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[] {"--nope", "1"}));
    }

    @Test
    void maxChunkLenFitsForcedVersion() {
        int previous = 0;
        for (int version : new int[] {10, 20, 30, 40}) {
            int chunkLen = QrRenderer.maxChunkLen(version, ErrorCorrectionLevel.L, 32);
            assertTrue(chunkLen > previous, "capacity should grow with version");
            previous = chunkLen;

            QrRenderer renderer = new QrRenderer(ErrorCorrectionLevel.L, 2, version);
            String frame = Protocol.buildFrame(1, chunkLen, 123456, new byte[chunkLen]);
            BufferedImage image = renderer.render(frame);
            assertEquals(version, (image.getWidth() / 2 - 8 - 17) / 4,
                    "rendered QR should be exactly the requested version");
        }
    }
}
