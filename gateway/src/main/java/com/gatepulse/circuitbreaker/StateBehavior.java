package com.gatepulse.circuitbreaker;

/**
 * State pattern: what the breaker does depends on which state object is current.
 *
 * <p>Instead of one class full of {@code if (state == OPEN) ... else if (state == HALF_OPEN)}
 * branches, each state is its own small class that knows only its own rules and which state
 * comes next. A state object is created fresh on every transition, so its counters (failures
 * seen, trial permits handed out) automatically start from zero.
 *
 * <p>Implementations must be thread-safe; they are called concurrently by request threads.
 */
interface StateBehavior {

    CircuitState state();

    /** Cheap, side-effect-free check used by the load balancer to filter candidates. */
    boolean allowsTraffic(long nowNanos);

    /**
     * Asks to make one call. May trigger a transition (OPEN -> HALF_OPEN).
     *
     * @return the state object that granted the call (usually {@code this}; the new HALF_OPEN
     *         state when OPEN just cooled down), or {@code null} if the call is rejected
     */
    StateBehavior tryAcquire(long nowNanos);

    void onSuccess(long nowNanos);

    void onFailure(long nowNanos);
}
