package com.gatepulse.proxy;

import com.gatepulse.backend.Backend;
import com.gatepulse.backend.BackendRegistry;
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
    private ProxyFilter proxy;

    @BeforeEach
    void setUp() {
        registry = new BackendRegistry(List.of(definition("b1", 1), definition("b2", 1), definition("b3", 1)));
        proxy = new ProxyFilter(new LoadBalancer(registry, new RoundRobinStrategy()), client, 3);
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
        when(client.send(eq(get("b1")), any())).thenThrow(new ConnectException("refused"));
        when(client.send(eq(get("b2")), any())).thenReturn(ok());

        GatewayResponse response = run(request("GET"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("X-Gateway-Backend")).isEqualTo("b2");
        assertThat(response.header("X-Gateway-Attempts")).isEqualTo("2");
        assertThat(get("b1").failedRequests()).isEqualTo(1);
        assertThat(get("b1").activeConnections()).isZero();
    }

    @Test
    void getFailsOverOnServerErrorAndTimeout() throws Exception {
        when(client.send(eq(get("b1")), any())).thenReturn(status(503));
        when(client.send(eq(get("b2")), any())).thenThrow(new HttpTimeoutException("timed out"));
        when(client.send(eq(get("b3")), any())).thenReturn(ok());

        GatewayResponse response = run(request("GET"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("X-Gateway-Backend")).isEqualTo("b3");
        assertThat(response.header("X-Gateway-Attempts")).isEqualTo("3");
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
    void idempotencyRules() {
        assertThat(ProxyFilter.isRetryable("GET")).isTrue();
        assertThat(ProxyFilter.isRetryable("PUT")).isTrue();
        assertThat(ProxyFilter.isRetryable("DELETE")).isTrue();
        assertThat(ProxyFilter.isRetryable("POST")).isFalse();
        assertThat(ProxyFilter.isRetryable("PATCH")).isFalse();
    }
}
