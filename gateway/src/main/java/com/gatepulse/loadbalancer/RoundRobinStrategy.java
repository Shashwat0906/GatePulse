package com.gatepulse.loadbalancer;

import com.gatepulse.backend.Backend;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin: hand requests out in turn, 1, 2, 3, 1, 2, 3, ...
 *
 * <p>Lock-free: {@link AtomicLong#getAndIncrement()} gives every caller a unique ticket even
 * under heavy concurrency, and the ticket modulo the candidate count picks the backend.
 * A {@code long} counter will not overflow in practice, and {@link Math#floorMod} keeps the
 * index non-negative even if it ever did.
 *
 * <p>When the candidate list shrinks (a backend goes unhealthy) the rotation simply
 * continues over the remaining ones.
 */
public final class RoundRobinStrategy implements LoadBalancingStrategy {

    public static final String NAME = "round-robin";

    private final AtomicLong counter = new AtomicLong();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Backend select(List<Backend> candidates) {
        int index = (int) Math.floorMod(counter.getAndIncrement(), (long) candidates.size());
        return candidates.get(index);
    }
}
