package com.gatepulse;

import com.gatepulse.backend.Backend;
import com.gatepulse.config.BackendDefinition;

import java.net.URI;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Small shared helpers for tests. */
public final class TestBackends {

    private TestBackends() {
    }

    public static Backend backend(String id) {
        return backend(id, 1);
    }

    public static Backend backend(String id, int weight) {
        return new Backend(definition(id, weight));
    }

    public static BackendDefinition definition(String id, int weight) {
        return new BackendDefinition(id, URI.create("http://127.0.0.1:1"), weight);
    }

    /** Polls {@code condition} until it is true or {@code timeout} passes. */
    public static void await(BooleanSupplier condition, Duration timeout, String description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for: " + description, e);
            }
        }
        throw new AssertionError("Timed out after " + timeout.toMillis() + "ms waiting for: " + description);
    }
}
