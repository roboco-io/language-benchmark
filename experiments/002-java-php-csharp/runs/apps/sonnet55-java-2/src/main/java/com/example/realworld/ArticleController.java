package com.example.realworld;

import com.example.realworld.Entities.*;
import com.example.realworld.Repositories.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ArticleController {
    private static final int DEFAULT_LIMIT = 20;

    private final ArticleRepo articles;
    private final CommentRepo comments;
    private final FavoriteRepo favorites;
    private final FollowRepo follows;
    private final UserRepo users;
    private final Security security;
    private final Tx tx;
    private final EntityManager em;

    public ArticleController(ArticleRepo articles, CommentRepo comments, FavoriteRepo favorites, FollowRepo follows,
                             UserRepo users, Security security, Tx tx, EntityManager em) {
        this.articles = articles;
        this.comments = comments;
        this.favorites = favorites;
        this.follows = follows;
        this.users = users;
        this.security = security;
        this.tx = tx;
        this.em = em;
    }

    // ---------- serialization ----------

    private static String ts(long millis) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis));
    }

    /** Builds article JSON for a batch of articles, loading favorites/follows with one query each. */
    private List<Map<String, Object>> render(List<Article> list, User viewer, boolean withBody) {
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(a -> a.id).toList();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : favorites.countsFor(ids)) counts.put((Long) row[0], (Long) row[1]);
        Set<Long> favorited = viewer == null ? Set.of() : new HashSet<>(favorites.favoritedAmong(viewer.id, ids));
        Set<Long> following = viewer == null ? Set.of()
                : new HashSet<>(follows.followedAmong(viewer.id, list.stream().map(a -> a.author.id).toList()));
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Article a : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", a.slug);
            m.put("title", a.title);
            m.put("description", a.description);
            if (withBody) m.put("body", a.body);
            m.put("tagList", new ArrayList<>(a.tagList));
            m.put("createdAt", ts(a.createdAt));
            m.put("updatedAt", ts(a.updatedAt));
            m.put("favorited", favorited.contains(a.id));
            m.put("favoritesCount", counts.getOrDefault(a.id, 0L));
            m.put("author", UserController.profile(a.author, following.contains(a.author.id)));
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> renderOne(Article a, User viewer) {
        return Map.of("article", render(List.of(a), viewer, true).get(0));
    }

    private Map<String, Object> commentJson(Comment c, boolean following) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("createdAt", ts(c.createdAt));
        m.put("updatedAt", ts(c.updatedAt));
        m.put("body", c.body);
        m.put("author", UserController.profile(c.author, following));
        return m;
    }

    // ---------- helpers ----------

    private Article bySlug(String slug) {
        return articles.findBySlug(slug).orElseThrow(() -> ApiException.notFound("article"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static JsonNode section(JsonNode body, String name) {
        JsonNode n = body == null ? null : body.get(name);
        if (n == null || !n.isObject()) throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, name, "can't be blank");
        return n;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String slugify(String title) {
        String base = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "article";
        return base + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** Parses tagList: must be an array of strings; duplicates are dropped, order is preserved. */
    private static List<String> parseTags(JsonNode node) {
        if (!node.isArray()) throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "tagList", "is invalid");
        Set<String> tags = new LinkedHashSet<>();
        for (JsonNode t : node) {
            if (!t.isTextual() || t.asText().isBlank()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "tagList", "is invalid");
            }
            tags.add(t.asText());
        }
        return new ArrayList<>(tags);
    }

    private static int intParam(String raw, int def, int min) {
        if (raw == null) return def;
        try {
            return Math.max(min, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------- articles ----------

    @GetMapping("/articles")
    public Map<String, Object> list(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestParam(required = false) String tag,
                                    @RequestParam(required = false) String author,
                                    @RequestParam(required = false) String favorited,
                                    @RequestParam(required = false) String limit,
                                    @RequestParam(required = false) String offset) {
        return tx.read(() -> {
            User viewer = security.optionalUser(auth).orElse(null);
            StringBuilder where = new StringBuilder(" where 1=1");
            Map<String, Object> params = new HashMap<>();
            if (!blank(tag)) {
                where.append(" and :tag member of a.tagList");
                params.put("tag", tag);
            }
            if (!blank(author)) {
                where.append(" and a.author.username = :author");
                params.put("author", author);
            }
            if (!blank(favorited)) {
                where.append(" and exists (select 1 from Favorite f, User fu where f.articleId = a.id"
                        + " and f.userId = fu.id and fu.username = :favorited)");
                params.put("favorited", favorited);
            }
            return page(where.toString(), params, viewer, intParam(limit, DEFAULT_LIMIT, 0), intParam(offset, 0, 0));
        });
    }

    @GetMapping("/articles/feed")
    public Map<String, Object> feed(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestParam(required = false) String limit,
                                    @RequestParam(required = false) String offset) {
        return tx.read(() -> {
            User me = security.requireUser(auth);
            return page(" where a.author.id in (select f.followeeId from Follow f where f.followerId = :uid)",
                    Map.of("uid", me.id), me, intParam(limit, DEFAULT_LIMIT, 0), intParam(offset, 0, 0));
        });
    }

    private Map<String, Object> page(String where, Map<String, Object> params, User viewer, int limit, int offset) {
        TypedQuery<Long> count = em.createQuery("select count(a) from Article a" + where, Long.class);
        TypedQuery<Article> select = em.createQuery("select a from Article a join fetch a.author" + where
                + " order by a.createdAt desc, a.id desc", Article.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            select.setParameter(k, v);
        });
        long total = count.getSingleResult();
        List<Article> rows = select.setFirstResult(offset).setMaxResults(limit).getResultList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("articles", render(rows, viewer, false));
        out.put("articlesCount", total);
        return out;
    }

    @GetMapping("/articles/{slug}")
    public Map<String, Object> get(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable String slug) {
        return tx.read(() -> renderOne(bySlug(slug), security.optionalUser(auth).orElse(null)));
    }

    @PostMapping("/articles")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody(required = false) JsonNode body) {
        User me = tx.read(() -> security.requireUser(auth));
        JsonNode in = section(body, "article");
        String title = text(in, "title"), description = text(in, "description"), content = text(in, "body");
        var v = new ApiException.Validation();
        if (blank(title)) v.add("title", "can't be blank");
        if (blank(description)) v.add("description", "can't be blank");
        if (blank(content)) v.add("body", "can't be blank");
        v.throwIfAny();
        List<String> tags = in.hasNonNull("tagList") ? parseTags(in.get("tagList")) : List.of();

        Map<String, Object> result = tx.write(() -> {
            Article a = new Article();
            a.slug = slugify(title);
            a.title = title;
            a.description = description;
            a.body = content;
            a.author = users.findById(me.id).orElseThrow(() -> ApiException.unauthorized("is invalid"));
            a.createdAt = a.updatedAt = System.currentTimeMillis();
            a.tagList = new ArrayList<>(tags);
            articles.save(a);
            return renderOne(a, me);
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PutMapping("/articles/{slug}")
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @PathVariable String slug, @RequestBody(required = false) JsonNode body) {
        User me = tx.read(() -> security.requireUser(auth));
        return tx.write(() -> {
            Article a = bySlug(slug);
            if (!a.author.id.equals(me.id)) throw ApiException.forbidden("article");
            JsonNode in = section(body, "article");
            var v = new ApiException.Validation();
            for (String f : new String[] {"title", "description", "body"}) {
                if (in.has(f) && blank(text(in, f))) v.add(f, "can't be blank");
            }
            if (in.has("tagList") && !in.get("tagList").isArray()) v.add("tagList", "is invalid");
            v.throwIfAny();
            List<String> tags = in.has("tagList") ? parseTags(in.get("tagList")) : null;

            if (in.has("title")) a.title = text(in, "title");
            if (in.has("description")) a.description = text(in, "description");
            if (in.has("body")) a.body = text(in, "body");
            if (tags != null) {
                a.tagList.clear();
                a.tagList.addAll(tags);
            }
            a.updatedAt = Math.max(System.currentTimeMillis(), a.updatedAt + 1);
            articles.save(a);
            return renderOne(a, me);
        });
    }

    @DeleteMapping("/articles/{slug}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @PathVariable String slug) {
        User me = tx.read(() -> security.requireUser(auth));
        tx.write(() -> {
            Article a = bySlug(slug);
            if (!a.author.id.equals(me.id)) throw ApiException.forbidden("article");
            comments.deleteByArticle(a.id);
            favorites.deleteByArticle(a.id);
            articles.delete(a);
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    // ---------- favorites ----------

    @PostMapping("/articles/{slug}/favorite")
    public Map<String, Object> favorite(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @PathVariable String slug) {
        User me = tx.read(() -> security.requireUser(auth));
        return tx.write(() -> {
            Article a = bySlug(slug);
            if (!favorites.existsByUserIdAndArticleId(me.id, a.id)) {
                Favorite f = new Favorite();
                f.userId = me.id;
                f.articleId = a.id;
                favorites.save(f);
            }
            return renderOne(a, me);
        });
    }

    @DeleteMapping("/articles/{slug}/favorite")
    public Map<String, Object> unfavorite(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @PathVariable String slug) {
        User me = tx.read(() -> security.requireUser(auth));
        return tx.write(() -> {
            Article a = bySlug(slug);
            favorites.deleteByUserIdAndArticleId(me.id, a.id);
            em.flush();
            return renderOne(a, me);
        });
    }

    // ---------- comments ----------

    @GetMapping("/articles/{slug}/comments")
    public Map<String, Object> listComments(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @PathVariable String slug) {
        return tx.read(() -> {
            User viewer = security.optionalUser(auth).orElse(null);
            Article a = bySlug(slug);
            List<Comment> list = comments.findByArticleIdOrderByIdAsc(a.id);
            Set<Long> following = viewer == null || list.isEmpty() ? Set.of()
                    : new HashSet<>(follows.followedAmong(viewer.id, list.stream().map(c -> c.author.id).toList()));
            return Map.of("comments", list.stream()
                    .map(c -> commentJson(c, following.contains(c.author.id))).collect(Collectors.toList()));
        });
    }

    @PostMapping("/articles/{slug}/comments")
    public ResponseEntity<Map<String, Object>> addComment(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String slug, @RequestBody(required = false) JsonNode body) {
        User me = tx.read(() -> security.requireUser(auth));
        return tx.write(() -> {
            Article a = bySlug(slug);
            JsonNode in = section(body, "comment");
            String text = text(in, "body");
            if (blank(text)) throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "body", "can't be blank");
            Comment c = new Comment();
            c.body = text;
            c.article = a;
            c.author = me;
            c.createdAt = c.updatedAt = System.currentTimeMillis();
            comments.save(c);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("comment", commentJson(c, false)));
        });
    }

    @DeleteMapping("/articles/{slug}/comments/{id}")
    public ResponseEntity<Void> deleteComment(@RequestHeader(value = "Authorization", required = false) String auth,
                                              @PathVariable String slug, @PathVariable String id) {
        User me = tx.read(() -> security.requireUser(auth));
        tx.write(() -> {
            Article a = bySlug(slug);
            Long cid = parseId(id);
            Comment c = comments.findById(cid).filter(x -> x.article.id.equals(a.id))
                    .orElseThrow(() -> ApiException.notFound("comment"));
            if (!c.author.id.equals(me.id)) throw ApiException.forbidden("comment");
            comments.delete(c);
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    private static Long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ApiException.notFound("comment");
        }
    }

    // ---------- tags ----------

    @GetMapping("/tags")
    public Map<String, Object> tags() {
        return tx.read(() -> Map.of("tags", articles.allTags()));
    }
}
