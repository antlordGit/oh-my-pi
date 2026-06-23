# App Publish — 应用发布管理 设计文档

- **日期**：2026-06-23
- **作者**：omp 团队
- **状态**：Draft（待评审）
- **关联模块**：`backend/src/main/java/com/yourorg/omp/app/`（新增）
- **关联数据库迁移**：`backend/src/main/resources/db/migration/V<next>__app_publish.sql`

---

## 1. 背景与目标

开发者在 omp 平台内完成仓库编码后，需要把"仓库"封装成一个可对外提供服务的**应用**，并经历"预览 → 发布"两步上线。

- **预览**：仅作者本人能访问，用于自测；产物与生产环境隔离
- **发布**：对外提供服务的稳定版本，作者点击后预览 URL 自动切换到生产 URL
- **分享**：把当前可访问的 URL 复制到剪贴板，方便点对点发送

最终目标：在一个 Web UI 里完成"创建应用 → 触发预览 → 触发发布 → 分享 URL"完整闭环。

---

## 2. 范围与非目标

### In scope
- 应用 CRUD、关联仓库、状态机推进
- 触发预览/发布的异步构建流程
- 前端静态资源通过 Nginx（端口 8888）以路径 `/apps/<name>/`、`/preview/<name>/` 暴露
- 后端（如有）通过端口池分配端口，Nginx 反代 `/apps/<name>/api/`、`/preview/<name>/api/`
- 作者私有鉴权（预览完全私有）

### Out of scope（YAGNI）
- 多租户/团队协作（本期仅"作者私有"）
- 自定义域名、HTTPS 证书自动签发
- 自动扩容、流量切分、灰度发布
- 定时发布、Webhook 触发
- 应用市场、应用评分
- 回滚的 UI 复杂交互（仅支持一键回滚到历史版本）
- 构建过程的实时日志推送（本期仅"完成后可下载完整日志"）

---

## 3. 架构

### 3.1 模块位置

新增模块 `app-publish`：

```
backend/src/main/java/com/yourorg/omp/app/
├── AppController.java
├── AppService.java
├── BuildExecutor.java
├── NginxManager.java
├── PortAllocator.java
├── entity/
│   ├── App.java
│   └── AppVersion.java
├── repo/
│   ├── AppRepository.java
│   ├── AppVersionRepository.java
│   └── PortPoolRepository.java
└── dto/
    ├── CreateAppRequest.java
    ├── AppResponse.java
    └── PreviewResponse.java
```

### 3.2 组件职责

| 组件 | 职责 |
|------|------|
| `AppController` | REST API |
| `AppService` | 状态机推进、权限校验、URL 生成 |
| `BuildExecutor` | git clone → 读 `.omp/app.yaml` → 跑构建 → 落产物 |
| `NginxManager` | 渲染 Nginx vhost 片段，写 `/etc/omp/nginx/apps/<name>.conf`，reload |
| `PortAllocator` | 从 `port_pool` 表领用 9001-9999 端口 |
| `AppRepository` / `AppVersionRepository` / `PortPoolRepository` | JPA 数据访问 |

### 3.3 数据流

```
[Web UI] --HTTP--> [AppController] --调用--> [AppService]
                                                |
                                                ├─> [PortAllocator] 领端口
                                                |
                                                └─> @Async [BuildExecutor]
                                                          |
                                                          ├─> git clone repo
                                                          ├─> read .omp/app.yaml
                                                          ├─> run frontend.build_cmd
                                                          ├─> run backend.build_cmd (if has_backend)
                                                          ├─> write artifact to data/omp/apps/<app>/<env>/
                                                          ├─> start backend process on allocated port
                                                          └─> [NginxManager] render conf + reload
```

异步模型：**`@Async` + DB 轮询**，零外部依赖。Controller 触发后立刻返回 `versionId`，前端轮询 `/api/apps/{name}/versions/{vid}` 直到 status=READY/FAILED。后续如并发上来再考虑替换为消息队列。

并发上限：同时最多 3 个构建（`@Async` 配合自定义 `TaskExecutor` bean，限制 `corePoolSize=3`）。

