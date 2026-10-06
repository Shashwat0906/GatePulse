package com.gatepulse.circuitbreaker;

/**
 * OPEN: reject everything (fail fast, give the backend room to recover) until the open
 * duration has passed; the first request after that moves the breaker to HALF_OPEN.
 *
 * <p>There is no timer thread: the transition happens lazily, on the first request that
 * notices enough time has passed.
 */
final class OpenState implements StateBehavior {

    private final CircuitBreaker breaker;
    private final long openedAtNanos;

    OpenState(CircuitBreaker breaker, long openedAtNanos) {
        this.breaker = breaker;
        this.openedAtNanos = openedAtNanos;
    }

    @Override
    public CircuitState state() {
        return CircuitState.OPEN;
    }

    private boolean cooledDown(long nowNanos) {
        return nowNanos - openedAtNanos >= breaker.config().openDuration().toNanos();
    }

    @Override
    public boolean allowsTraffic(long nowNanos) {
        return cooledDown(nowNanos);
    }

    @Override
    public StateBehavior tryAcquire(long nowNanos) {
        if (!cooledDown(nowNanos)) {
            return null;
        }
        breaker.transition(this, new HalfOpenState(breaker), "open duration elapsed, testing backend");
        // Whether we won the transition race or another thread did, ask whatever state is current now.
        return breaker.currentBehavior().tryAcquire(nowNanos);
    }

    // Results of calls started before the circuit opened are ignored (see CircuitBreaker.Permit).
    @Override
    public void onSuccess(long nowNanos) {
    }

    @Override
    public void onFailure(long nowNanos) {
    }

    long remainingOpenNanos(long nowNanos) {
        return Math.max(0, breaker.config().openDuration().toNanos() - (nowNanos - openedAtNanos));
    }
}
