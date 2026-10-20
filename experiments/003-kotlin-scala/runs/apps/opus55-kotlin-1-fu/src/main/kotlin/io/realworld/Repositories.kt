package io.realworld

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserRepository : JpaRepository<User, Long> {
    fun findByUsername(username: String): User?
    fun findByEmail(email: String): User?
    fun existsByUsername(username: String): Boolean
    fun existsByEmail(email: String): Boolean

    @Query("select count(f) > 0 from User u join u.following f where u.id = :followerId and f.id = :followeeId")
    fun isFollowing(@Param("followerId") followerId: Long, @Param("followeeId") followeeId: Long): Boolean
}

interface ArticleRepository : JpaRepository<Article, Long> {
    fun findBySlug(slug: String): Article?
    fun existsBySlug(slug: String): Boolean

    @Query(
        value = """
            select a from Article a
            where (:author is null or a.author.username = :author)
              and (:tag is null or exists (select 1 from Article t join t.tagList tg where t = a and tg = :tag))
              and (:favorited is null or exists (select 1 from Article f join f.favoritedBy u where f = a and u.username = :favorited))
            order by a.createdAt desc, a.id desc
        """,
        countQuery = """
            select count(a) from Article a
            where (:author is null or a.author.username = :author)
              and (:tag is null or exists (select 1 from Article t join t.tagList tg where t = a and tg = :tag))
              and (:favorited is null or exists (select 1 from Article f join f.favoritedBy u where f = a and u.username = :favorited))
        """,
    )
    fun search(
        @Param("author") author: String?,
        @Param("tag") tag: String?,
        @Param("favorited") favorited: String?,
        pageable: Pageable,
    ): Page<Article>

    @Query(
        value = """
            select a from Article a
            where a.author in (select f from User u join u.following f where u.id = :userId)
            order by a.createdAt desc, a.id desc
        """,
        countQuery = """
            select count(a) from Article a
            where a.author in (select f from User u join u.following f where u.id = :userId)
        """,
    )
    fun feed(@Param("userId") userId: Long, pageable: Pageable): Page<Article>

    @Query("select distinct tg from Article a join a.tagList tg order by tg")
    fun allTags(): List<String>
}

interface CommentRepository : JpaRepository<Comment, Long> {
    fun findByArticleOrderByCreatedAtAscIdAsc(article: Article): List<Comment>
}
