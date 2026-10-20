package io.realworld

import jakarta.persistence.*

@Entity
@Table(name = "users")
class User(
    @Column(nullable = false, unique = true) var username: String,
    @Column(nullable = false, unique = true) var email: String,
    @Column(name = "password_hash", nullable = false) var passwordHash: String,
    var bio: String? = null,
    var image: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToMany
    @JoinTable(
        name = "follows",
        joinColumns = [JoinColumn(name = "follower_id")],
        inverseJoinColumns = [JoinColumn(name = "followee_id")],
    )
    var following: MutableSet<User> = mutableSetOf()

    override fun equals(other: Any?) = other is User && id != null && id == other.id
    override fun hashCode() = id?.hashCode() ?: 0
}

@Entity
@Table(name = "articles")
class Article(
    @Column(nullable = false, unique = true) var slug: String,
    @Column(nullable = false) var title: String,
    @Column(nullable = false) var description: String,
    @Column(nullable = false) var body: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id")
    var author: User,
    @Column(name = "created_at", nullable = false) var createdAt: Long,
    @Column(name = "updated_at", nullable = false) var updatedAt: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ElementCollection
    @CollectionTable(name = "article_tags", joinColumns = [JoinColumn(name = "article_id")])
    @OrderColumn(name = "position")
    @Column(name = "tag")
    var tagList: MutableList<String> = mutableListOf()

    @ManyToMany
    @JoinTable(
        name = "favorites",
        joinColumns = [JoinColumn(name = "article_id")],
        inverseJoinColumns = [JoinColumn(name = "user_id")],
    )
    var favoritedBy: MutableSet<User> = mutableSetOf()
}

@Entity
@Table(name = "comments")
class Comment(
    @Column(nullable = false) var body: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id")
    var article: Article,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id")
    var author: User,
    @Column(name = "created_at", nullable = false) var createdAt: Long,
    @Column(name = "updated_at", nullable = false) var updatedAt: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
