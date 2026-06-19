# OMP Web 开发全流程记录

> 把 omp CLI 装进 Web 应用，然后在浏览器里完成真实编码联调，最后修复所有 bug 并建立完整可观测性。

**日期**：2026-06-19
**项目位置**：`/Users/chenzhiwei/work/github/oh-my-pi-main`
**新增代码量**：~5200 行（67 个源文件 + 设计系统 + 日志观测）

---

## 0. 起点 —— 从一个看似简单的问题开始

下午第一句话：

> 当前工程是只支持 CLI 端编码吗？

这个项目是 oh-my-pi（omp）—— 一个 Fork 自 Pi 的 monorepo 编程代理。前几轮对话其实是在帮用户搞清楚项目能力边界，最终明确真实需求：

**目标**：用 Java 后端 + Vue 前端开发一套多用户 Web 编码平台，让用户通过浏览器用 omp 完成编码任务。支持进程隔离（每用户每 session 一进程）、并发≥10、独立工作目录、MySQL 审计全部 prompt/response、超管管理模型配置。

参考 `python/robomp` 的成熟模式（FastAPI + sqlite + WorkerPool + Workspace + host_tools audit），创新点是用 Java/Spring Boot 复刻它并扩展成一个通用的多租户 Web IDE。

---

## 1. Phase 1：方案规划（Plan Mode）

进入 Plan Mode，并发派 3 个 Explore agent 摸清三件事：

1. **omp RPC 协议完整性** — 41 个 RpcCommand、~15 类 outbound 帧、stdout 严格 JSONL（首帧 `{"type":"ready"}`）、无协议版本字段
2. **omp CLI 启动参数和认证机制** — `--api-key` 是进程绑定的（一个进程一个 provider 的 key），`PI_CODING_AGENT_DIR` env var 是用户隔离的杠杆
3. **生态参考实现** — `python/robomp` 是现成的 worker pool + workspace + audit 模式参考；`python/omp-rpc` 是协议客户端 wire 形状参考

### 三个关键决策（用 AskUserQuestion 得到的）

| 决策 | 选择 |
|---|---|
| OMP 进程隔离粒度 | **每用户每 session 一进程**（auth 进程绑定，不可跨用户复用） |
| 审计实现 | **Java 端拦截全部 RPC 事件落 MySQL**（不依赖 omp 扩展） |
| 回滚语义 | **Session 分支（OMP 原生）+ Git 历史（workspace 内 git init）** |

### 最终架构

```
浏览器 (Vue 3 + Vite + Pinia + Naive UI)
  ↕ WebSocket
Spring Boot 3 (Java 21)
  - REST API + WS Gateway
  - OmpOrchestrator: ProcessPool + OmpRpcClient + SessionManager
  - WorkspaceService（本地盘 + git）
  - AuditService（→ MySQL）
  - AdminConfigService（运行时 model/thinking/tools 配置）
  ↕ stdio: JSONL
omp 子进程 × N（并发上限 10，per-session synchronized lock 防竞态）
```

---

## 2. Phase 2：实现 P0（8 个任务，4000 行代码）

| # | 任务 | 关键产出 |
|---|---|---|
| 1 | 后端骨架 + MySQL + Flyway | `pom.xml`、`application.yml`、V1__init.sql（7 张表）、JPA Entity + Repository |
| 2 | OmpRpcClient + 协议封装 | `OmpRpcClient`（stdio JSONL 客户端）、`OmpProcessSpec`、`RpcCommands`（41 命令 builder） |
| 3 | ProcessPool + SessionManager | Semaphore 并发上限、idle 60s 回收、SessionMeta CRUD |
| 4 | WorkspaceService + AuditService | git init、diff/log/snapshot；EventBus 订阅落 MySQL |
| 5 | REST API + WebSocket Gateway + Auth | JWT HS256 + BCrypt + 启动注入 admin、9 个 REST 端点 + `/ws/sessions/{id}` |
| 6 | 前端工程骨架 | Vue 3 + Vite + Pinia + Router + Naive UI + axios 封装 |
| 7 | ChatView + SessionList | 登录页、会话列表、流式对话、工具卡片、管理面板 |
| 8 | 部署与文档 | docker-compose、Dockerfile、nginx.conf、`README-OMP-WEB.md` |

