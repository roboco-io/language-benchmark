package io.realworld.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    List<Comment> findByArticleOrderByCreatedAtAscIdAsc(Article article);
}
