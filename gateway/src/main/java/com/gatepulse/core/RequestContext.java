package com.gatepulse.core;

import com.gatepulse.backend.Backend;
import com.gatepulse.cache.CacheStatus;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Everything the filter chain knows about one incoming request.
 *
 * <p>The request data (method, path, headers, body, client identity) is immutable and is
 * captured once from the HTTP layer, so filters never touch Javalin directly. That keeps
 * filters framework-independent and easy to unit-test with a hand-built context.
 *
 * <p>A few mutable "notes" ({@link #servedBy()}, {@link #attempts()}, {@link #cacheStatus()}) let filters record what
 * happened, for response headers, logs and metrics. A context belongs to exactly one request
 * and one thread at a time, so these notes need no synchronization.
 */
public final class RequestContext {

    private final String requestId;
    private final String method;
    private final String path;
    private final String query;
    private final Map<String, String> headers;
    private final byte[] body;
    private final String clientIp;
    private final String clientKey;
    private final long startNanos;

    private Backend servedBy;
    private int attempts;
    private CacheStatus cacheStatus;

    private RequestContext(Builder b) {
        this.requestId = Objects.requireNonNull(b.requestId, "requestId");
        this.method = Objects.requireNonNull(b.method, "method");
        this.path = Objects.requireNonNull(b.path, "path");
        this.query = b.query;
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(b.headers);
        this.headers = Collections.unmodifiableMap(copy);
        this.body = b.body == null ? new byte[0] : b.body;
        this.clientIp = Objects.requireNonNull(b.clientIp, "clientIp");
        this.clientKey = b.clientKey == null ? b.clientIp : b.clientKey;
        this.startNanos = b.startNanos;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String requestId() {
        return requestId;
    }

    /** Upper-case HTTP method, e.g. {@code GET}. */
    public String method() {
        return method;
    }

    /** Raw request path, e.g. {@code /products/3}. */
    public String path() {
        return path;
    }

    /** Raw query string without {@code ?}, or {@code null}. */
    public String query() {
        return query;
    }

    /** Request headers; lookups are case-insensitive as HTTP requires. */
    public Map<String, String> headers() {
        return headers;
    }

    public String header(String name) {
        return headers.get(name);
    }

    public byte[] body() {
        return body;
    }

    /** Best-known address of the real client (first X-Forwarded-For hop when trusted). */
    public String clientIp() {
        return clientIp;
    }

    /** Identity used for per-client limits: the API key if sent, otherwise the client IP. */
    public String clientKey() {
        return clientKey;
    }

    public long startNanos() {
        return startNanos;
    }

    public Backend servedBy() {
        return servedBy;
    }

    public void setServedBy(Backend servedBy) {
        this.servedBy = servedBy;
    }

    /** How many backends were tried for this request (0 if none were reached). */
    public int attempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    /** What the cache did for this request, or {@code null} if it never reached the cache. */
    public CacheStatus cacheStatus() {
        return cacheStatus;
    }

    public void setCacheStatus(CacheStatus cacheStatus) {
        this.cacheStatus = cacheStatus;
    }

    public static final class Builder {
        private String requestId;
        private String method;
        private String path;
        private String query;
        private Map<String, String> headers = Map.of();
        private byte[] body;
        private String clientIp;
        private String clientKey;
        private long startNanos = System.nanoTime();

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Builder body(byte[] body) {
            this.body = body;
            return this;
        }

        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        public Builder clientKey(String clientKey) {
            this.clientKey = clientKey;
            return this;
        }

        public Builder startNanos(long startNanos) {
            this.startNanos = startNanos;
            return this;
        }

        public RequestContext build() {
            return new RequestContext(this);
        }
    }
}
