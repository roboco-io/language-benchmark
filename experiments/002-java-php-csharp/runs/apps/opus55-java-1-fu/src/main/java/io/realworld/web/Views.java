package io.realworld.web;

import io.realworld.auth.JwtService;
import io.realworld.domain.Article;
import io.realworld.domain.Comment;
import io.realworld.domain.Tag;
import io.realworld.domain.User;
import io.realworld.web.Dtos.*;
import org.springframework.stereotype.Component;

/** Maps entities to response DTOs. Must be called inside a transaction (lazy associations). */
@Component
class Views {

    private final JwtService jwt;

    Views(JwtService jwt) {
        this.jwt = jwt;
    }

    UserDto user(User user) {
        return new UserDto(user.getEmail(), jwt.issue(user.getId()), user.getUsername(), user.getBio(), user.getImage());
    }

    ProfileDto profile(User user, User viewer) {
        boolean following = viewer != null && viewer.getFollowing().contains(user);
        return new ProfileDto(user.getUsername(), user.getBio(), user.getImage(), following);
    }

    ArticleDto article(Article a, User viewer, boolean includeBody) {
        return new ArticleDto(
                a.getSlug(),
                a.getTitle(),
                a.getDescription(),
                includeBody ? a.getBody() : null,
                a.getTags().stream().map(Tag::getName).toList(),
                Json.timestamp(a.getCreatedAt()),
                Json.timestamp(a.getUpdatedAt()),
                viewer != null && a.getFavoritedBy().contains(viewer),
                a.getFavoritedBy().size(),
                profile(a.getAuthor(), viewer));
    }

    CommentDto comment(Comment c, User viewer) {
        return new CommentDto(c.getId(), Json.timestamp(c.getCreatedAt()), Json.timestamp(c.getUpdatedAt()),
                c.getBody(), profile(c.getAuthor(), viewer));
    }
}
