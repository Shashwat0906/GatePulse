package com.gatepulse.backend;

import com.gatepulse.config.BackendDefinition;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Live, mutable view of one backend instance.
 *
 * <p>Concurrency notes (this object is read on every request by many threads):
 * <ul>
 *   <li>{@code healthy} is {@code volatile}: written only by the health checker, read by the
 *       load balancer. A plain volatile is enough because it is a single independent flag.</li>
 *   <li>{@code activeConnections} is an {@link AtomicInteger} because least-connections
 *       load balancing needs an exact current value.</li>
 *   <li>Request counters use {@link LongAdder}: many threads increment, few read, and
 *       LongAdder spreads contention across cells instead of fighting over one CAS.</li>
 * </ul>
 */
public final class Backend {

    private final String id;
    private final URI baseUri;
    private final String baseUrl;
    private final int weight;

    private volatile boolean healthy = true;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();

    public Backend(BackendDefinition definition) {
        this.id = definition.id();
        this.baseUri = definition.baseUri();
        this.baseUrl = stripTrailingSlash(definition.baseUri().toString());
        this.weight = definition.weight();
    }

    public String id() {
        return id;
    }

    public URI baseUri() {
        return baseUri;
    }

    public int weight() {
        return weight;
    }

    /**
     * Builds the full backend URI for a gateway request.
     *
     * @param path  raw (still percent-encoded) request path, starting with {@code /}
     * @param query raw query string without the leading {@code ?}, or {@code null}
     */
    public URI resolve(String path, String query) {
        StringBuilder sb = new StringBuilder(baseUrl.length() + path.length() + 16).append(baseUrl).append(path);
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(query);
        }
        return URI.create(sb.toString());
    }

    // ---------------------------------------------------------------- health

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    // ---------------------------------------------------------------- traffic accounting

    /** Called right before a request is sent to this backend. */
    public void onRequestStart() {
        activeConnections.incrementAndGet();
        totalRequests.increment();
    }

    /** Called when a request to this backend finishes, successfully or not. */
    public void onRequestEnd(boolean failed) {
        activeConnections.decrementAndGet();
        if (failed) {
            failedRequests.increment();
        }
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public long totalRequests() {
        return totalRequests.sum();
    }

    public long failedRequests() {
        return failedRequests.sum();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // Identity is the id: two Backend objects describe the same instance iff ids match.
    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Backend other && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return id + "(" + baseUrl + ")";
    }
}
