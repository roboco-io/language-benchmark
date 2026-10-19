package com.example.realworld;

import com.example.realworld.Entities.Follow;
import com.example.realworld.Entities.User;
import com.example.realworld.Repositories.FollowRepo;
import com.example.realworld.Repositories.UserRepo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UserController {
    private static final int MIN_PASSWORD = 8;

    private final UserRepo users;
    private final FollowRepo follows;
    private final Security security;
    private final Tx tx;

    public UserController(UserRepo users, FollowRepo follows, Security security, Tx tx) {
        this.users = users;
        this.follows = follows;
        this.security = security;
        this.tx = tx;
    }

    static Map<String, Object> profile(User u, boolean following) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.username);
        m.put("bio", u.bio);
        m.put("image", u.image);
        m.put("following", following);
        return m;
    }

    private Map<String, Object> userJson(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.email);
        m.put("token", security.issueToken(u));
        m.put("username", u.username);
        m.put("bio", u.bio);
        m.put("image", u.image);
        return Map.of("user", m);
    }

    /** Reads a string field; null when absent or JSON null. */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static JsonNode section(JsonNode body, String name) {
        JsonNode n = body == null ? null : body.get(name);
        if (n == null || !n.isObject()) throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, name, "can't be blank");
        return n;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) JsonNode body) {
        JsonNode in = section(body, "user");
        String username = text(in, "username"), email = text(in, "email"), password = text(in, "password");
        var v = new ApiException.Validation();
        if (blank(username)) v.add("username", "can't be blank");
        if (blank(email)) v.add("email", "can't be blank");
        if (blank(password)) v.add("password", "can't be blank");
        else if (password.length() < MIN_PASSWORD) v.add("password", "is too short (minimum is 8 characters)");
        v.throwIfAny();

        User created = tx.write(() -> {
            var taken = new LinkedHashMap<String, java.util.List<String>>();
            if (users.existsByUsername(username)) taken.put("username", java.util.List.of("has already been taken"));
            if (users.existsByEmail(email)) taken.put("email", java.util.List.of("has already been taken"));
            if (!taken.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, taken);
            User u = new User();
            u.username = username;
            u.email = email;
            u.passwordHash = security.hashPassword(password);
            return users.save(u);
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(userJson(created));
    }

    @PostMapping("/users/login")
    public Map<String, Object> login(@RequestBody(required = false) JsonNode body) {
        JsonNode in = section(body, "user");
        String email = text(in, "email"), password = text(in, "password");
        var v = new ApiException.Validation();
        if (blank(email)) v.add("email", "can't be blank");
        if (blank(password)) v.add("password", "can't be blank");
        v.throwIfAny();
        Optional<User> user = tx.read(() -> users.findByEmail(email));
        if (user.isEmpty() || !security.checkPassword(password, user.get().passwordHash)) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "credentials", "invalid");
        }
        return userJson(user.get());
    }

    @GetMapping("/user")
    public Map<String, Object> current(@RequestHeader(value = "Authorization", required = false) String auth) {
        return userJson(tx.read(() -> security.requireUser(auth)));
    }

    @PutMapping("/user")
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody(required = false) JsonNode body) {
        User me = tx.read(() -> security.requireUser(auth));
        JsonNode in = section(body, "user");

        var v = new ApiException.Validation();
        for (String f : new String[] {"username", "email"}) {
            if (in.has(f) && blank(text(in, f))) v.add(f, "can't be blank");
        }
        if (in.has("password")) {
            String p = text(in, "password");
            if (blank(p)) v.add("password", "can't be blank");
            else if (p.length() < MIN_PASSWORD) v.add("password", "is too short (minimum is 8 characters)");
        }
        v.throwIfAny();

        return userJson(tx.write(() -> {
            User u = users.findById(me.id).orElseThrow(() -> ApiException.unauthorized("is invalid"));
            if (in.has("username") && !text(in, "username").equals(u.username)) {
                if (users.existsByUsername(text(in, "username"))) {
                    throw ApiException.of(HttpStatus.CONFLICT, "username", "has already been taken");
                }
                u.username = text(in, "username");
            }
            if (in.has("email") && !text(in, "email").equals(u.email)) {
                if (users.existsByEmail(text(in, "email"))) {
                    throw ApiException.of(HttpStatus.CONFLICT, "email", "has already been taken");
                }
                u.email = text(in, "email");
            }
            if (in.has("password")) u.passwordHash = security.hashPassword(text(in, "password"));
            if (in.has("bio")) u.bio = blank(text(in, "bio")) ? null : text(in, "bio");
            if (in.has("image")) u.image = blank(text(in, "image")) ? null : text(in, "image");
            return users.save(u);
        }));
    }

    @GetMapping("/profiles/{username}")
    public Map<String, Object> getProfile(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @PathVariable String username) {
        return tx.read(() -> {
            Optional<User> me = security.optionalUser(auth);
            User target = users.findByUsername(username).orElseThrow(() -> ApiException.notFound("profile"));
            boolean following = me.isPresent() && follows.existsByFollowerIdAndFolloweeId(me.get().id, target.id);
            return Map.of("profile", profile(target, following));
        });
    }

    @PostMapping("/profiles/{username}/follow")
    public Map<String, Object> follow(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @PathVariable String username) {
        User me = tx.read(() -> security.requireUser(auth));
        return tx.write(() -> {
            User target = users.findByUsername(username).orElseThrow(() -> ApiException.notFound("profile"));
            if (!follows.existsByFollowerIdAndFolloweeId(me.id, target.id)) {
                Follow f = new Follow();
                f.followerId = me.id;
                f.followeeId = target.id;
                follows.save(f);
            }
            return Map.of("profile", profile(target, true));
        });
    }

    @DeleteMapping("/profiles/{username}/follow")
    public Map<String, Object> unfollow(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @PathVariable String username) {
        User me = tx.read(() -> security.requireUser(auth));
        return tx.write(() -> {
            User target = users.findByUsername(username).orElseThrow(() -> ApiException.notFound("profile"));
            follows.deleteByFollowerIdAndFolloweeId(me.id, target.id);
            return Map.of("profile", profile(target, false));
        });
    }
}
