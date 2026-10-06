package com.gatepulse.metrics;

import com.gatepulse.cache.CacheStatus;
import com.gatepulse.core.Filter;
import com.gatepulse.core.FilterChain;
import com.gatepulse.core.GatewayResponse;
import com.gatepulse.core.RequestContext;

/**
 * The outermost filter: times every request and records its outcome.
 *
 * <p>It wraps the whole chain on purpose. Placed last, it would never see requests that an
 * earlier filter answered on its own (a 429 from the rate limiter, a cache hit), and those are
 * exactly the numbers the dashboard needs. Wrapping means it sees every response, and the
 * latency it measures is the true time spent inside the gateway.
 */
public final class MetricsFilter implements Filter {

    private static final int MAX_CLIENT_LABEL = 40;
    private static final int MAX_VISIBLE_KEY = 16;

    private final MetricsCollector collector;

    public MetricsFilter(MetricsCollector collector) {
        this.collector = collector;
    }

    @Override
    public GatewayResponse apply(RequestContext ctx, FilterChain chain) throws Exception {
        collector.requestStarted();
        int status = 500;
        try {
            GatewayResponse response = chain.next(ctx);
            status = response.status();
            return response;
        } finally {
            // Runs even if a filter threw: the handler turns that into a 500, and we count it as one.
            long latencyMicros = (System.nanoTime() - ctx.startNanos()) / 1_000;
            CacheStatus cache = ctx.cacheStatus();
            RequestRecord record = new RequestRecord(
                    0,
                    collector.nowMillis(),
                    ctx.requestId(),
                    ctx.method(),
                    ctx.path(),
                    clientLabel(ctx.clientKey()),
                    cache == CacheStatus.HIT || ctx.servedBy() == null ? null : ctx.servedBy().id(),
                    status,
                    latencyMicros / 1000.0,
                    cache == null ? null : cache.name());
            collector.requestFinished(record, latencyMicros, cache == CacheStatus.HIT);
        }
    }

    /**
     * Label for the request log. Short API keys (like the demo's {@code steady-client-1}) are shown
     * as-is; long ones, which look like real secrets, are truncated so the public dashboard never
     * displays them in full.
     */
    public static String clientLabel(String clientKey) {
        if (clientKey.startsWith("key:") && clientKey.length() - 4 > MAX_VISIBLE_KEY) {
            return clientKey.substring(0, 4 + 6) + "…";
        }
        return clientKey.length() > MAX_CLIENT_LABEL ? clientKey.substring(0, MAX_CLIENT_LABEL) + "…" : clientKey;
    }
}
