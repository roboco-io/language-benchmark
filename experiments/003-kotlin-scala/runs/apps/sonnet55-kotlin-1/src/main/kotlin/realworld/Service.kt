package realworld

import jakarta.persistence.EntityManager
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private fun notFound(field: String) = ApiException(HttpStatus.NOT_FOUND, field, "not found")
private fun forbidden(field: String) = ApiException(HttpStatus.FORBIDDEN, field, "forbidden")
private fun now(): Instant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
private fun blank(v: String?) = v == null || v.isBlank()

@Service
class RealWorldService(
    private val users: UserRepository,
    private val articles: ArticleRepository,
    private val comments: CommentRepository,
    private val follows: FollowRepository,
    private val favorites: FavoriteRepository,
    private val jwt: Jwt,
    private val em: EntityManager,
    txm: PlatformTransactionManager,
) {
    private val encoder = BCryptPasswordEncoder(10)
    private val random = SecureRandom()
    private val writeTx = TransactionTemplate(txm)
    private val readTx = TransactionTemplate(txm).apply { isReadOnly = true }

    // SQLite allows a single writer. Serialising writes in-process avoids SQLITE_BUSY on
    // read-to-write lock upgrades; busy_timeout still covers contention from other processes.
    private val writeLock = ReentrantLock()

    private fun <T> write(block: () -> T): T = writeLock.withLock { writeTx.execute { block() }!! }
    private fun <T> read(block: () -> T): T = readTx.execute { block() }!!

    // ---- users ----

    fun userJson(u: User) = mapOf(
        "user" to mapOf(
            "email" to u.email, "username" to u.username, "bio" to u.bio, "image" to u.image,
            "token" to jwt.create(u.id),
        )
    )

    fun authenticate(header: String?): Long? {
        if (header == null) return null
        val token = header.substringAfter(' ', "").trim()
        if (token.isEmpty()) return null
        return jwt.verify(token)
    }

    fun requireAuth(header: String?): Long {
        if (blank(header)) throw ApiException(HttpStatus.UNAUTHORIZED, "token", "is missing")
        val id = authenticate(header) ?: throw ApiException(HttpStatus.UNAUTHORIZED, "token", "is invalid")
        return id
    }

    fun optionalAuth(header: String?): Long? = if (blank(header)) null else authenticate(header)

    private fun validatePassword(p: String?, errors: MutableMap<String, List<String>>) {
        if (blank(p)) errors["password"] = listOf("can't be blank")
        else if (p!!.length < 8) errors["password"] = listOf("is too short (minimum is 8 characters)")
    }

    fun register(username: String?, email: String?, password: String?): Map<String, Any?> {
        val errors = linkedMapOf<String, List<String>>()
        if (blank(username)) errors["username"] = listOf("can't be blank")
        if (blank(email)) errors["email"] = listOf("can't be blank")
        else if (!email!!.contains('@')) errors["email"] = listOf("is invalid")
        validatePassword(password, errors)
        if (errors.isNotEmpty()) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errors)
        val hash = encoder.encode(password)
        return write {
            val dup = linkedMapOf<String, List<String>>()
            if (users.existsByUsername(username!!)) dup["username"] = listOf("has already been taken")
            if (users.existsByEmail(email!!)) dup["email"] = listOf("has already been taken")
            if (dup.isNotEmpty()) throw ApiException(HttpStatus.CONFLICT, dup)
            userJson(users.save(User(email = email, username = username, passwordHash = hash)))
        }
    }

    fun login(email: String?, password: String?): Map<String, Any?> {
        val errors = linkedMapOf<String, List<String>>()
        if (blank(email)) errors["email"] = listOf("can't be blank")
        if (blank(password)) errors["password"] = listOf("can't be blank")
        if (errors.isNotEmpty()) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errors)
        val u = read { users.findByEmail(email!!) }
        if (u == null || !encoder.matches(password, u.passwordHash)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "credentials", "invalid")
        }
        return userJson(u)
    }

    fun currentUser(id: Long): Map<String, Any?> = read {
        userJson(users.findById(id).orElseThrow { ApiException(HttpStatus.UNAUTHORIZED, "token", "is invalid") })
    }

    fun updateUser(id: Long, fields: Map<String, Any?>): Map<String, Any?> {
        val errors = linkedMapOf<String, List<String>>()
        for (f in listOf("username", "email")) {
            if (fields.containsKey(f) && (fields[f] !is String || blank(fields[f] as String))) {
                errors[f] = listOf("can't be blank")
            }
        }
        if (fields.containsKey("email") && fields["email"] is String && !(fields["email"] as String).contains('@')) {
            errors["email"] = listOf("is invalid")
        }
        if (fields.containsKey("password")) validatePassword(fields["password"] as? String, errors)
        if (errors.isNotEmpty()) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errors)
        val hash = (fields["password"] as? String)?.let { encoder.encode(it) }
        return write {
            val u = users.findById(id).orElseThrow { ApiException(HttpStatus.UNAUTHORIZED, "token", "is invalid") }
            val dup = linkedMapOf<String, List<String>>()
            (fields["username"] as? String)?.let {
                if (it != u.username && users.existsByUsername(it)) dup["username"] = listOf("has already been taken")
            }
            (fields["email"] as? String)?.let {
                if (it != u.email && users.existsByEmail(it)) dup["email"] = listOf("has already been taken")
            }
            if (dup.isNotEmpty()) throw ApiException(HttpStatus.CONFLICT, dup)
            (fields["username"] as? String)?.let { u.username = it }
            (fields["email"] as? String)?.let { u.email = it }
            if (hash != null) u.passwordHash = hash
            if (fields.containsKey("bio")) u.bio = (fields["bio"] as? String)?.ifEmpty { null }
            if (fields.containsKey("image")) u.image = (fields["image"] as? String)?.ifEmpty { null }
            userJson(users.save(u))
        }
    }

    // ---- profiles ----

    private fun profile(u: User, viewer: Long?) = mapOf(
        "username" to u.username, "bio" to u.bio, "image" to u.image,
        "following" to (viewer != null && follows.existsByFollowerIdAndFolloweeId(viewer, u.id)),
    )

    fun getProfile(username: String, viewer: Long?): Map<String, Any?> = read {
        val u = users.findByUsername(username) ?: throw notFound("profile")
        mapOf("profile" to profile(u, viewer))
    }

    fun follow(username: String, me: Long, on: Boolean): Map<String, Any?> = write {
        val u = users.findByUsername(username) ?: throw notFound("profile")
        if (on) {
            if (!follows.existsByFollowerIdAndFolloweeId(me, u.id)) follows.save(Follow(followerId = me, followeeId = u.id))
        } else {
            follows.deletePair(me, u.id)
        }
        mapOf("profile" to profile(u, me))
    }

    // ---- articles ----

    private fun article(a: Article, viewer: Long?, withBody: Boolean): Map<String, Any?> {
        val author = users.findById(a.authorId).get()
        val m = linkedMapOf<String, Any?>(
            "slug" to a.slug, "title" to a.title, "description" to a.description,
        )
        if (withBody) m["body"] = a.body
        m["tagList"] = a.tags.sorted()
        m["createdAt"] = a.createdAt
        m["updatedAt"] = a.updatedAt
        m["favorited"] = viewer != null && favorites.existsByUserIdAndArticleId(viewer, a.id)
        m["favoritesCount"] = favorites.countByArticleId(a.id)
        m["author"] = profile(author, viewer)
        return m
    }

    private fun slugify(title: String): String {
        val base = Normalizer.normalize(title, Normalizer.Form.NFKD)
            .replace(Regex("[^\\p{ASCII}]"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return base.ifEmpty { "article" }
    }

    private fun uniqueSlug(title: String): String {
        val base = slugify(title)
        var slug = base
        while (articles.existsBySlug(slug)) slug = "$base-${Integer.toString(random.nextInt(36 * 36 * 36 * 36 * 36), 36)}"
        return slug
    }

    private fun findArticle(slug: String) = articles.findBySlug(slug) ?: throw notFound("article")

    fun createArticle(me: Long, f: Map<String, Any?>): Map<String, Any?> {
        val title = f["title"] as? String
        val description = f["description"] as? String
        val body = f["body"] as? String
        val errors = linkedMapOf<String, List<String>>()
        if (blank(title)) errors["title"] = listOf("can't be blank")
        if (blank(description)) errors["description"] = listOf("can't be blank")
        if (blank(body)) errors["body"] = listOf("can't be blank")
        if (errors.isNotEmpty()) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errors)
        val tags = (f["tagList"] as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
        return write {
            val a = articles.save(
                Article(slug = uniqueSlug(title!!), title = title, description = description!!, body = body!!, authorId = me, tags = tags, createdAt = now(), updatedAt = now())
            )
            mapOf("article" to article(a, me, true))
        }
    }

    fun getArticle(slug: String, viewer: Long?): Map<String, Any?> = read {
        mapOf("article" to article(findArticle(slug), viewer, true))
    }

    fun updateArticle(slug: String, me: Long, f: Map<String, Any?>): Map<String, Any?> {
        val errors = linkedMapOf<String, List<String>>()
        for (k in listOf("title", "description", "body")) {
            if (f.containsKey(k) && (f[k] !is String || blank(f[k] as String))) errors[k] = listOf("can't be blank")
        }
        if (f.containsKey("tagList") && f["tagList"] !is List<*>) errors["tagList"] = listOf("is invalid")
        return write {
            val a = findArticle(slug)
            if (a.authorId != me) throw forbidden("article")
            if (errors.isNotEmpty()) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errors)
            (f["title"] as? String)?.let { a.title = it }
            (f["description"] as? String)?.let { a.description = it }
            (f["body"] as? String)?.let { a.body = it }
            (f["tagList"] as? List<*>)?.let { l ->
                a.tags = l.filterIsInstance<String>().filter { it.isNotBlank() }.toMutableSet()
            }
            a.updatedAt = now()
            articles.save(a)
            mapOf("article" to article(a, me, true))
        }
    }

    fun deleteArticle(slug: String, me: Long) = write {
        val a = findArticle(slug)
        if (a.authorId != me) throw forbidden("article")
        comments.deleteByArticleId(a.id)
        favorites.deleteByArticleId(a.id)
        articles.delete(a)
    }

    private fun page(where: String, params: Map<String, Any?>, limit: Int, offset: Int, viewer: Long?): Map<String, Any?> {
        val countQ = em.createQuery("select count(a) from Article a where $where", java.lang.Long::class.java)
        val listQ = em.createQuery("select a from Article a where $where order by a.createdAt desc, a.id desc", Article::class.java)
        params.forEach { (k, v) -> countQ.setParameter(k, v); listQ.setParameter(k, v) }
        val total = countQ.singleResult.toLong()
        val list = listQ.setFirstResult(offset).setMaxResults(limit).resultList
        return mapOf("articles" to list.map { article(it, viewer, false) }, "articlesCount" to total)
    }

    fun listArticles(tag: String?, author: String?, favorited: String?, limit: Int, offset: Int, viewer: Long?): Map<String, Any?> = read {
        val w = mutableListOf("1=1")
        val p = linkedMapOf<String, Any?>()
        if (!tag.isNullOrEmpty()) { w += ":tag member of a.tags"; p["tag"] = tag }
        if (!author.isNullOrEmpty()) {
            w += "a.authorId in (select u.id from User u where u.username = :author)"; p["author"] = author
        }
        if (!favorited.isNullOrEmpty()) {
            w += "a.id in (select f.articleId from Favorite f where f.userId in (select u.id from User u where u.username = :fav))"
            p["fav"] = favorited
        }
        page(w.joinToString(" and "), p, limit, offset, viewer)
    }

    fun feed(me: Long, limit: Int, offset: Int): Map<String, Any?> = read {
        page("a.authorId in (select f.followeeId from Follow f where f.followerId = :me)", mapOf("me" to me), limit, offset, me)
    }

    fun favorite(slug: String, me: Long, on: Boolean): Map<String, Any?> = write {
        val a = findArticle(slug)
        if (on) {
            if (!favorites.existsByUserIdAndArticleId(me, a.id)) favorites.save(Favorite(userId = me, articleId = a.id))
        } else {
            favorites.deletePair(me, a.id)
        }
        mapOf("article" to article(a, me, true))
    }

    fun tags(): Map<String, Any?> = read { mapOf("tags" to articles.findAllTags()) }

    // ---- comments ----

    private fun comment(c: Comment, viewer: Long?) = mapOf(
        "id" to c.id, "createdAt" to c.createdAt, "updatedAt" to c.updatedAt, "body" to c.body,
        "author" to profile(users.findById(c.authorId).get(), viewer),
    )

    fun addComment(slug: String, me: Long, body: String?): Map<String, Any?> {
        if (blank(body)) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "body", "can't be blank")
        return write {
            val a = findArticle(slug)
            mapOf("comment" to comment(comments.save(Comment(articleId = a.id, authorId = me, body = body!!, createdAt = now(), updatedAt = now())), me))
        }
    }

    fun listComments(slug: String, viewer: Long?): Map<String, Any?> = read {
        val a = findArticle(slug)
        mapOf("comments" to comments.findByArticleIdOrderByCreatedAtAscIdAsc(a.id).map { comment(it, viewer) })
    }

    fun deleteComment(slug: String, id: Long, me: Long) = write {
        val a = findArticle(slug)
        val c = comments.findById(id).orElse(null)?.takeIf { it.articleId == a.id } ?: throw notFound("comment")
        if (c.authorId != me) throw forbidden("comment")
        comments.delete(c)
    }
}
