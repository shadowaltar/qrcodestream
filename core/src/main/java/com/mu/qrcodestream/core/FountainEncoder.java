package com.mu.qrcodestream.core;

/**
 * Encodes a payload (the {@code AQRC1} envelope) into an infinite, looping stream of
 * LT-fountain-coded frames.
 */
public final class FountainEncoder {

    private final int chunkLen;
    private final int total;
    private final int sourceBlockCount;
    private final int frameCount;
    private final byte[][] sourceBlocks;
    private final FountainCodec codec;

    public FountainEncoder(String payload, int chunkLen, double redundancy) {
        if (chunkLen <= 0) {
            throw new IllegalArgumentException("chunkLen must be > 0");
        }
        this.chunkLen = chunkLen;
        byte[] bytes = Protocol.utf8(payload);
        this.total = bytes.length;
        this.sourceBlockCount = Math.max(1, (int) Math.ceil(total / (double) chunkLen));
        this.codec = new FountainCodec(sourceBlockCount);
        this.sourceBlocks = split(bytes, chunkLen, sourceBlockCount);
        this.frameCount = Math.max(
                sourceBlockCount,
                (int) Math.ceil(sourceBlockCount * Math.max(1.0, redundancy)));
    }

    public static FountainEncoder forFile(byte[] fileBytes, int chunkLen, double redundancy) {
        return new FountainEncoder(Protocol.buildEnvelope(fileBytes), chunkLen, redundancy);
    }

    public int chunkLen() {
        return chunkLen;
    }

    public int totalLength() {
        return total;
    }

    public int sourceBlockCount() {
        return sourceBlockCount;
    }

    /** Number of distinct encoded frames generated; the emitter loops over these. */
    public int frameCount() {
        return frameCount;
    }

    public byte[] dataForFrame(int index) {
        int[] neighbors = codec.neighbors(index);
        byte[] out = new byte[chunkLen];
        for (int n : neighbors) {
            xorInto(out, sourceBlocks[n]);
        }
        return out;
    }

    public String frameAt(int index) {
        return Protocol.buildFrame(index, chunkLen, total, dataForFrame(index));
    }

    private static byte[][] split(byte[] payload, int chunkLen, int blocks) {
        byte[][] result = new byte[blocks][];
        for (int i = 0; i < blocks; i++) {
            int offset = i * chunkLen;
            int len = Math.min(chunkLen, payload.length - offset);
            byte[] block = new byte[chunkLen];
            System.arraycopy(payload, offset, block, 0, len);
            result[i] = block;
        }
        return result;
    }

    private static void xorInto(byte[] target, byte[] source) {
        for (int i = 0; i < target.length; i++) {
            target[i] ^= source[i];
        }
    }
}
