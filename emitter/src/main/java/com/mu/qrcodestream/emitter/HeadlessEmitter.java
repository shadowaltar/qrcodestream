package com.mu.qrcodestream.emitter;

import com.mu.qrcodestream.core.FountainEncoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the encoded frames as a numbered PNG sequence instead of displaying them. */
public final class HeadlessEmitter {

    private HeadlessEmitter() {}

    /**
     * Writes {@code frame-000000.png}, {@code frame-000001.png}, ... into {@code outputDir}.
     *
     * @param maxFrames cap on the number of frames, or {@code <= 0} to write all
     * @return the number of frames written
     */
    public static int writeFrames(
            FountainEncoder encoder, QrRenderer renderer, Path outputDir, int maxFrames)
            throws IOException {
        Files.createDirectories(outputDir);
        int available = encoder.frameCount();
        int count = (maxFrames > 0) ? Math.min(maxFrames, available) : available;
        for (int i = 0; i < count; i++) {
            Path file = outputDir.resolve(String.format("frame-%06d.png", i));
            renderer.writePng(encoder.frameAt(i), file);
        }
        return count;
    }
}
