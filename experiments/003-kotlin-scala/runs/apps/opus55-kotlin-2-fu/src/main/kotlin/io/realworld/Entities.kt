package io.realworld

import jakarta.persistence.*

@Entity
@Table(name = "users")
class UserEntity(
    @Column(nullable = false, unique = true) var username: String,
    @Column(nullable = false, unique = true) var email: String,
    @Column(nullable = false) var passwordHash: String,
    var bio: String? = null,
    var image: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
)

@Entity
@Table(name = "articles", indexes = [Index(columnList = "authorId"), Index(columnList = "createdAt")])
class ArticleEntity(
    @Column(nullable = false, unique = true) var slug: String,
    @Column(nullable = false) var title: String,
    @Column(nullable = false) var description: String,
    @Column(nullable = false, columnDefinition = "TEXT") var body: String,
    @Column(nullable = false) var authorId: Long,
    /** Epoch milliseconds (UTC). */
    @Column(nullable = false) var createdAt: Long,
    /** Epoch milliseconds (UTC). */
    @Column(nullable = false) var updatedAt: Long,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "article_tags", joinColumns = [JoinColumn(name = "article_id")], indexes = [Index(columnList = "tag")])
    @OrderColumn(name = "position")
    @Column(name = "tag", nullable = false)
    var tagList: MutableList<String> = mutableListOf(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
)

@Entity
@Table(name = "follows", uniqueConstraints = [UniqueConstraint(columnNames = ["followerId", "followeeId"])])
class FollowEntity(
    @Column(nullable = false) var followerId: Long,
    @Column(nullable = false) var followeeId: Long,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
)

@Entity
@Table(
    name = "favorites",
    uniqueConstraints = [UniqueConstraint(columnNames = ["userId", "articleId"])],
    indexes = [Index(columnList = "articleId")],
)
class FavoriteEntity(
    @Column(nullable = false) var userId: Long,
    @Column(nullable = false) var articleId: Long,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
)

@Entity
@Table(name = "comments", indexes = [Index(columnList = "articleId")])
class CommentEntity(
    @Column(nullable = false) var articleId: Long,
    @Column(nullable = false) var authorId: Long,
    @Column(nullable = false, columnDefinition = "TEXT") var body: String,
    @Column(nullable = false) var createdAt: Long,
    @Column(nullable = false) var updatedAt: Long,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
)
