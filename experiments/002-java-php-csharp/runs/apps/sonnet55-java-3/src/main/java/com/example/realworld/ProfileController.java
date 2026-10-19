package com.example.realworld;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profiles")
@Transactional
public class ProfileController {
    private final UserRepository users;
    private final FollowRepository follows;
    private final Security security;

    ProfileController(UserRepository users, FollowRepository follows, Security security) {
        this.users = users;
        this.follows = follows;
        this.security = security;
    }

    static Map<String, Object> profile(User u, boolean following) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.username);
        m.put("bio", u.bio);
        m.put("image", u.image);
        m.put("following", following);
        return m;
    }

    private User find(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "profile", "not found"));
    }

    @GetMapping("/{username}")
    public Map<String, Object> get(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable String username) {
        User viewer = security.optionalUser(auth);
        User target = find(username);
        boolean following = viewer != null && follows.existsByFollowerIdAndFollowedId(viewer.id, target.id);
        return Map.of("profile", profile(target, following));
    }

    @PostMapping("/{username}/follow")
    public Map<String, Object> follow(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @PathVariable String username) {
        User viewer = security.requiredUser(auth);
        User target = find(username);
        if (!follows.existsByFollowerIdAndFollowedId(viewer.id, target.id)) {
            Follow f = new Follow();
            f.followerId = viewer.id;
            f.followedId = target.id;
            follows.save(f);
        }
        return Map.of("profile", profile(target, true));
    }

    @DeleteMapping("/{username}/follow")
    public Map<String, Object> unfollow(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @PathVariable String username) {
        User viewer = security.requiredUser(auth);
        User target = find(username);
        follows.deleteByFollowerIdAndFollowedId(viewer.id, target.id);
        return Map.of("profile", profile(target, false));
    }
}
