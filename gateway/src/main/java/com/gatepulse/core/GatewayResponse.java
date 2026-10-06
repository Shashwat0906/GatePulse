package com.gatepulse.core;

import com.gatepulse.util.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The response a filter chain produces, independent of the HTTP framework.
 *
 * <p>Status and body are fixed at construction; headers can still be added by outer filters
 * on the way back out (e.g. the cache adds {@code X-Cache}, the rate limiter adds
 * {@code X-RateLimit-Remaining}). Header names keep their original case; a header may have
 * several values (e.g. {@code Set-Cookie}).
 */
public final class GatewayResponse {

    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    public GatewayResponse(int status, Map<String, List<String>> headers, byte[] body) {
        this.status = status;
        this.headers = new LinkedHashMap<>();
        headers.forEach((name, values) -> this.headers.put(name, new ArrayList<>(values)));
        this.body = body == null ? new byte[0] : body;
    }

    /** A JSON response; the body object is serialized with Jackson. */
    public static GatewayResponse json(int status, Object body) {
        GatewayResponse response = new GatewayResponse(status, Map.of(), Json.toBytes(body));
        response.setHeader("Content-Type", Json.CONTENT_TYPE);
        return response;
    }

    /** A gateway-generated error with a consistent JSON shape. */
    public static GatewayResponse error(int status, String code, String message, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.put("requestId", requestId);
        return json(status, body);
    }

    public int status() {
        return status;
    }

    public byte[] body() {
        return body;
    }

    public Map<String, List<String>> headers() {
        return Collections.unmodifiableMap(headers);
    }

    /** First value of a header (case-insensitive lookup), or {@code null}. */
    public String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    /** Replaces any existing values of the header (matched case-insensitively). */
    public GatewayResponse setHeader(String name, String value) {
        headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        headers.put(name, new ArrayList<>(List.of(value)));
        return this;
    }

    public boolean isServerError() {
        return status >= 500;
    }
}