### 数据库表结构

```sql
users          -- BCrypt 哈希、role(user/admin)
repos          -- 用户仓库（user_id × repo_id 唯一）
sessions       -- 会话元数据 + omp_session_file 路径
prompt_audit   -- 用户 prompt 审计
tool_audit     -- 工具调用审计（args/result/duration/isError）
response_audit -- 响应文本 + thinking + stopReason
admin_config   -- key-value JSON 配置（model/tools/thinking/key vault）
```

---

## 3. Phase 3-6：让代码真正跑起来（9 个真实 bug 修复）

### Bug 1：H2 自动配置抢占 MySQL
**症状**：日志显示 `jdbc:h2:mem:cf224f43-...` 而不是配置的 MySQL  
**根因**：pom 里加了 H2 依赖（临时方案残留），Spring Boot 自动配置优先级高  
**修复**：直接移除 H2 依赖

### Bug 2：OmpProperties 嵌套 record 绑定失败 → Spring Security NPE
**症状**：`NullPointerException: ... OmpProperties.security() is null`  
**根因**：`@ConfigurationProperties` 对嵌套 record 静默 null（Spring Boot 3.3 已知问题）  
**修复**：JwtService 改用 `@Value` 直接注入 `app.omp.security.*` 配置项

### Bug 3：没注册 PasswordEncoder → 登录 403
**症状**：POST `/api/auth/login` 返回 403 Forbidden  
**根因**：DaoAuthenticationProvider 报错 *"You have entered a password with no PasswordEncoder"*  
**修复**：SecurityConfig 显式 `@Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }`

### Bug 4：`Map.of` 不接受 null → toDto NPE
**症状**：`POST /api/sessions` 返回 `{"error":"internal error"}`  
**根因**：`SessionMeta.getCreatedAt()` 在 save 后还未 JPA 回填，`Map.of("createdAt", null, ...)` 直接 NPE  
**修复**：DTO 改用 `new HashMap<>().put("createdAt", r.getCreatedAt() == null ? null : ...)`

### Bug 5：`/error` 转发引发 401 死循环 → 124MB 日志
**症状**：后端日志暴涨到 124MB，里面是 `/api/api/api/api/...` 重复几百遍  
**根因**：controller 抛异常 → Spring 转发到 `/error` → 又被 SecurityFilter 拦 → `ErrorReportValve` 又转发 → 路径前缀越拼越长  
**修复**：
1. `GlobalExceptionHandler` 改用 `@RestControllerAdvice` + `ResponseEntity`（避免走默认 error 路径）
2. SecurityConfig 加 `requestMatchers("/error").permitAll()`
3. `@ControllerAdvice` 中加 `log.error(...)` 避免异常静默

### Bug 6：`runGit("init", "-b", "main")` 拼成单参数
**症状**：日志 `git init -b main failed: git: 'init -b main' is not a git command`  
**根因**：`runGitCapture(cwd, String.join(" ", args))` 把多个 varargs 拼成单 string 再当 varargs 传  
**修复**：直接转发 varargs：`runGitCapture(cwd, args)`

### Bug 7：pi_natives native addon 未编译 → omp 立即退出
**症状**：omp 子进程立即崩溃，stderr：`Failed to load pi_natives native addon`  
**根因**：rust crate 用了 `#![feature(alloc_error_hook)]`，需要 nightly toolchain  
**修复**：
```bash
rustup toolchain install nightly-2026-04-29
export PATH="$HOME/.rustup/toolchains/nightly-2026-04-29-aarch64-apple-darwin/bin:$PATH"
bun --cwd=packages/natives run build
# 编译耗时约 1 分 25 秒
```

