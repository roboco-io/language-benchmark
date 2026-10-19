package io.realworld.api.domain;

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

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    private String bio;
    private String image;

    /** Users that this user follows. */
    @ManyToMany
    @JoinTable(name = "follows",
            joinColumns = @JoinColumn(name = "follower_id"),
            inverseJoinColumns = @JoinColumn(name = "followee_id"))
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

    public boolean isFollowing(User other) {
        return other != null && following.stream().anyMatch(u -> u.getId().equals(other.getId()));
    }
}
