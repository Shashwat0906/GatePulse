package com.gatepulse.loadbalancer;

import com.gatepulse.backend.Backend;

import java.util.List;

/**
 * Strategy pattern: one interface, several interchangeable algorithms for picking a backend.
 *
 * <p>The {@link LoadBalancer} has already filtered out unhealthy backends (and, in later
 * phases, backends whose circuit is open), so a strategy only answers one question:
 * "among these eligible candidates, which one gets the next request?"
 *
 * <p>Implementations must be thread-safe: {@link #select} is called concurrently.
 */
public interface LoadBalancingStrategy {

    /** Short machine-friendly name, e.g. {@code round-robin}. Shown on the dashboard. */
    String name();

    /**
     * Picks one backend.
     *
     * @param candidates eligible backends; never empty. The list must not be modified.
     */
    Backend select(List<Backend> candidates);
}
