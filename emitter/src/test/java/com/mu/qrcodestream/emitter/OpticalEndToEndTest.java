package com.mu.qrcodestream.emitter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.mu.qrcodestream.core.Envelope;
import com.mu.qrcodestream.core.FountainDecoder;
import com.mu.qrcodestream.core.FountainEncoder;
import com.mu.qrcodestream.core.Frame;
import com.mu.qrcodestream.core.Protocol;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Full optical round trip: render each frame to a QR image, decode those images with ZXing
 * (as the Android camera would), additionally drop 20% of frames, and reconstruct the
 * original file. Individual QR decode misses are expected and simply treated as another
 * dropped frame — the fountain code absorbs them, which is the whole point of the design.
 */
class OpticalEndToEndTest {

    @Test
    void renderedFrameStreamReconstructsFileDespiteLoss() throws Exception {
        byte[] file = new byte[30_000];
        new Random(99).nextBytes(file);

        FountainEncoder encoder =
                FountainEncoder.forFile(file, Protocol.DEFAULT_CHUNK_LEN, Protocol.DEFAULT_REDUNDANCY);
        QrRenderer renderer = new QrRenderer(ErrorCorrectionLevel.L, 6);
        MultiFormatReader reader = new MultiFormatReader();

        FountainDecoder decoder = null;
        Random drop = new Random(5);
        int decoded = 0;
        int missed = 0;
        final int maxLoops = 6;
        outer:
        for (int loop = 0; loop < maxLoops; loop++) {
            for (int i = 0; i < encoder.frameCount(); i++) {
                // The camera may miss any given presentation of a frame.
                if (drop.nextDouble() < 0.2) {
                    continue;
                }
                BufferedImage image = renderer.render(encoder.frameAt(i));
                int width = image.getWidth();
                int height = image.getHeight();
                int[] pixels = new int[width * height];
                image.getRGB(0, 0, width, height, pixels, 0, width);

                String text;
                try {
                    Result result = reader.decode(
                            new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels))));
                    text = result.getText();
                } catch (Exception decodeMiss) {
                    missed++;
                    continue;
                }
                Frame frame = Protocol.parseFrame(text);
                if (frame == null) {
                    missed++;
                    continue;
                }

                if (decoder == null) {
                    decoder = new FountainDecoder(frame.getChunkLen(), frame.getTotal());
                }
                decoder.add(frame);
                decoded++;
                if (decoder.isComplete()) {
                    break outer;
                }
            }
        }

        assertNotNull(decoder, "no frames decoded");
        System.out.println("frames decoded=" + decoded + " qrMisses=" + missed
                + " sourceBlocks=" + encoder.sourceBlockCount());
        assertTrue(decoder.isComplete(),
                "file should complete with 20% drops + QR misses (decoded=" + decoded
                        + ", missed=" + missed + ", K=" + encoder.sourceBlockCount() + ")");
        Envelope envelope =
                Protocol.parseEnvelope(new String(decoder.payload(), StandardCharsets.UTF_8));
        assertTrue(envelope.verify(), "integrity check must pass");
        assertArrayEquals(file, envelope.getData(), "reconstructed file must match the original");
    }
}
