package services

import play.api.Configuration
import play.api.libs.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}
import javax.inject.{Inject, Singleton}

/** HS256 JWT issuing/verification and PBKDF2 password hashing, using only the JDK. */
@Singleton
class Security @Inject() (config: Configuration) {
  private val secret = config.get[String]("realworld.jwt.secret").getBytes(UTF_8)
  private val ttlSeconds = config.get[Int]("realworld.jwt.ttl-hours") * 3600L
  private val rng = new SecureRandom()
  private val enc = Base64.getUrlEncoder.withoutPadding
  private val dec = Base64.getUrlDecoder

  private def sign(data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret, "HmacSHA256"))
    enc.encodeToString(mac.doFinal(data.getBytes(UTF_8)))
  }

  def issueToken(userId: Long): String = {
    val now = System.currentTimeMillis() / 1000
    val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".getBytes(UTF_8))
    val payload = enc.encodeToString(Json.stringify(Json.obj("sub" -> userId, "iat" -> now, "exp" -> (now + ttlSeconds))).getBytes(UTF_8))
    val unsigned = s"$header.$payload"
    s"$unsigned.${sign(unsigned)}"
  }

  /** Returns the user id if the token is well formed, correctly signed and not expired. */
  def verifyToken(token: String): Option[Long] =
    token.split('.') match {
      case Array(h, p, s) if MessageDigest.isEqual(sign(s"$h.$p").getBytes(UTF_8), s.getBytes(UTF_8)) =>
        try {
          val json = Json.parse(dec.decode(p))
          val exp = (json \ "exp").as[Long]
          if (exp > System.currentTimeMillis() / 1000) Some((json \ "sub").as[Long]) else None
        } catch { case _: Exception => None }
      case _ => None
    }

  private val Iterations = 20000

  private def pbkdf2(password: String, salt: Array[Byte]): Array[Byte] =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
      .generateSecret(new PBEKeySpec(password.toCharArray, salt, Iterations, 256)).getEncoded

  def hashPassword(password: String): String = {
    val salt = new Array[Byte](16)
    rng.nextBytes(salt)
    val b64 = Base64.getEncoder
    s"${b64.encodeToString(salt)}:${b64.encodeToString(pbkdf2(password, salt))}"
  }

  def checkPassword(password: String, stored: String): Boolean =
    stored.split(':') match {
      case Array(s, h) =>
        val d = Base64.getDecoder
        MessageDigest.isEqual(pbkdf2(password, d.decode(s)), d.decode(h))
      case _ => false
    }
}
