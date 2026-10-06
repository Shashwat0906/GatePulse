package com.gatepulse.loadbalancer;

import com.gatepulse.backend.Backend;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.gatepulse.TestBackends.backend;
import static org.assertj.core.api.Assertions.assertThat;

class LeastConnectionsStrategyTest {

    private final Backend a = backend("a");
    private final Backend b = backend("b");
    private final Backend c = backend("c");
    private final LeastConnectionsStrategy strategy = new LeastConnectionsStrategy();

    private static void busy(Backend backend, int connections) {
        for (int i = 0; i < connections; i++) {
            backend.onRequestStart();
        }
    }

    @Test
    void picksBackendWithFewestActiveConnections() {
        busy(a, 5);
        busy(b, 1);
        busy(c, 3);

        for (int i = 0; i < 10; i++) {
            assertThat(strategy.select(List.of(a, b, c))).isSameAs(b);
        }
    }

    @Test
    void routesAroundABackendThatGetsSlow() {
        busy(b, 1);
        busy(c, 1);
        assertThat(strategy.select(List.of(a, b, c))).isSameAs(a);

        busy(a, 10); // a's requests pile up (slow backend)
        assertThat(strategy.select(List.of(a, b, c))).isIn(b, c);
    }

    @Test
    void spreadsTiesInsteadOfAlwaysPickingTheFirst() {
        Set<Backend> chosen = new HashSet<>();
        for (int i = 0; i < 9; i++) {
            chosen.add(strategy.select(List.of(a, b, c)));
        }
        assertThat(chosen).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    void reflectsConnectionsFinishing() {
        busy(a, 2);
        busy(b, 1);
        assertThat(strategy.select(List.of(a, b))).isSameAs(b);

        a.onRequestEnd(false);
        a.onRequestEnd(false);
        busy(b, 1);
        assertThat(strategy.select(List.of(a, b))).isSameAs(a);
    }
}
