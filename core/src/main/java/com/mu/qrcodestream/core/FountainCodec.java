package com.mu.qrcodestream.core;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic neighbour selection for LT (Luby Transform) fountain codes. Given a block
 * code, both the encoder and the decoder derive exactly the same set of source-block
 * indices, so the encoded data is just the XOR of those blocks.
 */
final class FountainCodec {

    private final int sourceBlocks;
    private final SolitonDistribution distribution;

    FountainCodec(int sourceBlocks) {
        this.sourceBlocks = sourceBlocks;
        this.distribution = new SolitonDistribution(sourceBlocks);
    }

    int sourceBlocks() {
        return sourceBlocks;
    }

    int[] neighbors(long blockCode) {
        // Systematic prefix: the first K block codes are the raw source blocks, so the receiver
        // solves one block per frame and progress is linear from the very start.
        if (blockCode >= 0 && blockCode < sourceBlocks) {
            return new int[] {(int) blockCode};
        }

        Random random = new Random(mix(blockCode));
        int degree = distribution.sampleDegree(random);
        int[] result = new int[degree];
        if (degree == 1) {
            result[0] = random.nextInt(sourceBlocks);
            return result;
        }

        // Floyd's algorithm for sampling `degree` distinct indices from [0, sourceBlocks).
        Set<Integer> chosen = new HashSet<>(degree * 2);
        int offset = sourceBlocks - degree;
        for (int j = offset; j < sourceBlocks; j++) {
            int t = random.nextInt(j + 1);
            int value = chosen.contains(t) ? j : t;
            chosen.add(value);
            result[j - offset] = value;
        }
        return result;
    }

    private long mix(long blockCode) {
        long x = Protocol.FOUNTAIN_SEED * 0x9E3779B97F4A7C15L + blockCode;
        x ^= (x >>> 33);
        x *= 0xFF51AFD7ED558CCDL;
        x ^= (x >>> 33);
        return x;
    }
}
