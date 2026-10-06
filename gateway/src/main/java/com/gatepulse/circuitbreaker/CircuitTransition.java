package com.gatepulse.circuitbreaker;

import java.time.Instant;

/** One recorded state change, shown on the dashboard timeline. */
public record CircuitTransition(String backendId, CircuitState from, CircuitState to, Instant at, String reason) {
}
