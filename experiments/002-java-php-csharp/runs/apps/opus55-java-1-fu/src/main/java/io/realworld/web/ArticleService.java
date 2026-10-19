package io.realworld.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.realworld.domain.*;
import io.realworld.error.ApiException;
import io.realworld.web.Dtos.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 1000;

    private final ArticleRepository articles;
    private final CommentRepository comments;
    private final TagRepository tags;
    private final UserService userService;
    private final Views views;
    private final EntityManager em;

    public ArticleService(ArticleRepository articles, CommentRepository comments, TagRepository tags,
                          UserService userService, Views views, EntityManager em) {
        this.articles = articles;
        this.comments = comments;
        this.tags = tags;
        this.userService = userService;
        this.views = views;
        this.em = em;
    }

    // ---- listing -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ArticlesResponse list(String tag, String author, String favorited, Integer limit, Integer offset,
                                 Optional<Long> viewerId) {
        User viewer = userService.loadOptional(viewerId);
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
        return page(where.toString(), params, limit, offset, viewer);
    }

    @Transactional(readOnly = true)
    public ArticlesResponse feed(long viewerId, Integer limit, Integer offset) {
        User viewer = userService.load(viewerId);
        String where = " where a.author in (select f from User u join u.following f where u.id = :viewerId)";
        return page(where, Map.of("viewerId", viewer.getId()), limit, offset, viewer);
    }

    private ArticlesResponse page(String where, Map<String, Object> params, Integer limit, Integer offset, User viewer) {
        int max = limit == null || limit < 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int first = offset == null || offset < 0 ? 0 : offset;

        TypedQuery<Long> count = em.createQuery("select count(a) from Article a" + where, Long.class);
        TypedQuery<Article> select = em.createQuery(
                "select a from Article a join fetch a.author" + where + " order by a.createdAt desc, a.id desc",
                Article.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            select.setParameter(k, v);
        });
        long total = count.getSingleResult();
        List<Article> found = max == 0 ? List.of() : select.setFirstResult(first).setMaxResults(max).getResultList();
        List<ArticleDto> dtos = found.stream().map(a -> views.article(a, viewer, false)).toList();
        return new ArticlesResponse(dtos, total);
    }

    // ---- single article ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ArticleResponse get(String slug, Optional<Long> viewerId) {
        User viewer = userService.loadOptional(viewerId);
        return new ArticleResponse(views.article(find(slug), viewer, true));
    }

    @Transactional
    public ArticleResponse create(long userId, JsonNode body) {
        User author = userService.load(userId);
        JsonNode in = Json.wrapped(body, "article");
        String title = Json.required(in, "title");
        String description = Json.required(in, "description");
        String text = Json.required(in, "body");
        List<Tag> tagList = in.has("tagList") ? resolveTags(in.get("tagList")) : List.of();

        Article article = new Article(uniqueSlug(title), title, description, text, author, System.currentTimeMillis());
        article.getTags().addAll(tagList);
        articles.save(article);
        return new ArticleResponse(views.article(article, author, true));
    }

    @Transactional
    public ArticleResponse update(String slug, long userId, JsonNode body) {
        User viewer = userService.load(userId);
        Article article = find(slug);
        if (!article.getAuthor().equals(viewer)) {
            throw ApiException.forbidden("article");
        }
        JsonNode in = Json.wrapped(body, "article");
        String title = Json.requiredIfPresent(in, "title");
        String description = Json.requiredIfPresent(in, "description");
        String text = Json.requiredIfPresent(in, "body");
        List<Tag> tagList = in.has("tagList") ? resolveTags(in.get("tagList")) : null;

        if (title != null && !title.equals(article.getTitle())) {
            article.setTitle(title);
            article.setSlug(uniqueSlug(title));
        }
        if (description != null) {
            article.setDescription(description);
        }
        if (text != null) {
            article.setBody(text);
        }
        if (tagList != null) {
            article.getTags().clear();
            article.getTags().addAll(tagList);
        }
        // Guarantee a visible change even when the update lands within the creation millisecond.
        article.setUpdatedAt(Math.max(System.currentTimeMillis(), article.getUpdatedAt() + 1));
        return new ArticleResponse(views.article(article, viewer, true));
    }

    @Transactional
    public void delete(String slug, long userId) {
        User viewer = userService.load(userId);
        Article article = find(slug);
        if (!article.getAuthor().equals(viewer)) {
            throw ApiException.forbidden("article");
        }
        articles.delete(article);
    }

    @Transactional
    public ArticleResponse favorite(String slug, long userId, boolean favorite) {
        User viewer = userService.load(userId);
        Article article = find(slug);
        if (favorite) {
            article.getFavoritedBy().add(viewer);
        } else {
            article.getFavoritedBy().remove(viewer);
        }
        return new ArticleResponse(views.article(article, viewer, true));
    }

    // ---- comments ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CommentsResponse comments(String slug, Optional<Long> viewerId) {
        User viewer = userService.loadOptional(viewerId);
        Article article = find(slug);
        List<CommentDto> dtos = comments.findByArticleOrderByCreatedAtAscIdAsc(article).stream()
                .map(c -> views.comment(c, viewer)).toList();
        return new CommentsResponse(dtos);
    }

    @Transactional
    public CommentResponse addComment(String slug, long userId, JsonNode body) {
        User author = userService.load(userId);
        Article article = find(slug);
        JsonNode in = Json.wrapped(body, "comment");
        String text = Json.required(in, "body");
        Comment comment = comments.save(new Comment(text, article, author, System.currentTimeMillis()));
        return new CommentResponse(views.comment(comment, author));
    }

    @Transactional
    public CommentResponse updateComment(String slug, long commentId, long userId, JsonNode body) {
        User viewer = userService.load(userId);
        Comment comment = ownedComment(slug, commentId, viewer);
        JsonNode in = Json.wrapped(body, "comment");
        comment.setBody(Json.required(in, "body"));
        // Guarantee a visible change even when the update lands within the creation millisecond.
        comment.setUpdatedAt(Math.max(System.currentTimeMillis(), comment.getUpdatedAt() + 1));
        return new CommentResponse(views.comment(comment, viewer));
    }

    @Transactional
    public void deleteComment(String slug, long commentId, long userId) {
        User viewer = userService.load(userId);
        comments.delete(ownedComment(slug, commentId, viewer));
    }

    /** Loads a comment of the given article and checks that {@code viewer} wrote it. */
    private Comment ownedComment(String slug, long commentId, User viewer) {
        Article article = find(slug);
        Comment comment = comments.findById(commentId)
                .filter(c -> c.getArticle().getId().equals(article.getId()))
                .orElseThrow(() -> ApiException.notFound("comment"));
        if (!comment.getAuthor().equals(viewer)) {
            throw ApiException.forbidden("comment");
        }
        return comment;
    }

    // ---- tags ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TagsResponse tags() {
        return new TagsResponse(tags.findUsedNames());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Article find(String slug) {
        return articles.findBySlug(slug).orElseThrow(() -> ApiException.notFound("article"));
    }

    private List<Tag> resolveTags(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw ApiException.invalid("tagList", "must be an array of strings");
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw ApiException.invalid("tagList", "must be an array of strings");
            }
            String name = item.asText().trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        List<Tag> result = new ArrayList<>();
        for (String name : names) {
            result.add(tags.findByName(name).orElseGet(() -> tags.save(new Tag(name))));
        }
        return result;
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
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String slug = normalized.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? "article" : slug;
    }
}
