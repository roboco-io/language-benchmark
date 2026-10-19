package io.realworld.article;

import io.realworld.user.ProfileView;

public record CommentView(long id, String createdAt, String updatedAt, String body, ProfileView author) {
}
