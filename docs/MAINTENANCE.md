# 系统维护功能说明

## 概述

本功能在服务更新前提供维护模式开关和推流会话监控，避免更新时打断用户正在进行的对话。

**生效范围**：`120 服务`（后端 Java + 前端 Vue）

---

## 后端实现

### 1. 依赖与配置

| 文件 | 说明 |
|---|---|
| `pom.xml` | 新增 `spring-boot-starter-data-redis` + `commons-pool2` |
| `application.yml` | 新增 `spring.data.redis.*` 配置（本地默认 localhost:6379） |
| `application-120.yml` | 容器内 Redis 配置指向宿主机 `172.17.0.1:6379` |
| `RedisConfig.java` | Jackson JSON 序列化配置 |

### 2. 核心服务

**`MaintenanceService.java`**（`com.yourorg.omp.maintenance`）

- Redis 存储维护状态（key: `omp:maintenance:status`），TTL 7 天
- 订阅 EventBus 监听 `turn_start` / `turn_end` 事件，在内存中维护「正在推流的会话集合」
- 对外暴露 API：
  - `enable(operator)`：开启维护模式，写入 Redis
  - `disable()`：关闭维护模式，删除 Redis key
  - `isEnabled()`：判断维护中
  - `getStatus()`：完整状态
  - `getStreamingSessions()`：正在推流的会话列表（带时长、标题、用户）

### 3. 拦截器

**`MaintenanceInterceptor.java`**（`HandlerInterceptor`）

维护模式下拦截以下请求，返回 `503`：
- `POST /api/sessions` 新建会话
- `POST /api/sessions/*/prompt` 发送消息
- `POST /api/sessions/*/steer` 干预
- `POST /api/sessions/*/follow-up` 追问
- `POST /api/sessions/*/switch` 切换会话
- `POST /api/sessions/*/branch` 分支
- `POST /api/sessions/*/compact` 压缩上下文
- `POST /api/sessions/*/unarchive` 取消归档
- `POST /api/sessions/*/resume` 恢复会话

**白名单（不拦截）**：
- 登录、只读查询（GET /api/sessions/*、GET /api/repos/*）
- 终止会话、归档会话
- admin 管理接口
- 系统管理接口

**注册**：`WebMvcConfig.java` 全局注册，排除 `/error` 和静态资源。

### 4. WebSocket 拦截

**`SessionWsHandler.java`**

- 在 `afterConnectionEstablished` 一开始判断 `maintenance.isEnabled()`
- 若维护中，发送 `{type:"maintenance"}` 帧后关闭连接，`CloseStatus.SERVICE_RESTARTED`
- **不影响已有连接**，只拦新连接

### 5. 管理接口

**`AdminController.java`** 新增 4 个端点：

| 端点 | 说明 |
|---|---|
| `GET /admin/maintenance/status` | 当前维护状态 |
| `POST /admin/maintenance/enable` | 开启维护（7 天 TTL），记录操作者 |
| `POST /admin/maintenance/disable` | 解除维护 |
| `GET /admin/maintenance/streaming-sessions` | 推流会话列表 + `canStop` 标记（列表为空时为 true） |

响应示例：
```json
// GET /admin/maintenance/status
{
  "enabled": true,
  "createdAt": "2026-06-22T07:00:00Z",
  "expiresAt": "2026-06-29T07:00:00Z",
  "enabledBy": "admin"
}

// GET /admin/maintenance/streaming-sessions
{
  "items": [
    { "sessionId": "abc...", "title": "写一个爬虫", "userId": 2,
      "streamingSince": "2026-06-22T07:00:00Z", "streamingSeconds": 125 }
  ],
  "total": 1,
  "canStop": false
}
```

---

## 前端实现

### 1. API 封装

**`web/src/api/admin.ts`** 新增：
- `getMaintenanceStatus()`
- `enableMaintenance()`
- `disableMaintenance()`
- `getStreamingSessions()`

### 2. AdminView.vue 改动

**位置**：「终止所有会话」按钮右侧

| 按钮 | 说明 |
|---|---|
| **系统维护** | 黄色 `btn-mini-warning`。未维护时点击 → 确认对话框 → 开启；维护中点击 → 解除 |
| **推流监控** | 蓝色 `btn-mini-info`。点击弹出对话框，查看正在推流的会话，显示「是否可停止更新」 |
| **维护状态 Badge** | 维护中显示橙色脉冲小圆点 + 倒计时（还剩 X 分钟/小时/天） |

---

## 部署前配置

> ⚠️ **120 服务器需要 Redis 实例**，本功能依赖 Redis 存储维护开关状态。

### 120 服务器快速部署 Redis

```bash
# 启动一个持久化的 Redis 容器（绑定宿主机端口，允许容器内访问）
docker run -d --name omp-maintenance-redis \
  --restart unless-stopped \
  -p 172.17.0.1:6379:6379 \
  redis:7-alpine \
  redis-server --appendonly yes

# 验证（容器内访问宿主机）
docker exec -it omp-app curl http://172.17.0.1:6379/  # 响应说明在监听
```

`application-120.yml` 已预置正确的 Redis host：
```yaml
spring:
  data:
    redis:
      host: 172.17.0.1  # 宿主机 docker0 网关
      port: 6379
```

---

## 更新操作流程（管理员操作）

1. 进入 **Admin 控制台 → Sessions 页**
2. 点击 **「系统维护」** → 确认开启
   - 此时新用户创建会话、发送消息等操作均返回 503 提示
   - 已有会话继续推流不受影响
3. 点击 **「推流监控」** 查看正在推流的会话
   - 若列表不为空：等待，或点击「终止所有会话」强制终止（谨慎操作）
   - 若列表为空：显示「无正在推流的会话，可执行服务更新」
4. 执行服务更新（`backend` + `web` 打包部署，重启容器）
5. 更新完成后，**解除维护模式**：再次点击「解除维护」
   - 或 7 天后自动解除

---

## 权限控制

- 按钮权限码：`omp:system:maintenance`
- 需在「系统管理 → 菜单管理」中新建一条「按钮」类型的记录，权限码为此值
- 给管理员角色授权后，按钮才可见

---

## 文件清单

### 新增文件

| 文件 | 说明 |
|---|---|
| `backend/src/main/java/com/yourorg/omp/config/RedisConfig.java` | Redis Jackson 序列化 |
| `backend/src/main/java/com/yourorg/omp/config/WebMvcConfig.java` | 拦截器注册 |
| `backend/src/main/java/com/yourorg/omp/maintenance/MaintenanceService.java` | 核心服务 |
| `backend/src/main/java/com/yourorg/omp/maintenance/MaintenanceInterceptor.java` | REST 拦截器 |

### 修改文件

| 文件 | 改动 |
|---|---|
| `backend/pom.xml` | 加 Redis 依赖 |
| `backend/src/main/resources/application.yml` | Redis 默认配置 |
| `backend/src/main/resources/application-120.yml` | 120 容器 Redis 配置 |
| `backend/src/main/java/com/yourorg/omp/rest/AdminController.java` | 4 个维护接口 |
| `backend/src/main/java/com/yourorg/omp/ws/SessionWsHandler.java` | WS 维护拦截 |
| `web/src/api/admin.ts` | 4 个 API 函数 |
| `web/src/views/admin/AdminView.vue` | 2 个按钮 + 状态 badge + 监控对话框 + 样式 |
