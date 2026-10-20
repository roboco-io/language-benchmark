package io.realworld

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

fun formatTimestamp(epochMillis: Long): String = TIMESTAMP.format(Instant.ofEpochMilli(epochMillis))

/** Returns the wrapped object (e.g. `{"user": {...}}` -> `{...}`) or fails with 422. */
fun JsonNode?.wrapped(name: String): JsonNode {
    val node = this?.get(name)
    if (node == null || !node.isObject) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, name, "is missing")
    return node
}

/** A JSON field that distinguishes "absent" from an explicit `null`. */
sealed interface Field {
    data object Absent : Field
    data object Null : Field
    data class Text(val value: String) : Field
    data class Other(val node: JsonNode) : Field
}

fun JsonNode.field(name: String): Field {
    val node = get(name) ?: return Field.Absent
    return when {
        node.isNull -> Field.Null
        node.isTextual -> Field.Text(node.asText())
        else -> Field.Other(node)
    }
}

/** Reads a required, non-blank string field; records "can't be blank" otherwise. */
fun JsonNode.requiredText(name: String, v: Validator): String? {
    val f = field(name)
    if (f is Field.Text && f.value.isNotBlank()) return f.value
    v.add(name, if (f is Field.Other) "must be a string" else "can't be blank")
    return null
}
