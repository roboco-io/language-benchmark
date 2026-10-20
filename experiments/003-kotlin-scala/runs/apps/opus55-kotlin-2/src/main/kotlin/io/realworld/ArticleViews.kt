package io.realworld

import org.springframework.stereotype.Component

/** Builds article JSON views, loading authors, favorites, and follows in batches. */
@Component
class ArticleViews(
    private val users: UserRepository,
    private val follows: FollowRepository,
    private val favorites: FavoriteRepository,
) {
    fun many(articles: List<ArticleEntity>, me: UserEntity?, includeBody: Boolean): List<Map<String, Any?>> {
        if (articles.isEmpty()) return emptyList()
        val ids = articles.map { it.id!! }
        val authorIds = articles.map { it.authorId }.toSet()
        val authors = users.findAllById(authorIds).associateBy { it.id!! }
        val counts = favorites.countByArticleIds(ids).associate { (it[0] as Long) to (it[1] as Long) }
        val favorited = me?.let { favorites.findFavoritedAmong(it.id!!, ids).toSet() } ?: emptySet()
        val followed = me?.let { follows.findFollowedAmong(it.id!!, authorIds).toSet() } ?: emptySet()
        return articles.map { a ->
            view(a, authors.getValue(a.authorId), counts[a.id] ?: 0, a.id in favorited, a.authorId in followed, includeBody)
        }
    }

    fun one(article: ArticleEntity, me: UserEntity?): Map<String, Any?> = many(listOf(article), me, includeBody = true).single()

    private fun view(
        a: ArticleEntity,
        author: UserEntity,
        favoritesCount: Long,
        favorited: Boolean,
        following: Boolean,
        includeBody: Boolean,
    ): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "slug" to a.slug,
            "title" to a.title,
            "description" to a.description,
        )
        if (includeBody) m["body"] = a.body
        m["tagList"] = a.tagList.toList()
        m["createdAt"] = formatTimestamp(a.createdAt)
        m["updatedAt"] = formatTimestamp(a.updatedAt)
        m["favorited"] = favorited
        m["favoritesCount"] = favoritesCount
        m["author"] = profileView(author, following)
        return m
    }
}
