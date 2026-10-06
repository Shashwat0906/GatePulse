package com.gatepulse.config;

import java.net.URI;
import java.util.Objects;

/**
 * Static description of one backend instance, as read from configuration.
 *
 * @param id      stable, human-readable identifier (e.g. {@code backend-1})
 * @param baseUri scheme + host + port of the backend, without a trailing slash
 * @param weight  relative share of traffic for weighted load balancing (1..100)
 */
public record BackendDefinition(String id, URI baseUri, int weight) {

    /** Upper bound keeps the weighted round-robin schedule (length = sum of weights) small. */
    public static final int MAX_WEIGHT = 100;

    public BackendDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(baseUri, "baseUri");
        if (weight < 1 || weight > MAX_WEIGHT) {
            throw new IllegalArgumentException(
                    "Backend weight must be between 1 and " + MAX_WEIGHT + " but was " + weight + " for " + id);
        }
    }
}
