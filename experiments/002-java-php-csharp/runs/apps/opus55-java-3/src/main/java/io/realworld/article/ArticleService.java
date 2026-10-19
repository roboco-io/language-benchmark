package io.realworld.article;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import io.realworld.common.ApiException;
import io.realworld.common.Json;
import io.realworld.common.Times;
import io.realworld.common.Validation;
import io.realworld.user.ProfileView;
import io.realworld.user.User;
import io.realworld.user.UserRepository;
import io.realworld.user.UserService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

@Service
public class ArticleService {

    private final ArticleRepository articles;
    private final CommentRepository comments;
    private final UserRepository users;
    private final UserService userService;
    private final EntityManager em;

    public ArticleService(ArticleRepository articles, CommentRepository comments, UserRepository users,
                          UserService userService, EntityManager em) {
        this.articles = articles;
        this.comments = comments;
        this.users = users;
        this.userService = userService;
        this.em = em;
    }

    // ---------------------------------------------------------------- articles

    /** Filters for GET /api/articles; null fields are not applied. */
    public record Filter(String tag, String author, String favorited) {
    }

    @Transactional(readOnly = true)
    public ArticleList list(Filter filter, int limit, int offset, Optional<Long> viewerId) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (filter.author() != null) {
            where.append(" and a.author.username = :author");
            params.put("author", filter.author());
        }
        if (filter.tag() != null) {
            where.append(" and exists (select 1 from Article ta join ta.tagList t where ta = a and t = :tag)");
            params.put("tag", filter.tag());
        }
        if (filter.favorited() != null) {
            where.append(" and exists (select 1 from Article fa join fa.favoritedBy u"
                    + " where fa = a and u.username = :favorited)");
            params.put("favorited", filter.favorited());
        }
        return page(where.toString(), params, limit, offset, viewerId);
    }

    @Transactional(readOnly = true)
    public ArticleList feed(long viewerId, int limit, int offset) {
        userService.load(viewerId);
        List<Long> followed = users.followedIds(viewerId);
        if (followed.isEmpty()) {
            return new ArticleList(List.of(), 0);
        }
        return page(" where a.author.id in :followed", Map.of("followed", followed), limit, offset,
                Optional.of(viewerId));
    }

    private ArticleList page(String where, Map<String, Object> params, int limit, int offset,
                             Optional<Long> viewerId) {
        TypedQuery<Long> count = em.createQuery("select count(a) from Article a" + where, Long.class);
        TypedQuery<Article> select = em.createQuery(
                "select a from Article a join fetch a.author" + where + " order by a.createdAt desc, a.id desc",
                Article.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            select.setParameter(k, v);
        });
        long total = count.getSingleResult();
        List<Article> found = select.setFirstResult(offset).setMaxResults(limit).getResultList();
        return new ArticleList(views(found, viewerId, false), total);
    }

    @Transactional(readOnly = true)
    public ArticleView get(String slug, Optional<Long> viewerId) {
        return view(find(slug), viewerId);
    }

    @Transactional
    public ArticleView create(long userId, JsonNode body) {
        User author = userService.load(userId);
        JsonNode node = Json.wrapper(body, "article");
        String title = Json.text(node, "title");
        String description = Json.text(node, "description");
        String text = Json.text(node, "body");
        Validation v = new Validation()
                .notBlank("title", title)
                .notBlank("description", description)
                .notBlank("body", text);
        List<String> tags = node.has("tagList") ? tags(node.get("tagList"), v) : List.of();
        v.throwIfAny();

        Article article = new Article(uniqueSlug(title), title, description, text, author, Times.now());
        article.getTagList().addAll(tags);
        articles.save(article);
        return view(article, Optional.of(userId));
    }

    @Transactional
    public ArticleView update(String slug, long userId, JsonNode body) {
        userService.load(userId);
        Article article = find(slug);
        if (!article.getAuthor().getId().equals(userId)) {
            throw ApiException.forbidden("article");
        }
        JsonNode node = Json.wrapper(body, "article");
        Validation v = new Validation();
        String title = null;
        String description = null;
        String text = null;
        List<String> tags = null;
        if (node.has("title")) {
            title = Json.text(node, "title");
            v.notBlank("title", title);
        }
        if (node.has("description")) {
            description = Json.text(node, "description");
            v.notBlank("description", description);
        }
        if (node.has("body")) {
            text = Json.text(node, "body");
            v.notBlank("body", text);
        }
        if (node.has("tagList")) {
            tags = tags(node.get("tagList"), v);
        }
        v.throwIfAny();

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
        if (tags != null) {
            article.getTagList().clear();
            article.getTagList().addAll(tags);
        }
        article.setUpdatedAt(Times.after(article.getUpdatedAt()));
        return view(article, Optional.of(userId));
    }

    @Transactional
    public void delete(String slug, long userId) {
        userService.load(userId);
        Article article = find(slug);
        if (!article.getAuthor().getId().equals(userId)) {
            throw ApiException.forbidden("article");
        }
        comments.deleteForArticle(article);
        articles.delete(article);
    }

    @Transactional
    public ArticleView favorite(String slug, long userId) {
        User user = userService.load(userId);
        Article article = find(slug);
        article.getFavoritedBy().add(user);
        em.flush();
        return view(article, Optional.of(userId));
    }

    @Transactional
    public ArticleView unfavorite(String slug, long userId) {
        User user = userService.load(userId);
        Article article = find(slug);
        article.getFavoritedBy().remove(user);
        em.flush();
        return view(article, Optional.of(userId));
    }

    @Transactional(readOnly = true)
    public List<String> tags() {
        return articles.allTags();
    }

    // ---------------------------------------------------------------- comments

    @Transactional(readOnly = true)
    public List<CommentView> comments(String slug, Optional<Long> viewerId) {
        List<Comment> found = comments.findForArticle(find(slug));
        Set<Long> followed = followed(viewerId, found.stream().map(c -> c.getAuthor().getId()).toList());
        return found.stream().map(c -> commentView(c, followed)).toList();
    }

    @Transactional
    public CommentView addComment(String slug, long userId, JsonNode body) {
        User author = userService.load(userId);
        Article article = find(slug);
        JsonNode node = Json.wrapper(body, "comment");
        String text = Json.text(node, "body");
        new Validation().notBlank("body", text).throwIfAny();
        Comment comment = comments.save(new Comment(text, article, author, Times.now()));
        return commentView(comment, Set.of());
    }

    @Transactional
    public void deleteComment(String slug, long commentId, long userId) {
        userService.load(userId);
        Article article = find(slug);
        Comment comment = comments.findById(commentId)
                .filter(c -> c.getArticle().getId().equals(article.getId()))
                .orElseThrow(() -> ApiException.notFound("comment"));
        if (!comment.getAuthor().getId().equals(userId)) {
            throw ApiException.forbidden("comment");
        }
        comments.delete(comment);
    }

    // ---------------------------------------------------------------- helpers

    private Article find(String slug) {
        return articles.findBySlug(slug).orElseThrow(() -> ApiException.notFound("article"));
    }

    private String uniqueSlug(String title) {
        String base = Slugs.base(title);
        String slug = base;
        while (articles.existsBySlug(slug)) {
            slug = base + "-" + Slugs.suffix();
        }
        return slug;
    }

    /** Parses tagList: must be an array of strings; tags are trimmed, de-duplicated, and keep their order. */
    private static List<String> tags(JsonNode value, Validation v) {
        if (value == null || !value.isArray()) {
            v.add("tagList", "must be an array of strings");
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                v.add("tagList", "must be an array of strings");
                return List.of();
            }
            String tag = item.asText().trim();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return new ArrayList<>(tags);
    }

    private ArticleView view(Article article, Optional<Long> viewerId) {
        return views(List.of(article), viewerId, true).getFirst();
    }

    /** Builds views for a batch of articles with a constant number of queries for counts and flags. */
    private List<ArticleView> views(List<Article> list, Optional<Long> viewerId, boolean withBody) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Article::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : articles.favoriteCounts(ids)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        Set<Long> favorited = viewerId.map(id -> (Set<Long>) new HashSet<>(articles.favoritedAmong(id, ids)))
                .orElse(Set.of());
        Set<Long> followed = followed(viewerId, list.stream().map(a -> a.getAuthor().getId()).toList());

        List<ArticleView> result = new ArrayList<>(list.size());
        for (Article a : list) {
            User author = a.getAuthor();
            result.add(new ArticleView(
                    a.getSlug(),
                    a.getTitle(),
                    a.getDescription(),
                    withBody ? a.getBody() : null,
                    List.copyOf(a.getTagList()),
                    Times.format(a.getCreatedAt()),
                    Times.format(a.getUpdatedAt()),
                    favorited.contains(a.getId()),
                    counts.getOrDefault(a.getId(), 0L),
                    ProfileView.of(author, followed.contains(author.getId()))));
        }
        return result;
    }

    private Set<Long> followed(Optional<Long> viewerId, Collection<Long> authorIds) {
        if (viewerId.isEmpty() || authorIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(users.followedAmong(viewerId.get(), new LinkedHashSet<>(authorIds)));
    }

    private static CommentView commentView(Comment c, Set<Long> followed) {
        User author = c.getAuthor();
        return new CommentView(c.getId(), Times.format(c.getCreatedAt()), Times.format(c.getUpdatedAt()),
                c.getBody(), ProfileView.of(author, followed.contains(author.getId())));
    }
}
