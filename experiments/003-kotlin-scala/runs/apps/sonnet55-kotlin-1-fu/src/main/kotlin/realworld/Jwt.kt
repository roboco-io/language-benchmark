package realworld

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Minimal HS256 JWT implementation. */
@Component
class Jwt(
    @Value("\${app.jwt.secret}") secret: String,
    @Value("\${app.jwt.ttl-seconds}") private val ttl: Long,
    private val mapper: ObjectMapper,
) {
    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())

    private fun sign(data: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(data.toByteArray())
    }

    fun create(userId: Long): String {
        val now = System.currentTimeMillis() / 1000
        val payload = enc.encodeToString(mapper.writeValueAsBytes(mapOf("sub" to userId, "iat" to now, "exp" to now + ttl)))
        val data = "$header.$payload"
        return "$data.${enc.encodeToString(sign(data))}"
    }

    /** Returns the user id, or null if the token is malformed, forged or expired. */
    fun verify(token: String): Long? = try {
        val parts = token.split('.')
        if (parts.size != 3) null
        else if (!MessageDigest.isEqual(sign("${parts[0]}.${parts[1]}"), dec.decode(parts[2]))) null
        else {
            val claims = mapper.readTree(dec.decode(parts[1]))
            if (claims["exp"].asLong() < System.currentTimeMillis() / 1000) null else claims["sub"].asLong()
        }
    } catch (e: Exception) {
        null
    }
}
