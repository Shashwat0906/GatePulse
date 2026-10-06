package com.gatepulse.proxy;

import com.gatepulse.backend.Backend;
import com.gatepulse.backend.BackendRegistry;
import com.gatepulse.circuitbreaker.CircuitBreakerConfig;
import com.gatepulse.circuitbreaker.CircuitBreakerRegistry;
import com.gatepulse.circuitbreaker.CircuitState;
import com.gatepulse.core.FilterChain;
import com.gatepulse.core.GatewayResponse;
import com.gatepulse.core.RequestContext;
import com.gatepulse.loadbalancer.LoadBalancer;
import com.gatepulse.loadbalancer.RoundRobinStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.gatepulse.TestBackends.definition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Retry/failover rules of the proxy, with the network replaced by a Mockito mock. */
@ExtendWith(MockitoExtension.class)
class ProxyFilterTest {

    @Mock
    private BackendClient client;

    private BackendRegistry registry;
    private CircuitBreakerRegistry breakers;
    private ProxyFilter proxy;

    @BeforeEach
    void setUp() {
        registry = new BackendRegistry(List.of(definition("b1", 1), definition("b2", 1), definition("b3", 1)));
        breakers = new CircuitBreakerRegistry(List.of("b1", "b2", "b3"),
                new CircuitBreakerConfig(2, Duration.ofMinutes(1), 1));
        // Same eligibility rule as production: healthy and circuit not open.
        LoadBalancer lb = new LoadBalancer(registry, new RoundRobinStrategy(),
                b -> b.isHealthy() && breakers.forBackend(b.id()).allowsTraffic());
        proxy = new ProxyFilter(lb, breakers, client, 3);
    }

    private Backend get(String id) {
        return registry.byId(id).orElseThrow();
    }

    private static RequestContext request(String method) {
        return RequestContext.builder()
                .requestId("test")
                .method(method)
                .path("/products")
                .clientIp("10.0.0.1")
                .build();
    }

    private static GatewayResponse ok() {
        return new GatewayResponse(200, Map.of(), "ok".getBytes());
    }

    private static GatewayResponse status(int code) {
        return new GatewayResponse(code, Map.of(), new byte[0]);
    }

    private GatewayResponse run(RequestContext ctx) throws Exception {
        return FilterChain.execute(List.of(proxy), ctx);
    }

