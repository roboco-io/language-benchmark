package io.realworld.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Helpers for reading request bodies while distinguishing absent fields from explicit nulls. */
public final class Body {
    private Body() {}

    /** Returns the wrapped object, e.g. {"user": {...}} -> {...}; 422 if missing or not an object. */
    public static JsonNode section(JsonNode root, String name) {
        JsonNode node = root == null ? null : root.get(name);
        if (node == null || !node.isObject()) throw ApiException.invalid(name, "is missing");
        return node;
    }

    public static boolean has(JsonNode obj, String field) {
        return obj.has(field);
    }

    /** String value of a field; null when absent or JSON null; 422 when not a string. */
    public static String string(JsonNode obj, String field) {
        JsonNode v = obj.get(field);
        if (v == null || v.isNull()) return null;
        if (!v.isTextual()) throw ApiException.invalid(field, "must be a string");
        return v.asText();
    }

    /** Required non-blank string (used for create operations). */
    public static String required(JsonNode obj, String field) {
        String s = string(obj, field);
        if (s == null || s.isBlank()) throw ApiException.blank(field);
        return s;
    }

    /** For partial updates: if the field is present it must be a non-blank string. */
    public static String optionalNonBlank(JsonNode obj, String field) {
        if (!obj.has(field)) return null;
        return required(obj, field);
    }

    /** List of strings; 422 if the value is not an array of strings. */
    public static List<String> stringList(JsonNode obj, String field) {
        JsonNode v = obj.get(field);
        if (v == null || !v.isArray()) throw ApiException.invalid(field, "must be an array");
        List<String> out = new ArrayList<>();
        for (JsonNode item : v) {
            if (!item.isTextual()) throw ApiException.invalid(field, "must contain strings");
            out.add(item.asText());
        }
        return out;
    }
}
