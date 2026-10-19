package io.realworld.error;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return body(e.status(), e.field(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Map<String, Object>> handleUnreadable(Exception e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "body", "is invalid");
    }

    /** A concurrent insert won the race on a unique column (username, email, slug, tag name). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(DataIntegrityViolationException e) {
        return body(HttpStatus.CONFLICT, "resource", "has already been taken");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getName(), "is invalid");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String field, String message) {
        return ResponseEntity.status(status).body(Map.of("errors", Map.of(field, List.of(message))));
    }
}
