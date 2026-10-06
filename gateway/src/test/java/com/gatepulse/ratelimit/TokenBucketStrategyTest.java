package com.gatepulse.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketStrategyTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        TokenBucketStrategy bucket = new TokenBucketStrategy(5, 1);
        long now = 0;

        for (int i = 0; i < 5; i++) {
            RateLimitDecision d = bucket.tryAcquire("client", now);
            assertThat(d.allowed()).as("request %d", i + 1).isTrue();
            assertThat(d.remaining()).isEqualTo(4 - i);
            assertThat(d.limit()).isEqualTo(5);
        }
        RateLimitDecision sixth = bucket.tryAcquire("client", now);
        assertThat(sixth.allowed()).isFalse();
        assertThat(sixth.remaining()).isZero();
    }

    @Test
    void refillsAtConfiguredRate() {
        TokenBucketStrategy bucket = new TokenBucketStrategy(5, 2); // 2 tokens per second
        for (int i = 0; i < 5; i++) {
            bucket.tryAcquire("c", 0);
        }
        assertThat(bucket.tryAcquire("c", 0).allowed()).isFalse();

        // After 0.5s exactly one token has dripped in.
        assertThat(bucket.tryAcquire("c", SECOND / 2).allowed()).isTrue();
        assertThat(bucket.tryAcquire("c", SECOND / 2).allowed()).isFalse();

        // After a long idle period the bucket is full again, but never above capacity.
        long later = 100 * SECOND;
        int allowed = 0;
        while (bucket.tryAcquire("c", later).allowed()) {
            allowed++;
        }
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void retryAfterReflectsTimeUntilNextToken() {
        TokenBucketStrategy bucket = new TokenBucketStrategy(1, 4); // a token every 250ms
        bucket.tryAcquire("c", 0);

        RateLimitDecision rejected = bucket.tryAcquire("c", 50_000_000L); // 50ms later
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterMillis()).isBetween(199L, 201L);
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1); // header rounds up
    }

    @Test
    void clientsAreIndependent() {
        TokenBucketStrategy bucket = new TokenBucketStrategy(1, 1);
        assertThat(bucket.tryAcquire("a", 0).allowed()).isTrue();
        assertThat(bucket.tryAcquire("a", 0).allowed()).isFalse();
        assertThat(bucket.tryAcquire("b", 0).allowed()).isTrue();
    }

    @Test
    void evictsOnlyBucketsThatAreFullAgain() {
        TokenBucketStrategy bucket = new TokenBucketStrategy(10, 1);
        bucket.tryAcquire("idle", 0);
        bucket.tryAcquire("busy", 0);
        for (int i = 0; i < 9; i++) {
            bucket.tryAcquire("busy", 5 * SECOND);
        }

        // At t=6s "idle" has refilled (1 used, 6 regained) but "busy" has not.
        int removed = bucket.evictIdle(6 * SECOND);

        assertThat(removed).isEqualTo(1);
        assertThat(bucket.trackedClients()).isEqualTo(1);
        // "busy" kept its state: still nearly empty.
        assertThat(bucket.tryAcquire("busy", 6 * SECOND).remaining()).isLessThan(5);
        // "idle" behaves exactly like a new client.
        assertThat(bucket.tryAcquire("idle", 6 * SECOND).remaining()).isEqualTo(9);
    }

    /**
     * 32 threads hammer one client at the same instant. Exactly `capacity` must succeed: a
     * non-atomic read-modify-write would let several threads spend the same token.
     */
    @Test
    void neverOverAdmitsUnderConcurrency() throws Exception {
        int capacity = 1_000;
        TokenBucketStrategy bucket = new TokenBucketStrategy(capacity, 0.000_001); // effectively no refill
        AtomicInteger allowed = new AtomicInteger();
        int threads = 32;
        int perThread = 200; // 6,400 attempts for 1,000 tokens
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (bucket.tryAcquire("shared", 0).allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(allowed.get()).isEqualTo(capacity);
    }

    /** Eviction racing with requests must never let a client exceed its allowance. */
    @Test
    void evictionRacingWithRequestsStaysCorrect() throws Exception {
        int capacity = 50;
        TokenBucketStrategy bucket = new TokenBucketStrategy(capacity, 0.000_001);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(9)) {
            for (int t = 0; t < 8; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        if (bucket.tryAcquire("c", 0).allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 1_000; i++) {
                    bucket.evictIdle(0);
                }
                return null;
            });
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        // Only a full (unused) bucket may be evicted, so at most one extra fresh bucket could
        // ever be created, and only before any token was spent: the total stays at capacity.
        assertThat(allowed.get()).isEqualTo(capacity);
    }
}
