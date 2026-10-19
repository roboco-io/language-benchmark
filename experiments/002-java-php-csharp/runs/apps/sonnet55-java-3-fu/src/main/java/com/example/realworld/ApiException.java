package com.example.realworld;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error that is rendered as {"errors": {field: [messages]}} with the given HTTP status. */
public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public final Map<String, List<String>> errors = new LinkedHashMap<>();

    public ApiException(HttpStatus status, String field, String message) {
        super(field + " " + message, null, false, false);
        this.status = status;
        errors.put(field, List.of(message));
    }

    public ApiException(HttpStatus status, Map<String, List<String>> errors) {
        super(errors.toString(), null, false, false);
        this.status = status;
        this.errors.putAll(errors);
    }
}
