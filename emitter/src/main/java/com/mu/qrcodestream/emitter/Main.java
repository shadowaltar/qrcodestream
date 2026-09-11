package com.mu.qrcodestream.emitter;

import com.mu.qrcodestream.core.FountainEncoder;
import java.io.IOException;
import java.nio.file.Files;
import javax.swing.SwingUtilities;

/** CLI entrypoint for the optical QR emitter. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.println(CliOptions.usage());
            System.exit(2);
            return;
        }

        if (options.help) {
            System.out.println(CliOptions.usage());
            return;
        }

        if (options.headlessDir == null) {
            SwingUtilities.invokeLater(() -> new EmitterWindow(options).show());
            return;
        }

        if (options.input == null) {
            System.err.println("error: --input is required in headless mode");
            System.err.println();
            System.err.println(CliOptions.usage());
            System.exit(2);
            return;
        }

        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(options.input);
        } catch (IOException e) {
            System.err.println("error: cannot read input file '" + options.input + "': " + e.getMessage());
            System.exit(1);
            return;
        }

        FountainEncoder encoder =
                FountainEncoder.forFile(fileBytes, EmitterDefaults.HEADLESS_CHUNK_LEN,
                        EmitterDefaults.REDUNDANCY);
        QrRenderer renderer = new QrRenderer(EmitterDefaults.EC, EmitterDefaults.SCALE);

        System.out.printf("Input: %s (%d bytes)%n", options.input, fileBytes.length);
        System.out.printf(
                "Source blocks: %d, total frames: %d (chunkLen=%d, redundancy=%.2f, ec=%s, scale=%d)%n",
                encoder.sourceBlockCount(),
                encoder.frameCount(),
                EmitterDefaults.HEADLESS_CHUNK_LEN,
                EmitterDefaults.REDUNDANCY,
                EmitterDefaults.EC,
                EmitterDefaults.SCALE);

        int written;
        try {
            written = HeadlessEmitter.writeFrames(
                    encoder, renderer, options.headlessDir, options.maxFrames);
        } catch (IOException e) {
            System.err.println("error: failed to write frames: " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.printf(
                "Wrote %d PNG frame(s) to %s%n",
                written, options.headlessDir.toAbsolutePath());
    }
}
