package com.gatepulse.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.http.Context;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One shared, pre-configured Jackson {@link ObjectMapper}.
 *
 * <p>ObjectMapper is thread-safe once configured and expensive to create, so the whole
 * application reuses this single instance. Responses are written by hand (bytes + content
 * type) rather than through Javalin's JSON plugin, which keeps serialization explicit.
 */
public final class Json {

    public static final String CONTENT_TYPE = "application/json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static byte[] toBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + value.getClass().getSimpleName(), e);
        }
    }

    /** Parses a JSON body; an empty body yields an empty object node. */
    public static JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException("Request body is not valid JSON", e);
        }
    }

    /** Writes {@code value} as a JSON response with the given status. */
    public static void respond(Context ctx, int status, Object value) {
        ctx.status(status).contentType(CONTENT_TYPE).result(toBytes(value));
    }
}
