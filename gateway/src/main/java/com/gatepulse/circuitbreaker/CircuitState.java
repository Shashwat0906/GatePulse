package com.gatepulse.circuitbreaker;

/**
 * The three states of a circuit breaker.
 *
 * <pre>
 *            N consecutive failures              open duration elapsed
 *   CLOSED ---------------------------> OPEN ---------------------------> HALF_OPEN
 *     ^                                  ^                                   |
 *     |          a trial call fails      |                                   |
 *     |          ------------------------+-----------------------------------+
 *     |                                                                      |
 *     +--------------------- all trial calls succeed ------------------------+
 * </pre>
 */
public enum CircuitState {
    /** Normal operation: calls flow, consecutive failures are counted. */
    CLOSED,
    /** Backend considered broken: calls are rejected immediately without touching it. */
    OPEN,
    /** Probation: a few trial calls are let through to test whether the backend recovered. */
    HALF_OPEN
}
