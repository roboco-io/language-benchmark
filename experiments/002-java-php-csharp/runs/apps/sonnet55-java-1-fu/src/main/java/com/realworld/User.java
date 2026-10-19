package com.realworld;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    private String bio;
    private String image;

    @ManyToMany
    @JoinTable(name = "follows", joinColumns = @JoinColumn(name = "follower_id"),
            inverseJoinColumns = @JoinColumn(name = "followed_id"))
    private Set<User> following = new HashSet<>();

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public Set<User> getFollowing() { return following; }

    @Override
    public boolean equals(Object o) {
        return o instanceof User u && id != null && id.equals(u.id);
    }

    @Override
    public int hashCode() { return 31; }
}
