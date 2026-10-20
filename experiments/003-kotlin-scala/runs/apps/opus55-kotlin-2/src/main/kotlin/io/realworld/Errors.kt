package io.realworld

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

/** An error rendered as `{"errors": {field: [messages]}}`. */
class ApiException(val status: HttpStatus, val errors: Map<String, List<String>>) : RuntimeException() {
    constructor(status: HttpStatus, field: String, message: String) : this(status, mapOf(field to listOf(message)))

    companion object {
        fun notFound(field: String) = ApiException(HttpStatus.NOT_FOUND, field, "not found")
        fun forbidden(field: String) = ApiException(HttpStatus.FORBIDDEN, field, "forbidden")
        fun unauthorized(message: String) = ApiException(HttpStatus.UNAUTHORIZED, "token", message)
        fun taken(field: String) = ApiException(HttpStatus.CONFLICT, field, "has already been taken")
    }
}

/** Collects field validation messages and throws a single 422 error. */
class Validator {
    private val errors = linkedMapOf<String, MutableList<String>>()

    fun add(field: String, message: String) {
        errors.getOrPut(field) { mutableListOf() }.add(message)
    }

    fun throwIfAny() {
        if (errors.isNotEmpty()) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errors)
    }
}

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(ErrorHandler::class.java)

    private fun body(errors: Map<String, List<String>>) = mapOf("errors" to errors)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException) = ResponseEntity.status(e.status).body(body(e.errors))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException) =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body(mapOf("body" to listOf("is invalid"))))

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaType(e: HttpMediaTypeNotSupportedException) =
        ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body(mapOf("body" to listOf("must be JSON"))))

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(mapOf("path" to listOf("not found"))))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethod(e: HttpRequestMethodNotSupportedException) =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body(mapOf("method" to listOf("not allowed"))))

    @ExceptionHandler(Exception::class)
    fun handleOther(e: Exception): ResponseEntity<Map<String, Any>> {
        log.error("Unhandled error", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(mapOf("server" to listOf("internal error"))))
    }
}