---

## 4. 数据模型

### 4.1 Flyway 迁移

```sql
-- V<next>__app_publish.sql

CREATE TABLE app (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(64) NOT NULL UNIQUE,
    owner_id        BIGINT NOT NULL,
    repo_url        VARCHAR(512) NOT NULL,
    has_backend     BOOLEAN NOT NULL DEFAULT FALSE,
    current_version_id BIGINT NULL,
    status          VARCHAR(32) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMP NULL,
    INDEX idx_owner (owner_id),
    INDEX idx_status (status)
);

CREATE TABLE app_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_id          BIGINT NOT NULL,
    commit_sha      VARCHAR(64) NOT NULL,
    env             VARCHAR(16) NOT NULL,           -- PREVIEW / PROD
    status          VARCHAR(32) NOT NULL,           -- BUILDING / READY / FAILED
    backend_port    INT NULL,
    artifact_path   VARCHAR(512) NOT NULL,
    log_path        VARCHAR(512) NOT NULL,
    error           TEXT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMP NULL,
    UNIQUE KEY uk_app_env_active (app_id, env, status),  -- 仅校验 status=READY 唯一（用部分索引思路，详见下方说明）
    INDEX idx_app_env (app_id, env),
    INDEX idx_status (status)
);

CREATE TABLE port_pool (
    port            INT PRIMARY KEY,
    status          VARCHAR(16) NOT NULL,           -- FREE / HELD
    app_id          BIGINT NULL,
    env             VARCHAR(16) NULL,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status)
);

-- 初始化端口池 9001-9999
INSERT INTO port_pool (port, status)
SELECT n.port, 'FREE'
FROM (
    SELECT a.N + b.N * 10 + c.N * 100 + 9000 AS port
    FROM (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
) n
WHERE n.port BETWEEN 9001 AND 9999;
```

> **关于 `uk_app_env_active` 的说明**：MySQL 不直接支持部分唯一索引。本期在应用层保证：每次创建版本前在同一事务里将旧的 `(app_id, env, status=READY)` 版本标记为 `ARCHIVED`（status 增加枚举值），从而让唯一约束只需要 `(app_id, env, status)` 简单唯一即可。下面 4.2 给最终枚举。

### 4.2 状态枚举

- `App.status`：`CREATED` / `BUILDING_PREVIEW` / `PREVIEW_READY` / `BUILDING_PROD` / `PUBLISHED` / `FAILED`
- `AppVersion.status`：`BUILDING` / `READY` / `FAILED` / `ARCHIVED`
- `PortPool.status`：`FREE` / `HELD`

### 4.3 状态机

```
CREATED ──preview──> BUILDING_PREVIEW ──success──> PREVIEW_READY
                       │                                │
                       └──fail──> FAILED ──retry──> BUILDING_PREVIEW
                                                        │
                                                        │
                                            PREVIEW_READY ──publish──> BUILDING_PROD
                                                                              │
                                                                              ├─success──> PUBLISHED
                                                                              └─fail──────> PREVIEW_READY (回退)

PUBLISHED ──publish again (新版本)──> BUILDING_PROD
                                       └─success──> PUBLISHED（旧版本 ARCHIVED）
PUBLISHED ──rollback──> PUBLISHED（激活历史版本）
```

显式约束：
- 只有 `PREVIEW_READY` 才允许触发发布
- 只有 `CREATED` / `PREVIEW_READY` 才允许触发预览
- 只有作者本人能调任何写接口
- 预览/生产各保留最多一个 `READY` 版本，再构建时把旧的 ARCHIVED

---

## 5. .omp/app.yaml 仓库契约

每个要发布的仓库必须在根目录提供：

```yaml
# .omp/app.yaml
version: 1

frontend:
  framework: vue            # vue / react / static （仅做记录，不强制）
  build_cmd: npm run build  # 必须产出可静态服务文件
  output_dir: dist          # 构建产物的相对路径

backend:                    # 可省略，省略则视为无后端
  runtime: java             # java / node / python
  build_cmd: mvn -B -DskipTests package
  start_cmd: java -jar target/app.jar
  health_path: /actuator/health  # 用于探测启动完成
  start_timeout_sec: 60
```

