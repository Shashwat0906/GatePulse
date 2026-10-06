package com.gatepulse;

import com.gatepulse.admin.AdminAuth;
import com.gatepulse.admin.AdminController;
import com.gatepulse.admin.BackendChaosClient;
import com.gatepulse.admin.MetricsBroadcaster;
import com.gatepulse.admin.SnapshotService;
import com.gatepulse.admin.TrafficGenerator;
import com.gatepulse.backend.Backend;
import com.gatepulse.backend.BackendRegistry;
import com.gatepulse.cache.CacheFilter;
import com.gatepulse.cache.ResponseCache;
import com.gatepulse.circuitbreaker.CircuitBreakerRegistry;
import com.gatepulse.config.GatewayConfig;
import com.gatepulse.core.Filter;
import com.gatepulse.core.GatewayHandler;
import com.gatepulse.health.HealthChecker;
import com.gatepulse.health.HealthProbe;
import com.gatepulse.loadbalancer.LoadBalancer;
import com.gatepulse.loadbalancer.LoadBalancingStrategies;
import com.gatepulse.metrics.MetricsCollector;
import com.gatepulse.metrics.MetricsFilter;
import com.gatepulse.proxy.BackendClient;
import com.gatepulse.proxy.ProxyFilter;
import com.gatepulse.ratelimit.RateLimitFilter;
import com.gatepulse.ratelimit.RateLimiter;
import com.gatepulse.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Wires every component together and owns their lifecycle.
 *
 * <p>This is the composition root: the only place that knows the concrete classes and the order
 * of the filter chain. Everything else depends on small interfaces, which is what makes each
 * piece unit-testable on its own.
 *
 * <h2>The request pipeline</h2>
 * <pre>
 *   MetricsFilter        times and records every request (outermost, so it sees 429s and cache hits)
 *     RateLimitFilter    429 if the client is over its allowance
 *       CacheFilter      X-Cache: HIT returns early; MISS continues and stores the 200
 *         ProxyFilter    load balancer -> circuit breaker -> backend, with failover
 * </pre>
 */
