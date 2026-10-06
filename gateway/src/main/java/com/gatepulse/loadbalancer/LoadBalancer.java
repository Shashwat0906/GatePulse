package com.gatepulse.loadbalancer;

import com.gatepulse.backend.Backend;
import com.gatepulse.backend.BackendRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Decides which backend serves a request.
 *
 * <p>Two responsibilities, kept separate on purpose:
 * <ol>
 *   <li><b>Eligibility</b> (here): drop backends that are unhealthy, or that the caller
 *       has already tried for this request (so a retry goes somewhere new).</li>
 *   <li><b>Selection</b> (in the {@link LoadBalancingStrategy}): pick among the rest.</li>
 * </ol>
 *
 * <p>The active strategy sits in an {@link AtomicReference}, so the admin API can switch
 * algorithms at runtime without a restart and without locking the request path: each
 * request reads whichever strategy is current at that instant.
 */
public final class LoadBalancer {

    private final BackendRegistry registry;
    private final AtomicReference<LoadBalancingStrategy> strategy;
    private final Predicate<Backend> eligibility;

    public LoadBalancer(BackendRegistry registry, LoadBalancingStrategy initialStrategy) {
        this(registry, initialStrategy, Backend::isHealthy);
    }

    /**
     * @param eligibility which backends may receive traffic at all; defaults to "is healthy"
     */
    public LoadBalancer(BackendRegistry registry, LoadBalancingStrategy initialStrategy,
                        Predicate<Backend> eligibility) {
        this.registry = Objects.requireNonNull(registry);
        this.strategy = new AtomicReference<>(Objects.requireNonNull(initialStrategy));
        this.eligibility = Objects.requireNonNull(eligibility);
    }

    /**
     * Chooses a backend, skipping any in {@code excluded}.
     *
     * @return the chosen backend, or empty if no backend is eligible
     */
    public Optional<Backend> choose(Set<Backend> excluded) {
        List<Backend> all = registry.all();
        List<Backend> candidates = new ArrayList<>(all.size());
        for (Backend backend : all) {
            if (!excluded.contains(backend) && eligibility.test(backend)) {
                candidates.add(backend);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(strategy.get().select(candidates));
    }

    public Optional<Backend> choose() {
        return choose(Set.of());
    }

    public LoadBalancingStrategy strategy() {
        return strategy.get();
    }

    public void setStrategy(LoadBalancingStrategy newStrategy) {
        strategy.set(Objects.requireNonNull(newStrategy));
    }
}
