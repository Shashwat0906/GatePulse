package com.gatepulse.proxy;

import com.gatepulse.backend.Backend;
import com.gatepulse.circuitbreaker.CircuitBreaker;
import com.gatepulse.circuitbreaker.CircuitBreakerRegistry;
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
 * The last stage of the chain: picks a backend, asks its circuit breaker for permission,
 * forwards the request, and fails over to another backend if that one fails.
 *
 * <h2>Why load balancing and circuit breaking live inside this filter</h2>
 * Failover means "choose a backend, check its breaker, call it" may run several times for one
 * request. If load balancing and circuit breaking were separate filters earlier in the chain,
 * a retry would have to re-enter the chain from the middle. Keeping the loop here, and using
 * {@link LoadBalancer} and {@link CircuitBreaker} as plain collaborators, keeps each of them
 * independently testable without bending the chain.
 *
 * <h2>Failover</h2>
 * An attempt fails if the backend cannot be reached, times out, or answers 5xx. A failed
 * attempt is retried on a <em>different</em> backend, up to {@code maxAttempts} total.
 * 4xx answers are the client's problem: returned as-is and counted as a success for the breaker
 * (the backend is clearly alive).
 *
 * <h2>Only idempotent requests are retried</h2>
 * Retrying {@code GET} is safe: doing it twice has the same effect as once. Retrying a
 * {@code POST} that timed out could create an order twice, because the first backend may have
 * processed it before the connection dropped. So non-idempotent methods get exactly one attempt.
 *
 * <h2>Fallback</h2>
 * If no backend is eligible (all unhealthy, or all circuits open), the client gets a fast
 * {@code 503} and no backend is touched.
 */
public final class ProxyFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFilter.class);

    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");

    private final LoadBalancer loadBalancer;
    private final CircuitBreakerRegistry breakers;
    private final BackendClient client;
    private final int maxAttempts;

    public ProxyFilter(LoadBalancer loadBalancer, CircuitBreakerRegistry breakers, BackendClient client, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.loadBalancer = loadBalancer;
        this.breakers = breakers;
        this.client = client;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public GatewayResponse apply(RequestContext ctx, FilterChain chain) throws InterruptedException {
        int allowedAttempts = isRetryable(ctx.method()) ? maxAttempts : 1;
        Set<Backend> excluded = new HashSet<>(4);
        GatewayResponse lastFailure = null;
        Backend lastFailedBackend = null;
        int attempts = 0;

        while (attempts < allowedAttempts) {
            Optional<Backend> next = loadBalancer.choose(excluded);
            if (next.isEmpty()) {
                break; // nothing (else) eligible
            }
            Backend backend = next.get();
            excluded.add(backend);

            CircuitBreaker.Permit permit = breakers.forBackend(backend.id()).tryAcquire();
            if (permit == null) {
                // The breaker closed the door between the load balancer's check and now
                // (e.g. another thread took the last half-open trial). Not an attempt: try another.
                continue;
            }

            attempts++;
            ctx.setAttempts(attempts);
            GatewayResponse response = attempt(backend, ctx, permit);
            if (!response.isServerError()) {
                return tag(response, backend, ctx);
            }
            lastFailure = response;
            lastFailedBackend = backend;
            if (attempts < allowedAttempts) {
                log.debug("event=retry backend={} status={} attempt={}", backend.id(), response.status(), attempts);
            }
        }

        if (lastFailure != null) {
            // Out of attempts, or out of other eligible backends: the last failure is what the client sees.
            return tag(lastFailure, lastFailedBackend, ctx);
        }
        return GatewayResponse.error(503, "no_backend_available",
                "No backend can take this request: all are unhealthy or have open circuits", ctx.requestId());
    }

    /**
     * Sends to one backend and reports the outcome to its breaker. Network failures are turned
     * into a 502 response so the retry loop treats "unreachable" and "answered 5xx" alike.
     */
    private GatewayResponse attempt(Backend backend, RequestContext ctx, CircuitBreaker.Permit permit)
            throws InterruptedException {
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
            if (failed) {
                permit.onFailure();
            } else {
                permit.onSuccess();
            }
        }
    }

    private static GatewayResponse tag(GatewayResponse response, Backend backend, RequestContext ctx) {
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
