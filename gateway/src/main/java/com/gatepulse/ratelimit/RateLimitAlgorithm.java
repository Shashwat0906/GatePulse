package com.gatepulse.ratelimit;

import java.util.Arrays;
import java.util.Locale;

/** The rate-limiting algorithms the gateway can switch between at runtime. */
public enum RateLimitAlgorithm {

    TOKEN_BUCKET("token-bucket"),
    SLIDING_WINDOW_LOG("sliding-window");

    private final String id;

    RateLimitAlgorithm(String id) {
        this.id = id;
    }

    /** Wire name used in the admin API, env vars and dashboard. */
    public String id() {
        return id;
    }

    public static RateLimitAlgorithm fromId(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (RateLimitAlgorithm algorithm : values()) {
            if (algorithm.id.equals(normalized) || algorithm.name().toLowerCase(Locale.ROOT).replace('_', '-').equals(normalized)) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("Unknown rate limit algorithm '" + value + "', expected one of "
                + Arrays.stream(values()).map(RateLimitAlgorithm::id).toList());
    }
}
