package io.realworld

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** An error rendered as `{"errors": {"<field>": ["<message>"]}}`. */
class ApiException(val status: HttpStatus, val errors: Map<String, List<String>>) : RuntimeException() {
    constructor(status: HttpStatus, field: String, message: String) : this(status, mapOf(field to listOf(message)))

    companion object {
        fun notFound(field: String) = ApiException(HttpStatus.NOT_FOUND, field, "not found")
        fun forbidden(field: String) = ApiException(HttpStatus.FORBIDDEN, field, "forbidden")
        fun blank(field: String) = ApiException(HttpStatus.UNPROCESSABLE_ENTITY, field, "can't be blank")
        fun invalid(field: String, message: String = "is invalid") =
            ApiException(HttpStatus.UNPROCESSABLE_ENTITY, field, message)
        fun taken(field: String) = ApiException(HttpStatus.CONFLICT, field, "has already been taken")
        fun unauthorized(message: String) = ApiException(HttpStatus.UNAUTHORIZED, "token", message)
    }
}

@RestControllerAdvice
class ErrorHandler {
    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(e.status).body(mapOf("errors" to e.errors))

    @ExceptionHandler(HttpMessageNotReadableException::class, HttpMediaTypeNotSupportedException::class)
    fun handleUnreadable(e: Exception): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(mapOf("errors" to mapOf("body" to listOf("is invalid"))))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(mapOf("errors" to mapOf(e.name to listOf("is invalid"))))
}
