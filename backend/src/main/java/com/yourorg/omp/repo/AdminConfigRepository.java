package com.yourorg.omp.repo;

import com.yourorg.omp.entity.AdminConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminConfigRepository extends JpaRepository<AdminConfig, String> {
}