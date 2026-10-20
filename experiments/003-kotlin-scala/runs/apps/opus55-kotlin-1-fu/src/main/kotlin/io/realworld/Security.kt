package io.realworld

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Minimal HS256 JSON Web Token implementation. The subject claim holds the user id. */
@Component
class JwtService(
    @Value("\${JWT_SECRET:realworld-development-secret-change-me-in-production}") secret: String,
    @Value("\${JWT_TTL_SECONDS:2592000}") private val ttlSeconds: Long,
    private val mapper: ObjectMapper,
) {
    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())

    fun generate(userId: Long): String {
        val now = Instant.now().epochSecond
        val payload = mapper.writeValueAsBytes(mapOf("sub" to userId.toString(), "iat" to now, "exp" to now + ttlSeconds))
        val unsigned = header + "." + encoder.encodeToString(payload)
        return unsigned + "." + sign(unsigned)
    }

    /** Returns the user id stored in a valid, unexpired token, or null when the token is invalid. */
    fun parse(token: String): Long? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val expected = sign(parts[0] + "." + parts[1])
        if (!MessageDigest.isEqual(expected.toByteArray(), parts[2].toByteArray())) return null
        return try {
            val claims = mapper.readTree(decoder.decode(parts[1]))
            val exp = claims.path("exp").asLong(0)
            if (exp <= Instant.now().epochSecond) null else claims.path("sub").asText().toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun sign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return encoder.encodeToString(mac.doFinal(data.toByteArray()))
    }
}

/** Resolves the current user from the `Authorization: Token <jwt>` header. */
@Component
class Auth(private val jwt: JwtService, private val users: UserRepository) {
    fun required(header: String?): User =
        optionalOrThrow(header) ?: throw ApiException.unauthorized("is missing")

    fun optional(header: String?): User? = optionalOrThrow(header)

    private fun optionalOrThrow(header: String?): User? {
        if (header.isNullOrBlank()) return null
        val token = header.trim().let {
            when {
                it.startsWith("Token ", ignoreCase = true) -> it.substring(6).trim()
                it.startsWith("Bearer ", ignoreCase = true) -> it.substring(7).trim()
                else -> throw ApiException.unauthorized("is invalid")
            }
        }
        val userId = jwt.parse(token) ?: throw ApiException.unauthorized("is invalid")
        return users.findById(userId).orElseThrow { ApiException.unauthorized("is invalid") }
    }
}
