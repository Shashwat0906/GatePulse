package com.gatepulse.health;

import com.gatepulse.backend.Backend;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Answers "is this backend alive right now?" asynchronously.
 *
 * <p>An interface (rather than hard-wiring HTTP into the checker) lets tests plug in a fake
 * probe and drive the healthy/unhealthy thresholds deterministically, with no real servers.
 */
@FunctionalInterface
public interface HealthProbe {

    /** Completes with {@code true} if healthy; {@code false} or exceptional completion = unhealthy. */
    CompletableFuture<Boolean> probe(Backend backend);

    /** Real probe: {@code GET <backend>/health} must answer 200 within {@code timeout}. */
    static HealthProbe http(HttpClient client, Duration timeout) {
        return backend -> {
            HttpRequest request = HttpRequest.newBuilder(backend.resolve("/health", null))
                    .timeout(timeout)
                    .GET()
                    .build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenApply(response -> response.statusCode() == 200);
        };
    }
}
