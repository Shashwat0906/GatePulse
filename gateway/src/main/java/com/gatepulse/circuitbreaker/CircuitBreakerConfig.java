package com.gatepulse.circuitbreaker;

import java.time.Duration;
import java.util.Objects;

/**
 * Circuit breaker settings, shared by all per-backend breakers and changeable at runtime.
 *
 * @param failureThreshold consecutive failures that trip CLOSED -> OPEN
 * @param openDuration     how long to stay OPEN before allowing trial calls
 * @param halfOpenTrials   trial calls allowed in HALF_OPEN; all must succeed to close again
 */
public record CircuitBreakerConfig(int failureThreshold, Duration openDuration, int halfOpenTrials) {

    public CircuitBreakerConfig {
        Objects.requireNonNull(openDuration, "openDuration");
        if (failureThreshold < 1 || failureThreshold > 1000) {
            throw new IllegalArgumentException("failureThreshold must be between 1 and 1000 but was " + failureThreshold);
        }
        if (openDuration.toMillis() < 1 || openDuration.toMinutes() > 60) {
            throw new IllegalArgumentException("openDurationMs must be between 1 and 3600000 but was " + openDuration.toMillis());
        }
        if (halfOpenTrials < 1 || halfOpenTrials > 100) {
            throw new IllegalArgumentException("halfOpenTrials must be between 1 and 100 but was " + halfOpenTrials);
        }
    }

    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(5, Duration.ofSeconds(10), 3);
    }
}
