package io.realworld.api.security;

import io.realworld.api.domain.User;
import io.realworld.api.domain.UserRepository;
import io.realworld.api.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** Resolves the current user from the "Authorization: Token <jwt>" header. */
@Component
public class Auth {
    private final JwtService jwt;
    private final UserRepository users;

    public Auth(JwtService jwt, UserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    /** Current user, or 401 if the token is missing or invalid. */
    public User require(HttpServletRequest request) {
        return resolve(request, true).orElseThrow();
    }

    /** Current user if a token is present; 401 if a token is present but invalid. */
    public Optional<User> optional(HttpServletRequest request) {
        return resolve(request, false);
    }

    private Optional<User> resolve(HttpServletRequest request, boolean required) {
        String token = extractToken(request);
        if (token == null) {
            if (required) throw ApiException.unauthorized("token", "is missing");
            return Optional.empty();
        }
        Long userId = jwt.verify(token).orElseThrow(() -> ApiException.unauthorized("token", "is invalid"));
        User user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("token", "is invalid"));
        return Optional.of(user);
    }

    public static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) return null;
        header = header.trim();
        for (String scheme : new String[] {"Token ", "Bearer "}) {
            if (header.regionMatches(true, 0, scheme, 0, scheme.length())) {
                String token = header.substring(scheme.length()).trim();
                return token.isEmpty() ? null : token;
            }
        }
        return header;
    }
}
