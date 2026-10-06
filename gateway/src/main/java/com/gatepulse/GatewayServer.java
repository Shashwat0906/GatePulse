package com.gatepulse;

import com.gatepulse.backend.Backend;
import com.gatepulse.backend.BackendRegistry;
import com.gatepulse.config.GatewayConfig;
import com.gatepulse.core.Filter;
import com.gatepulse.core.GatewayHandler;
import com.gatepulse.health.HealthChecker;
import com.gatepulse.health.HealthProbe;
import com.gatepulse.loadbalancer.LoadBalancer;
import com.gatepulse.loadbalancer.RoundRobinStrategy;
import com.gatepulse.proxy.BackendClient;
import com.gatepulse.proxy.ProxyFilter;
import com.gatepulse.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wires every component together and owns their lifecycle.
 *
 * <p>This is the composition root: the only place that knows the concrete classes and the
 * order of the filter chain. Everything else depends on small interfaces, which is what
 * makes each piece unit-testable on its own.
 */
public final class GatewayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    /** Methods forwarded to backends. OPTIONS is answered by the CORS plugin. */
    private static final List<HandlerType> PROXIED_METHODS = List.of(
            HandlerType.GET, HandlerType.POST, HandlerType.PUT, HandlerType.PATCH, HandlerType.DELETE);

    private final GatewayConfig config;
    private final BackendRegistry registry;
    private final LoadBalancer loadBalancer;
    private final BackendClient backendClient;
    private final HttpClient healthHttpClient;
    private final HealthChecker healthChecker;
    private final List<Filter> filters;
    private Javalin app;
    private Instant startedAt;

    public GatewayServer(GatewayConfig config) {
        this.config = config;
        this.registry = new BackendRegistry(config.backends());
        this.loadBalancer = new LoadBalancer(registry, new RoundRobinStrategy());
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

        // The request pipeline. Phase 2 inserts metrics, rate limiting, cache and circuit
        // breaking in front of the proxy.
        this.filters = List.of(new ProxyFilter(loadBalancer, backendClient, config.maxProxyAttempts()));
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
                rule.exposeHeader("X-Request-Id");
                rule.exposeHeader("X-Gateway-Backend");
            }));
        });

        // Gateway-owned routes are registered first so they win over the catch-all proxy route.
        app.get("/", this::info);
        app.get("/health", this::health);

        GatewayHandler proxyHandler = new GatewayHandler(filters, config.trustForwardedHeaders());
        for (HandlerType method : PROXIED_METHODS) {
            app.addHttpHandler(method, "/<path>", proxyHandler);
        }

        app.start(config.port());
        startedAt = Instant.now();
        healthChecker.start();
        log.info("event=gateway_started port={} mode={} backends={}", app.port(), config.mode(), registry.all());
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

    /**
     * Stops accepting requests first, then background work, then outbound clients, so
     * in-flight requests are never left talking to a closed HTTP client.
     */
    @Override
    public synchronized void close() {
        if (app != null) {
            app.stop();
            app = null;
        }
        healthChecker.close();
        backendClient.close();
        healthHttpClient.close();
        log.info("event=gateway_stopped");
    }

    // ---------------------------------------------------------------- gateway-owned endpoints

    private void info(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "GatePulse API Gateway");
        body.put("mode", config.mode().name().toLowerCase());
        body.put("loadBalancer", loadBalancer.strategy().name());
        body.put("health", "/health");
        body.put("sampleRoutes", List.of("/products", "/products/{id}", "/slow"));
        Json.respond(ctx, 200, body);
    }

    /**
     * Liveness of the gateway itself. Always 200 while the process is serving, even if every
     * backend is down: the hosting platform should restart the gateway only when the gateway
     * is broken, not when its backends are.
     */
    private void health(Context ctx) {
        List<Map<String, Object>> backends = registry.all().stream().map(GatewayServer::describe).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        body.put("mode", config.mode().name().toLowerCase());
        body.put("healthyBackends", registry.all().stream().filter(Backend::isHealthy).count());
        body.put("backends", backends);
        Json.respond(ctx, 200, body);
    }

    private static Map<String, Object> describe(Backend backend) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", backend.id());
        m.put("url", backend.baseUri().toString());
        m.put("healthy", backend.isHealthy());
        m.put("weight", backend.weight());
        m.put("activeConnections", backend.activeConnections());
        m.put("totalRequests", backend.totalRequests());
        m.put("failedRequests", backend.failedRequests());
        return m;
    }
}
