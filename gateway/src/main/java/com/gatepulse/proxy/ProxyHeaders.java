package com.gatepulse.proxy;

import java.util.Locale;
import java.util.Set;

/**
 * Rules for which headers may cross the gateway.
 *
 * <p>"Hop-by-hop" headers (RFC 9110 section 7.6.1) describe a single TCP connection, not
 * the request, so a proxy must not forward them. On top of that, the JDK
 * {@link java.net.http.HttpClient} refuses to let callers set a few headers it manages
 * itself ({@code Host}, {@code Content-Length}, {@code Expect}...).
 */
final class ProxyHeaders {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-connection", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "trailers", "transfer-encoding", "upgrade");

    /** Managed by the HttpClient (setting them throws) or recomputed by it. */
    private static final Set<String> CLIENT_MANAGED = Set.of("host", "content-length", "expect");

    /** Set by our own HTTP server when writing the response; forwarding would duplicate them. */
    private static final Set<String> SERVER_MANAGED = Set.of("content-length", "date", "server");

    private ProxyHeaders() {
    }

    static boolean forwardToBackend(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !HOP_BY_HOP.contains(lower) && !CLIENT_MANAGED.contains(lower);
    }

    static boolean forwardToClient(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        // ":status" style pseudo headers can appear in JDK header maps; never forward them.
        return !lower.startsWith(":") && !HOP_BY_HOP.contains(lower) && !SERVER_MANAGED.contains(lower);
    }
}
