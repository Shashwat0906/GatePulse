package com.gatepulse.core;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * The bridge between Javalin and the framework-free filter chain.
 *
 * <p>For every proxied request it: assigns a request id, converts the Javalin
 * {@link Context} into an immutable {@link RequestContext}, runs the filter chain, and copies
 * the resulting {@link GatewayResponse} back to the HTTP response. Nothing else in the
 * pipeline imports Javalin.
 */
public final class GatewayHandler implements Handler {

    private static final Logger log = LoggerFactory.getLogger(GatewayHandler.class);

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String API_KEY_HEADER = "X-API-Key";
    private static final int MAX_API_KEY_LENGTH = 128;

    /** Accept a caller's request id only if it is short and harmless to log. */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final List<Filter> filters;
    private final boolean trustForwardedHeaders;

    public GatewayHandler(List<Filter> filters, boolean trustForwardedHeaders) {
        this.filters = List.copyOf(filters);
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    @Override
    public void handle(Context ctx) {
        String requestId = requestIdFor(ctx.header(REQUEST_ID_HEADER));
        MDC.put("requestId", requestId);
        try {
            RequestContext request = toRequestContext(ctx, requestId);
            GatewayResponse response = FilterChain.execute(filters, request);
            write(ctx, response, requestId);
            if (log.isDebugEnabled()) {
                long micros = (System.nanoTime() - request.startNanos()) / 1_000;
                log.debug("event=request method={} path={} status={} backend={} latencyUs={}",
                        request.method(), request.path(), response.status(),
                        request.servedBy() == null ? "-" : request.servedBy().id(), micros);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            write(ctx, GatewayResponse.error(503, "shutting_down", "Gateway is shutting down", requestId), requestId);
        } catch (Exception e) {
            log.error("event=unhandled_error path={}", ctx.path(), e);
            write(ctx, GatewayResponse.error(500, "internal_error", "Unexpected gateway error", requestId), requestId);
        } finally {
            MDC.remove("requestId");
        }
    }

    RequestContext toRequestContext(Context ctx, String requestId) {
        String clientIp = clientIp(ctx.header("X-Forwarded-For"), ctx.ip());
        String apiKey = ctx.header(API_KEY_HEADER);
        String clientKey = apiKey != null && !apiKey.isBlank() ? "key:" + boundedKey(apiKey.trim()) : "ip:" + clientIp;

        Map<String, String> headers = ctx.headerMap();
        return RequestContext.builder()
                .requestId(requestId)
                .method(ctx.method().name())
                .path(ctx.path())
                .query(ctx.queryString())
                .headers(headers)
                .body(ctx.bodyAsBytes())
                .clientIp(clientIp)
                .clientKey(clientKey)
                .build();
    }

    /**
     * The real client address. Behind a hosting load balancer every request seems to come from
     * the balancer, and the original client is the first entry of {@code X-Forwarded-For}.
     * That header is client-controlled, so it is only trusted when configured.
     */
    String clientIp(String forwardedFor, String remoteAddress) {
        if (trustForwardedHeaders && forwardedFor != null && !forwardedFor.isBlank()) {
            int comma = forwardedFor.indexOf(',');
            String first = (comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return remoteAddress;
    }

    /** Caps key length so a client cannot bloat the rate limiter's per-client map with huge keys. */
    private static String boundedKey(String apiKey) {
        return apiKey.length() <= MAX_API_KEY_LENGTH ? apiKey : apiKey.substring(0, MAX_API_KEY_LENGTH);
    }

    static String requestIdFor(String incoming) {
        if (incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFF_FFFF_FFFFL);
    }

    private static void write(Context ctx, GatewayResponse response, String requestId) {
        ctx.status(response.status());
        response.headers().forEach((name, values) -> {
            if (name.equalsIgnoreCase("Content-Type")) {
                if (!values.isEmpty()) {
                    ctx.contentType(values.get(0));
                }
                return;
            }
            for (String value : values) {
                ctx.res().addHeader(name, value);
            }
        });
        ctx.header(REQUEST_ID_HEADER, requestId);
        ctx.result(response.body());
    }
}