构建执行流程：
1. `git clone <repo_url> <commit_sha> --depth 1` 到临时目录 `data/omp/apps/<app>/<env>-<vid>/src/`
2. 解析 `.omp/app.yaml`（不存在 → FAILED，"missing .omp/app.yaml"）
3. `cd src && <frontend.build_cmd>` → 拷贝 `output_dir` 到 `data/omp/apps/<app>/<env>/frontend/`
4. 如有 backend：`cd src && <backend.build_cmd>` → 启动 `start_cmd`，绑定 `port_pool` 分配的端口 → 轮询 `health_path` 直到 200 或超时
5. 全部成功 → version.status=READY，触发 `NginxManager.renderAndReload(app, env)`
6. 任意步骤失败 → 写 `error` 字段 + `log_path`，version.status=FAILED

---

## 6. Nginx 配置

### 6.1 主入口

平台主 Nginx（`/etc/omp/nginx/omp.conf`，端口 8888）通过 `include /etc/omp/nginx/apps/*.conf;` 引入每个应用的片段。

### 6.2 应用片段模板

文件名：`/etc/omp/nginx/apps/<appName>.conf`

```
# === app: <appName> | env: <env> | version: <vid> ===
# generated by NginxManager @ <ts>

location /<env_path>/<appName>/ {
    alias /opt/omp/data/apps/<appName>/<env>/frontend/;
    try_files $uri $uri/ /<env_path>/<appName>/index.html;
    expires 1h;
    add_header Cache-Control "public";
}

location /<env_path>/<appName>/api/ {
    <auth_block>
    proxy_pass http://127.0.0.1:<backend_port>/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 60s;
}
```

变量：
- `<env_path>` = `preview` 或 `apps`
- `<auth_block>`：仅 PREVIEW 环境注入，PROD 留空。内容为：

```
if ($http_x_omp_user != "<hmac_token>") { return 403; }
```

`<hmac_token>` 由 `NginxManager` 调用 `WorkspaceSigner`（复用最近提交里 `IdeService` 同款的 HMAC-SHA256 签名工具）生成：`HMAC(secret, "<appId>:<ownerId>")`。前端在调用预览 URL 时必须带上 `X-Omp-User: <hmac_token>` header。

> **前端接入**：预览按钮点击后，前端先用当前会话 token 调 `/api/apps/{name}/preview-token` 拿到 `hmac_token`，再用 fetch/iframe 打开 URL 并附带 header（注意：浏览器地址栏直接访问不会带 header → 会被 Nginx 403，符合"别人进不去"）。如作者想"复制可访问的 URL"，分享按钮复制的是带 query 参数 `?omp_user=<token>` 的版本，由 Nginx 解析 query 兼容。**MVP 简化**：本期仅要求"作者带 header 访问"，分享按钮复制的是 `/preview/<name>/` 路径但附 query token；Nginx 主配置增加一条 `map $arg_omp_user $header_omp_user` 把 query 转为等效 header。后续可优化。

### 6.3 渲染与重载

`NginxManager.renderAndReload(app, env)`：
1. 渲染模板字符串 → 写文件 `/etc/omp/nginx/apps/<appName>.conf`（原子：先写 `.tmp` 再 rename）
2. 备份旧文件 → `nginx -t` 校验语法
3. 校验失败：还原旧文件，抛 `NginxReloadException`
4. 校验成功：`nginx -s reload`
5. reload 失败：抛 `NginxReloadException`，version 标 FAILED，状态机回退

并发安全：所有 Nginx 写入通过 `nginxWriteLock`（`ReentrantLock`）串行化。

---

## 7. API 设计

### 7.1 应用管理

```
POST   /api/apps
  body: { name, repoUrl, hasBackend }
  resp: 201 AppResponse

GET    /api/apps
  resp: 200 AppResponse[]（仅当前 owner 拥有的，未删除）

GET    /api/apps/{name}
  resp: 200 AppResponse（包含 currentVersion 摘要）

DELETE /api/apps/{name}
  resp: 204（软删除：app.deleted_at 置为 now()，Nginx 片段移除，端口释放，后端进程 kill）
```

