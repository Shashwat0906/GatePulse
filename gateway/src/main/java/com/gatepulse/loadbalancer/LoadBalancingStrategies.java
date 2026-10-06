package com.gatepulse.loadbalancer;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Factory: turns a strategy name from config or the admin API into a fresh strategy instance. */
public final class LoadBalancingStrategies {

    private static final Map<String, Supplier<LoadBalancingStrategy>> FACTORIES = Map.of(
            RoundRobinStrategy.NAME, RoundRobinStrategy::new,
            LeastConnectionsStrategy.NAME, LeastConnectionsStrategy::new,
            WeightedRoundRobinStrategy.NAME, WeightedRoundRobinStrategy::new);

    /** Names in display order. */
    public static final List<String> NAMES = List.of(
            RoundRobinStrategy.NAME, LeastConnectionsStrategy.NAME, WeightedRoundRobinStrategy.NAME);

    private LoadBalancingStrategies() {
    }

    public static LoadBalancingStrategy create(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        Supplier<LoadBalancingStrategy> factory = FACTORIES.get(key);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown load balancing strategy '" + name + "', expected one of " + NAMES);
        }
        return factory.get();
    }
}
