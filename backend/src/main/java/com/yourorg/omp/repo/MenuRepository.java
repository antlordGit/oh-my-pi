package com.yourorg.omp.repo;

import com.yourorg.omp.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MenuRepository extends JpaRepository<Menu, Long> {
    Optional<Menu> findByMenuCode(String menuCode);

    List<Menu> findByParentIdOrderBySortOrder(Long parentId);

    @Query("SELECT m FROM Menu m WHERE m.parentId IS NULL ORDER BY m.sortOrder")
    List<Menu> findRootMenus();

    List<Menu> findByEnabledTrueOrderBySortOrder();

    @Query("SELECT m FROM Menu m WHERE m.id IN :ids ORDER BY m.sortOrder")
    List<Menu> findByIdsOrderBySortOrder(@Param("ids") Set<Long> ids);

    @Query("SELECT m FROM Menu m WHERE m.parentId IS NOT NULL ORDER BY m.parentId, m.sortOrder")
    List<Menu> findChildMenus();
}
