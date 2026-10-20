package io.realworld

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

const val MIN_PASSWORD_LENGTH = 8

@RestController
@RequestMapping("/api")
@Transactional
class UserController(
    private val users: UserRepository,
    private val auth: Auth,
    private val jwt: JwtService,
) {
    private val passwords = BCryptPasswordEncoder()

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody(required = false) body: JsonNode?): Map<String, UserView> {
        val node = Body.wrapped(body, "user")
        val errors = mutableMapOf<String, List<String>>()
        val username = Body.requiredText(node, "username", errors)?.trim()
        val email = Body.requiredText(node, "email", errors)?.trim()
        val password = Body.requiredText(node, "password", errors)
        Body.failIfAny(errors)
        if (users.existsByUsername(username!!)) throw ApiException.taken("username")
        if (users.existsByEmail(email!!)) throw ApiException.taken("email")
        val user = users.save(User(username, email, passwords.encode(password!!)))
        return mapOf("user" to view(user))
    }

    @PostMapping("/users/login")
    fun login(@RequestBody(required = false) body: JsonNode?): Map<String, UserView> {
        val node = Body.wrapped(body, "user")
        val errors = mutableMapOf<String, List<String>>()
        val email = Body.requiredText(node, "email", errors)?.trim()
        val password = Body.requiredText(node, "password", errors)
        Body.failIfAny(errors)
        val user = users.findByEmail(email!!)
        if (user == null || !passwords.matches(password, user.passwordHash)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "credentials", "invalid")
        }
        return mapOf("user" to view(user))
    }

    @GetMapping("/user")
    fun current(@RequestHeader("Authorization", required = false) header: String?): Map<String, UserView> =
        mapOf("user" to view(auth.required(header)))

    @PutMapping("/user")
    fun update(
        @RequestHeader("Authorization", required = false) header: String?,
        @RequestBody(required = false) body: JsonNode?,
    ): Map<String, UserView> {
        val user = auth.required(header)
        val node = Body.wrapped(body, "user")
        val errors = mutableMapOf<String, List<String>>()

        // Identity fields and password: when present they must be non-blank.
        val username = if (node.has("username")) Body.requiredText(node, "username", errors)?.trim() else null
        val email = if (node.has("email")) Body.requiredText(node, "email", errors)?.trim() else null
        val password = if (node.has("password")) Body.requiredText(node, "password", errors) else null
        if (password != null && password.length < MIN_PASSWORD_LENGTH) {
            errors["password"] = listOf("is too short (minimum is $MIN_PASSWORD_LENGTH characters)")
        }
        Body.failIfAny(errors)

        if (username != null && username != user.username) {
            if (users.existsByUsername(username)) throw ApiException.taken("username")
            user.username = username
        }
        if (email != null && email != user.email) {
            if (users.existsByEmail(email)) throw ApiException.taken("email")
            user.email = email
        }
        if (password != null) user.passwordHash = passwords.encode(password)
        // Optional profile fields: null or empty string clears the value.
        if (node.has("bio")) user.bio = Body.text(node, "bio")?.takeIf { it.isNotEmpty() }
        if (node.has("image")) user.image = Body.text(node, "image")?.takeIf { it.isNotEmpty() }
        users.save(user)
        return mapOf("user" to view(user))
    }

    private fun view(user: User) = UserView(user.email, jwt.generate(user.id!!), user.username, user.bio, user.image)
}

@RestController
@RequestMapping("/api/profiles/{username}")
@Transactional
class ProfileController(private val users: UserRepository, private val auth: Auth) {
    @GetMapping
    fun get(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable username: String,
    ): Map<String, ProfileView> {
        val viewer = auth.optional(header)
        val target = find(username)
        val following = viewer != null && users.isFollowing(viewer.id!!, target.id!!)
        return mapOf("profile" to ProfileView(target.username, target.bio, target.image, following))
    }

    @PostMapping("/follow")
    fun follow(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable username: String,
    ): Map<String, ProfileView> {
        val viewer = auth.required(header)
        val target = find(username)
        if (target.id != viewer.id) viewer.following.add(target)
        return mapOf("profile" to ProfileView(target.username, target.bio, target.image, target.id != viewer.id))
    }

    @DeleteMapping("/follow")
    fun unfollow(
        @RequestHeader("Authorization", required = false) header: String?,
        @PathVariable username: String,
    ): Map<String, ProfileView> {
        val viewer = auth.required(header)
        val target = find(username)
        viewer.following.remove(target)
        return mapOf("profile" to ProfileView(target.username, target.bio, target.image, false))
    }

    private fun find(username: String) = users.findByUsername(username) ?: throw ApiException.notFound("profile")
}
