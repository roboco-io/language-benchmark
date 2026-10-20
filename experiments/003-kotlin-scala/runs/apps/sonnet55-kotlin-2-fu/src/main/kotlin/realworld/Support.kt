package realworld

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.Date

class ApiException(val status: HttpStatus, val field: String, val msg: String) : RuntimeException(msg)

class ValidationErrors {
    val errors = linkedMapOf<String, List<String>>()
    fun add(field: String, msg: String) { errors.putIfAbsent(field, listOf(msg)) }
    fun check() { if (errors.isNotEmpty()) throw MultiFieldException(errors) }
}

class MultiFieldException(val errors: Map<String, List<String>>) : RuntimeException()

@RestControllerAdvice
class ErrorHandler {
    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = ResponseEntity.status(e.status).body(mapOf("errors" to mapOf(e.field to listOf(e.msg))))

    @ExceptionHandler(MultiFieldException::class)
    fun multi(e: MultiFieldException) = ResponseEntity.status(422).body(mapOf("errors" to e.errors))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        ResponseEntity.status(422).body(mapOf("errors" to mapOf("body" to listOf("is invalid"))))
}

@Component
class Passwords {
    private val encoder = BCryptPasswordEncoder(10)
    fun hash(raw: String): String = encoder.encode(raw)
    fun matches(raw: String, hash: String): Boolean = encoder.matches(raw, hash)
}

@Component
class Tokens(@Value("\${app.jwt-secret}") secret: String) {
    private val key = Keys.hmacShaKeyFor(secret.toByteArray())

    fun create(userId: Long): String = Jwts.builder()
        .subject(userId.toString())
        .issuedAt(Date())
        .expiration(Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000))
        .signWith(key)
        .compact()

    fun parse(token: String): Long? = try {
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload.subject.toLong()
    } catch (e: Exception) {
        null
    }
}
