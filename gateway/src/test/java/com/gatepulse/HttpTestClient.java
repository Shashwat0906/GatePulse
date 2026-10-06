package com.gatepulse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Minimal blocking HTTP client for tests (never goes through a proxy). */
public final class HttpTestClient implements AutoCloseable {

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public HttpResponse<String> get(String url) {
        return send("GET", url, null, Map.of());
    }

    public HttpResponse<String> get(String url, Map<String, String> headers) {
        return send("GET", url, null, headers);
    }

    public HttpResponse<String> post(String url, String jsonBody) {
        return send("POST", url, jsonBody, Map.of());
    }

    /** Any method, optional JSON body, extra headers. */
    public HttpResponse<String> send(String method, String url, String jsonBody, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody));
        return send(builder.build());
    }

    /** Raw access, e.g. for streaming bodies. */
    public HttpClient raw() {
        return client;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError("Request failed: " + request.method() + " " + request.uri(), e);
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
