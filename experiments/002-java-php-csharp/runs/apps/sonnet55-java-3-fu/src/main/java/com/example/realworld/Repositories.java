package com.example.realworld;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}

interface ArticleRepository extends JpaRepository<Article, Long> {
    Optional<Article> findBySlug(String slug);

    @Query("select distinct t from Article a join a.tags t order by t")
    List<String> findAllTags();
}

interface CommentRepository extends JpaRepository<Comment, Long> {
    List<Comment> findByArticleIdOrderByIdDesc(Long articleId);

    @Modifying
    void deleteByArticleId(Long articleId);
}

interface FollowRepository extends JpaRepository<Follow, Long> {
    boolean existsByFollowerIdAndFollowedId(Long followerId, Long followedId);

    @Modifying
    void deleteByFollowerIdAndFollowedId(Long followerId, Long followedId);
}

interface FavoriteRepository extends JpaRepository<Favorite, Long> {
    boolean existsByUserIdAndArticleId(Long userId, Long articleId);

    long countByArticleId(Long articleId);

    @Modifying
    void deleteByUserIdAndArticleId(Long userId, Long articleId);

    @Modifying
    void deleteByArticleId(Long articleId);
}