    @Test
    void forwardsToBackendAndTagsResponse() throws Exception {
        when(client.send(eq(get("b1")), any())).thenReturn(ok());

        RequestContext ctx = request("GET");
        GatewayResponse response = run(ctx);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("X-Gateway-Backend")).isEqualTo("b1");
        assertThat(response.header("X-Gateway-Attempts")).isEqualTo("1");
        assertThat(ctx.servedBy()).isEqualTo(get("b1"));
        assertThat(get("b1").totalRequests()).isEqualTo(1);
        assertThat(get("b1").activeConnections()).as("connection released").isZero();
    }

    @Test
    void getFailsOverToAnotherBackendWhenConnectionRefused() throws Exception {
        // b1 is dead; every other backend answers. Which one gets the retry is the
        // strategy's business, so the test only asserts "not b1".
        when(client.send(any(), any())).thenAnswer(inv -> {
            if (inv.getArgument(0, Backend.class).id().equals("b1")) {
                throw new ConnectException("refused");
            }
            return ok();
        });

        GatewayResponse response = run(request("GET"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("X-Gateway-Backend")).isNotEqualTo("b1");
        assertThat(response.header("X-Gateway-Attempts")).isEqualTo("2");
        assertThat(get("b1").failedRequests()).isEqualTo(1);
        assertThat(get("b1").activeConnections()).isZero();
    }

    @Test
    void getFailsOverOnServerErrorAndTimeoutToThreeDistinctBackends() throws Exception {
        List<Backend> called = new ArrayList<>();
        when(client.send(any(), any())).thenAnswer(inv -> {
            called.add(inv.getArgument(0, Backend.class));
            return switch (called.size()) {
                case 1 -> status(503);
                case 2 -> throw new HttpTimeoutException("timed out");
                default -> ok();
            };
        });

        GatewayResponse response = run(request("GET"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("X-Gateway-Attempts")).isEqualTo("3");
        assertThat(called).as("each retry goes to a backend not tried before").doesNotHaveDuplicates().hasSize(3);
        assertThat(response.header("X-Gateway-Backend")).isEqualTo(called.get(2).id());
    }

    @Test
    void clientErrorsAreReturnedWithoutRetry() throws Exception {
        when(client.send(eq(get("b1")), any())).thenReturn(status(404));

        GatewayResponse response = run(request("GET"));

        assertThat(response.status()).isEqualTo(404);
        verify(client, times(1)).send(any(), any());
    }

    @Test
    void postIsNeverRetried() throws Exception {
        when(client.send(eq(get("b1")), any())).thenThrow(new HttpTimeoutException("timed out"));

        GatewayResponse response = run(request("POST"));

        assertThat(response.status()).isEqualTo(502);
        verify(client, times(1)).send(any(), any());
        verify(client, never()).send(eq(get("b2")), any());
    }

    @Test
    void returnsLastServerErrorWhenEveryAttemptFails() throws Exception {
        when(client.send(any(), any())).thenReturn(status(503));

        GatewayResponse response = run(request("GET"));

        assertThat(response.status()).isEqualTo(503);
        verify(client, times(3)).send(any(), any());
    }

    @Test
    void returns503WithoutCallingAnyBackendWhenNoneHealthy() throws Exception {
        registry.all().forEach(b -> b.setHealthy(false));

        GatewayResponse response = run(request("GET"));

        assertThat(response.status()).isEqualTo(503);
        assertThat(new String(response.body())).contains("no_backend_available");
        verify(client, never()).send(any(), any());
    }

    @Test
    void stopsWhenEligibleBackendsRunOutBeforeAttempts() throws Exception {
        get("b2").setHealthy(false);
        get("b3").setHealthy(false);
        when(client.send(eq(get("b1")), any())).thenReturn(status(500));

        GatewayResponse response = run(request("GET"));

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.header("X-Gateway-Backend")).isEqualTo("b1");
        verify(client, times(1)).send(any(), any());
    }

    @Test
    void repeatedFailuresOpenTheCircuitAndTrafficStopsGoingThere() throws Exception {
        when(client.send(any(), any())).thenAnswer(inv ->
                inv.getArgument(0, Backend.class).id().equals("b1") ? status(503) : ok());

        // Every request still succeeds thanks to failover, while b1 collects failures.
        for (int i = 0; i < 6; i++) {
            assertThat(run(request("GET")).status()).isEqualTo(200);
        }
        assertThat(breakers.forBackend("b1").state()).isEqualTo(CircuitState.OPEN);

        org.mockito.Mockito.clearInvocations(client);
        for (int i = 0; i < 10; i++) {
            run(request("GET"));
        }
        verify(client, never()).send(eq(get("b1")), any());
    }

    @Test
    void returns503FastWhenEveryCircuitIsOpen() throws Exception {
        when(client.send(any(), any())).thenReturn(status(500));
        // Enough failing requests to open all three circuits (threshold 2).
        for (int i = 0; i < 3; i++) {
            run(request("GET"));
        }
        assertThat(registry.all()).allMatch(b -> breakers.forBackend(b.id()).state() == CircuitState.OPEN);
        org.mockito.Mockito.clearInvocations(client);

        GatewayResponse response = run(request("GET"));

        assertThat(response.status()).isEqualTo(503);
        assertThat(new String(response.body())).contains("no_backend_available");
        verify(client, never()).send(any(), any());
    }

    @Test
    void clientErrorsCountAsSuccessForTheBreaker() throws Exception {
        when(client.send(any(), any())).thenReturn(status(404));
        for (int i = 0; i < 9; i++) {
            run(request("GET"));
        }
        assertThat(registry.all()).allMatch(b -> breakers.forBackend(b.id()).state() == CircuitState.CLOSED);
    }

    @Test
    void idempotencyRules() {
        assertThat(ProxyFilter.isRetryable("GET")).isTrue();
        assertThat(ProxyFilter.isRetryable("PUT")).isTrue();
        assertThat(ProxyFilter.isRetryable("DELETE")).isTrue();
        assertThat(ProxyFilter.isRetryable("POST")).isFalse();
        assertThat(ProxyFilter.isRetryable("PATCH")).isFalse();
    }
}
