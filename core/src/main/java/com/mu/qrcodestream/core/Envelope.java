package com.mu.qrcodestream.core;

/** A parsed {@code AQRC1} integrity envelope. */
public final class Envelope {

    private final int originalLength;
    private final String sha256;
    private final byte[] data;

    public Envelope(int originalLength, String sha256, byte[] data) {
        this.originalLength = originalLength;
        this.sha256 = sha256;
        this.data = data;
    }

    public int getOriginalLength() {
        return originalLength;
    }

    public String getSha256() {
        return sha256;
    }

    public byte[] getData() {
        return data;
    }

    /** Verifies that the decoded length and SHA-256 both match the declared values. */
    public boolean verify() {
        return data != null
                && data.length == originalLength
                && Protocol.sha256Hex(data).equalsIgnoreCase(sha256);
    }
}
