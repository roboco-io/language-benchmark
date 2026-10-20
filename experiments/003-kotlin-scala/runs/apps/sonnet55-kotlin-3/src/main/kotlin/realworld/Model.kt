package realworld

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@Table(name = "users")
class User(
    @Column(unique = true, nullable = false) var username: String = "",
    @Column(unique = true, nullable = false) var email: String = "",
    @Column(nullable = false) var passwordHash: String = "",
    var bio: String? = null,
    var image: String? = null,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0
}

@Entity
@Table(name = "articles", indexes = [Index(columnList = "createdAt")])
class Article(
    @Column(unique = true, nullable = false) var slug: String = "",
    @Column(nullable = false) var title: String = "",
    @Column(nullable = false) var description: String = "",
    @Column(nullable = false, length = 100000) var body: String = "",
    @ManyToOne(optional = false, fetch = FetchType.EAGER) var author: User = User(),
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "article_tags", joinColumns = [JoinColumn(name = "article_id")])
    @OrderColumn(name = "position")
    @Column(name = "tag", nullable = false)
    var tags: MutableList<String> = mutableListOf()
}

@Entity
@Table(name = "comments")
class Comment(
    @ManyToOne(optional = false, fetch = FetchType.EAGER) var article: Article = Article(),
    @ManyToOne(optional = false, fetch = FetchType.EAGER) var author: User = User(),
    @Column(nullable = false, length = 100000) var body: String = "",
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0
}

@Entity
@Table(name = "favorites", uniqueConstraints = [UniqueConstraint(columnNames = ["userId", "articleId"])])
class Favorite(var userId: Long = 0, var articleId: Long = 0) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0
}

@Entity
@Table(name = "follows", uniqueConstraints = [UniqueConstraint(columnNames = ["followerId", "followeeId"])])
class Follow(var followerId: Long = 0, var followeeId: Long = 0) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0
}

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
    fun findByUsername(username: String): User?
}

interface ArticleRepository : JpaRepository<Article, Long> {
    fun findBySlug(slug: String): Article?
    fun existsBySlug(slug: String): Boolean
}

interface CommentRepository : JpaRepository<Comment, Long> {
    fun findByArticleIdOrderByIdAsc(articleId: Long): List<Comment>
    fun deleteByArticleId(articleId: Long)
}

interface FavoriteRepository : JpaRepository<Favorite, Long> {
    fun countByArticleId(articleId: Long): Long
    fun existsByUserIdAndArticleId(userId: Long, articleId: Long): Boolean
    fun deleteByUserIdAndArticleId(userId: Long, articleId: Long)
    fun deleteByArticleId(articleId: Long)
}

interface FollowRepository : JpaRepository<Follow, Long> {
    fun existsByFollowerIdAndFolloweeId(followerId: Long, followeeId: Long): Boolean
    fun deleteByFollowerIdAndFolloweeId(followerId: Long, followeeId: Long)
}
