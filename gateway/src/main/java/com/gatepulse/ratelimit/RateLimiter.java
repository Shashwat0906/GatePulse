package com.gatepulse.ratelimit;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Runtime holder for the active rate-limit configuration and algorithm.
 *
 * <p>The config and the strategy built from it are stored together in one immutable pair inside
 * an {@link AtomicReference}. An admin update swaps the whole pair in one atomic step, so a
 * request always sees a strategy that matches its config, and the hot path takes no lock.
 *
 * <p>Changing the algorithm or its numbers starts every client with a fresh allowance (old
 * per-client state belonged to different rules). Merely toggling {@code enabled} keeps it.
 */
public final class RateLimiter {

    private record Active(RateLimitConfig config, RateLimitStrategy strategy) {
    }

    private final AtomicReference<Active> active;
    private final LongSupplier nanoClock;

    public RateLimiter(RateLimitConfig initial) {
        this(initial, System::nanoTime);
    }

    public RateLimiter(RateLimitConfig initial, LongSupplier nanoClock) {
        this.active = new AtomicReference<>(new Active(initial, create(initial)));
        this.nanoClock = Objects.requireNonNull(nanoClock);
    }

    public RateLimitDecision tryAcquire(String clientKey) {
        Active current = active.get();
        if (!current.config().enabled()) {
            return RateLimitDecision.UNLIMITED;
        }
        return current.strategy().tryAcquire(clientKey, nanoClock.getAsLong());
    }

    public RateLimitConfig config() {
        return active.get().config();
    }

    public void update(RateLimitConfig newConfig) {
        Objects.requireNonNull(newConfig);
        active.updateAndGet(current -> sameRules(current.config(), newConfig)
                ? new Active(newConfig, current.strategy())
                : new Active(newConfig, create(newConfig)));
    }

    /** Called periodically by a maintenance thread. */
    public int evictIdle() {
        return active.get().strategy().evictIdle(nanoClock.getAsLong());
    }

    public int trackedClients() {
        return active.get().strategy().trackedClients();
    }

    static RateLimitStrategy create(RateLimitConfig config) {
        return switch (config.algorithm()) {
            case TOKEN_BUCKET -> new TokenBucketStrategy(config.limit(), config.refillPerSecond());
            case SLIDING_WINDOW_LOG -> new SlidingWindowLogStrategy(config.limit(), config.windowMillis());
        };
    }

    private static boolean sameRules(RateLimitConfig a, RateLimitConfig b) {
        return a.algorithm() == b.algorithm() && a.limit() == b.limit()
                && a.refillPerSecond() == b.refillPerSecond() && a.windowMillis() == b.windowMillis();
    }
}
