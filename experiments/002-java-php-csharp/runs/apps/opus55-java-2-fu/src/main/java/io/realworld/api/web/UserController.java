package io.realworld.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.realworld.api.domain.User;
import io.realworld.api.domain.UserRepository;
import io.realworld.api.security.Auth;
import io.realworld.api.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UserController {
    /** NIST SP 800-63B: at least 8 characters, and at least 64 must be accepted. */
    static final int MIN_PASSWORD = 8;
    /** BCrypt only uses the first 72 bytes of input, so longer passwords are rejected. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final JwtService jwt;
    private final Auth auth;
    private final PasswordEncoder encoder;

    public UserController(UserRepository users, JwtService jwt, Auth auth, PasswordEncoder encoder) {
        this.users = users;
        this.jwt = jwt;
        this.auth = auth;
        this.encoder = encoder;
    }

    @PostMapping("/users")
    @Transactional
    public ResponseEntity<Object> register(@RequestBody JsonNode root) {
        JsonNode in = Body.section(root, "user");
        String username = Body.required(in, "username").trim();
        String email = Body.required(in, "email").trim();
        String password = Body.required(in, "password");
        checkPassword(password);
        if (users.existsByUsername(username)) throw ApiException.taken("username");
        if (users.existsByEmail(email)) throw ApiException.taken("email");

        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        users.save(u);
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(u));
    }

    @PostMapping("/users/login")
    @Transactional(readOnly = true)
    public Map<String, Object> login(@RequestBody JsonNode root) {
        JsonNode in = Body.section(root, "user");
        String email = Body.required(in, "email").trim();
        String password = Body.required(in, "password");
        User u = users.findByEmail(email)
                .filter(found -> encoder.matches(password, found.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("credentials", "invalid"));
        return wrap(u);
    }

    @GetMapping("/user")
    @Transactional(readOnly = true)
    public Map<String, Object> current(HttpServletRequest request) {
        return wrap(auth.require(request));
    }

    @PutMapping("/user")
    @Transactional
    public Map<String, Object> update(HttpServletRequest request, @RequestBody JsonNode root) {
        User u = auth.require(request);
        JsonNode in = Body.section(root, "user");

        String username = Body.optionalNonBlank(in, "username");
        String email = Body.optionalNonBlank(in, "email");
        String password = Body.optionalNonBlank(in, "password");
        if (password != null) checkPassword(password);

        if (username != null) {
            username = username.trim();
            if (!username.equals(u.getUsername()) && users.existsByUsername(username)) {
                throw ApiException.taken("username");
            }
        }
        if (email != null) {
            email = email.trim();
            if (!email.equals(u.getEmail()) && users.existsByEmail(email)) {
                throw ApiException.taken("email");
            }
        }

        if (username != null) u.setUsername(username);
        if (email != null) u.setEmail(email);
        if (password != null) u.setPasswordHash(encoder.encode(password));
        if (in.has("bio")) u.setBio(emptyToNull(Body.string(in, "bio")));
        if (in.has("image")) u.setImage(emptyToNull(Body.string(in, "image")));
        users.save(u);
        return wrap(u);
    }

    private Map<String, Object> wrap(User u) {
        return Map.of("user", Views.user(u, jwt.issue(u.getId())));
    }

    private static void checkPassword(String password) {
        if (password.length() < MIN_PASSWORD) {
            throw ApiException.invalid("password", "is too short (minimum is " + MIN_PASSWORD + " characters)");
        }
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw ApiException.invalid("password", "is too long (maximum is " + MAX_PASSWORD_BYTES + " bytes)");
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
