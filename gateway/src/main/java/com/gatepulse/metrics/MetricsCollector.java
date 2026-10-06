package com.gatepulse.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Collects request metrics with no locks on the request path.
 *
 * <h2>Two kinds of data</h2>
 * <ul>
 *   <li><b>Lifetime totals</b> (total requests, status-code counts): {@link LongAdder}s, which
 *       stay fast when many threads increment at once.</li>
 *   <li><b>Per-second buckets</b> for the last {@value #RING_SECONDS} seconds: request count,
 *       errors, 429s, cache hits, and a {@link LatencyHistogram}. These give requests/sec, the
 *       live charts, and percentiles over a sliding window.</li>
 * </ul>
 *
 * <h2>The ring of seconds</h2>
 * Second {@code s} lives in slot {@code s % RING_SECONDS}. When a request arrives in a new
 * second, the first thread to notice installs a fresh bucket with compare-and-set, replacing
 * the one from {@value #RING_SECONDS} seconds ago. Old data ages out by itself; no cleanup
 * thread, no lock. A sliding-window query just reads the last N slots whose second matches.
 */
public final class MetricsCollector {

    public static final int RING_SECONDS = 120;

    /** Counters for one wall-clock second. */
    static final class SecondBucket {
        final long second;
        final LongAdder requests = new LongAdder();
        final LongAdder serverErrors = new LongAdder();
        final LongAdder rateLimited = new LongAdder();
        final LongAdder cacheHits = new LongAdder();
        final LatencyHistogram latency = new LatencyHistogram();

        SecondBucket(long second) {
            this.second = second;
        }
    }

    private final LongSupplier epochMillis;
    private final long startedAtMillis;
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder totalServerErrors = new LongAdder();
    private final LongAdder totalRateLimited = new LongAdder();
    private final Map<Integer, LongAdder> statusCounts = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicReferenceArray<SecondBucket> ring = new AtomicReferenceArray<>(RING_SECONDS);
    private final RequestLog requestLog;

    public MetricsCollector() {
        this(System::currentTimeMillis, 1024);
    }

    public MetricsCollector(LongSupplier epochMillis, int requestLogCapacity) {
        this.epochMillis = epochMillis;
        this.startedAtMillis = epochMillis.getAsLong();
        this.requestLog = new RequestLog(requestLogCapacity);
    }

    // ---------------------------------------------------------------- recording (hot path)

    public void requestStarted() {
        inFlight.incrementAndGet();
    }

    /**
     * Records a finished request. Must be paired with an earlier {@link #requestStarted()}.
     *
     * @param latencyMicros time from arrival to response
     * @param cacheHit      whether the response came from the cache
     */
    public void requestFinished(RequestRecord record, long latencyMicros, boolean cacheHit) {
        inFlight.decrementAndGet();
        int status = record.status();
        boolean serverError = status >= 500;
        boolean rateLimited = status == 429;

        totalRequests.increment();
        statusCounts.computeIfAbsent(status, s -> new LongAdder()).increment();
        if (serverError) {
            totalServerErrors.increment();
        }
        if (rateLimited) {
            totalRateLimited.increment();
        }

        SecondBucket bucket = bucketFor(record.timestamp() / 1000);
        if (bucket != null) {
            bucket.requests.increment();
            bucket.latency.record(latencyMicros);
            if (serverError) {
                bucket.serverErrors.increment();
            }
            if (rateLimited) {
                bucket.rateLimited.increment();
            }
            if (cacheHit) {
                bucket.cacheHits.increment();
            }
        }
        requestLog.add(record);
    }

    /** The bucket for {@code second}, creating it if needed; {@code null} for a stale second. */
    private SecondBucket bucketFor(long second) {
        int slot = (int) Math.floorMod(second, (long) RING_SECONDS);
        while (true) {
            SecondBucket existing = ring.get(slot);
            if (existing != null && existing.second == second) {
                return existing;
            }
            if (existing != null && existing.second > second) {
                return null; // a very late writer for a second that has been recycled: drop it
            }
            SecondBucket fresh = new SecondBucket(second);
            if (ring.compareAndSet(slot, existing, fresh)) {
                return fresh;
            }
            // Another thread installed a bucket first; loop and use theirs.
        }
    }

    private SecondBucket bucketIfCurrent(long second) {
        SecondBucket bucket = ring.get((int) Math.floorMod(second, (long) RING_SECONDS));
        return bucket != null && bucket.second == second ? bucket : null;
    }

    // ---------------------------------------------------------------- queries

    public long nowMillis() {
        return epochMillis.getAsLong();
    }

    public long uptimeSeconds() {
        return (epochMillis.getAsLong() - startedAtMillis) / 1000;
    }

    public long totalRequests() {
        return totalRequests.sum();
    }

    public long totalServerErrors() {
        return totalServerErrors.sum();
    }

    public long totalRateLimited() {
        return totalRateLimited.sum();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public RequestLog requestLog() {
        return requestLog;
    }

    /** Lifetime count per status code, sorted by code. */
    public Map<Integer, Long> statusCounts() {
        Map<Integer, Long> result = new TreeMap<>();
        statusCounts.forEach((code, count) -> result.put(code, count.sum()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Average requests per second over the last {@code seconds} <em>completed</em> seconds.
     * The current second is excluded because it is only partly over.
     */
    public double requestsPerSecond(int seconds) {
        long current = epochMillis.getAsLong() / 1000;
        long sum = 0;
        for (long s = current - seconds; s < current; s++) {
            SecondBucket bucket = bucketIfCurrent(s);
            if (bucket != null) {
                sum += bucket.requests.sum();
            }
        }
        return (double) sum / seconds;
    }

    /** Fraction of requests answered 5xx over the last {@code seconds} (including the current one). */
    public double errorRate(int seconds) {
        long current = epochMillis.getAsLong() / 1000;
        long requests = 0;
        long errors = 0;
        for (long s = current - seconds + 1; s <= current; s++) {
            SecondBucket bucket = bucketIfCurrent(s);
            if (bucket != null) {
                requests += bucket.requests.sum();
                errors += bucket.serverErrors.sum();
            }
        }
        return requests == 0 ? 0 : (double) errors / requests;
    }

    /** p50/p95/p99 over the last {@code seconds}, merged from the per-second histograms. */
    public LatencySummary latency(int seconds) {
        long current = epochMillis.getAsLong() / 1000;
        long[] merged = new long[LatencyHistogram.BUCKET_COUNT];
        long samples = 0;
        for (long s = current - seconds + 1; s <= current; s++) {
            SecondBucket bucket = bucketIfCurrent(s);
            if (bucket != null) {
                bucket.latency.addTo(merged);
                samples += bucket.requests.sum();
            }
        }
        return LatencySummary.from(merged, samples, seconds);
    }

    /** One point per second for the last {@code seconds} completed seconds, oldest first. */
    public List<TimelinePoint> timeline(int seconds) {
        int window = Math.min(seconds, RING_SECONDS - 1);
        long current = epochMillis.getAsLong() / 1000;
        List<TimelinePoint> points = new ArrayList<>(window);
        for (long s = current - window; s < current; s++) {
            SecondBucket bucket = bucketIfCurrent(s);
            if (bucket == null) {
                points.add(TimelinePoint.empty(s * 1000));
                continue;
            }
            long[] hist = new long[LatencyHistogram.BUCKET_COUNT];
            bucket.latency.addTo(hist);
            points.add(new TimelinePoint(
                    s * 1000,
                    bucket.requests.sum(),
                    bucket.serverErrors.sum(),
                    bucket.rateLimited.sum(),
                    bucket.cacheHits.sum(),
                    LatencySummary.toMillis(LatencyHistogram.percentileMicros(hist, 0.50)),
                    LatencySummary.toMillis(LatencyHistogram.percentileMicros(hist, 0.95)),
                    LatencySummary.toMillis(LatencyHistogram.percentileMicros(hist, 0.99))));
        }
        return points;
    }

    /** Requests/sec, errors, 429s, cache hits and latency percentiles for one second. */
    public record TimelinePoint(long t, long requests, long errors, long rateLimited, long cacheHits,
                                double p50, double p95, double p99) {
        static TimelinePoint empty(long t) {
            return new TimelinePoint(t, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** Latency percentiles in milliseconds over a window. */
    public record LatencySummary(double p50, double p95, double p99, long samples, int windowSeconds) {

        static LatencySummary from(long[] histogram, long samples, int windowSeconds) {
            return new LatencySummary(
                    toMillis(LatencyHistogram.percentileMicros(histogram, 0.50)),
                    toMillis(LatencyHistogram.percentileMicros(histogram, 0.95)),
                    toMillis(LatencyHistogram.percentileMicros(histogram, 0.99)),
                    samples,
                    windowSeconds);
        }

        static double toMillis(long micros) {
            return Math.round(micros / 10.0) / 100.0; // two decimals
        }
    }
}
