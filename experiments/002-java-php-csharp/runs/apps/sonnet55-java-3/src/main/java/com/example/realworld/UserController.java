package com.example.realworld;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Transactional
public class UserController {
    private static final int MIN_PASSWORD = 8;

    private final UserRepository users;
    private final Security security;

    UserController(UserRepository users, Security security) {
        this.users = users;
        this.security = security;
    }

    static Map<String, Object> userBody(User u, String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.email);
        m.put("username", u.username);
        m.put("bio", u.bio);
        m.put("image", u.image);
        m.put("token", token);
        return Map.of("user", m);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody JsonNode body) {
        JsonNode in = Json.wrapped(body, "user");
        var errors = new Json.Errors();
        errors.requireNotBlank(in, "username");
        errors.requireNotBlank(in, "email");
        errors.requireNotBlank(in, "password");
        if (!errors.has("email") && !in.get("email").asText().matches("^[^@\\s]+@[^@\\s]+$")) {
            errors.add("email", "is invalid");
        }
        if (!errors.has("password") && in.get("password").asText().length() < MIN_PASSWORD) {
            errors.add("password", "is too short (minimum is 8 characters)");
        }
        errors.throwIfAny();

        String username = in.get("username").asText().trim();
        String email = in.get("email").asText().trim();
        var conflicts = new LinkedHashMap<String, List<String>>();
        if (users.existsByUsername(username)) {
            conflicts.put("username", List.of("has already been taken"));
        }
        if (users.existsByEmail(email)) {
            conflicts.put("email", List.of("has already been taken"));
        }
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, conflicts);
        }

        User u = new User();
        u.username = username;
        u.email = email;
        u.passwordHash = security.hashPassword(in.get("password").asText());
        users.save(u);
        return userBody(u, security.issueToken(u));
    }

    @PostMapping("/users/login")
    public Map<String, Object> login(@RequestBody JsonNode body) {
        JsonNode in = Json.wrapped(body, "user");
        var errors = new Json.Errors();
        errors.requireNotBlank(in, "email");
        errors.requireNotBlank(in, "password");
        errors.throwIfAny();

        User u = users.findByEmail(in.get("email").asText().trim()).orElse(null);
        if (u == null || !security.checkPassword(in.get("password").asText(), u.passwordHash)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "credentials", "invalid");
        }
        return userBody(u, security.issueToken(u));
    }

    @GetMapping("/user")
    public Map<String, Object> current(@RequestHeader(value = "Authorization", required = false) String auth) {
        User u = security.requiredUser(auth);
        return userBody(u, security.issueToken(u));
    }

    @PutMapping("/user")
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody JsonNode body) {
        User u = security.requiredUser(auth);
        JsonNode in = Json.wrapped(body, "user");

        var errors = new Json.Errors();
        for (String field : List.of("username", "email", "password")) {
            if (in.has(field) && Json.blank(in, field)) {
                errors.add(field, "can't be blank");
            }
        }
        if (in.has("email") && !errors.has("email") && !in.get("email").asText().matches("^[^@\\s]+@[^@\\s]+$")) {
            errors.add("email", "is invalid");
        }
        if (in.has("password") && !errors.has("password") && in.get("password").asText().length() < MIN_PASSWORD) {
            errors.add("password", "is too short (minimum is 8 characters)");
        }
        errors.throwIfAny();

        var conflicts = new LinkedHashMap<String, List<String>>();
        if (in.has("username")) {
            String username = in.get("username").asText().trim();
            if (!username.equals(u.username) && users.existsByUsername(username)) {
                conflicts.put("username", List.of("has already been taken"));
            } else {
                u.username = username;
            }
        }
        if (in.has("email")) {
            String email = in.get("email").asText().trim();
            if (!email.equals(u.email) && users.existsByEmail(email)) {
                conflicts.put("email", List.of("has already been taken"));
            } else {
                u.email = email;
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, conflicts);
        }
        if (in.has("password")) {
            u.passwordHash = security.hashPassword(in.get("password").asText());
        }
        if (in.has("bio")) {
            u.bio = nullIfBlank(Json.text(in, "bio"));
        }
        if (in.has("image")) {
            u.image = nullIfBlank(Json.text(in, "image"));
        }
        users.save(u);
        return userBody(u, security.issueToken(u));
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
