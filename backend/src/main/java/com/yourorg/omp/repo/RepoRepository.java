package com.yourorg.omp.repo;

import com.yourorg.omp.entity.Repo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepoRepository extends JpaRepository<Repo, Long> {
    List<Repo> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<Repo> findByUserIdAndRepoId(Long userId, String repoId);
}