package io.realworld

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

const val MIN_PASSWORD_LENGTH = 8

@RestController
@RequestMapping("/api")
class UserController(
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val jwt: JwtService,
    private val auth: Auth,
) {
    private fun userBody(u: UserEntity) = mapOf(
        "user" to linkedMapOf(
            "email" to u.email,
            "token" to jwt.issue(u.id!!),
            "username" to u.username,
            "bio" to u.bio,
            "image" to u.image,
        ),
    )

    private fun checkPassword(password: String, v: Validator) {
        if (password.length < MIN_PASSWORD_LENGTH) v.add("password", "is too short (minimum is $MIN_PASSWORD_LENGTH characters)")
    }

    @PostMapping("/users")
    @Transactional
    fun register(@RequestBody body: JsonNode): ResponseEntity<Any> {
        val input = body.wrapped("user")
        val v = Validator()
        val username = input.requiredText("username", v)?.trim()
        val email = input.requiredText("email", v)?.trim()
        val password = input.requiredText("password", v)
        password?.let { checkPassword(it, v) }
        v.throwIfAny()

        if (users.existsByUsername(username!!)) throw ApiException.taken("username")
        if (users.existsByEmail(email!!)) throw ApiException.taken("email")
        val user = users.save(UserEntity(username = username, email = email, passwordHash = hasher.hash(password!!)))
        return ResponseEntity.status(HttpStatus.CREATED).body(userBody(user))
    }

    @PostMapping("/users/login")
    @Transactional(readOnly = true)
    fun login(@RequestBody body: JsonNode): Any {
        val input = body.wrapped("user")
        val v = Validator()
        val email = input.requiredText("email", v)?.trim()
        val password = input.requiredText("password", v)
        v.throwIfAny()

        val user = users.findByEmail(email!!)
        if (user == null || !hasher.matches(password!!, user.passwordHash)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "credentials", "invalid")
        }
        return userBody(user)
    }

    @GetMapping("/user")
    @Transactional(readOnly = true)
    fun current(@RequestHeader("Authorization", required = false) authorization: String?): Any =
        userBody(auth.required(authorization))

    @PutMapping("/user")
    @Transactional
    fun update(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody(required = false) body: JsonNode?,
    ): Any {
        val user = auth.required(authorization)
        val input = body.wrapped("user")
        val v = Validator()

        val username = if (input.has("username")) input.requiredText("username", v)?.trim() else null
        val email = if (input.has("email")) input.requiredText("email", v)?.trim() else null
        val password = if (input.has("password")) input.requiredText("password", v) else null
        password?.let { checkPassword(it, v) }
        val bio = optionalText(input, "bio", v)
        val image = optionalText(input, "image", v)
        v.throwIfAny()

        if (username != null && username != user.username) {
            if (users.existsByUsername(username)) throw ApiException.taken("username")
            user.username = username
        }
        if (email != null && email != user.email) {
            if (users.existsByEmail(email)) throw ApiException.taken("email")
            user.email = email
        }
        if (password != null) user.passwordHash = hasher.hash(password)
        bio?.let { user.bio = it.value }
        image?.let { user.image = it.value }
        return userBody(users.save(user))
    }

    private class Nullable(val value: String?)

    /** Nullable text field: absent -> no change, null or "" -> cleared, text -> set. */
    private fun optionalText(input: JsonNode, name: String, v: Validator): Nullable? = when (val f = input.field(name)) {
        Field.Absent -> null
        Field.Null -> Nullable(null)
        is Field.Text -> Nullable(f.value.ifBlank { null })
        is Field.Other -> {
            v.add(name, "must be a string")
            null
        }
    }
}
