package io.realworld.article;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.realworld.user.ProfileView;

/** An article; {@code body} is null (and omitted) in list responses. */
public record ArticleView(
        String slug,
        String title,
        String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) String body,
        List<String> tagList,
        String createdAt,
        String updatedAt,
        boolean favorited,
        long favoritesCount,
        ProfileView author) {
}
