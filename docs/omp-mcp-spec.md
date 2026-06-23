# OMP MCP 配置使用规范

> **适用范围**：Oh-My-Pi 后端（`backend/`）通过 `OmpRpcClientFactory.spawn()` 启动 omp coding-agent 子进程的部署形态。
>
> **核心约定**：MCP 配置**全局统一管控**，每用户独立 agentDir 下的 `mcp.json` 由 Java 在每次 spawn 时**强覆盖**；APPEND_SYSTEM.md 采用**全局模板 + 用户私有覆盖**的合并模式。
>
> **关联代码**：
> - `backend/src/main/java/com/yourorg/omp/rpc/OmpRpcClientFactory.java`（spawn 时同步模板）
> - `backend/src/main/java/com/yourorg/omp/config/OmpProperties.java`（模板路径配置）
> - `backend/src/main/resources/application*.yml`（各环境路径配置）
> - omp 侧的 MCP 发现机制详见 [`docs/mcp-config.md`](./mcp-config.md) 和 [`docs/mcp-runtime-lifecycle.md`](./mcp-runtime-lifecycle.md)

---

## 目录

- [1. 设计背景](#1-设计背景)
- [2. 文件布局总览](#2-文件布局总览)
- [3. 运行时行为详解](#3-运行时行为详解)
- [4. 全局 mcp.json 编写规范](#4-全局-mcpjson-编写规范)
- [5. APPEND_SYSTEM 模板编写规范](#5-append_system-模板编写规范)
- [6. 各环境部署流程](#6-各环境部署流程)
- [7. 运维变更操作手册](#7-运维变更操作手册)
- [8. 常见 MCP 服务示例](#8-常见-mcp-服务示例)
- [9. 故障排查](#9-故障排查)
- [10. 安全注意事项](#10-安全注意事项)
- [11. 历史与设计决策](#11-历史与设计决策)

---

## 1. 设计背景

### 1.1 omp 原生 MCP 发现机制

omp coding-agent 在启动时通过 `loadAllMCPConfigs()` 扫描多个发现源（见 `packages/coding-agent/src/discovery/`）：

| 源 | 优先级 | 路径示例 |
|----|--------|---------|
| 项目级 | 高 | `<cwd>/.omp/mcp.json`, `<cwd>/mcp.json`, `<cwd>/.mcp.json` |
| 用户级 | 中 | `<agentDir>/mcp.json`（`PI_CODING_AGENT_DIR` 决定 agentDir）|
| 其他工具兼容 | 低 | `.cursor/mcp.json`, `.vscode/mcp.json`, `.claude/mcp.json` 等 |

omp 本身**没有"系统级 / 全局" MCP 概念**——多个源会按优先级去重而非合并。

### 1.2 多租户场景的需求矛盾

Oh-My-Pi 后端是企业多租户服务，每个用户拥有独立的 `agentDir`：

```
/data/omp/agent/{username}/                  ← PI_CODING_AGENT_DIR
├── mcp.json                                 ← omp 实际读取
├── APPEND_SYSTEM.md                         ← omp 实际读取
├── models.yml
├── agent.db
└── sessions/
```

需求：
- ✅ **所有用户共享同一套基础 MCP 工具**（codegraph、filesystem 等）
- ✅ **运维改一份配置 → 所有用户下次会话生效**
- ❌ 用户不能自行通过 `/mcp add` 持久化私有 MCP（管控风险面）
- ✅ 用户可以**有限度地**定制 system prompt（追加自己的偏好）

### 1.3 解决方案

**Java 端在 `OmpRpcClientFactory.spawn()` 中预处理模板**：

| 文件 | 策略 | 来源 | 用户私有 |
|------|------|------|----------|
| `<agentDir>/mcp.json` | **每次 spawn 强覆盖** | 全局模板 | ❌ 不支持 |
| `<agentDir>/APPEND_SYSTEM.md` | **每次 spawn 重新合并生成** | 全局模板 + 用户 `.user.md` | ✅ 支持 |

---

## 2. 文件布局总览

### 2.1 物理路径

| 角色 | 开发机（local） | UAT/生产容器 |
|------|----------------|-------------|
| 全局 mcp 模板 | `/tmp/omp/mcp.json` | `/etc/omp/mcp.json` |
| 全局 prompt 模板 | `/tmp/omp/APPEND_SYSTEM.md` | `/etc/omp/APPEND_SYSTEM.md` |
| agentRoot | `/tmp/omp/agent/` | `/data/omp/agent/` |
| 每用户 agentDir | `/tmp/omp/agent/{username}/` | `/data/omp/agent/{username}/` |
| 用户私有 prompt 覆盖 | `<agentDir>/APPEND_SYSTEM.user.md` | 同左 |
| omp 实际读取 mcp.json | `<agentDir>/mcp.json` | 同左 |
| omp 实际读取 prompt | `<agentDir>/APPEND_SYSTEM.md` | 同左 |

### 2.2 配置来源映射

`application.yml` / `application-uat.yml` / `application-120.yml` 都映射到 `OmpProperties`：

```yaml
app:
  omp:
    # 默认值见各 profile 的 yml；可被环境变量 OMP_MCP_TEMPLATE / OMP_APPEND_SYSTEM_TEMPLATE 覆盖
    mcp-template-path: /etc/omp/mcp.json
    append-system-template-path: /etc/omp/APPEND_SYSTEM.md
```

### 2.3 关系图

```
┌────────────────────────────────────────────────────────────────────┐
│                          运维（管理员）                              │
│         编辑全局模板（一份文件 → 所有用户生效）                       │
└──────────────────────────────┬─────────────────────────────────────┘
                               │ 写入
                               ▼
            ┌─────────────────────────────────────────┐
            │  /etc/omp/mcp.json                      │  全局 MCP 模板
            │  /etc/omp/APPEND_SYSTEM.md              │  全局 prompt 模板
            └────────────────┬────────────────────────┘
                             │ 每次 spawn 时被 Java 读取
                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│  OmpRpcClientFactory.spawn(userId, repoId, ...)                      │
│  ├─ agentDir = /data/omp/agent/{username}                            │
│  ├─ syncGlobalMcp(模板, agentDir/mcp.json)        ← 强覆盖           │
│  └─ syncAppendSystemMd(模板, agentDir/APPEND_SYSTEM.user.md,         │
│                       agentDir/APPEND_SYSTEM.md)  ← 合并生成         │
└──────────────────────────────┬───────────────────────────────────────┘
                               │ 写入用户专属 agentDir
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│  /data/omp/agent/{username}/                                         │
│  ├─ mcp.json                  ← omp 读：MCP 工具来源                 │
│  ├─ APPEND_SYSTEM.md          ← omp 读：自动追加到 system prompt    │
│  └─ APPEND_SYSTEM.user.md     ← 用户写：私有 prompt 覆盖（可选）    │
└──────────────────────────────┬───────────────────────────────────────┘
                               │ 启动子进程
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│  omp 子进程（PI_CODING_AGENT_DIR=/data/omp/agent/{username}）        │
│  loadAllMCPConfigs() → 发现 user 级 mcp.json → 加载 MCP 工具         │
│  discoverAppendSystemPromptFile() → 找到 APPEND_SYSTEM.md → 注入 prompt │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. 运行时行为详解

### 3.1 mcp.json 同步逻辑

实现：`OmpRpcClientFactory.syncGlobalMcp(Path templatePath, Path target)`

```
┌─────────────────────────────────────────┐
│ spawn() 调用                            │
└─────────────────────┬───────────────────┘
                      ▼
   ┌────────────────────────────────────┐
   │ templatePath 是 regular file ?      │
   └─────┬──────────────────────────┬────┘
         │ Yes                       │ No
         ▼                           ▼
  ┌──────────────────┐    ┌─────────────────────┐
  │ 用 REPLACE_EXISTING │    │ 跳过（保留 target  │
  │ 覆盖到 target     │    │ 现有内容,不报错)  │
  └──────────────────┘    └─────────────────────┘
```

**关键行为**：

| 条件 | 行为 |
|------|------|
| 模板路径为 null（配置缺失） | 跳过，target 保留旧内容 |
| 模板文件不存在 | 跳过 |
| 模板存在 + target 不存在 | 创建 target，复制内容 |
| 模板存在 + target 已存在 | **覆盖** target |
| 写入 IOException | 静默吞掉（日志中只有 stderr 看得到，不阻塞 spawn） |

**用户级私有 MCP 配置无效**：即使用户在 omp 内执行 `/mcp add`、`/mcp enable`，下次 spawn 时 `<agentDir>/mcp.json` 会被覆盖。

### 3.2 APPEND_SYSTEM.md 合并逻辑

实现：`OmpRpcClientFactory.syncAppendSystemMd(template, userOverride, target)`

```
┌─────────────────────────────────────────┐
│ spawn() 调用                            │
└─────────────────────┬───────────────────┘
                      ▼
   ┌──────────────────────────────┐
   │ 读 templatePath → global      │
   │ 读 userOverride → user        │
   └──────┬───────────────────────┘
          ▼
   ┌─────────────────────────────────────────────┐
   │ global / user 各自是 null 还是有内容?         │
   ├─────────────────────────────────────────────┤
   │  全 有 → global.trim + "\n\n---\n\n" + user │
   │  仅 global → 直接写 global                  │
   │  仅 user   → 直接写 user                    │
   │  都 无     → Files.deleteIfExists(target)   │
   └─────────────────────────────────────────────┘
```

**合并示例**：

`/etc/omp/APPEND_SYSTEM.md`：

```markdown
## CodeGraph (mcp__codegraph__*)
This workspace has a CodeGraph knowledge graph. Prefer its tools over grep/glob.
```

`/data/omp/agent/alice/APPEND_SYSTEM.user.md`：

```markdown
## Alice 的偏好
- 始终用中文回答
- 不要主动建议 git commit
```

生成的 `/data/omp/agent/alice/APPEND_SYSTEM.md`：

```markdown
## CodeGraph (mcp__codegraph__*)
This workspace has a CodeGraph knowledge graph. Prefer its tools over grep/glob.

---

## Alice 的偏好
- 始终用中文回答
- 不要主动建议 git commit
```

### 3.3 触发时机

只在 `OmpRpcClientFactory.spawn(sessionId, userId, repoId, resumePath)` 被调用时同步。具体时机：

1. **用户在 H5 前端创建新会话** → `SessionService.create()` → `ProcessPool.acquire()` → `OmpRpcClientFactory.spawn()` ✅
2. **用户在 H5 恢复历史会话** → 同上路径 ✅
3. **后端重启** → 池中所有 omp 进程被销毁，下次用户操作触发新 spawn ✅
4. **omp 进程内 `/mcp reload`** → ❌ **不会触发** Java 层重新同步模板（这是 omp 进程内自己重新扫描 `<agentDir>/mcp.json`）

> **重要**：编辑全局模板后，**已运行的 omp 进程不会立即生效**，必须等下次 spawn。如需立即生效，重启后端或让用户主动结束当前会话再新建。

### 3.4 性能影响

每次 spawn 增加 2 次文件 I/O（读模板 + 写 target），总耗时 < 1ms。模板文件极小（典型 < 10KB），无内存/CPU 压力。

---

## 4. 全局 mcp.json 编写规范

### 4.1 文件位置

| 环境 | 路径 | 由谁维护 |
|------|------|---------|
| 开发机 | `/tmp/omp/mcp.json` | 后端开发者本地手工放 |
| UAT/生产 容器 | `/etc/omp/mcp.json` | Dockerfile `COPY` 进去 / `docker cp` 手动更新 |

可被环境变量 `OMP_MCP_TEMPLATE` 覆盖（仅对开发机和 UAT 默认配置生效，生产 `application-120.yml` 是硬编码）。

### 4.2 文件格式

完整字段定义见 omp 官方文档 [`docs/mcp-config.md`](./mcp-config.md)。本规范的关键约束如下：

**必须遵守**：

```json
{
  "$schema": "https://raw.githubusercontent.com/can1357/oh-my-pi/main/packages/coding-agent/src/config/mcp-schema.json",
  "mcpServers": {
    "server-name": {
      "type": "stdio | http | sse",
      ...
    }
  }
}
```

- ✅ 始终添加 `$schema` 顶层字段（IDE/编辑器可校验）
- ✅ server 名称符合 `^[a-zA-Z0-9_.-]{1,100}$`
- ✅ 显式声明 `type`，**不要省略**（省略时默认 `stdio`，远程 server 没写 `type: "http"` 会被识别为 stdio 而报"缺少 command"）
- ✅ 显式设置 `timeout`（毫秒），网络慢的远程 server 设 60000+

**禁止**：

- ❌ 不要在全局模板里写**用户私有信息**（个人 token、个人路径）
- ❌ 不要在全局模板里写**绝对路径绑定到某个用户**（如 `/Users/alice/...`）
- ❌ 不要使用 `${VAR:-default}` 把敏感默认值硬编码进文件（用 env 注入）

### 4.3 字段速查

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | `"stdio"\|"http"\|"sse"` | 推荐 | 传输类型，omp 默认 `stdio` |
| `enabled` | boolean | 否 | `false` 时该 server 不会被加载 |
| `timeout` | number | 否 | 毫秒，连接/调用超时；`0` 禁用 |
| `command` | string | stdio 必填 | 子进程命令 |
| `args` | string[] | 否 | 子进程参数 |
| `env` | Record<string, string> | 否 | 子进程环境变量（见 [4.4 env 解析](#44-env--header-解析特性)） |
| `cwd` | string | 否 | 子进程工作目录 |
| `url` | string | http/sse 必填 | 远程 endpoint |
| `headers` | Record<string, string> | 否 | HTTP headers（同样支持 env 解析） |
| `auth` | object | 否 | OAuth/API key 元信息 |
| `oauth` | object | 否 | OAuth client 设置 |

### 4.4 env / header 解析特性

omp 在连接前会对 `env` value 和 `headers` value 做特殊解析（顺序）：

1. 值以 `!` 开头 → 当 shell 命令执行（10s 超时），取 trimmed stdout
2. 命令失败/超时/空白 → 该 entry 被**省略**
3. 否则检查值本身是否是环境变量名：是则取 `process.env[value]`
4. 都不是 → 当字面量

**实用场景**：

```json
"env": {
  "GITHUB_TOKEN": "GITHUB_TOKEN"          // 从 omp 子进程的 env 取
},
"headers": {
  "Authorization": "Bearer ${GITHUB_TOKEN}",      // 发现期 ${} 展开
  "X-API-Key": "!aws secretsmanager get-secret-value --secret-id foo --query SecretString --output text"
}
```

> **注意**：omp 子进程的 env 由 `OmpProcessSpec.effectiveEnv()` 决定。如果模板里写了 `"GITHUB_TOKEN": "GITHUB_TOKEN"`，但 Java 没把 `GITHUB_TOKEN` 注入到子进程 env，**会变成字面量字符串 `"GITHUB_TOKEN"`** 传给 MCP server，多半导致认证失败。

### 4.5 当前生产模板内容

> 实际线上版本见运维仓库；下文是参考起点

```json
{
  "$schema": "https://raw.githubusercontent.com/can1357/oh-my-pi/main/packages/coding-agent/src/config/mcp-schema.json",
  "mcpServers": {
    "codegraph": {
      "type": "stdio",
      "command": "codegraph",
      "args": ["serve", "--mcp"],
      "env": {
        "CODEGRAPH_MCP_TOOLS": "explore,context,node,search,callers,impact"
      },
      "timeout": 30000
    }
  }
}
```

---

## 5. APPEND_SYSTEM 模板编写规范

### 5.1 用途

`APPEND_SYSTEM.md` 内容会被 **追加** 到 omp 每次会话的 system prompt 末尾。常见用途：

- 告诉 LLM **优先使用某些 MCP 工具**（如 codegraph 替代 grep）
- 注入**项目背景**和**编码规范**
- 设置**回答语言、格式偏好**（中英、Markdown 风格）

### 5.2 全局模板（运维维护）

适合放：
- 跨用户共享的**工具使用策略**（MCP 工具的偏好顺序）
- 全公司**统一约定**（如代码风格、注释语言）
- omp 工具集说明

**示例**（参考当前 admin 用户的版本）：

```markdown
## CodeGraph (mcp__codegraph__*)

This workspace has a CodeGraph knowledge graph (`.codegraph/`) for surgical
code intelligence. Prefer its tools over grep/glob/Read when exploring code:

- `mcp__codegraph__codegraph_explore` — primary tool. One call returns relevant
  symbols' verbatim source grouped by file, plus call paths and blast radius.
  Use for "how does X work", "trace X → Y", "what would change if I edit X".
- `mcp__codegraph__codegraph_callers` / `..._impact` — narrow follow-ups after
  explore surfaces a symbol of interest.

The graph auto-syncs ~2s after file edits. If a fresh edit isn't reflected,
retry once; do not fall back to grep without trying codegraph first.

Fallback (graph missing / broken): use built-in `search` + `find` + `read`.
```

### 5.3 用户私有覆盖（用户/客服维护）

放在 `<agentDir>/APPEND_SYSTEM.user.md`。每个用户独立。

**典型内容**：

```markdown
## 我的偏好

- 始终用中文回答
- 不主动建议 git commit，由我决定提交时机
- 代码注释优先用中文
- 我熟悉 Spring Boot 生态，无需解释基础概念
```

> 用户**不能**通过 H5 前端编辑这个文件（前端不暴露此能力）。需要客服后台或运维通过 SSH/Docker 操作 agentDir 下手工放置。

### 5.4 编写建议

- 用 Markdown 二级标题 `##` 组织段落（合并后视觉清晰）
- 单条不超过 80 字，避免 system prompt 过长
- 不要写"你必须"等强约束（LLM 仍会偶尔违背，规则太刚性反而失真）
- **不要写敏感信息**（这个 prompt 会发给 LLM 服务商，不在 omp 本地）

---

## 6. 各环境部署流程

### 6.1 开发机（macOS / Linux 本地）

**首次配置**：

```bash
# 1. 创建模板目录
mkdir -p /tmp/omp

# 2. 创建全局 MCP 模板
cat > /tmp/omp/mcp.json <<'EOF'
{
  "$schema": "https://raw.githubusercontent.com/can1357/oh-my-pi/main/packages/coding-agent/src/config/mcp-schema.json",
  "mcpServers": {
    "codegraph": {
      "type": "stdio",
      "command": "codegraph",
      "args": ["serve", "--mcp"],
      "env": {
        "CODEGRAPH_MCP_TOOLS": "explore,context,node,search,callers,impact"
      },
      "timeout": 30000
    }
  }
}
EOF

# 3. 创建全局 APPEND_SYSTEM 模板
cat > /tmp/omp/APPEND_SYSTEM.md <<'EOF'
## CodeGraph (mcp__codegraph__*)

This workspace has a CodeGraph knowledge graph. Prefer its tools over grep/glob.
EOF

# 4. 重启后端
cd backend && mvn spring-boot:run
```

**验证**：

```bash
# 触发任意用户创建会话后，agentDir 应同步出新文件
diff /tmp/omp/mcp.json /tmp/omp/agent/admin/mcp.json   # 应无差异
ls /tmp/omp/agent/admin/APPEND_SYSTEM.md               # 应存在
```

### 6.2 UAT 服务器（10.126.2.120）

参考 `docs/DEPLOY.md` §18 完整部署文档。MCP 相关步骤：

**第一次部署或更新模板**：

```bash
# 在你的开发机上准备好两份模板文件
# 编辑 /home/omp/docker-build/codegraph-mcp.json （新名称建议直接命名为 mcp.json）
# 编辑 /home/omp/docker-build/APPEND_SYSTEM.md

# 上传 + 拷贝进容器
ssh root@10.126.2.120 'docker cp /home/omp/docker-build/mcp.json \
  omp-app:/etc/omp/mcp.json && \
  docker cp /home/omp/docker-build/APPEND_SYSTEM.md \
  omp-app:/etc/omp/APPEND_SYSTEM.md'
```

**等待生效**：下次每个用户的会话首次 spawn 时，Java 端会自动从 `/etc/omp/` 重新拷贝。

> 如果需要立即生效（所有用户当前会话失效）：`docker exec omp-app sh -c 'rm /data/omp/agent/*/mcp.json /data/omp/agent/*/APPEND_SYSTEM.md'`，然后用户在 H5 重连。

### 6.3 Dockerfile（生产容器）

参考 `docs/DEPLOY.md` §20.8。Dockerfile 应有：

```dockerfile
# 在 omp-allinone 镜像构建阶段
COPY mcp.json            /etc/omp/mcp.json
COPY APPEND_SYSTEM.md    /etc/omp/APPEND_SYSTEM.md
```

> **历史兼容**：旧版本 Dockerfile 使用 `codegraph-mcp.json` 作为文件名，新版收敛为 `mcp.json`。如果生产环境暂时不想改 Dockerfile，可在 `application-120.yml` 中把 `mcp-template-path` 指向 `/etc/omp/codegraph-mcp.json`（保留旧名）。

---

## 7. 运维变更操作手册

### 7.1 新增一个 MCP 服务

**例**：在全局模板加入 GitHub MCP

**Step 1**: 编辑全局模板

开发机 `/tmp/omp/mcp.json` 或生产容器 `/etc/omp/mcp.json`：

```json
{
  "mcpServers": {
    "codegraph": { ... existing ... },
    "github": {
      "type": "http",
      "url": "https://api.githubcopilot.com/mcp/",
      "headers": {
        "Authorization": "Bearer ${GITHUB_TOKEN}"
      },
      "timeout": 60000
    }
  }
}
```

**Step 2**: 注入 Token 到 omp 子进程

如果用 `${GITHUB_TOKEN}` 占位符，需要让 omp 子进程能拿到 env。修改 `OmpProcessSpec.effectiveEnv()`：

```java
public Map<String, String> effectiveEnv() {
    Map<String, String> m = new HashMap<>(env);
    m.put("PI_CODING_AGENT_DIR", agentDir.toAbsolutePath().toString());
    m.putIfAbsent("PI_NOTIFICATIONS", "off");
    // 新增：注入 GitHub Token（从 Java 进程的 env 透传）
    String ghToken = System.getenv("GITHUB_TOKEN");
    if (ghToken != null && !ghToken.isBlank()) {
        m.put("GITHUB_TOKEN", ghToken);
    }
    ...
    return m;
}
```

或者把 token 放进 `OmpProperties`（推荐：避免在代码里读 `System.getenv`）：

```java
// application.yml
app.omp.mcp.tokens.github: ${GITHUB_TOKEN:}
```

**Step 3**: 重启后端，等待用户下次 spawn 自动应用

**Step 4**: 在 H5 用任一用户登录，开启新会话，确认 LLM 能调用 `mcp__github_*` 工具

### 7.2 临时禁用某个 MCP

```json
{
  "mcpServers": {
    "github": {
      "type": "http",
      "url": "...",
      "enabled": false        // 整段保留，只关掉
    }
  }
}
```

> 保留比删除好——下次想重新启用只要改 `true`，不用重写一整段配置。

### 7.3 给指定用户添加私有 system prompt

```bash
# 进生产容器（或 SSH 到 UAT 服务器）
docker exec -it omp-app sh
cd /data/omp/agent/{username}/

# 创建 .user.md
cat > APPEND_SYSTEM.user.md <<'EOF'
## 用户 alice 的偏好

- 我是后端 Java 开发，不需要解释 Spring Boot 基础概念
- 始终用中文回答
EOF

# 让用户下次开新会话即生效（已运行会话不受影响）
```

> 如需立即生效：删除该用户的 `APPEND_SYSTEM.md`（删除后 Java 会在下次 spawn 重新合并），并让用户在 H5 结束当前会话重新开始。

### 7.4 验证某个用户的最终生效配置

```bash
# UAT 服务器
docker exec omp-app cat /data/omp/agent/alice/mcp.json
docker exec omp-app cat /data/omp/agent/alice/APPEND_SYSTEM.md
```

注意：mcp.json 应**与全局模板逐字节相同**；APPEND_SYSTEM.md 应是合并后的版本。

### 7.5 模板文件大改后强制全部用户同步

平时下次 spawn 自动同步即可。如果想强制全员**立即**应用：

```bash
# UAT 服务器，强制清掉所有用户的已生成文件
docker exec omp-app sh -c '
  for d in /data/omp/agent/*/; do
    rm -f "$d/mcp.json" "$d/APPEND_SYSTEM.md"
  done
'

# 等用户在 H5 创建/恢复会话时自动重新生成
# 或直接重启后端清空 ProcessPool，所有在线会话失效
docker restart omp-app
```

---

## 8. 常见 MCP 服务示例

### 8.1 CodeGraph（已默认启用）

工作区代码图谱，AST 解析 + 调用图分析。stdio 启动。

```json
{
  "codegraph": {
    "type": "stdio",
    "command": "codegraph",
    "args": ["serve", "--mcp"],
    "env": {
      "CODEGRAPH_MCP_TOOLS": "explore,context,node,search,callers,impact"
    },
    "timeout": 30000
  }
}
```

**前置条件**：容器内 `codegraph` 二进制在 PATH 中（Dockerfile 已安装）。

### 8.2 Filesystem（按需启用）

让 agent 读写指定根目录下的任意文件，不受 omp 内置 read/edit 工具的 workspace 限制。

```json
{
  "filesystem": {
    "type": "stdio",
    "command": "npx",
    "args": [
      "-y",
      "@modelcontextprotocol/server-filesystem",
      "/data/omp/workspaces"
    ],
    "timeout": 30000
  }
}
```

**前置条件**：容器内有 `node` + `npx`；首次会下载 npm 包，首次启动慢 10-30s。

### 8.3 Fetch（按需启用）

抓取任意 HTTP URL → 转 Markdown 给 agent。

```json
{
  "fetch": {
    "type": "stdio",
    "command": "uvx",
    "args": ["mcp-server-fetch"],
    "timeout": 60000
  }
}
```

**前置条件**：容器内有 Python `uv` 工具链。

### 8.4 GitHub（HTTP，需 Token）

```json
{
  "github": {
    "type": "http",
    "url": "https://api.githubcopilot.com/mcp/",
    "headers": {
      "Authorization": "Bearer ${GITHUB_TOKEN}"
    },
    "timeout": 60000
  }
}
```

**前置条件**：`OmpProcessSpec.effectiveEnv()` 注入 `GITHUB_TOKEN` 环境变量。

### 8.5 公司内部 MCP（HTTP）

```json
{
  "wiki": {
    "type": "http",
    "url": "https://wiki.company.local/mcp",
    "headers": {
      "Authorization": "Bearer ${COMPANY_API_TOKEN}",
      "X-Tenant-Id": "${OMP_TENANT_ID:-default}"
    },
    "timeout": 30000
  }
}
```

---

## 9. 故障排查

### 9.1 LLM 不调用 MCP 工具

**症状**：会话里 LLM 完全不使用 `mcp__xxx_*` 工具。

**排查**：

```bash
# 1. 检查全局模板是否存在
ls -l /etc/omp/mcp.json  # 或 /tmp/omp/mcp.json

# 2. 检查用户 agentDir 是否同步成功
docker exec omp-app cat /data/omp/agent/alice/mcp.json

# 3. 检查 omp 子进程是否连接 MCP 成功（关键！）
docker exec omp-app tail -100 /data/omp/logs/omp-{sessionId}.err.log | grep -i mcp

# 常见关键字：
#   "MCP server connected" → 连上了
#   "Failed to connect to MCP" → 连接失败
#   "tools/list timed out" → server 慢，看 timeout 设置
```

### 9.2 用户 agentDir 里的 mcp.json 和模板不一致

**原因**：用户 omp 进程仍在运行，未触发新 spawn。

**修复**：

```bash
# 让用户在 H5 主动结束并重开会话
# 或重启后端清空 ProcessPool
docker restart omp-app
```

### 9.3 编辑了模板但用户没生效

确认顺序：

1. 模板文件路径是否与 `application-*.yml` 中 `mcp-template-path` 一致？
2. 模板修改后**用户是否触发了新 spawn**（恢复会话或新建）？
3. 后端日志是否有 spawn 异常？

```bash
docker exec omp-app grep "omp.spawn" /app/logs/*.log | tail -10
```

### 9.4 APPEND_SYSTEM 合并结果异常

**症状**：`<agentDir>/APPEND_SYSTEM.md` 内容不对，但 `.user.md` 是对的。

**排查**：

```bash
# 1. 看全局模板内容
cat /etc/omp/APPEND_SYSTEM.md

# 2. 看用户私有
cat /data/omp/agent/alice/APPEND_SYSTEM.user.md

# 3. 看实际生成
cat /data/omp/agent/alice/APPEND_SYSTEM.md
# 应该是：全局 + "\n\n---\n\n" + 用户

# 4. 检查文件编码（必须 UTF-8）
file /etc/omp/APPEND_SYSTEM.md
```

如果用户在 H5/IDE 直接编辑了 `APPEND_SYSTEM.md`（不是 `.user.md`），下次 spawn 会被合并结果覆盖。**告知用户必须改 `.user.md`**。

### 9.5 omp 子进程启动失败

模板 JSON 语法错误会导致 omp 在加载 MCP 时报错，但**不会**让 omp 进程挂掉（omp 是 best-effort 加载）。但如果模板路径权限错误：

```bash
# 容器内权限检查（应可读）
docker exec omp-app ls -l /etc/omp/
# -rw-r--r-- 1 root root  ... /etc/omp/mcp.json   ← 这个权限 OK

# 如果是 700 但 omp 子进程不是 root → 权限不足，syncGlobalMcp 静默失败
```

### 9.6 用户 agentDir 没自动创建

`WorkspaceService.userAgentDir()` 会在 `props.agentRoot()` 下自动创建。如果失败：

```bash
# agentRoot 目录权限
docker exec omp-app ls -ld /data/omp/agent
# 应该 drwxr-xr-x

# 卷挂载是否正确
docker inspect omp-app | grep -A 5 Mounts
```

---

## 10. 安全注意事项

### 10.1 模板文件权限

| 文件 | 推荐权限 | 理由 |
|------|---------|------|
| `/etc/omp/mcp.json` | 644 (root:root) | 所有用户可读，仅运维可写 |
| `/etc/omp/APPEND_SYSTEM.md` | 644 | 同上 |
| `<agentDir>/APPEND_SYSTEM.user.md` | 644 | 用户可读，运维可写 |
| `<agentDir>/mcp.json`（生成） | 644 | 由 Java 进程写，omp 子进程读 |

### 10.2 不要在 mcp.json 里硬编码 secret

所有 token / API key 必须用以下方式之一：

- `${ENV_VAR}` 占位符 + 在 `OmpProcessSpec.effectiveEnv()` 注入
- `!shell-command` 从外部 secrets manager 拉取
- `auth.credentialId` + omp 内置 credential store

**反例**（禁止）：

```json
"headers": { "Authorization": "Bearer sk-abc123..." }   // ❌ 明文 token
```

### 10.3 用户级 prompt 注入风险

`<agentDir>/APPEND_SYSTEM.user.md` 由运维/客服代写，**不应**暴露给用户自助编辑。理由：

- 用户可在 prompt 里写"忽略以上所有规则" → LLM 行为不可控
- 用户可写"导出所有其他用户的会话历史" → 试图越权（虽然 omp 工具层有边界，但 LLM 会尝试调用）

**规范**：用户私有 prompt 通过工单/审批流由客服添加，不开放前端编辑入口。

### 10.4 MCP server 信任边界

新增 MCP server 前评估：

- ✅ **stdio MCP**：调本地命令，权限继承 omp 子进程（受 omp Docker 容器约束）
- ⚠️ **http MCP**：会泄露 prompt/工具参数到外部 server，确认 server 受信
- ❌ **不要加来路不明的开源 MCP**：尤其是 Smithery 之类的公共注册中心

### 10.5 禁用用户级 `/mcp add` 的副作用

omp 的 `/mcp add` 命令仍然可用——用户在会话内执行后会向 `<agentDir>/mcp.json` 写入新 server。但**下次 spawn 时被覆盖**。

**用户体验副作用**：用户可能困惑"我加的 MCP 怎么没了"。可以在 H5 前端的帮助文档里说明：

> MCP 配置由系统统一管理。如需新增 MCP 服务，请联系管理员。

---

## 11. 历史与设计决策

### 11.1 演进时间线

| 时间 | 状态 | 变更 |
|------|------|------|
| ~Jun 2026 之前 | 旧 | `copyTemplateIfAbsent("/etc/omp/codegraph-mcp.json", ...)`，仅当 target 不存在时拷贝 |
| 2026-06-23 | 新 | 拆分为 `syncGlobalMcp`（强覆盖）+ `syncAppendSystemMd`（合并）；模板路径配置化 |

### 11.2 为什么不用 symlink

考虑过给每个 agentDir 的 `mcp.json` 软链到 `/etc/omp/mcp.json`：

- ✅ 改一份立即生效（无需重启会话）
- ❌ Docker `cp` 替换文件会破坏 symlink，需要 `cp --remove-destination`
- ❌ Windows 不友好（虽然部署在 Linux，开发机也可能是 Linux）
- ❌ `<agentDir>` 由 `WorkspaceService` 自动创建，每用户首次访问触发，建 symlink 的逻辑会让代码更绕

最终选择"每次 spawn 拷贝"——虽然多了几次文件 I/O，但行为可预测、跨平台兼容、易于调试。

### 11.3 为什么 mcp.json 不合并

考虑过和 APPEND_SYSTEM 一样支持 `mcp.user.json` 合并：

- ❌ JSON 合并语义复杂（同名 server 怎么合？数组怎么合？）
- ❌ 用户私有 MCP 会引入未审计的外部依赖（命令注入风险）
- ✅ Mardkown 合并简单（拼接 + 分隔线）且语义清晰
- ✅ prompt 即使用户写错也只是 LLM 行为偏差，不会真的执行恶意代码

所以决定：MCP **全局唯一**，prompt **允许私有覆盖**。

### 11.4 为什么模板缺失静默跳过

设计原则：**模板系统不应阻塞 omp 启动**。

- 开发机首次安装：开发者还没建 `/tmp/omp/mcp.json`，omp 仍能跑（只是没 MCP）
- 生产首次升级：Dockerfile 还没加 COPY 时，老镜像照常工作
- 模板文件意外删除：服务降级（无 MCP）而非完全宕机

代价：模板缺失时**用户感觉"少了工具"但不会报错**，运维需要主动验证。这个 trade-off 用文档（本文）兜底。

---

## 附录 A：完整字段速查（Java 端）

### `OmpProperties` 新增字段

```java
public record OmpProperties(
    ...,
    Path mcpTemplatePath,            // 必须配置在 application*.yml
    Path appendSystemTemplatePath    // 必须配置在 application*.yml
) {}
```

### `OmpRpcClientFactory` 关键方法

| 方法 | 输入 | 输出/副作用 |
|------|------|------------|
| `syncGlobalMcp(template, target)` | 模板路径、目标 | 模板存在则覆盖 target；缺失则跳过 |
| `syncAppendSystemMd(template, userOverride, target)` | 全局模板、用户私有、目标 | 按合并规则生成 target；都无则删 target |
| `readIfRegular(p)` | 文件路径 | 文件内容或 null |

### 配置文件路径覆盖矩阵

| profile | mcp 模板默认值 | append-system 模板默认值 |
|---------|---------------|------------------------|
| `default` (本机开发) | `/tmp/omp/mcp.json` | `/tmp/omp/APPEND_SYSTEM.md` |
| `uat` | `/etc/omp/mcp.json` | `/etc/omp/APPEND_SYSTEM.md` |
| `prod` / `120` | `/etc/omp/mcp.json` (硬编码) | `/etc/omp/APPEND_SYSTEM.md` (硬编码) |

环境变量 `OMP_MCP_TEMPLATE` / `OMP_APPEND_SYSTEM_TEMPLATE` 可覆盖 default 和 uat。

---

## 附录 B：相关文档

- omp 官方 MCP 配置文档：[`docs/mcp-config.md`](./mcp-config.md)
- omp MCP 运行时生命周期：[`docs/mcp-runtime-lifecycle.md`](./mcp-runtime-lifecycle.md)
- omp MCP 协议传输层：[`docs/mcp-protocol-transports.md`](./mcp-protocol-transports.md)
- omp MCP server 工具开发：[`docs/mcp-server-tool-authoring.md`](./mcp-server-tool-authoring.md)
- 部署 runbook：[`docs/DEPLOY.md`](./DEPLOY.md) §18, §20.8
- MCP 协议规范：https://modelcontextprotocol.io/specification/2025-03-26/basic/transports

---

## 附录 C：FAQ

**Q1: 用户可以在 H5 里管理自己的 MCP 吗？**
A: 不行。所有 MCP 配置由全局模板统一管控。用户在 omp 会话内执行 `/mcp add` 也会在下次 spawn 时被覆盖。

**Q2: 我改了 `/etc/omp/mcp.json` 后所有用户都立即生效吗？**
A: 不是。只对**下次 spawn** 的会话生效。已运行的 omp 子进程不会重新加载。

**Q3: 用户私有的 `APPEND_SYSTEM.user.md` 备份吗？**
A: 取决于运维卷挂载策略。`<agentDir>` 在生产环境是挂载到 docker volume，默认随容器持久化（参考 `docs/DEPLOY.md`）。但建议运维定期把所有用户的 `.user.md` 备份到独立存储。

**Q4: 如果想让某个用户的 MCP 配置和别人不一样怎么办？**
A: 当前架构不支持。如有明确业务需要，建议另起方案：在全局模板里用 `${OMP_USER_ID}` 占位符 + Java 注入用户 ID 到 env，让 MCP server 自己按用户 ID 切换行为。或者扩展 `syncGlobalMcp` 支持 `<agentDir>/mcp.user.json` 合并（需新方案讨论）。

**Q5: 全局模板路径可以放在工程内（如 `backend/src/main/resources/global-mcp.json`）吗？**
A: 不推荐。原因：
1. 配置应可独立于 jar 发布，运维改一行不需要重新构建/部署后端
2. 容器内 jar 是只读的（classpath resource 不能改）
3. 当前设计就是为了**运维改模板 → 立即生效（下次 spawn）**

**Q6: omp 升级后如果 MCP 协议变了，我的全局模板还能用吗？**
A: omp MCP 配置 schema 至 2026-06 稳定。重大变更会在 omp release notes 公布。模板的 `$schema` URL 指向 main 分支的最新 schema，建议升级 omp 时同步 review 一遍。

---

**文档维护者**：后端组
**最后更新**：2026-06-23
**关联 commit**：(待提交) feat(rpc): 全局 MCP 配置 + APPEND_SYSTEM 合并模式
