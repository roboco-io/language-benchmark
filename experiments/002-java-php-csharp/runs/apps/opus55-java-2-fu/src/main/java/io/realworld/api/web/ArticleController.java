package io.realworld.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.realworld.api.domain.*;
import io.realworld.api.security.Auth;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/articles")
public class ArticleController {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ArticleRepository articles;
    private final TagRepository tags;
    private final CommentRepository comments;
    private final Auth auth;
    private final EntityManager em;

    public ArticleController(ArticleRepository articles, TagRepository tags, CommentRepository comments,
                             Auth auth, EntityManager em) {
        this.articles = articles;
        this.tags = tags;
        this.comments = comments;
        this.auth = auth;
        this.em = em;
    }

    // ---- Articles ----

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(required = false) String tag,
                                    @RequestParam(required = false) String author,
                                    @RequestParam(required = false) String favorited,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) Integer offset) {
        User viewer = auth.optional(request).orElse(null);
        StringBuilder where = new StringBuilder(" where 1=1");
        Map<String, Object> params = new HashMap<>();
        if (tag != null) {
            where.append(" and exists (select 1 from Article a2 join a2.tags t where a2 = a and t.name = :tag)");
            params.put("tag", tag);
        }
        if (author != null) {
            where.append(" and a.author.username = :author");
            params.put("author", author);
        }
        if (favorited != null) {
            where.append(" and exists (select 1 from Article a3 join a3.favoritedBy f where a3 = a and f.username = :fav)");
            params.put("fav", favorited);
        }
        return page(where.toString(), params, viewer, limit, offset);
    }

    @GetMapping("/feed")
    @Transactional(readOnly = true)
    public Map<String, Object> feed(HttpServletRequest request,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) Integer offset) {
        User viewer = auth.require(request);
        String where = " where a.author in (select f from User u join u.following f where u.id = :viewerId)";
        return page(where, Map.of("viewerId", viewer.getId()), viewer, limit, offset);
    }

    private Map<String, Object> page(String where, Map<String, Object> params, User viewer,
                                     Integer limit, Integer offset) {
        int lim = limit == null ? DEFAULT_LIMIT : Math.max(0, Math.min(limit, MAX_LIMIT));
        int off = offset == null ? 0 : Math.max(0, offset);

        TypedQuery<Long> count = em.createQuery("select count(a) from Article a" + where, Long.class);
        TypedQuery<Article> select = em.createQuery(
                "select a from Article a join fetch a.author" + where + " order by a.createdAt desc, a.id desc",
                Article.class);
        params.forEach((k, v) -> { count.setParameter(k, v); select.setParameter(k, v); });
        List<Article> found = lim == 0 ? List.of() : select.setFirstResult(off).setMaxResults(lim).getResultList();

        List<Map<String, Object>> views = found.stream().map(a -> Views.article(a, viewer, false)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("articles", views);
        out.put("articlesCount", count.getSingleResult());
        return out;
    }

    @GetMapping("/{slug}")
    @Transactional(readOnly = true)
    public Map<String, Object> get(HttpServletRequest request, @PathVariable String slug) {
        User viewer = auth.optional(request).orElse(null);
        return wrap(find(slug), viewer);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Object> create(HttpServletRequest request, @RequestBody JsonNode root) {
        User viewer = auth.require(request);
        JsonNode in = Body.section(root, "article");
        String title = Body.required(in, "title");
        String description = Body.required(in, "description");
        String body = Body.required(in, "body");
        List<String> tagNames = in.has("tagList") && !in.get("tagList").isNull()
                ? Body.stringList(in, "tagList") : List.of();

        Article a = new Article();
        a.setTitle(title);
        a.setDescription(description);
        a.setBody(body);
        a.setSlug(uniqueSlug(title));
        a.setAuthor(viewer);
        long now = System.currentTimeMillis();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        a.getTags().addAll(resolveTags(tagNames));
        articles.save(a);
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(a, viewer));
    }

    @PutMapping("/{slug}")
    @Transactional
    public Map<String, Object> update(HttpServletRequest request, @PathVariable String slug,
                                      @RequestBody JsonNode root) {
        User viewer = auth.require(request);
        Article a = find(slug);
        if (!a.getAuthor().getId().equals(viewer.getId())) throw ApiException.forbidden("article");
        JsonNode in = Body.section(root, "article");

        String title = Body.optionalNonBlank(in, "title");
        String description = Body.optionalNonBlank(in, "description");
        String body = Body.optionalNonBlank(in, "body");
        List<String> tagNames = in.has("tagList") ? Body.stringList(in, "tagList") : null;

        if (title != null && !title.equals(a.getTitle())) {
            a.setTitle(title);
            a.setSlug(uniqueSlug(title));
        }
        if (description != null) a.setDescription(description);
        if (body != null) a.setBody(body);
        if (tagNames != null) {
            a.getTags().clear();
            a.getTags().addAll(resolveTags(tagNames));
        }
        long now = System.currentTimeMillis();
        a.setUpdatedAt(now > a.getUpdatedAt() ? now : a.getUpdatedAt() + 1);
        return wrap(a, viewer);
    }

    @DeleteMapping("/{slug}")
    @Transactional
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String slug) {
        User viewer = auth.require(request);
        Article a = find(slug);
        if (!a.getAuthor().getId().equals(viewer.getId())) throw ApiException.forbidden("article");
        comments.deleteByArticle(a);
        articles.delete(a);
        return ResponseEntity.noContent().build();
    }

    // ---- Favorites ----

    @PostMapping("/{slug}/favorite")
    @Transactional
    public Map<String, Object> favorite(HttpServletRequest request, @PathVariable String slug) {
        User viewer = auth.require(request);
        Article a = find(slug);
        if (!a.isFavoritedBy(viewer)) a.getFavoritedBy().add(viewer);
        return wrap(a, viewer);
    }

    @DeleteMapping("/{slug}/favorite")
    @Transactional
    public Map<String, Object> unfavorite(HttpServletRequest request, @PathVariable String slug) {
        User viewer = auth.require(request);
        Article a = find(slug);
        a.getFavoritedBy().removeIf(u -> u.getId().equals(viewer.getId()));
        return wrap(a, viewer);
    }

    // ---- Comments ----

    @GetMapping("/{slug}/comments")
    @Transactional(readOnly = true)
    public Map<String, Object> listComments(HttpServletRequest request, @PathVariable String slug) {
        User viewer = auth.optional(request).orElse(null);
        Article a = find(slug);
        return Map.of("comments", comments.findByArticleOrderByCreatedAtAscIdAsc(a).stream()
                .map(c -> Views.comment(c, viewer)).toList());
    }

    @PostMapping("/{slug}/comments")
    @Transactional
    public ResponseEntity<Object> addComment(HttpServletRequest request, @PathVariable String slug,
                                             @RequestBody JsonNode root) {
        User viewer = auth.require(request);
        Article a = find(slug);
        JsonNode in = Body.section(root, "comment");
        String body = Body.required(in, "body");
        Comment c = new Comment();
        c.setBody(body);
        c.setArticle(a);
        c.setAuthor(viewer);
        long now = System.currentTimeMillis();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        comments.save(c);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("comment", Views.comment(c, viewer)));
    }

    @PutMapping("/{slug}/comments/{id}")
    @Transactional
    public Map<String, Object> updateComment(HttpServletRequest request, @PathVariable String slug,
                                             @PathVariable String id, @RequestBody(required = false) JsonNode root) {
        User viewer = auth.require(request);
        Comment c = findComment(find(slug), id);
        if (!c.getAuthor().getId().equals(viewer.getId())) throw ApiException.forbidden("comment");
        JsonNode in = Body.section(root, "comment");
        c.setBody(Body.required(in, "body"));
        // Keep updatedAt strictly increasing even if two updates land in the same millisecond.
        c.setUpdatedAt(Math.max(System.currentTimeMillis(), c.getUpdatedAt() + 1));
        return Map.of("comment", Views.comment(c, viewer));
    }

    @DeleteMapping("/{slug}/comments/{id}")
    @Transactional
    public ResponseEntity<Void> deleteComment(HttpServletRequest request, @PathVariable String slug,
                                              @PathVariable String id) {
        User viewer = auth.require(request);
        Article a = find(slug);
        Comment c = findComment(a, id);
        if (!c.getAuthor().getId().equals(viewer.getId())) throw ApiException.forbidden("comment");
        comments.delete(c);
        return ResponseEntity.noContent().build();
    }

    // ---- Helpers ----

    private Article find(String slug) {
        return articles.findBySlug(slug).orElseThrow(() -> ApiException.notFound("article"));
    }

    /** Finds a comment by id that belongs to the given article; 404 otherwise. */
    private Comment findComment(Article a, String id) {
        return parseId(id).flatMap(comments::findById)
                .filter(found -> found.getArticle().getId().equals(a.getId()))
                .orElseThrow(() -> ApiException.notFound("comment"));
    }

    private static Map<String, Object> wrap(Article a, User viewer) {
        return Map.of("article", Views.article(a, viewer, true));
    }

    private static Optional<Long> parseId(String id) {
        try {
            return Optional.of(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Looks up or creates tags, keeping client order and dropping blanks and duplicates. */
    private List<Tag> resolveTags(List<String> names) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String n : names) {
            String trimmed = n.trim();
            if (!trimmed.isEmpty()) unique.add(trimmed);
        }
        List<Tag> out = new ArrayList<>();
        for (String n : unique) {
            out.add(tags.findByName(n).orElseGet(() -> tags.save(new Tag(n))));
        }
        return out;
    }

    private String uniqueSlug(String title) {
        String base = slugify(title);
        String slug = base;
        while (articles.existsBySlug(slug)) {
            slug = base + "-" + Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36 * 36 * 36), 36);
        }
        return slug;
    }

    static String slugify(String title) {
        String s = Normalizer.normalize(title, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "article" : s;
    }
}
