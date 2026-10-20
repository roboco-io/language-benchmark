package auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}

import play.api.libs.json.*

/** Password hashing (PBKDF2) and HS256 JWT helpers built on the JDK only. */
object Security:
  private val random = new SecureRandom()
  private val Iterations = 60000

  private def pbkdf2(password: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    val spec = new PBEKeySpec(password.toCharArray, salt, iterations, 256)
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded

  def hashPassword(password: String): String =
    val salt = new Array[Byte](16)
    random.nextBytes(salt)
    val b64 = Base64.getEncoder
    s"$Iterations:${b64.encodeToString(salt)}:${b64.encodeToString(pbkdf2(password, salt, Iterations))}"

  def verifyPassword(password: String, stored: String): Boolean =
    stored.split(':') match
      case Array(iter, salt, hash) =>
        val dec = Base64.getDecoder
        MessageDigest.isEqual(pbkdf2(password, dec.decode(salt), iter.toInt), dec.decode(hash))
      case _ => false

  private val urlEnc = Base64.getUrlEncoder.withoutPadding
  private val urlDec = Base64.getUrlDecoder

  private def hmac(secret: String, data: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(data.getBytes(UTF_8))

  private val header = urlEnc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".getBytes(UTF_8))

  def issueToken(secret: String, userId: Long, ttlSeconds: Long): String =
    val now = System.currentTimeMillis() / 1000
    val payload = Json.obj("sub" -> userId.toString, "iat" -> now, "exp" -> (now + ttlSeconds))
    val body = urlEnc.encodeToString(Json.stringify(payload).getBytes(UTF_8))
    val unsigned = s"$header.$body"
    s"$unsigned.${urlEnc.encodeToString(hmac(secret, unsigned))}"

  /** Returns the user id when the token is correctly signed and not expired. */
  def verifyToken(secret: String, token: String): Option[Long] =
    token.split('.') match
      case Array(h, p, s) =>
        scala.util.Try {
          val expected = hmac(secret, s"$h.$p")
          if !MessageDigest.isEqual(expected, urlDec.decode(s)) then None
          else
            val json = Json.parse(urlDec.decode(p))
            val exp = (json \ "exp").as[Long]
            if exp < System.currentTimeMillis() / 1000 then None
            else Some((json \ "sub").as[String].toLong)
        }.toOption.flatten
      case _ => None
