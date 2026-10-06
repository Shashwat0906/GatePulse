package com.gatepulse.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LruCacheTest {

    private static final long TTL = TimeUnit.SECONDS.toNanos(60);

    private final AtomicLong clock = new AtomicLong();

    private LruCache<String, String> cache(int capacity) {
        return new LruCache<>(capacity, clock::get);
    }

    @Test
    void evictsLeastRecentlyUsedWhenFull() {
        LruCache<String, String> cache = cache(3);
        cache.put("a", "A", TTL);
        cache.put("b", "B", TTL);
        cache.put("c", "C", TTL);

        cache.put("d", "D", TTL); // "a" is the oldest

        assertThat(cache.get("a")).isEmpty();
        assertThat(cache.get("b")).hasValue("B");
        assertThat(cache.get("c")).hasValue("C");
        assertThat(cache.get("d")).hasValue("D");
        assertThat(cache.stats().evictions()).isEqualTo(1);
    }

    @Test
    void getMarksEntryAsRecentlyUsed() {
        LruCache<String, String> cache = cache(3);
        cache.put("a", "A", TTL);
        cache.put("b", "B", TTL);
        cache.put("c", "C", TTL);

        cache.get("a");          // now order (newest first): a, c, b
        cache.put("d", "D", TTL); // evicts b, not a

        assertThat(cache.get("a")).hasValue("A");
        assertThat(cache.get("b")).isEmpty();
    }

    @Test
    void putOnExistingKeyReplacesValueAndRefreshesRecency() {
        LruCache<String, String> cache = cache(2);
        cache.put("a", "A1", TTL);
        cache.put("b", "B", TTL);
        cache.put("a", "A2", TTL); // a is now newest
        cache.put("c", "C", TTL);  // evicts b

        assertThat(cache.get("a")).hasValue("A2");
        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void entriesExpireAfterTtl() {
        LruCache<String, String> cache = cache(10);
        cache.put("a", "A", 1_000);

        clock.set(999);
        assertThat(cache.get("a")).hasValue("A");

        clock.set(1_000);
        assertThat(cache.get("a")).isEmpty();
        assertThat(cache.size()).as("expired entry removed on access").isZero();
        assertThat(cache.stats().expirations()).isEqualTo(1);
    }

    @Test
    void reputtingResetsTtl() {
        LruCache<String, String> cache = cache(10);
        cache.put("a", "A", 1_000);
        clock.set(800);
        cache.put("a", "A", 1_000);
        clock.set(1_500);
        assertThat(cache.get("a")).hasValue("A");
    }

    @Test
    void tracksHitsAndMisses() {
        LruCache<String, String> cache = cache(10);
        cache.put("a", "A", TTL);
        cache.get("a");
        cache.get("a");
        cache.get("a");
        cache.get("missing");

        LruCache.Stats stats = cache.stats();
        assertThat(stats.hits()).isEqualTo(3);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.hitRatio()).isEqualTo(0.75);
    }

    @Test
    void resizeShrinksByEvictingLeastRecentlyUsed() {
        LruCache<String, String> cache = cache(5);
        for (String k : new String[]{"a", "b", "c", "d", "e"}) {
            cache.put(k, k.toUpperCase(), TTL);
        }

        cache.resize(2);

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("d")).isPresent();
        assertThat(cache.get("e")).isPresent();
        assertThat(cache.get("a")).isEmpty();
        assertThat(cache.stats().capacity()).isEqualTo(2);
    }

    @Test
    void removeAndClear() {
        LruCache<String, String> cache = cache(5);
        cache.put("a", "A", TTL);
        cache.put("b", "B", TTL);

        assertThat(cache.remove("a")).isTrue();
        assertThat(cache.remove("a")).isFalse();
        cache.clear();

        assertThat(cache.size()).isZero();
        cache.put("c", "C", TTL);
        assertThat(cache.get("c")).hasValue("C");
        cache.checkInvariants();
    }

    @Test
    void rejectsInvalidCapacityAndIgnoresNonPositiveTtl() {
        assertThatThrownBy(() -> cache(0)).isInstanceOf(IllegalArgumentException.class);
        LruCache<String, String> cache = cache(1);
        cache.put("a", "A", 0);
        assertThat(cache.size()).isZero();
    }

    /**
     * 16 threads doing random puts, gets and removes on a small key space. Afterwards the map
     * and the linked list must still describe exactly the same entries and respect capacity.
     */
    @Test
    void staysConsistentUnderConcurrentAccess() throws Exception {
        int capacity = 64;
        LruCache<Integer, Integer> cache = new LruCache<>(capacity);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int t = 0; t < 16; t++) {
                pool.submit(() -> {
                    start.await();
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < 20_000; i++) {
                        int key = random.nextInt(256);
                        switch (random.nextInt(10)) {
                            case 0 -> cache.remove(key);
                            case 1, 2, 3 -> cache.put(key, key * 10, TTL);
                            default -> cache.get(key).ifPresent(v -> {
                                if (v != key * 10) {
                                    throw new AssertionError("wrong value " + v + " for key " + key);
                                }
                            });
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        cache.checkInvariants();
        assertThat(cache.size()).isLessThanOrEqualTo(capacity);
        LruCache.Stats stats = cache.stats();
        assertThat(stats.hits() + stats.misses()).isPositive();
    }
}
