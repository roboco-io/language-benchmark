package io.realworld.article;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    Optional<Article> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** Rows of [articleId, favoritesCount] for the given articles that have at least one favorite. */
    @Query("select a.id, count(u) from Article a join a.favoritedBy u where a.id in :ids group by a.id")
    List<Object[]> favoriteCounts(@Param("ids") Collection<Long> ids);

    /** Returns which of the given articles the user has favorited. */
    @Query("select a.id from Article a join a.favoritedBy u where u.id = :userId and a.id in :ids")
    List<Long> favoritedAmong(@Param("userId") long userId, @Param("ids") Collection<Long> ids);

    @Query("select distinct t from Article a join a.tagList t order by t")
    List<String> allTags();
}
