package com.mu.qrcodestream.emitter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Parsed command-line options for the emitter. The command line is intentionally tiny: normal
 * use is the interactive window, while the CLI exists to pre-select a file and to drive the
 * headless PNG-sequence mode. Tuning knobs (QR version, frame rate) live in the UI.
 */
public final class CliOptions {

    private static final Set<String> KNOWN = Set.of("--input", "--headless", "--frames", "--help");

    /** File to pre-select in the UI; required for headless mode. */
    public Path input;

    /** If non-null, write PNG frames here instead of opening a window. */
    public Path headlessDir;

    /** Optional cap on frames written in headless mode; {@code -1} means all. */
    public int maxFrames = -1;

    /** True when {@code --help} was requested. */
    public boolean help;

    private CliOptions() {}

    public static CliOptions parse(String[] args) {
        CliOptions options = new CliOptions();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            String key = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 2) {
                key = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }

            if ("--help".equals(key)) {
                options.help = true;
                i++;
                continue;
            }
            if (!KNOWN.contains(key)) {
                throw new IllegalArgumentException("Unknown option: " + key);
            }
            if (value == null) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for option: " + key);
                }
                value = args[++i];
            }
            i++;

            switch (key) {
                case "--input":
                    options.input = Paths.get(value);
                    break;
                case "--headless":
                    options.headlessDir = Paths.get(value);
                    break;
                case "--frames":
                    options.maxFrames = parseInt(key, value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option: " + key);
            }
        }
        options.validate();
        return options;
    }

    private void validate() {
        if (maxFrames == 0) {
            throw new IllegalArgumentException("--frames must be >= 1");
        }
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + value);
        }
    }

    public static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: emitter [--input <path>] [--headless <dir> [--frames <n>]]",
                "",
                "With no --headless an interactive window opens; use the Open/Start/Stop buttons",
                "and the QR version / FPS sliders.",
                "",
                "Options:",
                "  --input <path>    Pre-select a file in the UI (required in headless mode)",
                "  --headless <dir>  Write numbered PNG frames to <dir> instead of a window",
                "  --frames <int>    Cap frame count in headless mode (default: all)",
                "  --help            Show this help");
    }
}
