package com.mu.qrcodestream.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LT-fountain decoder. Feed decoded frames in any order, with any losses; once enough distinct
 * frames have arrived, the payload is recovered.
 *
 * <p>Uses a ripple/belief-propagation update: each pending frame is indexed by the source blocks
 * it still references, so solving one block only touches the frames that contain it. This is
 * O(total neighbours) overall rather than rescanning every pending frame per received frame,
 * which matters for large files (large K).
 */
public final class FountainDecoder {

    private final int chunkLen;
    private final int total;
    private final int sourceBlockCount;
    private final FountainCodec codec;
    private final byte[][] solved;
    private final boolean[] hasSolved;
    private final Set<Long> seen = new HashSet<>();
    private final List<Pending>[] adjacency;
    private final Deque<Pending> ready = new ArrayDeque<>();
    private int solvedCount;

    private static final class Pending {
        int[] indices;
        byte[] data;
        int unsolved;
        boolean done;
        boolean queued;
    }

    @SuppressWarnings("unchecked")
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
        this.adjacency = new List[sourceBlockCount];
        for (int i = 0; i < sourceBlockCount; i++) {
            adjacency[i] = new ArrayList<>();
        }
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
        int[] unsolvedIndices = new int[neighbors.length];
        int unknown = 0;
        for (int index : neighbors) {
            if (hasSolved[index]) {
                xor(data, solved[index]);
            } else {
                unsolvedIndices[unknown++] = index;
            }
        }
        if (unknown == 0) {
            return true;
        }

        Pending block = new Pending();
        block.indices = Arrays.copyOf(unsolvedIndices, unknown);
        block.data = data;
        block.unsolved = unknown;
        for (int index : block.indices) {
            adjacency[index].add(block);
        }
        if (unknown == 1) {
            block.queued = true;
            ready.add(block);
        }
        drainReady();
        return true;
    }

    private void drainReady() {
        while (!ready.isEmpty()) {
            Pending block = ready.poll();
            if (block.done) {
                continue;
            }
            int index = -1;
            for (int candidate : block.indices) {
                if (!hasSolved[candidate]) {
                    index = candidate;
                    break;
                }
            }
            if (index < 0) {
                block.done = true;
                continue;
            }

            solved[index] = block.data;
            hasSolved[index] = true;
            solvedCount++;
            block.done = true;

            for (Pending neighbor : adjacency[index]) {
                if (neighbor.done) {
                    continue;
                }
                xor(neighbor.data, solved[index]);
                neighbor.unsolved--;
                if (neighbor.unsolved == 1 && !neighbor.queued) {
                    neighbor.queued = true;
                    ready.add(neighbor);
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
