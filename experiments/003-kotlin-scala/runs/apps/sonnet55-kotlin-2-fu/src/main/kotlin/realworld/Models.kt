package realworld

import jakarta.persistence.*

@Entity
@Table(name = "users")
class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(unique = true, nullable = false) var username: String = "",
    @Column(unique = true, nullable = false) var email: String = "",
    @Column(nullable = false) var passwordHash: String = "",
    var bio: String? = null,
    var image: String? = null,
)

@Entity
@Table(name = "articles", indexes = [Index(columnList = "authorId"), Index(columnList = "createdAt")])
class Article(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(unique = true, nullable = false) var slug: String = "",
    @Column(nullable = false) var title: String = "",
    @Column(nullable = false) var description: String = "",
    @Column(nullable = false, length = 1_000_000) var body: String = "",
    @Column(nullable = false) var authorId: Long = 0,
    @Column(nullable = false) var createdAt: Long = 0,
    @Column(nullable = false) var updatedAt: Long = 0,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "article_tags", joinColumns = [JoinColumn(name = "article_id")], indexes = [Index(columnList = "tag")])
    @OrderColumn(name = "position")
    @Column(name = "tag")
    var tagList: MutableList<String> = mutableListOf(),
)

@Entity
@Table(name = "comments", indexes = [Index(columnList = "articleId")])
class Comment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false) var articleId: Long = 0,
    @Column(nullable = false) var authorId: Long = 0,
    @Column(nullable = false, length = 1_000_000) var body: String = "",
    @Column(nullable = false) var createdAt: Long = 0,
    @Column(nullable = false) var updatedAt: Long = 0,
)

@Entity
@Table(name = "favorites", uniqueConstraints = [UniqueConstraint(columnNames = ["userId", "articleId"])], indexes = [Index(columnList = "articleId")])
class Favorite(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false) var userId: Long = 0,
    @Column(nullable = false) var articleId: Long = 0,
)

@Entity
@Table(name = "follows", uniqueConstraints = [UniqueConstraint(columnNames = ["followerId", "followeeId"])], indexes = [Index(columnList = "followeeId")])
class Follow(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false) var followerId: Long = 0,
    @Column(nullable = false) var followeeId: Long = 0,
)
