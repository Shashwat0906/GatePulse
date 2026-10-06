package com.gatepulse.ratelimit;

/**
 * Strategy pattern: a rate-limiting algorithm with per-client state.
 *
 * <p>A strategy instance is built for one {@link RateLimitConfig} and never changes its
 * settings. When an admin changes the config, the {@link RateLimiter} builds a brand-new
 * strategy, so there is no need to make settings and state change together atomically.
 *
 * <p>Implementations must be thread-safe: many requests from the same client can arrive at
 * the same instant on different threads.
 */
public interface RateLimitStrategy {

    RateLimitAlgorithm algorithm();

    /**
     * Records one request from {@code clientKey} if allowed.
     *
     * @param nowNanos current time from a monotonic clock ({@link System#nanoTime()})
     */
    RateLimitDecision tryAcquire(String clientKey, long nowNanos);

    /**
     * Forgets clients whose state is identical to a brand-new client's (bucket full again,
     * or window empty). Removing such entries never changes any future decision; it only
     * stops memory from growing with every client ever seen.
     *
     * @return number of client entries removed
     */
    int evictIdle(long nowNanos);

    /** Number of clients currently tracked (for the dashboard and tests). */
    int trackedClients();
}
