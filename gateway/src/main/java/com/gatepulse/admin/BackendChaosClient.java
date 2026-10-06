package com.gatepulse.admin;

import com.gatepulse.backend.Backend;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Forwards chaos commands (kill / revive / add latency) to a backend's own admin endpoints.
 *
 * <p>Going through the backend's HTTP API (rather than flipping a flag inside the gateway)
 * means the gateway learns about the failure the honest way: through failed requests, health
 * checks and its circuit breaker. That is exactly what the demo is meant to show.
 */
public class BackendChaosClient implements AutoCloseable {

    /** Result of a chaos command: the backend's status code and JSON body. */
    public record Result(int status, String body) {
    }

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public Result kill(Backend backend) {
        return post(backend, "/admin/kill", "");
    }

    public Result revive(Backend backend) {
        return post(backend, "/admin/revive", "");
    }

    public Result setLatency(Backend backend, int millis) {
        return post(backend, "/admin/latency", "{\"ms\":" + millis + "}");
    }

    private Result post(Backend backend, String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(backend.resolve(path, null))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Result(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new ApiException(502, "backend_unreachable", "Could not reach " + backend.id() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "interrupted", "Interrupted while contacting " + backend.id());
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
