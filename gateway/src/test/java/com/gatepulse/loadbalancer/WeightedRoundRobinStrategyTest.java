package com.gatepulse.loadbalancer;

import com.gatepulse.backend.Backend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeightedRoundRobinStrategyTest {

    private final Backend a = backend("a", 5);
    private final Backend b = backend("b", 1);
    private final Backend c = backend("c", 1);

    @Test
    void producesTheSmoothNginxSequence() {
        WeightedRoundRobinStrategy wrr = new WeightedRoundRobinStrategy();
        List<String> picks = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            picks.add(wrr.select(List.of(a, b, c)).id());
        }
        // Not a,a,a,a,a,b,c: the heavy backend's turns are interleaved with the others.
        assertThat(picks).containsExactly("a", "a", "b", "a", "c", "a", "a");
    }

    @Test
    void trafficIsProportionalToWeight() {
        WeightedRoundRobinStrategy wrr = new WeightedRoundRobinStrategy();
        Backend heavy = backend("heavy", 3);
        Backend light = backend("light", 1);
        int heavyCount = 0;
        for (int i = 0; i < 400; i++) {
            if (wrr.select(List.of(heavy, light)) == heavy) {
                heavyCount++;
            }
        }
        assertThat(heavyCount).isEqualTo(300);
    }

    @Test
    void equalWeightsBehaveLikeRoundRobin() {
        assertThat(WeightedRoundRobinStrategy.buildOrder(List.of(backend("x"), backend("y"), backend("z"))))
                .containsExactly(0, 1, 2);
    }

    @Test
    void scheduleIsRebuiltWhenCandidatesChange() {
        WeightedRoundRobinStrategy wrr = new WeightedRoundRobinStrategy();
        wrr.select(List.of(a, b, c));

        // a went unhealthy: only b and c remain and must alternate.
        List<String> picks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            picks.add(wrr.select(List.of(b, c)).id());
        }
        assertThat(picks).containsOnly("b", "c");
        assertThat(picks.stream().filter("b"::equals).count()).isEqualTo(2);
    }

    @Test
    void exactProportionsUnderConcurrency() throws Exception {
        WeightedRoundRobinStrategy wrr = new WeightedRoundRobinStrategy();
        List<Backend> candidates = List.of(a, b, c);
        Map<String, LongAdder> counts = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        int threads = 14;
        int perThread = 7_000; // total 98,000 = 14,000 full cycles of 7

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        counts.computeIfAbsent(wrr.select(candidates).id(), k -> new LongAdder()).increment();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(counts.get("a").sum()).isEqualTo(70_000);
        assertThat(counts.get("b").sum()).isEqualTo(14_000);
        assertThat(counts.get("c").sum()).isEqualTo(14_000);
    }

    @Test
    void factoryKnowsAllStrategies() {
        assertThat(LoadBalancingStrategies.create("round-robin")).isInstanceOf(RoundRobinStrategy.class);
        assertThat(LoadBalancingStrategies.create("LEAST_CONNECTIONS")).isInstanceOf(LeastConnectionsStrategy.class);
        assertThat(LoadBalancingStrategies.create("weighted-round-robin")).isInstanceOf(WeightedRoundRobinStrategy.class);
        assertThatThrownBy(() -> LoadBalancingStrategies.create("random")).hasMessageContaining("round-robin");
    }
}
