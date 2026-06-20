package com.yourorg.omp.repo;

import com.yourorg.omp.entity.SessionMeta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface SessionMetaRepository extends JpaRepository<SessionMeta, Long> {
    Optional<SessionMeta> findBySessionId(String sessionId);
    List<SessionMeta> findByUserIdOrderByLastActiveAtDesc(Long userId);
    List<SessionMeta> findByUserIdAndRepoIdOrderByLastActiveAtDesc(Long userId, String repoId);
    List<SessionMeta> findByStatus(String status);
    long countByUserIdAndStatus(Long userId, String status);

    // ---- 三级数据范围查询（userId/tenantId 为 null 表示不限）----

    @Query("""
            SELECT s FROM SessionMeta s
            WHERE (:userId IS NULL OR s.userId = :userId)
              AND (:tenantId IS NULL OR s.tenantId = :tenantId)
            ORDER BY s.lastActiveAt DESC
            """)
    List<SessionMeta> findScoped(@Param("userId") Long userId,
                                 @Param("tenantId") Long tenantId);

    @Query("""
            SELECT s FROM SessionMeta s
            WHERE (:userId IS NULL OR s.userId = :userId)
              AND (:tenantId IS NULL OR s.tenantId = :tenantId)
            ORDER BY s.lastActiveAt DESC
            """)
    Page<SessionMeta> findScopedPaged(@Param("userId") Long userId,
                                       @Param("tenantId") Long tenantId,
                                       Pageable pageable);

    @Query("""
            SELECT s FROM SessionMeta s
            WHERE (:userId IS NULL OR s.userId = :userId)
              AND (:tenantId IS NULL OR s.tenantId = :tenantId)
              AND s.repoId = :repoId
            ORDER BY s.lastActiveAt DESC
            """)
    List<SessionMeta> findScopedByRepo(@Param("userId") Long userId,
                                       @Param("tenantId") Long tenantId,
                                       @Param("repoId") String repoId);

    @Query("""
            SELECT s FROM SessionMeta s
            WHERE s.sessionId = :sessionId
              AND (:userId IS NULL OR s.userId = :userId)
              AND (:tenantId IS NULL OR s.tenantId = :tenantId)
            """)
    Optional<SessionMeta> findScopedBySessionId(@Param("sessionId") String sessionId,
                                                @Param("userId") Long userId,
                                                @Param("tenantId") Long tenantId);
}
