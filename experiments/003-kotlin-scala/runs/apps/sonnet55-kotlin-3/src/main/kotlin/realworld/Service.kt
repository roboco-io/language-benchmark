package realworld

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.EntityManager
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

private fun now() = System.currentTimeMillis()
private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

private fun JsonNode?.text(): String? = if (this == null || isNull) null else asText()

/** Returns the wrapper object `root[key]` or fails with 422 when it is missing or not an object. */
fun requireObject(root: JsonNode?, key: String): JsonNode {
    val node = root?.get(key)
    if (node == null || !node.isObject) throw ApiException(422, key, "can't be blank")
    return node
}

private fun checkPassword(pw: String?, errors: MutableMap<String, MutableList<String>>) {
    if (pw.isNullOrEmpty()) errors.getOrPut("password") { mutableListOf() }.add("can't be blank")
    else if (pw.length < 8) errors.getOrPut("password") { mutableListOf() }.add("is too short (minimum is 8 characters)")
}

private fun fail(errors: Map<String, List<String>>, status: Int = 422) {
    if (errors.isNotEmpty()) throw ApiException(status, errors)
}

@Service
class UserService(
    private val users: UserRepository,
    private val follows: FollowRepository,
    private val jwt: JwtService,
    private val passwords: PasswordService,
) {
    fun userJson(u: User): Map<String, Any?> = mapOf(
        "user" to mapOf(
            "email" to u.email, "username" to u.username, "bio" to u.bio, "image" to u.image,
            "token" to jwt.create(u.id),
        )
    )

    fun profileJson(u: User, viewer: User?): Map<String, Any?> = mapOf("profile" to profile(u, viewer))

    fun profile(u: User, viewer: User?): Map<String, Any?> = mapOf(
        "username" to u.username, "bio" to u.bio, "image" to u.image,
        "following" to (viewer != null && follows.existsByFollowerIdAndFolloweeId(viewer.id, u.id)),
    )

    @Transactional
    fun register(body: JsonNode?): Map<String, Any?> {
        val n = requireObject(body, "user")
        val username = n["username"].text()
        val email = n["email"].text()
        val password = n["password"].text()
        val errors = linkedMapOf<String, MutableList<String>>()
        if (username.isNullOrBlank()) errors["username"] = mutableListOf("can't be blank")
        if (email.isNullOrBlank()) errors["email"] = mutableListOf("can't be blank")
        checkPassword(password, errors)
        fail(errors)
        if (users.findByUsername(username!!) != null) errors["username"] = mutableListOf("has already been taken")
        if (users.findByEmail(email!!) != null) errors["email"] = mutableListOf("has already been taken")
        fail(errors, 409)
        val user = try {
            users.saveAndFlush(User(username, email, passwords.hash(password!!)))
        } catch (e: DataIntegrityViolationException) {
            throw ApiException(409, "username", "has already been taken")
        }
        return userJson(user)
    }

    fun login(body: JsonNode?): Map<String, Any?> {
        val n = requireObject(body, "user")
        val email = n["email"].text()
        val password = n["password"].text()
        val errors = linkedMapOf<String, MutableList<String>>()
        if (email.isNullOrBlank()) errors["email"] = mutableListOf("can't be blank")
        if (password.isNullOrEmpty()) errors["password"] = mutableListOf("can't be blank")
        fail(errors)
        val user = users.findByEmail(email!!)
        if (user == null || !passwords.matches(password!!, user.passwordHash)) {
            throw ApiException(401, "credentials", "invalid")
        }
        return userJson(user)
    }

    @Transactional
    fun update(current: User, body: JsonNode?): Map<String, Any?> {
        val n = requireObject(body, "user")
        val user = users.findById(current.id).get()
        val errors = linkedMapOf<String, MutableList<String>>()
        for (f in listOf("username", "email")) {
            if (n.has(f) && n[f].text().isNullOrBlank()) errors[f] = mutableListOf("can't be blank")
        }
        if (n.has("password")) checkPassword(n["password"].text(), errors)
        fail(errors)
        if (n.has("username")) {
            val v = n["username"].asText()
            if (v != user.username && users.findByUsername(v) != null) errors["username"] = mutableListOf("has already been taken")
        }
        if (n.has("email")) {
            val v = n["email"].asText()
            if (v != user.email && users.findByEmail(v) != null) errors["email"] = mutableListOf("has already been taken")
        }
        fail(errors, 409)
        if (n.has("username")) user.username = n["username"].asText()
        if (n.has("email")) user.email = n["email"].asText()
        if (n.has("password")) user.passwordHash = passwords.hash(n["password"].asText())
        if (n.has("bio")) user.bio = n["bio"].text()?.ifEmpty { null }
        if (n.has("image")) user.image = n["image"].text()?.ifEmpty { null }
        try {
            users.saveAndFlush(user)
        } catch (e: DataIntegrityViolationException) {
            throw ApiException(409, "username", "has already been taken")
        }
        return userJson(user)
    }

    private fun target(username: String) =
        users.findByUsername(username) ?: throw ApiException(404, "profile", "not found")

    fun getProfile(username: String, viewer: User?) = profileJson(target(username), viewer)

    @Transactional
    fun follow(viewer: User, username: String): Map<String, Any?> {
        val u = target(username)
        if (!follows.existsByFollowerIdAndFolloweeId(viewer.id, u.id)) follows.save(Follow(viewer.id, u.id))
        return profileJson(u, viewer)
    }

    @Transactional
    fun unfollow(viewer: User, username: String): Map<String, Any?> {
        val u = target(username)
        follows.deleteByFollowerIdAndFolloweeId(viewer.id, u.id)
        return profileJson(u, viewer)
    }
}

