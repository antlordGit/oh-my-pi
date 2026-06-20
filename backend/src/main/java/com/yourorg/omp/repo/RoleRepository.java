package com.yourorg.omp.repo;

import com.yourorg.omp.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    List<Role> findByTenantId(Long tenantId);
    List<Role> findByTenantIdAndEnabledTrue(Long tenantId);
    Optional<Role> findByTenantIdAndRoleCode(Long tenantId, String roleCode);
    Optional<Role> findByTenantIdAndRoleName(Long tenantId, String roleName);
}
