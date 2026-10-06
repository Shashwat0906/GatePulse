package com.gatepulse;

import com.gatepulse.backend.Backend;
import com.gatepulse.config.GatewayConfig;
import com.gatepulse.dummy.DummyBackendServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.gatepulse.TestBackends.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: a real gateway in front of three real dummy backends, all on random ports.
 * Health checks run every 100ms so state changes are observed quickly.
 */
class GatewayIntegrationTest {

    private final List<DummyBackendServer> backends = new ArrayList<>();
    private GatewayServer gateway;
    private HttpTestClient http;
    private String base;

    @BeforeEach
    void setUp() {
        for (int i = 1; i <= 3; i++) {
            backends.add(new DummyBackendServer("backend-" + i, 0).start());
        }
        String urls = backends.stream()
                .map(b -> "http://127.0.0.1:" + b.port())
                .collect(Collectors.joining(","));

        GatewayConfig config = GatewayConfig.fromEnv(Map.of(
                "PORT", "0",
                "MODE", "external",
                "BACKEND_URLS", urls,
                "HEALTH_CHECK_INTERVAL_MS", "100",
                "HEALTH_CHECK_TIMEOUT_MS", "500",
                "HEALTH_UNHEALTHY_THRESHOLD", "2",
                "HEALTH_HEALTHY_THRESHOLD", "2"));
        gateway = new GatewayServer(config).start();
        http = new HttpTestClient();
        base = "http://127.0.0.1:" + gateway.port();
    }

    @AfterEach
    void tearDown() {
        http.close();
        gateway.close();
        backends.forEach(DummyBackendServer::close);
    }

    private Backend gatewayView(String id) {
        return gateway.registry().byId(id).orElseThrow();
    }

    /** Sends {@code n} GETs and counts which backend served each one. Fails on any non-200. */
    private Map<String, Integer> sendAndCount(int n) {
        Map<String, Integer> servedBy = new HashMap<>();
        for (int i = 0; i < n; i++) {
            HttpResponse<String> response = http.get(base + "/products");
            assertThat(response.statusCode()).as("request %d: %s", i, response.body()).isEqualTo(200);
            String backend = response.headers().firstValue("X-Gateway-Backend").orElseThrow();
            servedBy.merge(backend, 1, Integer::sum);
        }
        return servedBy;
    }

    @Test
    void proxiesRequestsAndAddsGatewayHeaders() {
        HttpResponse<String> response = http.get(base + "/products/2");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Wireless Mouse");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(response.headers().firstValue("X-Gateway-Backend")).isPresent();
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/json"));
    }

    @Test
    void roundRobinSpreadsTrafficEvenly() {
        Map<String, Integer> servedBy = sendAndCount(30);

        assertThat(servedBy).containsOnlyKeys("backend-1", "backend-2", "backend-3");
        assertThat(servedBy.values()).allMatch(count -> count == 10);
    }

    @Test
    void killedBackendCausesNoClientErrorsAndRejoinsAfterRevive() {
        backends.get(1).kill(); // backend-2

        // Immediately, before health checks notice: retries hide the failure from clients.
        Map<String, Integer> duringFailure = sendAndCount(30);
        assertThat(duringFailure).doesNotContainKey("backend-2");

        // Health checker takes it out of rotation.
        await(() -> !gatewayView("backend-2").isHealthy(), Duration.ofSeconds(5), "backend-2 marked unhealthy");
        Map<String, Integer> afterDetection = sendAndCount(20);
        assertThat(afterDetection).containsOnlyKeys("backend-1", "backend-3");

        // Revive: back in rotation once enough health checks pass.
        backends.get(1).revive();
        await(() -> gatewayView("backend-2").isHealthy(), Duration.ofSeconds(5), "backend-2 marked healthy again");
        assertThat(sendAndCount(30)).containsKey("backend-2");
    }

    @Test
    void returns503WhenEveryBackendIsDown() {
        backends.forEach(DummyBackendServer::kill);
        await(() -> gateway.registry().all().stream().noneMatch(Backend::isHealthy),
                Duration.ofSeconds(5), "all backends unhealthy");

        HttpResponse<String> response = http.get(base + "/products");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("no_backend_available");
    }

    @Test
    void gatewayHealthStaysUpWhenBackendsAreDown() {
        backends.forEach(DummyBackendServer::kill);
        await(() -> gateway.registry().all().stream().noneMatch(Backend::isHealthy),
                Duration.ofSeconds(5), "all backends unhealthy");

        HttpResponse<String> response = http.get(base + "/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").contains("\"healthyBackends\":0");
    }

    @Test
    void postBodyIsForwarded() {
        // The dummy has no POST /products route, so a 404 proves the request reached a
        // backend (not a gateway error) and was not retried.
        HttpResponse<String> response = http.post(base + "/products", "{\"name\":\"x\"}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("X-Gateway-Attempts")).hasValue("1");
    }
}
