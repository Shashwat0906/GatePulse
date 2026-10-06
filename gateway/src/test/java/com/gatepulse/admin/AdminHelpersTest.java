package com.gatepulse.admin;

import com.gatepulse.metrics.MetricsFilter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminHelpersTest {

    @Test
    void trafficGeneratorOnlyAcceptsSimpleGatewayPaths() {
        assertThat(TrafficGenerator.sanitizePath(null)).isEqualTo("/products");
        assertThat(TrafficGenerator.sanitizePath(" /products/2 ")).isEqualTo("/products/2");
        assertThat(TrafficGenerator.sanitizePath("/products?page=1")).isEqualTo("/products?page=1");

        for (String bad : new String[]{"http://evil.example", "//evil.example/x", "/admin/kill", "products", "/a b"}) {
            assertThatThrownBy(() -> TrafficGenerator.sanitizePath(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void longApiKeysAreShortenedInTheRequestLog() {
        assertThat(MetricsFilter.clientLabel("key:abc")).isEqualTo("key:abc");
        assertThat(MetricsFilter.clientLabel("key:steady-client-1")).isEqualTo("key:steady-client-1");
        assertThat(MetricsFilter.clientLabel("key:super-secret-api-key")).isEqualTo("key:super-…");
        assertThat(MetricsFilter.clientLabel("ip:10.0.0.1")).isEqualTo("ip:10.0.0.1");
    }

    @Test
    void openDemoModeWhenNoTokenConfigured() {
        assertThat(new AdminAuth(null).required()).isFalse();
        assertThat(new AdminAuth("  ").required()).isFalse();
        assertThat(new AdminAuth("t").required()).isTrue();
    }
}
