package com.gatepulse.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gatepulse.backend.Backend;
import com.gatepulse.backend.BackendRegistry;
import com.gatepulse.cache.CacheSettings;
import com.gatepulse.cache.ResponseCache;
import com.gatepulse.circuitbreaker.CircuitBreakerConfig;
import com.gatepulse.circuitbreaker.CircuitBreakerRegistry;
import com.gatepulse.loadbalancer.LoadBalancer;
import com.gatepulse.loadbalancer.LoadBalancingStrategies;
import com.gatepulse.metrics.RequestLog;
import com.gatepulse.ratelimit.RateLimitAlgorithm;
import com.gatepulse.ratelimit.RateLimitConfig;
import com.gatepulse.ratelimit.RateLimiter;
import com.gatepulse.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The admin REST API used by the dashboard.
 *
 * <pre>
 * Reads (public)
 *   GET    /admin/metrics                      full snapshot (polling fallback for the stream)
 *   GET    /admin/stream                       Server-Sent Events: "metrics" every second + "requests"
 *   GET    /admin/requests?since=N&amp;limit=M     request log entries after sequence N
 *   GET    /admin/config                       current runtime configuration
 *
 * Changes (require the admin token when ADMIN_TOKEN is set)
 *   PUT    /admin/config/load-balancer         {"strategy": "least-connections"}
 *   PUT    /admin/config/rate-limit            {"enabled", "algorithm", "limit", "refillPerSecond", "windowMs"}
 *   PUT    /admin/config/cache                 {"enabled", "ttlMs", "capacity"}
 *   PUT    /admin/config/circuit-breaker       {"failureThreshold", "openDurationMs", "halfOpenTrials"}
 *   DELETE /admin/cache                        empty the cache
 *   POST   /admin/backends/{id}/kill | revive  chaos: make a backend fail / recover
 *   POST   /admin/backends/{id}/latency        {"ms": 800}
 *   POST   /admin/backends/{id}/reset-circuit  force a circuit back to CLOSED
 *   POST   /admin/traffic/spike                {"requests": 500, "path": "/products"}
 *   POST   /admin/traffic/steady               {"rps": 20}
 *   DELETE /admin/traffic/steady               stop steady traffic
 * </pre>
 * All PUT bodies are partial: omitted fields keep their current value.
 */
