package com.gatepulse.ratelimit;

import com.gatepulse.core.Filter;
import com.gatepulse.core.FilterChain;
import com.gatepulse.core.GatewayResponse;
import com.gatepulse.core.RequestContext;

/**
 * First line of defence: rejects clients that exceed their allowance before any further work
 * (cache lookup, backend call) is spent on them.
 *
 * <p>Clients are identified by {@link RequestContext#clientKey()}: the {@code X-API-Key}
 * header if present, otherwise the client IP.
 *
 * <p>Response headers follow the common convention:
 * <ul>
 *   <li>{@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining} on every response;</li>
 *   <li>{@code 429 Too Many Requests} with {@code Retry-After} (seconds) when rejected.</li>
 * </ul>
 */
public final class RateLimitFilter implements Filter {

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public GatewayResponse apply(RequestContext ctx, FilterChain chain) throws Exception {
        RateLimitDecision decision = limiter.tryAcquire(ctx.clientKey());
        if (decision.isUnlimited()) {
            return chain.next(ctx);
        }
        if (!decision.allowed()) {
            GatewayResponse rejected = GatewayResponse.error(429, "rate_limited",
                    "Too many requests. Retry after " + decision.retryAfterSeconds() + "s.", ctx.requestId());
            rejected.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            return withHeaders(rejected, decision);
        }
        return withHeaders(chain.next(ctx), decision);
    }

    private static GatewayResponse withHeaders(GatewayResponse response, RateLimitDecision decision) {
        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        return response;
    }
}
