package com.gatepulse.loadbalancer;

import com.gatepulse.backend.Backend;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Weighted round robin: a backend with weight 3 gets three times the traffic of one with
 * weight 1 (useful when machines have different sizes).
 *
 * <h2>Smooth, not bursty</h2>
 * The naive approach for weights {A:5, B:1, C:1} sends A,A,A,A,A,B,C, a burst of 5 to A.
 * This uses the "smooth weighted round robin" algorithm (the one nginx uses), which
 * interleaves: A,A,B,A,C,A,A. Each round every backend gains its weight in "credit", the
 * richest backend is picked and pays back the total weight.
 *
 * <h2>Lock-free via a precomputed schedule</h2>
 * The smooth algorithm mutates shared counters on every pick, which would need a lock. Instead,
 * the full cycle (length = sum of weights) is computed once into an int array, and requests
 * walk it with an atomic ticket counter, exactly like plain round robin. The schedule is
 * rebuilt only when the set of eligible backends changes (a backend goes down or comes back),
 * which is rare. The schedule is an immutable object published through a {@code volatile}
 * field, so readers never see a half-built array.
 */
public final class WeightedRoundRobinStrategy implements LoadBalancingStrategy {

    public static final String NAME = "weighted-round-robin";

    /** The cycle for one specific candidate list. Immutable once published. */
    private record Schedule(List<Backend> candidates, int[] order) {
    }

    private final AtomicLong counter = new AtomicLong();
    private volatile Schedule schedule;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Backend select(List<Backend> candidates) {
        Schedule current = schedule;
        if (current == null || !current.candidates().equals(candidates)) {
            current = new Schedule(List.copyOf(candidates), buildOrder(candidates));
            schedule = current; // a racing thread may overwrite with an equivalent schedule; harmless
        }
        int[] order = current.order();
        int slot = (int) Math.floorMod(counter.getAndIncrement(), (long) order.length);
        return current.candidates().get(order[slot]);
    }

    /** Smooth weighted round robin, run once for a full cycle. Returns candidate indexes. */
    static int[] buildOrder(List<Backend> candidates) {
        int n = candidates.size();
        int[] weights = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            weights[i] = candidates.get(i).weight();
            total += weights[i];
        }
        int[] credit = new int[n];
        int[] order = new int[total];
        for (int slot = 0; slot < total; slot++) {
            int best = 0;
            for (int i = 0; i < n; i++) {
                credit[i] += weights[i];
                if (credit[i] > credit[best]) {
                    best = i;
                }
            }
            credit[best] -= total;
            order[slot] = best;
        }
        return order;
    }
}
