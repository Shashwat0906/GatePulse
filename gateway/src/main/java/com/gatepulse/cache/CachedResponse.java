package com.gatepulse.cache;

import com.gatepulse.core.GatewayResponse;

import java.util.List;
import java.util.Map;

/**
 * An immutable copy of a backend response, safe to share between threads and to hand out many
 * times. Each hit builds a fresh {@link GatewayResponse} from it, so headers added by outer
 * filters on one request can never leak into another.
 */
public record CachedResponse(int status, Map<String, List<String>> headers, byte[] body, String backendId) {

    public CachedResponse {
        headers = Map.copyOf(headers);
    }

    public static CachedResponse of(GatewayResponse response, String backendId) {
        return new CachedResponse(response.status(), response.headers(), response.body().clone(), backendId);
    }

    public GatewayResponse toResponse() {
        return new GatewayResponse(status, headers, body);
    }
}
