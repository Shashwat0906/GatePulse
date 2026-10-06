package com.gatepulse.cache;

/**
 * Runtime-changeable cache settings.
 *
 * @param enabled  when false, requests bypass the cache entirely
 * @param ttlMillis how long a cached response stays fresh
 * @param capacity maximum number of cached responses
 */
public record CacheSettings(boolean enabled, long ttlMillis, int capacity) {

    public static final long MAX_TTL_MILLIS = 3_600_000;
    public static final int MAX_CAPACITY = 100_000;

    public CacheSettings {
        if (ttlMillis < 1 || ttlMillis > MAX_TTL_MILLIS) {
            throw new IllegalArgumentException("ttlMs must be between 1 and " + MAX_TTL_MILLIS + " but was " + ttlMillis);
        }
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be between 1 and " + MAX_CAPACITY + " but was " + capacity);
        }
    }

    public static CacheSettings defaults() {
        return new CacheSettings(true, 5_000, 500);
    }
}
