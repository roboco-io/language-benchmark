package io.realworld.user;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.realworld.common.ApiException;

@Service
public class ProfileService {

    private final UserRepository users;
    private final UserService userService;

    public ProfileService(UserRepository users, UserService userService) {
        this.users = users;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public ProfileView get(String username, Optional<Long> viewerId) {
        User target = find(username);
        boolean following = viewerId.isPresent()
                && !users.followedAmong(viewerId.get(), List.of(target.getId())).isEmpty();
        return ProfileView.of(target, following);
    }

    @Transactional
    public ProfileView follow(String username, long viewerId) {
        User viewer = userService.load(viewerId);
        User target = find(username);
        viewer.getFollowing().add(target);
        return ProfileView.of(target, true);
    }

    @Transactional
    public ProfileView unfollow(String username, long viewerId) {
        User viewer = userService.load(viewerId);
        User target = find(username);
        viewer.getFollowing().remove(target);
        return ProfileView.of(target, false);
    }

    private User find(String username) {
        return users.findByUsername(username).orElseThrow(() -> ApiException.notFound("profile"));
    }
}
