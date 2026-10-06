package com.gatepulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.gatepulse.circuitbreaker.CircuitState;
import com.gatepulse.config.GatewayConfig;
import com.gatepulse.dummy.DummyBackendServer;
import com.gatepulse.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.gatepulse.TestBackends.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for Phase 2: rate limiting, caching, circuit breaking, metrics, the admin
 * API (with token auth) and the live SSE stream, against a real gateway and real backends.
 */
class ResilienceIntegrationTest {

    private static final String TOKEN = "test-token";
    private static final Map<String, String> AUTH = Map.of("Authorization", "Bearer " + TOKEN);

    private final List<DummyBackendServer> backends = new ArrayList<>();
    private GatewayServer gateway;
    private HttpTestClient http;
    private String base;

    @BeforeEach
    void setUp() {
        for (int i = 1; i <= 3; i++) {
            backends.add(new DummyBackendServer("backend-" + i, 0).start());
        }
        Map<String, String> env = new HashMap<>();
        env.put("PORT", "0");
        env.put("MODE", "external");
        env.put("BACKEND_URLS", backends.stream().map(b -> "http://127.0.0.1:" + b.port()).collect(Collectors.joining(",")));
        env.put("HEALTH_CHECK_INTERVAL_MS", "100");
        env.put("HEALTH_CHECK_TIMEOUT_MS", "500");
        env.put("CB_FAILURE_THRESHOLD", "3");
        env.put("CB_OPEN_DURATION_MS", "500");
        env.put("CB_HALF_OPEN_TRIALS", "2");
        env.put("ADMIN_TOKEN", TOKEN);
        gateway = new GatewayServer(GatewayConfig.fromEnv(env)).start();
        http = new HttpTestClient();
        base = "http://127.0.0.1:" + gateway.port();
    }

    @AfterEach
    void tearDown() {
        http.close();
        gateway.close();
        backends.forEach(DummyBackendServer::close);
    }

    private HttpResponse<String> getAs(String apiKey, String path) {
        return http.get(base + path, Map.of("X-API-Key", apiKey));
    }

    private HttpResponse<String> admin(String method, String path, String body) {
        return http.send(method, base + path, body, AUTH);
    }

    private JsonNode json(HttpResponse<String> response) {
        return Json.parse(response.body().getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode metrics() {
        return json(http.get(base + "/admin/metrics"));
    }

    // ---------------------------------------------------------------- rate limiting

    @Test
    void rateLimiterReturns429WithRetryAfterPerClient() {
        assertThat(admin("PUT", "/admin/config/rate-limit",
                "{\"algorithm\":\"token-bucket\",\"limit\":5,\"refillPerSecond\":0.5}").statusCode()).isEqualTo(200);

        for (int i = 0; i < 5; i++) {
            HttpResponse<String> ok = getAs("client-a", "/products/" + (i + 1));
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.headers().firstValue("X-RateLimit-Remaining")).hasValue(Integer.toString(4 - i));
        }
        HttpResponse<String> limited = getAs("client-a", "/products");

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).hasValue("2");
        assertThat(limited.body()).contains("rate_limited");
        assertThat(getAs("client-b", "/products").statusCode()).as("other clients unaffected").isEqualTo(200);
        assertThat(metrics().path("summary").path("rateLimited").asLong()).isEqualTo(1);
    }

    @Test
    void slidingWindowCanBeSelectedAtRuntime() {
        admin("PUT", "/admin/config/rate-limit", "{\"algorithm\":\"sliding-window\",\"limit\":3,\"windowMs\":60000}");

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            statuses.add(getAs("client-sw", "/products").statusCode());
        }

