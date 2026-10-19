package io.realworld.auth;

import io.realworld.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Resolves the authenticated user id from the "Authorization: Token <jwt>" header. */
@Component
public class CurrentUser {

    private final JwtService jwt;

    public CurrentUser(JwtService jwt) {
        this.jwt = jwt;
    }

    /** Returns the user id, or throws 401 when the token is missing or invalid. */
    public long require(HttpServletRequest request) {
        return optional(request).orElseThrow(() -> ApiException.unauthorized("token", "is missing"));
    }

    /** Returns the user id when a token is present; a present but invalid token is rejected with 401. */
    public Optional<Long> optional(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String token = header.trim();
        if (token.regionMatches(true, 0, "Token ", 0, 6) || token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(token.indexOf(' ') + 1).trim();
        } else {
            throw ApiException.unauthorized("token", "is invalid");
        }
        return Optional.of(jwt.verify(token).orElseThrow(() -> ApiException.unauthorized("token", "is invalid")));
    }
}
