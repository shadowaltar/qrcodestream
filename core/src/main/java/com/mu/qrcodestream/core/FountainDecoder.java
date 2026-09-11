package com.mu.qrcodestream.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LT-fountain decoder. Feed decoded frames in any order, with any losses; once slightly
 * more than the number of source blocks have been received, the payload is recovered.
 */
public final class FountainDecoder {

    private final int chunkLen;
    private final int total;
    private final int sourceBlockCount;
    private final FountainCodec codec;
    private final byte[][] solved;
    private final boolean[] hasSolved;
    private final Set<Long> seen = new HashSet<>();
    private final List<Pending> pending = new ArrayList<>();
    private int solvedCount;

    private static final class Pending {
        int[] indices;
        byte[] data;
        boolean done;
    }

    public FountainDecoder(int chunkLen, int total) {
        if (chunkLen <= 0 || total < 0) {
            throw new IllegalArgumentException("invalid chunkLen/total");
        }
        this.chunkLen = chunkLen;
        this.total = total;
        this.sourceBlockCount = Math.max(1, (int) Math.ceil(total / (double) chunkLen));
        this.codec = new FountainCodec(sourceBlockCount);
        this.solved = new byte[sourceBlockCount][];
        this.hasSolved = new boolean[sourceBlockCount];
    }

    /** Returns {@code true} if the frame was accepted (it is a new, matching frame). */
    public boolean add(Frame frame) {
        if (frame == null
                || frame.getChunkLen() != chunkLen
                || frame.getTotal() != total
                || frame.getData() == null) {
            return false;
        }
        if (!seen.add(frame.getBlockCode())) {
            return false;
        }

        int[] neighbors = codec.neighbors(frame.getBlockCode());
        byte[] data = frame.getData().clone();
        List<Integer> unknown = new ArrayList<>(neighbors.length);
        for (int index : neighbors) {
            if (hasSolved[index]) {
                xor(data, solved[index]);
            } else {
                unknown.add(index);
            }
        }
        if (unknown.isEmpty()) {
            return true;
        }

        Pending block = new Pending();
        block.indices = new int[unknown.size()];
        for (int i = 0; i < unknown.size(); i++) {
            block.indices[i] = unknown.get(i);
        }
        block.data = data;
        pending.add(block);
        resolve();
        return true;
    }

    private void resolve() {
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Pending block : pending) {
                if (block.done) {
                    continue;
                }
                int unknownCount = 0;
                int unknownIndex = -1;
                for (int index : block.indices) {
                    if (!hasSolved[index]) {
                        unknownCount++;
                        unknownIndex = index;
                        if (unknownCount > 1) {
                            break;
                        }
                    }
                }
                if (unknownCount == 0) {
                    block.done = true;
                } else if (unknownCount == 1) {
                    byte[] value = block.data.clone();
                    for (int index : block.indices) {
                        if (index != unknownIndex) {
                            xor(value, solved[index]);
                        }
                    }
                    solved[unknownIndex] = value;
                    hasSolved[unknownIndex] = true;
                    solvedCount++;
                    block.done = true;
                    progress = true;
                }
            }
        }
    }

    public boolean isComplete() {
        return solvedCount == sourceBlockCount;
    }

    /** Progress as a fraction in [0, 1]. */
    public double progress() {
        return sourceBlockCount == 0 ? 1.0 : solvedCount / (double) sourceBlockCount;
    }

    public int solvedCount() {
        return solvedCount;
    }

    public int sourceBlockCount() {
        return sourceBlockCount;
    }

    public int receivedCount() {
        return seen.size();
    }

    public int totalLength() {
        return total;
    }

    /** Returns the recovered payload bytes, or {@code null} if not yet complete. */
    public byte[] payload() {
        if (!isComplete()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(sourceBlockCount * chunkLen);
        for (byte[] block : solved) {
            out.write(block, 0, block.length);
        }
        byte[] all = out.toByteArray();
        byte[] result = new byte[total];
        System.arraycopy(all, 0, result, 0, total);
        return result;
    }

    private static void xor(byte[] target, byte[] source) {
        for (int i = 0; i < target.length; i++) {
            target[i] ^= source[i];
        }
    }
}
