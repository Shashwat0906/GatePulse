package com.gatepulse.config;

import java.net.URI;
import java.util.Objects;

/**
 * Static description of one backend instance, as read from configuration.
 *
 * @param id      stable, human-readable identifier (e.g. {@code backend-1})
 * @param baseUri scheme + host + port of the backend, without a trailing slash
 * @param weight  relative share of traffic for weighted load balancing (>= 1)
 */
public record BackendDefinition(String id, URI baseUri, int weight) {

    public BackendDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(baseUri, "baseUri");
        if (weight < 1) {
            throw new IllegalArgumentException("Backend weight must be >= 1 but was " + weight + " for " + id);
        }
    }
}
