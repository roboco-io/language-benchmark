package io.realworld

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.EntityManager
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.security.SecureRandom

private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 100

@RestController
@RequestMapping("/api")
class ArticleController(
    private val articles: ArticleRepository,
    private val users: UserRepository,
    private val favorites: FavoriteRepository,
    private val comments: CommentRepository,
    private val views: ArticleViews,
    private val auth: Auth,
    private val em: EntityManager,
) {
    private val random = SecureRandom()

    private fun find(slug: String) = articles.findBySlug(slug) ?: throw ApiException.notFound("article")

    // ---- Listing -------------------------------------------------------------------------------

    @GetMapping("/articles")
    @Transactional(readOnly = true)
    fun list(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestParam tag: String?,
        @RequestParam author: String?,
        @RequestParam favorited: String?,
        @RequestParam limit: String?,
        @RequestParam offset: String?,
    ): Any {
        val me = auth.optional(authorization)
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (!tag.isNullOrEmpty()) {
            where += "a.id in (select a2.id from ArticleEntity a2 join a2.tagList t where t = :tag)"
            params["tag"] = tag
        }
        if (!author.isNullOrEmpty()) {
            where += "a.authorId in (select u.id from UserEntity u where u.username = :author)"
            params["author"] = author
        }
        if (!favorited.isNullOrEmpty()) {
            where += "a.id in (select f.articleId from FavoriteEntity f, UserEntity u where f.userId = u.id and u.username = :favorited)"
            params["favorited"] = favorited
        }
        return page(where, params, me, limit, offset)
    }

    @GetMapping("/articles/feed")
    @Transactional(readOnly = true)
    fun feed(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestParam limit: String?,
        @RequestParam offset: String?,
    ): Any {
        val me = auth.required(authorization)
        val where = listOf("a.authorId in (select f.followeeId from FollowEntity f where f.followerId = :me)")
        return page(where, mapOf("me" to me.id!!), me, limit, offset)
    }

    private fun page(where: List<String>, params: Map<String, Any>, me: UserEntity?, limit: String?, offset: String?): Any {
        val lim = (limit?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(0, MAX_LIMIT)
        val off = (offset?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val clause = if (where.isEmpty()) "" else " where " + where.joinToString(" and ")

        val countQuery = em.createQuery("select count(a) from ArticleEntity a$clause", java.lang.Long::class.java)
        params.forEach { (k, v) -> countQuery.setParameter(k, v) }
        val count = countQuery.singleResult.toLong()

        val found = if (lim == 0 || off >= count) {
            emptyList()
        } else {
            val q = em.createQuery("select a from ArticleEntity a$clause order by a.createdAt desc, a.id desc", ArticleEntity::class.java)
            params.forEach { (k, v) -> q.setParameter(k, v) }
            q.setFirstResult(off).setMaxResults(lim).resultList
        }
        return mapOf("articles" to views.many(found, me, includeBody = false), "articlesCount" to count)
    }

    // ---- CRUD ----------------------------------------------------------------------------------

    @GetMapping("/articles/{slug}")
    @Transactional(readOnly = true)
    fun get(@PathVariable slug: String, @RequestHeader("Authorization", required = false) authorization: String?): Any {
        val me = auth.optional(authorization)
        return mapOf("article" to views.one(find(slug), me))
    }

    @PostMapping("/articles")
    @Transactional
    fun create(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody(required = false) body: JsonNode?,
    ): ResponseEntity<Any> {
        val me = auth.required(authorization)
        val input = body.wrapped("article")
        val v = Validator()
        val title = input.requiredText("title", v)
        val description = input.requiredText("description", v)
        val text = input.requiredText("body", v)
        val tags = if (input.has("tagList")) parseTags(input, v) else emptyList()
        v.throwIfAny()

        val now = System.currentTimeMillis()
        val article = articles.save(
            ArticleEntity(
                slug = uniqueSlug(title!!),
                title = title,
                description = description!!,
                body = text!!,
                authorId = me.id!!,
                createdAt = now,
                updatedAt = now,
                tagList = tags.toMutableList(),
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("article" to views.one(article, me)))
    }

    @PutMapping("/articles/{slug}")
    @Transactional
    fun update(
        @PathVariable slug: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody(required = false) body: JsonNode?,
    ): Any {
        val me = auth.required(authorization)
        val article = find(slug)
        if (article.authorId != me.id) throw ApiException.forbidden("article")
        val input = body.wrapped("article")
        val v = Validator()
        val title = if (input.has("title")) input.requiredText("title", v) else null
        val description = if (input.has("description")) input.requiredText("description", v) else null
        val text = if (input.has("body")) input.requiredText("body", v) else null
        val tags = if (input.has("tagList")) parseTags(input, v) else null
        v.throwIfAny()

        if (title != null && title != article.title) {
            article.title = title
            article.slug = uniqueSlug(title)
        }
        description?.let { article.description = it }
        text?.let { article.body = it }
        if (tags != null) {
            article.tagList.clear()
            article.tagList.addAll(tags)
        }
        // Guarantee a strictly increasing updatedAt even for updates within the same millisecond.
        article.updatedAt = maxOf(System.currentTimeMillis(), article.updatedAt + 1)
        return mapOf("article" to views.one(articles.save(article), me))
    }

    @DeleteMapping("/articles/{slug}")
    @Transactional
    fun delete(@PathVariable slug: String, @RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Void> {
        val me = auth.required(authorization)
        val article = find(slug)
        if (article.authorId != me.id) throw ApiException.forbidden("article")
        comments.deleteAllByArticleId(article.id!!)
        favorites.deleteAllByArticleId(article.id!!)
        articles.delete(article)
        return ResponseEntity.noContent().build()
    }

    // ---- Favorites -----------------------------------------------------------------------------

    @PostMapping("/articles/{slug}/favorite")
    @Transactional
    fun favorite(@PathVariable slug: String, @RequestHeader("Authorization", required = false) authorization: String?): Any {
        val me = auth.required(authorization)
        val article = find(slug)
        if (!favorites.existsByUserIdAndArticleId(me.id!!, article.id!!)) {
            favorites.saveAndFlush(FavoriteEntity(userId = me.id!!, articleId = article.id!!))
        }
        return mapOf("article" to views.one(article, me))
    }

    @DeleteMapping("/articles/{slug}/favorite")
    @Transactional
    fun unfavorite(@PathVariable slug: String, @RequestHeader("Authorization", required = false) authorization: String?): Any {
        val me = auth.required(authorization)
        val article = find(slug)
        favorites.deleteByUserIdAndArticleId(me.id!!, article.id!!)
        favorites.flush()
        return mapOf("article" to views.one(article, me))
    }

    // ---- Comments ------------------------------------------------------------------------------

    private fun commentView(c: CommentEntity, author: UserEntity, following: Boolean) = linkedMapOf(
        "id" to c.id,
        "createdAt" to formatTimestamp(c.createdAt),
        "updatedAt" to formatTimestamp(c.updatedAt),
        "body" to c.body,
        "author" to profileView(author, following),
    )

    @GetMapping("/articles/{slug}/comments")
    @Transactional(readOnly = true)
    fun listComments(@PathVariable slug: String, @RequestHeader("Authorization", required = false) authorization: String?): Any {
        val me = auth.optional(authorization)
        val article = find(slug)
        val list = comments.findByArticleIdOrderByIdAsc(article.id!!)
        val authorIds = list.map { it.authorId }.toSet()
        val authors = users.findAllById(authorIds).associateBy { it.id!! }
        val followed = if (me == null || authorIds.isEmpty()) emptySet() else followsAmong(me.id!!, authorIds)
        return mapOf("comments" to list.map { commentView(it, authors.getValue(it.authorId), it.authorId in followed) })
    }

    private fun followsAmong(me: Long, ids: Collection<Long>): Set<Long> =
        em.createQuery("select f.followeeId from FollowEntity f where f.followerId = :me and f.followeeId in :ids", java.lang.Long::class.java)
            .setParameter("me", me).setParameter("ids", ids).resultList.map { it.toLong() }.toSet()

    @PostMapping("/articles/{slug}/comments")
    @Transactional
    fun addComment(
        @PathVariable slug: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody(required = false) body: JsonNode?,
    ): ResponseEntity<Any> {
        val me = auth.required(authorization)
        val article = find(slug)
        val input = body.wrapped("comment")
        val v = Validator()
        val text = input.requiredText("body", v)
        v.throwIfAny()

        val now = System.currentTimeMillis()
        val comment = comments.save(CommentEntity(articleId = article.id!!, authorId = me.id!!, body = text!!, createdAt = now, updatedAt = now))
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("comment" to commentView(comment, me, false)))
    }

    @DeleteMapping("/articles/{slug}/comments/{id}")
    @Transactional
    fun deleteComment(
        @PathVariable slug: String,
        @PathVariable id: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<Void> {
        val me = auth.required(authorization)
        val article = find(slug)
        val comment = id.toLongOrNull()?.let { comments.findById(it).orElse(null) }
        if (comment == null || comment.articleId != article.id) throw ApiException.notFound("comment")
        if (comment.authorId != me.id) throw ApiException.forbidden("comment")
        comments.delete(comment)
        return ResponseEntity.noContent().build()
    }

    // ---- Tags ----------------------------------------------------------------------------------

    @GetMapping("/tags")
    @Transactional(readOnly = true)
    fun tags(): Any = mapOf("tags" to articles.findAllTags())

    // ---- Helpers -------------------------------------------------------------------------------

    /** Parses `tagList`: must be an array of strings. Blank entries are dropped, duplicates removed, order kept. */
    private fun parseTags(input: JsonNode, v: Validator): List<String> {
        val node = input.get("tagList")
        if (node == null || !node.isArray || node.any { !it.isTextual }) {
            v.add("tagList", "must be an array of strings")
            return emptyList()
        }
        return node.map { it.asText().trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun uniqueSlug(title: String): String {
        val base = title.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(80)
            .ifEmpty { "article" }
        var slug = base
        while (articles.existsBySlug(slug)) {
            slug = "$base-" + (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }
        return slug
    }

    companion object {
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
