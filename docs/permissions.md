# 权限体系说明

## 三级身份模型

系统采用三级身份隔离模型，通过 `User.identityLevel` 字段区分用户身份。

| 身份级别 | identityLevel 值 | 数据范围 | 菜单可见性 |
|---------|------------------|---------|-----------|
| 超级管理员 | `super_admin` | 全部租户的全部数据 | 全部菜单 |
| 管理员 | `admin` | 本租户全部数据 | 角色权限配置的菜单 |
| 普通用户 | `user` | 仅本人数据 | 角色权限配置的菜单 |

## 数据范围（DataScope）

`DataScope` 将三级身份抽象为两个可空过滤条件，统一查询逻辑：

```sql
WHERE (:userId IS NULL OR x.user_id = :userId)
  AND (:tenantId IS NULL OR x.tenant_id = :tenantId)
```

| 身份 | userId | tenantId | 效果 |
|-----|--------|----------|------|
| super_admin | `null` | `null` | 不受限，可查全部数据 |
| admin | `null` | 本租户ID | 受租户限制，可查本租户全部数据 |
| user | 本人ID | `null` | 受用户限制，仅查本人数据 |

## 业务实体权限矩阵

### 仓库（Repo）

| 操作 | 超级管理员 | 管理员 | 普通用户 |
|-----|----------|-------|---------|
| 查询列表 | 全部仓库 | 本租户仓库 | 本人仓库 |
| 创建 | ✓ | ✓（归属本租户） | ✓（归属本人） |
| 复制 | 全部仓库 | 本租户仓库 | ✗ |
| 删除 | 全部仓库 | 本租户仓库 | ✗ |
| 文件读写 | 全部仓库 | 本租户仓库 | 本人仓库 |

### 会话（Session）

| 操作 | 超级管理员 | 管理员 | 普通用户 |
|-----|----------|-------|---------|
| 查询列表 | 全部会话 | 本租户会话 | 本人会话 |
| 创建 | ✓ | ✓ | ✓ |
| 归档/恢复 | 全部会话 | 本租户会话 | 本人会话 |
| 发送消息 | 全部会话 | 本租户会话 | 本人会话 |
| 终止进程 | 全部会话 | 本租户会话 | 本人会话 |

### 用户（User）

| 操作 | 超级管理员 | 管理员 | 普通用户 |
|-----|----------|-------|---------|
| 查询列表 | 全部用户 | 本租户用户 | ✗ |
| 创建 | ✓ | ✓（归属本租户） | ✗ |
| 编辑 | 全部用户 | 本租户用户 | ✗ |
| 删除 | 全部用户 | 本租户用户 | ✗ |
| 分配角色 | 全部用户 | 本租户用户 | ✗ |
| 启用/禁用 | 全部用户 | 本租户用户 | ✗ |

### 租户（Tenant）

| 操作 | 超级管理员 | 管理员 | 普通用户 |
|-----|----------|-------|---------|
| 查询列表 | 全部租户 | 本租户 | ✗ |
| 创建 | ✓ | ✗ | ✗ |
| 编辑 | 全部租户 | 本租户 | ✗ |
| 删除 | 全部租户 | ✗ | ✗ |

### 角色（Role）

| 操作 | 超级管理员 | 管理员 | 普通用户 |
|-----|----------|-------|---------|
| 查询列表 | 全部角色 + 全局角色 | 本租户角色 + 全局角色 | ✗ |
| 创建 | ✓ | ✓（归属本租户） | ✗ |
| 编辑 | 全部角色 | 本租户角色 | ✗ |
| 删除 | 全部角色 | 本租户角色 | ✗ |
| 分配菜单 | 全部角色 | 本租户角色（仅自己有的菜单） | ✗ |

### 菜单（Menu）

| 操作 | 超级管理员 | 管理员 | 普通用户 |
|-----|----------|-------|---------|
| 查询列表 | 全部菜单 | 自己拥有的菜单 | ✗ |
| 创建 | ✓ | ✗ | ✗ |
| 编辑 | 全部菜单 | ✗ | ✗ |
| 删除 | 全部菜单 | ✗ | ✗ |

