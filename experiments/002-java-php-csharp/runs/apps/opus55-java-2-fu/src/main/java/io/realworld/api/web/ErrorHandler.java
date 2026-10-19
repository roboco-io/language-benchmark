package io.realworld.api.web;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    static ResponseEntity<Object> body(HttpStatus status, String field, String message) {
        return ResponseEntity.status(status).body(Map.of("errors", Map.of(field, List.of(message))));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> api(ApiException e) {
        return body(e.status(), e.field(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Object> unreadable(Exception e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "body", "is invalid");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> typeMismatch(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getName(), "is invalid");
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Object> noRoute(Exception e) {
        return body(HttpStatus.NOT_FOUND, "path", "not found");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> method(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "method", "not allowed");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> other(Exception e) {
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "server", "internal error");
    }
}
