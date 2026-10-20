package realworld.auth

import play.api.libs.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}
import scala.util.Try

/** Minimal HS256 JSON Web Token implementation. The subject claim is the user id. */
final class Jwt(secret: String, ttlSeconds: Long):
  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder
  private val key     = new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256")
  private val header  = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".getBytes(UTF_8))

  private def sign(data: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(key)
    mac.doFinal(data.getBytes(UTF_8))

  def issue(userId: Long): String =
    val now     = System.currentTimeMillis() / 1000
    val payload = Json.obj("sub" -> userId.toString, "iat" -> now, "exp" -> (now + ttlSeconds))
    val body    = s"$header.${encoder.encodeToString(Json.toBytes(payload))}"
    s"$body.${encoder.encodeToString(sign(body))}"

  /** Returns the user id when the token has a valid signature and has not expired. */
  def verify(token: String): Option[Long] =
    token.split('.') match
      case Array(h, p, s) =>
        Try {
          val expected = sign(s"$h.$p")
          if !MessageDigest.isEqual(expected, decoder.decode(s)) then None
          else
            val claims = Json.parse(decoder.decode(p))
            val exp    = (claims \ "exp").asOpt[Long]
            val now    = System.currentTimeMillis() / 1000
            if exp.exists(_ < now) then None
            else (claims \ "sub").asOpt[String].flatMap(_.toLongOption)
        }.toOption.flatten
      case _ => None

/** PBKDF2-HMAC-SHA256 password hashing. Stored format: `iterations:saltB64:hashB64`. */
object PasswordHasher:
  private val Iterations = 10000
  private val KeyBits    = 256
  private val random     = new SecureRandom()
  private val b64e       = Base64.getEncoder
  private val b64d       = Base64.getDecoder

  private def derive(password: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    val spec = new PBEKeySpec(password.toCharArray, salt, iterations, KeyBits)
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded

  def hash(password: String): String =
    val salt = new Array[Byte](16)
    random.nextBytes(salt)
    s"$Iterations:${b64e.encodeToString(salt)}:${b64e.encodeToString(derive(password, salt, Iterations))}"

  def verify(password: String, stored: String): Boolean =
    stored.split(':') match
      case Array(it, salt, hash) =>
        Try {
          MessageDigest.isEqual(derive(password, b64d.decode(salt), it.toInt), b64d.decode(hash))
        }.getOrElse(false)
      case _ => false
