package com.yourorg.omp.repo;

import com.yourorg.omp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    /** 原子累加用户已消耗 Token，避免并发覆盖。 */
    @Modifying
    @Query("UPDATE User u SET u.tokenUsed = u.tokenUsed + :delta WHERE u.id = :userId")
    void addTokenUsed(@Param("userId") Long userId, @Param("delta") long delta);
}
