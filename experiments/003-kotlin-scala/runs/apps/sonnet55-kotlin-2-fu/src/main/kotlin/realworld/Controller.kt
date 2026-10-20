package realworld

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.EntityManager
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@RestController
@RequestMapping("/api")
class ApiController(
    private val users: UserRepository,
    private val articles: ArticleRepository,
    private val comments: CommentRepository,
    private val favorites: FavoriteRepository,
    private val follows: FollowRepository,
    private val em: EntityManager,
    private val passwords: Passwords,
    private val tokens: Tokens,
    txManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(txManager)
    // SQLite allows a single writer; serializing writes in-process avoids SQLITE_BUSY on read-to-write upgrades.
    private val writeLock = ReentrantLock()

    private fun <T : Any> read(f: () -> T): T = tx.execute { f() }!!
    private fun <T : Any> write(f: () -> T): T = writeLock.withLock { tx.execute { f() }!! }

    // ---------- helpers ----------

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun nextTimestamp(previous: Long = 0): Long = maxOf(System.currentTimeMillis(), previous + 1)

    private fun tokenFrom(header: String?): String? {
        if (header == null) return null
        val parts = header.trim().split(" ", limit = 2)
        return if (parts.size == 2 && (parts[0] == "Token" || parts[0] == "Bearer")) parts[1].trim() else null
    }

    private fun optionalUser(header: String?): User? {
        val raw = tokenFrom(header) ?: return null
        val id = tokens.parse(raw) ?: return null
        return users.findById(id).orElse(null)
    }

    private fun requireUser(header: String?): User {
        if (header.isNullOrBlank()) throw ApiException(HttpStatus.UNAUTHORIZED, "token", "is missing")
        val raw = tokenFrom(header) ?: throw ApiException(HttpStatus.UNAUTHORIZED, "token", "is invalid")
        val id = tokens.parse(raw) ?: throw ApiException(HttpStatus.UNAUTHORIZED, "token", "is invalid")
        return users.findById(id).orElseThrow { ApiException(HttpStatus.UNAUTHORIZED, "token", "is invalid") }
    }

    private fun userJson(u: User) = mapOf(
        "user" to linkedMapOf(
            "email" to u.email, "token" to tokens.create(u.id!!), "username" to u.username,
            "bio" to u.bio, "image" to u.image,
        )
    )

    private fun profileMap(u: User, viewer: User?) = linkedMapOf(
        "username" to u.username, "bio" to u.bio, "image" to u.image,
        "following" to (viewer != null && follows.existsByFollowerIdAndFolloweeId(viewer.id!!, u.id!!)),
    )

    private fun articleMap(a: Article, viewer: User?, authors: MutableMap<Long, User> = mutableMapOf()): Map<String, Any?> {
        val author = authors.getOrPut(a.authorId) { users.findById(a.authorId).get() }
        return linkedMapOf(
            "slug" to a.slug, "title" to a.title, "description" to a.description, "body" to a.body,
            "tagList" to a.tagList.toList(), "createdAt" to iso(a.createdAt), "updatedAt" to iso(a.updatedAt),
            "favorited" to (viewer != null && favorites.existsByUserIdAndArticleId(viewer.id!!, a.id!!)),
            "favoritesCount" to favorites.countByArticleId(a.id!!),
            "author" to profileMap(author, viewer),
        )
    }

    private fun commentMap(c: Comment, viewer: User?, authors: MutableMap<Long, User> = mutableMapOf()): Map<String, Any?> {
        val author = authors.getOrPut(c.authorId) { users.findById(c.authorId).get() }
        return linkedMapOf(
            "id" to c.id, "createdAt" to iso(c.createdAt), "updatedAt" to iso(c.updatedAt),
            "body" to c.body, "author" to profileMap(author, viewer),
        )
    }

    private fun payload(root: JsonNode?, key: String): JsonNode {
        val n = root?.get(key)
        if (n == null || !n.isObject) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, key, "can't be blank")
        return n
    }

    /** Returns the text of a required string field; records an error if missing, null or blank. */
    private fun required(n: JsonNode, field: String, errs: ValidationErrors): String? {
        val v = n.get(field)
        if (v == null || v.isNull || !v.isTextual || v.asText().isBlank()) {
            errs.add(field, "can't be blank"); return null
        }
        return v.asText()
    }

    private fun slugify(title: String): String {
        val base = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(80).trim('-')
        return (base.ifEmpty { "article" }) + "-" + UUID.randomUUID().toString().substring(0, 8)
    }

    private fun validEmail(e: String) = Regex("^[^@\\s]+@[^@\\s]+$").matches(e)

    private fun articleBySlug(slug: String): Article =
        articles.findBySlug(slug) ?: throw ApiException(HttpStatus.NOT_FOUND, "article", "not found")

    private fun userByName(name: String): User =
        users.findByUsername(name) ?: throw ApiException(HttpStatus.NOT_FOUND, "profile", "not found")

    private fun checkPassword(p: String, errs: ValidationErrors) {
        if (p.length < 8) errs.add("password", "is too short (minimum is 8 characters)")
    }

    // ---------- users ----------

    @PostMapping("/users")
    fun register(@RequestBody(required = false) body: JsonNode?): ResponseEntity<Any> {
        val n = payload(body, "user")
        val errs = ValidationErrors()
        val username = required(n, "username", errs)
        val email = required(n, "email", errs)
        val password = required(n, "password", errs)
        if (email != null && !validEmail(email)) errs.add("email", "is invalid")
        if (password != null) checkPassword(password, errs)
        errs.check()
        val hash = passwords.hash(password!!)
        val user = write {
            if (users.existsByUsername(username!!)) throw ApiException(HttpStatus.CONFLICT, "username", "has already been taken")
            if (users.existsByEmail(email!!)) throw ApiException(HttpStatus.CONFLICT, "email", "has already been taken")
            users.save(User(username = username, email = email, passwordHash = hash))
        }
        return ResponseEntity.status(201).body(userJson(user))
    }

    @PostMapping("/users/login")
    fun login(@RequestBody(required = false) body: JsonNode?): Any {
        val n = payload(body, "user")
        val errs = ValidationErrors()
        val email = required(n, "email", errs)
        val password = required(n, "password", errs)
        errs.check()
        val user = tx.execute { users.findByEmail(email!!) }
        if (user == null || !passwords.matches(password!!, user.passwordHash))
            throw ApiException(HttpStatus.UNAUTHORIZED, "credentials", "invalid")
        return userJson(user)
    }

    @GetMapping("/user")
    fun currentUser(@RequestHeader("Authorization", required = false) auth: String?): Any =
        read { userJson(requireUser(auth)) }

    @PutMapping("/user")
    fun updateUser(@RequestHeader("Authorization", required = false) auth: String?, @RequestBody(required = false) body: JsonNode?): Any {
        read { requireUser(auth) }
        val n = payload(body, "user")
        val errs = ValidationErrors()
        fun present(f: String) = n.has(f)
        val newUsername = if (present("username")) required(n, "username", errs) else null
        val newEmail = if (present("email")) required(n, "email", errs) else null
        val newPassword = if (present("password")) required(n, "password", errs) else null
        if (newEmail != null && !validEmail(newEmail)) errs.add("email", "is invalid")
        if (newPassword != null) checkPassword(newPassword, errs)
        for (f in listOf("bio", "image")) {
            val v = n.get(f)
            if (v != null && !v.isNull && !v.isTextual) errs.add(f, "is invalid")
        }
        errs.check()
        val hash = newPassword?.let { passwords.hash(it) }
        return write {
            val user = requireUser(auth)
            if (newUsername != null && newUsername != user.username) {
                if (users.existsByUsername(newUsername)) throw ApiException(HttpStatus.CONFLICT, "username", "has already been taken")
                user.username = newUsername
            }
            if (newEmail != null && newEmail != user.email) {
                if (users.existsByEmail(newEmail)) throw ApiException(HttpStatus.CONFLICT, "email", "has already been taken")
                user.email = newEmail
            }
            if (hash != null) user.passwordHash = hash
            if (n.has("bio")) user.bio = n.get("bio").takeUnless { it.isNull }?.asText()?.ifEmpty { null }
            if (n.has("image")) user.image = n.get("image").takeUnless { it.isNull }?.asText()?.ifEmpty { null }
            userJson(users.save(user))
        }
    }

    // ---------- profiles ----------

    @GetMapping("/profiles/{username}")
    fun profile(@PathVariable username: String, @RequestHeader("Authorization", required = false) auth: String?): Any = read {
        mapOf("profile" to profileMap(userByName(username), optionalUser(auth)))
    }

    @PostMapping("/profiles/{username}/follow")
    fun follow(@PathVariable username: String, @RequestHeader("Authorization", required = false) auth: String?): Any = write {
        val me = requireUser(auth)
        val target = userByName(username)
        if (!follows.existsByFollowerIdAndFolloweeId(me.id!!, target.id!!))
            follows.save(Follow(followerId = me.id!!, followeeId = target.id!!))
        mapOf("profile" to profileMap(target, me))
    }

    @DeleteMapping("/profiles/{username}/follow")
    fun unfollow(@PathVariable username: String, @RequestHeader("Authorization", required = false) auth: String?): Any = write {
        val me = requireUser(auth)
        val target = userByName(username)
        follows.deleteByFollowerIdAndFolloweeId(me.id!!, target.id!!)
        mapOf("profile" to profileMap(target, me))
    }

    // ---------- articles ----------

    private fun listArticles(where: List<String>, params: Map<String, Any>, limit: Int, offset: Int, viewer: User?): Any {
        val clause = if (where.isEmpty()) "" else " where " + where.joinToString(" and ")
        val countQ = em.createQuery("select count(a) from Article a$clause", java.lang.Long::class.java)
        val listQ = em.createQuery("select a from Article a$clause order by a.createdAt desc, a.id desc", Article::class.java)
        params.forEach { (k, v) -> countQ.setParameter(k, v); listQ.setParameter(k, v) }
        val total = countQ.singleResult.toLong()
        val authors = mutableMapOf<Long, User>()
        val items = listQ.setFirstResult(offset).setMaxResults(limit).resultList.map { a ->
            articleMap(a, viewer, authors).filterKeys { it != "body" }
        }
        return linkedMapOf("articles" to items, "articlesCount" to total)
    }

    private fun intParam(v: String?, default: Int, min: Int, max: Int): Int =
        (v?.toIntOrNull() ?: default).coerceIn(min, max)

    @GetMapping("/articles")
    fun list(
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) author: String?,
        @RequestParam(required = false) favorited: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) offset: String?,
        @RequestHeader("Authorization", required = false) auth: String?,
    ): Any = read {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (!tag.isNullOrEmpty()) { where += ":tag member of a.tagList"; params["tag"] = tag }
        if (!author.isNullOrEmpty()) { where += "a.authorId in (select u.id from User u where u.username = :author)"; params["author"] = author }
        if (!favorited.isNullOrEmpty()) {
            where += "a.id in (select f.articleId from Favorite f where f.userId in (select u2.id from User u2 where u2.username = :fav))"
            params["fav"] = favorited
        }
        listArticles(where, params, intParam(limit, 20, 0, 1000), intParam(offset, 0, 0, Int.MAX_VALUE), optionalUser(auth))
    }

    @GetMapping("/articles/feed")
    fun feed(
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) offset: String?,
        @RequestHeader("Authorization", required = false) auth: String?,
    ): Any = read {
        val me = requireUser(auth)
        listArticles(
            listOf("a.authorId in (select fo.followeeId from Follow fo where fo.followerId = :me)"),
            mapOf("me" to me.id!!), intParam(limit, 20, 0, 1000), intParam(offset, 0, 0, Int.MAX_VALUE), me,
        )
    }

    @GetMapping("/articles/{slug}")
    fun get(@PathVariable slug: String, @RequestHeader("Authorization", required = false) auth: String?): Any = read {
        mapOf("article" to articleMap(articleBySlug(slug), optionalUser(auth)))
    }

    private fun tagsFrom(n: JsonNode, errs: ValidationErrors): MutableList<String>? {
        val t = n.get("tagList")
        if (t == null) return null
        if (!t.isArray || t.any { !it.isTextual }) { errs.add("tagList", "is invalid"); return null }
        return t.map { it.asText() }.distinct().toMutableList()
    }

    @PostMapping("/articles")
    fun create(@RequestHeader("Authorization", required = false) auth: String?, @RequestBody(required = false) body: JsonNode?): ResponseEntity<Any> {
        read { requireUser(auth) }
        val n = payload(body, "article")
        val errs = ValidationErrors()
        val title = required(n, "title", errs)
        val description = required(n, "description", errs)
        val text = required(n, "body", errs)
        val tags = tagsFrom(n, errs)
        errs.check()
        val result = write {
            val me = requireUser(auth)
            val now = nextTimestamp()
            val a = articles.save(
                Article(
                    slug = slugify(title!!), title = title, description = description!!, body = text!!,
                    authorId = me.id!!, createdAt = now, updatedAt = now, tagList = tags ?: mutableListOf(),
                )
            )
            mapOf("article" to articleMap(a, me))
        }
        return ResponseEntity.status(201).body(result)
    }

    @PutMapping("/articles/{slug}")
    fun update(@PathVariable slug: String, @RequestHeader("Authorization", required = false) auth: String?, @RequestBody(required = false) body: JsonNode?): Any {
        read { requireUser(auth) }
        val n = payload(body, "article")
        val errs = ValidationErrors()
        val title = if (n.has("title")) required(n, "title", errs) else null
        val description = if (n.has("description")) required(n, "description", errs) else null
        val text = if (n.has("body")) required(n, "body", errs) else null
        val tags = tagsFrom(n, errs)
        errs.check()
        return write {
            val me = requireUser(auth)
            val a = articleBySlug(slug)
            if (a.authorId != me.id) throw ApiException(HttpStatus.FORBIDDEN, "article", "forbidden")
            if (title != null) a.title = title
            if (description != null) a.description = description
            if (text != null) a.body = text
            if (tags != null) { a.tagList.clear(); a.tagList.addAll(tags) }
            a.updatedAt = nextTimestamp(a.updatedAt)
            mapOf("article" to articleMap(articles.save(a), me))
        }
    }

    @DeleteMapping("/articles/{slug}")
    fun delete(@PathVariable slug: String, @RequestHeader("Authorization", required = false) auth: String?): ResponseEntity<Void> {
        read { requireUser(auth) }
        write {
            val me = requireUser(auth)
            val a = articleBySlug(slug)
            if (a.authorId != me.id) throw ApiException(HttpStatus.FORBIDDEN, "article", "forbidden")
            comments.deleteByArticleId(a.id!!)
            favorites.deleteByArticleId(a.id!!)
            articles.delete(a)
        }
        return ResponseEntity.noContent().build()
    }

    // ---------- comments ----------

    @GetMapping("/articles/{slug}/comments")
    fun listComments(@PathVariable slug: String, @RequestHeader("Authorization", required = false) auth: String?): Any = read {
        val a = articleBySlug(slug)
        val viewer = optionalUser(auth)
        val authors = mutableMapOf<Long, User>()
        mapOf("comments" to comments.findByArticleIdOrderByIdAsc(a.id!!).map { commentMap(it, viewer, authors) })
    }

    @PostMapping("/articles/{slug}/comments")
    fun addComment(@PathVariable slug: String, @RequestHeader("Authorization", required = false) auth: String?, @RequestBody(required = false) body: JsonNode?): ResponseEntity<Any> {
        read { requireUser(auth) }
        val n = payload(body, "comment")
        val errs = ValidationErrors()
        val text = required(n, "body", errs)
        errs.check()
        val result = write {
            val me = requireUser(auth)
            val a = articleBySlug(slug)
            val now = nextTimestamp()
            val c = comments.save(Comment(articleId = a.id!!, authorId = me.id!!, body = text!!, createdAt = now, updatedAt = now))
            mapOf("comment" to commentMap(c, me))
        }
        return ResponseEntity.status(201).body(result)
    }

    @PutMapping("/articles/{slug}/comments/{id}")
    fun updateComment(@PathVariable slug: String, @PathVariable id: String, @RequestHeader("Authorization", required = false) auth: String?, @RequestBody(required = false) body: JsonNode?): Any {
        read { requireUser(auth) }
        val n = payload(body, "comment")
        val errs = ValidationErrors()
        val text = required(n, "body", errs)
        errs.check()
        return write {
            val me = requireUser(auth)
            val a = articleBySlug(slug)
            val c = id.toLongOrNull()?.let { comments.findById(it).orElse(null) }
            if (c == null || c.articleId != a.id) throw ApiException(HttpStatus.NOT_FOUND, "comment", "not found")
            if (c.authorId != me.id) throw ApiException(HttpStatus.FORBIDDEN, "comment", "forbidden")
            c.body = text!!
            c.updatedAt = nextTimestamp(c.updatedAt)
            mapOf("comment" to commentMap(comments.save(c), me))
        }
    }

    @DeleteMapping("/articles/{slug}/comments/{id}")
    fun deleteComment(@PathVariable slug: String, @PathVariable id: String, @RequestHeader("Authorization", required = false) auth: String?): ResponseEntity<Void> {
        read { requireUser(auth) }
        write {
            val me = requireUser(auth)
            val a = articleBySlug(slug)
            val c = id.toLongOrNull()?.let { comments.findById(it).orElse(null) }
            if (c == null || c.articleId != a.id) throw ApiException(HttpStatus.NOT_FOUND, "comment", "not found")
            if (c.authorId != me.id) throw ApiException(HttpStatus.FORBIDDEN, "comment", "forbidden")
            comments.delete(c)
        }
        return ResponseEntity.noContent().build()
    }

    // ---------- favorites ----------

    @PostMapping("/articles/{slug}/favorite")
    fun favorite(@PathVariable slug: String, @RequestHeader("Authorization", required = false) auth: String?): Any = write {
        val me = requireUser(auth)
        val a = articleBySlug(slug)
        if (!favorites.existsByUserIdAndArticleId(me.id!!, a.id!!)) favorites.save(Favorite(userId = me.id!!, articleId = a.id!!))
        mapOf("article" to articleMap(a, me))
    }

    @DeleteMapping("/articles/{slug}/favorite")
    fun unfavorite(@PathVariable slug: String, @RequestHeader("Authorization", required = false) auth: String?): Any = write {
        val me = requireUser(auth)
        val a = articleBySlug(slug)
        favorites.deleteByUserIdAndArticleId(me.id!!, a.id!!)
        mapOf("article" to articleMap(a, me))
    }

    // ---------- tags ----------

    @GetMapping("/tags")
    fun tags(): Any = read { mapOf("tags" to articles.allTags()) }
}
