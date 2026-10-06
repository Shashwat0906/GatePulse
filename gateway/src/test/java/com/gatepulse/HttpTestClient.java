package com.gatepulse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal blocking HTTP client for tests (never goes through a proxy). */
public final class HttpTestClient implements AutoCloseable {

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public HttpResponse<String> get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build());
    }

    public HttpResponse<String> post(String url, String jsonBody) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError("Request failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted: " + request.uri(), e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
