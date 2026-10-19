package com.realworld;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100000)
    private String body;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Article article;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }
    public Article getArticle() { return article; }
    public void setArticle(Article article) { this.article = article; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
