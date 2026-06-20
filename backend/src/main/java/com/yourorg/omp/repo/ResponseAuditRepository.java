package com.yourorg.omp.repo;

import com.yourorg.omp.entity.ResponseAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResponseAuditRepository extends JpaRepository<ResponseAudit, Long> {
    @Query("""
            SELECT r FROM ResponseAudit r
            WHERE (:sessionId IS NULL OR r.sessionId = :sessionId)
              AND (:userId IS NULL OR r.userId = :userId)
              AND (:tenantId IS NULL OR r.tenantId = :tenantId)
            ORDER BY r.finishedAt DESC
            """)
    Page<ResponseAudit> search(@Param("sessionId") String sessionId,
                               @Param("userId") Long userId,
                               @Param("tenantId") Long tenantId,
                               Pageable pageable);
}