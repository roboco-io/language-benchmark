package com.example.realworld;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Transactional
public class ArticleController {
    private final ArticleRepository articles;
    private final CommentRepository comments;
    private final UserRepository users;
    private final FollowRepository follows;
    private final FavoriteRepository favorites;
    private final Security security;
    private final EntityManager em;

    ArticleController(ArticleRepository articles, CommentRepository comments, UserRepository users,
                      FollowRepository follows, FavoriteRepository favorites, Security security,
                      EntityManager em) {
        this.articles = articles;
        this.comments = comments;
        this.users = users;
        this.follows = follows;
        this.favorites = favorites;
        this.security = security;
        this.em = em;
    }

    // ---- JSON views -------------------------------------------------------------------------

    private static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    private Map<String, Object> articleView(Article a, User viewer, boolean withBody) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", a.slug);
        m.put("title", a.title);
        m.put("description", a.description);
        if (withBody) {
            m.put("body", a.body);
        }
        m.put("tagList", List.copyOf(a.tags));
        m.put("createdAt", iso(a.createdAt));
        m.put("updatedAt", iso(a.updatedAt));
        m.put("favorited", viewer != null && favorites.existsByUserIdAndArticleId(viewer.id, a.id));
        m.put("favoritesCount", favorites.countByArticleId(a.id));
        boolean following = viewer != null && follows.existsByFollowerIdAndFollowedId(viewer.id, a.author.id);
        m.put("author", ProfileController.profile(a.author, following));
        return m;
    }

    private Map<String, Object> commentView(Comment c, User viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("createdAt", iso(c.createdAt));
        m.put("updatedAt", iso(c.updatedAt));
        m.put("body", c.body);
        boolean following = viewer != null && follows.existsByFollowerIdAndFollowedId(viewer.id, c.author.id);
        m.put("author", ProfileController.profile(c.author, following));
        return m;
    }

    private static ApiException notFound(String field) {
        return new ApiException(HttpStatus.NOT_FOUND, field, "not found");
    }

    private Article findArticle(String slug) {
        return articles.findBySlug(slug).orElseThrow(() -> notFound("article"));
    }

    // ---- Listing and feed -------------------------------------------------------------------

    private Map<String, Object> list(String where, Map<String, Object> params, int limit, int offset,
                                     User viewer) {
        TypedQuery<Long> count = em.createQuery("select count(a) from Article a " + where, Long.class);
        TypedQuery<Article> select = em.createQuery(
                "select a from Article a " + where + " order by a.id desc", Article.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            select.setParameter(k, v);
        });
        select.setFirstResult(Math.max(offset, 0));
        select.setMaxResults(Math.max(limit, 0));
        List<Map<String, Object>> views = new ArrayList<>();
        for (Article a : select.getResultList()) {
            views.add(articleView(a, viewer, false));
        }
        return Map.of("articles", views, "articlesCount", count.getSingleResult());
    }

    @GetMapping("/articles")
    public Map<String, Object> listArticles(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String favorited,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        User viewer = security.optionalUser(auth);
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (tag != null && !tag.isEmpty()) {
            conditions.add(":tag member of a.tags");
            params.put("tag", tag);
        }
        if (author != null && !author.isEmpty()) {
            conditions.add("a.author.username = :author");
            params.put("author", author);
        }
        if (favorited != null && !favorited.isEmpty()) {
            conditions.add("exists (select 1 from Favorite f, User u where f.articleId = a.id "
                    + "and f.userId = u.id and u.username = :favorited)");
            params.put("favorited", favorited);
        }
        String where = conditions.isEmpty() ? "" : "where " + String.join(" and ", conditions);
        return list(where, params, limit, offset, viewer);
    }

    // Declared before /articles/{slug}; Spring also prefers the literal path match.
    @GetMapping("/articles/feed")
    public Map<String, Object> feed(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        User viewer = security.requiredUser(auth);
        return list("where a.author.id in (select f.followedId from Follow f where f.followerId = :me)",
                Map.of("me", viewer.id), limit, offset, viewer);
    }

    @GetMapping("/tags")
    public Map<String, Object> tags() {
        return Map.of("tags", articles.findAllTags());
    }

    // ---- Article CRUD -----------------------------------------------------------------------

    @GetMapping("/articles/{slug}")
    public Map<String, Object> get(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable String slug) {
        User viewer = security.optionalUser(auth);
        return Map.of("article", articleView(findArticle(slug), viewer, true));
    }

    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody JsonNode body) {
        User viewer = security.requiredUser(auth);
        JsonNode in = Json.wrapped(body, "article");
        var errors = new Json.Errors();
        errors.requireNotBlank(in, "title");
        errors.requireNotBlank(in, "description");
        errors.requireNotBlank(in, "body");
        List<String> tags = readTags(in, errors);
        errors.throwIfAny();

        Article a = new Article();
        a.title = in.get("title").asText();
        a.description = in.get("description").asText();
        a.body = in.get("body").asText();
        a.author = viewer;
        a.slug = uniqueSlug(a.title);
        a.tags = tags == null ? new ArrayList<>() : tags;
        a.createdAt = a.updatedAt = System.currentTimeMillis();
        articles.save(a);
        return Map.of("article", articleView(a, viewer, true));
    }

    @PutMapping("/articles/{slug}")
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @PathVariable String slug, @RequestBody JsonNode body) {
        User viewer = security.requiredUser(auth);
        Article a = findArticle(slug);
        if (!a.author.id.equals(viewer.id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "article", "forbidden");
        }
        JsonNode in = Json.wrapped(body, "article");
        var errors = new Json.Errors();
        for (String field : List.of("title", "description", "body")) {
            if (in.has(field) && Json.blank(in, field)) {
                errors.add(field, "can't be blank");
            }
        }
        List<String> tags = in.has("tagList") ? readTags(in, errors) : null;
        errors.throwIfAny();

        if (in.has("title")) {
            a.title = in.get("title").asText();
        }
        if (in.has("description")) {
            a.description = in.get("description").asText();
        }
        if (in.has("body")) {
            a.body = in.get("body").asText();
        }
        if (tags != null) {
            a.tags.clear();
            a.tags.addAll(tags);
        }
        // Guarantee a strictly newer timestamp so clients can observe the change.
        a.updatedAt = Math.max(System.currentTimeMillis(), a.updatedAt + 1);
        articles.save(a);
        return Map.of("article", articleView(a, viewer, true));
    }

    @DeleteMapping("/articles/{slug}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @PathVariable String slug) {
        User viewer = security.requiredUser(auth);
        Article a = findArticle(slug);
        if (!a.author.id.equals(viewer.id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "article", "forbidden");
        }
        comments.deleteByArticleId(a.id);
        favorites.deleteByArticleId(a.id);
        articles.delete(a);
        return ResponseEntity.noContent().build();
    }

    /** Reads body.tagList. Returns null (and records an error) when it is present but not an array. */
    private static List<String> readTags(JsonNode in, Json.Errors errors) {
        JsonNode node = in.get("tagList");
        if (node == null) {
            return null;
        }
        if (!node.isArray()) {
            errors.add("tagList", "is invalid");
            return null;
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode t : node) {
            if (!t.isTextual() || t.asText().isBlank()) {
                errors.add("tagList", "is invalid");
                return null;
            }
            if (!tags.contains(t.asText())) {
                tags.add(t.asText());
            }
        }
        return tags;
    }

    private String uniqueSlug(String title) {
        String base = Normalizer.normalize(title, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "article";
        }
        String slug = base;
        while (articles.findBySlug(slug).isPresent()) {
            slug = base + "-" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 32), 36);
        }
        return slug;
    }

    // ---- Favorites --------------------------------------------------------------------------

    @PostMapping("/articles/{slug}/favorite")
    public Map<String, Object> favorite(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @PathVariable String slug) {
        User viewer = security.requiredUser(auth);
        Article a = findArticle(slug);
        if (!favorites.existsByUserIdAndArticleId(viewer.id, a.id)) {
            Favorite f = new Favorite();
            f.userId = viewer.id;
            f.articleId = a.id;
            favorites.save(f);
        }
        return Map.of("article", articleView(a, viewer, true));
    }

    @DeleteMapping("/articles/{slug}/favorite")
    public Map<String, Object> unfavorite(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @PathVariable String slug) {
        User viewer = security.requiredUser(auth);
        Article a = findArticle(slug);
        favorites.deleteByUserIdAndArticleId(viewer.id, a.id);
        return Map.of("article", articleView(a, viewer, true));
    }

    // ---- Comments ---------------------------------------------------------------------------

    @GetMapping("/articles/{slug}/comments")
    public Map<String, Object> listComments(
            @RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String slug) {
        User viewer = security.optionalUser(auth);
        Article a = findArticle(slug);
        List<Map<String, Object>> views = new ArrayList<>();
        for (Comment c : comments.findByArticleIdOrderByIdDesc(a.id)) {
            views.add(commentView(c, viewer));
        }
        return Map.of("comments", views);
    }

    @PostMapping("/articles/{slug}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addComment(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @PathVariable String slug, @RequestBody JsonNode body) {
        User viewer = security.requiredUser(auth);
        Article a = findArticle(slug);
        JsonNode in = Json.wrapped(body, "comment");
        var errors = new Json.Errors();
        errors.requireNotBlank(in, "body");
        errors.throwIfAny();

        Comment c = new Comment();
        c.articleId = a.id;
        c.author = viewer;
        c.body = in.get("body").asText();
        c.createdAt = c.updatedAt = System.currentTimeMillis();
        comments.save(c);
        return Map.of("comment", commentView(c, viewer));
    }

    @PutMapping("/articles/{slug}/comments/{id}")
    public Map<String, Object> updateComment(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String slug, @PathVariable long id, @RequestBody JsonNode body) {
        User viewer = security.requiredUser(auth);
        Article a = findArticle(slug);
        Comment c = comments.findById(id).filter(x -> x.articleId.equals(a.id))
                .orElseThrow(() -> notFound("comment"));
        if (!c.author.id.equals(viewer.id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "comment", "forbidden");
        }
        JsonNode in = Json.wrapped(body, "comment");
        var errors = new Json.Errors();
        errors.requireNotBlank(in, "body");
        errors.throwIfAny();

        c.body = in.get("body").asText();
        c.updatedAt = Math.max(System.currentTimeMillis(), c.createdAt + 1);
        comments.save(c);
        return Map.of("comment", commentView(c, viewer));
    }

    @DeleteMapping("/articles/{slug}/comments/{id}")
    public ResponseEntity<Void> deleteComment(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String slug, @PathVariable long id) {
        User viewer = security.requiredUser(auth);
        Article a = findArticle(slug);
        Comment c = comments.findById(id).filter(x -> x.articleId.equals(a.id))
                .orElseThrow(() -> notFound("comment"));
        if (!c.author.id.equals(viewer.id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "comment", "forbidden");
        }
        comments.delete(c);
        return ResponseEntity.noContent().build();
    }
}
