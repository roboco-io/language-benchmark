package io.realworld

import jakarta.persistence.EntityManager
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.text.Normalizer
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

fun isoTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

fun slugify(title: String): String {
    val ascii = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
    return ascii.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(80).ifEmpty { "article" }
}

/** Filters for the article list endpoint; [feedOf] restricts to authors followed by that user. */
data class ArticleFilter(
    val tag: String? = null,
    val author: String? = null,
    val favorited: String? = null,
    val feedOf: Long? = null,
)

@Service
@Transactional
class ArticleService(
    private val articles: ArticleRepository,
    private val comments: CommentRepository,
    private val users: UserService,
    private val em: EntityManager,
) {
    private var lastTimestamp = 0L

    /** Strictly increasing millisecond clock so updates always change `updatedAt`. */
    @Synchronized
    private fun now(): Long {
        val t = maxOf(System.currentTimeMillis(), lastTimestamp + 1)
        lastTimestamp = t
        return t
    }

    fun articleJson(a: Article, viewer: User?, includeBody: Boolean = true): Json {
        val m = linkedMapOf<String, Any?>(
            "slug" to a.slug,
            "title" to a.title,
            "description" to a.description,
        )
        if (includeBody) m["body"] = a.body
        m["tagList"] = a.tagList.toList()
        m["createdAt"] = isoTime(a.createdAt)
        m["updatedAt"] = isoTime(a.updatedAt)
        m["favorited"] = viewer?.let(a::isFavoritedBy) ?: false
        m["favoritesCount"] = a.favoritedBy.size
        m["author"] = profileJson(a.author, viewer)
        return m
    }

    private fun find(slug: String): Article = articles.findBySlug(slug) ?: throw notFound("article")

    private fun uniqueSlug(title: String): String {
        val base = slugify(title)
        var slug = base
        while (articles.existsBySlug(slug)) {
            slug = "$base-" + ThreadLocalRandom.current().nextInt(0x100000, 0xFFFFFF).toString(16)
        }
        return slug
    }

    private fun parseTags(req: Json): MutableList<String> {
        val raw = req["tagList"] as? List<*> ?: throw invalid("tagList", "must be an array of strings")
        val tags = LinkedHashSet<String>()
        for (t in raw) {
            if (t !is String) throw invalid("tagList", "must be an array of strings")
            t.trim().takeIf { it.isNotEmpty() }?.let(tags::add)
        }
        return tags.toMutableList()
    }

    fun list(filter: ArticleFilter, limit: Int, offset: Int, viewerId: Long?): Json {
        if (limit < 0 || offset < 0) throw invalid("limit", "is invalid")
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        filter.tag?.let { where += ":tag member of a.tagList"; params["tag"] = it }
        filter.author?.let { where += "a.author.username = :author"; params["author"] = it }
        filter.favorited?.let {
            where += "exists (select 1 from Article a2 join a2.favoritedBy f where a2 = a and f.username = :favorited)"
            params["favorited"] = it
        }
        filter.feedOf?.let {
            where += "a.author in (select f from User u join u.following f where u.id = :feedOf)"
            params["feedOf"] = it
        }
        val clause = if (where.isEmpty()) "" else " where " + where.joinToString(" and ")

        val countQuery = em.createQuery("select count(a) from Article a$clause", java.lang.Long::class.java)
        params.forEach(countQuery::setParameter)
        val total = countQuery.singleResult.toLong()

        val pageQuery = em.createQuery(
            "select a from Article a join fetch a.author$clause order by a.createdAt desc, a.id desc",
            Article::class.java,
        )
        params.forEach(pageQuery::setParameter)
        val page = pageQuery.setFirstResult(offset).setMaxResults(limit).resultList

        val viewer = viewerId?.let(users::load)
        return mapOf(
            "articles" to page.map { articleJson(it, viewer, includeBody = false) },
            "articlesCount" to total,
        )
    }

    fun get(slug: String, viewerId: Long?): Json =
        mapOf("article" to articleJson(find(slug), viewerId?.let(users::load)))

    fun create(userId: Long, body: Json?): Json {
        val author = users.load(userId)
        val req = body.section("article")
        val title = req.requiredString("title").trim()
        val description = req.requiredString("description")
        val text = req.requiredString("body")
        val tags = if (req["tagList"] == null) mutableListOf() else parseTags(req)
        val ts = now()
        val article = Article(uniqueSlug(title), title, description, text, author, ts, ts)
        article.tagList = tags
        articles.save(article)
        return mapOf("article" to articleJson(article, author))
    }

    fun update(slug: String, userId: Long, body: Json?): Json {
        val user = users.load(userId)
        val article = find(slug)
        if (article.author.id != user.id) throw forbidden("article")
        val req = body.section("article")
        if (req.containsKey("title")) {
            val title = req.requiredString("title").trim()
            if (title != article.title) {
                article.title = title
                article.slug = uniqueSlug(title)
            }
        }
        if (req.containsKey("description")) article.description = req.requiredString("description")
        if (req.containsKey("body")) article.body = req.requiredString("body")
        if (req.containsKey("tagList")) {
            val tags = parseTags(req)
            article.tagList.clear()
            article.tagList.addAll(tags)
        }
        article.updatedAt = now()
        articles.saveAndFlush(article)
        return mapOf("article" to articleJson(article, user))
    }

    fun delete(slug: String, userId: Long) {
        val article = find(slug)
        if (article.author.id != userId) throw forbidden("article")
        comments.deleteByArticle(article)
        articles.delete(article)
    }

    fun favorite(slug: String, userId: Long, favorite: Boolean): Json {
        val user = users.load(userId)
        val article = find(slug)
        if (favorite) {
            if (!article.isFavoritedBy(user)) article.favoritedBy.add(user)
        } else {
            article.favoritedBy.removeIf { it.id == user.id }
        }
        return mapOf("article" to articleJson(article, user))
    }

    fun tags(): Json = mapOf("tags" to articles.findAllTags())

    private fun commentJson(c: Comment, viewer: User?): Json = linkedMapOf(
        "id" to c.id,
        "createdAt" to isoTime(c.createdAt),
        "updatedAt" to isoTime(c.updatedAt),
        "body" to c.body,
        "author" to profileJson(c.author, viewer),
    )

    fun comments(slug: String, viewerId: Long?): Json {
        val article = find(slug)
        val viewer = viewerId?.let(users::load)
        return mapOf("comments" to comments.findByArticleOrderByIdAsc(article).map { commentJson(it, viewer) })
    }

    fun addComment(slug: String, userId: Long, body: Json?): Json {
        val user = users.load(userId)
        val article = find(slug)
        val text = body.section("comment").requiredString("body")
        val ts = now()
        val comment = comments.save(Comment(text, article, user, ts, ts))
        return mapOf("comment" to commentJson(comment, user))
    }

    /** Loads a comment that belongs to the article, or fails with 404 on `comment`. */
    private fun findComment(article: Article, id: Long): Comment {
        val comment = comments.findById(id).orElse(null)
        if (comment == null || comment.article.id != article.id) throw notFound("comment")
        return comment
    }

    fun updateComment(slug: String, id: Long, userId: Long, body: Json?): Json {
        val user = users.load(userId)
        val comment = findComment(find(slug), id)
        if (comment.author.id != user.id) throw forbidden("comment")
        comment.body = body.section("comment").requiredString("body")
        comment.updatedAt = now()
        comments.saveAndFlush(comment)
        return mapOf("comment" to commentJson(comment, user))
    }

    fun deleteComment(slug: String, id: Long, userId: Long) {
        val comment = findComment(find(slug), id)
        if (comment.author.id != userId) throw forbidden("comment")
        comments.delete(comment)
    }
}

