package com.yourorg.omp.rest;

import com.yourorg.omp.service.SystemService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统管理 REST API：用户、租户、角色、菜单管理。
 * 访问需要 admin 或 super_admin 身份（由 SecurityConfig 控制）。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final SystemService sys;

    public SystemController(SystemService sys) {
        this.sys = sys;
    }

    // ==================== 用户管理 ====================

    public record CreateUserRequest(@NotBlank String username, String name, @NotBlank String password,
                                    String identityLevel, Long tenantId, List<Long> roleIds) {}
    public record UpdateUserRequest(String name, String identityLevel, Long tenantId, Boolean enabled) {}
    public record AssignUserRolesRequest(List<Long> roleIds) {}

    @GetMapping("/users")
    public List<Map<String, Object>> listUsers() {
        return sys.listUsers();
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> getUser(@PathVariable Long id) {
        return sys.getUserDetail(id);
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody CreateUserRequest req) {
        return sys.createUser(req.username(), req.name(), req.password(), req.identityLevel(),
                req.tenantId(), req.roleIds());
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> updateUser(@PathVariable Long id, @RequestBody UpdateUserRequest req) {
        return sys.updateUser(id, req.name(), req.identityLevel(), req.tenantId(), req.enabled());
    }

    @PutMapping("/users/{id}/roles")
    public Map<String, Object> assignUserRoles(@PathVariable Long id, @RequestBody AssignUserRolesRequest req) {
        return sys.assignUserRoles(id, req.roleIds());
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable Long id) {
        boolean ok = sys.deleteUser(id);
        if (!ok) throw new IllegalArgumentException("用户不存在");
        return Map.of("ok", true);
    }

    // ==================== 租户管理 ====================

    public record CreateTenantRequest(@NotBlank String tenantCode, String tenantName, String description) {}
    public record UpdateTenantRequest(String tenantName, String description, Boolean enabled) {}

    @GetMapping("/tenants")
    public List<Map<String, Object>> listTenants() {
        return sys.listTenants();
    }

    @PostMapping("/tenants")
    public Map<String, Object> createTenant(@RequestBody CreateTenantRequest req) {
        return sys.createTenant(req.tenantCode(), req.tenantName(), req.description());
    }

    @PutMapping("/tenants/{id}")
    public Map<String, Object> updateTenant(@PathVariable Long id, @RequestBody UpdateTenantRequest req) {
        return sys.updateTenant(id, req.tenantName(), req.description(), req.enabled());
    }

    @DeleteMapping("/tenants/{id}")
    public Map<String, Object> deleteTenant(@PathVariable Long id) {
        boolean ok = sys.deleteTenant(id);
        if (!ok) throw new IllegalArgumentException("租户不存在");
        return Map.of("ok", true);
    }

    // ==================== 角色管理 ====================

    public record CreateRoleRequest(@NotBlank String roleCode, @NotBlank String roleName,
                                     String description, Long tenantId) {}
    public record UpdateRoleRequest(String roleName, String description, Boolean enabled) {}
    public record AssignRoleMenusRequest(Set<Long> menuIds) {}

    @GetMapping("/roles")
    public List<Map<String, Object>> listRoles() {
        return sys.listRoles();
    }

    @GetMapping("/roles/{id}")
    public Map<String, Object> getRole(@PathVariable Long id) {
        return sys.getRoleDetail(id);
    }

    @PostMapping("/roles")
    public Map<String, Object> createRole(@RequestBody CreateRoleRequest req) {
        return sys.createRole(req.roleCode(), req.roleName(), req.description(), req.tenantId());
    }

    @PutMapping("/roles/{id}")
    public Map<String, Object> updateRole(@PathVariable Long id, @RequestBody UpdateRoleRequest req) {
        return sys.updateRole(id, req.roleName(), req.description(), req.enabled());
    }

    @PutMapping("/roles/{id}/menus")
    public Map<String, Object> assignRoleMenus(@PathVariable Long id, @RequestBody AssignRoleMenusRequest req) {
        return sys.assignRoleMenus(id, req.menuIds());
    }

    @DeleteMapping("/roles/{id}")
    public Map<String, Object> deleteRole(@PathVariable Long id) {
        boolean ok = sys.deleteRole(id);
        if (!ok) throw new IllegalArgumentException("角色不存在");
        return Map.of("ok", true);
    }

    // ==================== 菜单管理 ====================

    public record CreateMenuRequest(@NotBlank String menuCode, @NotBlank String menuName,
                                     String menuType, Long parentId, String path,
                                     String component, String icon, Integer sortOrder, String permission) {}
    public record UpdateMenuRequest(String menuName, String menuType, Long parentId,
                                     String path, String component, String icon,
                                     Integer sortOrder, String permission, Boolean enabled) {}

    @GetMapping("/menus")
    public List<Map<String, Object>> listMenus(@RequestParam(defaultValue = "true") boolean tree) {
        return sys.listMenus(tree);
    }

    @PostMapping("/menus")
    public Map<String, Object> createMenu(@RequestBody CreateMenuRequest req) {
        return sys.createMenu(req.menuCode(), req.menuName(), req.menuType(),
                req.parentId(), req.path(), req.component(),
                req.icon(), req.sortOrder(), req.permission());
    }

    @PutMapping("/menus/{id}")
    public Map<String, Object> updateMenu(@PathVariable Long id, @RequestBody UpdateMenuRequest req) {
        return sys.updateMenu(id, req.menuName(), req.menuType(), req.parentId(),
                req.path(), req.component(), req.icon(),
                req.sortOrder(), req.permission(), req.enabled());
    }

    @DeleteMapping("/menus/{id}")
    public Map<String, Object> deleteMenu(@PathVariable Long id) {
        boolean ok = sys.deleteMenu(id);
        if (!ok) throw new IllegalArgumentException("菜单不存在");
        return Map.of("ok", true);
    }

    // ==================== 模型配置管理 ====================

    public record CreateModelConfigRequest(@NotBlank String configName, String displayName,
                                           @NotBlank String provider, @NotBlank String modelId,
                                           String baseUrl, String api, String apiKey, String configJson,
                                           String remark) {}
    public record UpdateModelConfigRequest(String configName, String displayName, String provider,
                                           String modelId, String baseUrl, String api,
                                           String apiKey, String configJson, String remark) {}

    @GetMapping("/model-configs")
    public List<Map<String, Object>> listModelConfigs() {
        return sys.listModelConfigs();
    }

    @GetMapping("/model-configs/{id}")
    public Map<String, Object> getModelConfig(@PathVariable Long id) {
        return sys.getModelConfig(id);
    }

    @PostMapping("/model-configs")
    public Map<String, Object> createModelConfig(@RequestBody CreateModelConfigRequest req) {
        return sys.createModelConfig(req.configName(), req.displayName(), req.provider(), req.modelId(),
                req.baseUrl(), req.api(), req.apiKey(), req.configJson(), req.remark());
    }

    @PutMapping("/model-configs/{id}")
    public Map<String, Object> updateModelConfig(@PathVariable Long id, @RequestBody UpdateModelConfigRequest req) {
        return sys.updateModelConfig(id, req.configName(), req.displayName(), req.provider(), req.modelId(),
                req.baseUrl(), req.api(), req.apiKey(), req.configJson(), req.remark());
    }

    @PutMapping("/model-configs/{id}/activate")
    public Map<String, Object> activateModelConfig(@PathVariable Long id) {
        return sys.activateModelConfig(id);
    }

    @DeleteMapping("/model-configs/{id}")
    public Map<String, Object> deleteModelConfig(@PathVariable Long id) {
        boolean ok = sys.deleteModelConfig(id);
        if (!ok) throw new IllegalArgumentException("模型配置不存在");
        return Map.of("ok", true);
    }

    // ==================== 当前用户权限 ====================

    @GetMapping("/me/menus")
    public List<Map<String, Object>> getMyMenus() {
        return sys.getMyMenus();
    }

    @GetMapping("/me/permissions")
    public List<String> getMyPermissions() {
        return sys.getMyPermissions();
    }

    @GetMapping("/me")
    public Map<String, Object> getMyInfo() {
        return sys.getMyInfo();
    }
}
