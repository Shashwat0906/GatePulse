package com.gatepulse.circuitbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A circuit breaker for one backend.
 *
 * <p>Why: when a backend is failing, continuing to send it traffic wastes time (every request
 * waits for a timeout) and may stop it from recovering. The breaker notices repeated failures,
 * "opens" to stop traffic for a while, then cautiously tests the backend before letting
 * traffic flow again.
 *
 * <h2>How it is used</h2>
 * <pre>
 *   Permit permit = breaker.tryAcquire();
 *   if (permit == null) { ...pick another backend... }
 *   try { call backend; permit.onSuccess(); } catch (...) { permit.onFailure(); }
 * </pre>
 *
 * <h2>Thread-safety</h2>
 * The current state object lives in an {@link AtomicReference}. A transition is a
 * compare-and-set from the exact state object that decided to transition, so when many threads
 * observe the same condition at once (e.g. the 5th and 6th failure arrive together) exactly one
 * transition happens and is recorded.
 *
 * <h2>Why permits</h2>
 * A call started while CLOSED might finish after the breaker has already moved to HALF_OPEN.
 * Its result says nothing about the trial phase, so it must not count as a trial. A
 * {@link Permit} remembers which state object issued it, and its result is applied only if that
 * state is still current.
 */
public final class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final String backendId;
    private final Supplier<CircuitBreakerConfig> config;
    private final LongSupplier nanoClock;
    private final Clock wallClock;
    private final Consumer<CircuitTransition> listener;
    private final AtomicReference<StateBehavior> current;
    private volatile Instant lastTransitionAt;

    public CircuitBreaker(String backendId, Supplier<CircuitBreakerConfig> config, LongSupplier nanoClock,
                          Clock wallClock, Consumer<CircuitTransition> listener) {
        this.backendId = Objects.requireNonNull(backendId);
        this.config = Objects.requireNonNull(config);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.wallClock = Objects.requireNonNull(wallClock);
        this.listener = Objects.requireNonNull(listener);
        this.current = new AtomicReference<>(new ClosedState(this));
        this.lastTransitionAt = wallClock.instant();
    }

    /** Proof that a call was allowed; report its outcome exactly once. */
    public final class Permit {
        private final StateBehavior issuedBy;

        private Permit(StateBehavior issuedBy) {
            this.issuedBy = issuedBy;
        }

        public void onSuccess() {
            if (current.get() == issuedBy) {
                issuedBy.onSuccess(nanoClock.getAsLong());
            }
        }

        public void onFailure() {
            if (current.get() == issuedBy) {
                issuedBy.onFailure(nanoClock.getAsLong());
            }
        }
    }

    /** @return a permit if the call may proceed, or {@code null} if the circuit rejects it */
    public Permit tryAcquire() {
        StateBehavior granted = current.get().tryAcquire(nanoClock.getAsLong());
        // The permit belongs to the state that actually granted it (an OPEN state that just
        // cooled down hands over to the new HALF_OPEN state).
        return granted == null ? null : new Permit(granted);
    }

    /** Side-effect-free check for the load balancer: could a call be attempted right now? */
    public boolean allowsTraffic() {
        return current.get().allowsTraffic(nanoClock.getAsLong());
    }

    public CircuitState state() {
        return current.get().state();
    }

    public String backendId() {
        return backendId;
    }

    public Instant lastTransitionAt() {
        return lastTransitionAt;
    }

    /** Consecutive failures counted so far while CLOSED (0 in other states). */
    public int consecutiveFailures() {
        return current.get() instanceof ClosedState closed ? closed.consecutiveFailures() : 0;
    }

    /** Milliseconds until an OPEN circuit allows a trial call (0 in other states). */
    public long remainingOpenMillis() {
        return current.get() instanceof OpenState open ? open.remainingOpenNanos(nanoClock.getAsLong()) / 1_000_000 : 0;
    }

    /** Manually closes the circuit (admin "reset"), e.g. after reviving a backend. */
    public void reset() {
        StateBehavior state = current.get();
        if (state.state() != CircuitState.CLOSED) {
            transition(state, new ClosedState(this), "manual reset");
        }
    }

    // ---------------------------------------------------------------- used by the state classes

    CircuitBreakerConfig config() {
        return config.get();
    }

    StateBehavior currentBehavior() {
        return current.get();
    }

    /**
     * Moves from {@code from} to {@code to} only if {@code from} is still the current state.
     *
     * @return true if this call performed the transition
     */
    boolean transition(StateBehavior from, StateBehavior to, String reason) {
        if (!current.compareAndSet(from, to)) {
            return false; // someone else already transitioned away from `from`
        }
        Instant at = wallClock.instant();
        lastTransitionAt = at;
        CircuitTransition event = new CircuitTransition(backendId, from.state(), to.state(), at, reason);
        if (to.state() == CircuitState.OPEN) {
            log.warn("event=circuit_transition backend={} from={} to={} reason=\"{}\"", backendId, from.state(), to.state(), reason);
        } else {
            log.info("event=circuit_transition backend={} from={} to={} reason=\"{}\"", backendId, from.state(), to.state(), reason);
        }
        try {
            listener.accept(event);
        } catch (RuntimeException e) {
            log.error("event=circuit_listener_failed backend={}", backendId, e);
        }
        return true;
    }
}
