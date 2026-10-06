package com.gatepulse.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token bucket: each client has a bucket holding up to {@code capacity} tokens. A request
 * takes one token; tokens drip back in at {@code refillPerSecond}. So a client may burst up
 * to {@code capacity} requests at once, and sustain {@code refillPerSecond} on average.
 *
 * <h2>Lazy refill</h2>
 * No background thread adds tokens. A bucket stores (tokens, lastRefillTime); on each request
 * we compute how many tokens would have dripped in since then. O(1) per request, zero cost
 * for idle clients.
 *
 * <h2>Lock-free</h2>
 * The bucket state is an immutable record inside an {@link AtomicReference}. A request reads
 * the current state, computes the new one, and installs it with compare-and-set. If another
 * thread changed the bucket in between, the CAS fails and we simply retry with fresh state.
 * No thread ever blocks, and two threads can never both spend the same last token.
 *
 * <h2>Safe eviction</h2>
 * A bucket that has refilled to full is indistinguishable from a brand-new bucket, so it can be
 * forgotten. The evictor first CASes it to a {@code retired} marker and only then removes it
 * from the map. A request that races with eviction and sees the marker drops the stale entry
 * and starts over, so no request is ever counted against a bucket that left the map.
 */
public final class TokenBucketStrategy implements RateLimitStrategy {

    private record Bucket(double tokens, long lastRefillNanos, boolean retired) {
    }

    private final Map<String, AtomicReference<Bucket>> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final double tokensPerNano;

    public TokenBucketStrategy(int capacity, double refillPerSecond) {
        if (capacity < 1 || !(refillPerSecond > 0)) {
            throw new IllegalArgumentException("capacity must be >= 1 and refillPerSecond > 0");
        }
        this.capacity = capacity;
        this.tokensPerNano = refillPerSecond / 1_000_000_000d;
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.TOKEN_BUCKET;
    }

    @Override
    public RateLimitDecision tryAcquire(String clientKey, long nowNanos) {
        while (true) {
            AtomicReference<Bucket> ref = buckets.computeIfAbsent(clientKey,
                    k -> new AtomicReference<>(new Bucket(capacity, nowNanos, false)));
            RateLimitDecision decision = tryAcquire(ref, nowNanos);
            if (decision != null) {
                return decision;
            }
            // The bucket was retired by the evictor between lookup and use; drop it and retry.
            buckets.remove(clientKey, ref);
        }
    }

    /** @return the decision, or {@code null} if the bucket was retired and the caller must retry */
    private RateLimitDecision tryAcquire(AtomicReference<Bucket> ref, long nowNanos) {
        while (true) {
            Bucket current = ref.get();
            if (current.retired()) {
                return null;
            }
            double tokens = refilled(current, nowNanos);
            if (tokens < 1) {
                // Rejections do not write anything: the stored state already implies the same
                // refill, so skipping the CAS avoids pointless contention under a flood.
                long waitNanos = (long) Math.ceil((1 - tokens) / tokensPerNano);
                return RateLimitDecision.reject(capacity, Math.max(1, waitNanos / 1_000_000));
            }
            long refillTime = Math.max(current.lastRefillNanos(), nowNanos);
            Bucket next = new Bucket(tokens - 1, refillTime, false);
            if (ref.compareAndSet(current, next)) {
                return RateLimitDecision.allow(capacity, (int) Math.floor(next.tokens()));
            }
            // Lost a race with another request for the same client: re-read and retry.
        }
    }

    private double refilled(Bucket bucket, long nowNanos) {
        long elapsed = Math.max(0, nowNanos - bucket.lastRefillNanos());
        return Math.min(capacity, bucket.tokens() + elapsed * tokensPerNano);
    }

    @Override
    public int evictIdle(long nowNanos) {
        int removed = 0;
        for (Map.Entry<String, AtomicReference<Bucket>> entry : buckets.entrySet()) {
            AtomicReference<Bucket> ref = entry.getValue();
            Bucket current = ref.get();
            if (!current.retired() && refilled(current, nowNanos) >= capacity
                    && ref.compareAndSet(current, new Bucket(capacity, nowNanos, true))) {
                buckets.remove(entry.getKey(), ref);
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int trackedClients() {
        return buckets.size();
    }
}
