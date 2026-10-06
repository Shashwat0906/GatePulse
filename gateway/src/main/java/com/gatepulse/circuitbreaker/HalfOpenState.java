package com.gatepulse.circuitbreaker;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * HALF_OPEN: hand out at most {@code halfOpenTrials} trial permits. If every trial succeeds,
 * the backend has recovered: CLOSE. If any trial fails, it has not: back to OPEN.
 *
 * <p>Limiting trials matters: a just-recovering backend should get a trickle of traffic, not
 * the full flood that was queued up while it was down.
 */
final class HalfOpenState implements StateBehavior {

    private final CircuitBreaker breaker;
    private final AtomicInteger permitsIssued = new AtomicInteger();
    private final AtomicInteger successes = new AtomicInteger();

    HalfOpenState(CircuitBreaker breaker) {
        this.breaker = breaker;
    }

    @Override
    public CircuitState state() {
        return CircuitState.HALF_OPEN;
    }

    @Override
    public boolean allowsTraffic(long nowNanos) {
        return permitsIssued.get() < breaker.config().halfOpenTrials();
    }

    @Override
    public StateBehavior tryAcquire(long nowNanos) {
        // incrementAndGet hands out unique ticket numbers, so exactly `trials` callers succeed.
        return permitsIssued.incrementAndGet() <= breaker.config().halfOpenTrials() ? this : null;
    }

    @Override
    public void onSuccess(long nowNanos) {
        int trials = breaker.config().halfOpenTrials();
        if (successes.incrementAndGet() >= trials) {
            breaker.transition(this, new ClosedState(breaker), trials + " trial calls succeeded");
        }
    }

    @Override
    public void onFailure(long nowNanos) {
        breaker.transition(this, new OpenState(breaker, nowNanos), "trial call failed");
    }
}
