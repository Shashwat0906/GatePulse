package com.gatepulse.circuitbreaker;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Owns one {@link CircuitBreaker} per backend, the shared runtime config, and a bounded history
 * of state transitions for the dashboard.
 */
public final class CircuitBreakerRegistry {

    public static final int HISTORY_SIZE = 100;

    private final Map<String, CircuitBreaker> breakers;
    private final AtomicReference<CircuitBreakerConfig> config;
    private final Deque<CircuitTransition> history = new ArrayDeque<>();

    public CircuitBreakerRegistry(Collection<String> backendIds, CircuitBreakerConfig initial) {
        this(backendIds, initial, System::nanoTime, Clock.systemUTC());
    }

    public CircuitBreakerRegistry(Collection<String> backendIds, CircuitBreakerConfig initial,
                                  LongSupplier nanoClock, Clock wallClock) {
        this.config = new AtomicReference<>(Objects.requireNonNull(initial));
        Map<String, CircuitBreaker> map = new LinkedHashMap<>();
        for (String id : backendIds) {
            map.put(id, new CircuitBreaker(id, config::get, nanoClock, wallClock, this::record));
        }
        this.breakers = Map.copyOf(map);
    }

    public CircuitBreaker forBackend(String backendId) {
        CircuitBreaker breaker = breakers.get(backendId);
        if (breaker == null) {
            throw new IllegalArgumentException("No circuit breaker for backend " + backendId);
        }
        return breaker;
    }

    public CircuitBreakerConfig config() {
        return config.get();
    }

    /** New settings apply to the next decision of every breaker; current states are kept. */
    public void updateConfig(CircuitBreakerConfig newConfig) {
        config.set(Objects.requireNonNull(newConfig));
    }

    /** Most recent transitions first. */
    public List<CircuitTransition> recentTransitions(int limit) {
        synchronized (history) {
            List<CircuitTransition> result = new ArrayList<>(Math.min(limit, history.size()));
            var it = history.descendingIterator();
            while (it.hasNext() && result.size() < limit) {
                result.add(it.next());
            }
            return result;
        }
    }

    private void record(CircuitTransition transition) {
        // Transitions are rare (a few per minute at most), so a simple lock is the right tool.
        synchronized (history) {
            history.addLast(transition);
            while (history.size() > HISTORY_SIZE) {
                history.removeFirst();
            }
        }
    }
}
