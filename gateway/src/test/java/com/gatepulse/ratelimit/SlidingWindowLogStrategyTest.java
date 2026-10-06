package com.gatepulse.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowLogStrategyTest {

    private static final long MS = 1_000_000L;

    @Test
    void allowsLimitRequestsPerWindow() {
        SlidingWindowLogStrategy window = new SlidingWindowLogStrategy(3, 1000);

        assertThat(window.tryAcquire("c", 0).remaining()).isEqualTo(2);
        assertThat(window.tryAcquire("c", 100 * MS).remaining()).isEqualTo(1);
        assertThat(window.tryAcquire("c", 200 * MS).remaining()).isZero();
        assertThat(window.tryAcquire("c", 300 * MS).allowed()).isFalse();
    }

    @Test
    void windowSlidesOneRequestAtATime() {
        SlidingWindowLogStrategy window = new SlidingWindowLogStrategy(3, 1000);
        window.tryAcquire("c", 0);
        window.tryAcquire("c", 100 * MS);
        window.tryAcquire("c", 200 * MS);

        // At t=1000ms the request from t=0 has slid out: exactly one slot frees up.
        assertThat(window.tryAcquire("c", 1000 * MS).allowed()).isTrue();
        assertThat(window.tryAcquire("c", 1000 * MS).allowed()).isFalse();
        // At t=1100ms the t=100ms request leaves.
        assertThat(window.tryAcquire("c", 1100 * MS).allowed()).isTrue();
    }

    /** The defining property: no 1-second span ever contains more than `limit` allowed requests. */
    @Test
    void noBoundaryBurstLikeAFixedWindow() {
        SlidingWindowLogStrategy window = new SlidingWindowLogStrategy(10, 1000);
        int allowedAroundBoundary = 0;
        // 20 requests between t=990ms and t=1010ms. A fixed window would allow 10 + 10 here.
        for (int i = 0; i < 20; i++) {
            if (window.tryAcquire("c", (990 + i) * MS).allowed()) {
                allowedAroundBoundary++;
            }
        }
        assertThat(allowedAroundBoundary).isEqualTo(10);
    }

    @Test
    void retryAfterPointsAtWhenOldestRequestExpires() {
        SlidingWindowLogStrategy window = new SlidingWindowLogStrategy(2, 1000);
        window.tryAcquire("c", 0);
        window.tryAcquire("c", 400 * MS);

        RateLimitDecision rejected = window.tryAcquire("c", 600 * MS);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterMillis()).isEqualTo(400); // t=0 entry leaves at t=1000
    }

    @Test
    void evictsClientsWithEmptyWindowsOnly() {
        SlidingWindowLogStrategy window = new SlidingWindowLogStrategy(5, 1000);
        window.tryAcquire("old", 0);
        window.tryAcquire("recent", 1500 * MS);

        assertThat(window.evictIdle(2000 * MS)).isEqualTo(1);
        assertThat(window.trackedClients()).isEqualTo(1);
        assertThat(window.tryAcquire("recent", 2000 * MS).remaining()).isEqualTo(3);
    }

    @Test
    void neverOverAdmitsUnderConcurrency() throws Exception {
        int limit = 500;
        SlidingWindowLogStrategy window = new SlidingWindowLogStrategy(limit, 60_000);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            for (int t = 0; t < 32; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        if (window.tryAcquire("shared", 0).allowed()) {
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

        assertThat(allowed.get()).isEqualTo(limit);
    }

    @Test
    void longRingGrowsAndDropsInOrder() {
        SlidingWindowLogStrategy.LongRing ring = new SlidingWindowLogStrategy.LongRing();
        for (long i = 1; i <= 100; i++) {
            ring.add(i);
        }
        assertThat(ring.size()).isEqualTo(100);
        assertThat(ring.first()).isEqualTo(1);

        ring.removeUpTo(60);
        assertThat(ring.size()).isEqualTo(40);
        assertThat(ring.first()).isEqualTo(61);

        for (long i = 101; i <= 150; i++) {
            ring.add(i); // wraps around the internal array
        }
        ring.removeUpTo(100);
        assertThat(ring.size()).isEqualTo(50);
        assertThat(ring.first()).isEqualTo(101);

        ring.removeUpTo(Long.MAX_VALUE);
        assertThat(ring.size()).isZero();
    }
}
