package com.gatepulse.backend;

import com.gatepulse.config.BackendDefinition;

import java.util.List;
import java.util.Optional;

/**
 * The fixed set of backends the gateway can route to.
 *
 * <p>The list is immutable (v1 has static service discovery), so reads need no locking.
 * Each {@link Backend}'s own state (health, counters) is mutable and thread-safe.
 */
public final class BackendRegistry {

    private final List<Backend> backends;

    public BackendRegistry(List<BackendDefinition> definitions) {
        this.backends = definitions.stream().map(Backend::new).toList();
    }

    public List<Backend> all() {
        return backends;
    }

    public Optional<Backend> byId(String id) {
        for (Backend backend : backends) {
            if (backend.id().equals(id)) {
                return Optional.of(backend);
            }
        }
        return Optional.empty();
    }

    public int size() {
        return backends.size();
    }
}
