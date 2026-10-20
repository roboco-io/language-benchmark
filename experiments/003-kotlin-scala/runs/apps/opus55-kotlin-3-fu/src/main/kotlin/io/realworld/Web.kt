package io.realworld

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** An error rendered as `{"errors": {field: [message]}}` with the given HTTP status. */
class ApiException(val status: HttpStatus, val field: String, val msg: String) : RuntimeException("$field $msg")

fun unauthorized(msg: String = "is missing") = ApiException(HttpStatus.UNAUTHORIZED, "token", msg)
fun notFound(field: String) = ApiException(HttpStatus.NOT_FOUND, field, "not found")
fun forbidden(field: String) = ApiException(HttpStatus.FORBIDDEN, field, "forbidden")
fun invalid(field: String, msg: String = "can't be blank") = ApiException(HttpStatus.UNPROCESSABLE_CONTENT, field, msg)
fun taken(field: String) = ApiException(HttpStatus.CONFLICT, field, "has already been taken")

fun errorBody(field: String, msg: String) = mapOf("errors" to mapOf(field to listOf(msg)))

@RestControllerAdvice
class ErrorHandler {
    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<Any> = ResponseEntity.status(e.status).body(errorBody(e.field, e.msg))

    @ExceptionHandler(HttpMessageNotReadableException::class, HttpMediaTypeNotSupportedException::class)
    fun unreadable(e: Exception): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(errorBody("body", "is invalid"))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun badParam(e: MethodArgumentTypeMismatchException): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(errorBody(e.name, "is invalid"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun noRoute(e: NoResourceFoundException): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("path", "not found"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun badMethod(e: HttpRequestMethodNotSupportedException): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(errorBody("method", "not allowed"))
}

/** Minimal HS256 JWT implementation carrying the user id in `sub`. */
@Component
class JwtService(
    @Value("\${app.jwt.secret}") secret: String,
    @Value("\${app.jwt.ttl-seconds}") private val ttlSeconds: Long,
) {
    private val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())

    fun issue(userId: Long): String {
        val exp = Instant.now().epochSecond + ttlSeconds
        val payload = encoder.encodeToString("""{"sub":"$userId","exp":$exp}""".toByteArray())
        val signingInput = "$header.$payload"
        return "$signingInput.${encoder.encodeToString(sign(signingInput))}"
    }

    /** Returns the user id if the token is well-formed, correctly signed and unexpired. */
    fun verify(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val sig = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(sig, sign("${parts[0]}.${parts[1]}"))) return null
        val json = runCatching { String(decoder.decode(parts[1]), StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val sub = SUB.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val exp = EXP.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return if (exp > Instant.now().epochSecond) sub else null
    }

    private fun sign(input: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(input.toByteArray(StandardCharsets.UTF_8))

    companion object {
        private val SUB = Regex("\"sub\":\"(\\d+)\"")
        private val EXP = Regex("\"exp\":(\\d+)")
    }
}

/** Resolves the caller's user id from the `Authorization: Token <jwt>` header. */
@Component
class Auth(private val jwt: JwtService, private val users: UserRepository) {
    fun optional(request: HttpServletRequest): Long? {
        val header = request.getHeader("Authorization") ?: return null
        val token = header.trim().let {
            when {
                it.startsWith("Token ", ignoreCase = true) -> it.substring(6).trim()
                it.startsWith("Bearer ", ignoreCase = true) -> it.substring(7).trim()
                else -> throw unauthorized("is invalid")
            }
        }
        val id = jwt.verify(token) ?: throw unauthorized("is invalid")
        if (!users.existsById(id)) throw unauthorized("is invalid")
        return id
    }

    fun required(request: HttpServletRequest): Long = optional(request) ?: throw unauthorized()
}
