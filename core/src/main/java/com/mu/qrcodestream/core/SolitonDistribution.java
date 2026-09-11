package com.mu.qrcodestream.core;

import java.util.Random;

/**
 * Robust Soliton Distribution (RSD) over degrees {@code 1..k}. Both encoder and decoder
 * build the distribution from the same {@code k}, so sampling is identical on both ends.
 */
final class SolitonDistribution {

    private static final double C = 0.1;
    private static final double DELTA = 0.5;

    private final int k;
    private final double[] cdf;

    SolitonDistribution(int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1");
        }
        this.k = k;
        this.cdf = new double[k];
        build();
    }

    private void build() {
        if (k == 1) {
            cdf[0] = 1.0;
            return;
        }

        double[] rho = new double[k + 1];
        double[] tau = new double[k + 1];

        rho[1] = 1.0 / k;
        for (int d = 2; d <= k; d++) {
            rho[d] = 1.0 / (d * (double) (d - 1));
        }

        double r = C * Math.log(k / DELTA) * Math.sqrt(k);
        int spike = (int) (k / r);
        if (spike < 1) {
            spike = 1;
        }
        if (spike > k) {
            spike = k;
        }
        for (int d = 1; d < spike; d++) {
            tau[d] = r / (d * (double) k);
        }
        double logTerm = Math.log(r / DELTA);
        if (logTerm > 0) {
            tau[spike] += r * logTerm / k;
        }

        double z = 0.0;
        for (int d = 1; d <= k; d++) {
            z += rho[d] + tau[d];
        }
        if (z <= 0.0) {
            // Degenerate fallback: uniform over 1..k.
            for (int d = 1; d <= k; d++) {
                cdf[d - 1] = d / (double) k;
            }
            return;
        }

        double cum = 0.0;
        for (int d = 1; d <= k; d++) {
            cum += (rho[d] + tau[d]) / z;
            cdf[d - 1] = cum;
        }
        cdf[k - 1] = 1.0;
    }

    int sampleDegree(Random random) {
        if (k == 1) {
            return 1;
        }
        double u = random.nextDouble();
        for (int d = 1; d <= k; d++) {
            if (u <= cdf[d - 1]) {
                return d;
            }
        }
        return k;
    }
}
