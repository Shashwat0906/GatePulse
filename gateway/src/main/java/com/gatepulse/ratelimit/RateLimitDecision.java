package com.gatepulse.ratelimit;

/**
 * Outcome of asking the limiter for permission.
 *
 * @param allowed          whether the request may proceed
 * @param limit            the configured limit (for the {@code X-RateLimit-Limit} header)
 * @param remaining        requests the client could still make right now
 * @param retryAfterMillis when rejected: how long until a request would be allowed; 0 otherwise
 */
public record RateLimitDecision(boolean allowed, int limit, int remaining, long retryAfterMillis) {

    /** Used when rate limiting is switched off. */
    public static final RateLimitDecision UNLIMITED = new RateLimitDecision(true, -1, -1, 0);

    public static RateLimitDecision allow(int limit, int remaining) {
        return new RateLimitDecision(true, limit, remaining, 0);
    }

    public static RateLimitDecision reject(int limit, long retryAfterMillis) {
        return new RateLimitDecision(false, limit, 0, Math.max(1, retryAfterMillis));
    }

    public boolean isUnlimited() {
        return limit < 0;
    }

    /** Retry-After header value: whole seconds, rounded up, at least 1. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfterMillis + 999) / 1000);
    }
}
