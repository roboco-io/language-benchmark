package com.realworld;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error that is rendered as {"errors": {field: [message, ...]}}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, List<String>> errors;

    public ApiException(HttpStatus status, Map<String, List<String>> errors) {
        super(errors.toString(), null, false, false);
        this.status = status;
        this.errors = errors;
    }

    public ApiException(HttpStatus status, String field, String message) {
        this(status, Map.of(field, List.of(message)));
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "token", message);
    }

    public static ApiException notFound(String field) {
        return new ApiException(HttpStatus.NOT_FOUND, field, "not found");
    }

    public static ApiException forbidden(String field) {
        return new ApiException(HttpStatus.FORBIDDEN, field, "forbidden");
    }

    public static ApiException unprocessable(Map<String, String> fieldErrors) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        fieldErrors.forEach((k, v) -> errors.put(k, List.of(v)));
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errors);
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, List<String>> errors() {
        return errors;
    }
}
