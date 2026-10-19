package io.realworld.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByUsernameAndIdNot(String username, Long id);

    boolean existsByEmailAndIdNot(String email, Long id);

    /** Returns which of the given user ids the follower follows. */
    @Query("select f.id from User u join u.following f where u.id = :followerId and f.id in :ids")
    List<Long> followedAmong(@Param("followerId") long followerId, @Param("ids") Collection<Long> ids);

    @Query("select f.id from User u join u.following f where u.id = :followerId")
    List<Long> followedIds(@Param("followerId") long followerId);
}
