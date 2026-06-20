-- ===========================================================================
-- V3: System management - tenants, menus, roles, user_role, role_menu
-- ===========================================================================

-- 1. 创建租户表
CREATE TABLE tenants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL UNIQUE,
    tenant_name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_tenant_code (tenant_code)
);

-- 2. 修改用户表，添加身份级别和租户外键
ALTER TABLE users ADD COLUMN identity_level VARCHAR(16) NOT NULL DEFAULT 'user' AFTER role;
ALTER TABLE users ADD COLUMN tenant_id BIGINT NULL AFTER identity_level;
ALTER TABLE users ADD INDEX idx_tenant_id (tenant_id);
ALTER TABLE users ADD INDEX idx_identity_level (identity_level);

-- 3. 创建菜单表（树形结构）
CREATE TABLE menus (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT NULL,
    menu_code VARCHAR(64) NOT NULL UNIQUE,
    menu_name VARCHAR(64) NOT NULL,
    menu_type VARCHAR(16) NOT NULL DEFAULT 'menu',
    path VARCHAR(256) NULL,
    component VARCHAR(256) NULL,
    icon VARCHAR(64) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    permission VARCHAR(128) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_parent_id (parent_id),
    INDEX idx_menu_code (menu_code)
);

-- 4. 创建角色表（租户级别）
CREATE TABLE roles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NULL,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    description VARCHAR(512) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_tenant_role (tenant_id, role_code),
    INDEX idx_tenant_id (tenant_id)
);

-- 5. 用户-角色关联表
CREATE TABLE user_roles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_role (user_id, role_id),
    INDEX idx_user_id (user_id),
    INDEX idx_role_id (role_id)
);

-- 6. 角色-菜单关联表
CREATE TABLE role_menus (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    INDEX idx_role_id (role_id),
    INDEX idx_menu_id (menu_id)
);

-- 7. 添加外键约束
ALTER TABLE menus ADD CONSTRAINT fk_menu_parent
    FOREIGN KEY (parent_id) REFERENCES menus(id) ON DELETE SET NULL;
ALTER TABLE roles ADD CONSTRAINT fk_role_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;
ALTER TABLE user_roles ADD CONSTRAINT fk_ur_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE user_roles ADD CONSTRAINT fk_ur_role
    FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE;
ALTER TABLE role_menus ADD CONSTRAINT fk_rm_role
    FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE;
ALTER TABLE role_menus ADD CONSTRAINT fk_rm_menu
    FOREIGN KEY (menu_id) REFERENCES menus(id) ON DELETE CASCADE;
ALTER TABLE users ADD CONSTRAINT fk_user_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE SET NULL;

-- 8. 初始化默认菜单数据
--
-- 完整菜单树结构：
--   omp (1)
--   ├── 控制室 (2)
--   │   ├── 配置 (3)
--   │   ├── 会话 (4)
--   │   ├── 审计 (5)
--   │   └── 系统管理 (6)
--   │       ├── 用户 (7)
--   │       ├── 租户 (8)
--   │       ├── 角色 (9)
--   │       └── 菜单 (10)
--   └── 工作台 (11)
INSERT INTO menus (id, parent_id, menu_code, menu_name, menu_type, path, component, icon, sort_order, permission) VALUES
-- 一级：omp 根节点
(1,  NULL, 'omp',              'omp',       'menu', '/omp',          NULL,                            NULL,       0, NULL),
-- 二级：控制室、工作台
(2,  1,    'omp:control',      '控制室',    'menu', '/admin',        NULL,                            'settings', 1, 'omp:control:view'),
(11, 1,    'omp:workspace',    '工作台',    'menu', '/sessions',     NULL,                            'monitor',  2, 'omp:workspace:view'),
-- 三级：控制室下的子菜单
(3,  2,    'omp:config',       '配置',      'menu', '/admin',        NULL,                            'tune',     1, 'omp:config:view'),
(4,  2,    'omp:sessions',     '会话',      'menu', '/admin',        NULL,                            'process',  2, 'omp:sessions:view'),
(5,  2,    'omp:audit',        '审计',      'menu', '/admin',        NULL,                            'audit',    3, 'omp:audit:view'),
(6,  2,    'omp:system',       '系统管理',  'menu', '/system',        NULL,                            'manage',   4, 'omp:system:view'),
-- 四级：系统管理下的子菜单
(7,  6,    'omp:system:user',   '用户管理', 'menu', '/system',       'system/user/UserList.vue',     'user',     1, 'omp:system:user:list'),
(8,  6,    'omp:system:tenant', '租户管理', 'menu', '/system',       'system/tenant/TenantList.vue', 'team',     2, 'omp:system:tenant:list'),
(9,  6,    'omp:system:role',   '角色管理', 'menu', '/system',       'system/role/RoleList.vue',     'role',     3, 'omp:system:role:list'),
(10, 6,    'omp:system:menu',   '菜单管理', 'menu', '/system',       'system/menu/MenuTree.vue',     'menu',     4, 'omp:system:menu:list');

-- 9. 将现有 admin 用户更新为超级管理员
UPDATE users SET identity_level = 'super_admin' WHERE role = 'admin';
UPDATE users SET identity_level = 'user' WHERE role = 'user' OR identity_level IS NULL;
