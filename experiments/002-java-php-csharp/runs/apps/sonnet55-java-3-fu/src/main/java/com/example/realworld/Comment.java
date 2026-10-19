package com.example.realworld;

import jakarta.persistence.*;

@Entity
@Table(name = "comments", indexes = @Index(columnList = "articleId"))
public class Comment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long articleId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    public User author;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String body;

    public long createdAt;
    public long updatedAt;
}
