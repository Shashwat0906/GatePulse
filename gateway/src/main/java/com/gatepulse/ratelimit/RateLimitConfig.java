package com.gatepulse.ratelimit;

import java.util.Objects;

/**
 * Immutable rate-limit settings. Replaced as a whole when an admin changes them, so a request
 * never sees a half-updated mix of old and new values.
 *
 * @param enabled         when false every request is allowed
 * @param algorithm       which algorithm to use
 * @param limit           token bucket: bucket capacity (max burst);
 *                        sliding window: max requests per window
 * @param refillPerSecond token bucket only: tokens added per second (sustained rate)
 * @param windowMillis    sliding window only: window length
 */
public record RateLimitConfig(boolean enabled, RateLimitAlgorithm algorithm, int limit,
                              double refillPerSecond, long windowMillis) {

    public static final int MAX_LIMIT = 1_000_000;
    public static final long MAX_WINDOW_MILLIS = 3_600_000;

    public RateLimitConfig {
        Objects.requireNonNull(algorithm, "algorithm");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + " but was " + limit);
        }
        if (!(refillPerSecond > 0) || refillPerSecond > MAX_LIMIT || Double.isNaN(refillPerSecond)) {
            throw new IllegalArgumentException("refillPerSecond must be > 0 and <= " + MAX_LIMIT + " but was " + refillPerSecond);
        }
        if (windowMillis < 1 || windowMillis > MAX_WINDOW_MILLIS) {
            throw new IllegalArgumentException("windowMs must be between 1 and " + MAX_WINDOW_MILLIS + " but was " + windowMillis);
        }
    }

    public static RateLimitConfig defaults() {
        return new RateLimitConfig(true, RateLimitAlgorithm.TOKEN_BUCKET, 100, 50, 1000);
    }

    public RateLimitConfig withEnabled(boolean value) {
        return new RateLimitConfig(value, algorithm, limit, refillPerSecond, windowMillis);
    }

    public RateLimitConfig withAlgorithm(RateLimitAlgorithm value) {
        return new RateLimitConfig(enabled, value, limit, refillPerSecond, windowMillis);
    }

    public RateLimitConfig withLimit(int value) {
        return new RateLimitConfig(enabled, algorithm, value, refillPerSecond, windowMillis);
    }

    public RateLimitConfig withRefillPerSecond(double value) {
        return new RateLimitConfig(enabled, algorithm, limit, value, windowMillis);
    }

    public RateLimitConfig withWindowMillis(long value) {
        return new RateLimitConfig(enabled, algorithm, limit, refillPerSecond, value);
    }
}
