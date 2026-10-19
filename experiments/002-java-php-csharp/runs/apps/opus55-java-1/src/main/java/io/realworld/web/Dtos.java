package io.realworld.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Response payload shapes defined by the RealWorld API spec. */
public final class Dtos {

    private Dtos() {
    }

    public record UserDto(String email, String token, String username, String bio, String image) {}

    public record UserResponse(UserDto user) {}

    public record ProfileDto(String username, String bio, String image, boolean following) {}

    public record ProfileResponse(ProfileDto profile) {}

    /** {@code body} is omitted (null) in list responses, as required by the spec. */
    public record ArticleDto(
            String slug,
            String title,
            String description,
            @JsonInclude(JsonInclude.Include.NON_NULL) String body,
            List<String> tagList,
            String createdAt,
            String updatedAt,
            boolean favorited,
            int favoritesCount,
            ProfileDto author) {}

    public record ArticleResponse(ArticleDto article) {}

    public record ArticlesResponse(List<ArticleDto> articles, long articlesCount) {}

    public record CommentDto(long id, String createdAt, String updatedAt, String body, ProfileDto author) {}

    public record CommentResponse(CommentDto comment) {}

    public record CommentsResponse(List<CommentDto> comments) {}

    public record TagsResponse(List<String> tags) {}
}
