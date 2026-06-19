# omp web — 多用户编码平台

把 `omp --mode rpc` 装进 Web：前端 Vue 对话框 → 后端 Java 编排 omp 子进程 → MySQL 审计 + 用户隔离 + 文件落本地磁盘。

参考上游文档：`docs/rpc.md`、`docs/environment-variables.md`、`python/robomp/` 的 worker 模式。

## 架构

```
Vue 3 + Vite + Naive UI  →  Spring Boot 3 + MySQL  →  omp --mode rpc × N
                           (Java 21)                  (并发上限默认 10)
```

每用户每 session 一个 omp 子进程：
- 启动参数：`--cwd /srv/omp/workspaces/<userId>/<repoId>` + `PI_CODING_AGENT_DIR=/srv/omp/agent/<userId>` + `--api-key <统一 key>`
- 用户代码目录自动 `git init`，每个 tool 调用结束后自动 commit（代码历史可见）
- session JSONL 由 omp 自己写到 `<agentDir>/sessions/...`，Java 只记录业务审计

## 目录结构

```
backend/                 Spring Boot (Java 21)
  src/main/java/com/yourorg/omp/
    rpc/                 OmpRpcClient（JSONL stdio 客户端）
    pool/                ProcessPool（并发上限 + idle 回收）
    session/             SessionManager（业务 CRUD）
    workspace/           WorkspaceService（git init / diff / log / snapshot）
    audit/               AuditService（事件 → MySQL）
    event/               EventBus（WS Gateway 与 AuditService 共用事件流）
    ws/                  SessionWsHandler + WebSocketConfig
    rest/                AuthController / RepoController / SessionController / AdminController
    admin/               AdminConfigService（运行时配置）
    entity/ + repo/      JPA 实体 + Spring Data Repository
    security/            JwtService + JwtAuthFilter + SecurityConfig

web/                     Vue 3 + Vite + Naive UI + Pinia
  src/views/             Login / SessionList / Chat / Admin

deploy/                  docker-compose + Dockerfile + nginx.conf
```

## 快速启动

### 0. 前置条件
- 已安装 `omp` CLI（版本 ≥ 15.x，命令 `omp --version` 可用）
- Docker + Docker Compose
- 一份有效的 LLM API key（任意 `--api-key` 兼容的 provider）

### 1. 配置环境变量

```bash
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env，至少设置 OMP_API_KEY
```

`deploy/.env` 示例：
```
OMP_API_KEY=sk-ant-xxx
OMP_JWT_SECRET=change-me-32-chars-or-more-for-hs256
OMP_BOOTSTRAP_ADMIN_USERNAME=admin
OMP_BOOTSTRAP_ADMIN_PASSWORD=your-admin-password
MYSQL_ROOT_PASSWORD=root
OMP_DB_PASSWORD=omp
```

### 2. 把 omp CLI 挂进容器

backend 容器通过 `/usr/local/bin/omp` 调用 CLI。最简单：在宿主机安装 omp，启动时 bind-mount：

```yaml
# deploy/docker-compose.yml 中 backend service 加：
volumes:
  - /usr/local/bin/omp:/usr/local/bin/omp:ro
  - omp-data:/srv/omp
```

或在容器内独立安装 omp（修改 Dockerfile）。

### 3. 启动

```bash
cd deploy
docker compose --env-file .env up -d
# 第一次启动会跑 Flyway 迁移、创建 admin 用户、构建前后端镜像
```

访问：
- Web UI：http://localhost/
- 默认管理员：`admin` / `.env` 里设的密码

### 4. 验证

```bash
# 登录拿 token
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

# 创建一个测试用户
curl -X POST localhost:8080/admin/users \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice123","role":"user"}'

# 用 alice 登录
A_TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice123"}' | jq -r .token)

# 建仓库 + session + 发 prompt
curl -X POST localhost:8080/api/repos \
  -H "Authorization: Bearer $A_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"repoId":"demo","displayName":"Demo"}'

curl -X POST localhost:8080/api/sessions \
  -H "Authorization: Bearer $A_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"repoId":"demo","title":"hello"}'
# 返回 sessionId

curl -X POST localhost:8080/api/sessions/<SESSION_ID>/prompt \
  -H "Authorization: Bearer $A_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"写一个 hello.py 打印 hello world"}'
```

打开浏览器 → http://localhost/ → 用 alice 登录 → 看到流式输出 + 工具卡片 + 文件树

## 配置项（`backend/src/main/resources/application.yml`）

```yaml
app:
  omp:
    binary: /usr/local/bin/omp               # CLI 路径
    workspaces-root: /srv/omp/workspaces     # 用户代码根
    agent-root: /srv/omp/agent               # 用户 omp agent dir
    stderr-log-dir: /srv/omp/logs            # 子进程 stderr 日志
    pool:
      max-concurrent: 10                     # 进程池上限
      idle-ttl-minutes: 30                   # idle 多少分钟后 kill
    per-user-session-limit: 5                # 单用户最多活跃 session
    default-flags:
      tools: read,edit,write,bash,grep,find  # 默认工具白名单
      thinking: medium                       # 默认思考等级
      approval-mode: write                   # 默认审批模式
    default-model:
      provider: anthropic
      model-id: claude-sonnet-4-5
  security:
    jwt-secret: change-me-...                # HS256 密钥（≥32 字符）
    jwt-ttl-hours: 24
    bootstrap-admin:
      username: admin
      password: admin
  vault:
    api-key: ${OMP_API_KEY}                  # 注入到所有 omp 进程的 --api-key
```

