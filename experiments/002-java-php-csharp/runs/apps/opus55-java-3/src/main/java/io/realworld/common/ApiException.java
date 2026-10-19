package io.realworld.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

/** An error that maps directly to a RealWorld error response: {"errors": {field: [messages]}}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, List<String>> errors;

    public ApiException(HttpStatus status, Map<String, List<String>> errors) {
        super(status + " " + errors);
        this.status = status;
        this.errors = errors;
    }

    public ApiException(HttpStatus status, String field, String message) {
        this(status, single(field, message));
    }

    private static Map<String, List<String>> single(String field, String message) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(field, List.of(message));
        return map;
    }

    public static ApiException notFound(String field) {
        return new ApiException(HttpStatus.NOT_FOUND, field, "not found");
    }

    public static ApiException forbidden(String field) {
        return new ApiException(HttpStatus.FORBIDDEN, field, "forbidden");
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "token", message);
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, List<String>> errors() {
        return errors;
    }
}
