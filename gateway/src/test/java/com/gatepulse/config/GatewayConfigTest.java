package com.gatepulse.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayConfigTest {

    @Test
    void defaultsToEmbeddedModeWithThreeBackends() {
        GatewayConfig config = GatewayConfig.fromEnv(Map.of());

        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.mode()).isEqualTo(Mode.EMBEDDED);
        assertThat(config.backends()).extracting(BackendDefinition::id)
                .containsExactly("backend-1", "backend-2", "backend-3");
        assertThat(config.backends()).extracting(d -> d.baseUri().getPort())
                .containsExactly(9001, 9002, 9003);
        assertThat(config.healthCheckInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.corsOrigins()).containsExactly("*");
    }

    @Test
    void parsesExternalBackendsAndWeights() {
        GatewayConfig config = GatewayConfig.fromEnv(Map.of(
                "MODE", "external",
                "PORT", "9000",
                "BACKEND_URLS", "http://backend-1:8080, http://backend-2:8080/ ,http://backend-3:8080",
                "BACKEND_WEIGHTS", "5,1,2"));

        assertThat(config.port()).isEqualTo(9000);
        assertThat(config.mode()).isEqualTo(Mode.EXTERNAL);
        assertThat(config.backends()).extracting(d -> d.baseUri().toString())
                .containsExactly("http://backend-1:8080", "http://backend-2:8080", "http://backend-3:8080");
        assertThat(config.backends()).extracting(BackendDefinition::weight).containsExactly(5, 1, 2);
    }

    @Test
    void externalModeRequiresBackendUrls() {
        assertThatThrownBy(() -> GatewayConfig.fromEnv(Map.of("MODE", "external")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BACKEND_URLS");
    }

    @Test
    void rejectsInvalidValuesWithReadableMessages() {
        assertThatThrownBy(() -> GatewayConfig.fromEnv(Map.of("PORT", "abc")))
                .hasMessageContaining("PORT must be an integer");
        assertThatThrownBy(() -> GatewayConfig.fromEnv(Map.of("MODE", "cloud")))
                .hasMessageContaining("MODE must be one of");
        assertThatThrownBy(() -> GatewayConfig.fromEnv(Map.of("HEALTH_UNHEALTHY_THRESHOLD", "0")))
                .hasMessageContaining("between 1 and 100");
        assertThatThrownBy(() -> GatewayConfig.fromEnv(Map.of("MODE", "external", "BACKEND_URLS", "not a url")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatewayConfig.fromEnv(Map.of("BACKEND_WEIGHTS", "0,1,1")))
                .hasMessageContaining("weight must be between 1 and 100");
        assertThatThrownBy(() -> GatewayConfig.fromEnv(Map.of("LB_STRATEGY", "random")))
                .hasMessageContaining("Unknown load balancing strategy");
        assertThatThrownBy(() -> GatewayConfig.fromEnv(Map.of("RATE_LIMIT_ALGORITHM", "leaky-bucket")))
                .hasMessageContaining("Unknown rate limit algorithm");
    }

    @Test
    void parsesResilienceSettings() {
        GatewayConfig config = GatewayConfig.fromEnv(Map.of(
                "LB_STRATEGY", "least-connections",
                "RATE_LIMIT_ALGORITHM", "sliding-window",
                "RATE_LIMIT_LIMIT", "20",
                "RATE_LIMIT_WINDOW_MS", "2000",
                "CACHE_TTL_MS", "1500",
                "CACHE_CAPACITY", "50",
                "CB_FAILURE_THRESHOLD", "7",
                "CB_OPEN_DURATION_MS", "3000",
                "ADMIN_TOKEN", "s3cret"));

        assertThat(config.loadBalancerStrategy()).isEqualTo("least-connections");
        assertThat(config.rateLimit().algorithm()).isEqualTo(com.gatepulse.ratelimit.RateLimitAlgorithm.SLIDING_WINDOW_LOG);
        assertThat(config.rateLimit().limit()).isEqualTo(20);
        assertThat(config.rateLimit().windowMillis()).isEqualTo(2000);
        assertThat(config.cache().ttlMillis()).isEqualTo(1500);
        assertThat(config.cache().capacity()).isEqualTo(50);
        assertThat(config.circuitBreaker().failureThreshold()).isEqualTo(7);
        assertThat(config.circuitBreaker().openDuration()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.adminTokenRequired()).isTrue();
        assertThat(config.toString()).doesNotContain("s3cret");
    }

    @Test
    void adminTokenIsOptional() {
        assertThat(GatewayConfig.fromEnv(Map.of()).adminTokenRequired()).isFalse();
    }

    @Test
    void parsesListsAndBooleans() {
        GatewayConfig config = GatewayConfig.fromEnv(Map.of(
                "CORS_ORIGINS", "https://gatepulse.vercel.app, http://localhost:5173",
                "TRUST_FORWARDED_HEADERS", "false",
                "EMBEDDED_BACKEND_PORTS", "7001,7002"));

        assertThat(config.corsOrigins()).containsExactly("https://gatepulse.vercel.app", "http://localhost:5173");
        assertThat(config.trustForwardedHeaders()).isFalse();
        assertThat(config.backends()).hasSize(2);
    }
}
