package realworld

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

private const val auth = "Authorization"

@RestController
@RequestMapping("/api")
class ApiController(private val s: RealWorldService) {
    @Suppress("UNCHECKED_CAST")
    private fun inner(body: Map<String, Any?>?, key: String): Map<String, Any?> {
        val v = body?.get(key)
        if (v !is Map<*, *>) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, key, "can't be blank")
        return v as Map<String, Any?>
    }

    @PostMapping("/users")
    fun register(@RequestBody(required = false) body: Map<String, Any?>?): ResponseEntity<Any> {
        val u = inner(body, "user")
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(s.register(u["username"] as? String, u["email"] as? String, u["password"] as? String))
    }

    @PostMapping("/users/login")
    fun login(@RequestBody(required = false) body: Map<String, Any?>?): Any {
        val u = inner(body, "user")
        return s.login(u["email"] as? String, u["password"] as? String)
    }

    @GetMapping("/user")
    fun current(@RequestHeader(auth, required = false) a: String?) = s.currentUser(s.requireAuth(a))

    @PutMapping("/user")
    fun update(@RequestHeader(auth, required = false) a: String?, @RequestBody(required = false) body: Map<String, Any?>?): Any {
        val me = s.requireAuth(a)
        return s.updateUser(me, inner(body, "user"))
    }

    @GetMapping("/profiles/{username}")
    fun profile(@PathVariable username: String, @RequestHeader(auth, required = false) a: String?) =
        s.getProfile(username, s.optionalAuth(a))

    @PostMapping("/profiles/{username}/follow")
    fun follow(@PathVariable username: String, @RequestHeader(auth, required = false) a: String?) =
        s.follow(username, s.requireAuth(a), true)

    @DeleteMapping("/profiles/{username}/follow")
    fun unfollow(@PathVariable username: String, @RequestHeader(auth, required = false) a: String?) =
        s.follow(username, s.requireAuth(a), false)

    @GetMapping("/articles")
    fun list(
        @RequestParam(required = false) tag: String?, @RequestParam(required = false) author: String?,
        @RequestParam(required = false) favorited: String?,
        @RequestParam(defaultValue = "20") limit: Int, @RequestParam(defaultValue = "0") offset: Int,
        @RequestHeader(auth, required = false) a: String?,
    ) = s.listArticles(tag, author, favorited, limit.coerceIn(0, 100), offset.coerceAtLeast(0), s.optionalAuth(a))

    @GetMapping("/articles/feed")
    fun feed(
        @RequestParam(defaultValue = "20") limit: Int, @RequestParam(defaultValue = "0") offset: Int,
        @RequestHeader(auth, required = false) a: String?,
    ) = s.feed(s.requireAuth(a), limit.coerceIn(0, 100), offset.coerceAtLeast(0))

    @PostMapping("/articles")
    fun create(@RequestHeader(auth, required = false) a: String?, @RequestBody(required = false) body: Map<String, Any?>?): ResponseEntity<Any> {
        val me = s.requireAuth(a)
        return ResponseEntity.status(HttpStatus.CREATED).body(s.createArticle(me, inner(body, "article")))
    }

    @GetMapping("/articles/{slug}")
    fun get(@PathVariable slug: String, @RequestHeader(auth, required = false) a: String?) =
        s.getArticle(slug, s.optionalAuth(a))

    @PutMapping("/articles/{slug}")
    fun updateArticle(
        @PathVariable slug: String, @RequestHeader(auth, required = false) a: String?,
        @RequestBody(required = false) body: Map<String, Any?>?,
    ): Any {
        val me = s.requireAuth(a)
        return s.updateArticle(slug, me, inner(body, "article"))
    }

    @DeleteMapping("/articles/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable slug: String, @RequestHeader(auth, required = false) a: String?) =
        s.deleteArticle(slug, s.requireAuth(a))

    @PostMapping("/articles/{slug}/favorite")
    fun favorite(@PathVariable slug: String, @RequestHeader(auth, required = false) a: String?) =
        s.favorite(slug, s.requireAuth(a), true)

    @DeleteMapping("/articles/{slug}/favorite")
    fun unfavorite(@PathVariable slug: String, @RequestHeader(auth, required = false) a: String?) =
        s.favorite(slug, s.requireAuth(a), false)

    @GetMapping("/articles/{slug}/comments")
    fun comments(@PathVariable slug: String, @RequestHeader(auth, required = false) a: String?) =
        s.listComments(slug, s.optionalAuth(a))

    @PostMapping("/articles/{slug}/comments")
    fun addComment(
        @PathVariable slug: String, @RequestHeader(auth, required = false) a: String?,
        @RequestBody(required = false) body: Map<String, Any?>?,
    ): ResponseEntity<Any> {
        val me = s.requireAuth(a)
        return ResponseEntity.status(HttpStatus.CREATED).body(s.addComment(slug, me, inner(body, "comment")["body"] as? String))
    }

    @PutMapping("/articles/{slug}/comments/{id}")
    fun updateComment(
        @PathVariable slug: String, @PathVariable id: Long, @RequestHeader(auth, required = false) a: String?,
        @RequestBody(required = false) body: Map<String, Any?>?,
    ): Any {
        val me = s.requireAuth(a)
        return s.updateComment(slug, id, me, inner(body, "comment")["body"] as? String)
    }

    @DeleteMapping("/articles/{slug}/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteComment(@PathVariable slug: String, @PathVariable id: Long, @RequestHeader(auth, required = false) a: String?) =
        s.deleteComment(slug, id, s.requireAuth(a))

    @GetMapping("/tags")
    fun tags() = s.tags()
}
