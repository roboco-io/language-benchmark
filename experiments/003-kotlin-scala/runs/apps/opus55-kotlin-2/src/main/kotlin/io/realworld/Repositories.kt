package io.realworld

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<UserEntity, Long> {
    fun findByUsername(username: String): UserEntity?
    fun findByEmail(email: String): UserEntity?
    fun existsByUsername(username: String): Boolean
    fun existsByEmail(email: String): Boolean
}

interface ArticleRepository : JpaRepository<ArticleEntity, Long> {
    fun findBySlug(slug: String): ArticleEntity?
    fun existsBySlug(slug: String): Boolean

    @Query("select distinct t from ArticleEntity a join a.tagList t order by t")
    fun findAllTags(): List<String>
}

interface FollowRepository : JpaRepository<FollowEntity, Long> {
    fun existsByFollowerIdAndFolloweeId(followerId: Long, followeeId: Long): Boolean

    @Modifying
    fun deleteByFollowerIdAndFolloweeId(followerId: Long, followeeId: Long): Long

    @Query("select f.followeeId from FollowEntity f where f.followerId = :followerId and f.followeeId in :ids")
    fun findFollowedAmong(followerId: Long, ids: Collection<Long>): List<Long>
}

interface FavoriteRepository : JpaRepository<FavoriteEntity, Long> {
    fun existsByUserIdAndArticleId(userId: Long, articleId: Long): Boolean
    fun countByArticleId(articleId: Long): Long

    @Modifying
    fun deleteByUserIdAndArticleId(userId: Long, articleId: Long): Long

    @Modifying
    @Query("delete from FavoriteEntity f where f.articleId = :articleId")
    fun deleteAllByArticleId(articleId: Long)

    @Query("select f.articleId, count(f) from FavoriteEntity f where f.articleId in :ids group by f.articleId")
    fun countByArticleIds(ids: Collection<Long>): List<Array<Any>>

    @Query("select f.articleId from FavoriteEntity f where f.userId = :userId and f.articleId in :ids")
    fun findFavoritedAmong(userId: Long, ids: Collection<Long>): List<Long>
}

interface CommentRepository : JpaRepository<CommentEntity, Long> {
    fun findByArticleIdOrderByIdAsc(articleId: Long): List<CommentEntity>

    @Modifying
    @Query("delete from CommentEntity c where c.articleId = :articleId")
    fun deleteAllByArticleId(articleId: Long)
}