### Bug 8：`--provider openai-completions` 不被识别
**症状**：omp stderr：`Unknown provider "openai-completions"`  
**根因**：把 omp 的 API 类型当成了 provider 名字  
**修复**：改成接受 admin 配置的真实 provider 名（`deepseek`），并写 `models.yml` 注入到每用户 agentDir

### Bug 9：ready 帧检测过于严格 + BufferedReader 数据丢失
**症状**：omp 启动后 autore search 扩展的 `extension_ui_request:setWidget` 帧比 `{"type":"ready"}` 先到，进程被误判失败退出  
**根因**：
1. `awaitReady()` 要求第一帧必须是 `ready`，但 omp 可能先发扩展帧
2. 更严重：用临时 `BufferedReader probe` 读第一帧后丢弃，但 BufferedReader 内部缓冲会把**后续的 N 个帧全部预读然后丢失**——这就是为什么早期 tool_execution 事件和流式 delta 全部丢失
**修复**：
```java
// 单线程 readLoop 负责所有帧，awaitReady 用 CountDownLatch
// pre-ready 的非 ready 帧也转发给订阅者（不丢失事件）
if (readyLatch.getCount() > 0) {
    if ("ready".equals(frame.path("type").asText())) {
        readyLatch.countDown();
        continue;
    }
}
routeFrame(frame);  // 非 ready 帧也正常分发
```

---

## 4. Phase 7：真实浏览器端到端验证（cdp-bridge）

通过 cdp-bridge 在浏览器里完整操作 omp 平台，重点是**开发一个真实可联调的 vue+java 系统**。

### 4.1 验证过程

1. **登录** → admin/admin，成功拿 JWT
2. **建仓库** → chat-app（DeepSeek 工作区）
3. **建 session** → 标题"AI 问答"
4. **发 prompt** → "在当前工作目录创建一个完整可运行 vue+java 联调系统"
5. **等待结果** → DeepSeek 写入了 5 个文件（pom.xml、App.java、ChatController.java、CorsConfig.java、index.html）
6. **编译后端** → `mvn package` 成功，生成 `chat-app-1.0.0.jar`
7. **启动后端 (9091)** → `/api/hello` 返回 `{"msg":"hi from java"}`
8. **启动前端 (5175)** → 浏览器显示 `hi from java`，CORS 跨域成功
9. **前端发消息** → fetch 9091/api/echo → Vue 渲染回复 → 完整联调闭环

### 4.2 验证结论

```
OMP 编辑 UI (:5173)
  ↓ 用户输入需求
Spring Boot OMP Orchestrator (:8080)
  ↓ spawn omp --mode rpc
omp 子进程 + DeepSeek API
  ↓ 生成真实可运行代码
mvn package + java -jar + python3 -m http.server
  ↓
chat-app 后端 (:9091)  ←→  chat-app 前端 (:5175)
  ↓ CORS 跨域
浏览器 fetch /api/hello → {"msg":"hi from java"} ✅
```

### 4.3 过程中额外修好的 bug

#### Bug 10：`vault.apiKey` 读取带 JSON 双引号 → DeepSeek 401
**症状**：DeepSeek 返回 `401 Authentication Fails, Your api key: ****bac" is invalid`  
**根因**：HTTP PUT 写入 `"sk-..."` 时存的是 JSON 字符串，读取时 `configValue` 带双引号  
**修复**：`AdminConfigService.apiKey()` 用 `mapper.readTree()` 解析 JSON textNode —— 后来进一步把 apiKey 移到 `model.active` 里，删除 `vault.apiKey`

#### Bug 11：`--no-rules --no-skills` 注入 system prompt → AI 自称 RTK
**症状**：AI 回复"我是 RTK（Rust Token Killer）项目的 AI 助手"  
**根因**：用户 `~/.claude/CLAUDE.md` 被 omp 自动加载为 context file，AI 以为自己在 RTK 项目里  
**修复**：OmpProcessSpec 给 omp 加 `--no-rules --no-skills` 禁用自动加载宿主 context

