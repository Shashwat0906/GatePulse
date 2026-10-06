package com.gatepulse.proxy;

import com.gatepulse.backend.Backend;
import com.gatepulse.core.GatewayResponse;
import com.gatepulse.core.RequestContext;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends one request to one backend using the JDK {@link HttpClient}.
 *
 * <p>Design choices:
 * <ul>
 *   <li><b>One shared HttpClient</b>: it pools keep-alive connections per backend, so
 *       requests skip the TCP handshake. Creating a client per request would destroy that.</li>
 *   <li><b>HTTP/1.1, no proxy, no redirects</b>: backends are plain internal services.
 *       Following redirects is the client's decision, not the gateway's.</li>
 *   <li><b>Timeouts</b>: a connect timeout and a per-request timeout, so a hung backend
 *       costs at most a bounded wait instead of tying up the request forever.</li>
 *   <li><b>Virtual-thread executor</b>: the client's internal async work runs on cheap
 *       virtual threads.</li>
 * </ul>
 *
 * <p>This class only moves bytes. Deciding what counts as a failure, and retrying, is the
 * {@link ProxyFilter}'s job.
 */
public class BackendClient implements AutoCloseable {

    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Duration requestTimeout;
    private final boolean trustForwardedHeaders;

    public BackendClient(Duration connectTimeout, Duration requestTimeout, boolean trustForwardedHeaders) {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .executor(executor)
                .build();
        this.requestTimeout = requestTimeout;
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    /**
     * Forwards {@code ctx} to {@code backend} and returns its response unmodified
     * (minus hop-by-hop headers).
     *
     * @throws IOException          on connection failure or timeout
     *                              ({@link java.net.http.HttpTimeoutException} is an IOException)
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public GatewayResponse send(Backend backend, RequestContext ctx) throws IOException, InterruptedException {
        HttpRequest request = buildRequest(backend, ctx);
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (ProxyHeaders.forwardToClient(name)) {
                headers.put(name, values);
            }
        });
        return new GatewayResponse(response.statusCode(), headers, response.body());
    }

    private HttpRequest buildRequest(Backend backend, RequestContext ctx) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(backend.resolve(ctx.path(), ctx.query()))
                .timeout(requestTimeout);

        ctx.headers().forEach((name, value) -> {
            if (ProxyHeaders.forwardToBackend(name) && !name.equalsIgnoreCase("X-Forwarded-For")) {
                builder.header(name, value);
            }
        });

        // Standard proxy header so backends can see the original client. When we sit behind a
        // trusted load balancer (Render, a cloud LB) the incoming chain is kept as-is; otherwise
        // a client-supplied value is untrusted and replaced with the address we observed.
        String existingXff = ctx.header("X-Forwarded-For");
        String xff = trustForwardedHeaders && existingXff != null && !existingXff.isBlank()
                ? existingXff
                : ctx.clientIp();
        builder.header("X-Forwarded-For", xff);
        builder.setHeader("X-Request-Id", ctx.requestId());

        byte[] body = ctx.body();
        HttpRequest.BodyPublisher publisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        return builder.method(ctx.method(), publisher).build();
    }

    @Override
    public void close() {
        httpClient.close();
        executor.shutdown();
    }
}
