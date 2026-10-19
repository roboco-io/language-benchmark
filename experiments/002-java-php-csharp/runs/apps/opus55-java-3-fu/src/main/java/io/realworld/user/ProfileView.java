package io.realworld.user;

/** A public profile, as seen by the (optional) current user. */
public record ProfileView(String username, String bio, String image, boolean following) {

    public static ProfileView of(User user, boolean following) {
        return new ProfileView(user.getUsername(), user.getBio(), user.getImage(), following);
    }
}
