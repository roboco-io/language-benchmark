package io.realworld.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

/** Collects field validation errors and throws a single 422 response for all of them. */
public class Validation {

    public static final String BLANK = "can't be blank";

    private final Map<String, List<String>> errors = new LinkedHashMap<>();

    public Validation add(String field, String message) {
        errors.computeIfAbsent(field, k -> new ArrayList<>()).add(message);
        return this;
    }

    /** Records "can't be blank" when the value is null or whitespace only. */
    public Validation notBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            add(field, BLANK);
        }
        return this;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public void throwIfAny() {
        if (hasErrors()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errors);
        }
    }
}
