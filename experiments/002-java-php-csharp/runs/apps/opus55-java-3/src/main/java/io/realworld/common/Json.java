package io.realworld.common;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Helpers for reading request bodies as raw JSON trees. Raw trees are used so that
 * an absent field ("leave unchanged") can be told apart from an explicit null.
 */
public final class Json {

    private Json() {
    }

    /** Returns the object under the given wrapper key (e.g. "user"), or throws 422. */
    public static JsonNode wrapper(JsonNode body, String key) {
        JsonNode node = body == null ? null : body.get(key);
        if (node == null || !node.isObject()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, key, BLANK_OBJECT);
        }
        return node;
    }

    private static final String BLANK_OBJECT = "can't be blank";

    public static boolean has(JsonNode node, String field) {
        return node.has(field);
    }

    /** Returns the field as text, or null when it is absent, null or not a scalar value. */
    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        return value.asText();
    }
}
