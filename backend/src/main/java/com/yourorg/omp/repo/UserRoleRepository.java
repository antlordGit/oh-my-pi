package com.yourorg.omp.repo;

import com.yourorg.omp.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {
    List<UserRole> findByUserId(Long userId);

    @Query("SELECT ur.roleId FROM UserRole ur WHERE ur.userId = :userId")
    Set<Long> findRoleIdsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM UserRole ur WHERE ur.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    boolean existsByUserIdAndRoleId(Long userId, Long roleId);

    @Modifying
    @Query("DELETE FROM UserRole ur WHERE ur.userId = :userId AND ur.roleId = :roleId")
    void deleteByUserIdAndRoleId(@Param("userId") Long userId, @Param("roleId") Long roleId);

    List<UserRole> findByRoleId(Long roleId);
}
