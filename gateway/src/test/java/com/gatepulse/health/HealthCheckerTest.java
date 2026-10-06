package com.gatepulse.health;

import com.gatepulse.backend.Backend;
import com.gatepulse.backend.BackendRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.gatepulse.TestBackends.await;
import static com.gatepulse.TestBackends.definition;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the checker with a fake probe whose answer per backend is set by the test,
 * so threshold behaviour is checked deterministically without real HTTP servers.
 */
class HealthCheckerTest {

    private static final int UNHEALTHY_AFTER = 3;
    private static final int HEALTHY_AFTER = 2;

    private BackendRegistry registry;
    private final Map<String, Boolean> probeAnswers = new ConcurrentHashMap<>();
    private HealthChecker checker;

    @BeforeEach
    void setUp() {
        registry = new BackendRegistry(List.of(definition("b1", 1), definition("b2", 1)));
        registry.all().forEach(b -> probeAnswers.put(b.id(), true));
        HealthProbe fakeProbe = backend -> CompletableFuture.completedFuture(probeAnswers.get(backend.id()));
        checker = new HealthChecker(registry, fakeProbe, Duration.ofSeconds(60), UNHEALTHY_AFTER, HEALTHY_AFTER);
    }

    @AfterEach
    void tearDown() {
        checker.close();
    }

    private Backend b1() {
        return registry.byId("b1").orElseThrow();
    }

    private void rounds(int n) {
        for (int i = 0; i < n; i++) {
            checker.checkAll().join();
        }
    }

    @Test
    void backendsStartHealthy() {
        assertThat(registry.all()).allMatch(Backend::isHealthy);
    }

    @Test
    void marksUnhealthyOnlyAfterThresholdConsecutiveFailures() {
        probeAnswers.put("b1", false);

        rounds(UNHEALTHY_AFTER - 1);
        assertThat(b1().isHealthy()).as("still healthy below threshold").isTrue();

        rounds(1);
        assertThat(b1().isHealthy()).as("unhealthy at threshold").isFalse();
        assertThat(registry.byId("b2").orElseThrow().isHealthy()).as("other backend unaffected").isTrue();
    }

    @Test
    void singleBlipDoesNotFlipState() {
        probeAnswers.put("b1", false);
        rounds(UNHEALTHY_AFTER - 1);
        probeAnswers.put("b1", true);
        rounds(1); // success resets the failure streak
        probeAnswers.put("b1", false);
        rounds(UNHEALTHY_AFTER - 1);

        assertThat(b1().isHealthy()).isTrue();
    }

    @Test
    void recoversOnlyAfterThresholdConsecutiveSuccesses() {
        probeAnswers.put("b1", false);
        rounds(UNHEALTHY_AFTER);
        assertThat(b1().isHealthy()).isFalse();

        probeAnswers.put("b1", true);
        rounds(HEALTHY_AFTER - 1);
        assertThat(b1().isHealthy()).as("still unhealthy below recovery threshold").isFalse();

        rounds(1);
        assertThat(b1().isHealthy()).isTrue();
    }

    @Test
    void exceptionalProbeCountsAsFailure() {
        HealthProbe failing = backend -> CompletableFuture.failedFuture(new java.net.ConnectException("refused"));
        try (HealthChecker c = new HealthChecker(registry, failing, Duration.ofSeconds(60), 1, 1)) {
            c.checkAll().join();
        }
        assertThat(registry.all()).noneMatch(Backend::isHealthy);
    }

    @Test
    void probeThrowingSynchronouslyCountsAsFailure() {
        HealthProbe throwing = backend -> {
            throw new IllegalStateException("bad probe");
        };
        try (HealthChecker c = new HealthChecker(registry, throwing, Duration.ofSeconds(60), 1, 1)) {
            c.checkAll().join();
        }
        assertThat(registry.all()).noneMatch(Backend::isHealthy);
    }

    @Test
    void scheduledRoundsRunInTheBackground() {
        probeAnswers.put("b1", false);
        try (HealthChecker scheduled = new HealthChecker(registry,
                backend -> CompletableFuture.completedFuture(probeAnswers.get(backend.id())),
                Duration.ofMillis(20), 2, 2)) {
            scheduled.start();
            await(() -> !b1().isHealthy(), Duration.ofSeconds(3), "b1 to be marked unhealthy by scheduled checks");
        }
    }
}