        assertThat(statuses).containsExactly(200, 200, 200, 429);
        assertThat(json(http.get(base + "/admin/config")).path("rateLimit").path("algorithm").asText())
                .isEqualTo("sliding-window");
    }

    // ---------------------------------------------------------------- cache

    @Test
    void cacheServesRepeatedGetsAndCanBeCleared() {
        HttpResponse<String> first = getAs("c", "/products/3");
        HttpResponse<String> second = getAs("c", "/products/3");

        assertThat(first.headers().firstValue("X-Cache")).hasValue("MISS");
        assertThat(second.headers().firstValue("X-Cache")).hasValue("HIT");
        assertThat(second.body()).isEqualTo(first.body());

        assertThat(admin("DELETE", "/admin/cache", null).statusCode()).isEqualTo(200);
        assertThat(getAs("c", "/products/3").headers().firstValue("X-Cache")).hasValue("MISS");

        JsonNode cache = metrics().path("cache");
        assertThat(cache.path("hits").asLong()).isEqualTo(1);
        assertThat(cache.path("hitRatio").asDouble()).isGreaterThan(0);
    }

    @Test
    void cacheTtlAndCapacityAreChangeableAtRuntime() {
        assertThat(admin("PUT", "/admin/config/cache", "{\"ttlMs\":200,\"capacity\":2}").statusCode()).isEqualTo(200);
        getAs("c", "/products/1");
        assertThat(getAs("c", "/products/1").headers().firstValue("X-Cache")).hasValue("HIT");

        await(() -> "MISS".equals(getAs("c", "/products/1").headers().firstValue("X-Cache").orElse("")),
                Duration.ofSeconds(3), "cache entry to expire after 200ms");
        assertThat(json(http.get(base + "/admin/config")).path("cache").path("capacity").asInt()).isEqualTo(2);
        assertThat(admin("PUT", "/admin/config/cache", "{\"ttlMs\":0}").statusCode()).as("validated").isEqualTo(400);
    }

    // ---------------------------------------------------------------- circuit breaker + chaos

    @Test
    void killingABackendOpensItsCircuitWithoutClientErrorsAndReviveRecovers() {
        assertThat(admin("POST", "/admin/backends/backend-2/kill", null).statusCode()).isEqualTo(200);

        // Bypass the cache so every request really hits the backends.
        Map<String, String> noCache = Map.of("Cache-Control", "no-cache", "X-API-Key", "chaos");
        for (int i = 0; i < 30; i++) {
            assertThat(http.get(base + "/products", noCache).statusCode()).as("request %d", i).isEqualTo(200);
        }

        assertThat(gateway.breakers().forBackend("backend-2").state()).isNotEqualTo(CircuitState.CLOSED);
        JsonNode snapshot = metrics();
        JsonNode b2 = findBackend(snapshot, "backend-2");
        assertThat(b2.path("circuitState").asText()).isIn("OPEN", "HALF_OPEN");
        assertThat(snapshot.path("circuitEvents").toString()).contains("\"to\":\"OPEN\"").contains("backend-2");

        assertThat(admin("POST", "/admin/backends/backend-2/revive", null).statusCode()).isEqualTo(200);
        await(() -> {
            http.get(base + "/products", noCache);
            return gateway.breakers().forBackend("backend-2").state() == CircuitState.CLOSED
                    && gateway.registry().byId("backend-2").orElseThrow().isHealthy();
        }, Duration.ofSeconds(10), "backend-2 healthy with circuit CLOSED again");
    }

    @Test
    void injectedLatencyIsForwardedToTheBackend() {
        assertThat(admin("POST", "/admin/backends/backend-1/latency", "{\"ms\":250}").statusCode()).isEqualTo(200);
        assertThat(admin("POST", "/admin/backends/backend-9/latency", "{\"ms\":250}").statusCode()).isEqualTo(404);
        assertThat(admin("POST", "/admin/backends/backend-1/latency", "{\"ms\":-1}").statusCode()).isEqualTo(400);
        assertThat(http.get("http://127.0.0.1:" + backends.get(0).port() + "/admin/state").body())
                .contains("\"extraLatencyMs\":250");
    }

    // ---------------------------------------------------------------- admin API

    @Test
    void changesRequireTheAdminTokenButReadsArePublic() {
        String body = "{\"strategy\":\"least-connections\"}";
        assertThat(http.send("PUT", base + "/admin/config/load-balancer", body, Map.of()).statusCode()).isEqualTo(401);
        assertThat(http.send("PUT", base + "/admin/config/load-balancer", body,
                Map.of("Authorization", "Bearer wrong")).statusCode()).isEqualTo(403);
        assertThat(http.send("PUT", base + "/admin/config/load-balancer", body,
                Map.of("X-Admin-Token", TOKEN)).statusCode()).isEqualTo(200);

        JsonNode config = json(http.get(base + "/admin/config"));
        assertThat(config.path("adminTokenRequired").asBoolean()).isTrue();
        assertThat(config.path("loadBalancer").path("strategy").asText()).isEqualTo("least-connections");
        assertThat(http.get(base + "/admin/metrics").statusCode()).isEqualTo(200);
    }

    @Test
    void loadBalancerCanBeSwitchedAndBadInputIsRejected() {
        for (String strategy : List.of("weighted-round-robin", "least-connections", "round-robin")) {
            HttpResponse<String> response = admin("PUT", "/admin/config/load-balancer", "{\"strategy\":\"" + strategy + "\"}");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json(response).path("loadBalancer").path("strategy").asText()).isEqualTo(strategy);
        }
        assertThat(admin("PUT", "/admin/config/load-balancer", "{\"strategy\":\"random\"}").statusCode()).isEqualTo(400);
        assertThat(admin("PUT", "/admin/config/load-balancer", "{not json").statusCode()).isEqualTo(400);
        assertThat(admin("PUT", "/admin/config/rate-limit", "{\"limit\":\"ten\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void unknownAdminPathsAreNeverProxiedToBackends() {
        // The dummy backends have POST /admin/kill. Through the gateway it must be a 404, not a proxied kill.
        HttpResponse<String> response = admin("POST", "/admin/kill", null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(backends).allMatch(DummyBackendServer::isAlive);
    }

    @Test
    void metricsSnapshotDescribesTraffic() {
        for (int i = 0; i < 10; i++) {
            getAs("m", "/products");
        }
        getAs("m", "/products/999");

        JsonNode snapshot = metrics();

        assertThat(snapshot.path("summary").path("totalRequests").asLong()).isEqualTo(11);
        assertThat(snapshot.path("statusCodes").path("200").asLong()).isEqualTo(10);
        assertThat(snapshot.path("statusCodes").path("404").asLong()).isEqualTo(1);
        assertThat(snapshot.path("latency").path("samples").asLong()).isEqualTo(11);
        assertThat(snapshot.path("latency").path("p99").asDouble()).isPositive();
        assertThat(snapshot.path("backends")).hasSize(3);
        assertThat(snapshot.path("timeline")).hasSize(60);
        assertThat(snapshot.path("loadBalancer").asText()).isEqualTo("round-robin");
        assertThat(snapshot.path("lastRequestSeq").asLong()).isEqualTo(11);

        JsonNode requests = json(http.get(base + "/admin/requests?since=9")).path("requests");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).path("status").asInt()).isEqualTo(404);
        assertThat(requests.get(0).path("cache").asText()).isEqualTo("HIT");
    }

    @Test
    void sseStreamPushesMetricsAndRequestEvents() throws Exception {
        getAs("sse", "/products"); // something for the initial "requests" event

        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/admin/stream"))
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<InputStream> response = http.raw().send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("text/event-stream"));

        Set<String> events = new HashSet<>();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        String event = line.substring(6).trim();
                        synchronized (events) {
                            events.add(event);
                        }
                        if (event.equals("metrics")) {
                            getAs("sse", "/products/2"); // new traffic should arrive as a "requests" event
                        }
                    }
                    if (line.startsWith("data:") && line.contains("\"summary\"")) {
                        synchronized (events) {
                            events.add("metrics-data");
                        }
                    }
                    synchronized (events) {
                        if (events.contains("metrics") && events.contains("requests") && events.contains("metrics-data")) {
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        reader.get(10, TimeUnit.SECONDS);
        assertThat(events).contains("metrics", "requests", "metrics-data");
    }

    @Test
    void trafficSpikeTripsTheRateLimiter() {
        assertThat(admin("POST", "/admin/traffic/spike", "{\"requests\":150}").statusCode()).isEqualTo(202);

        // Default token bucket is 100 per client; the spike uses a single client key.
        await(() -> metrics().path("summary").path("rateLimited").asLong() > 0, Duration.ofSeconds(10),
                "spike to produce 429s");
        await(() -> !metrics().path("traffic").path("spikeRunning").asBoolean(), Duration.ofSeconds(10),
                "spike to finish");
        assertThat(metrics().path("summary").path("totalRequests").asLong()).isGreaterThanOrEqualTo(150);
        assertThat(admin("POST", "/admin/traffic/spike", "{\"requests\":999999}").statusCode()).isEqualTo(400);
    }

    @Test
    void steadyTrafficStartsAndStops() {
        HttpResponse<String> started = admin("POST", "/admin/traffic/steady", "{\"rps\":50}");
        assertThat(started.statusCode()).isEqualTo(200);
        assertThat(json(started).path("steadyRunning").asBoolean()).isTrue();

        await(() -> metrics().path("summary").path("totalRequests").asLong() >= 20, Duration.ofSeconds(5),
                "steady traffic to flow");

        HttpResponse<String> stopped = admin("DELETE", "/admin/traffic/steady", null);
        assertThat(json(stopped).path("steadyRunning").asBoolean()).isFalse();
    }

    @Test
    void corsPreflightIsAnsweredForTheDashboard() {
        HttpResponse<String> preflight = http.send("OPTIONS", base + "/admin/config/cache", null, Map.of(
                "Origin", "https://gatepulse.example",
                "Access-Control-Request-Method", "PUT",
                "Access-Control-Request-Headers", "authorization,content-type"));

        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isPresent();
    }

    private static JsonNode findBackend(JsonNode snapshot, String id) {
        for (JsonNode backend : snapshot.path("backends")) {
            if (backend.path("id").asText().equals(id)) {
                return backend;
            }
        }
        throw new AssertionError("backend " + id + " not in snapshot");
    }
}
