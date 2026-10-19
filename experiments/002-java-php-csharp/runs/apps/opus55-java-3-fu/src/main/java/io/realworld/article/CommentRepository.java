package io.realworld.article;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @Query("select c from Comment c join fetch c.author where c.article = :article order by c.createdAt, c.id")
    List<Comment> findForArticle(@Param("article") Article article);

    @Modifying
    @Query("delete from Comment c where c.article = :article")
    void deleteForArticle(@Param("article") Article article);
}
