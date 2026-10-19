package com.realworld;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Transactional
public class ArticleController {

    private static final int DEFAULT_LIMIT = 20;

    private final ArticleRepository articles;
    private final CommentRepository comments;
    private final Security security;
    private final EntityManager em;

    public ArticleController(ArticleRepository articles, CommentRepository comments, Security security,
                             EntityManager em) {
        this.articles = articles;
        this.comments = comments;
        this.security = security;
        this.em = em;
    }

    @GetMapping("/articles")
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(required = false) String tag,
                                    @RequestParam(required = false) String author,
                                    @RequestParam(required = false) String favorited,
                                    @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        User viewer = security.optionalUser(request);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (!Json.blank(tag)) {
            where.append(" and :tag member of a.tagList");
            params.put("tag", tag);
        }
        if (!Json.blank(author)) {
            where.append(" and a.author.username = :author");
            params.put("author", author);
        }
        if (!Json.blank(favorited)) {
            where.append(" and exists (select 1 from a.favoritedBy f where f.username = :favorited)");
            params.put("favorited", favorited);
        }
        return page("from Article a" + where, params, limit, offset, viewer);
    }

    @GetMapping("/articles/feed")
    public Map<String, Object> feed(HttpServletRequest request,
                                    @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        User viewer = security.requiredUser(request);
        String from = "from Article a where a.author in (select f from User u join u.following f where u.id = :uid)";
        return page(from, Map.of("uid", viewer.getId()), limit, offset, viewer);
    }

    private Map<String, Object> page(String from, Map<String, Object> params, int limit, int offset, User viewer) {
        TypedQuery<Long> count = em.createQuery("select count(a) " + from, Long.class);
        TypedQuery<Article> select = em.createQuery("select a " + from + " order by a.createdAt desc, a.id desc",
                Article.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            select.setParameter(k, v);
        });
        select.setFirstResult(Math.max(offset, 0));
        select.setMaxResults(Math.max(limit, 0));
        List<Map<String, Object>> views = new ArrayList<>();
        for (Article a : select.getResultList()) {
            views.add(view(a, viewer, false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("articles", views);
        out.put("articlesCount", count.getSingleResult());
        return out;
    }

    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest request, @RequestBody JsonNode body) {
        User user = security.requiredUser(request);
        JsonNode in = Json.wrapper(body, "article");
        String title = Json.text(in, "title");
        String description = Json.text(in, "description");
        String text = Json.text(in, "body");
        Map<String, String> errors = new LinkedHashMap<>();
        if (Json.blank(title)) errors.put("title", "can't be blank");
        if (Json.blank(description)) errors.put("description", "can't be blank");
        if (Json.blank(text)) errors.put("body", "can't be blank");
        if (!errors.isEmpty()) throw ApiException.unprocessable(errors);

        Article a = new Article();
        a.setTitle(title);
        a.setDescription(description);
        a.setBody(text);
        a.setAuthor(user);
        a.setSlug(slugify(title) + "-" + UUID.randomUUID().toString().substring(0, 8));
        a.getTagList().addAll(readTags(in));
        a.setCreatedAt(Instant.now());
        a.setUpdatedAt(a.getCreatedAt());
        articles.saveAndFlush(a);
        return Map.of("article", view(a, user));
    }

    @GetMapping("/articles/{slug}")
    public Map<String, Object> get(HttpServletRequest request, @PathVariable String slug) {
        User viewer = security.optionalUser(request);
        return Map.of("article", view(find(slug), viewer));
    }

    @PutMapping("/articles/{slug}")
    public Map<String, Object> update(HttpServletRequest request, @PathVariable String slug,
                                      @RequestBody JsonNode body) {
        User user = security.requiredUser(request);
        Article a = find(slug);
        if (!a.getAuthor().equals(user)) throw ApiException.forbidden("article");
        JsonNode in = Json.wrapper(body, "article");

        Map<String, String> errors = new LinkedHashMap<>();
        for (String f : List.of("title", "description", "body")) {
            if (in.has(f) && Json.blank(Json.text(in, f))) errors.put(f, "can't be blank");
        }
        if (in.has("tagList") && !in.get("tagList").isArray()) errors.put("tagList", "is invalid");
        if (!errors.isEmpty()) throw ApiException.unprocessable(errors);

        if (in.has("title")) a.setTitle(Json.text(in, "title"));
        if (in.has("description")) a.setDescription(Json.text(in, "description"));
        if (in.has("body")) a.setBody(Json.text(in, "body"));
        if (in.has("tagList")) {
            List<String> tags = readTags(in);
            a.getTagList().clear();
            a.getTagList().addAll(tags);
        }
        a.setUpdatedAt(Instant.now());
        articles.saveAndFlush(a);
        return Map.of("article", view(a, user));
    }

    @DeleteMapping("/articles/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest request, @PathVariable String slug) {
        User user = security.requiredUser(request);
        Article a = find(slug);
        if (!a.getAuthor().equals(user)) throw ApiException.forbidden("article");
        comments.deleteByArticle(a);
        articles.delete(a);
        articles.flush();
    }

    @PostMapping("/articles/{slug}/favorite")
    public Map<String, Object> favorite(HttpServletRequest request, @PathVariable String slug) {
        User user = security.requiredUser(request);
        Article a = find(slug);
        a.getFavoritedBy().add(user);
        articles.saveAndFlush(a);
        return Map.of("article", view(a, user));
    }

    @DeleteMapping("/articles/{slug}/favorite")
    public Map<String, Object> unfavorite(HttpServletRequest request, @PathVariable String slug) {
        User user = security.requiredUser(request);
        Article a = find(slug);
        a.getFavoritedBy().remove(user);
        articles.saveAndFlush(a);
        return Map.of("article", view(a, user));
    }

    @GetMapping("/articles/{slug}/comments")
    public Map<String, Object> listComments(HttpServletRequest request, @PathVariable String slug) {
        User viewer = security.optionalUser(request);
        Article a = find(slug);
        List<Map<String, Object>> views = new ArrayList<>();
        for (Comment c : comments.findByArticleOrderByIdAsc(a)) {
            views.add(commentView(c, viewer));
        }
        return Map.of("comments", views);
    }

    @PostMapping("/articles/{slug}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addComment(HttpServletRequest request, @PathVariable String slug,
                                          @RequestBody JsonNode body) {
        User user = security.requiredUser(request);
        Article a = find(slug);
        JsonNode in = Json.wrapper(body, "comment");
        String text = Json.text(in, "body");
        if (Json.blank(text)) throw ApiException.unprocessable(Map.of("body", "can't be blank"));
        Comment c = new Comment();
        c.setBody(text);
        c.setAuthor(user);
        c.setArticle(a);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(c.getCreatedAt());
        comments.saveAndFlush(c);
        return Map.of("comment", commentView(c, user));
    }

    @PutMapping("/articles/{slug}/comments/{id}")
    public Map<String, Object> updateComment(HttpServletRequest request, @PathVariable String slug,
                                             @PathVariable long id, @RequestBody JsonNode body) {
        User user = security.requiredUser(request);
        Article a = find(slug);
        Comment c = comments.findById(id).filter(x -> x.getArticle().equals(a))
                .orElseThrow(() -> ApiException.notFound("comment"));
        if (!c.getAuthor().equals(user)) throw ApiException.forbidden("comment");
        String text = Json.text(Json.wrapper(body, "comment"), "body");
        if (Json.blank(text)) throw ApiException.unprocessable(Map.of("body", "can't be blank"));
        c.setBody(text);
        c.setUpdatedAt(Instant.now());
        comments.saveAndFlush(c);
        return Map.of("comment", commentView(c, user));
    }

    @DeleteMapping("/articles/{slug}/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(HttpServletRequest request, @PathVariable String slug, @PathVariable long id) {
        User user = security.requiredUser(request);
        Article a = find(slug);
        Comment c = comments.findById(id).filter(x -> x.getArticle().equals(a))
                .orElseThrow(() -> ApiException.notFound("comment"));
        if (!c.getAuthor().equals(user)) throw ApiException.forbidden("comment");
        comments.delete(c);
        comments.flush();
    }

    @GetMapping("/tags")
    public Map<String, Object> tags() {
        return Map.of("tags", articles.findAllTags());
    }

    private Article find(String slug) {
        return articles.findBySlug(slug).orElseThrow(() -> ApiException.notFound("article"));
    }

    private static List<String> readTags(JsonNode in) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        JsonNode list = in.get("tagList");
        if (list != null && list.isArray()) {
            list.forEach(t -> {
                if (!t.isNull() && !t.asText().isBlank()) tags.add(t.asText());
            });
        }
        return new ArrayList<>(tags);
    }

    private static String slugify(String title) {
        String s = Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "article" : s;
    }

    private Map<String, Object> view(Article a, User viewer) {
        return view(a, viewer, true);
    }

    /** Article lists omit the body, per the RealWorld multiple-articles response. */
    private Map<String, Object> view(Article a, User viewer, boolean includeBody) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", a.getSlug());
        m.put("title", a.getTitle());
        m.put("description", a.getDescription());
        if (includeBody) m.put("body", a.getBody());
        m.put("tagList", new ArrayList<>(a.getTagList()));
        m.put("createdAt", Json.time(a.getCreatedAt()));
        m.put("updatedAt", Json.time(a.getUpdatedAt()));
        m.put("favorited", viewer != null && a.getFavoritedBy().contains(viewer));
        m.put("favoritesCount", a.getFavoritedBy().size());
        m.put("author", Json.profile(a.getAuthor(), viewer != null && viewer.getFollowing().contains(a.getAuthor())));
        return m;
    }

    private Map<String, Object> commentView(Comment c, User viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("createdAt", Json.time(c.getCreatedAt()));
        m.put("updatedAt", Json.time(c.getUpdatedAt()));
        m.put("body", c.getBody());
        m.put("author", Json.profile(c.getAuthor(), viewer != null && viewer.getFollowing().contains(c.getAuthor())));
        return m;
    }
}
