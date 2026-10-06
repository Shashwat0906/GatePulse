package com.gatepulse.metrics;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A fixed-size, lock-free latency histogram with log-linear buckets (the idea behind
 * HdrHistogram, in ~50 lines).
 *
 * <h2>Why not just store every latency and sort?</h2>
 * At 2,000 req/s that is 120,000 numbers a minute to keep and sort for every percentile
 * query. A histogram stores a <em>count per latency range</em> instead: recording is one atomic
 * increment, memory is fixed (368 counters), and a percentile is one pass over the counters.
 *
 * <h2>Bucket layout</h2>
 * Values are microseconds. 0-15us get one bucket each. Above that, every power of two
 * [2^k, 2^(k+1)) is split into 16 equal sub-buckets. So the bucket width grows with the value
 * and the relative error stays under 1/16 = 6.25% everywhere, from 16us to 60s. A p99 of
 * "12.0ms" really means "between 11.3 and 12.0ms", plenty precise for a dashboard.
 *
 * <p>Percentiles report the bucket's upper bound, so they never understate latency.
 */
public final class LatencyHistogram {

    private static final int SUB_BUCKET_BITS = 4;
    private static final int SUB_BUCKETS = 1 << SUB_BUCKET_BITS;

    /** Values above this are clamped (60 seconds). */
    public static final long MAX_MICROS = 60_000_000L;

    public static final int BUCKET_COUNT = bucketIndex(MAX_MICROS) + 1;

    private final AtomicLongArray counts = new AtomicLongArray(BUCKET_COUNT);

    public void record(long micros) {
        counts.incrementAndGet(bucketIndex(Math.min(Math.max(micros, 0), MAX_MICROS)));
    }

    /** Adds this histogram's counts into {@code accumulator} (length {@link #BUCKET_COUNT}). */
    public void addTo(long[] accumulator) {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            accumulator[i] += counts.get(i);
        }
    }

    static int bucketIndex(long micros) {
        if (micros < SUB_BUCKETS) {
            return (int) micros;
        }
        int highestBit = 63 - Long.numberOfLeadingZeros(micros); // >= SUB_BUCKET_BITS here
        int shift = highestBit - SUB_BUCKET_BITS;
        int sub = (int) ((micros >>> shift) & (SUB_BUCKETS - 1));
        return SUB_BUCKETS + shift * SUB_BUCKETS + sub;
    }

    /** Largest value (inclusive) that falls into bucket {@code index}. */
    static long upperBoundMicros(int index) {
        if (index < SUB_BUCKETS) {
            return index;
        }
        int shift = (index - SUB_BUCKETS) / SUB_BUCKETS;
        int sub = (index - SUB_BUCKETS) % SUB_BUCKETS;
        return ((long) (SUB_BUCKETS + sub + 1) << shift) - 1;
    }

    /**
     * The value at percentile {@code p} (0 &lt; p &lt;= 1) of the distribution in {@code counts}.
     *
     * @return microseconds, or 0 if there are no samples
     */
    public static long percentileMicros(long[] counts, double p) {
        long total = 0;
        for (long c : counts) {
            total += c;
        }
        if (total == 0) {
            return 0;
        }
        long rank = Math.max(1, (long) Math.ceil(p * total)); // 1-based rank of the sample we want
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= rank) {
                return upperBoundMicros(i);
            }
        }
        return upperBoundMicros(counts.length - 1);
    }
}