@RestController
class ArticleController(private val service: ArticleService, private val auth: Auth) {
    @GetMapping("/api/articles")
    fun list(
        req: HttpServletRequest,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) author: String?,
        @RequestParam(required = false) favorited: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): Json = service.list(ArticleFilter(tag, author, favorited), limit, offset, auth.optional(req))

    @GetMapping("/api/articles/feed")
    fun feed(
        req: HttpServletRequest,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): Json {
        val userId = auth.required(req)
        return service.list(ArticleFilter(feedOf = userId), limit, offset, userId)
    }

    @PostMapping("/api/articles")
    fun create(req: HttpServletRequest, @RequestBody(required = false) body: Json?): ResponseEntity<Json> {
        val userId = auth.required(req)
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId, body))
    }

    @GetMapping("/api/articles/{slug}")
    fun get(req: HttpServletRequest, @PathVariable slug: String): Json = service.get(slug, auth.optional(req))

    @PutMapping("/api/articles/{slug}")
    fun update(req: HttpServletRequest, @PathVariable slug: String, @RequestBody(required = false) body: Json?): Json =
        service.update(slug, auth.required(req), body)

    @DeleteMapping("/api/articles/{slug}")
    fun delete(req: HttpServletRequest, @PathVariable slug: String): ResponseEntity<Void> {
        service.delete(slug, auth.required(req))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/articles/{slug}/favorite")
    fun favorite(req: HttpServletRequest, @PathVariable slug: String): Json =
        service.favorite(slug, auth.required(req), true)

    @DeleteMapping("/api/articles/{slug}/favorite")
    fun unfavorite(req: HttpServletRequest, @PathVariable slug: String): Json =
        service.favorite(slug, auth.required(req), false)

    @GetMapping("/api/articles/{slug}/comments")
    fun comments(req: HttpServletRequest, @PathVariable slug: String): Json =
        service.comments(slug, auth.optional(req))

    @PostMapping("/api/articles/{slug}/comments")
    fun addComment(req: HttpServletRequest, @PathVariable slug: String, @RequestBody(required = false) body: Json?): ResponseEntity<Json> {
        val userId = auth.required(req)
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addComment(slug, userId, body))
    }

    @PutMapping("/api/articles/{slug}/comments/{id}")
    fun updateComment(
        req: HttpServletRequest,
        @PathVariable slug: String,
        @PathVariable id: String,
        @RequestBody(required = false) body: Json?,
    ): Json {
        val userId = auth.required(req)
        return service.updateComment(slug, id.toLongOrNull() ?: throw notFound("comment"), userId, body)
    }

    @DeleteMapping("/api/articles/{slug}/comments/{id}")
    fun deleteComment(req: HttpServletRequest, @PathVariable slug: String, @PathVariable id: String): ResponseEntity<Void> {
        val userId = auth.required(req)
        service.deleteComment(slug, id.toLongOrNull() ?: throw notFound("comment"), userId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/api/tags")
    fun tags(): Json = service.tags()
}
