package com.gatepulse.admin;

import com.gatepulse.circuitbreaker.CircuitTransition;
import com.gatepulse.metrics.MetricsCollector.LatencySummary;
import com.gatepulse.metrics.MetricsCollector.TimelinePoint;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything the dashboard shows, in one JSON document. Served by {@code GET /admin/metrics}
 * and pushed every second over {@code GET /admin/stream}.
 */
public record GatewaySnapshot(
        long timestamp,
        long uptimeSeconds,
        String mode,
        Summary summary,
        LatencySummary latency,
        Map<String, Long> statusCodes,
        CacheView cache,
        RateLimitView rateLimit,
        String loadBalancer,
        List<BackendView> backends,
        List<CircuitTransition> circuitEvents,
        List<TimelinePoint> timeline,
        TrafficGenerator.Status traffic,
        long lastRequestSeq) {

    /**
     * Headline numbers for the stat cards.
     *
     * @param requestsPerSecond average over the last 5 completed seconds
     * @param errorRate         5xx share over the last 60 seconds (0..1)
     */
    public record Summary(long totalRequests, double requestsPerSecond, double errorRate, long serverErrors,
                          long rateLimited, double cacheHitRatio, int inFlight) {
    }

    public record CacheView(boolean enabled, long ttlMs, int capacity, int size, long hits, long misses,
                            long evictions, long expirations, double hitRatio) {
    }

    public record RateLimitView(boolean enabled, String algorithm, int limit, double refillPerSecond, long windowMs,
                                int trackedClients) {
    }

    public record BackendView(String id, String url, boolean healthy, int weight, int activeConnections,
                              long totalRequests, long failedRequests, String circuitState,
                              int consecutiveFailures, long circuitOpenRemainingMs, Instant circuitChangedAt) {
    }
}
