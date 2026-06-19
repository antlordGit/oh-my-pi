package com.yourorg.omp.repo;

import com.yourorg.omp.entity.PromptAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromptAuditRepository extends JpaRepository<PromptAudit, Long> {
    @Query("""
            SELECT p FROM PromptAudit p
            WHERE (:sessionId IS NULL OR p.sessionId = :sessionId)
              AND (:userId IS NULL OR p.userId = :userId)
            ORDER BY p.sentAt DESC
            """)
    Page<PromptAudit> search(@Param("sessionId") String sessionId,
                             @Param("userId") Long userId,
                             Pageable pageable);
}