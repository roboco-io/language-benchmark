package io.realworld

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Minimal HS256 JWT issuer/verifier. The subject claim holds the user id. */
@Component
class JwtService(
    @Value("\${realworld.jwt-secret}") secret: String,
    @Value("\${realworld.jwt-ttl-seconds}") private val ttlSeconds: Long,
    private val mapper: ObjectMapper,
) {
    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())

    private fun sign(data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(data.toByteArray())

    fun issue(userId: Long): String {
        val now = Instant.now().epochSecond
        val payload = mapper.writeValueAsBytes(mapOf("sub" to userId.toString(), "iat" to now, "exp" to now + ttlSeconds))
        val unsigned = header + "." + encoder.encodeToString(payload)
        return unsigned + "." + encoder.encodeToString(sign(unsigned))
    }

    /** Returns the user id, or null when the token is malformed, forged, or expired. */
    fun verify(token: String): Long? = try {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != header) {
            null
        } else if (!MessageDigest.isEqual(sign(parts[0] + "." + parts[1]), decoder.decode(parts[2]))) {
            null
        } else {
            val claims = mapper.readTree(decoder.decode(parts[1]))
            val exp = claims.path("exp").asLong(0)
            if (exp <= Instant.now().epochSecond) null else claims.path("sub").asText().toLongOrNull()
        }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: java.io.IOException) {
        null
    }
}

@Component
class PasswordHasher {
    private val encoder = BCryptPasswordEncoder()
    fun hash(raw: String): String = encoder.encode(raw)
    fun matches(raw: String, hash: String): Boolean = encoder.matches(raw, hash)
}

/** Resolves the current user from an `Authorization: Token <jwt>` (or `Bearer <jwt>`) header. */
@Component
class Auth(private val jwt: JwtService, private val users: UserRepository) {
    private fun extractToken(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val trimmed = header.trim()
        val space = trimmed.indexOf(' ')
        if (space < 0) throw ApiException.unauthorized("is invalid")
        val scheme = trimmed.substring(0, space)
        if (!scheme.equals("Token", true) && !scheme.equals("Bearer", true)) throw ApiException.unauthorized("is invalid")
        return trimmed.substring(space + 1).trim()
    }

    /** Returns the authenticated user, or null when no token was sent. An invalid token is rejected. */
    fun optional(header: String?): UserEntity? {
        val token = extractToken(header) ?: return null
        val id = jwt.verify(token) ?: throw ApiException.unauthorized("is invalid")
        return users.findById(id).orElseThrow { ApiException.unauthorized("is invalid") }
    }

    fun required(header: String?): UserEntity =
        optional(header) ?: throw ApiException.unauthorized("is missing")
}
