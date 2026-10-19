package com.example.realworld;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Helpers for reading request bodies and collecting validation errors. */
final class Json {
    private Json() {}

    /** Returns body[wrapper], which must be a JSON object. */
    static JsonNode wrapped(JsonNode body, String wrapper) {
        JsonNode node = body == null ? null : body.get(wrapper);
        if (node == null || !node.isObject()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, wrapper, "can't be blank");
        }
        return node;
    }

    static boolean blank(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isTextual() || v.asText().isBlank();
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Collects field errors; {@link #throwIfAny()} raises a 422 when any were added. */
    static final class Errors {
        private final Map<String, List<String>> errors = new LinkedHashMap<>();

        void add(String field, String message) {
            errors.computeIfAbsent(field, k -> new ArrayList<>()).add(message);
        }

        void requireNotBlank(JsonNode node, String field) {
            if (blank(node, field)) {
                add(field, "can't be blank");
            }
        }

        boolean has(String field) {
            return errors.containsKey(field);
        }

        void throwIfAny() {
            if (!errors.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errors);
            }
        }
    }
}