public final class GatewayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    /** Methods forwarded to backends. OPTIONS is answered by the CORS plugin. */
    private static final List<HandlerType> PROXIED_METHODS = List.of(
            HandlerType.GET, HandlerType.POST, HandlerType.PUT, HandlerType.PATCH, HandlerType.DELETE);

    private static final long RATE_LIMIT_EVICTION_SECONDS = 30;
    private static final long BROADCAST_INTERVAL_MILLIS = 1000;

    private final GatewayConfig config;
    private final BackendRegistry registry;
    private final CircuitBreakerRegistry breakers;
    private final LoadBalancer loadBalancer;
    private final RateLimiter rateLimiter;
    private final ResponseCache cache;
    private final MetricsCollector metrics;
    private final BackendClient backendClient;
    private final HttpClient healthHttpClient;
    private final HealthChecker healthChecker;
    private final BackendChaosClient chaosClient;
    private final AdminAuth adminAuth;
    private final List<Filter> filters;
    private final ScheduledExecutorService maintenance;

    // Created in start(): they need the bound port.
    private TrafficGenerator trafficGenerator;
    private MetricsBroadcaster broadcaster;
    private volatile Javalin app;

    public GatewayServer(GatewayConfig config) {
        this.config = config;
        this.registry = new BackendRegistry(config.backends());
        this.breakers = new CircuitBreakerRegistry(
                registry.all().stream().map(Backend::id).toList(), config.circuitBreaker());

        // A backend is eligible only if the health checker thinks it is up AND its circuit lets traffic through.
        this.loadBalancer = new LoadBalancer(registry, LoadBalancingStrategies.create(config.loadBalancerStrategy()),
                backend -> backend.isHealthy() && breakers.forBackend(backend.id()).allowsTraffic());

        this.rateLimiter = new RateLimiter(config.rateLimit());
        this.cache = new ResponseCache(config.cache());
        this.metrics = new MetricsCollector();
        this.backendClient = new BackendClient(
                config.backendConnectTimeout(), config.backendRequestTimeout(), config.trustForwardedHeaders());

        // A separate client for health probes so probe traffic never competes with user traffic.
        this.healthHttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.healthCheckTimeout())
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
        this.healthChecker = new HealthChecker(
                registry,
                HealthProbe.http(healthHttpClient, config.healthCheckTimeout()),
                config.healthCheckInterval(),
                config.unhealthyThreshold(),
                config.healthyThreshold());
        this.chaosClient = new BackendChaosClient();
        this.adminAuth = new AdminAuth(config.adminToken());

        this.filters = List.of(
                new MetricsFilter(metrics),
                new RateLimitFilter(rateLimiter),
                new CacheFilter(cache),
                new ProxyFilter(loadBalancer, breakers, backendClient, config.maxProxyAttempts()));

        this.maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gateway-maintenance");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized GatewayServer start() {
        if (app != null) {
            throw new IllegalStateException("Gateway already started");
        }
        app = Javalin.create(javalin -> {
            javalin.showJavalinBanner = false;
            // Each proxied request blocks while waiting for a backend. On virtual threads that
            // blocking is nearly free, so throughput is not capped by a fixed thread pool.
            javalin.useVirtualThreads = true;
            javalin.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                List<String> origins = config.corsOrigins();
                if (origins.contains("*")) {
                    rule.anyHost();
                } else {
                    rule.allowHost(origins.get(0), origins.subList(1, origins.size()).toArray(String[]::new));
                }
                for (String header : List.of("X-Request-Id", "X-Gateway-Backend", "X-Gateway-Attempts", "X-Cache",
                        "X-RateLimit-Limit", "X-RateLimit-Remaining", "Retry-After")) {
                    rule.exposeHeader(header);
                }
            }));
        });

        // The traffic generator calls the gateway's own port, which is only known once bound
        // (tests use port 0), so it reads the port lazily.
        trafficGenerator = new TrafficGenerator(this::port, config.steadyTrafficMaxDuration());
        SnapshotService snapshots = new SnapshotService(config.mode(), metrics, registry, breakers, loadBalancer,
                cache, rateLimiter, trafficGenerator);
        broadcaster = new MetricsBroadcaster(snapshots, metrics.requestLog(), BROADCAST_INTERVAL_MILLIS);

        // Gateway-owned routes are registered first so they win over the catch-all proxy route.
        app.get("/", this::info);
        app.get("/health", this::health);
        new AdminController(registry, loadBalancer, rateLimiter, cache, breakers, metrics.requestLog(), snapshots,
                broadcaster, trafficGenerator, chaosClient, adminAuth).register(app);

        GatewayHandler proxyHandler = new GatewayHandler(filters, config.trustForwardedHeaders());
        for (HandlerType method : PROXIED_METHODS) {
            app.addHttpHandler(method, "/<path>", proxyHandler);
        }

        app.start(config.port());
        healthChecker.start();
        broadcaster.start();
        maintenance.scheduleWithFixedDelay(this::evictIdleClients,
                RATE_LIMIT_EVICTION_SECONDS, RATE_LIMIT_EVICTION_SECONDS, TimeUnit.SECONDS);

        if (!adminAuth.required()) {
            log.warn("event=admin_open msg=\"ADMIN_TOKEN is not set: admin changes are allowed without a token (demo mode)\"");
        }
        log.info("event=gateway_started port={} config={}", app.port(), config);
        return this;
    }

    public int port() {
        if (app == null) {
            throw new IllegalStateException("Gateway is not started");
        }
        return app.port();
    }

    public BackendRegistry registry() {
        return registry;
    }

    public LoadBalancer loadBalancer() {
        return loadBalancer;
    }

    public CircuitBreakerRegistry breakers() {
        return breakers;
    }

    public MetricsCollector metrics() {
        return metrics;
    }

    public ResponseCache cache() {
        return cache;
    }

    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    /**
     * Stops accepting requests first, then background work, then outbound clients, so in-flight
     * requests are never left talking to a closed HTTP client.
     */
    @Override
    public synchronized void close() {
        if (broadcaster != null) {
            broadcaster.close(); // close SSE streams first, or the server waits for them on stop
        }
        if (trafficGenerator != null) {
            trafficGenerator.close();
        }
        if (app != null) {
            app.stop();
            app = null;
        }
        maintenance.shutdownNow();
        healthChecker.close();
        backendClient.close();
        healthHttpClient.close();
        chaosClient.close();
        log.info("event=gateway_stopped");
    }

    private void evictIdleClients() {
        try {
            int removed = rateLimiter.evictIdle();
            if (removed > 0) {
                log.debug("event=rate_limit_eviction removed={} remaining={}", removed, rateLimiter.trackedClients());
            }
        } catch (RuntimeException e) {
            log.error("event=rate_limit_eviction_failed", e);
        }
    }

    // ---------------------------------------------------------------- gateway-owned endpoints

    private void info(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "GatePulse API Gateway");
        body.put("mode", config.mode().name().toLowerCase(Locale.ROOT));
        body.put("loadBalancer", loadBalancer.strategy().name());
        body.put("health", "/health");
        body.put("metrics", "/admin/metrics");
        body.put("stream", "/admin/stream");
        body.put("sampleRoutes", List.of("/products", "/products/{id}", "/slow"));
        Json.respond(ctx, 200, body);
    }

    /**
     * Liveness of the gateway itself. Always 200 while the process is serving, even if every
     * backend is down: the hosting platform should restart the gateway only when the gateway is
     * broken, not when its backends are.
     */
    private void health(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("uptimeSeconds", metrics.uptimeSeconds());
        body.put("mode", config.mode().name().toLowerCase(Locale.ROOT));
        body.put("healthyBackends", registry.all().stream().filter(Backend::isHealthy).count());
        body.put("backends", registry.all().stream().map(this::describe).toList());
        Json.respond(ctx, 200, body);
    }

    private Map<String, Object> describe(Backend backend) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", backend.id());
        m.put("url", backend.baseUri().toString());
        m.put("healthy", backend.isHealthy());
        m.put("circuitState", breakers.forBackend(backend.id()).state().name());
        m.put("weight", backend.weight());
        m.put("activeConnections", backend.activeConnections());
        m.put("totalRequests", backend.totalRequests());
        m.put("failedRequests", backend.failedRequests());
        return m;
    }
}
