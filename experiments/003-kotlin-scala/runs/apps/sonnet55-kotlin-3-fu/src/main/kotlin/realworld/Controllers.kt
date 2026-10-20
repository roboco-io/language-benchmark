package realworld

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

private const val AUTH = "Authorization"

@RestController
@RequestMapping("/api")
class UserController(private val users: UserService, private val auth: AuthService) {
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody(required = false) body: JsonNode?) = users.register(body)

    @PostMapping("/users/login")
    fun login(@RequestBody(required = false) body: JsonNode?) = users.login(body)

    @GetMapping("/user")
    fun current(@RequestHeader(AUTH, required = false) h: String?) = users.userJson(auth.required(h))

    @PutMapping("/user")
    fun update(@RequestHeader(AUTH, required = false) h: String?, @RequestBody(required = false) body: JsonNode?) =
        users.update(auth.required(h), body)

    @GetMapping("/profiles/{username}")
    fun profile(@RequestHeader(AUTH, required = false) h: String?, @PathVariable username: String) =
        users.getProfile(username, auth.optional(h))

    @PostMapping("/profiles/{username}/follow")
    fun follow(@RequestHeader(AUTH, required = false) h: String?, @PathVariable username: String) =
        users.follow(auth.required(h), username)

    @DeleteMapping("/profiles/{username}/follow")
    fun unfollow(@RequestHeader(AUTH, required = false) h: String?, @PathVariable username: String) =
        users.unfollow(auth.required(h), username)
}

@RestController
@RequestMapping("/api")
class ArticleController(private val articles: ArticleService, private val auth: AuthService) {
    @GetMapping("/articles")
    fun list(
        @RequestHeader(AUTH, required = false) h: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) author: String?,
        @RequestParam(required = false) favorited: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ) = articles.list(tag, author, favorited, limit, offset, auth.optional(h))

    @GetMapping("/articles/feed")
    fun feed(
        @RequestHeader(AUTH, required = false) h: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ) = articles.feed(auth.required(h), limit, offset)

    @GetMapping("/articles/{slug}")
    fun get(@RequestHeader(AUTH, required = false) h: String?, @PathVariable slug: String) =
        articles.get(slug, auth.optional(h))

    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestHeader(AUTH, required = false) h: String?, @RequestBody(required = false) body: JsonNode?) =
        articles.create(auth.required(h), body)

    @PutMapping("/articles/{slug}")
    fun update(
        @RequestHeader(AUTH, required = false) h: String?,
        @PathVariable slug: String,
        @RequestBody(required = false) body: JsonNode?,
    ) = articles.update(auth.required(h), slug, body)

    @DeleteMapping("/articles/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@RequestHeader(AUTH, required = false) h: String?, @PathVariable slug: String) =
        articles.delete(auth.required(h), slug)

    @PostMapping("/articles/{slug}/favorite")
    fun favorite(@RequestHeader(AUTH, required = false) h: String?, @PathVariable slug: String) =
        articles.favorite(auth.required(h), slug, true)

    @DeleteMapping("/articles/{slug}/favorite")
    fun unfavorite(@RequestHeader(AUTH, required = false) h: String?, @PathVariable slug: String) =
        articles.favorite(auth.required(h), slug, false)

    @GetMapping("/articles/{slug}/comments")
    fun comments(@RequestHeader(AUTH, required = false) h: String?, @PathVariable slug: String) =
        articles.listComments(slug, auth.optional(h))

    @PostMapping("/articles/{slug}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun addComment(
        @RequestHeader(AUTH, required = false) h: String?,
        @PathVariable slug: String,
        @RequestBody(required = false) body: JsonNode?,
    ) = articles.addComment(auth.required(h), slug, body)

    @PutMapping("/articles/{slug}/comments/{id}")
    fun updateComment(
        @RequestHeader(AUTH, required = false) h: String?,
        @PathVariable slug: String,
        @PathVariable id: Long,
        @RequestBody(required = false) body: JsonNode?,
    ) = articles.updateComment(auth.required(h), slug, id, body)

    @DeleteMapping("/articles/{slug}/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteComment(@RequestHeader(AUTH, required = false) h: String?, @PathVariable slug: String, @PathVariable id: Long) =
        articles.deleteComment(auth.required(h), slug, id)

    @GetMapping("/tags")
    fun tags() = articles.tags()
}