## 管理员配置（运行时）

超级管理员可在 Web 的「管理面板」改以下 key：

| Key | 含义 | 示例 |
|---|---|---|
| `model.active` | 默认模型（每个新进程使用） | `{"provider":"anthropic","modelId":"claude-sonnet-4-5"}` |
| `omp.flags.tools` | 工具白名单 | `["read","edit","write","bash"]` |
| `omp.flags.thinking` | 思考等级 | `"medium"` |
| `omp.flags.approval-mode` | 审批模式 | `"write"` |
| `vault.apiKey` | 覆盖默认 API key | `"sk-..."` |

修改后**新进程**生效；运行中的进程需要 `POST /admin/sessions/{id}/reload` 强制重启。

## 关键 API 速查

```
[认证]
POST /api/auth/login                       # 登录拿 token

[仓库]
POST /api/repos                            # 新建（user 隔离）
GET  /api/repos                            # 列表（自己的）
GET  /api/repos/{repoId}/files             # 文件树
GET  /api/repos/{repoId}/diff?refA=&refB=  # git diff
GET  /api/repos/{repoId}/log?n=20          # git log

[Session]
POST /api/sessions                         # 新建（{repoId, title}）
GET  /api/sessions                         # 列表
GET  /api/sessions/{id}/state              # get_state（model/todo/context）
GET  /api/sessions/{id}/messages           # get_messages
POST /api/sessions/{id}/prompt             # 触发 prompt
POST /api/sessions/{id}/abort              # 中断
POST /api/sessions/{id}/steer              # 排队插入
POST /api/sessions/{id}/follow-up          # 排队追加
POST /api/sessions/{id}/new-session        # 新 session（可选 parentSession）
POST /api/sessions/{id}/switch             # 切到旧 session JSONL
POST /api/sessions/{id}/branch             # 从 entryId 开分支
POST /api/sessions/{id}/compact            # 手动压缩
POST /api/sessions/{id}/archive            # 归档

[WebSocket]
WS /ws/sessions/{id}                       # 实时事件流（JSON 帧透传 omp 协议）

[管理员]
GET  /admin/config                         # 列出所有配置
PUT  /admin/config/{key}                   # 设置
GET  /admin/users                          # 用户列表
POST /admin/users                          # 新建用户
POST /admin/sessions/{id}/kill             # 强制 kill 进程
POST /admin/sessions/{id}/reload           # 强制重启
GET  /admin/audit/prompts?sessionId=&userId=
GET  /admin/audit/tools?toolName=
GET  /admin/audit/responses?
GET  /admin/pool                           # 进程池状态
```

## 本地开发（不走 Docker）

```bash
# Backend
cd backend
mvn spring-boot:run \
  -DOMP_DB_URL=jdbc:mysql://localhost:3306/omp \
  -DOMP_DB_USER=omp -DOMP_DB_PASSWORD=omp \
  -DOMP_BIN=/usr/local/bin/omp \
  -DOMP_API_KEY=$OMP_API_KEY \
  -DOMP_JWT_SECRET=dev-secret-please-change-32chars

# Frontend
cd web
npm install
npm run dev   # http://localhost:5173
```

Vite proxy 会把 `/api`、`/admin`、`/ws` 转发到 `localhost:8080`。

## 安全注意

1. **JWT secret**：生产部署必须改成 ≥32 字符随机串（HS256 要求）
2. **OMP API key**：所有用户共享一个 key。如果想做"按用户计费"，需要扩展为 per-user key（注入到 `OMP_API_KEY` env）；本期是统一 key
3. **路径穿越防御**：`WorkspaceService.readFile` 检查 `file.startsWith(root)`
4. **session JSONL 暴露**：`ompSessionFile` 字段当前暴露绝对路径，但只能读取自己 user 的 session（通过 `findOwned` 校验）
5. **CORS**：当前 SecurityConfig 全开放（`*`）；生产需要收紧到你的前端域名

## 已知限制

- **OAuth 类 provider（openai-codex 等）不支持**：本期只支持 `--api-key`
- **MCP 集成**：服务端配置层开启，Web 不可配
- **协作 / collab 不做**：单用户单 session
- **超大 session 文件**：`get_messages` 一次性返回，无分页
- **进程死亡恢复**：下次 prompt 自动重启（kill → 新进程 → resume）

## 开发进度

| 阶段 | 状态 | 内容 |
|---|---|---|
| P0 | ✅ | 工程骨架 + RPC 客户端 + 进程池 + Session 管理 + Workspace + 基础审计 + 登录 + 对话框 + 工具卡片 |
| P1 | 🔜 | 完整 RPC 命令封装 + 文件树 + Diff UI + 分支树 + Todo 面板 + subagent 订阅 |
| P2 | 🔜 | 管理员 UI 完善 + 用户管理 + 审计查询页 + 强制 reload |
| P3 | 🔜 | 指标 + idle 回收调优 + 备份 + 集成测试 + HTTPS |