package com.realworld;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Transactional
public class UserController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");
    private static final int MIN_PASSWORD = 8;

    private final UserRepository users;
    private final Security security;

    public UserController(UserRepository users, Security security) {
        this.users = users;
        this.security = security;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody JsonNode body) {
        JsonNode in = Json.wrapper(body, "user");
        String username = Json.text(in, "username");
        String email = Json.text(in, "email");
        String password = Json.text(in, "password");

        Map<String, String> errors = new LinkedHashMap<>();
        if (Json.blank(username)) errors.put("username", "can't be blank");
        if (Json.blank(email)) errors.put("email", "can't be blank");
        else if (!EMAIL.matcher(email).matches()) errors.put("email", "is invalid");
        if (Json.blank(password)) errors.put("password", "can't be blank");
        else if (password.length() < MIN_PASSWORD) errors.put("password", "is too short (minimum is 8 characters)");
        if (!errors.isEmpty()) throw ApiException.unprocessable(errors);

        if (users.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "username", "has already been taken");
        }
        if (users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "email", "has already been taken");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(security.hashPassword(password));
        users.saveAndFlush(user);
        return Json.userView(user, security.issueToken(user));
    }

    @PostMapping("/users/login")
    public Map<String, Object> login(@RequestBody JsonNode body) {
        JsonNode in = Json.wrapper(body, "user");
        String email = Json.text(in, "email");
        String password = Json.text(in, "password");

        Map<String, String> errors = new LinkedHashMap<>();
        if (Json.blank(email)) errors.put("email", "can't be blank");
        if (Json.blank(password)) errors.put("password", "can't be blank");
        if (!errors.isEmpty()) throw ApiException.unprocessable(errors);

        User user = users.findByEmail(email).orElse(null);
        if (user == null || !security.matches(password, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "credentials", "invalid");
        }
        return Json.userView(user, security.issueToken(user));
    }

    @GetMapping("/user")
    public Map<String, Object> current(HttpServletRequest request) {
        User user = security.requiredUser(request);
        return Json.userView(user, security.issueToken(user));
    }

    @PutMapping("/user")
    public Map<String, Object> update(HttpServletRequest request, @RequestBody JsonNode body) {
        User user = security.requiredUser(request);
        JsonNode in = Json.wrapper(body, "user");

        Map<String, String> errors = new LinkedHashMap<>();
        if (in.has("username") && Json.blank(Json.text(in, "username"))) errors.put("username", "can't be blank");
        if (in.has("email")) {
            String email = Json.text(in, "email");
            if (Json.blank(email)) errors.put("email", "can't be blank");
            else if (!EMAIL.matcher(email).matches()) errors.put("email", "is invalid");
        }
        if (in.has("password")) {
            String password = Json.text(in, "password");
            if (Json.blank(password)) errors.put("password", "can't be blank");
            else if (password.length() < MIN_PASSWORD) errors.put("password", "is too short (minimum is 8 characters)");
        }
        if (!errors.isEmpty()) throw ApiException.unprocessable(errors);

        if (in.has("username") && !Json.text(in, "username").equals(user.getUsername())) {
            String username = Json.text(in, "username");
            if (users.existsByUsername(username)) {
                throw new ApiException(HttpStatus.CONFLICT, "username", "has already been taken");
            }
            user.setUsername(username);
        }
        if (in.has("email") && !Json.text(in, "email").equals(user.getEmail())) {
            String email = Json.text(in, "email");
            if (users.existsByEmail(email)) {
                throw new ApiException(HttpStatus.CONFLICT, "email", "has already been taken");
            }
            user.setEmail(email);
        }
        if (in.has("password")) user.setPasswordHash(security.hashPassword(Json.text(in, "password")));
        if (in.has("bio")) user.setBio(emptyToNull(Json.text(in, "bio")));
        if (in.has("image")) user.setImage(emptyToNull(Json.text(in, "image")));
        users.saveAndFlush(user);
        return Json.userView(user, security.issueToken(user));
    }

    @GetMapping("/profiles/{username}")
    public Map<String, Object> profile(HttpServletRequest request, @PathVariable String username) {
        User viewer = security.optionalUser(request);
        User target = findUser(username);
        return Map.of("profile", Json.profile(target, viewer != null && viewer.getFollowing().contains(target)));
    }

    @PostMapping("/profiles/{username}/follow")
    public Map<String, Object> follow(HttpServletRequest request, @PathVariable String username) {
        User viewer = security.requiredUser(request);
        User target = findUser(username);
        viewer.getFollowing().add(target);
        users.saveAndFlush(viewer);
        return Map.of("profile", Json.profile(target, true));
    }

    @DeleteMapping("/profiles/{username}/follow")
    public Map<String, Object> unfollow(HttpServletRequest request, @PathVariable String username) {
        User viewer = security.requiredUser(request);
        User target = findUser(username);
        viewer.getFollowing().remove(target);
        users.saveAndFlush(viewer);
        return Map.of("profile", Json.profile(target, false));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private User findUser(String username) {
        return users.findByUsername(username).orElseThrow(() -> ApiException.notFound("profile"));
    }
}
