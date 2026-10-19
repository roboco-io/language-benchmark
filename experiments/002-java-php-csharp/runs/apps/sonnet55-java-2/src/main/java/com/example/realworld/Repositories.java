package com.example.realworld;

import com.example.realworld.Entities.*;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public final class Repositories {
    private Repositories() {}

    public interface UserRepo extends JpaRepository<User, Long> {
        Optional<User> findByEmail(String email);
        Optional<User> findByUsername(String username);
        boolean existsByEmail(String email);
        boolean existsByUsername(String username);
    }

    public interface ArticleRepo extends JpaRepository<Article, Long> {
        Optional<Article> findBySlug(String slug);

        @Query("select distinct t from Article a join a.tagList t order by t")
        List<String> allTags();
    }

    public interface CommentRepo extends JpaRepository<Comment, Long> {
        List<Comment> findByArticleIdOrderByIdAsc(Long articleId);

        @Modifying
        @Query("delete from Comment c where c.article.id = :id")
        void deleteByArticle(@Param("id") Long id);
    }

    public interface FollowRepo extends JpaRepository<Follow, Long> {
        boolean existsByFollowerIdAndFolloweeId(Long followerId, Long followeeId);
        void deleteByFollowerIdAndFolloweeId(Long followerId, Long followeeId);

        @Query("select f.followeeId from Follow f where f.followerId = :uid and f.followeeId in :ids")
        List<Long> followedAmong(@Param("uid") Long uid, @Param("ids") Collection<Long> ids);
    }

    public interface FavoriteRepo extends JpaRepository<Favorite, Long> {
        boolean existsByUserIdAndArticleId(Long userId, Long articleId);
        void deleteByUserIdAndArticleId(Long userId, Long articleId);
        long countByArticleId(Long articleId);

        @Modifying
        @Query("delete from Favorite f where f.articleId = :id")
        void deleteByArticle(@Param("id") Long id);

        @Query("select f.articleId from Favorite f where f.userId = :uid and f.articleId in :ids")
        List<Long> favoritedAmong(@Param("uid") Long uid, @Param("ids") Collection<Long> ids);

        @Query("select f.articleId, count(f) from Favorite f where f.articleId in :ids group by f.articleId")
        List<Object[]> countsFor(@Param("ids") Collection<Long> ids);
    }
}
