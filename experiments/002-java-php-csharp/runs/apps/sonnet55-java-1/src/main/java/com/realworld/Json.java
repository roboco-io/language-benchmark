package com.realworld;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Helpers for reading request bodies and rendering response objects. */
final class Json {

    private Json() {}

    /** Returns the named object wrapper (e.g. "user"), or an empty object node when it is missing. */
    static JsonNode wrapper(JsonNode root, String name) {
        JsonNode node = root == null ? null : root.get(name);
        if (node == null || !node.isObject()) {
            throw ApiException.unprocessable(Map.of(name, "can't be blank"));
        }
        return node;
    }

    /** Returns the text of a field, or null when the field is missing, null, or not text. */
    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    static String time(Instant t) {
        return TIME.format(t);
    }

    static Map<String, Object> userView(User u, String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.getEmail());
        m.put("token", token);
        m.put("username", u.getUsername());
        m.put("bio", u.getBio());
        m.put("image", u.getImage());
        return Map.of("user", m);
    }

    static Map<String, Object> profile(User u, boolean following) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        m.put("bio", u.getBio());
        m.put("image", u.getImage());
        m.put("following", following);
        return m;
    }
}