### 模型配置（ModelConfig）

| 操作 | 超级管理员 | 管理员 | 普通用户 |
|-----|----------|-------|---------|
| 查询列表 | ✓ | ✓ | ✗ |
| 创建 | ✓ | ✓ | ✗ |
| 编辑 | ✓ | ✓ | ✗ |
| 删除 | ✓ | ✓ | ✗ |
| 激活 | ✓ | ✓ | ✗ |

### 审计日志（PromptAudit / ToolAudit / ResponseAudit）

| 操作 | 超级管理员 | 管理员 | 普通用户 |
|-----|----------|-------|---------|
| 查询 | 全部记录 | 本租户记录 | ✗ |

### 系统配置（AdminConfig）

| 操作 | 超级管理员 | 管理员 | 普通用户 |
|-----|----------|-------|---------|
| 查询 | ✓ | ✓ | ✗ |
| 创建/编辑 | ✓ | ✓ | ✗ |
| 删除 | ✓ | ✓ | ✗ |

## 菜单权限

菜单可见性通过 `RoleMenu` 关联表控制：

- **超级管理员**：强制可见全部菜单，无视角色配置
- **管理员/普通用户**：仅可见其角色被授权的菜单

前端通过 `/api/system/me/permissions` 接口获取当前用户权限码列表，`hasPerm(code)` 方法判断：
- 超级管理员恒返回 `true`
- 其他用户检查权限码是否在列表中

## 数据审计字段

所有支持增删改查的核心实体需维护以下审计字段：

| 字段 | 说明 | 自动维护方式 |
|-----|------|-------------|
| `created_at` | 创建时间 | 数据库默认值 `CURRENT_TIMESTAMP`，插入时自动填充 |
| `updated_at` | 更新时间 | 数据库触发器或 JPA `@PreUpdate` 自动更新 |
| `updated_by` | 更新者 | 服务层在保存前从 `CurrentUser` 获取用户名并设置 |

### 需要审计字段的实体

| 实体 | created_at | updated_at | updated_by |
|-----|------------|------------|------------|
| User | ✓ | ✗ | ✗ |
| Tenant | ✓ | ✓ | ✗ |
| Role | ✓ | ✓ | ✗ |
| Menu | ✓ | ✓ | ✗ |
| Repo | ✓ | ✗ | ✗ |
| SessionMeta | ✓ | ✓ | ✗ |
| ModelConfig | ✓ | ✓ | ✗ |
| AdminConfig | ✓ | ✓ | ✓ |

### 服务层更新示例

```java
public void updateEntity(Entity entity) {
    var user = currentUser.require();
    entity.setUpdatedAt(Instant.now());
    entity.setUpdatedBy(user.getUsername());
    repo.save(entity);
}
```

## 权限校验位置

| 层级 | 校验方式 | 示例 |
|-----|---------|------|
| 路由层 | Spring Security | `.requestMatchers("/admin/**").hasRole("ADMIN")` |
| 控制器层 | `CurrentUser` + 业务判断 | `if (!self.isAdmin()) throw ...` |
| 服务层 | `tenantFilter()` / `DataScope` | `repo.findScoped(scope.userId(), scope.tenantId())` |
| 数据层 | `DataScope` 过滤 | `WHERE (:userId IS NULL OR x.user_id = :userId)` |
| 前端 | `auth.isAdmin` / `hasPerm()` | `v-if="auth.isAdmin"` |

## 相关代码

- `backend/src/main/java/com/yourorg/omp/security/DataScope.java` — 数据范围抽象
- `backend/src/main/java/com/yourorg/omp/entity/User.java` — 用户实体，`isSuperAdmin()` / `isAdmin()`
- `backend/src/main/java/com/yourorg/omp/security/CurrentUser.java` — 当前用户上下文
- `backend/src/main/java/com/yourorg/omp/service/SystemService.java` — 系统管理服务，`tenantFilter()` 方法
- `web/src/stores/auth.ts` — 前端权限状态，`isAdmin` / `isSuperAdmin` / `hasPerm()`
