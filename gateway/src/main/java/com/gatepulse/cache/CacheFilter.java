package com.gatepulse.cache;

import com.gatepulse.core.Filter;
import com.gatepulse.core.FilterChain;
import com.gatepulse.core.GatewayResponse;
import com.gatepulse.core.RequestContext;

import java.util.Locale;
import java.util.Optional;

/**
 * Serves repeated GET requests from memory instead of calling a backend.
 *
 * <p>Rules (deliberately conservative, a wrong cache hit is worse than a miss):
 * <ul>
 *   <li>Only {@code GET} requests are cached; anything else is {@code BYPASS}.</li>
 *   <li>A client sending {@code Cache-Control: no-cache} or {@code no-store} bypasses the cache.</li>
 *   <li>Only {@code 200} responses are stored, and never ones the backend marked
 *       {@code no-store} or {@code private}, or that set cookies.</li>
 *   <li>The key is path + query string. Request headers are not part of the key, so this cache
 *       is for public data (no {@code Vary} support; see README limitations).</li>
 * </ul>
 * Every response through this filter gets {@code X-Cache: HIT | MISS | BYPASS}.
 */
public final class CacheFilter implements Filter {

    public static final String HEADER = "X-Cache";

    private final ResponseCache cache;

    public CacheFilter(ResponseCache cache) {
        this.cache = cache;
    }

    @Override
    public GatewayResponse apply(RequestContext ctx, FilterChain chain) throws Exception {
        if (!cache.enabled() || !"GET".equals(ctx.method()) || clientRefusesCache(ctx)) {
            ctx.setCacheStatus(CacheStatus.BYPASS);
            return chain.next(ctx).setHeader(HEADER, CacheStatus.BYPASS.name());
        }

        String key = keyFor(ctx);
        Optional<CachedResponse> hit = cache.get(key);
        if (hit.isPresent()) {
            ctx.setCacheStatus(CacheStatus.HIT);
            return hit.get().toResponse().setHeader(HEADER, CacheStatus.HIT.name());
        }

        ctx.setCacheStatus(CacheStatus.MISS);
        GatewayResponse response = chain.next(ctx);
        if (isStorable(response)) {
            String backendId = ctx.servedBy() == null ? null : ctx.servedBy().id();
            cache.put(key, CachedResponse.of(response, backendId));
        }
        return response.setHeader(HEADER, CacheStatus.MISS.name());
    }

    static String keyFor(RequestContext ctx) {
        String query = ctx.query();
        return query == null || query.isEmpty() ? ctx.path() : ctx.path() + "?" + query;
    }

    private static boolean clientRefusesCache(RequestContext ctx) {
        String cc = ctx.header("Cache-Control");
        if (cc == null) {
            return false;
        }
        String lower = cc.toLowerCase(Locale.ROOT);
        return lower.contains("no-cache") || lower.contains("no-store");
    }

    static boolean isStorable(GatewayResponse response) {
        if (response.status() != 200 || response.header("Set-Cookie") != null) {
            return false;
        }
        String cc = response.header("Cache-Control");
        if (cc == null) {
            return true;
        }
        String lower = cc.toLowerCase(Locale.ROOT);
        return !lower.contains("no-store") && !lower.contains("private");
    }
}
