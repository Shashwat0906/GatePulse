package com.gatepulse.config;

/**
 * How the gateway finds its backends.
 *
 * <ul>
 *   <li>{@link #EMBEDDED}: three dummy backends are started inside the gateway JVM
 *       (ports 9001-9003 by default). Used for single-service free-tier deployment.</li>
 *   <li>{@link #EXTERNAL}: backends run elsewhere (e.g. separate docker-compose containers)
 *       and are listed in the {@code BACKEND_URLS} environment variable.</li>
 * </ul>
 */
public enum Mode {
    EMBEDDED,
    EXTERNAL
}
