package com.gatepulse.dummy;

import com.gatepulse.HttpTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DummyBackendServerTest {

    private DummyBackendServer server;
    private HttpTestClient http;
    private String base;

    @BeforeEach
    void setUp() {
        server = new DummyBackendServer("backend-x", 0).start();
        http = new HttpTestClient();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        http.close();
        server.close();
    }

    @Test
    void servesProductsAndIdentifiesItself() {
        HttpResponse<String> response = http.get(base + "/products");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"servedBy\":\"backend-x\"").contains("Mechanical Keyboard");
        assertThat(response.headers().firstValue("X-Backend-Id")).hasValue("backend-x");
    }

    @Test
    void servesSingleProductOr404() {
        assertThat(http.get(base + "/products/3").body()).contains("4K Monitor");
        assertThat(http.get(base + "/products/999").statusCode()).isEqualTo(404);
    }

    @Test
    void killMakesEverythingAnswer503AndReviveRestores() {
        assertThat(http.post(base + "/admin/kill", "").statusCode()).isEqualTo(200);

        assertThat(http.get(base + "/products").statusCode()).isEqualTo(503);
        assertThat(http.get(base + "/health").statusCode()).isEqualTo(503);

        assertThat(http.post(base + "/admin/revive", "").statusCode()).isEqualTo(200);

        assertThat(http.get(base + "/products").statusCode()).isEqualTo(200);
        assertThat(http.get(base + "/health").statusCode()).isEqualTo(200);
    }

    @Test
    void injectedLatencySlowsBusinessEndpointsButNotHealth() {
        assertThat(http.post(base + "/admin/latency", "{\"ms\": 300}").statusCode()).isEqualTo(200);

        long start = System.nanoTime();
        assertThat(http.get(base + "/products").statusCode()).isEqualTo(200);
        long productsMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        assertThat(http.get(base + "/health").statusCode()).isEqualTo(200);
        long healthMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(productsMs).isGreaterThanOrEqualTo(300);
        assertThat(healthMs).isLessThan(300);
    }

    @Test
    void latencyAcceptsQueryParamAndRejectsBadValues() {
        assertThat(http.post(base + "/admin/latency?ms=50", "").statusCode()).isEqualTo(200);
        assertThat(http.post(base + "/admin/latency?ms=abc", "").statusCode()).isEqualTo(400);
        assertThat(http.post(base + "/admin/latency", "{\"ms\": -5}").statusCode()).isEqualTo(400);
        assertThat(http.post(base + "/admin/latency", "not json").statusCode()).isEqualTo(400);
    }

    @Test
    void slowEndpointTakesBetweenHalfAndTwoSeconds() {
        long start = System.nanoTime();
        HttpResponse<String> response = http.get(base + "/slow");
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ms).isBetween((long) DummyBackendServer.SLOW_MIN_MS, DummyBackendServer.SLOW_MAX_MS + 1_000L);
    }
}
