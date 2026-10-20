package realworld

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
    fun findByUsername(username: String): User?
    fun existsByEmail(email: String): Boolean
    fun existsByUsername(username: String): Boolean
}

interface ArticleRepository : JpaRepository<Article, Long> {
    fun findBySlug(slug: String): Article?
    @Query("select distinct t from Article a join a.tagList t order by t")
    fun allTags(): List<String>
}

interface CommentRepository : JpaRepository<Comment, Long> {
    fun findByArticleIdOrderByIdAsc(articleId: Long): List<Comment>
    @Modifying
    @Query("delete from Comment c where c.articleId = :articleId")
    fun deleteByArticleId(articleId: Long)
}

interface FavoriteRepository : JpaRepository<Favorite, Long> {
    fun countByArticleId(articleId: Long): Long
    fun existsByUserIdAndArticleId(userId: Long, articleId: Long): Boolean
    @Modifying
    @Query("delete from Favorite f where f.userId = :userId and f.articleId = :articleId")
    fun deleteByUserIdAndArticleId(userId: Long, articleId: Long)
    @Modifying
    @Query("delete from Favorite f where f.articleId = :articleId")
    fun deleteByArticleId(articleId: Long)
}

interface FollowRepository : JpaRepository<Follow, Long> {
    fun existsByFollowerIdAndFolloweeId(followerId: Long, followeeId: Long): Boolean
    @Modifying
    @Query("delete from Follow f where f.followerId = :followerId and f.followeeId = :followeeId")
    fun deleteByFollowerIdAndFolloweeId(followerId: Long, followeeId: Long)
}