public final class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final BackendRegistry registry;
    private final LoadBalancer loadBalancer;
    private final RateLimiter rateLimiter;
    private final ResponseCache cache;
    private final CircuitBreakerRegistry breakers;
    private final RequestLog requestLog;
    private final SnapshotService snapshots;
    private final MetricsBroadcaster broadcaster;
    private final TrafficGenerator traffic;
    private final BackendChaosClient chaos;
    private final AdminAuth auth;

    public AdminController(BackendRegistry registry, LoadBalancer loadBalancer, RateLimiter rateLimiter,
                           ResponseCache cache, CircuitBreakerRegistry breakers, RequestLog requestLog,
                           SnapshotService snapshots, MetricsBroadcaster broadcaster, TrafficGenerator traffic,
                           BackendChaosClient chaos, AdminAuth auth) {
        this.registry = registry;
        this.loadBalancer = loadBalancer;
        this.rateLimiter = rateLimiter;
        this.cache = cache;
        this.breakers = breakers;
        this.requestLog = requestLog;
        this.snapshots = snapshots;
        this.broadcaster = broadcaster;
        this.traffic = traffic;
        this.chaos = chaos;
        this.auth = auth;
    }

    /** Registers every admin route. Must run before the catch-all proxy route is added. */
    public void register(Javalin app) {
        app.before("/admin/*", auth);

        app.get("/admin/metrics", ctx -> Json.respond(ctx, 200, snapshots.snapshot()));
        app.sse("/admin/stream", broadcaster::subscribe);
        app.get("/admin/requests", this::requests);
        app.get("/admin/config", ctx -> Json.respond(ctx, 200, currentConfig()));

        app.put("/admin/config/load-balancer", this::updateLoadBalancer);
        app.put("/admin/config/rate-limit", this::updateRateLimit);
        app.put("/admin/config/cache", this::updateCache);
        app.put("/admin/config/circuit-breaker", this::updateCircuitBreaker);
        app.delete("/admin/cache", ctx -> {
            cache.clear();
            Json.respond(ctx, 200, Map.of("cleared", true));
        });

        app.post("/admin/backends/{id}/kill", ctx -> chaosResult(ctx, chaos.kill(backend(ctx)), "killed"));
        app.post("/admin/backends/{id}/revive", ctx -> chaosResult(ctx, chaos.revive(backend(ctx)), "revived"));
        app.post("/admin/backends/{id}/latency", this::setLatency);
        app.post("/admin/backends/{id}/reset-circuit", ctx -> {
            Backend backend = backend(ctx);
            breakers.forBackend(backend.id()).reset();
            Json.respond(ctx, 200, Map.of("backend", backend.id(), "circuitState", "CLOSED"));
        });

        app.post("/admin/traffic/spike", this::spike);
        app.post("/admin/traffic/steady", this::startSteady);
        app.delete("/admin/traffic/steady", ctx -> {
            traffic.stopSteady();
            Json.respond(ctx, 200, traffic.status());
        });

        // Anything else under /admin is a 404 here. Without this, the catch-all proxy route would
        // forward e.g. POST /admin/kill to a backend's own admin API, bypassing the gateway's token.
        for (HandlerType method : List.of(HandlerType.GET, HandlerType.POST, HandlerType.PUT,
                HandlerType.PATCH, HandlerType.DELETE)) {
            app.addHttpHandler(method, "/admin/<rest>", ctx -> {
                throw new ApiException(404, "not_found", "Unknown admin endpoint " + ctx.method() + " " + ctx.path());
            });
        }

        app.exception(ApiException.class, (e, ctx) -> error(ctx, e.status(), e.code(), e.getMessage()));
        app.exception(IllegalArgumentException.class, (e, ctx) -> error(ctx, 400, "bad_request", e.getMessage()));
        app.exception(UncheckedIOException.class, (e, ctx) -> error(ctx, 400, "bad_request", e.getMessage()));
    }

    // ---------------------------------------------------------------- reads

    private void requests(Context ctx) {
        long since = queryLong(ctx, "since", 0);
        int limit = (int) Math.min(queryLong(ctx, "limit", 100), requestLog.capacity());
        Json.respond(ctx, 200, Map.of("lastSeq", requestLog.lastSeq(), "requests", requestLog.since(since, limit)));
    }

    Map<String, Object> currentConfig() {
        RateLimitConfig rl = rateLimiter.config();
        CacheSettings cs = cache.settings();
        CircuitBreakerConfig cb = breakers.config();

        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("enabled", rl.enabled());
        rateLimit.put("algorithm", rl.algorithm().id());
        rateLimit.put("limit", rl.limit());
        rateLimit.put("refillPerSecond", rl.refillPerSecond());
        rateLimit.put("windowMs", rl.windowMillis());
        rateLimit.put("algorithms", List.of(RateLimitAlgorithm.TOKEN_BUCKET.id(), RateLimitAlgorithm.SLIDING_WINDOW_LOG.id()));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("loadBalancer", Map.of("strategy", loadBalancer.strategy().name(),
                "strategies", LoadBalancingStrategies.NAMES));
        config.put("rateLimit", rateLimit);
        config.put("cache", Map.of("enabled", cs.enabled(), "ttlMs", cs.ttlMillis(), "capacity", cs.capacity()));
        config.put("circuitBreaker", Map.of("failureThreshold", cb.failureThreshold(),
                "openDurationMs", cb.openDuration().toMillis(), "halfOpenTrials", cb.halfOpenTrials()));
        config.put("backends", registry.all().stream()
                .map(b -> Map.of("id", b.id(), "url", b.baseUri().toString(), "weight", b.weight())).toList());
        config.put("adminTokenRequired", auth.required());
        config.put("traffic", Map.of("maxSpikeRequests", TrafficGenerator.MAX_SPIKE_REQUESTS,
                "maxSteadyRps", TrafficGenerator.MAX_STEADY_RPS));
        return config;
    }

    // ---------------------------------------------------------------- config changes

    private void updateLoadBalancer(Context ctx) {
        JsonNode body = Json.parse(ctx.bodyAsBytes());
        String strategy = requiredText(body, "strategy");
        loadBalancer.setStrategy(LoadBalancingStrategies.create(strategy));
        log.info("event=config_changed component=load_balancer strategy={}", loadBalancer.strategy().name());
        Json.respond(ctx, 200, currentConfig());
    }

    private void updateRateLimit(Context ctx) {
        JsonNode body = Json.parse(ctx.bodyAsBytes());
        RateLimitConfig c = rateLimiter.config();
        if (body.has("enabled")) {
            c = c.withEnabled(bool(body, "enabled"));
        }
        if (body.has("algorithm")) {
            c = c.withAlgorithm(RateLimitAlgorithm.fromId(requiredText(body, "algorithm")));
        }
        if (body.has("limit")) {
            c = c.withLimit(intValue(body, "limit"));
        }
        if (body.has("refillPerSecond")) {
            c = c.withRefillPerSecond(number(body, "refillPerSecond"));
        }
        if (body.has("windowMs")) {
            c = c.withWindowMillis(integer(body, "windowMs"));
        }
        rateLimiter.update(c);
        log.info("event=config_changed component=rate_limit config={}", c);
        Json.respond(ctx, 200, currentConfig());
    }

    private void updateCache(Context ctx) {
        JsonNode body = Json.parse(ctx.bodyAsBytes());
        CacheSettings s = cache.settings();
        CacheSettings updated = new CacheSettings(
                body.has("enabled") ? bool(body, "enabled") : s.enabled(),
                body.has("ttlMs") ? integer(body, "ttlMs") : s.ttlMillis(),
                body.has("capacity") ? intValue(body, "capacity") : s.capacity());
        cache.update(updated);
        log.info("event=config_changed component=cache settings={}", updated);
        Json.respond(ctx, 200, currentConfig());
    }

    private void updateCircuitBreaker(Context ctx) {
        JsonNode body = Json.parse(ctx.bodyAsBytes());
        CircuitBreakerConfig c = breakers.config();
        CircuitBreakerConfig updated = new CircuitBreakerConfig(
                body.has("failureThreshold") ? intValue(body, "failureThreshold") : c.failureThreshold(),
                body.has("openDurationMs") ? Duration.ofMillis(integer(body, "openDurationMs")) : c.openDuration(),
                body.has("halfOpenTrials") ? intValue(body, "halfOpenTrials") : c.halfOpenTrials());
        breakers.updateConfig(updated);
        log.info("event=config_changed component=circuit_breaker config={}", updated);
        Json.respond(ctx, 200, currentConfig());
    }

    // ---------------------------------------------------------------- chaos & traffic

    private void setLatency(Context ctx) {
        Backend backend = backend(ctx);
        long ms = integer(Json.parse(ctx.bodyAsBytes()), "ms");
        if (ms < 0 || ms > 10_000) {
            throw new IllegalArgumentException("ms must be between 0 and 10000");
        }
        chaosResult(ctx, chaos.setLatency(backend, (int) ms), "latency_set");
    }

    private void chaosResult(Context ctx, BackendChaosClient.Result result, String action) {
        log.info("event=chaos action={} backend={} backendStatus={}", action, ctx.pathParam("id"), result.status());
        ctx.status(result.status() == 200 ? 200 : 502).contentType(Json.CONTENT_TYPE).result(result.body());
    }

    private void spike(Context ctx) {
        JsonNode body = Json.parse(ctx.bodyAsBytes());
        int requests = body.has("requests") ? intValue(body, "requests") : 500;
        String path = body.has("path") ? requiredText(body, "path") : "/products";
        if (!traffic.spike(requests, path)) {
            throw new ApiException(409, "spike_in_progress", "A traffic spike is already running");
        }
        Json.respond(ctx, 202, Map.of("started", true, "requests", requests, "path", path));
    }

    private void startSteady(Context ctx) {
        JsonNode body = Json.parse(ctx.bodyAsBytes());
        int rps = body.has("rps") ? intValue(body, "rps") : 20;
        traffic.startSteady(rps);
        Json.respond(ctx, 200, traffic.status());
    }

    // ---------------------------------------------------------------- helpers

    private Backend backend(Context ctx) {
        String id = ctx.pathParam("id");
        return registry.byId(id)
                .orElseThrow(() -> new ApiException(404, "unknown_backend", "No backend with id '" + id + "'"));
    }

    private static void error(Context ctx, int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        Json.respond(ctx, status, body);
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("'" + field + "' must be a non-empty string");
        }
        return node.asText();
    }

    private static boolean bool(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isBoolean()) {
            throw new IllegalArgumentException("'" + field + "' must be true or false");
        }
        return node.asBoolean();
    }

    private static long integer(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new IllegalArgumentException("'" + field + "' must be an integer");
        }
        return node.asLong();
    }

    private static int intValue(JsonNode body, String field) {
        long value = integer(body, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("'" + field + "' is out of range");
        }
        return (int) value;
    }

    private static double number(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException("'" + field + "' must be a number");
        }
        return node.asDouble();
    }

    private static long queryLong(Context ctx, String name, long defaultValue) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(0, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + name + "' must be an integer");
        }
    }
}
