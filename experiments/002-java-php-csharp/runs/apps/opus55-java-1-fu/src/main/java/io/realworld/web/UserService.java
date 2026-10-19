package io.realworld.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.realworld.domain.User;
import io.realworld.domain.UserRepository;
import io.realworld.error.ApiException;
import io.realworld.web.Dtos.*;
import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserService {

    /** NIST 800-63B: at least 8 characters; at least 64 must be accepted (bcrypt uses up to 72 bytes). */
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD = 72;

    private final UserRepository users;
    private final Views views;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private final TransactionTemplate tx;

    public UserService(UserRepository users, Views views, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.views = views;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Loads the user behind a verified token; a token for a vanished user is treated as invalid. */
    User load(long userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.unauthorized("token", "is invalid"));
    }

    User loadOptional(Optional<Long> userId) {
        return userId.map(this::load).orElse(null);
    }

    // Password hashing is deliberately done before the write transaction starts, so the SQLite
    // write lock is never held while bcrypt runs.
    public UserResponse register(JsonNode body) {
        JsonNode in = Json.wrapped(body, "user");
        String username = Json.required(in, "username").trim();
        String email = Json.required(in, "email").trim();
        String password = Json.required(in, "password");
        checkPassword(password);
        String hash = encoder.encode(password);
        return tx.execute(status -> {
            if (users.existsByUsername(username)) {
                throw ApiException.taken("username");
            }
            if (users.existsByEmail(email)) {
                throw ApiException.taken("email");
            }
            User user = users.save(new User(username, email, hash));
            return new UserResponse(views.user(user));
        });
    }

    @Transactional(readOnly = true)
    public UserResponse login(JsonNode body) {
        JsonNode in = Json.wrapped(body, "user");
        String email = Json.required(in, "email").trim();
        String password = Json.required(in, "password");
        User user = users.findByEmail(email)
                .filter(u -> encoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("credentials", "invalid"));
        return new UserResponse(views.user(user));
    }

    @Transactional(readOnly = true)
    public UserResponse current(long userId) {
        return new UserResponse(views.user(load(userId)));
    }

    public UserResponse update(long userId, JsonNode body) {
        JsonNode in = Json.wrapped(body, "user");
        String rawUsername = Json.requiredIfPresent(in, "username");
        String rawEmail = Json.requiredIfPresent(in, "email");
        String password = Json.requiredIfPresent(in, "password");
        boolean hasBio = in.has("bio");
        boolean hasImage = in.has("image");
        String bio = Json.nullable(in, "bio");
        String image = Json.nullable(in, "image");
        String hash = null;
        if (password != null) {
            checkPassword(password);
            hash = encoder.encode(password);
        }
        String username = rawUsername == null ? null : rawUsername.trim();
        String email = rawEmail == null ? null : rawEmail.trim();
        String passwordHash = hash;

        return tx.execute(status -> {
            User user = load(userId);
            if (username != null) {
                if (!username.equals(user.getUsername()) && users.existsByUsername(username)) {
                    throw ApiException.taken("username");
                }
                user.setUsername(username);
            }
            if (email != null) {
                if (!email.equals(user.getEmail()) && users.existsByEmail(email)) {
                    throw ApiException.taken("email");
                }
                user.setEmail(email);
            }
            if (passwordHash != null) {
                user.setPasswordHash(passwordHash);
            }
            if (hasBio) {
                user.setBio(bio);
            }
            if (hasImage) {
                user.setImage(image);
            }
            return new UserResponse(views.user(user));
        });
    }

    @Transactional(readOnly = true)
    public ProfileResponse profile(String username, Optional<Long> viewerId) {
        User viewer = loadOptional(viewerId);
        User target = users.findByUsername(username).orElseThrow(() -> ApiException.notFound("profile"));
        return new ProfileResponse(views.profile(target, viewer));
    }

    @Transactional
    public ProfileResponse follow(String username, long viewerId, boolean follow) {
        User viewer = load(viewerId);
        User target = users.findByUsername(username).orElseThrow(() -> ApiException.notFound("profile"));
        if (follow) {
            if (!target.equals(viewer)) {
                viewer.getFollowing().add(target);
            }
        } else {
            viewer.getFollowing().remove(target);
        }
        return new ProfileResponse(views.profile(target, viewer));
    }

    private static void checkPassword(String password) {
        if (password.length() < MIN_PASSWORD) {
            throw ApiException.invalid("password", "is too short (minimum is " + MIN_PASSWORD + " characters)");
        }
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PASSWORD) {
            throw ApiException.invalid("password", "is too long (maximum is " + MAX_PASSWORD + " bytes)");
        }
    }
}
