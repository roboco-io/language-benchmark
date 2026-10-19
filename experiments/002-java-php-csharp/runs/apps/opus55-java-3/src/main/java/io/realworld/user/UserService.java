package io.realworld.user;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import io.realworld.common.ApiException;
import io.realworld.common.Json;
import io.realworld.common.Jwt;
import io.realworld.common.Validation;

@Service
public class UserService {

    /** NIST SP 800-63B: at least 8 characters; at least 64 must be accepted. */
    static final int MIN_PASSWORD = 8;
    static final int MAX_PASSWORD = 128;
    static final String TAKEN = "has already been taken";

    private final UserRepository users;
    private final Jwt jwt;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    /** Write transactions are serialized, so slow password hashing happens before they start. */
    private final TransactionTemplate tx;

    public UserService(UserRepository users, Jwt jwt, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.jwt = jwt;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public UserView register(JsonNode body) {
        JsonNode node = Json.wrapper(body, "user");
        String username = trim(Json.text(node, "username"));
        String email = trim(Json.text(node, "email"));
        String password = Json.text(node, "password");

        Validation v = new Validation().notBlank("username", username).notBlank("email", email);
        checkPassword(v, password);
        v.throwIfAny();
        String hash = encoder.encode(password);

        return tx.execute(status -> {
            Map<String, List<String>> conflicts = new LinkedHashMap<>();
            if (users.existsByUsername(username)) {
                conflicts.put("username", List.of(TAKEN));
            }
            if (users.existsByEmail(email)) {
                conflicts.put("email", List.of(TAKEN));
            }
            if (!conflicts.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, conflicts);
            }
            User user = users.save(new User(username, email, hash));
            return UserView.of(user, jwt.issue(user.getId()));
        });
    }

    @Transactional(readOnly = true)
    public UserView login(JsonNode body) {
        JsonNode node = Json.wrapper(body, "user");
        String email = trim(Json.text(node, "email"));
        String password = Json.text(node, "password");
        new Validation().notBlank("email", email).notBlank("password", password).throwIfAny();

        User user = users.findByEmail(email)
                .filter(u -> encoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "credentials", "invalid"));
        return UserView.of(user, jwt.issue(user.getId()));
    }

    @Transactional(readOnly = true)
    public UserView current(long userId) {
        User user = load(userId);
        return UserView.of(user, jwt.issue(user.getId()));
    }

    public UserView update(long userId, JsonNode body) {
        JsonNode node = Json.wrapper(body, "user");
        Validation v = new Validation();

        String username = null;
        String email = null;
        String password = null;
        if (node.has("username")) {
            username = trim(Json.text(node, "username"));
            v.notBlank("username", username);
        }
        if (node.has("email")) {
            email = trim(Json.text(node, "email"));
            v.notBlank("email", email);
        }
        if (node.has("password")) {
            password = Json.text(node, "password");
            checkPassword(v, password);
        }
        v.throwIfAny();
        String hash = password == null ? null : encoder.encode(password);
        String newUsername = username;
        String newEmail = email;

        return tx.execute(status -> {
            User user = load(userId);
            Map<String, List<String>> conflicts = new LinkedHashMap<>();
            if (newUsername != null && users.existsByUsernameAndIdNot(newUsername, userId)) {
                conflicts.put("username", List.of(TAKEN));
            }
            if (newEmail != null && users.existsByEmailAndIdNot(newEmail, userId)) {
                conflicts.put("email", List.of(TAKEN));
            }
            if (!conflicts.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, conflicts);
            }

            if (newUsername != null) {
                user.setUsername(newUsername);
            }
            if (newEmail != null) {
                user.setEmail(newEmail);
            }
            if (hash != null) {
                user.setPasswordHash(hash);
            }
            // bio and image are nullable: null and "" both clear the value.
            if (node.has("bio")) {
                user.setBio(emptyToNull(Json.text(node, "bio")));
            }
            if (node.has("image")) {
                user.setImage(emptyToNull(Json.text(node, "image")));
            }
            return UserView.of(user, jwt.issue(user.getId()));
        });
    }

    /** Loads the authenticated user; a token for a user that no longer exists is invalid. */
    public User load(long userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.unauthorized("is invalid"));
    }

    private static void checkPassword(Validation v, String password) {
        if (password == null || password.isEmpty()) {
            v.add("password", Validation.BLANK);
        } else if (password.length() < MIN_PASSWORD) {
            v.add("password", "is too short (minimum is " + MIN_PASSWORD + " characters)");
        } else if (password.length() > MAX_PASSWORD) {
            v.add("password", "is too long (maximum is " + MAX_PASSWORD + " characters)");
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
