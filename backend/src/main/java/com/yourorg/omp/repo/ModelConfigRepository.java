package com.yourorg.omp.repo;

import com.yourorg.omp.entity.ModelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelConfigRepository extends JpaRepository<ModelConfigEntity, Long> {

    Optional<ModelConfigEntity> findByActiveTrue();

    List<ModelConfigEntity> findAllByOrderBySortOrderAscCreatedAtDesc();

    List<ModelConfigEntity> findAllByOrderByCreatedAtDesc();

    Optional<ModelConfigEntity> findByConfigName(String configName);
}
