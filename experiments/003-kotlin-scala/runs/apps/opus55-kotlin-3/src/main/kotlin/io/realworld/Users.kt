package io.realworld

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

typealias Json = Map<String, Any?>

/** Extracts the wrapper object (e.g. `{"user": {...}}`) from a request body. */
fun Json?.section(name: String): Json {
    @Suppress("UNCHECKED_CAST")
    return this?.get(name) as? Json ?: throw invalid(name, "is missing")
}

/** Reads a required string field; missing, null, non-string or blank values are rejected. */
fun Json.requiredString(field: String): String {
    val value = this[field] as? String
    if (value.isNullOrBlank()) throw invalid(field)
    return value
}

const val MIN_PASSWORD = 8

fun profileJson(user: User, viewer: User?): Json = linkedMapOf(
    "username" to user.username,
    "bio" to user.bio,
    "image" to user.image,
    "following" to (viewer?.isFollowing(user) ?: false),
)

@Service
@Transactional
class UserService(
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
    private val jwt: JwtService,
) {
    fun load(id: Long): User = users.findById(id).orElseThrow { unauthorized("is invalid") }

    fun userJson(user: User): Json = mapOf(
        "user" to linkedMapOf(
            "email" to user.email,
            "token" to jwt.issue(user.id!!),
            "username" to user.username,
            "bio" to user.bio,
            "image" to user.image,
        ),
    )

    fun register(body: Json?): Json {
        val req = body.section("user")
        val username = req.requiredString("username").trim()
        val email = req.requiredString("email").trim()
        val password = req.requiredString("password")
        if (password.length < MIN_PASSWORD) throw invalid("password", "is too short (minimum is $MIN_PASSWORD characters)")
        if (users.existsByUsername(username)) throw taken("username")
        if (users.existsByEmail(email)) throw taken("email")
        val user = users.save(User(username = username, email = email, passwordHash = encoder.encode(password)!!))
        return userJson(user)
    }

    fun login(body: Json?): Json {
        val req = body.section("user")
        val email = req.requiredString("email").trim()
        val password = req.requiredString("password")
        val user = users.findByEmail(email)
        if (user == null || !encoder.matches(password, user.passwordHash)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "credentials", "invalid")
        }
        return userJson(user)
    }

    fun current(id: Long): Json = userJson(load(id))

    fun update(id: Long, body: Json?): Json {
        val user = load(id)
        val req = body.section("user")
        if (req.containsKey("username")) {
            val username = req.requiredString("username").trim()
            if (username != user.username && users.existsByUsername(username)) throw taken("username")
            user.username = username
        }
        if (req.containsKey("email")) {
            val email = req.requiredString("email").trim()
            if (email != user.email && users.existsByEmail(email)) throw taken("email")
            user.email = email
        }
        if (req.containsKey("password")) {
            val password = req.requiredString("password")
            if (password.length < MIN_PASSWORD) throw invalid("password", "is too short (minimum is $MIN_PASSWORD characters)")
            user.passwordHash = encoder.encode(password)!!
        }
        if (req.containsKey("bio")) user.bio = nullableString(req, "bio")
        if (req.containsKey("image")) user.image = nullableString(req, "image")
        users.saveAndFlush(user)
        return userJson(user)
    }

    /** Optional text field: null or empty string both clear the value. */
    private fun nullableString(req: Json, field: String): String? = when (val v = req[field]) {
        null -> null
        is String -> v.ifEmpty { null }
        else -> throw invalid(field, "is invalid")
    }

    fun profile(username: String, viewerId: Long?): Json {
        val user = users.findByUsername(username) ?: throw notFound("profile")
        return mapOf("profile" to profileJson(user, viewerId?.let(::load)))
    }

    fun follow(username: String, viewerId: Long, follow: Boolean): Json {
        val viewer = load(viewerId)
        val target = users.findByUsername(username) ?: throw notFound("profile")
        if (follow) {
            if (target.id != viewer.id && !viewer.isFollowing(target)) viewer.following.add(target)
        } else {
            viewer.following.removeIf { it.id == target.id }
        }
        return mapOf("profile" to profileJson(target, viewer))
    }
}

@RestController
class UserController(private val service: UserService, private val auth: Auth) {
    @PostMapping("/api/users")
    fun register(@RequestBody(required = false) body: Json?): ResponseEntity<Json> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.register(body))

    @PostMapping("/api/users/login")
    fun login(@RequestBody(required = false) body: Json?): Json = service.login(body)

    @GetMapping("/api/user")
    fun current(req: HttpServletRequest): Json = service.current(auth.required(req))

    @PutMapping("/api/user")
    fun update(req: HttpServletRequest, @RequestBody(required = false) body: Json?): Json =
        service.update(auth.required(req), body)

    @GetMapping("/api/profiles/{username}")
    fun profile(req: HttpServletRequest, @PathVariable username: String): Json =
        service.profile(username, auth.optional(req))

    @PostMapping("/api/profiles/{username}/follow")
    fun follow(req: HttpServletRequest, @PathVariable username: String): Json =
        service.follow(username, auth.required(req), true)

    @DeleteMapping("/api/profiles/{username}/follow")
    fun unfollow(req: HttpServletRequest, @PathVariable username: String): Json =
        service.follow(username, auth.required(req), false)
}
