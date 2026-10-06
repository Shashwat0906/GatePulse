package com.gatepulse.proxy;

import com.gatepulse.backend.Backend;
import com.gatepulse.core.Filter;
import com.gatepulse.core.FilterChain;
import com.gatepulse.core.GatewayResponse;
import com.gatepulse.core.RequestContext;
import com.gatepulse.loadbalancer.LoadBalancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The last stage of the chain: actually forwards the request to a backend.
 *
 * <h2>Failover</h2>
 * Health checks run every few seconds, so for a short window a dead backend can still be
 * picked. To keep that window invisible to clients, a failed attempt is retried on a
 * <em>different</em> backend, up to {@code maxAttempts} total.
 *
 * <p>An attempt "fails" if the backend cannot be reached, times out, or answers 5xx.
 * 4xx answers are the client's problem and are returned as-is.
 *
 * <h2>Only idempotent requests are retried</h2>
 * Retrying {@code GET} is safe: doing it twice has the same effect as once. Retrying a
 * {@code POST} that timed out could create an order twice, because the first backend may
 * have processed it before the connection dropped. So non-idempotent methods get exactly
 * one attempt.
 */
public final class ProxyFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFilter.class);

    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");

    private final LoadBalancer loadBalancer;
    private final BackendClient client;
    private final int maxAttempts;

    public ProxyFilter(LoadBalancer loadBalancer, BackendClient client, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.loadBalancer = loadBalancer;
        this.client = client;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public GatewayResponse apply(RequestContext ctx, FilterChain chain) throws InterruptedException {
        int allowedAttempts = isRetryable(ctx.method()) ? maxAttempts : 1;
        Set<Backend> tried = new HashSet<>(4);
        GatewayResponse lastFailure = null;

        for (int attempt = 1; attempt <= allowedAttempts; attempt++) {
            Optional<Backend> next = loadBalancer.choose(tried);
            if (next.isEmpty()) {
                break; // nothing (else) eligible
            }
            Backend backend = next.get();
            tried.add(backend);
            ctx.setAttempts(attempt);

            GatewayResponse response = attempt(backend, ctx);
            if (!response.isServerError()) {
                return served(response, backend, ctx);
            }
            lastFailure = response;
            if (attempt < allowedAttempts) {
                log.debug("event=retry backend={} status={} attempt={}", backend.id(), response.status(), attempt);
            } else {
                // Out of attempts: the last 5xx is what the client sees.
                return served(response, backend, ctx);
            }
        }

        if (lastFailure != null) {
            // We ran out of eligible backends before running out of attempts.
            return served(lastFailure, ctx.servedBy(), ctx);
        }
        return GatewayResponse.error(503, "no_backend_available",
                "No healthy backend is available to serve this request", ctx.requestId());
    }

    /**
     * Sends to one backend. Network failures are converted into a 502 response so the retry
     * loop treats "unreachable" and "answered 5xx" the same way.
     */
    private GatewayResponse attempt(Backend backend, RequestContext ctx) throws InterruptedException {
        backend.onRequestStart();
        boolean failed = true;
        try {
            GatewayResponse response = client.send(backend, ctx);
            failed = response.isServerError();
            ctx.setServedBy(backend);
            return response;
        } catch (IOException e) {
            log.warn("event=backend_error backend={} error=\"{}\"", backend.id(), describe(e));
            ctx.setServedBy(backend);
            return GatewayResponse.error(502, "bad_gateway",
                    "Backend " + backend.id() + " could not be reached", ctx.requestId());
        } catch (IllegalArgumentException e) {
            // Malformed path/query that cannot form a valid URI: the client's fault, not the backend's.
            failed = false;
            return GatewayResponse.error(400, "bad_request", "Invalid request target", ctx.requestId());
        } finally {
            backend.onRequestEnd(failed);
        }
    }

    private static GatewayResponse served(GatewayResponse response, Backend backend, RequestContext ctx) {
        if (backend != null) {
            response.setHeader("X-Gateway-Backend", backend.id());
        }
        response.setHeader("X-Gateway-Attempts", Integer.toString(Math.max(ctx.attempts(), 1)));
        return response;
    }

    static boolean isRetryable(String method) {
        return IDEMPOTENT_METHODS.contains(method);
    }

    private static String describe(IOException e) {
        String type = e.getClass().getSimpleName();
        return e.getMessage() == null ? type : type + ": " + e.getMessage();
    }
}
