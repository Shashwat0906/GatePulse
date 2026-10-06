package com.gatepulse.admin;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards admin endpoints that <em>change</em> something (POST/PUT/PATCH/DELETE).
 *
 * <p>Reads (metrics, the live stream, current config) stay public so the dashboard can be
 * viewed by anyone; changes require the token from the {@code ADMIN_TOKEN} env var, sent as
 * {@code Authorization: Bearer <token>} or {@code X-Admin-Token: <token>}.
 *
 * <p>If {@code ADMIN_TOKEN} is not set, the gateway runs in open demo mode (logged as a warning
 * at startup). The comparison is constant-time ({@link MessageDigest#isEqual}) so the response
 * time does not leak how many leading characters of a guess were right.
 */
public final class AdminAuth implements Handler {

    private final byte[] token;

    /** @param token required token, or {@code null}/blank for open demo mode */
    public AdminAuth(String token) {
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    public boolean required() {
        return token != null;
    }

    @Override
    public void handle(Context ctx) {
        if (token == null || isReadOnly(ctx.method())) {
            return;
        }
        String presented = presentedToken(ctx);
        if (presented == null) {
            throw new ApiException(401, "unauthorized", "Admin token required (Authorization: Bearer <token>)");
        }
        if (!MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(403, "forbidden", "Invalid admin token");
        }
    }

    private static boolean isReadOnly(HandlerType method) {
        return method == HandlerType.GET || method == HandlerType.HEAD || method == HandlerType.OPTIONS;
    }

    private static String presentedToken(Context ctx) {
        String authorization = ctx.header("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        String header = ctx.header("X-Admin-Token");
        return header == null || header.isBlank() ? null : header.trim();
    }
}
