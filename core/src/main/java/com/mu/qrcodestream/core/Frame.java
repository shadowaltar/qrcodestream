package com.mu.qrcodestream.core;

/** A parsed protocol frame: {@code blockCode/chunkLen/total|base64(data)}. */
public final class Frame {

    private final long blockCode;
    private final int chunkLen;
    private final int total;
    private final byte[] data;

    public Frame(long blockCode, int chunkLen, int total, byte[] data) {
        this.blockCode = blockCode;
        this.chunkLen = chunkLen;
        this.total = total;
        this.data = data;
    }

    public long getBlockCode() {
        return blockCode;
    }

    public int getChunkLen() {
        return chunkLen;
    }

    public int getTotal() {
        return total;
    }

    public byte[] getData() {
        return data;
    }
}
