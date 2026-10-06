package com.gatepulse.loadbalancer;

import com.gatepulse.backend.Backend;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static com.gatepulse.TestBackends.backend;
import static org.assertj.core.api.Assertions.assertThat;

class RoundRobinStrategyTest {

    private final Backend a = backend("a");
    private final Backend b = backend("b");
    private final Backend c = backend("c");

    @Test
    void cyclesThroughCandidatesInOrder() {
        RoundRobinStrategy rr = new RoundRobinStrategy();
        List<Backend> candidates = List.of(a, b, c);

        assertThat(List.of(rr.select(candidates), rr.select(candidates), rr.select(candidates),
                rr.select(candidates), rr.select(candidates), rr.select(candidates)))
                .containsExactly(a, b, c, a, b, c);
    }

    @Test
    void keepsRotatingWhenCandidateListShrinks() {
        RoundRobinStrategy rr = new RoundRobinStrategy();
        rr.select(List.of(a, b, c));

        List<Backend> twoLeft = List.of(a, c);
        Backend first = rr.select(twoLeft);
        Backend second = rr.select(twoLeft);

        assertThat(first).isNotEqualTo(second);
        assertThat(List.of(first, second)).containsExactlyInAnyOrder(a, c);
    }

    @Test
    void singleCandidateAlwaysChosen() {
        RoundRobinStrategy rr = new RoundRobinStrategy();
        for (int i = 0; i < 10; i++) {
            assertThat(rr.select(List.of(b))).isSameAs(b);
        }
    }

    /**
     * Concurrency check: 16 threads making 30,000 selections in total must split them
     * exactly evenly. A non-atomic counter would lose increments and skew the split.
     */
    @Test
    void distributesExactlyEvenlyUnderConcurrency() throws Exception {
        RoundRobinStrategy rr = new RoundRobinStrategy();
        List<Backend> candidates = List.of(a, b, c);
        int threads = 16;
        int perThread = 30_000 / threads + 1;
        int total = perThread * threads;
        Map<Backend, LongAdder> counts = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        counts.computeIfAbsent(rr.select(candidates), k -> new LongAdder()).increment();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        long sum = counts.values().stream().mapToLong(LongAdder::sum).sum();
        assertThat(sum).isEqualTo(total);
        long min = counts.values().stream().mapToLong(LongAdder::sum).min().orElseThrow();
        long max = counts.values().stream().mapToLong(LongAdder::sum).max().orElseThrow();
        // total is not always divisible by 3, so allow a difference of at most one.
        assertThat(max - min).isLessThanOrEqualTo(1);
    }
}
