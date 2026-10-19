package io.realworld.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.realworld.error.ApiException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Helpers for reading request bodies and formatting values. */
final class Json {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Json() {
    }

    /** Returns the object wrapped under {@code key}, e.g. {"user": {...}}. */
    static JsonNode wrapped(JsonNode root, String key) {
        if (root == null || !root.path(key).isObject()) {
            throw ApiException.invalid(key, "is missing");
        }
        return root.get(key);
    }

    /** A required, non-blank string field. */
    static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw ApiException.blank(field);
        }
        return value.asText();
    }

    /** For updates: null when absent; when present, must be a non-blank string. */
    static String requiredIfPresent(JsonNode node, String field) {
        return node.has(field) ? required(node, field) : null;
    }

    /** For nullable fields: empty strings and JSON null are normalised to null. */
    static String nullable(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw ApiException.invalid(field, "must be a string");
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    static String timestamp(long epochMillis) {
        return ISO_MILLIS.format(Instant.ofEpochMilli(epochMillis));
    }
}
