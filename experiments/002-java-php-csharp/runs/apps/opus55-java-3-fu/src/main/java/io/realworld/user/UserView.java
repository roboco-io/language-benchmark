package io.realworld.user;

/** The authenticated user as returned by the /users and /user endpoints. */
public record UserView(String email, String token, String username, String bio, String image) {

    public static UserView of(User user, String token) {
        return new UserView(user.getEmail(), token, user.getUsername(), user.getBio(), user.getImage());
    }
}
