package com.example.realworld;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "articles")
public class Article {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true)
    public String slug;

    @Column(nullable = false)
    public String title;

    @Column(nullable = false)
    public String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String body;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    public User author;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "article_tags", indexes = @Index(columnList = "tag"))
    @OrderColumn(name = "position")
    @Column(name = "tag", nullable = false)
    public List<String> tags = new ArrayList<>();

    /** Epoch milliseconds. */
    public long createdAt;
    public long updatedAt;
}
