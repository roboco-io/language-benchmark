package realworld

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

class ApiException(val status: HttpStatus, val errors: Map<String, List<String>>) : RuntimeException() {
    constructor(status: HttpStatus, field: String, message: String) : this(status, mapOf(field to listOf(message)))
}

@RestControllerAdvice
class ErrorHandler {
    private fun body(status: HttpStatus, errors: Map<String, List<String>>): ResponseEntity<Any> =
        ResponseEntity.status(status).body(mapOf("errors" to errors))

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = body(e.status, e.errors)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        body(HttpStatus.UNPROCESSABLE_ENTITY, mapOf("body" to listOf("is invalid")))

    @ExceptionHandler(NoResourceFoundException::class)
    fun noResource(e: NoResourceFoundException) = body(HttpStatus.NOT_FOUND, mapOf("path" to listOf("not found")))

    @ExceptionHandler(Exception::class)
    fun other(e: Exception): ResponseEntity<Any> {
        e.printStackTrace()
        return body(HttpStatus.INTERNAL_SERVER_ERROR, mapOf("server" to listOf("internal error")))
    }
}
