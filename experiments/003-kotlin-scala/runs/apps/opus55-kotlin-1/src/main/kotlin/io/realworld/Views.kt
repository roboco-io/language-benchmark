package io.realworld

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

fun formatTimestamp(epochMillis: Long): String = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis))

data class UserView(val email: String, val token: String, val username: String, val bio: String?, val image: String?)

data class ProfileView(val username: String, val bio: String?, val image: String?, val following: Boolean)

data class ArticleView(
    val slug: String,
    val title: String,
    val description: String,
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val body: String?,
    val tagList: List<String>,
    val createdAt: String,
    val updatedAt: String,
    val favorited: Boolean,
    val favoritesCount: Int,
    val author: ProfileView,
)

data class CommentView(val id: Long, val createdAt: String, val updatedAt: String, val body: String, val author: ProfileView)

/** Offset/limit based paging; Spring's PageRequest only supports page numbers. */
class OffsetLimit(private val offset: Long, private val limit: Int) : Pageable {
    override fun getPageNumber() = (offset / limit).toInt()
    override fun getPageSize() = limit
    override fun getOffset() = offset
    override fun getSort(): Sort = Sort.unsorted()
    override fun next() = OffsetLimit(offset + limit, limit)
    override fun previousOrFirst() = if (offset < limit) first() else OffsetLimit(offset - limit, limit)
    override fun first() = OffsetLimit(0, limit)
    override fun withPage(pageNumber: Int) = OffsetLimit(pageNumber.toLong() * limit, limit)
    override fun hasPrevious() = offset > 0
}

/** Helpers for reading request bodies while distinguishing absent fields from explicit nulls. */
object Body {
    /** Returns the object nested under [key] (e.g. `{"user": {...}}`), or fails with 422. */
    fun wrapped(body: JsonNode?, key: String): JsonNode {
        val node = body?.get(key)
        if (node == null || !node.isObject) throw ApiException.invalid(key, "is missing")
        return node
    }

    /** Reads a string field; null if absent or null. Non-string values are rejected. */
    fun text(node: JsonNode, field: String): String? {
        val value = node.get(field) ?: return null
        if (value.isNull) return null
        if (!value.isTextual) throw ApiException.invalid(field)
        return value.asText()
    }

    /** Reads a required non-blank string field. */
    fun requiredText(node: JsonNode, field: String, errors: MutableMap<String, List<String>>): String? {
        val value = text(node, field)
        if (value.isNullOrBlank()) {
            errors[field] = listOf("can't be blank")
            return null
        }
        return value
    }

    fun failIfAny(errors: Map<String, List<String>>) {
        if (errors.isNotEmpty()) throw ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, errors)
    }
}
