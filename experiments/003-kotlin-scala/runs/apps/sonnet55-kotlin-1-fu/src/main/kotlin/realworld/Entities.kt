package realworld

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "users")
class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(nullable = false, unique = true) var email: String = "",
    @Column(nullable = false, unique = true) var username: String = "",
    @Column(nullable = false) var passwordHash: String = "",
    @Column(length = 4000) var bio: String? = null,
    @Column(length = 2000) var image: String? = null,
)

@Entity
@Table(name = "articles", indexes = [Index(columnList = "authorId"), Index(columnList = "createdAt")])
class Article(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(nullable = false, unique = true) var slug: String = "",
    @Column(nullable = false) var title: String = "",
    @Column(nullable = false, length = 4000) var description: String = "",
    @Column(nullable = false, length = 100000) var body: String = "",
    @Column(nullable = false) var authorId: Long = 0,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = Instant.now(),
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "article_tags", joinColumns = [JoinColumn(name = "article_id")], indexes = [Index(columnList = "tag")])
    @Column(name = "tag", nullable = false)
    var tags: MutableSet<String> = mutableSetOf(),
)

@Entity
@Table(name = "comments", indexes = [Index(columnList = "articleId")])
class Comment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(nullable = false) var articleId: Long = 0,
    @Column(nullable = false) var authorId: Long = 0,
    @Column(nullable = false, length = 100000) var body: String = "",
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "follows", uniqueConstraints = [UniqueConstraint(columnNames = ["followerId", "followeeId"])])
class Follow(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(nullable = false) var followerId: Long = 0,
    @Column(nullable = false) var followeeId: Long = 0,
)

@Entity
@Table(
    name = "favorites",
    uniqueConstraints = [UniqueConstraint(columnNames = ["userId", "articleId"])],
    indexes = [Index(columnList = "articleId")],
)
class Favorite(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(nullable = false) var userId: Long = 0,
    @Column(nullable = false) var articleId: Long = 0,
)