@Service
class ArticleService(
    private val articles: ArticleRepository,
    private val comments: CommentRepository,
    private val favorites: FavoriteRepository,
    private val userService: UserService,
    private val em: EntityManager,
) {
    private fun slugify(title: String): String {
        val s = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
        return s.ifEmpty { "article" }
    }

    private fun uniqueSlug(title: String, selfId: Long? = null): String {
        val base = slugify(title)
        var slug = base
        while (articles.findBySlug(slug)?.let { it.id != selfId } == true) {
            slug = base + "-" + UUID.randomUUID().toString().substring(0, 8)
        }
        return slug
    }

    private fun find(slug: String) = articles.findBySlug(slug) ?: throw ApiException(404, "article", "not found")

    fun view(a: Article, viewer: User?, withBody: Boolean = true): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "slug" to a.slug, "title" to a.title, "description" to a.description,
        )
        if (withBody) m["body"] = a.body
        m["tagList"] = a.tags.toList()
        m["createdAt"] = iso(a.createdAt)
        m["updatedAt"] = iso(a.updatedAt)
        m["favorited"] = viewer != null && favorites.existsByUserIdAndArticleId(viewer.id, a.id)
        m["favoritesCount"] = favorites.countByArticleId(a.id)
        m["author"] = userService.profile(a.author, viewer)
        return m
    }

    private fun single(a: Article, viewer: User?) = mapOf("article" to view(a, viewer))

    @Transactional
    fun create(author: User, body: JsonNode?): Map<String, Any?> {
        val n = requireObject(body, "article")
        val errors = linkedMapOf<String, MutableList<String>>()
        for (f in listOf("title", "description", "body")) {
            if (n[f].text().isNullOrBlank()) errors[f] = mutableListOf("can't be blank")
        }
        fail(errors)
        val tagNode = n["tagList"]
        if (tagNode != null && !tagNode.isNull && !tagNode.isArray) throw ApiException(422, "tagList", "is invalid")
        val ts = now()
        val a = Article(uniqueSlug(n["title"].asText()), n["title"].asText(), n["description"].asText(), n["body"].asText(), author, ts, ts)
        if (tagNode != null && tagNode.isArray) a.tags = tagNode.map { it.asText() }.distinct().toMutableList()
        return single(articles.saveAndFlush(a), author)
    }

    fun get(slug: String, viewer: User?) = single(find(slug), viewer)

    @Transactional
    fun update(user: User, slug: String, body: JsonNode?): Map<String, Any?> {
        val a = find(slug)
        if (a.author.id != user.id) throw ApiException(403, "article", "forbidden")
        val n = requireObject(body, "article")
        val errors = linkedMapOf<String, MutableList<String>>()
        for (f in listOf("title", "description", "body")) {
            if (n.has(f) && n[f].text().isNullOrBlank()) errors[f] = mutableListOf("can't be blank")
        }
        if (n.has("tagList") && !n["tagList"].isArray) errors["tagList"] = mutableListOf("is invalid")
        fail(errors)
        if (n.has("title") && n["title"].asText() != a.title) {
            a.title = n["title"].asText()
            a.slug = uniqueSlug(a.title, a.id)
        }
        if (n.has("description")) a.description = n["description"].asText()
        if (n.has("body")) a.body = n["body"].asText()
        if (n.has("tagList")) {
            a.tags.clear()
            a.tags.addAll(n["tagList"].map { it.asText() }.distinct())
        }
        a.updatedAt = maxOf(now(), a.updatedAt + 1)
        return single(articles.saveAndFlush(a), user)
    }

    @Transactional
    fun delete(user: User, slug: String) {
        val a = find(slug)
        if (a.author.id != user.id) throw ApiException(403, "article", "forbidden")
        comments.deleteByArticleId(a.id)
        favorites.deleteByArticleId(a.id)
        articles.delete(a)
    }

    private fun page(where: String, params: Map<String, Any>, limit: Int, offset: Int, viewer: User?): Map<String, Any?> {
        val q = em.createQuery("select a from Article a $where order by a.createdAt desc, a.id desc", Article::class.java)
        val c = em.createQuery("select count(a) from Article a $where", java.lang.Long::class.java)
        params.forEach { (k, v) -> q.setParameter(k, v); c.setParameter(k, v) }
        q.firstResult = offset.coerceAtLeast(0)
        q.maxResults = limit.coerceAtLeast(0)
        val list = q.resultList.map { view(it, viewer, false) }
        return mapOf("articles" to list, "articlesCount" to c.singleResult.toLong())
    }

    fun list(tag: String?, author: String?, favorited: String?, limit: Int, offset: Int, viewer: User?): Map<String, Any?> {
        val conds = mutableListOf<String>()
        val params = linkedMapOf<String, Any>()
        if (!tag.isNullOrEmpty()) { conds += ":tag member of a.tags"; params["tag"] = tag }
        if (!author.isNullOrEmpty()) { conds += "a.author.username = :author"; params["author"] = author }
        if (!favorited.isNullOrEmpty()) {
            conds += "exists (select f.id from Favorite f, User u where f.articleId = a.id and f.userId = u.id and u.username = :fav)"
            params["fav"] = favorited
        }
        val where = if (conds.isEmpty()) "" else "where " + conds.joinToString(" and ")
        return page(where, params, limit, offset, viewer)
    }

    fun feed(viewer: User, limit: Int, offset: Int) = page(
        "where a.author.id in (select f.followeeId from Follow f where f.followerId = :uid)",
        mapOf("uid" to viewer.id), limit, offset, viewer,
    )

    @Transactional
    fun favorite(user: User, slug: String, on: Boolean): Map<String, Any?> {
        val a = find(slug)
        if (on) {
            if (!favorites.existsByUserIdAndArticleId(user.id, a.id)) favorites.saveAndFlush(Favorite(user.id, a.id))
        } else {
            favorites.deleteByUserIdAndArticleId(user.id, a.id)
        }
        return single(a, user)
    }

    fun tags(): Map<String, Any?> = mapOf(
        "tags" to em.createQuery("select distinct t from Article a join a.tags t order by t", String::class.java).resultList
    )

    private fun commentJson(c: Comment, viewer: User?) = mapOf(
        "id" to c.id, "createdAt" to iso(c.createdAt), "updatedAt" to iso(c.updatedAt),
        "body" to c.body, "author" to userService.profile(c.author, viewer),
    )

    fun listComments(slug: String, viewer: User?): Map<String, Any?> {
        val a = find(slug)
        return mapOf("comments" to comments.findByArticleIdOrderByIdAsc(a.id).map { commentJson(it, viewer) })
    }

    @Transactional
    fun addComment(user: User, slug: String, body: JsonNode?): Map<String, Any?> {
        val a = find(slug)
        val n = requireObject(body, "comment")
        if (n["body"].text().isNullOrBlank()) throw ApiException(422, "body", "can't be blank")
        val ts = now()
        return mapOf("comment" to commentJson(comments.saveAndFlush(Comment(a, user, n["body"].asText(), ts, ts)), user))
    }

    @Transactional
    fun deleteComment(user: User, slug: String, id: Long) {
        val a = find(slug)
        val c = comments.findById(id).orElse(null)?.takeIf { it.article.id == a.id }
            ?: throw ApiException(404, "comment", "not found")
        if (c.author.id != user.id) throw ApiException(403, "comment", "forbidden")
        comments.delete(c)
    }
}