### 7.2 构建与发布

```
POST   /api/apps/{name}/preview
  校验：owner, status ∈ {CREATED, PREVIEW_READY, FAILED}
  resp: 202 { versionId }

POST   /api/apps/{name}/publish
  校验：owner, status == PREVIEW_READY
  resp: 202 { versionId }

POST   /api/apps/{name}/rollback/{versionId}
  校验：owner, targetVersion.env == PROD 且 status == READY
  resp: 200 AppResponse

GET    /api/apps/{name}/versions/{versionId}
  resp: 200 AppVersionResponse

GET    /api/apps/{name}/versions/{versionId}/log
  resp: 200 text/plain（日志文件内容，超长截断到 1MB）
```

### 7.3 预览鉴权 Token

```
GET    /api/apps/{name}/preview-token
  校验：owner, app.status == PREVIEW_READY
  resp: 200 { token, expiresAt }（有效期 24h，仅作者可获取）
```

### 7.4 错误码

| HTTP | 业务码 | 场景 |
|------|--------|------|
| 400 | `INVALID_NAME` | name 含非法字符或与已存在应用冲突 |
| 400 | `MISSING_APP_YAML` | 仓库无 `.omp/app.yaml` |
| 401 | `UNAUTHORIZED` | 未登录 |
| 403 | `NOT_OWNER` | 当前用户非作者 |
| 403 | `PREVIEW_TOKEN_EXPIRED` | Nginx 拦截（前端捕获 403 → 重新拉 token） |
| 404 | `APP_NOT_FOUND` | 应用不存在或已删除 |
| 409 | `INVALID_STATE` | 当前状态不允许该操作（如非 PREVIEW_READY 触发 publish） |
| 503 | `NO_FREE_PORT` | 端口池耗尽 |

---

## 8. 关键流程时序

### 8.1 预览

```
Author ─POST /preview─> Controller
                         │
                         ├─ owner 校验、状态校验
                         ├─ PortAllocator.acquire(appId, PREVIEW) → port=9123
                         ├─ 创建 AppVersion(BUILDING, env=PREVIEW)
                         └─ @Async BuildExecutor.run(version)
                                │
                                ├─ git clone <repoUrl> <sha>
                                ├─ read .omp/app.yaml
                                ├─ frontend.build_cmd
                                ├─ backend.build_cmd + start_cmd + health check
                                └─ NginxManager.renderAndReload(app, PREVIEW)
                                       └─ success → version.status=READY, app.status=PREVIEW_READY
                                       └─ failure → version.status=FAILED, app.status=FAILED
```

### 8.2 发布

```
Author ─POST /publish─> Controller
                          │
                          ├─ status == PREVIEW_READY?
                          ├─ PortAllocator.acquire(appId, PROD) → port=9150
                          ├─ 创建 AppVersion(BUILDING, env=PROD)
                          └─ @Async BuildExecutor.run(version)
                                │
                                ├─ 重新走完整构建（或复用 src/？本期：每个 PROD 版本独立构建，避免环境漂移）
                                └─ NginxManager.renderAndReload(app, PROD) — 同 conf 文件覆盖
                                       └─ success → current_version_id=新版本, app.status=PUBLISHED
                                       └─ failure → 回退 app.status=PREVIEW_READY
```

### 8.3 回滚

```
Author ─POST /rollback/{vid}─> Controller
                                │
                                ├─ 校验 vid 属当前 app 且 env=PROD status=READY/ARCHIVED
                                ├─ 找到对应 backend_port，如已被释放则从历史记录恢复（HELD）
                                ├─ 重启该历史版本 start_cmd（pid 文件管理）
                                ├─ NginxManager.renderAndReload(app, PROD) — 切回旧 alias
                                └─ current_version_id = vid
```

### 8.4 分享

前端：复制 `<env_path>/<appName>/` 到剪贴板。预览环境附加 `?omp_user=<token>` 由前端获取后拼上。

