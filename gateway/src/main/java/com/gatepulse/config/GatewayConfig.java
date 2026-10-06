package com.gatepulse.config;

import com.gatepulse.cache.CacheSettings;
import com.gatepulse.circuitbreaker.CircuitBreakerConfig;
import com.gatepulse.loadbalancer.LoadBalancingStrategies;
import com.gatepulse.ratelimit.RateLimitAlgorithm;
import com.gatepulse.ratelimit.RateLimitConfig;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable startup configuration, read from environment variables.
 *
 * <p>Every value has a sensible default so {@code java -jar gatepulse.jar} works with no setup.
 * Parsing takes a plain {@code Map} instead of calling {@link System#getenv()} directly,
 * which keeps the class trivially unit-testable.
 *
 * <p>Values that can change while the gateway is running (load-balancing algorithm,
 * rate-limit settings, cache TTL) live in separate runtime holders; this record only
 * holds what is fixed for the lifetime of the process.
 */
public record GatewayConfig(
        int port,
        Mode mode,
        List<BackendDefinition> backends,
        Duration healthCheckInterval,
        Duration healthCheckTimeout,
        int unhealthyThreshold,
        int healthyThreshold,
        Duration backendConnectTimeout,
        Duration backendRequestTimeout,
        int maxProxyAttempts,
        boolean trustForwardedHeaders,
        List<String> corsOrigins,
        String loadBalancerStrategy,
        RateLimitConfig rateLimit,
        CacheSettings cache,
        CircuitBreakerConfig circuitBreaker,
        String adminToken,
        Duration steadyTrafficMaxDuration) {

    public static final List<Integer> DEFAULT_EMBEDDED_PORTS = List.of(9001, 9002, 9003);

    public GatewayConfig {
        backends = List.copyOf(backends);
        corsOrigins = List.copyOf(corsOrigins);
        if (backends.isEmpty()) {
            throw new IllegalArgumentException("At least one backend must be configured");
        }
        LoadBalancingStrategies.create(loadBalancerStrategy); // validates the name
        long distinctIds = backends.stream().map(BackendDefinition::id).distinct().count();
        if (distinctIds != backends.size()) {
            throw new IllegalArgumentException("Backend ids must be unique: " + backends);
        }
    }

    /** Builds the configuration from the real process environment. */
    public static GatewayConfig fromEnvironment() {
        return fromEnv(System.getenv());
    }

    /**
     * Builds the configuration from an arbitrary key/value map (the process environment in
     * production, a hand-made map in tests).
     *
     * @throws IllegalArgumentException with a readable message if any value is invalid
     */
    public static GatewayConfig fromEnv(Map<String, String> env) {
        EnvReader r = new EnvReader(env);

        Mode mode = r.enumValue("MODE", Mode.class, Mode.EMBEDDED);
        List<Integer> weights = r.intList("BACKEND_WEIGHTS", List.of());
        List<BackendDefinition> backends = switch (mode) {
            case EMBEDDED -> embeddedBackends(r.intList("EMBEDDED_BACKEND_PORTS", DEFAULT_EMBEDDED_PORTS), weights);
            case EXTERNAL -> externalBackends(r.required("BACKEND_URLS"), weights);
        };

        return new GatewayConfig(
                r.intValue("PORT", 8080, 0, 65535),
                mode,
                backends,
                r.millis("HEALTH_CHECK_INTERVAL_MS", 5000, 50),
                r.millis("HEALTH_CHECK_TIMEOUT_MS", 2000, 10),
                r.intValue("HEALTH_UNHEALTHY_THRESHOLD", 2, 1, 100),
                r.intValue("HEALTH_HEALTHY_THRESHOLD", 2, 1, 100),
                r.millis("BACKEND_CONNECT_TIMEOUT_MS", 1000, 10),
                r.millis("BACKEND_REQUEST_TIMEOUT_MS", 5000, 10),
                r.intValue("PROXY_MAX_ATTEMPTS", 3, 1, 10),
                r.bool("TRUST_FORWARDED_HEADERS", true),
                r.stringList("CORS_ORIGINS", List.of("*")),
                r.string("LB_STRATEGY", "round-robin"),
                new RateLimitConfig(
                        r.bool("RATE_LIMIT_ENABLED", true),
                        RateLimitAlgorithm.fromId(r.string("RATE_LIMIT_ALGORITHM", "token-bucket")),
                        r.intValue("RATE_LIMIT_LIMIT", 100, 1, RateLimitConfig.MAX_LIMIT),
                        r.doubleValue("RATE_LIMIT_REFILL_PER_SEC", 50),
                        r.intValue("RATE_LIMIT_WINDOW_MS", 1000, 1, (int) RateLimitConfig.MAX_WINDOW_MILLIS)),
                new CacheSettings(
                        r.bool("CACHE_ENABLED", true),
                        r.intValue("CACHE_TTL_MS", 5000, 1, (int) CacheSettings.MAX_TTL_MILLIS),
                        r.intValue("CACHE_CAPACITY", 500, 1, CacheSettings.MAX_CAPACITY)),
                new CircuitBreakerConfig(
                        r.intValue("CB_FAILURE_THRESHOLD", 5, 1, 1000),
                        r.millis("CB_OPEN_DURATION_MS", 10_000, 1),
                        r.intValue("CB_HALF_OPEN_TRIALS", 3, 1, 100)),
                r.string("ADMIN_TOKEN", null),
                Duration.ofSeconds(r.intValue("STEADY_TRAFFIC_MAX_SECONDS", 300, 1, 86_400)));
    }

    /** Whether admin changes require a token (otherwise: open demo mode). */
    public boolean adminTokenRequired() {
        return adminToken != null && !adminToken.isBlank();
    }

    /** Never print the admin token in logs. */
    @Override
    public String toString() {
        return "GatewayConfig[port=" + port + ", mode=" + mode + ", backends=" + backends
                + ", loadBalancer=" + loadBalancerStrategy + ", rateLimit=" + rateLimit + ", cache=" + cache
                + ", circuitBreaker=" + circuitBreaker + ", adminTokenRequired=" + adminTokenRequired() + "]";
    }

    private static List<BackendDefinition> embeddedBackends(List<Integer> ports, List<Integer> weights) {
        List<BackendDefinition> result = new ArrayList<>(ports.size());
        for (int i = 0; i < ports.size(); i++) {
            int port = ports.get(i);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid EMBEDDED_BACKEND_PORTS entry: " + port);
            }
            result.add(new BackendDefinition(
                    "backend-" + (i + 1), URI.create("http://127.0.0.1:" + port), weightAt(weights, i)));
        }
        return result;
    }

    private static List<BackendDefinition> externalBackends(String urls, List<Integer> weights) {
        String[] parts = urls.split(",");
        List<BackendDefinition> result = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            String raw = parts[i].trim();
            if (raw.isEmpty()) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid URL in BACKEND_URLS: '" + raw + "'", e);
            }
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException(
                        "BACKEND_URLS entries must look like http://host:port but got '" + raw + "'");
            }
            result.add(new BackendDefinition("backend-" + (result.size() + 1), uri, weightAt(weights, i)));
        }
        return result;
    }

    private static int weightAt(List<Integer> weights, int index) {
        return index < weights.size() ? weights.get(index) : 1;
    }

    /** Small helper that turns raw strings into typed values with clear error messages. */
    private static final class EnvReader {
        private final Map<String, String> env;

        EnvReader(Map<String, String> env) {
            this.env = env;
        }

        private String raw(String key) {
            String value = env.get(key);
            return value == null || value.isBlank() ? null : value.trim();
        }

        String string(String key, String defaultValue) {
            String value = raw(key);
            return value == null ? defaultValue : value;
        }

        double doubleValue(String key, double defaultValue) {
            String value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be a number but was '" + value + "'", e);
            }
        }

        String required(String key) {
            String value = raw(key);
            if (value == null) {
                throw new IllegalArgumentException(key + " is required in this mode");
            }
            return value;
        }

        int intValue(String key, int defaultValue, int min, int max) {
            String value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < min || parsed > max) {
                    throw new IllegalArgumentException(
                            key + " must be between " + min + " and " + max + " but was " + parsed);
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be an integer but was '" + value + "'", e);
            }
        }

        Duration millis(String key, int defaultMillis, int minMillis) {
            return Duration.ofMillis(intValue(key, defaultMillis, minMillis, Integer.MAX_VALUE));
        }

        boolean bool(String key, boolean defaultValue) {
            String value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "true", "1", "yes" -> true;
                case "false", "0", "no" -> false;
                default -> throw new IllegalArgumentException(key + " must be true or false but was '" + value + "'");
            };
        }

        <E extends Enum<E>> E enumValue(String key, Class<E> type, E defaultValue) {
            String value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        key + " must be one of " + Arrays.toString(type.getEnumConstants()) + " but was '" + value + "'",
                        e);
            }
        }

        List<String> stringList(String key, List<String> defaultValue) {
            String value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }

        List<Integer> intList(String key, List<Integer> defaultValue) {
            String value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Integer::parseInt)
                        .toList();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be a comma-separated list of integers but was '" + value + "'", e);
            }
        }
    }
}
