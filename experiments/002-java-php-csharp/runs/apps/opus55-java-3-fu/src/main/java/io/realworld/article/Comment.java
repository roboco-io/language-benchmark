package io.realworld.article;

import io.realworld.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    protected Comment() {
    }

    public Comment(String body, Article article, User author, long now) {
        this.body = body;
        this.article = article;
        this.author = author;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Replaces the body and refreshes updatedAt; id and createdAt are unchanged. */
    public void updateBody(String body, long now) {
        this.body = body;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getBody() {
        return body;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public Article getArticle() {
        return article;
    }

    public User getAuthor() {
        return author;
    }
}
