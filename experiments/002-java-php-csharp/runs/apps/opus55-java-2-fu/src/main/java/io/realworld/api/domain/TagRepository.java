package io.realworld.api.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByName(String name);

    /** Names of tags attached to at least one article. */
    @Query("select distinct t.name from Article a join a.tags t order by t.name")
    List<String> findUsedTagNames();
}
