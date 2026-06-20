-- ===========================================================================
-- V5: 补充模型配置菜单 + 各模块按钮级权限码
-- ===========================================================================
-- 权限控制粒度：菜单级（menu_type='menu'）控制 Tab/页面可见性，
-- 按钮级（menu_type='button'）控制页面内操作按钮（创建/编辑/删除等）。

-- 1. 模型配置菜单（系统管理下，sort=5）
INSERT INTO menus (id, parent_id, menu_code, menu_name, menu_type, path, component, icon, sort_order, permission) VALUES
(12, 6, 'omp:system:model', '模型配置', 'menu', '/system', 'system/model/ModelConfigList.vue', 'model', 5, 'omp:system:model:list');

-- 2. 按钮级权限项 —— 用户管理 (parent=7)
INSERT INTO menus (id, parent_id, menu_code, menu_name, menu_type, sort_order, permission) VALUES
(101, 7, 'omp:system:user:create',      '创建用户',  'button', 1, 'omp:system:user:create'),
(102, 7, 'omp:system:user:edit',        '编辑用户',  'button', 2, 'omp:system:user:edit'),
(103, 7, 'omp:system:user:delete',      '删除用户',  'button', 3, 'omp:system:user:delete'),
(104, 7, 'omp:system:user:assign-role', '分配角色',  'button', 4, 'omp:system:user:assign-role');

-- 3. 按钮级权限项 —— 租户管理 (parent=8)
INSERT INTO menus (id, parent_id, menu_code, menu_name, menu_type, sort_order, permission) VALUES
(111, 8, 'omp:system:tenant:create', '创建租户', 'button', 1, 'omp:system:tenant:create'),
(112, 8, 'omp:system:tenant:edit',   '编辑租户', 'button', 2, 'omp:system:tenant:edit'),
(113, 8, 'omp:system:tenant:delete', '删除租户', 'button', 3, 'omp:system:tenant:delete');

-- 4. 按钮级权限项 —— 角色管理 (parent=9)
INSERT INTO menus (id, parent_id, menu_code, menu_name, menu_type, sort_order, permission) VALUES
(121, 9, 'omp:system:role:create',      '创建角色', 'button', 1, 'omp:system:role:create'),
(122, 9, 'omp:system:role:edit',        '编辑角色', 'button', 2, 'omp:system:role:edit'),
(123, 9, 'omp:system:role:delete',      '删除角色', 'button', 3, 'omp:system:role:delete'),
(124, 9, 'omp:system:role:assign-menu', '分配菜单', 'button', 4, 'omp:system:role:assign-menu');

-- 5. 按钮级权限项 —— 菜单管理 (parent=10)
INSERT INTO menus (id, parent_id, menu_code, menu_name, menu_type, sort_order, permission) VALUES
(131, 10, 'omp:system:menu:create', '创建菜单', 'button', 1, 'omp:system:menu:create'),
(132, 10, 'omp:system:menu:edit',   '编辑菜单', 'button', 2, 'omp:system:menu:edit'),
(133, 10, 'omp:system:menu:delete', '删除菜单', 'button', 3, 'omp:system:menu:delete');

-- 6. 按钮级权限项 —— 模型配置 (parent=12)
INSERT INTO menus (id, parent_id, menu_code, menu_name, menu_type, sort_order, permission) VALUES
(141, 12, 'omp:system:model:create',   '创建模型', 'button', 1, 'omp:system:model:create'),
(142, 12, 'omp:system:model:edit',     '编辑模型', 'button', 2, 'omp:system:model:edit'),
(143, 12, 'omp:system:model:delete',   '删除模型', 'button', 3, 'omp:system:model:delete'),
(144, 12, 'omp:system:model:activate', '激活模型', 'button', 4, 'omp:system:model:activate');

-- 7. 控制室子功能按钮级权限项 —— 配置(3)/会话(4)/审计(5)
INSERT INTO menus (id, parent_id, menu_code, menu_name, menu_type, sort_order, permission) VALUES
(151, 3, 'omp:config:edit',      '编辑配置',   'button', 1, 'omp:config:edit'),
(152, 3, 'omp:config:delete',    '删除配置',   'button', 2, 'omp:config:delete'),
(153, 4, 'omp:sessions:kill',    '终止会话',   'button', 1, 'omp:sessions:kill'),
(154, 4, 'omp:sessions:restore', '恢复会话',   'button', 2, 'omp:sessions:restore');
