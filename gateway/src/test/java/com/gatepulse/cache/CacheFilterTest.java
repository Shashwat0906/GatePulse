package com.gatepulse.cache;

import com.gatepulse.core.Filter;
import com.gatepulse.core.FilterChain;
import com.gatepulse.core.GatewayResponse;
import com.gatepulse.core.RequestContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CacheFilterTest {

    private final AtomicLong clock = new AtomicLong();
    private final ResponseCache cache = new ResponseCache(new CacheSettings(true, 1_000, 100), clock::get);
    private final AtomicInteger backendCalls = new AtomicInteger();
    private int nextStatus = 200;
    private Map<String, List<String>> nextHeaders = Map.of();

    private final Filter backend = (ctx, chain) -> {
        backendCalls.incrementAndGet();
        return new GatewayResponse(nextStatus, nextHeaders, ("call-" + backendCalls.get()).getBytes());
    };

    private GatewayResponse send(String method, String path, String query, Map<String, String> headers) throws Exception {
        RequestContext ctx = RequestContext.builder().requestId("r").method(method).path(path).query(query)
                .headers(headers).clientIp("1.1.1.1").build();
        return FilterChain.execute(List.of(new CacheFilter(cache), backend), ctx);
    }

    private GatewayResponse get(String path) throws Exception {
        return send("GET", path, null, Map.of());
    }

    @Test
    void firstGetIsMissSecondIsHitWithSameBody() throws Exception {
        GatewayResponse first = get("/products");
        GatewayResponse second = get("/products");

        assertThat(first.header("X-Cache")).isEqualTo("MISS");
        assertThat(second.header("X-Cache")).isEqualTo("HIT");
        assertThat(new String(second.body())).isEqualTo("call-1");
        assertThat(backendCalls).hasValue(1);
    }

    @Test
    void entriesExpireAfterTtl() throws Exception {
        get("/products");
        clock.set(1_000_000_000L); // 1s later: ttl (1000ms) has passed

        GatewayResponse after = get("/products");

        assertThat(after.header("X-Cache")).isEqualTo("MISS");
        assertThat(backendCalls).hasValue(2);
    }

    @Test
    void queryStringIsPartOfTheKey() throws Exception {
        send("GET", "/products", "page=1", Map.of());
        GatewayResponse page2 = send("GET", "/products", "page=2", Map.of());
        GatewayResponse page1Again = send("GET", "/products", "page=1", Map.of());

        assertThat(page2.header("X-Cache")).isEqualTo("MISS");
        assertThat(page1Again.header("X-Cache")).isEqualTo("HIT");
    }

    @Test
    void nonGetRequestsBypass() throws Exception {
        GatewayResponse post = send("POST", "/products", null, Map.of());
        send("POST", "/products", null, Map.of());

        assertThat(post.header("X-Cache")).isEqualTo("BYPASS");
        assertThat(backendCalls).hasValue(2);
    }

    @Test
    void clientNoCacheHeaderBypasses() throws Exception {
        get("/products");
        GatewayResponse fresh = send("GET", "/products", null, Map.of("cache-control", "no-cache"));

        assertThat(fresh.header("X-Cache")).isEqualTo("BYPASS");
        assertThat(backendCalls).hasValue(2);
    }

    @Test
    void errorsAndUncacheableResponsesAreNotStored() throws Exception {
        nextStatus = 503;
        get("/a");
        get("/a");
        assertThat(backendCalls).as("5xx never cached").hasValue(2);

        nextStatus = 200;
        nextHeaders = Map.of("Cache-Control", List.of("no-store"));
        get("/b");
        get("/b");
        assertThat(backendCalls).as("no-store respected").hasValue(4);

        nextHeaders = Map.of("Set-Cookie", List.of("session=1"));
        get("/c");
        get("/c");
        assertThat(backendCalls).as("responses that set cookies are per-user").hasValue(6);
    }

    @Test
    void hitResponsesAreIndependentCopies() throws Exception {
        get("/products");
        GatewayResponse hit1 = get("/products");
        hit1.setHeader("X-Extra", "1");

        GatewayResponse hit2 = get("/products");

        assertThat(hit2.header("X-Extra")).isNull();
    }

    @Test
    void disablingTheCacheBypassesAndClears() throws Exception {
        get("/products");
        cache.update(new CacheSettings(false, 1_000, 100));

        assertThat(get("/products").header("X-Cache")).isEqualTo("BYPASS");
        cache.update(new CacheSettings(true, 1_000, 100));
        assertThat(get("/products").header("X-Cache")).as("old entry was cleared").isEqualTo("MISS");
    }
}
