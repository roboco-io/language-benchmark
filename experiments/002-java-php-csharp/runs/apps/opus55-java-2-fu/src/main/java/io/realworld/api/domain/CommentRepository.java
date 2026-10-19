package io.realworld.api.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    List<Comment> findByArticleOrderByCreatedAtAscIdAsc(Article article);

    @Modifying
    @Query("delete from Comment c where c.article = ?1")
    void deleteByArticle(Article article);
}
