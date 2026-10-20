package io.realworld

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.text.Normalizer
import java.util.concurrent.ThreadLocalRandom

private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 1000

@RestController
@RequestMapping("/api")
@Transactional
class ArticleController(
    private val articles: ArticleRepository,
    private val comments: CommentRepository,
    private val users: UserRepository,
    private val auth: Auth,
) {
    @GetMapping("/articles")
    fun list(
        @RequestHeader("Authorization", required = false) header: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) author: String?,
        @RequestParam(required = false) favorited: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) offset: Long?,
    ): Map<String, Any> {
        val viewer = auth.optional(header)
        return listView(articles.search(author, tag, favorited, page(limit, offset)), viewer)
    }

    @GetMapping("/articles/feed")
    fun feed(
        @RequestHeader("Authorization", required = false) header: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) offset: Long?,
    ): Map<String, Any> {
        val viewer = auth.required(header)
        return listView(articles.feed(viewer.id!!, page(limit, offset)), viewer)
    }

    @GetMapping("/articles/{slug}")
    fun get(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable slug: String,
    ): Map<String, ArticleView> {
        val viewer = auth.optional(header)
        return mapOf("article" to view(find(slug), viewer, includeBody = true))
    }

    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestHeader("Authorization", required = false) header: String?,
        @RequestBody(required = false) body: JsonNode?,
    ): Map<String, ArticleView> {
        val viewer = auth.required(header)
        val node = Body.wrapped(body, "article")
        val errors = mutableMapOf<String, List<String>>()
        val title = Body.requiredText(node, "title", errors)
        val description = Body.requiredText(node, "description", errors)
        val text = Body.requiredText(node, "body", errors)
        val tags = node.get("tagList")?.takeUnless { it.isNull }?.let { readTags(it) } ?: emptyList()
        Body.failIfAny(errors)
        val now = System.currentTimeMillis()
        val article = Article(uniqueSlug(title!!), title, description!!, text!!, viewer, now, now)
        article.tagList.addAll(tags)
        articles.save(article)
        return mapOf("article" to view(article, viewer, includeBody = true))
    }

    @PutMapping("/articles/{slug}")
    fun update(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable slug: String,
        @RequestBody(required = false) body: JsonNode?,
    ): Map<String, ArticleView> {
        val viewer = auth.required(header)
        val article = find(slug)
        if (article.author.id != viewer.id) throw ApiException.forbidden("article")
        val node = Body.wrapped(body, "article")
        val errors = mutableMapOf<String, List<String>>()
        val title = if (node.has("title")) Body.requiredText(node, "title", errors) else null
        val description = if (node.has("description")) Body.requiredText(node, "description", errors) else null
        val text = if (node.has("body")) Body.requiredText(node, "body", errors) else null
        val tags = if (node.has("tagList")) {
            val tagNode = node.get("tagList")
            if (tagNode.isNull) {
                errors["tagList"] = listOf("can't be null")
                null
            } else {
                readTags(tagNode)
            }
        } else {
            null
        }
        Body.failIfAny(errors)

        if (title != null && title != article.title) {
            article.title = title
            article.slug = uniqueSlug(title)
        }
        if (description != null) article.description = description
        if (text != null) article.body = text
        if (tags != null) {
            article.tagList.clear()
            article.tagList.addAll(tags)
        }
        // Guarantee a strictly increasing updatedAt even within the same millisecond.
        article.updatedAt = maxOf(System.currentTimeMillis(), article.updatedAt + 1)
        articles.save(article)
        return mapOf("article" to view(article, viewer, includeBody = true))
    }

    @DeleteMapping("/articles/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable slug: String,
    ) {
        val viewer = auth.required(header)
        val article = find(slug)
        if (article.author.id != viewer.id) throw ApiException.forbidden("article")
        comments.deleteAll(comments.findByArticleOrderByCreatedAtAscIdAsc(article))
        articles.delete(article)
    }

    @PostMapping("/articles/{slug}/favorite")
    fun favorite(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable slug: String,
    ): Map<String, ArticleView> {
        val viewer = auth.required(header)
        val article = find(slug)
        article.favoritedBy.add(viewer)
        return mapOf("article" to view(article, viewer, includeBody = true))
    }

    @DeleteMapping("/articles/{slug}/favorite")
    fun unfavorite(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable slug: String,
    ): Map<String, ArticleView> {
        val viewer = auth.required(header)
        val article = find(slug)
        article.favoritedBy.remove(viewer)
        return mapOf("article" to view(article, viewer, includeBody = true))
    }

    @GetMapping("/articles/{slug}/comments")
    fun listComments(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable slug: String,
    ): Map<String, List<CommentView>> {
        val viewer = auth.optional(header)
        val article = find(slug)
        return mapOf("comments" to comments.findByArticleOrderByCreatedAtAscIdAsc(article).map { commentView(it, viewer) })
    }

    @PostMapping("/articles/{slug}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun addComment(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable slug: String,
        @RequestBody(required = false) body: JsonNode?,
    ): Map<String, CommentView> {
        val viewer = auth.required(header)
        val article = find(slug)
        val node = Body.wrapped(body, "comment")
        val errors = mutableMapOf<String, List<String>>()
        val text = Body.requiredText(node, "body", errors)
        Body.failIfAny(errors)
        val now = System.currentTimeMillis()
        val comment = comments.save(Comment(text!!, article, viewer, now, now))
        return mapOf("comment" to commentView(comment, viewer))
    }

    @PutMapping("/articles/{slug}/comments/{id}")
    fun updateComment(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable slug: String,
        @PathVariable id: String,
        @RequestBody(required = false) body: JsonNode?,
    ): Map<String, CommentView> {
        val viewer = auth.required(header)
        val comment = findOwnComment(find(slug), id, viewer)
        val node = Body.wrapped(body, "comment")
        val errors = mutableMapOf<String, List<String>>()
        val text = Body.requiredText(node, "body", errors)
        Body.failIfAny(errors)
        comment.body = text!!
        // Guarantee a strictly increasing updatedAt even within the same millisecond.
        comment.updatedAt = maxOf(System.currentTimeMillis(), comment.updatedAt + 1)
        comments.save(comment)
        return mapOf("comment" to commentView(comment, viewer))
    }

    @DeleteMapping("/articles/{slug}/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteComment(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable slug: String,
        @PathVariable id: String,
    ) {
        val viewer = auth.required(header)
        comments.delete(findOwnComment(find(slug), id, viewer))
    }

    @GetMapping("/tags")
    fun tags(): Map<String, List<String>> = mapOf("tags" to articles.allTags())

    private fun find(slug: String) = articles.findBySlug(slug) ?: throw ApiException.notFound("article")

    /** Finds comment [id] on [article]; 404 if absent, 403 if [viewer] is not its author. */
    private fun findOwnComment(article: Article, id: String, viewer: User): Comment {
        val comment = id.toLongOrNull()?.let { comments.findById(it).orElse(null) }
        if (comment == null || comment.article.id != article.id) throw ApiException.notFound("comment")
        if (comment.author.id != viewer.id) throw ApiException.forbidden("comment")
        return comment
    }

    private fun page(limit: Int?, offset: Long?) =
        OffsetLimit(maxOf(offset ?: 0, 0), (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT))

    private fun listView(page: Page<Article>, viewer: User?): Map<String, Any> = mapOf(
        "articles" to page.content.map { view(it, viewer, includeBody = false) },
        "articlesCount" to page.totalElements,
    )

    private fun readTags(node: JsonNode): List<String> {
        if (!node.isArray || node.any { !it.isTextual }) throw ApiException.invalid("tagList", "must be an array of strings")
        return node.map { it.asText().trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun profile(user: User, viewer: User?) = ProfileView(
        user.username,
        user.bio,
        user.image,
        viewer != null && viewer.id != user.id && users.isFollowing(viewer.id!!, user.id!!),
    )

    private fun view(article: Article, viewer: User?, includeBody: Boolean) = ArticleView(
        slug = article.slug,
        title = article.title,
        description = article.description,
        body = if (includeBody) article.body else null,
        tagList = article.tagList.toList(),
        createdAt = formatTimestamp(article.createdAt),
        updatedAt = formatTimestamp(article.updatedAt),
        favorited = viewer != null && article.favoritedBy.any { it.id == viewer.id },
        favoritesCount = article.favoritedBy.size,
        author = profile(article.author, viewer),
    )

    private fun commentView(comment: Comment, viewer: User?) = CommentView(
        id = comment.id!!,
        createdAt = formatTimestamp(comment.createdAt),
        updatedAt = formatTimestamp(comment.updatedAt),
        body = comment.body,
        author = profile(comment.author, viewer),
    )

    private fun uniqueSlug(title: String): String {
        val base = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "article" }
        var slug = base
        while (articles.existsBySlug(slug)) {
            slug = base + "-" + java.lang.Long.toString(ThreadLocalRandom.current().nextLong(1L shl 30, 1L shl 40), 36)
        }
        return slug
    }
}
