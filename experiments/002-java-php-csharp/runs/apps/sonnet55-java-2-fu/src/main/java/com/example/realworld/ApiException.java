package com.example.realworld;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public final Map<String, List<String>> errors;

    public ApiException(HttpStatus status, Map<String, List<String>> errors) {
        super(errors.toString(), null, false, false);
        this.status = status;
        this.errors = errors;
    }

    public static ApiException of(HttpStatus status, String field, String message) {
        return new ApiException(status, Map.of(field, List.of(message)));
    }

    public static ApiException unauthorized(String message) {
        return of(HttpStatus.UNAUTHORIZED, "token", message);
    }

    public static ApiException notFound(String field) {
        return of(HttpStatus.NOT_FOUND, field, "not found");
    }

    public static ApiException forbidden(String field) {
        return of(HttpStatus.FORBIDDEN, field, "forbidden");
    }

    /** Collects per-field validation messages and throws 422 when any exist. */
    public static class Validation {
        private final Map<String, List<String>> errors = new LinkedHashMap<>();

        public void add(String field, String message) {
            errors.computeIfAbsent(field, k -> new java.util.ArrayList<>()).add(message);
        }

        public void throwIfAny() {
            if (!errors.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errors);
        }
    }
}