#### Bug 12：`RepoController.toDto()` 也用了 `Map.of` → 同 Bug 4
**症状**：POST /api/repos 创建成功但返回 500  
**修复**：同 Bug 4，改用 HashMap

---

## 5. Phase 8：深度 debug —— 浏览器输入后无反应（5 个严重 bug）

### 5.1 WebSocket 认证链全套修复

用户反馈在输入框输入后**没有任何反应**，Network 面板 5 个请求全部 (canceled)。

#### Bug 13：Spring Security 拒绝 WS upgrade → WebSocket 永远连不上
**症状**：浏览器 WebSocket 请求 4-6ms 立即断开，Network 面板显示 10 次重试  
**根因**：browser WebSocket 无法设 `Authorization` header，`/ws/**` 设了 `.authenticated()` → filter 内直接 403，根本没进 SessionWsHandler  
**修复**：SecurityConfig 改为 `requestMatchers("/ws/**").permitAll()` → 鉴权移到 SessionWsHandler 内部通过 `?token=` query 参数完成

#### Bug 14：WS token 鉴权未实现 → 修复了仍连不上
**症状**：改了 permitAll 后 WS 请求成功升级，但 vite proxy 把 403 从后端传回浏览器  
**根因**：SessionWsHandler 只有 `afterConnectionEstablished` 里的 `currentUser.requireId()` 拿不到 security context（因为没有 filter 设置 auth）  
**修复**：SessionWsHandler 内实现 `authenticateToken()`——用 `jjwt` 解析 query 中的 token，设置 `SecurityContext`

#### Bug 15：ProcessPool slots 泄漏 from 并发竞态
**症状**：pool 状态 `active=4, usedSlots=10`——泄漏了 6 个 slot  
**根因**：并发 5 个 `acquire()` 请求（get_messages + get_state + prompt 同时攻击），因 omp 还在 spawn 中 `active.put` 还没执行——所有 5 个请求都看到 `existing=false` → 都进了 spawn → 5 个 slot 全占  
**修复**：Per-session `ConcurrentHashMap<String, Object>` 锁 + `synchronized` 块保护 spawn 原子操作

#### Bug 16：WS handler 双订阅导致字符重复（"我我是是"）
**症状**：流式输出中每个字出现两次  
**根因**：WS handler 订阅了 `client.events()`（直连）AND `eventBus.subscribe()`（fanout 转发），收到了双份 frame  
**修复**：去掉 `client.events()` 直连，只订阅 EventBus（因为 ProcessPool 已经把 client.events() 转发到 EventBus 了）

#### Bug 17：聊天界面大量空 assistant turn（30+ 个空块）
**症状**：ChatView 中 reply 16-41 显示了 30 多个只有思考内容、无文本的空白 assistant 气泡  
**根因**：`message_end` 处理中只要有 toolCalls 就 push assistant turn，但工具调用的流式输出已通过 live tools-stream 展示，不需要重复 push 空气泡  
**修复**：改为只对 `text` 或 `thinking` 有真实内容的 `message_end` 才 push turn

---

## 6. Phase 9：UI 重设计 —— Studio Noir

用户说"重新设计一下 web 的 ui 的排版和样式，要有质感"。最终 **Studio Noir**：暖炭黑 + 青铜色调，深度材质层、胶片颗粒、宽大留白。

- 配色：`void(#0a0c0b)` / `ink(#111413)` / `bronze(#c8883c)` 单一强调色
- 字体：`Fraunces`（可变 serif display）+ `DM Sans`（UI）+ `JetBrains Mono`（代码）
- 质感：SVG 噪片（3-octave turbulence）+ 暖色径向晕影 + 层次影子系统
- 所有 4 个视图统一重写（Login / SessionList / Chat / Admin）

---

