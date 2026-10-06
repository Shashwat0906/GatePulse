package com.gatepulse.core;

/**
 * One stage of the gateway's request pipeline (Chain of Responsibility pattern).
 *
 * <p>A filter can:
 * <ul>
 *   <li><b>short-circuit</b>: return a response without calling {@code chain.next(ctx)}
 *       (rate limiter answering 429, cache answering a HIT);</li>
 *   <li><b>wrap</b>: call {@code chain.next(ctx)} and inspect or decorate the result on the
 *       way back out (metrics timing the call, cache storing the response);</li>
 *   <li><b>terminate</b>: be the last stage and produce the response itself (the proxy).</li>
 * </ul>
 *
 * <p>Each concern lives in its own small class, and the order of concerns is decided in
 * exactly one place (where the chain is assembled) instead of being tangled through one
 * giant handler.
 */
@FunctionalInterface
public interface Filter {

    GatewayResponse apply(RequestContext ctx, FilterChain chain) throws Exception;
}
