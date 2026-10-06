package com.gatepulse.metrics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyHistogramTest {

    @Test
    void everyValueFallsInsideItsBucketBounds() {
        for (long v = 0; v < 200_000; v += (v < 1000 ? 1 : 97)) {
            int index = LatencyHistogram.bucketIndex(v);
            long upper = LatencyHistogram.upperBoundMicros(index);
            long lower = index == 0 ? 0 : LatencyHistogram.upperBoundMicros(index - 1) + 1;
            assertThat(v).as("value %d in bucket %d [%d, %d]", v, index, lower, upper).isBetween(lower, upper);
        }
    }

    @Test
    void bucketsAreContiguousAndRelativeErrorIsBounded() {
        for (int i = 1; i < LatencyHistogram.BUCKET_COUNT; i++) {
            long lower = LatencyHistogram.upperBoundMicros(i - 1) + 1;
            long upper = LatencyHistogram.upperBoundMicros(i);
            assertThat(upper).isGreaterThanOrEqualTo(lower);
            if (lower >= 16) {
                assertThat((double) (upper - lower + 1) / lower).as("bucket %d width", i).isLessThanOrEqualTo(1.0 / 16);
            }
        }
        assertThat(LatencyHistogram.upperBoundMicros(LatencyHistogram.BUCKET_COUNT - 1))
                .isGreaterThanOrEqualTo(LatencyHistogram.MAX_MICROS);
    }

    @Test
    void percentilesMatchExactValuesWithinBucketPrecision() {
        Random random = new Random(42);
        long[] samples = new long[10_000];
        LatencyHistogram histogram = new LatencyHistogram();
        for (int i = 0; i < samples.length; i++) {
            // Mostly fast with a long tail, like real traffic.
            samples[i] = random.nextDouble() < 0.9 ? 500 + random.nextInt(4_500) : 20_000 + random.nextInt(180_000);
            histogram.record(samples[i]);
        }
        Arrays.sort(samples);
        long[] counts = new long[LatencyHistogram.BUCKET_COUNT];
        histogram.addTo(counts);

        for (double p : new double[]{0.50, 0.95, 0.99}) {
            long exact = samples[(int) Math.ceil(p * samples.length) - 1];
            long estimate = LatencyHistogram.percentileMicros(counts, p);
            assertThat(estimate).as("p%.0f", p * 100).isGreaterThanOrEqualTo(exact);
            assertThat((double) (estimate - exact) / exact).as("p%.0f relative error", p * 100).isLessThanOrEqualTo(0.0625);
        }
    }

    @Test
    void emptyHistogramReportsZeroAndHugeValuesAreClamped() {
        long[] counts = new long[LatencyHistogram.BUCKET_COUNT];
        assertThat(LatencyHistogram.percentileMicros(counts, 0.99)).isZero();

        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(Long.MAX_VALUE);
        histogram.record(-5);
        histogram.addTo(counts);
        assertThat(counts[0]).isEqualTo(1);
        assertThat(counts[LatencyHistogram.BUCKET_COUNT - 1]).isEqualTo(1);
    }
}
