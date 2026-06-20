package com.yourorg.omp.repo;

import com.yourorg.omp.entity.RoleMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface RoleMenuRepository extends JpaRepository<RoleMenu, Long> {
    List<RoleMenu> findByRoleId(Long roleId);

    @Query("SELECT rm.menuId FROM RoleMenu rm WHERE rm.roleId = :roleId")
    Set<Long> findMenuIdsByRoleId(@Param("roleId") Long roleId);

    @Modifying
    @Query("DELETE FROM RoleMenu rm WHERE rm.roleId = :roleId")
    void deleteByRoleId(@Param("roleId") Long roleId);

    @Query("SELECT rm.menuId FROM RoleMenu rm WHERE rm.roleId IN :roleIds")
    Set<Long> findMenuIdsByRoleIds(@Param("roleIds") Set<Long> roleIds);
}
