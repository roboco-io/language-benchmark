package com.example.realworld;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.BatchSize;

public final class Entities {
    private Entities() {}

    @Entity(name = "User")
    @Table(name = "users")
    public static class User {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(nullable = false, unique = true) public String email;
        @Column(nullable = false, unique = true) public String username;
        @Column(nullable = false) public String passwordHash;
        public String bio;
        public String image;
    }

    @Entity(name = "Article")
    @Table(name = "articles", indexes = @Index(columnList = "createdAt"))
    public static class Article {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(nullable = false, unique = true) public String slug;
        @Column(nullable = false) public String title;
        @Column(nullable = false) public String description;
        @Column(nullable = false, columnDefinition = "TEXT") public String body;
        public long createdAt;
        public long updatedAt;
        @ManyToOne(optional = false) public User author;

        @ElementCollection(fetch = FetchType.LAZY)
        @CollectionTable(name = "article_tags", joinColumns = @JoinColumn(name = "article_id"))
        @Column(name = "tag", nullable = false)
        @OrderColumn(name = "position")
        @BatchSize(size = 50)
        public List<String> tagList = new ArrayList<>();
    }

    @Entity(name = "Comment")
    @Table(name = "comments", indexes = @Index(columnList = "article_id"))
    public static class Comment {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(nullable = false, columnDefinition = "TEXT") public String body;
        public long createdAt;
        public long updatedAt;
        @ManyToOne(optional = false) @JoinColumn(name = "article_id") public Article article;
        @ManyToOne(optional = false) public User author;
    }

    @Entity(name = "Follow")
    @Table(name = "follows", uniqueConstraints = @UniqueConstraint(columnNames = {"followerId", "followeeId"}))
    public static class Follow {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public Long followerId;
        public Long followeeId;
    }

    @Entity(name = "Favorite")
    @Table(name = "favorites", uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "articleId"}),
            indexes = @Index(columnList = "articleId"))
    public static class Favorite {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public Long userId;
        public Long articleId;
    }
}
