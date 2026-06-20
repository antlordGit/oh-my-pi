package com.yourorg.omp.repo;

import com.yourorg.omp.entity.Repo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RepoRepository extends JpaRepository<Repo, Long> {
    List<Repo> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<Repo> findByUserIdAndRepoId(Long userId, String repoId);

    // ---- 三级数据范围查询（userId/tenantId 为 null 表示不限）----

    @Query("""
            SELECT r FROM Repo r
            WHERE (:userId IS NULL OR r.userId = :userId)
              AND (:tenantId IS NULL OR r.tenantId = :tenantId)
            ORDER BY r.createdAt DESC
            """)
    List<Repo> findScoped(@Param("userId") Long userId,
                          @Param("tenantId") Long tenantId);

    @Query("""
            SELECT r FROM Repo r
            WHERE r.repoId = :repoId
              AND (:userId IS NULL OR r.userId = :userId)
              AND (:tenantId IS NULL OR r.tenantId = :tenantId)
            """)
    Optional<Repo> findScopedByRepoId(@Param("repoId") String repoId,
                                      @Param("userId") Long userId,
                                      @Param("tenantId") Long tenantId);
}
