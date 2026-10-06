package com.gatepulse.cache;

/** What the cache did for a request; shown in the {@code X-Cache} header and the request log. */
public enum CacheStatus {
    /** Served from the cache; no backend was called. */
    HIT,
    /** Looked up but not found (or expired); a backend was called. */
    MISS,
    /** Not eligible for caching (non-GET, cache disabled, or client asked for fresh data). */
    BYPASS
}
