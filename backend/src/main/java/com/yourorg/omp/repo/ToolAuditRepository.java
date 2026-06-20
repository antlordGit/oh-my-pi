package com.yourorg.omp.repo;

import com.yourorg.omp.entity.ToolAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ToolAuditRepository extends JpaRepository<ToolAudit, Long> {
    @Query("""
            SELECT t FROM ToolAudit t
            WHERE (:sessionId IS NULL OR t.sessionId = :sessionId)
              AND (:userId IS NULL OR t.userId = :userId)
              AND (:tenantId IS NULL OR t.tenantId = :tenantId)
              AND (:toolName IS NULL OR t.toolName = :toolName)
            ORDER BY t.startedAt DESC
            """)
    Page<ToolAudit> search(@Param("sessionId") String sessionId,
                           @Param("userId") Long userId,
                           @Param("tenantId") Long tenantId,
                           @Param("toolName") String toolName,
                           Pageable pageable);
}