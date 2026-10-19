package io.realworld.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.realworld.auth.CurrentUser;
import io.realworld.web.Dtos.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService service;
    private final CurrentUser currentUser;

    public UserController(UserService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@RequestBody(required = false) JsonNode body) {
        return service.register(body);
    }

    @PostMapping("/users/login")
    public UserResponse login(@RequestBody(required = false) JsonNode body) {
        return service.login(body);
    }

    @GetMapping("/user")
    public UserResponse current(HttpServletRequest request) {
        return service.current(currentUser.require(request));
    }

    @PutMapping("/user")
    public UserResponse update(HttpServletRequest request, @RequestBody(required = false) JsonNode body) {
        return service.update(currentUser.require(request), body);
    }

    @GetMapping("/profiles/{username}")
    public ProfileResponse profile(@PathVariable String username, HttpServletRequest request) {
        return service.profile(username, currentUser.optional(request));
    }

    @PostMapping("/profiles/{username}/follow")
    public ProfileResponse follow(@PathVariable String username, HttpServletRequest request) {
        return service.follow(username, currentUser.require(request), true);
    }

    @DeleteMapping("/profiles/{username}/follow")
    public ProfileResponse unfollow(@PathVariable String username, HttpServletRequest request) {
        return service.follow(username, currentUser.require(request), false);
    }
}
