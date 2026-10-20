package realworld.auth

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256 password hashing. Stored format: `pbkdf2$<iterations>$<salt>$<hash>`. */
final class Passwords(iterations: Int) {
  private val random = new SecureRandom()
  private val b64e = Base64.getEncoder
  private val b64d = Base64.getDecoder

  private def derive(password: String, salt: Array[Byte], iter: Int): Array[Byte] = {
    val spec = new PBEKeySpec(password.toCharArray, salt, iter, 256)
    try SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded
    finally spec.clearPassword()
  }

  def hash(password: String): String = {
    val salt = new Array[Byte](16)
    random.nextBytes(salt)
    s"pbkdf2$$$iterations$$${b64e.encodeToString(salt)}$$${b64e.encodeToString(derive(password, salt, iterations))}"
  }

  def verify(password: String, stored: String): Boolean =
    stored.split('$') match {
      case Array("pbkdf2", iter, salt, hash) =>
        iter.toIntOption.exists { i =>
          MessageDigest.isEqual(derive(password, b64d.decode(salt), i), b64d.decode(hash))
        }
      case _ => false
    }
}
