package com.gatepulse.cache;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * The gateway's HTTP response cache: an {@link LruCache} plus runtime-changeable settings.
 *
 * <p>Settings live in an {@link AtomicReference}; changing the TTL applies to entries stored
 * from then on, changing the capacity resizes the LRU immediately.
 */
public final class ResponseCache {

    private final LruCache<String, CachedResponse> lru;
    private final AtomicReference<CacheSettings> settings;

    public ResponseCache(CacheSettings initial) {
        this(initial, System::nanoTime);
    }

    public ResponseCache(CacheSettings initial, LongSupplier nanoClock) {
        this.settings = new AtomicReference<>(Objects.requireNonNull(initial));
        this.lru = new LruCache<>(initial.capacity(), nanoClock);
    }

    public boolean enabled() {
        return settings.get().enabled();
    }

    public Optional<CachedResponse> get(String key) {
        return lru.get(key);
    }

    public void put(String key, CachedResponse response) {
        lru.put(key, response, TimeUnit.MILLISECONDS.toNanos(settings.get().ttlMillis()));
    }

    public CacheSettings settings() {
        return settings.get();
    }

    public synchronized void update(CacheSettings newSettings) {
        CacheSettings previous = settings.getAndSet(Objects.requireNonNull(newSettings));
        if (previous.capacity() != newSettings.capacity()) {
            lru.resize(newSettings.capacity());
        }
        if (!newSettings.enabled()) {
            lru.clear(); // turning the cache off should not serve stale data when it is turned back on
        }
    }

    public void clear() {
        lru.clear();
    }

    public LruCache.Stats stats() {
        return lru.stats();
    }
}
