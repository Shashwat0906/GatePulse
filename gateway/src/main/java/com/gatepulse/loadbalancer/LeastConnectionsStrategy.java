package com.gatepulse.loadbalancer;

import com.gatepulse.backend.Backend;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Least connections: send the request to the backend with the fewest requests in flight.
 *
 * <p>Round robin assumes every request costs the same. When some requests are slow (our
 * {@code /slow} endpoint, or a backend with injected latency), round robin keeps piling work
 * onto the slow backend; least connections notices its in-flight count rising and routes
 * around it automatically.
 *
 * <p>Ties are broken by starting the scan at a rotating offset. Without that, an idle system
 * (all counts 0) would send every request to the first backend in the list.
 *
 * <p>Lock-free: each backend's count is an {@code AtomicInteger}; the scan reads a snapshot.
 * Two simultaneous requests may both see the same "least loaded" backend, which is fine for
 * a heuristic and much cheaper than locking the whole pool.
 */
public final class LeastConnectionsStrategy implements LoadBalancingStrategy {

    public static final String NAME = "least-connections";

    private final AtomicLong rotor = new AtomicLong();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Backend select(List<Backend> candidates) {
        int n = candidates.size();
        int start = (int) Math.floorMod(rotor.getAndIncrement(), (long) n);
        Backend best = null;
        int fewest = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            Backend backend = candidates.get((start + i) % n);
            int active = backend.activeConnections();
            if (active < fewest) {
                fewest = active;
                best = backend;
            }
        }
        return best;
    }
}