## 7. Phase 10：ChatView 竖排文本修复

用户截图：文本每个字独占一行，整个对话是竖排的。

#### Bug 18：竖排文本 + 空页历史不渲染
**症状**：`.msg` width: 48px，parent `.turn-content` width: 60px  
**根因**：`<div class="turn-user">` 和 `<div class="turn-assistant">` wrapper 内嵌 `.turn-spine` + `.turn-content`，破坏了 `.turn` 的 `grid: 60px 1fr` 排列。1fr 被第二层网格压缩，只剩 12px  
**修复**：删除 wrapper，spine 和 content 直接作为 `.turn` 的子元素，用 v-if/:class 动态切换角色颜色
**同时修复**：`refresh()` 把 messages 写进 `history.value` 但不渲染。改为填充 `turnLog.value`（按 user/assistant role 拆 text/thinking）

---

## 8. Phase 11：建立完整可观测性（OMP IN/OUT 日志）

用户要求"打印 OMP 交互的输入和输出"。新增日志格式：

```
[omp.spawn]  session=0f002383 cwd=/tmp/omp/workspaces/2/chat-app stderr=...log argv=[omp, --mode, rpc, ...]
[omp.spawn]  session=0f002383 READY pid-alive=true
[omp→IN ]   session=0f002383 cmd={"type":"get_messages","id":"req_xxx"}
[omp→OUT]   session=0f002383 {"type":"agent_start"}
[omp→OUT]   session=0f002383 message_update evt=text_start
[omp→OUT]   session=0f002383 {"type":"agent_end"}
[omp.exit]   session=0f002383 reason=EOF exitCode=0 pendingRequests=0
```

关键设计：
- `[omp→IN ]` INFO 级 —— stdin 写入的命令（长文本截断到 400 字符）
- `[omp→OUT]` INFO 级 —— stdout 输出的帧。`message_update` 的 `text_delta`/`thinking_delta` 用紧凑形式避免刷屏；看增量字符则改 DEBUG
- `[omp.spawn]` INFO 级 —— 含 argv（api-key 已脱敏）
- `[omp.exit]` WARN 级 —— 含 exitCode + 未完成请求数

---

## 9. Phase 12：模型配置优化

#### 移除 vault.apiKey
**目标**：用户不需要在两个地方分别配 model.active 和 vault.apiKey  
**实现**：apiKey 改为优先从 `model.active.apiKey` 字段读，fallback 到 vault.apiKey（已不再依赖），再 fallback 到 `application.yml` 的 `app.vault.api-key`

---

## 10. 最终交付清单

### 后端 `backend/`

```
src/main/java/com/yourorg/omp/
├── OmpApplication.java
├── rpc/             OmpRpcClient (+ 详细 IN/OUT 日志), OmpProcessSpec, RpcCommands, 协议常量
├── pool/            ProcessPool (per-session lock 防竞态, Semaphore 10 并发)
├── session/         SessionManager
├── workspace/       WorkspaceService (git init + models.yml seed)
├── audit/           AuditService (工具调用 audit 失败防御性 catch)
├── event/           EventBus (per-session multicast sink)
├── ws/              SessionWsHandler (token query 参数鉴权, 单 EventBus 订阅)
├── rest/            AuthController / RepoController / SessionController / AdminController / GlobalExceptionHandler
├── admin/           AdminConfigService (apiKey 从 model.active 优先)
├── entity/          7 个 JPA 实体
├── repo/            7 个 Spring Data Repository
├── config/          OmpProperties + PropertiesConfig + StartupWiring
└── security/        JwtService + JwtAuthFilter + SecurityConfig (/ws permitAll)
```

### 前端 `web/`

```
src/
├── styles/design-system.css   ★ Studio Noir 设计系统
├── views/
│   ├── LoginView.vue          ★ 登录终端
│   ├── SessionListView.vue    ★ 操作员仪表盘
│   ├── ChatView.vue           ★ 对话界面（grid 修复 + history→turnLog）
│   └── admin/AdminView.vue    ★ 控制面板
├── components/
│   ├── MessageBubble.vue      ★ 引号开端的签名格式
│   └── ToolCard.vue           ★ 折叠式终端卡片
```

