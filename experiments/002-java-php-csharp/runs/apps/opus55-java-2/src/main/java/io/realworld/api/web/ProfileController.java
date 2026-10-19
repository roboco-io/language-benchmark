package io.realworld.api.web;

import io.realworld.api.domain.User;
import io.realworld.api.domain.UserRepository;
import io.realworld.api.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profiles/{username}")
public class ProfileController {
    private final UserRepository users;
    private final Auth auth;

    public ProfileController(UserRepository users, Auth auth) {
        this.users = users;
        this.auth = auth;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> get(HttpServletRequest request, @PathVariable String username) {
        User viewer = auth.optional(request).orElse(null);
        return Map.of("profile", Views.profile(find(username), viewer));
    }

    @PostMapping("/follow")
    @Transactional
    public Map<String, Object> follow(HttpServletRequest request, @PathVariable String username) {
        User viewer = auth.require(request);
        User target = find(username);
        if (!target.getId().equals(viewer.getId()) && !viewer.isFollowing(target)) {
            viewer.getFollowing().add(target);
        }
        return Map.of("profile", Views.profile(target, viewer));
    }

    @DeleteMapping("/follow")
    @Transactional
    public Map<String, Object> unfollow(HttpServletRequest request, @PathVariable String username) {
        User viewer = auth.require(request);
        User target = find(username);
        viewer.getFollowing().removeIf(u -> u.getId().equals(target.getId()));
        return Map.of("profile", Views.profile(target, viewer));
    }

    private User find(String username) {
        return users.findByUsername(username).orElseThrow(() -> ApiException.notFound("profile"));
    }
}
