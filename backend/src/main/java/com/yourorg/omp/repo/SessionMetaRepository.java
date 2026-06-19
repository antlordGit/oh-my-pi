package com.yourorg.omp.repo;

import com.yourorg.omp.entity.SessionMeta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SessionMetaRepository extends JpaRepository<SessionMeta, Long> {
    Optional<SessionMeta> findBySessionId(String sessionId);
    List<SessionMeta> findByUserIdOrderByLastActiveAtDesc(Long userId);
    List<SessionMeta> findByUserIdAndRepoIdOrderByLastActiveAtDesc(Long userId, String repoId);
    List<SessionMeta> findByStatus(String status);
    long countByUserIdAndStatus(Long userId, String status);
}