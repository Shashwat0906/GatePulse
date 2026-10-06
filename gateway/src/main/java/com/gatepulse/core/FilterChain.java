package com.gatepulse.core;

import java.util.List;

/**
 * Walks a request through an ordered list of {@link Filter}s.
 *
 * <p>The filter list is immutable and shared by all requests; each request gets its own tiny
 * cursor object holding "which filter is next". That cursor is the only per-request state,
 * so the pipeline is thread-safe without locks.
 */
public final class FilterChain {

    private final List<Filter> filters;
    private int position;

    private FilterChain(List<Filter> filters) {
        this.filters = filters;
    }

    /** Runs {@code ctx} through {@code filters} from the first one. */
    public static GatewayResponse execute(List<Filter> filters, RequestContext ctx) throws Exception {
        return new FilterChain(filters).next(ctx);
    }

    /** Passes the request to the next filter. */
    public GatewayResponse next(RequestContext ctx) throws Exception {
        if (position >= filters.size()) {
            // The last filter must always produce a response; reaching here is a wiring bug.
            throw new IllegalStateException("Filter chain ended without a response");
        }
        Filter current = filters.get(position++);
        return current.apply(ctx, this);
    }
}
