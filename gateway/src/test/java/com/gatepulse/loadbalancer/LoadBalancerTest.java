package com.gatepulse.loadbalancer;

import com.gatepulse.backend.Backend;
import com.gatepulse.backend.BackendRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.gatepulse.TestBackends.definition;
import static org.assertj.core.api.Assertions.assertThat;

class LoadBalancerTest {

    private BackendRegistry registry;
    private LoadBalancer lb;

    @BeforeEach
    void setUp() {
        registry = new BackendRegistry(List.of(definition("b1", 1), definition("b2", 1), definition("b3", 1)));
        lb = new LoadBalancer(registry, new RoundRobinStrategy());
    }

    private Backend get(String id) {
        return registry.byId(id).orElseThrow();
    }

    @Test
    void neverChoosesUnhealthyBackend() {
        get("b2").setHealthy(false);

        Set<String> chosen = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            chosen.add(lb.choose().orElseThrow().id());
        }

        assertThat(chosen).containsExactlyInAnyOrder("b1", "b3");
    }

    @Test
    void skipsExcludedBackendsSoRetriesGoSomewhereNew() {
        Set<Backend> tried = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            Backend next = lb.choose(tried).orElseThrow();
            assertThat(tried).doesNotContain(next);
            tried.add(next);
        }
        assertThat(lb.choose(tried)).isEmpty();
    }

    @Test
    void emptyWhenEveryBackendIsUnhealthy() {
        registry.all().forEach(b -> b.setHealthy(false));
        assertThat(lb.choose()).isEmpty();
    }

    @Test
    void backendReturnsToRotationWhenHealthyAgain() {
        get("b1").setHealthy(false);
        get("b1").setHealthy(true);

        Set<String> chosen = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            chosen.add(lb.choose().orElseThrow().id());
        }
        assertThat(chosen).contains("b1");
    }

    @Test
    void strategyCanBeSwappedAtRuntime() {
        LoadBalancingStrategy alwaysLast = new LoadBalancingStrategy() {
            @Override
            public String name() {
                return "always-last";
            }

            @Override
            public Backend select(List<Backend> candidates) {
                return candidates.get(candidates.size() - 1);
            }
        };

        lb.setStrategy(alwaysLast);

        assertThat(lb.strategy().name()).isEqualTo("always-last");
        assertThat(lb.choose().orElseThrow().id()).isEqualTo("b3");
    }
}
