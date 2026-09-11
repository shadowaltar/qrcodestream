package com.mu.qrcodestream.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Wire protocol shared by the Java emitter and the Android receiver.
 *
 * <p>Two layers are defined here:
 *
 * <ul>
 *   <li><b>Frame</b>: {@code <blockCode>/<chunkLen>/<total>|<base64(data)>} — displayed
 *       as a single QR code. This is TXQR-compatible framing.
 *   <li><b>Envelope</b>: {@code AQRC1|<origLen>|<sha256hex>|<base64(fileBytes)>} — the
 *       payload that gets fountain-encoded. Adds integrity verification.
 * </ul>
 */
public final class Protocol {

    private Protocol() {}

    /** Magic/version tag for the integrity envelope. */
    public static final String MAGIC = "AQRC1";

    /** QR bytes per frame. TXQR's best measured value. */
    public static final int DEFAULT_CHUNK_LEN = 1850;

    /** Fountain redundancy factor (systematic prefix makes K frames enough when lossless). */
    public static final double DEFAULT_REDUNDANCY = 2.5;

    /** Emitter default frame rate. */
    public static final int DEFAULT_FPS = 15;

    /** Fixed seed mixed into the fountain-code neighbour selection. */
    public static final long FOUNTAIN_SEED = 0x51EEDL;

    // ------------------------------------------------------------------
    // Base64 (JDK/Android API 26+)
    // ------------------------------------------------------------------

    public static String toBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] fromBase64(String text) {
        return Base64.getDecoder().decode(text);
    }

    // ------------------------------------------------------------------
    // Integrity
    // ------------------------------------------------------------------

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    // ------------------------------------------------------------------
    // Envelope
    // ------------------------------------------------------------------

    public static String buildEnvelope(byte[] fileBytes) {
        return MAGIC
                + "|" + fileBytes.length
                + "|" + sha256Hex(fileBytes)
                + "|" + toBase64(fileBytes);
    }

    public static Envelope parseEnvelope(String payload) {
        String[] parts = payload.split("\\|", 4);
        if (parts.length != 4 || !MAGIC.equals(parts[0])) {
            throw new IllegalArgumentException("Not an " + MAGIC + " payload");
        }
        int originalLength = Integer.parseInt(parts[1]);
        String sha256 = parts[2];
        byte[] data = fromBase64(parts[3]);
        return new Envelope(originalLength, sha256, data);
    }

    // ------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------

    public static String buildFrame(long blockCode, int chunkLen, int total, byte[] data) {
        return blockCode + "/" + chunkLen + "/" + total + "|" + toBase64(data);
    }

    /** Returns {@code null} when {@code text} is not a valid protocol frame. */
    public static Frame parseFrame(String text) {
        if (text == null) {
            return null;
        }
        int bar = text.indexOf('|');
        if (bar < 0) {
            return null;
        }
        String[] header = text.substring(0, bar).split("/");
        if (header.length != 3) {
            return null;
        }
        try {
            long blockCode = Long.parseLong(header[0]);
            int chunkLen = Integer.parseInt(header[1]);
            int total = Integer.parseInt(header[2]);
            byte[] data = fromBase64(text.substring(bar + 1));
            return new Frame(blockCode, chunkLen, total, data);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
