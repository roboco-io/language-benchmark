package com.realworld;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    List<Comment> findByArticleOrderByIdAsc(Article article);

    @Modifying
    @Query("delete from Comment c where c.article = :article")
    void deleteByArticle(Article article);
}
