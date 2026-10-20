package realworld

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.HttpRequestMethodNotSupportedException

class ApiException(val status: Int, val errors: Map<String, List<String>>) : RuntimeException() {
    constructor(status: Int, field: String, message: String) : this(status, mapOf(field to listOf(message)))
}

@RestControllerAdvice
class ErrorHandler {
    private fun body(status: Int, errors: Map<String, List<String>>) =
        ResponseEntity.status(status).body(mapOf("errors" to errors))

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = body(e.status, e.errors)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) = body(422, mapOf("body" to listOf("is invalid")))

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(e: NoResourceFoundException) = body(404, mapOf("resource" to listOf("not found")))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(e: HttpRequestMethodNotSupportedException) = body(405, mapOf("method" to listOf("not allowed")))

    @ExceptionHandler(Exception::class)
    fun other(e: Exception): ResponseEntity<*> {
        org.slf4j.LoggerFactory.getLogger(ErrorHandler::class.java).error("Unhandled error", e)
        return body(HttpStatus.INTERNAL_SERVER_ERROR.value(), mapOf("server" to listOf("internal error")))
    }
}
