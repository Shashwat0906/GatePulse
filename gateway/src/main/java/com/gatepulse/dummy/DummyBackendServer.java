package com.gatepulse.dummy;

import com.fasterxml.jackson.databind.JsonNode;
import com.gatepulse.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * A deliberately simple "product service" that the gateway sits in front of.
 *
 * <p>It exists to make failures easy to demonstrate. Besides normal endpoints it exposes
 * chaos controls:
 * <ul>
 *   <li>{@code POST /admin/kill}: the process keeps running, but every business endpoint and
 *       {@code /health} answer {@code 503}. Keeping the port open (instead of really exiting)
 *       is what lets {@code /admin/revive} bring it back instantly.</li>
 *   <li>{@code POST /admin/revive}: back to normal.</li>
 *   <li>{@code POST /admin/latency}: adds a fixed delay (ms) to business endpoints, to simulate
 *       a slow dependency. {@code /health} is not delayed, so a slow-but-alive server stays
 *       "healthy" and slowness has to be caught by timeouts and the circuit breaker instead.</li>
 * </ul>
 *
 * <p>Runs embedded inside the gateway JVM ({@code MODE=embedded}) or standalone via
 * {@link #main(String[])} as its own container ({@code MODE=external}).
 */
public final class DummyBackendServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DummyBackendServer.class);

    static final int MAX_INJECTED_LATENCY_MS = 10_000;
    static final int SLOW_MIN_MS = 500;
    static final int SLOW_MAX_MS = 2_000;

    private static final List<Product> CATALOGUE = List.of(
            new Product(1, "Mechanical Keyboard", "peripherals", 4999.00, 42),
            new Product(2, "Wireless Mouse", "peripherals", 1299.00, 120),
            new Product(3, "27\" 4K Monitor", "displays", 28999.00, 15),
            new Product(4, "USB-C Hub", "accessories", 2499.00, 64),
            new Product(5, "Noise-Cancelling Headphones", "audio", 17999.00, 23),
            new Product(6, "Laptop Stand", "accessories", 1899.00, 87));

    private final String id;
    private final int requestedPort;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicInteger extraLatencyMs = new AtomicInteger(0);
    private final LongAdder requestsServed = new LongAdder();
    private Javalin app;

    public DummyBackendServer(String id, int port) {
        this.id = id;
        this.requestedPort = port;
    }

    /** Starts the HTTP server. Port 0 picks a free port (handy in tests). */
    public synchronized DummyBackendServer start() {
        if (app != null) {
            throw new IllegalStateException(id + " is already started");
        }
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            // Virtual threads: /slow sleeps up to 2s; with platform threads a burst of
            // slow requests would exhaust the pool. Virtual threads make blocking cheap.
            config.useVirtualThreads = true;
        });

        app.get("/products", guarded(this::listProducts));
        app.get("/products/{id}", guarded(this::getProduct));
        app.get("/slow", guarded(this::slow));
        app.get("/health", this::health);

        app.get("/admin/state", ctx -> Json.respond(ctx, 200, state()));
        app.post("/admin/kill", ctx -> {
            alive.set(false);
            log.warn("backend={} event=killed", id);
            Json.respond(ctx, 200, state());
        });
        app.post("/admin/revive", ctx -> {
            alive.set(true);
            log.info("backend={} event=revived", id);
            Json.respond(ctx, 200, state());
        });
        app.post("/admin/latency", this::setLatency);

        app.exception(UncheckedIOException.class, (e, ctx) ->
                Json.respond(ctx, 400, Map.of("error", "bad_request", "message", e.getMessage())));

        app.start(requestedPort);
        log.info("backend={} event=started port={}", id, app.port());
        return this;
    }

    public String id() {
        return id;
    }

    public int port() {
        if (app == null) {
            throw new IllegalStateException(id + " is not started");
        }
        return app.port();
    }

    public boolean isAlive() {
        return alive.get();
    }

    public void kill() {
        alive.set(false);
    }

    public void revive() {
        alive.set(true);
    }

    public void setExtraLatencyMs(int ms) {
        extraLatencyMs.set(clampLatency(ms));
    }

    public long requestsServed() {
        return requestsServed.sum();
    }

    @Override
    public synchronized void close() {
        if (app != null) {
            app.stop();
            app = null;
            log.info("backend={} event=stopped", id);
        }
    }

    // ---------------------------------------------------------------- handlers

    /**
     * Wraps a business handler with the shared behaviour: answer 503 when killed,
     * apply injected latency, and count served requests.
     */
    private Handler guarded(Handler delegate) {
        return ctx -> {
            if (!alive.get()) {
                Json.respond(ctx, 503, Map.of("error", "service_unavailable", "backend", id));
                return;
            }
            sleep(extraLatencyMs.get());
            requestsServed.increment();
            ctx.header("X-Backend-Id", id);
            delegate.handle(ctx);
        };
    }

    private void listProducts(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("servedBy", id);
        body.put("count", CATALOGUE.size());
        body.put("products", CATALOGUE);
        Json.respond(ctx, 200, body);
    }

    private void getProduct(Context ctx) {
        Optional<Product> product = parseId(ctx.pathParam("id"))
                .flatMap(pid -> CATALOGUE.stream().filter(p -> p.id() == pid).findFirst());
        if (product.isPresent()) {
            Json.respond(ctx, 200, Map.of("servedBy", id, "product", product.get()));
        } else {
            Json.respond(ctx, 404, Map.of("error", "not_found", "servedBy", id));
        }
    }

    private void slow(Context ctx) {
        int delay = ThreadLocalRandom.current().nextInt(SLOW_MIN_MS, SLOW_MAX_MS + 1);
        sleep(delay);
        Json.respond(ctx, 200, Map.of("servedBy", id, "delayMs", delay));
    }

    private void health(Context ctx) {
        if (alive.get()) {
            Json.respond(ctx, 200, Map.of("status", "UP", "backend", id));
        } else {
            Json.respond(ctx, 503, Map.of("status", "DOWN", "backend", id));
        }
    }

    /** Accepts either {@code ?ms=300} or a JSON body {@code {"ms": 300}}. */
    private void setLatency(Context ctx) {
        String fromQuery = ctx.queryParam("ms");
        int ms;
        if (fromQuery != null) {
            Optional<Integer> parsed = parseId(fromQuery);
            if (parsed.isEmpty()) {
                Json.respond(ctx, 400, Map.of("error", "bad_request", "message", "ms must be an integer"));
                return;
            }
            ms = parsed.get();
        } else {
            JsonNode node = Json.parse(ctx.bodyAsBytes()).path("ms");
            if (!node.canConvertToInt()) {
                Json.respond(ctx, 400, Map.of("error", "bad_request", "message", "body must be {\"ms\": <int>}"));
                return;
            }
            ms = node.asInt();
        }
        if (ms < 0 || ms > MAX_INJECTED_LATENCY_MS) {
            Json.respond(ctx, 400, Map.of("error", "bad_request",
                    "message", "ms must be between 0 and " + MAX_INJECTED_LATENCY_MS));
            return;
        }
        extraLatencyMs.set(ms);
        log.info("backend={} event=latency_set ms={}", id, ms);
        Json.respond(ctx, 200, state());
    }

    private Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("backend", id);
        state.put("alive", alive.get());
        state.put("extraLatencyMs", extraLatencyMs.get());
        state.put("requestsServed", requestsServed.sum());
        return state;
    }

    // ---------------------------------------------------------------- helpers

    private static Optional<Integer> parseId(String raw) {
        try {
            return Optional.of(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static int clampLatency(int ms) {
        return Math.max(0, Math.min(ms, MAX_INJECTED_LATENCY_MS));
    }

    private static void sleep(int ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Standalone entry point for {@code MODE=external}. Reads {@code PORT} (default 8080) and
     * {@code BACKEND_ID} (default {@code backend}).
     */
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String id = System.getenv().getOrDefault("BACKEND_ID", "backend");
        DummyBackendServer server = new DummyBackendServer(id, port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "dummy-shutdown"));
    }
}
