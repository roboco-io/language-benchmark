package com.example.realworld;

import jakarta.persistence.*;

@Entity
@Table(name = "follows", uniqueConstraints = @UniqueConstraint(columnNames = {"followerId", "followedId"}))
public class Follow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long followerId;

    @Column(nullable = false)
    public Long followedId;
}
