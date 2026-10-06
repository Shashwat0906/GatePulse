package com.gatepulse.circuitbreaker;

import java.util.concurrent.atomic.AtomicInteger;

/** CLOSED: everything flows; trip to OPEN after N consecutive failures. */
final class ClosedState implements StateBehavior {

    private final CircuitBreaker breaker;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    ClosedState(CircuitBreaker breaker) {
        this.breaker = breaker;
    }

    @Override
    public CircuitState state() {
        return CircuitState.CLOSED;
    }

    @Override
    public boolean allowsTraffic(long nowNanos) {
        return true;
    }

    @Override
    public StateBehavior tryAcquire(long nowNanos) {
        return this;
    }

    @Override
    public void onSuccess(long nowNanos) {
        // "Consecutive" means any success wipes the slate clean.
        consecutiveFailures.set(0);
    }

    @Override
    public void onFailure(long nowNanos) {
        int failures = consecutiveFailures.incrementAndGet();
        int threshold = breaker.config().failureThreshold();
        if (failures >= threshold) {
            // Several threads may cross the threshold together; the CAS inside transition()
            // guarantees exactly one of them performs (and records) the switch to OPEN.
            breaker.transition(this, new OpenState(breaker, nowNanos), failures + " consecutive failures");
        }
    }

    int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
