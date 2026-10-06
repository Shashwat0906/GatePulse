package com.gatepulse.admin;

import com.gatepulse.backend.Backend;
import com.gatepulse.backend.BackendRegistry;
import com.gatepulse.cache.CacheSettings;
import com.gatepulse.cache.LruCache;
import com.gatepulse.cache.ResponseCache;
import com.gatepulse.circuitbreaker.CircuitBreaker;
import com.gatepulse.circuitbreaker.CircuitBreakerRegistry;
import com.gatepulse.config.Mode;
import com.gatepulse.loadbalancer.LoadBalancer;
import com.gatepulse.metrics.MetricsCollector;
import com.gatepulse.ratelimit.RateLimitConfig;
import com.gatepulse.ratelimit.RateLimiter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assembles a {@link GatewaySnapshot} from the live components.
 *
 * <p>Reading is cheap (atomic counters plus ~60 small histograms) and lock-free except for the
 * cache stats, which briefly take the cache lock. The broadcaster builds one snapshot per
 * second and shares it with every connected dashboard.
 */
public final class SnapshotService {

    public static final int TIMELINE_SECONDS = 60;
    public static final int RPS_WINDOW_SECONDS = 5;
    public static final int ERROR_RATE_WINDOW_SECONDS = 60;
    public static final int LATENCY_WINDOW_SECONDS = 10;
    public static final int CIRCUIT_EVENTS = 20;

    private final Mode mode;
    private final MetricsCollector metrics;
    private final BackendRegistry registry;
    private final CircuitBreakerRegistry breakers;
    private final LoadBalancer loadBalancer;
    private final ResponseCache cache;
    private final RateLimiter rateLimiter;
    private final TrafficGenerator traffic;

    public SnapshotService(Mode mode, MetricsCollector metrics, BackendRegistry registry,
                           CircuitBreakerRegistry breakers, LoadBalancer loadBalancer, ResponseCache cache,
                           RateLimiter rateLimiter, TrafficGenerator traffic) {
        this.mode = mode;
        this.metrics = metrics;
        this.registry = registry;
        this.breakers = breakers;
        this.loadBalancer = loadBalancer;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.traffic = traffic;
    }

    public GatewaySnapshot snapshot() {
        LruCache.Stats cacheStats = cache.stats();
        CacheSettings cacheSettings = cache.settings();
        RateLimitConfig rl = rateLimiter.config();

        GatewaySnapshot.Summary summary = new GatewaySnapshot.Summary(
                metrics.totalRequests(),
                round2(metrics.requestsPerSecond(RPS_WINDOW_SECONDS)),
                round4(metrics.errorRate(ERROR_RATE_WINDOW_SECONDS)),
                metrics.totalServerErrors(),
                metrics.totalRateLimited(),
                round4(cacheStats.hitRatio()),
                metrics.inFlight());

        Map<String, Long> statusCodes = new LinkedHashMap<>();
        metrics.statusCounts().forEach((code, count) -> statusCodes.put(Integer.toString(code), count));

        return new GatewaySnapshot(
                metrics.nowMillis(),
                metrics.uptimeSeconds(),
                mode.name().toLowerCase(Locale.ROOT),
                summary,
                metrics.latency(LATENCY_WINDOW_SECONDS),
                statusCodes,
                new GatewaySnapshot.CacheView(cacheSettings.enabled(), cacheSettings.ttlMillis(), cacheStats.capacity(),
                        cacheStats.size(), cacheStats.hits(), cacheStats.misses(), cacheStats.evictions(),
                        cacheStats.expirations(), round4(cacheStats.hitRatio())),
                new GatewaySnapshot.RateLimitView(rl.enabled(), rl.algorithm().id(), rl.limit(), rl.refillPerSecond(),
                        rl.windowMillis(), rateLimiter.trackedClients()),
                loadBalancer.strategy().name(),
                backends(),
                breakers.recentTransitions(CIRCUIT_EVENTS),
                metrics.timeline(TIMELINE_SECONDS),
                traffic.status(),
                metrics.requestLog().lastSeq());
    }

    private List<GatewaySnapshot.BackendView> backends() {
        return registry.all().stream().map(this::describe).toList();
    }

    private GatewaySnapshot.BackendView describe(Backend backend) {
        CircuitBreaker breaker = breakers.forBackend(backend.id());
        return new GatewaySnapshot.BackendView(
                backend.id(),
                backend.baseUri().toString(),
                backend.isHealthy(),
                backend.weight(),
                backend.activeConnections(),
                backend.totalRequests(),
                backend.failedRequests(),
                breaker.state().name(),
                breaker.consecutiveFailures(),
                breaker.remainingOpenMillis(),
                breaker.lastTransitionAt());
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
