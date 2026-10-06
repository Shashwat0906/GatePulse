package com.gatepulse.health;

import com.gatepulse.backend.Backend;
import com.gatepulse.backend.BackendRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Actively probes every backend on a fixed schedule and flips its healthy flag.
 *
 * <h2>Why thresholds instead of "one failure = dead"</h2>
 * A single dropped probe (GC pause, network blip) should not pull a backend out of rotation,
 * and a single lucky success should not put a flapping backend back in. So:
 * <ul>
 *   <li>a healthy backend becomes <b>unhealthy</b> after {@code unhealthyThreshold}
 *       consecutive failed probes;</li>
 *   <li>an unhealthy backend becomes <b>healthy</b> after {@code healthyThreshold}
 *       consecutive successful probes.</li>
 * </ul>
 * Any result of the opposite kind resets the streak. This hysteresis prevents flapping.
 *
 * <h2>Scheduling</h2>
 * One daemon thread fires a round every {@code interval}. All backends are probed in parallel
 * (async HTTP), and the round waits for every probe to finish before the next delay starts
 * ({@code scheduleWithFixedDelay}), so rounds never overlap. Probes have their own timeout,
 * so a hung backend cannot stall the round.
 */
public final class HealthChecker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);

    private final BackendRegistry registry;
    private final HealthProbe probe;
    private final Duration interval;
    private final int unhealthyThreshold;
    private final int healthyThreshold;
    private final Map<String, Streak> streaks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public HealthChecker(BackendRegistry registry, HealthProbe probe, Duration interval,
                         int unhealthyThreshold, int healthyThreshold) {
        if (unhealthyThreshold < 1 || healthyThreshold < 1) {
            throw new IllegalArgumentException("Thresholds must be >= 1");
        }
        this.registry = registry;
        this.probe = probe;
        this.interval = interval;
        this.unhealthyThreshold = unhealthyThreshold;
        this.healthyThreshold = healthyThreshold;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-checker");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts periodic checking; the first round runs immediately. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::runRoundSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("event=health_checker_started intervalMs={} unhealthyAfter={} healthyAfter={}",
                interval.toMillis(), unhealthyThreshold, healthyThreshold);
    }

    /**
     * Probes all backends once, in parallel, and applies the results.
     * Public so tests (and a future admin "check now" button) can trigger a round directly.
     *
     * @return a future that completes when every probe has been recorded
     */
    public CompletableFuture<Void> checkAll() {
        List<Backend> backends = registry.all();
        CompletableFuture<?>[] rounds = new CompletableFuture<?>[backends.size()];
        for (int i = 0; i < backends.size(); i++) {
            Backend backend = backends.get(i);
            rounds[i] = safeProbe(backend).thenAccept(ok -> record(backend, ok));
        }
        return CompletableFuture.allOf(rounds);
    }

    /**
     * Applies one probe result to a backend's streak and flips its state if a threshold is hit.
     * Synchronized per backend so concurrent results for the same backend cannot interleave.
     */
    void record(Backend backend, boolean success) {
        Streak streak = streaks.computeIfAbsent(backend.id(), id -> new Streak());
        synchronized (streak) {
            if (success) {
                streak.failures = 0;
                streak.successes++;
                if (!backend.isHealthy() && streak.successes >= healthyThreshold) {
                    backend.setHealthy(true);
                    log.info("event=backend_healthy backend={} consecutiveSuccesses={}", backend.id(), streak.successes);
                }
            } else {
                streak.successes = 0;
                streak.failures++;
                if (backend.isHealthy() && streak.failures >= unhealthyThreshold) {
                    backend.setHealthy(false);
                    log.warn("event=backend_unhealthy backend={} consecutiveFailures={}", backend.id(), streak.failures);
                }
            }
        }
    }

    /** Never lets a probe throw synchronously or complete exceptionally: both mean "unhealthy". */
    private CompletableFuture<Boolean> safeProbe(Backend backend) {
        try {
            return probe.probe(backend)
                    .handle((ok, error) -> {
                        if (error != null) {
                            log.debug("event=probe_failed backend={} error=\"{}\"", backend.id(), error.toString());
                            return false;
                        }
                        return Boolean.TRUE.equals(ok);
                    });
        } catch (RuntimeException e) {
            log.debug("event=probe_failed backend={} error=\"{}\"", backend.id(), e.toString());
            return CompletableFuture.completedFuture(false);
        }
    }

    private void runRoundSafely() {
        try {
            checkAll().join();
        } catch (RuntimeException e) {
            // An exception escaping a scheduled task would silently cancel all future runs.
            log.error("event=health_round_failed", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /** Consecutive results of one kind for one backend. Guarded by its own monitor. */
    private static final class Streak {
        int failures;
        int successes;
    }
}
