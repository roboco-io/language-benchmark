package io.realworld.user;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.realworld.common.Auth;

@RestController
@RequestMapping("/api")
public class UserController {

    private static final String AUTHORIZATION = "Authorization";

    private final UserService userService;
    private final ProfileService profileService;
    private final Auth auth;

    public UserController(UserService userService, ProfileService profileService, Auth auth) {
        this.userService = userService;
        this.profileService = profileService;
        this.auth = auth;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UserView> register(@RequestBody(required = false) JsonNode body) {
        return Map.of("user", userService.register(body));
    }

    @PostMapping("/users/login")
    public Map<String, UserView> login(@RequestBody(required = false) JsonNode body) {
        return Map.of("user", userService.login(body));
    }

    @GetMapping("/user")
    public Map<String, UserView> current(@RequestHeader(value = AUTHORIZATION, required = false) String header) {
        return Map.of("user", userService.current(auth.require(header)));
    }

    @PutMapping("/user")
    public Map<String, UserView> update(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                                        @RequestBody(required = false) JsonNode body) {
        long userId = auth.require(header);
        return Map.of("user", userService.update(userId, body));
    }

    @GetMapping("/profiles/{username}")
    public Map<String, ProfileView> profile(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                                            @PathVariable String username) {
        Optional<Long> viewer = auth.optional(header);
        return Map.of("profile", profileService.get(username, viewer));
    }

    @PostMapping("/profiles/{username}/follow")
    public Map<String, ProfileView> follow(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                                           @PathVariable String username) {
        return Map.of("profile", profileService.follow(username, auth.require(header)));
    }

    @DeleteMapping("/profiles/{username}/follow")
    public Map<String, ProfileView> unfollow(@RequestHeader(value = AUTHORIZATION, required = false) String header,
                                             @PathVariable String username) {
        return Map.of("profile", profileService.unfollow(username, auth.require(header)));
    }
}
