package io.realworld

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

@Entity
@Table(name = "users")
class User(
    @Column(nullable = false, unique = true) var username: String,
    @Column(nullable = false, unique = true) var email: String,
    @Column(nullable = false) var passwordHash: String,
    var bio: String? = null,
    var image: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
) {
    @ManyToMany
    @JoinTable(
        name = "follows",
        joinColumns = [JoinColumn(name = "follower_id")],
        inverseJoinColumns = [JoinColumn(name = "followee_id")],
    )
    var following: MutableSet<User> = mutableSetOf()

    fun isFollowing(other: User): Boolean = following.any { it.id == other.id }
}

@Entity
@Table(name = "articles", indexes = [Index(columnList = "createdAt"), Index(columnList = "author_id")])
class Article(
    @Column(nullable = false, unique = true) var slug: String,
    @Column(nullable = false) var title: String,
    @Column(nullable = false) var description: String,
    @Column(nullable = false, columnDefinition = "text") var body: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) var author: User,
    @Column(nullable = false) var createdAt: Long,
    @Column(nullable = false) var updatedAt: Long,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
) {
    @ElementCollection
    @CollectionTable(name = "article_tags", joinColumns = [JoinColumn(name = "article_id")])
    @OrderColumn(name = "position")
    @Column(name = "tag", nullable = false)
    var tagList: MutableList<String> = mutableListOf()

    @ManyToMany
    @JoinTable(
        name = "favorites",
        joinColumns = [JoinColumn(name = "article_id")],
        inverseJoinColumns = [JoinColumn(name = "user_id")],
    )
    var favoritedBy: MutableSet<User> = mutableSetOf()

    fun isFavoritedBy(user: User): Boolean = favoritedBy.any { it.id == user.id }
}

@Entity
@Table(name = "comments", indexes = [Index(columnList = "article_id")])
class Comment(
    @Column(nullable = false, columnDefinition = "text") var body: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) var article: Article,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) var author: User,
    @Column(nullable = false) var createdAt: Long,
    @Column(nullable = false) var updatedAt: Long,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
)

interface UserRepository : JpaRepository<User, Long> {
    fun findByUsername(username: String): User?
    fun findByEmail(email: String): User?
    fun existsByUsername(username: String): Boolean
    fun existsByEmail(email: String): Boolean
}

interface ArticleRepository : JpaRepository<Article, Long> {
    fun findBySlug(slug: String): Article?
    fun existsBySlug(slug: String): Boolean

    @Query("select distinct t from Article a join a.tagList t order by t")
    fun findAllTags(): List<String>
}

interface CommentRepository : JpaRepository<Comment, Long> {
    fun findByArticleOrderByIdAsc(article: Article): List<Comment>

    @Modifying
    @Query("delete from Comment c where c.article = :article")
    fun deleteByArticle(article: Article): Int
}
