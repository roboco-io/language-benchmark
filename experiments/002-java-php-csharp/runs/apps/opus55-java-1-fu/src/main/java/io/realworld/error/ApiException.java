package io.realworld.error;

import org.springframework.http.HttpStatus;

/** An error rendered as {"errors": {field: [message]}} with the given HTTP status. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String field;

    public ApiException(HttpStatus status, String field, String message) {
        super(message);
        this.status = status;
        this.field = field;
    }

    public HttpStatus status() {
        return status;
    }

    public String field() {
        return field;
    }

    public static ApiException blank(String field) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, field, "can't be blank");
    }

    public static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, field, message);
    }

    public static ApiException taken(String field) {
        return new ApiException(HttpStatus.CONFLICT, field, "has already been taken");
    }

    public static ApiException notFound(String field) {
        return new ApiException(HttpStatus.NOT_FOUND, field, "not found");
    }

    public static ApiException forbidden(String field) {
        return new ApiException(HttpStatus.FORBIDDEN, field, "forbidden");
    }

    public static ApiException unauthorized(String field, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, field, message);
    }
}
