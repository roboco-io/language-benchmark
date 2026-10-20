package realworld

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Minimal HS256 JWT implementation. */
@Component
class JwtService(
    @Value("\${app.jwt.secret}") secret: String,
    @Value("\${app.jwt.ttl-seconds}") private val ttl: Long,
    private val mapper: ObjectMapper,
) {
    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    private fun sign(data: String): String =
        enc.encodeToString(Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(data.toByteArray()))

    fun create(userId: Long): String {
        val now = System.currentTimeMillis() / 1000
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(
            mapper.writeValueAsBytes(mapOf("sub" to userId.toString(), "iat" to now, "exp" to now + ttl))
        )
        return "$header.$payload.${sign("$header.$payload")}"
    }

    /** Returns the user id or null when the token is malformed, forged or expired. */
    fun verify(token: String): Long? = try {
        val parts = token.split('.')
        if (parts.size != 3) null
        else if (!java.security.MessageDigest.isEqual(sign("${parts[0]}.${parts[1]}").toByteArray(), parts[2].toByteArray())) null
        else {
            val node = mapper.readTree(dec.decode(parts[1]))
            if (node["exp"].asLong() < System.currentTimeMillis() / 1000) null else node["sub"].asText().toLong()
        }
    } catch (e: Exception) {
        null
    }
}

@Component
class PasswordService {
    private val encoder = BCryptPasswordEncoder(10)
    fun hash(raw: String): String = encoder.encode(raw)
    fun matches(raw: String, hash: String): Boolean = encoder.matches(raw, hash)
}

@Component
class AuthService(private val jwt: JwtService, private val users: UserRepository) {
    private fun parse(header: String?): String? {
        if (header == null) return null
        val h = header.trim()
        return when {
            h.startsWith("Token ", true) -> h.substring(6).trim()
            h.startsWith("Bearer ", true) -> h.substring(7).trim()
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    fun optional(header: String?): User? {
        val token = parse(header) ?: return null
        val id = jwt.verify(token) ?: throw ApiException(401, "token", "is invalid")
        return users.findById(id).orElseThrow { ApiException(401, "token", "is invalid") }
    }

    fun required(header: String?): User {
        if (parse(header) == null) throw ApiException(401, "token", "is missing")
        return optional(header)!!
    }
}