---

## 9. 错误处理与边界

| 场景 | 处理 |
|------|------|
| git clone 失败（凭证、404、网络） | version.status=FAILED，`error`=stderr tail |
| `.omp/app.yaml` 不存在或字段缺失 | version.status=FAILED，error=`missing/invalid .omp/app.yaml` |
| 前端构建失败（exit ≠ 0） | 同上，error=`<exit code>: <stderr last 4KB>` |
| 后端启动超时（`start_timeout_sec` 到了 health_path 还非 200） | kill 后端进程，version FAILED |
| 端口池耗尽 | API 返回 503 |
| Nginx reload 失败 | 还原 conf，version FAILED，状态机回退到上一稳定态 |
| 应用被删除时仍有运行中后端 | 软删除同步：kill pid、释放端口、移除 conf |
| 并发构建同一 app | DB 行锁（`SELECT ... FOR UPDATE` on app）保证同一 app 串行构建 |

构建失败后保留产物目录（不下线），方便排查；产物清理策略：仅 ARCHIVED 状态 + 距 finished_at > 30 天的版本会被定时任务清理。

---

## 10. 测试策略

### 10.1 单元测试

- `AppServiceTest`：状态机迁移矩阵（每条合法/非法迁移一条用例）
- `PortAllocatorTest`：并发抢占（用 `CountDownLatch` + `ExecutorService` 模拟 50 个并发 acquire，断言领到的 port 全部不重复）
- `NginxManagerTest`：渲染快照测试（给定 app/version 输入 → 期望字符串，含前后空格稳定）
- `AppYamlParserTest`：合法/非法 yaml → 异常分类

### 10.2 集成测试

- `AppControllerIT`：用 `@SpringBootTest(webEnvironment=RANDOM_PORT)` 跑通"创建 → 预览 → 发布 → 回滚 → 删除"完整链路，用 `AppGitFixture` 临时 git 仓库
- Nginx reload 集成测试：实际写 conf + `nginx -t`，用 testcontainers 起一个最小 Nginx 镜像

### 10.3 不测

- 后端进程的代码本身（不在本平台范围内）
- git clone 的网络行为（mock git 命令本身在 BuildExecutor 内部）

---

## 11. 配置项

`application.yml` 新增：

```yaml
omp:
  app-publish:
    workspace:
      base-dir: ${OMP_DATA_DIR:/opt/omp/data}/apps
    build:
      concurrency: 3
      start-timeout-sec: 60
      log-tail-bytes: 4096
    port-pool:
      range-start: 9001
      range-end: 9999
    nginx:
      include-dir: /etc/omp/nginx/apps
      main-config: /etc/omp/nginx/omp.conf
      reload-cmd: nginx -s reload
      test-cmd: nginx -t
    preview-token:
      ttl-sec: 86400
      secret: ${OMP_PREVIEW_TOKEN_SECRET}   # 与 WorkspaceSigner 共用 secret
```

---

## 12. 风险与后续

| 风险 | 缓解 |
|------|------|
| 构建机器中毒（恶意仓库代码） | 后续接 pi-iso 沙箱；本期假设作者可信 |
| 端口耗尽 | 监控 + 告警；自动清理 ARCHIVED+过期的端口占用 |
| Nginx reload 中断长连接 | reload 是热加载，影响可控；后续可加 graceful |
| 多副本部署时 Nginx 渲染冲突 | 本期单实例；后续需把渲染+reload 改成推送到所有实例 |
| 预览 token 泄露（分享链接被他人） | token 24h 过期；后续可加 IP 绑定 |

---

## 13. 实施拆分（建议执行顺序）

1. Flyway 迁移 + 实体 + Repository + PortAllocator（含并发测试）
2. AppService + 状态机迁移 + 控制器契约（不含构建）
3. BuildExecutor + .omp/app.yaml 解析 + git 集成
4. NginxManager + 模板渲染 + reload 集成测试
5. 预览鉴权 token + Nginx `map` query → header
6. 回滚流程 + 软删除清理
7. 端到端集成测试 + 文档（README 接入指南）