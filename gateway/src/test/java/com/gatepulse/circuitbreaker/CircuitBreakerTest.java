package com.gatepulse.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong();
    private CircuitBreakerRegistry registry;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        // 3 failures to open, 10s open, 2 trial calls.
        registry = new CircuitBreakerRegistry(List.of("b1"),
                new CircuitBreakerConfig(3, Duration.ofSeconds(10), 2),
                clock::get, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        breaker = registry.forBackend("b1");
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            CircuitBreaker.Permit permit = breaker.tryAcquire();
            assertThat(permit).as("permit for failure %d", i + 1).isNotNull();
            permit.onFailure();
        }
    }

    private void succeed() {
        breaker.tryAcquire().onSuccess();
    }

    @Test
    void startsClosedAndAllowsCalls() {
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.allowsTraffic()).isTrue();
        assertThat(breaker.tryAcquire()).isNotNull();
    }

    @Test
    void opensAfterThresholdConsecutiveFailures() {
        fail(2);
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.consecutiveFailures()).isEqualTo(2);

        fail(1);

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.allowsTraffic()).isFalse();
        assertThat(breaker.tryAcquire()).as("OPEN rejects without calling the backend").isNull();
    }

    @Test
    void successResetsTheFailureCount() {
        fail(2);
        succeed();
        fail(2);
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void movesToHalfOpenAfterOpenDurationAndClosesWhenTrialsSucceed() {
        fail(3);
        clock.set(9 * SECOND);
        assertThat(breaker.tryAcquire()).as("still cooling down").isNull();
        assertThat(breaker.remainingOpenMillis()).isEqualTo(1000);

        clock.set(10 * SECOND);
        assertThat(breaker.allowsTraffic()).isTrue();
        CircuitBreaker.Permit trial1 = breaker.tryAcquire();
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        CircuitBreaker.Permit trial2 = breaker.tryAcquire();
        assertThat(trial1).isNotNull();
        assertThat(trial2).isNotNull();
        assertThat(breaker.tryAcquire()).as("only 2 trial calls allowed").isNull();
        assertThat(breaker.allowsTraffic()).isFalse();

        trial1.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        trial2.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void anyFailedTrialReopensTheCircuit() {
        fail(3);
        clock.set(10 * SECOND);
        CircuitBreaker.Permit trial = breaker.tryAcquire();

        trial.onFailure();

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        clock.set(19 * SECOND);
        assertThat(breaker.tryAcquire()).as("a fresh open period started at t=10s").isNull();
        clock.set(20 * SECOND);
        assertThat(breaker.tryAcquire()).isNotNull();
    }

    @Test
    void lateResultFromBeforeTheTransitionIsIgnored() {
        CircuitBreaker.Permit slowCall = breaker.tryAcquire(); // started while CLOSED
        fail(3);                                               // circuit opens meanwhile
        clock.set(10 * SECOND);
        breaker.tryAcquire();                                  // HALF_OPEN, trial 1 in flight

        slowCall.onFailure(); // finishes now; must not count as a failed trial

        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    void recordsTransitionsWithTimestampsAndReasons() {
        fail(3);
        clock.set(10 * SECOND);
        breaker.tryAcquire().onSuccess();
        breaker.tryAcquire().onSuccess();

        List<CircuitTransition> events = registry.recentTransitions(10);

        assertThat(events).extracting(CircuitTransition::to)
                .containsExactly(CircuitState.CLOSED, CircuitState.HALF_OPEN, CircuitState.OPEN); // newest first
        assertThat(events.get(2).reason()).contains("3 consecutive failures");
        assertThat(events.get(2).backendId()).isEqualTo("b1");
        assertThat(events.get(2).at()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void manualResetClosesTheCircuit() {
        fail(3);
        breaker.reset();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.tryAcquire()).isNotNull();
    }

    @Test
    void configChangesApplyAtRuntime() {
        registry.updateConfig(new CircuitBreakerConfig(1, Duration.ofSeconds(1), 1));
        fail(1);
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        clock.set(SECOND);
        breaker.tryAcquire().onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void configValidation() {
        assertThatThrownBy(() -> new CircuitBreakerConfig(0, Duration.ofSeconds(1), 1)).hasMessageContaining("failureThreshold");
        assertThatThrownBy(() -> new CircuitBreakerConfig(1, Duration.ZERO, 1)).hasMessageContaining("openDurationMs");
        assertThatThrownBy(() -> new CircuitBreakerConfig(1, Duration.ofSeconds(1), 0)).hasMessageContaining("halfOpenTrials");
        assertThatThrownBy(() -> registry.forBackend("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Many threads failing at once must produce exactly one CLOSED -> OPEN transition. */
    @Test
    void concurrentFailuresOpenTheCircuitExactlyOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int t = 0; t < 16; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        CircuitBreaker.Permit permit = breaker.tryAcquire();
                        if (permit != null) {
                            permit.onFailure();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(registry.recentTransitions(100)).hasSize(1);
    }

    /** In HALF_OPEN, racing threads must be handed exactly `halfOpenTrials` permits. */
    @Test
    void halfOpenHandsOutExactlyTheConfiguredTrialsUnderConcurrency() throws Exception {
        fail(3);
        clock.set(10 * SECOND);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int t = 0; t < 16; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        if (breaker.tryAcquire() != null) {
                            granted.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(granted).hasValue(2);
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
    }
}
