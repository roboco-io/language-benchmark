package io.realworld.api.web;

import io.realworld.api.domain.Article;
import io.realworld.api.domain.Comment;
import io.realworld.api.domain.Tag;
import io.realworld.api.domain.User;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the JSON response shapes defined by the RealWorld spec. */
public final class Views {
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Views() {}

    public static String time(long epochMillis) {
        return ISO.format(Instant.ofEpochMilli(epochMillis));
    }

    public static Map<String, Object> user(User u, String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.getEmail());
        m.put("token", token);
        m.put("username", u.getUsername());
        m.put("bio", u.getBio());
        m.put("image", u.getImage());
        return m;
    }

    public static Map<String, Object> profile(User u, User viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        m.put("bio", u.getBio());
        m.put("image", u.getImage());
        m.put("following", viewer != null && viewer.isFollowing(u));
        return m;
    }

    public static Map<String, Object> article(Article a, User viewer, boolean includeBody) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", a.getSlug());
        m.put("title", a.getTitle());
        m.put("description", a.getDescription());
        if (includeBody) m.put("body", a.getBody());
        m.put("tagList", a.getTags().stream().map(Tag::getName).toList());
        m.put("createdAt", time(a.getCreatedAt()));
        m.put("updatedAt", time(a.getUpdatedAt()));
        m.put("favorited", a.isFavoritedBy(viewer));
        m.put("favoritesCount", a.getFavoritedBy().size());
        m.put("author", profile(a.getAuthor(), viewer));
        return m;
    }

    public static Map<String, Object> comment(Comment c, User viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("createdAt", time(c.getCreatedAt()));
        m.put("updatedAt", time(c.getUpdatedAt()));
        m.put("body", c.getBody());
        m.put("author", profile(c.getAuthor(), viewer));
        return m;
    }
}
