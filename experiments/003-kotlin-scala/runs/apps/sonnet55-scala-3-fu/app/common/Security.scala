package common

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}

import play.api.libs.json.{JsObject, Json}

/** HS256 JSON Web Tokens implemented on top of the JDK crypto primitives. */
final class Jwt(secret: String, ttlSeconds: Long):
  private val enc = Base64.getUrlEncoder.withoutPadding
  private val dec = Base64.getUrlDecoder
  private val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".getBytes(UTF_8))

  private def sign(data: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(data.getBytes(UTF_8))

  def issue(userId: Long): String =
    val now = System.currentTimeMillis() / 1000
    val payload = enc.encodeToString(Json.obj("id" -> userId, "iat" -> now, "exp" -> (now + ttlSeconds)).toString.getBytes(UTF_8))
    val unsigned = s"$header.$payload"
    s"$unsigned.${enc.encodeToString(sign(unsigned))}"

  /** Returns the user id carried by a valid, unexpired token. */
  def verify(token: String): Option[Long] =
    token.split('.') match
      case Array(h, p, s) =>
        val expected = sign(s"$h.$p")
        val actual = try dec.decode(s) catch case _: IllegalArgumentException => Array.emptyByteArray
        if !MessageDigest.isEqual(expected, actual) then None
        else
          try
            val json = Json.parse(dec.decode(p)).as[JsObject]
            val exp = (json \ "exp").as[Long]
            if exp * 1000 < System.currentTimeMillis() then None else Some((json \ "id").as[Long])
          catch case _: Exception => None
      case _ => None

/** PBKDF2 password hashing; the stored format is `iterations:salt:hash` (base64). */
object Passwords:
  private val Iterations = 20000
  private val random = SecureRandom()

  private def derive(password: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    val spec = PBEKeySpec(password.toCharArray, salt, iterations, 256)
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded

  def hash(password: String): String =
    val salt = new Array[Byte](16)
    random.nextBytes(salt)
    val b64 = Base64.getEncoder
    s"$Iterations:${b64.encodeToString(salt)}:${b64.encodeToString(derive(password, salt, Iterations))}"

  def check(password: String, stored: String): Boolean =
    stored.split(':') match
      case Array(it, salt, hash) =>
        val dec = Base64.getDecoder
        MessageDigest.isEqual(derive(password, dec.decode(salt), it.toInt), dec.decode(hash))
      case _ => false
