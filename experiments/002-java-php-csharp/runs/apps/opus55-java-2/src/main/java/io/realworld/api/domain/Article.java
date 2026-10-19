package io.realworld.api.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "articles", indexes = @Index(name = "idx_articles_created_at", columnList = "created_at"))
public class Article {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /** Creation and update times are stored as epoch milliseconds to keep full precision in SQLite. */
    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    /** Tags in the order the client supplied them. */
    @ManyToMany
    @JoinTable(name = "article_tags",
            joinColumns = @JoinColumn(name = "article_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    @OrderColumn(name = "position")
    private List<Tag> tags = new ArrayList<>();

    @ManyToMany
    @JoinTable(name = "favorites",
            joinColumns = @JoinColumn(name = "article_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> favoritedBy = new HashSet<>();

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }
    public List<Tag> getTags() { return tags; }
    public Set<User> getFavoritedBy() { return favoritedBy; }

    public boolean isFavoritedBy(User user) {
        return user != null && favoritedBy.stream().anyMatch(u -> u.getId().equals(user.getId()));
    }
}