### 部署 `deploy/` + 根目录

```
docker-compose.yml / Dockerfile / nginx.conf / .env.example
DEVELOPMENT-LOG.md  ← 本文档
README-OMP-WEB.md   ← 工程总览
```

---

## 11. 启动方式（当前实际运行）

### 服务状态

| 服务 | 端口 | 命令 |
|---|---|---|
| OMP 后端 | 8080 | `java -jar backend/target/omp-backend-0.1.0.jar` |
| OMP 前端 | 5173 | `npx vite --host 0.0.0.0 --port 5173` |
| MySQL | 3306 | 远程 119.29.237.63 |

### omp CLI 子进程启动方式

Java 端 `ProcessBuilder` 模拟：

```bash
~/bin/omp \
  --mode rpc \
  --thinking medium \
  --approval-mode write \
  --tools read,edit,write,bash,grep,find,ls \
  --no-rules --no-skills \
  --provider deepseek --model deepseek-v4-flash \
  --api-key sk-b***ebac

# env: PI_CODING_AGENT_DIR=/tmp/omp/agent/2
# env: OPENAI_BASE_URL=https://api.deepseek.com/v1
# cwd: /tmp/omp/workspaces/2/<repoId>
```

`~/bin/omp` 是一个包装脚本（本机没安装编译后的 omp）：

```bash
#!/bin/bash
exec /opt/homebrew/bin/bun /Users/chenzhiwei/work/github/oh-my-pi-main/packages/coding-agent/src/cli.ts "$@"
```

---

## 12. bug 总数

18 个真实 bug，全部修完。

| 类别 | bug 数 | 序号 |
|---|---|---|
| 启动/配置 | 4 | #1 H2抢占，#2 record绑定，#3 PasswordEncoder，#4 Map.of NPE |
| 安全/认证 | 3 | #5 /error死循环，#13 WS 403，#14 WS token |
| 协议/I/O | 3 | #6 git参数拼接，#8 provider名字，#9 ready帧检测+数据丢失 |
| 构建/依赖 | 1 | #7 pi_natives nightly rust |
| 生产运维 | 2 | #10 apiKey引用号，#11 CLAUDE.md泄露 |
| 并发/状态 | 2 | #15 slots泄漏，#16 WS双订阅重复 |
| 前端渲染 | 3 | #12 RepoController.toDto，#17 空turn，#18 竖排+history不渲染 |

---

## 13. 给后续的提醒

1. **omp 升级时**跑集成测试套件 —— RPC 协议无版本号，靠 `type` 字符串兼容
2. **生产部署前必改**：
   - `OMP_JWT_SECRET` 必须 ≥ 32 字符随机串
   - `OMP_BOOTSTRAP_ADMIN_PASSWORD` 改为强密码
   - SecurityConfig 的 CORS 收紧到生产域名
3. **未做但值得做**：
   - 文件树 + Diff UI + Session 分支树可视化
   - Subagent 子事件订阅
   - 指标暴露（Micrometer + Prometheus）
   - 集成测试（Testcontainers + JSONL fixture 回放）
4. **部署清单**（Docker Compose 或 systemd + nginx）写在 `README-OMP-WEB.md` 中

---

## 14. 一句话总结

> 上午第一个问题还在问"项目是不是只支持 CLI"，到下午浏览器里 DeepSeek 生成可运行的 Vue + Spring Boot 联调系统，再到晚上修完 18 个 bug、配好 Studio Noir 设计系统、建立完整的 OMP IN/OUT 可观测性——端到端从 0 到 Web 多用户编码平台跑通就是今天。

— *Pressed on 2026-06-19 by 似梦寒夕 × Claude Opus 4.8*
