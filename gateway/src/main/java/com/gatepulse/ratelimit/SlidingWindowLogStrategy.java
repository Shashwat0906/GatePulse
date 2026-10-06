package com.gatepulse.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding window log: remember the timestamp of every allowed request per client, and allow a
 * new request only if fewer than {@code limit} timestamps fall within the last
 * {@code window}.
 *
 * <h2>Trade-off vs token bucket</h2>
 * It is exact: there is never a moment where a client exceeds {@code limit} requests in any
 * window-sized period (a fixed-window counter can allow 2x at a window boundary). The price is
 * memory: up to {@code limit} timestamps per active client, versus two numbers for a token bucket.
 *
 * <h2>Concurrency</h2>
 * Each client has its own small lock (the {@link Window} monitor). Contention only happens
 * between requests from the same client; different clients never block each other, and there
 * is no global lock. Eviction uses the same "retire, then remove" handshake as
 * {@link TokenBucketStrategy}.
 */
public final class SlidingWindowLogStrategy implements RateLimitStrategy {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final long windowNanos;

    public SlidingWindowLogStrategy(int limit, long windowMillis) {
        if (limit < 1 || windowMillis < 1) {
            throw new IllegalArgumentException("limit and windowMillis must be >= 1");
        }
        this.limit = limit;
        this.windowNanos = windowMillis * 1_000_000L;
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.SLIDING_WINDOW_LOG;
    }

    @Override
    public RateLimitDecision tryAcquire(String clientKey, long nowNanos) {
        while (true) {
            Window window = windows.computeIfAbsent(clientKey, k -> new Window());
            RateLimitDecision decision;
            synchronized (window) {
                decision = window.retired ? null : decide(window, nowNanos);
            }
            if (decision != null) {
                return decision;
            }
            windows.remove(clientKey, window);
        }
    }

    /** Must be called while holding the window's lock. */
    private RateLimitDecision decide(Window window, long nowNanos) {
        LongRing log = window.log;
        log.removeUpTo(nowNanos - windowNanos);
        if (log.size() < limit) {
            log.add(nowNanos);
            return RateLimitDecision.allow(limit, limit - log.size());
        }
        // Full: the next slot frees up when the oldest timestamp slides out of the window.
        long waitNanos = log.first() + windowNanos - nowNanos;
        return RateLimitDecision.reject(limit, Math.max(1, (waitNanos + 999_999) / 1_000_000));
    }

    @Override
    public int evictIdle(long nowNanos) {
        int removed = 0;
        for (Map.Entry<String, Window> entry : windows.entrySet()) {
            Window window = entry.getValue();
            boolean retire;
            synchronized (window) {
                window.log.removeUpTo(nowNanos - windowNanos);
                retire = !window.retired && window.log.size() == 0;
                if (retire) {
                    window.retired = true;
                }
            }
            if (retire) {
                windows.remove(entry.getKey(), window);
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int trackedClients() {
        return windows.size();
    }

    /** Per-client state. Guarded by its own monitor. */
    private static final class Window {
        final LongRing log = new LongRing();
        boolean retired;
    }

    /**
     * A growable ring buffer of primitive {@code long}s in ascending order. Avoids boxing every
     * timestamp into a {@code Long} (an {@code ArrayDeque<Long>} costs ~4x the memory).
     * Not thread-safe; callers hold the window lock.
     */
    static final class LongRing {
        private long[] buffer = new long[8];
        private int head;
        private int size;

        int size() {
            return size;
        }

        long first() {
            if (size == 0) {
                throw new IllegalStateException("empty");
            }
            return buffer[head];
        }

        void add(long value) {
            if (size == buffer.length) {
                grow();
            }
            buffer[(head + size) % buffer.length] = value;
            size++;
        }

        /** Drops every value {@code <= cutoff} from the front. */
        void removeUpTo(long cutoff) {
            while (size > 0 && buffer[head] <= cutoff) {
                head = (head + 1) % buffer.length;
                size--;
            }
            if (size == 0) {
                head = 0;
                if (buffer.length > 64) {
                    buffer = new long[8]; // release memory after a burst
                }
            }
        }

        private void grow() {
            long[] bigger = new long[buffer.length * 2];
            for (int i = 0; i < size; i++) {
                bigger[i] = buffer[(head + i) % buffer.length];
            }
            buffer = bigger;
            head = 0;
        }
    }
}
