package io.realworld.common;

import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Resolves the caller from an "Authorization: Token <jwt>" header
 * ("Bearer <jwt>" is accepted as well).
 */
@Component
public class Auth {

    private final Jwt jwt;

    public Auth(Jwt jwt) {
        this.jwt = jwt;
    }

    /** Returns the caller's user id, or empty when no header was sent. An invalid token is rejected. */
    public Optional<Long> optional(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String value = header.trim();
        int space = value.indexOf(' ');
        if (space < 0) {
            throw ApiException.unauthorized("is invalid");
        }
        String scheme = value.substring(0, space);
        if (!scheme.equalsIgnoreCase("Token") && !scheme.equalsIgnoreCase("Bearer")) {
            throw ApiException.unauthorized("is invalid");
        }
        return Optional.of(jwt.verify(value.substring(space + 1).trim())
                .orElseThrow(() -> ApiException.unauthorized("is invalid")));
    }

    /** Returns the caller's user id, or throws 401 when the header is missing or invalid. */
    public long require(String header) {
        return optional(header).orElseThrow(() -> ApiException.unauthorized("is missing"));
    }
}
