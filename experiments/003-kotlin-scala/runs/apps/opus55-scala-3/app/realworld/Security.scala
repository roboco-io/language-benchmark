package realworld

import play.api.libs.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.{Mac, SecretKeyFactory}
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}

/** Minimal HS256 JSON Web Tokens. The subject (`sub`) is the user id. */
final class Jwt(secret: String, ttlSeconds: Long):
  private val enc = Base64.getUrlEncoder.withoutPadding()
  private val dec = Base64.getUrlDecoder
  private val key = SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256")
  private val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".getBytes(UTF_8))

  private def sign(data: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(key)
    enc.encodeToString(mac.doFinal(data.getBytes(UTF_8)))

  def issue(userId: Long, username: String): String =
    val now = System.currentTimeMillis() / 1000
    val payload = Json.obj("sub" -> userId.toString, "username" -> username, "iat" -> now, "exp" -> (now + ttlSeconds))
    val body = s"$header.${enc.encodeToString(Json.toBytes(payload))}"
    s"$body.${sign(body)}"

  /** Returns the user id if the token is well formed, correctly signed and not expired. */
  def verify(token: String): Option[Long] =
    token.split('.') match
      case Array(h, p, s) =>
        val expected = sign(s"$h.$p")
        if !MessageDigest.isEqual(expected.getBytes(UTF_8), s.getBytes(UTF_8)) then None
        else
          try
            val json = Json.parse(dec.decode(p))
            val exp = (json \ "exp").asOpt[Long]
            if exp.exists(_ < System.currentTimeMillis() / 1000) then None
            else (json \ "sub").asOpt[String].flatMap(_.toLongOption)
          catch case _: Exception => None
      case _ => None

/** PBKDF2-HMAC-SHA256 password hashing. Stored format: `iterations$salt$hash` (Base64). */
object Passwords:
  private val Iterations = 10000
  private val KeyBits = 256
  private val random = SecureRandom()
  private val b64 = Base64.getEncoder
  private val b64d = Base64.getDecoder

  private def derive(password: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    val spec = PBEKeySpec(password.toCharArray, salt, iterations, KeyBits)
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded

  def hash(password: String): String =
    val salt = new Array[Byte](16)
    random.nextBytes(salt)
    s"$Iterations$$${b64.encodeToString(salt)}$$${b64.encodeToString(derive(password, salt, Iterations))}"

  def verify(password: String, stored: String): Boolean =
    stored.split('$') match
      case Array(it, salt, hash) =>
        it.toIntOption.exists(i => MessageDigest.isEqual(derive(password, b64d.decode(salt), i), b64d.decode(hash)))
      case _ => false
