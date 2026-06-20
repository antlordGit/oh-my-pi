package com.yourorg.omp.service;

import com.yourorg.omp.entity.*;
import com.yourorg.omp.repo.*;
import com.yourorg.omp.admin.AdminConfigService;
import com.yourorg.omp.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * 系统管理服务：用户、租户、角色、菜单的 CRUD 和权限查询。
 *
 * <p>权限策略：
 * <ul>
 *   <li>超级管理员 (super_admin)：可管理所有租户的所有数据</li>
 *   <li>管理员 (admin)：仅管理本租户数据，跨租户隔离</li>
 *   <li>服务方法通过 tenantFilter() 实现租户隔离</li>
 * </ul>
 */
@Service
public class SystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemService.class);

    private static final String DEFAULT_ROLE_CODE = "default_user";
    private static final String DEFAULT_ROLE_NAME = "普通用户";

    /** 统一按创建时间倒序排列。 */
    private static final Sort CREATED_DESC = Sort.by(Sort.Direction.DESC, "createdAt");

    /** 用户名规则：英文/数字/中划线，1-32 字符。仅用于登录。 */
    private static final java.util.regex.Pattern USERNAME_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9-]{1,32}$");

    private final CurrentUser currentUser;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final MenuRepository menus;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final RoleMenuRepository roleMenus;
    private final ModelConfigRepository modelConfigs;
    private final AdminConfigService adminConfig;
    private final BCryptPasswordEncoder encoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SystemService(CurrentUser currentUser,
                         UserRepository users,
                         TenantRepository tenants,
                         MenuRepository menus,
                         RoleRepository roles,
                         UserRoleRepository userRoles,
                         RoleMenuRepository roleMenus,
                         ModelConfigRepository modelConfigs,
                         AdminConfigService adminConfig,
                         BCryptPasswordEncoder encoder) {
        this.currentUser = currentUser;
        this.users = users;
        this.tenants = tenants;
        this.menus = menus;
        this.roles = roles;
        this.userRoles = userRoles;
        this.roleMenus = roleMenus;
        this.modelConfigs = modelConfigs;
        this.adminConfig = adminConfig;
        this.encoder = encoder;
    }

    // ==================== 租户过滤 ====================

    /**
     * 返回当前操作用户有权管理的租户 ID 范围。
     * 超级管理员返回 null（不限），管理员返回自己的 tenantId。
     */
    private Long tenantFilter() {
        User self = currentUser.require();
        if (self.isSuperAdmin()) return null; // 无限制
        return self.getTenantId();
    }

    // ==================== 用户管理 ====================

    public List<Map<String, Object>> listUsers() {
        Long filterTenantId = tenantFilter();
        // 预取租户名、角色名映射，避免逐行 N+1 查询
        Map<Long, String> tenantNames = new HashMap<>();
        for (Tenant t : tenants.findAll()) tenantNames.put(t.getId(), t.getTenantName());
        Map<Long, String> roleNameById = new HashMap<>();
        for (Role r : roles.findAll()) roleNameById.put(r.getId(), r.getRoleName());
        // 一次性取出全部 用户-角色 关联，聚合成 userId → 角色名列表
        Map<Long, List<String>> userRoleNames = new HashMap<>();
        for (UserRole ur : userRoles.findAll()) {
            String roleName = roleNameById.get(ur.getRoleId());
            if (roleName != null) {
                userRoleNames.computeIfAbsent(ur.getUserId(), k -> new ArrayList<>()).add(roleName);
            }
        }
        return users.findAll(CREATED_DESC).stream()
                .filter(u -> filterTenantId == null || filterTenantId.equals(u.getTenantId()))
                .map(u -> userToMap(u, tenantNames, userRoleNames.getOrDefault(u.getId(), List.of())))
                .toList();
    }

    public Map<String, Object> getUserDetail(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        Map<String, Object> map = userToMap(u);
        map.put("roleIds", userRoles.findRoleIdsByUserId(userId));
        return map;
    }

    @Transactional
    public Map<String, Object> createUser(String username, String name, String password, String identityLevel,
                                           Long tenantId, List<Long> roleIds) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("用户名只能包含英文、数字和中划线，且不超过 32 个字符");
        }
        if (users.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }

        User self = currentUser.require();
        String idLevel = identityLevel != null ? identityLevel : "user";

        // 管理员创建的用户必须绑定到自己的租户
        if (!self.isSuperAdmin()) {
            tenantId = self.getTenantId();
        }

        // 如果用户是管理员级别，确保创建同名租户
        if ("admin".equals(idLevel) || "super_admin".equals(idLevel)) {
            Tenant tenant = ensureTenant(username);
            if (tenantId == null) {
                tenantId = tenant.getId();
            }
        }

        // 普通用户必须绑定到租户（如果没有则用操作者自己的租户）
        if (tenantId == null) {
            if (!self.isSuperAdmin()) {
                tenantId = self.getTenantId();
            }
        }

        User u = new User();
        u.setUsername(username);
        u.setName(name != null && !name.isBlank() ? name.trim() : null);
        u.setPasswordHash(encoder.encode(password));
        u.setRole("admin".equals(idLevel) || "super_admin".equals(idLevel) ? "admin" : "user");
        u.setIdentityLevel(idLevel);
        u.setTenantId(tenantId);
        u.setEnabled(true);
        users.save(u);

        // 自动分配默认角色
        if (roleIds != null && !roleIds.isEmpty()) {
            assignRoles(u.getId(), roleIds);
        } else if (tenantId != null) {
            assignDefaultRole(u.getId(), tenantId);
        }

        log.info("User created: {} (identity={}, tenant={})", username, u.getIdentityLevel(), tenantId);
        return userToMap(u);
    }

    @Transactional
    public Map<String, Object> updateUser(Long userId, String name, String identityLevel, Long tenantId, Boolean enabled) {
        User u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (name != null) u.setName(name.isBlank() ? null : name.trim());
        if (identityLevel != null) {
            u.setIdentityLevel(identityLevel);
            u.setRole("admin".equals(identityLevel) || "super_admin".equals(identityLevel) ? "admin" : "user");
        }
        if (tenantId != null) u.setTenantId(tenantId);
        if (enabled != null) u.setEnabled(enabled);
        users.save(u);
        return userToMap(u);
    }

    @Transactional
    public Map<String, Object> assignUserRoles(Long userId, List<Long> roleIds) {
        User u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        assignRoles(userId, roleIds);
        Map<String, Object> map = userToMap(u);
        map.put("roleIds", roleIds);
        return map;
    }

    public boolean deleteUser(Long userId) {
        if (!users.existsById(userId)) return false;
        users.deleteById(userId);
        return true;
    }

    private void assignDefaultRole(Long userId, Long tenantId) {
        Role defaultRole = roles.findByTenantIdAndRoleCode(tenantId, DEFAULT_ROLE_CODE).orElse(null);
        if (defaultRole != null) {
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(defaultRole.getId());
            userRoles.save(ur);
        }
    }

    private void assignRoles(Long userId, List<Long> roleIds) {
        userRoles.deleteByUserId(userId);
        for (Long roleId : roleIds) {
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoles.save(ur);
        }
    }

    private Map<String, Object> userToMap(User u) {
        // 单条场景：即时查询租户名与角色名
        Map<Long, String> tenantNames = new HashMap<>();
        if (u.getTenantId() != null) {
            tenants.findById(u.getTenantId()).ifPresent(t -> tenantNames.put(t.getId(), t.getTenantName()));
        }
        Set<Long> roleIds = userRoles.findRoleIdsByUserId(u.getId());
        List<String> roleNames = roles.findAllById(roleIds).stream()
                .map(Role::getRoleName)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        return userToMap(u, tenantNames, roleNames);
    }

    /**
     * 将用户实体转为前端 Map。
     * @param tenantNames 租户 id → 名称 映射（用于展示「租户」列）
     * @param roleNames   该用户已分配的角色名称列表（用于展示「角色」列）
     */
    private Map<String, Object> userToMap(User u, Map<Long, String> tenantNames, List<String> roleNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("name", u.getName());
        m.put("role", u.getRole());
        m.put("identityLevel", u.getIdentityLevel());
        m.put("tenantId", u.getTenantId());
        m.put("tenantName", u.getTenantId() == null ? null : tenantNames.get(u.getTenantId()));
        m.put("roleNames", roleNames);
        m.put("enabled", u.isEnabled());
        m.put("createdAt", u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
        m.put("lastLoginAt", u.getLastLoginAt() == null ? null : u.getLastLoginAt().toString());
        return m;
    }

    // ==================== 租户管理 ====================

    public List<Map<String, Object>> listTenants() {
        Long filterTenantId = tenantFilter();
        return tenants.findAll(CREATED_DESC).stream()
                .filter(t -> filterTenantId == null || filterTenantId.equals(t.getId()))
                .map(this::tenantToMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> createTenant(String tenantCode, String tenantName, String description) {
        if (tenants.existsByTenantCode(tenantCode)) {
            throw new IllegalArgumentException("租户编码 " + tenantCode + " 已存在");
        }
        Tenant t = new Tenant();
        t.setTenantCode(tenantCode);
        t.setTenantName(tenantName != null ? tenantName : tenantCode);
        t.setDescription(description);
        t.setEnabled(true);
        tenants.save(t);

        // 自动创建默认角色
        createDefaultRole(t.getId());

        log.info("Tenant created: {} ({})", tenantCode, t.getId());
        return tenantToMap(t);
    }

    @Transactional
    public Map<String, Object> updateTenant(Long tenantId, String tenantName, String description, Boolean enabled) {
        Tenant t = tenants.findById(tenantId).orElseThrow(() -> new IllegalArgumentException("租户不存在"));
        if (tenantName != null) t.setTenantName(tenantName);
        if (description != null) t.setDescription(description);
        if (enabled != null) t.setEnabled(enabled);
        tenants.save(t);
        return tenantToMap(t);
    }

    public boolean deleteTenant(Long tenantId) {
        if (!tenants.existsById(tenantId)) return false;
        tenants.deleteById(tenantId);
        return true;
    }

    /** 确保租户存在，不存在则创建。返回租户实体。 */
    private Tenant ensureTenant(String tenantCode) {
        return tenants.findByTenantCode(tenantCode).orElseGet(() -> {
            Tenant t = new Tenant();
            t.setTenantCode(tenantCode);
            t.setTenantName(tenantCode);
            t.setEnabled(true);
            return tenants.save(t);
        });
    }

    /** 为新租户创建默认角色，并分配基础菜单权限（工作台） */
    private void createDefaultRole(Long tenantId) {
        Role r = new Role();
        r.setTenantId(tenantId);
        r.setRoleCode(DEFAULT_ROLE_CODE);
        r.setRoleName(DEFAULT_ROLE_NAME);
        r.setDescription("租户默认角色，拥有基础权限");
        r.setEnabled(true);
        roles.save(r);

        // 自动分配「工作台」菜单权限（menu_code = omp:workspace, id = 11）
        menus.findByMenuCode("omp:workspace").ifPresent(workspace -> {
            RoleMenu rm = new RoleMenu();
            rm.setRoleId(r.getId());
            rm.setMenuId(workspace.getId());
            roleMenus.save(rm);
        });

        log.info("Default role '{}' created for tenant {} with workspace menu", DEFAULT_ROLE_CODE, tenantId);
    }

    private Map<String, Object> tenantToMap(Tenant t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("tenantCode", t.getTenantCode());
        m.put("tenantName", t.getTenantName());
        m.put("description", t.getDescription());
        m.put("enabled", t.isEnabled());
        m.put("createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        return m;
    }

    // ==================== 角色管理 ====================

    public List<Map<String, Object>> listRoles() {
        Long filterTenantId = tenantFilter();
        List<Role> list;
        if (filterTenantId == null) {
            list = roles.findAll(CREATED_DESC);
        } else {
            list = roles.findByTenantId(filterTenantId);
        }
        return list.stream().map(this::roleToMap).toList();
    }

    public Map<String, Object> getRoleDetail(Long roleId) {
        Role r = roles.findById(roleId).orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        Map<String, Object> map = roleToMap(r);
        map.put("menuIds", roleMenus.findMenuIdsByRoleId(roleId));
        return map;
    }

    @Transactional
    public Map<String, Object> createRole(String roleCode, String roleName, String description, Long tenantId) {
        User self = currentUser.require();
        // 管理员创建的角色必须绑定到自己的租户
        if (!self.isSuperAdmin()) {
            tenantId = self.getTenantId();
        } else if (tenantId == null) {
            tenantId = self.getTenantId(); // 超级管理员未指定时绑定到自己
        }
        if (roles.findByTenantIdAndRoleCode(tenantId, roleCode).isPresent()) {
            throw new IllegalArgumentException("角色编码 " + roleCode + " 在该租户中已存在");
        }
        Role r = new Role();
        r.setTenantId(tenantId);
        r.setRoleCode(roleCode);
        r.setRoleName(roleName);
        r.setDescription(description);
        r.setEnabled(true);
        roles.save(r);
        log.info("Role created: {} (tenant={})", roleCode, tenantId);
        return roleToMap(r);
    }

    @Transactional
    public Map<String, Object> updateRole(Long roleId, String roleName, String description, Boolean enabled) {
        Role r = roles.findById(roleId).orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        if (roleName != null) r.setRoleName(roleName);
        if (description != null) r.setDescription(description);
        if (enabled != null) r.setEnabled(enabled);
        roles.save(r);
        return roleToMap(r);
    }

    @Transactional
    public Map<String, Object> assignRoleMenus(Long roleId, Set<Long> menuIds) {
        Role r = roles.findById(roleId).orElseThrow(() -> new IllegalArgumentException("角色不存在"));

        // 管理员只能分配自己拥有的菜单权限
        User self = currentUser.require();
        if (!self.isSuperAdmin()) {
            Set<Long> myRoleIds = userRoles.findRoleIdsByUserId(self.getId());
            Set<Long> myMenuIds = roleMenus.findMenuIdsByRoleIds(myRoleIds);
            for (Long menuId : menuIds) {
                if (!myMenuIds.contains(menuId)) {
                    throw new IllegalArgumentException("无权分配菜单 ID=" + menuId + "，您没有该菜单的权限");
                }
            }
        }

        roleMenus.deleteByRoleId(roleId);
        for (Long menuId : menuIds) {
            RoleMenu rm = new RoleMenu();
            rm.setRoleId(roleId);
            rm.setMenuId(menuId);
            roleMenus.save(rm);
        }
        log.info("Role {} assigned {} menus", roleId, menuIds.size());
        Map<String, Object> map = roleToMap(r);
        map.put("menuIds", menuIds);
        return map;
    }

    public boolean deleteRole(Long roleId) {
        if (!roles.existsById(roleId)) return false;
        roles.deleteById(roleId);
        return true;
    }

    private Map<String, Object> roleToMap(Role r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("tenantId", r.getTenantId());
        m.put("roleCode", r.getRoleCode());
        m.put("roleName", r.getRoleName());
        m.put("description", r.getDescription());
        m.put("enabled", r.isEnabled());
        m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        return m;
    }

    // ==================== 菜单管理 ====================

    public List<Map<String, Object>> listMenus(boolean tree) {
        User self = currentUser.require();
        List<Menu> all;
        if (self.isSuperAdmin()) {
            // 超级管理员：查看全部菜单
            all = menus.findAll();
        } else {
            // 管理员：只能看到自己拥有的菜单权限
            Set<Long> roleIds = userRoles.findRoleIdsByUserId(self.getId());
            log.info("listMenus: userId={}, username={}, roleIds={}", self.getId(), self.getUsername(), roleIds);
            if (roleIds.isEmpty()) {
                all = List.of();
            } else {
                Set<Long> menuIds = roleMenus.findMenuIdsByRoleIds(roleIds);
                log.info("listMenus: menuIds.size={}, menuIds={}", menuIds.size(), menuIds);
                all = menuIds.isEmpty() ? List.of() : menus.findByIdsOrderBySortOrder(menuIds);
                // 补全祖先菜单，确保树结构完整
                all = withAncestors(all);
                log.info("listMenus: with ancestors, menus.size={}", all.size());
            }
        }
        if (tree) {
            List<Map<String, Object>> result = buildMenuTree(all, null);
            log.info("listMenus: tree result.size={}", result.size());
            return result;
        } else {
            // 平铺模式按创建时间倒序
            return all.stream()
                    .sorted(Comparator.comparing(Menu::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(this::menuToMap)
                    .toList();
        }
    }

    /** 补全菜单的祖先链，确保树结构完整。 */
    private List<Menu> withAncestors(List<Menu> menus) {
        Map<Long, Menu> allById = new HashMap<>();
        for (Menu m : menus) {
            allById.put(m.getId(), m);
        }
        // 递归补全父菜单
        for (Menu m : new ArrayList<>(menus)) {
            addAncestors(m, allById);
        }
        return new ArrayList<>(allById.values());
    }

    private void addAncestors(Menu menu, Map<Long, Menu> allById) {
        Long parentId = menu.getParentId();
        while (parentId != null && !allById.containsKey(parentId)) {
            Menu parent = menus.findById(parentId).orElse(null);
            if (parent == null) break;
            allById.put(parent.getId(), parent);
            parentId = parent.getParentId();
        }
    }

    /** 构建菜单树 */
    private List<Map<String, Object>> buildMenuTree(List<Menu> all, Long parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Menu m : all) {
            Long pid = m.getParentId();
            if (parentId == null && pid == null || parentId != null && parentId.equals(pid)) {
                Map<String, Object> node = menuToMap(m);
                node.put("children", buildMenuTree(all, m.getId()));
                result.add(node);
            }
        }
        result.sort(Comparator.comparingInt(m -> (int) m.get("sortOrder")));
        return result;
    }

    @Transactional
    public Map<String, Object> createMenu(String menuCode, String menuName, String menuType,
                                           Long parentId, String path, String component,
                                           String icon, Integer sortOrder, String permission) {
        if (menus.findByMenuCode(menuCode).isPresent()) {
            throw new IllegalArgumentException("菜单编码 " + menuCode + " 已存在");
        }
        Menu m = new Menu();
        m.setMenuCode(menuCode);
        m.setMenuName(menuName);
        m.setMenuType(menuType != null ? menuType : "menu");
        m.setParentId(parentId);
        m.setPath(path);
        m.setComponent(component);
        m.setIcon(icon);
        m.setSortOrder(sortOrder != null ? sortOrder : 0);
        m.setPermission(permission);
        m.setEnabled(true);
        menus.save(m);
        log.info("Menu created: {}", menuCode);
        return menuToMap(m);
    }

    @Transactional
    public Map<String, Object> updateMenu(Long menuId, String menuName, String menuType,
                                           Long parentId, String path, String component,
                                           String icon, Integer sortOrder, String permission, Boolean enabled) {
        Menu m = menus.findById(menuId).orElseThrow(() -> new IllegalArgumentException("菜单不存在"));
        if (menuName != null) m.setMenuName(menuName);
        if (menuType != null) m.setMenuType(menuType);
        if (parentId != null) m.setParentId(parentId);
        if (path != null) m.setPath(path);
        if (component != null) m.setComponent(component);
        if (icon != null) m.setIcon(icon);
        if (sortOrder != null) m.setSortOrder(sortOrder);
        if (permission != null) m.setPermission(permission);
        if (enabled != null) m.setEnabled(enabled);
        menus.save(m);
        return menuToMap(m);
    }

    public boolean deleteMenu(Long menuId) {
        if (!menus.existsById(menuId)) return false;
        menus.deleteById(menuId);
        return true;
    }

    // ==================== 模型配置管理 ====================

    public List<Map<String, Object>> listModelConfigs() {
        return modelConfigs.findAllByOrderByCreatedAtDesc().stream()
                .map(this::modelConfigToMap)
                .toList();
    }

    public Map<String, Object> getModelConfig(Long id) {
        ModelConfigEntity e = modelConfigs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模型配置不存在"));
        return modelConfigToMap(e);
    }

    @Transactional
    public Map<String, Object> createModelConfig(String configName, String displayName, String provider, String modelId,
                                                  String baseUrl, String api, String apiKey, String configJson, String remark) {
        if (configName == null || configName.isBlank()) {
            throw new IllegalArgumentException("配置名称不能为空");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider 不能为空");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        if (modelConfigs.findByConfigName(configName).isPresent()) {
            throw new IllegalArgumentException("配置名称 " + configName + " 已存在");
        }

        ModelConfigEntity e = new ModelConfigEntity();
        e.setConfigName(configName.trim());
        e.setDisplayName(displayName != null && !displayName.isBlank() ? displayName.trim() : null);
        e.setProvider(provider.trim());
        e.setModelId(modelId.trim());
        e.setBaseUrl(baseUrl != null ? baseUrl.trim() : null);
        e.setApi(api != null ? api.trim() : null);
        e.setApiKey(apiKey != null ? apiKey.trim() : null);
        e.setConfigJson(configJson);
        e.setRemark(remark != null && !remark.isBlank() ? remark.trim() : null);

        // 第一个创建的是活跃的
        long count = modelConfigs.count();
        e.setActive(count == 0);

        e.setSortOrder(modelConfigs.findAll().size());
        modelConfigs.save(e);
        log.info("ModelConfig created: {}", configName);
        return modelConfigToMap(e);
    }

    @Transactional
    public Map<String, Object> updateModelConfig(Long id, String configName, String displayName, String provider, String modelId,
                                                  String baseUrl, String api, String apiKey, String configJson, String remark) {
        ModelConfigEntity e = modelConfigs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模型配置不存在"));
        if (configName != null && !configName.isBlank()) e.setConfigName(configName.trim());
        if (displayName != null) e.setDisplayName(displayName.isBlank() ? null : displayName.trim());
        if (provider != null && !provider.isBlank()) e.setProvider(provider.trim());
        if (modelId != null && !modelId.isBlank()) e.setModelId(modelId.trim());
        if (baseUrl != null) e.setBaseUrl(baseUrl.isBlank() ? null : baseUrl.trim());
        if (api != null) e.setApi(api.isBlank() ? null : api.trim());
        if (apiKey != null) e.setApiKey(apiKey.isBlank() ? null : apiKey.trim());
        if (configJson != null) e.setConfigJson(configJson);
        if (remark != null) e.setRemark(remark.isBlank() ? null : remark.trim());
        modelConfigs.save(e);
        log.info("ModelConfig updated: id={}", id);
        return modelConfigToMap(e);
    }

    @Transactional
    public Map<String, Object> activateModelConfig(Long id) {
        ModelConfigEntity target = modelConfigs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模型配置不存在"));

        // 停用所有配置
        modelConfigs.findAll().forEach(e -> {
            if (e.isActive()) {
                e.setActive(false);
                modelConfigs.save(e);
            }
        });

        // 激活目标配置
        target.setActive(true);
        modelConfigs.save(target);

        // 同步写入 admin_config.model.active，使其成为当前激活模型的唯一真相来源。
        // OMP 进程启动只读 model.active；运行时配置页也能看到此次变更。
        syncActiveToAdminConfig(target);

        log.info("ModelConfig activated: id={}, configName={}", id, target.getConfigName());
        return modelConfigToMap(target);
    }

    public boolean deleteModelConfig(Long id) {
        ModelConfigEntity e = modelConfigs.findById(id).orElse(null);
        if (e == null) return false;
        boolean wasActive = e.isActive();
        modelConfigs.deleteById(id);
        // 删除的是当前激活配置时，清除 admin_config.model.active，回退到默认模型
        if (wasActive) {
            adminConfig.delete(AdminConfigService.KEY_MODEL_ACTIVE);
        }
        log.info("ModelConfig deleted: id={}, wasActive={}", id, wasActive);
        return true;
    }

    /**
     * 将激活的模型配置写入 admin_config.model.active。
     * 优先使用 configJson（含 discovery/modelOverrides/models 等完整配置），
     * 否则用核心字段拼装一个最小对象。
     */
    private void syncActiveToAdminConfig(ModelConfigEntity e) {
        JsonNode node = null;
        if (e.getConfigJson() != null && !e.getConfigJson().isBlank()) {
            try {
                node = objectMapper.readTree(e.getConfigJson());
            } catch (JsonProcessingException ex) {
                log.warn("ModelConfig {} 的 configJson 解析失败，回退到核心字段", e.getId(), ex);
            }
        }
        Object value;
        if (node != null && node.isObject()) {
            // 确保核心字段存在（以实体列为准覆盖）
            com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            obj.put("provider", e.getProvider());
            obj.put("modelId", e.getModelId());
            if (e.getBaseUrl() != null) obj.put("baseUrl", e.getBaseUrl());
            if (e.getApi() != null) obj.put("api", e.getApi());
            if (e.getApiKey() != null) obj.put("apiKey", e.getApiKey());
            value = obj;
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", e.getProvider());
            m.put("modelId", e.getModelId());
            if (e.getBaseUrl() != null) m.put("baseUrl", e.getBaseUrl());
            if (e.getApi() != null) m.put("api", e.getApi());
            if (e.getApiKey() != null) m.put("apiKey", e.getApiKey());
            value = m;
        }
        adminConfig.set(AdminConfigService.KEY_MODEL_ACTIVE, value,
                "由模型配置「" + e.getConfigName() + "」激活", "admin");
    }

    private Map<String, Object> modelConfigToMap(ModelConfigEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("configName", e.getConfigName());
        m.put("displayName", e.getDisplayName());
        m.put("provider", e.getProvider());
        m.put("modelId", e.getModelId());
        m.put("baseUrl", e.getBaseUrl());
        m.put("api", e.getApi());
        m.put("apiKey", e.getApiKey());
        m.put("configJson", e.getConfigJson());
        m.put("remark", e.getRemark());
        m.put("active", e.isActive());
        m.put("sortOrder", e.getSortOrder());
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }

    private Map<String, Object> menuToMap(Menu m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("parentId", m.getParentId());
        map.put("menuCode", m.getMenuCode());
        map.put("menuName", m.getMenuName());
        map.put("menuType", m.getMenuType());
        map.put("path", m.getPath());
        map.put("component", m.getComponent());
        map.put("icon", m.getIcon());
        map.put("sortOrder", m.getSortOrder());
        map.put("permission", m.getPermission());
        map.put("enabled", m.isEnabled());
        map.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
        return map;
    }

    // ==================== 权限查询 ====================

    /**
     * 获取当前用户的菜单权限。
     * 超级管理员返回所有菜单；其他用户返回其角色关联的菜单。
     */
    public List<Map<String, Object>> getMyMenus() {
        User self = currentUser.require();
        if (self.isSuperAdmin()) {
            // 超级管理员：全部菜单
            return buildMenuTree(menus.findAll(), null);
        }
        // 通过角色获取菜单
        Set<Long> roleIds = userRoles.findRoleIdsByUserId(self.getId());
        if (roleIds.isEmpty()) return List.of();
        Set<Long> menuIds = roleMenus.findMenuIdsByRoleIds(roleIds);
        if (menuIds.isEmpty()) return List.of();
        List<Menu> userMenus = menus.findByIdsOrderBySortOrder(menuIds);
        // 补全祖先菜单，确保树结构完整
        userMenus = withAncestors(userMenus);
        return buildMenuTree(userMenus, null);
    }

    /**
     * 获取当前用户的角色和身份信息。
     */
    public Map<String, Object> getMyInfo() {
        User self = currentUser.require();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", self.getId());
        m.put("username", self.getUsername());
        m.put("identityLevel", self.getIdentityLevel());
        m.put("tenantId", self.getTenantId());
        m.put("superAdmin", self.isSuperAdmin());

        if (!self.isSuperAdmin()) {
            Set<Long> roleIds = userRoles.findRoleIdsByUserId(self.getId());
            List<Map<String, Object>> roleList = roles.findAllById(roleIds).stream()
                    .map(this::roleToMap).toList();
            m.put("roles", roleList);
        } else {
            m.put("roles", List.of());
        }
        return m;
    }

    /**
     * 获取当前用户的权限码集合（菜单的 permission 字段）。
     * 超级管理员返回全部权限码；其他用户返回其角色关联菜单（含按钮级）的权限码。
     * 前端用此集合驱动菜单/按钮的显示隐藏（v-permission）。
     */
    public List<String> getMyPermissions() {
        User self = currentUser.require();
        List<Menu> source;
        if (self.isSuperAdmin()) {
            source = menus.findAll();
        } else {
            Set<Long> roleIds = userRoles.findRoleIdsByUserId(self.getId());
            if (roleIds.isEmpty()) return List.of();
            Set<Long> menuIds = roleMenus.findMenuIdsByRoleIds(roleIds);
            if (menuIds.isEmpty()) return List.of();
            source = menus.findAllById(menuIds);
        }
        return source.stream()
                .map(Menu::getPermission)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .sorted()
                .toList();
    }
}
