package realworld.auth

import play.api.libs.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/** Minimal HS256 JSON Web Token implementation (RFC 7519). The subject is the user id. */
final class Jwt(secret: String, ttlSeconds: Long) {
  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder
  private val key = new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256")
  private val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".getBytes(UTF_8))

  private def sign(data: String): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(key)
    mac.doFinal(data.getBytes(UTF_8))
  }

  def issue(userId: Long, username: String): String = {
    val now = System.currentTimeMillis() / 1000
    val payload = Json.obj("sub" -> userId.toString, "username" -> username, "iat" -> now, "exp" -> (now + ttlSeconds))
    val unsigned = s"$header.${encoder.encodeToString(Json.toBytes(payload))}"
    s"$unsigned.${encoder.encodeToString(sign(unsigned))}"
  }

  /** Returns the user id if the token is well-formed, correctly signed and not expired. */
  def verify(token: String): Option[Long] = {
    token.split('.') match {
      case Array(h, p, s) =>
        val validSig = Try(decoder.decode(s)).toOption.exists(sig => MessageDigest.isEqual(sig, sign(s"$h.$p")))
        if (!validSig) None
        else
          for {
            json <- Try(Json.parse(decoder.decode(p))).toOption
            alg <- Try(Json.parse(decoder.decode(h))).toOption.flatMap(j => (j \ "alg").asOpt[String])
            if alg == "HS256"
            exp <- (json \ "exp").asOpt[Long]
            if exp > System.currentTimeMillis() / 1000
            sub <- (json \ "sub").asOpt[String].flatMap(_.toLongOption)
          } yield sub
      case _ => None
    }
  }
}
