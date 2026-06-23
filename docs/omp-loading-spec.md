# OMP 会话启动加载规范

> **本文档说明**：当 Java 后端（`OmpRpcClientFactory`）启动一个 omp 子进程时，omp 在会话初始化阶段会从多个位置读取配置文件、上下文文件和记忆数据。这些加载项决定了 LLM 在每次会话中能做什么、看到什么、记住什么。
>
> **关联代码**：
> - `backend/src/main/java/com/yourorg/omp/rpc/OmpProcessSpec.java`（启动参数构造）
> - `backend/src/main/java/com/yourorg/omp/rpc/OmpRpcClientFactory.java`（模板同步 + 环境注入）
> - `packages/coding-agent/src/main.ts`（CLI 入口）
> - `packages/coding-agent/src/sdk.ts`（会话构建）
> - `packages/coding-agent/src/mnemopi/`（记忆引擎）

---

## 目录

- [1. 加载全貌图](#1-加载全貌图)
   - [1.1 数据流：从模板到 LLM](#11-数据流从模板到-llm)
   - [1.2 决策树：每个加载项的开关](#12-决策树每个加载项的开关)
- [2. Java 端启动参数详解](#2-java-端启动参数详解)
   - [2.1 argv 完整清单与加载映射](#21-argv-完整清单与加载映射)
   - [2.2 环境变量与加载位置的关系](#22-环境变量与加载位置的关系)
- [3. 九大加载项详解](#3-九大加载项详解)
   - [3.1 MCP 配置 (mcp.json)](#31-mcp-配置-mcpjson)
   - [3.2 System Prompt 硬覆盖 (SYSTEM.md)](#32-system-prompt-硬覆盖-systemmd)
   - [3.3 Append Prompt 追加 (APPEND_SYSTEM.md)](#33-append-prompt-追加-append_systemmd)
   - [3.4 项目上下文文件 (AGENTS.md)](#34-项目上下文文件-agentsmd)
   - [3.5 Skills 技能包](#35-skills-技能包)
   - [3.6 Extensions 扩展 (RTK)](#36-extensions-扩展-rtk)
   - [3.7 Hooks 钩子 (rtk-proxy)](#37-hooks-钩子-rtk-proxy)
   - [3.8 Memory 记忆后端 (mnemopi / hindsight)](#38-memory-记忆后端-mnemopi--hindsight)
   - [3.9 Models 模型配置 (models.yml)](#39-models-模型配置-modelsyml)
- [4. 加载优先级与去重规则](#4-加载优先级与去重规则)
- [5. 会话启动时间线](#5-会话启动时间线)
- [6. 配置速查表](#6-配置速查表)
- [7. 附录：部署对照表](#7-附录部署对照表)

---

## 1. 加载全貌图

本工程将 omp 接入多租户 Java 后端。所有"每次会话加载的内容"可分为三路源头：

- **模板源**（运维维护一份 → 全局生效）
- **参数源**（Java 硬编码在 `OmpProcessSpec.toArgv()` 中）
- **用户源**（用户私有文件，仅影响该用户自己）

### 1.1 数据流：从模板到 LLM

下图中箭头的方向表示**数据传递方向**，而非调用链。详见各章节节标注。

```
┌═══════════════════════════════════════════════════════════════════════════┐
║                      运维维护的模板文件（一或多份）                       ║
║                                                                          ║
║  /etc/omp/mcp.json              §3.1                                    ║
║  /etc/omp/APPEND_SYSTEM.md      §3.3                                    ║
╚═══════════════════════════════╤═══════════════════════════════════════════╝
                                │ 模板路径由 application.yml 配置
                                │ 文件不存在则跳过（开发机未放模板不报错）
                                ▼
┌═══════════════════════════════════════════════════════════════════════════┐
║              Java 后端每次 spawn 时的预处理（OmpRpcClientFactory）         ║
║                                                                          ║
║  同步的对象：                                                             ║
║  ┌──────────────────────────────────────────────────────────────────┐    ║
║  │ 文件              时机     受影响的用户                          │    ║
║  ├──────────────────────────────────────────────────────────────────│    ║
║  │ <agentDir>/mcp.json           强覆盖     所有用户                 │    ║
║  │ <agentDir>/APPEND_SYSTEM.md   合并生成   所有用户（含私有）      │    ║
║  │ <agentDir>/models.yml         增量合并   所有用户                 │    ║
║  │ <agentDir>/SYSTEM.md          [未处理]   被任何放置者影响         │    ║
║  │ workspace/.codegraph/ 索引    后台刷新   单个用户的工作区         │    ║
║  └──────────────────────────────────────────────────────────────────┘    ║
║                                                                          ║
║  同时构造进程参数：                                                        ║
║  ┌──────────────────────────────────────────────────────────────────┐    ║
║  │ 参数              作用                          关联章节         │    ║
║  ├──────────────────────────────────────────────────────────────────│    ║
║  │ --no-rules        禁用 AGENTS.md/CLAUDE.md 发现      §3.4       │    ║
║  │ --no-skills       禁用 Skills 发现                    §3.5       │    ║
║  │ --extension       显式加载 RTK 扩展                   §3.6       │    ║
║  │ --hook            显式加载 rtk-proxy 钩子             §3.7       │    ║
║  │ --api-key         注入 LLM API Key                    §3.9       │    ║
║  └──────────────────────────────────────────────────────────────────┘    ║
╚═══════════════════════════════╤═══════════════════════════════════════════╝
                                │ PI_CODING_AGENT_DIR = <agentDir>
                                ▼
┌═══════════════════════════════════════════════════════════════════════════┐
║                    omp 子进程（coding-agent）启动时                          ║
║                                                                          ║
║  每个子进程有一个独立的 <agentDir>（由 PI_CODING_AGENT_DIR 决定）           ║
║                                                                          ║
║  ┌─ main.ts:runRootCommand() ───────────────────────────────────────┐   ║
║  │ 解析 Args → buildSessionOptions() → SDK createAgentSession()     │   ║
║  └──────────────────────────────────────────────────────────────────┘   ║
║     ▼                                                                   ║
║  ┌─ sdk.ts:createAgentSession() ───────────────────────────────────┐   ║
║  │  加载项                     来源文件          Java 是否参与      │   ║
║  ├─────────────────────────────────────────────────────────────────│   ║
║  │  MCP 工具集      <agentDir>/mcp.json              ✅ 模板覆盖    │   ║
║  │  System Prompt   <agentDir>/SYSTEM.md              ⚠️ 未处理     │   ║
║  │  Append Prompt   <agentDir>/APPEND_SYSTEM.md       ✅ 模板合并    │   ║
║  │  Context Files   <cwd> 向上 AGENTS.md              ❌ --no-rules │   ║
║  │  Skills          skills/ 目录                      ❌ --no-skills│   ║
║  │  Extensions      --extension 路径                   ✅ 显式传参    │   ║
║  │  Hooks           --hook 路径                        ✅ 显式传参    │   ║
║  │  Memory          memory.backend 设置                ❌ 未启用     │   ║
║  │  Models          <agentDir>/models.yml              ✅ 模板合并    │   ║
║  └─────────────────────────────────────────────────────────────────┘   ║
║     ▼                                                                   ║
║  ┌─ 最终合并为 System Prompt + Append Prompt + Tools → 发送给 LLM ──┐   ║
╚═══════════════════════════════════════════════════════════════════════════╝
```

### 1.2 决策树：每个加载项的开关

运维/开发者需要回答"某个加载项为什么存在/不存在"时，查这张树图：

```
                               omp 子进程启动
                                      │
                                      ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  查找 models.yml（<agentDir>/models.yml）                          │
  │  存在 → 加载自定义模型提供商                                       │
  │  不存在 → 仅使用 omp 内置模型                                      │
  │  Java 端已 syncModelsYml() 保证存在                               │
  └──────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  加载 MCP 配置（mcp.json）                                         │
  │  按能力系统多源发现，优先级见 §4                                    │
  │  Java 端已 syncGlobalMcp() 强覆盖 user 级                          │
  │  但项目级（<cwd>/.omp/mcp.json）若存在仍会叠加                    │
  └──────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  是否传了 --no-rules?                                             │
  │  YES → 跳过 AGENTS.md 发现                      ← Java 传了      │
  │  NO  → 沿 <cwd> 向上查找所有 AGENTS.md 并加载                    │
  └──────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  是否传了 --no-skills?                                            │
  │  YES → 跳过 Skills 发现                          ← Java 传了      │
  │  NO  → 发现并注册 .omp/skills/ 等                                │
  └──────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  查找 SYSTEM.md                                                    │
  │  先查项目级：<cwd>/.omp/SYSTEM.md（或 .agents/SYSTEM.md 等）      │
  │  再查用户级：<agentDir>/SYSTEM.md                                  │
  │  任一存在 → 整段替换 omp 默认 system prompt                        │
  │  都不存在 → 使用 omp 默认 prompt（正确行为）                       │
  │  ⚠️ Java 端未参与此文件管理                                         │
  └──────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  加载 Extensions（--extension <path>）                             │
  │  每个显式传入的路径存在则加载                                      │
  │  Java 已传入 RTK 扩展（条件加载）                                   │
  │  <agentDir>/extensions/ 下的其他文件不会自动加载                    │
  └──────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  加载 Hooks（--hook <path>）                                       │
  │  同 extension 逻辑：只加载显式传入的路径                            │
  │  Java 已传入 rtk-proxy hook（条件加载）                            │
  └──────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  查找 APPEND_SYSTEM.md                                             │
  │  先查项目级：<cwd>/.omp/APPEND_SYSTEM.md                           │
  │  再查用户级：<agentDir>/APPEND_SYSTEM.md  ← Java 已合并生成       │
  │  找到 → 追加到 system prompt 末尾                                  │
  │  找不到 → 跳过（无追加）                                           │
  │  Java 端已 syncAppendSystemMd() 保证存在（除非模板和 .user 都缺） │
  └──────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  读取 memory.backend 设置                                          │
  │  = "mnemopi" → 从 agent.db 加载跨会话记忆并追加到 append prompt    │
  │  = "none"    → 跳过                         ← 当前默认            │
  │  Java 端未管理此设置                                               │
  └──────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                           系统 Prompt 最终定型
                    ┌──── 默认 prompt（由 omp 内部组装）
                    │ 替换 SYSTEM.md 的定制（如果有）
                    │ 追加 APPEND_SYSTEM.md 的内容（如果有）
                    └ 追加 Memory Instructions（mnemopi 启用时）
```

---

## 2. Java 端启动参数详解

### 2.1 argv 完整清单与加载映射

`OmpProcessSpec.toArgv()` 构造的完整命令行。每个参数均列出取值来源和它控制的加载行为。

| 序号 | 参数 | 取值来源 | 控制的加载行为 | 关联章节 |
|:----:|------|---------|--------------|:--------:|
| 1 | `--mode rpc-ui` | 硬编码 | 启用 WebSocket RPC 模式 + `ask` UI 工具 | §5 |
| 2 | `--thinking <level>` | `AdminConfigService.thinkingLevel()` → `props.defaultFlags().thinking()` | LLM 推理深度 | - |
| 3 | `--approval-mode <mode>` | `AdminConfigService.approvalMode()` → `props.defaultFlags().approvalMode()` | 工具调用审批模式 | - |
| 4 | `--tools <list>` | `AdminConfigService.toolWhitelist()` → `props.defaultFlags().tools` | 内置工具白名单（没有 MCP 工具，MCP 由 SDK 另加载） | - |
| 5 | `--no-rules` | **硬编码** | ❌ 禁用 AGENTS.md / CLAUDE.md 自动发现 | §3.4 |
| 6 | `--no-skills` | **硬编码** | ❌ 禁用 Skills 自动发现 | §3.5 |
| 7 | `--extension <path>` | `Files.isRegularFile()` 探测≥1个位置 | ✅ 按需加载扩展（RTK） | §3.6 |
| 8 | `--hook <path>` | `Files.isRegularFile()` 探测≥1个位置 | ✅ 按需加载钩子（rtk-proxy） | §3.7 |
| 9 | `--provider <id>` | Admin 模型配置 → `props.defaultModel().provider()` | LLM 提供商选择 | §3.9 |
| 10 | `--model <id>` | Admin 模型配置 → `props.defaultModel().modelId()` | LLM 模型选择 | §3.9 |
| 11 | `--api-key <key>` | `AdminConfigService.apiKey()` → `props.vault().apiKey()` | LLM API 认证 | §3.9 |
| 12 | `--resume <path>` | spa 时参数 `resumePath` | 恢复历史会话（不是加记载入项，是跳过新建） | - |
| 13 | `--fork <path>` | spa 时参数 `forkFromSessionPath` | 从历史会话分叉 | - |

**"硬编码"的含义**：在 `OmpProcessSpec.java` 里写死，环境 / 配置不可覆盖。如需修改，必须改 Java 源码 + 重新部署。

**扩展/Hook 的条件探测逻辑**（行 83-103）：

```java
// 双路径探测：容器路径优先，开发机路径降级
Path path = Path.of("/root/.omp/agent/extensions/rtk.ts");   // 容器
if (!Files.isRegularFile(path) && userHome != null) {
    path = Path.of(userHome, ".omp/agent/extensions/rtk.ts"); // 开发机
}
if (Files.isRegularFile(path)) {
    argv.add("--extension");
    argv.add(path.toString());
}
// hook 同理，路径换成 /root/.omp/hooks/rtk-proxy.ts
```

### 2.2 环境变量与加载位置的关系

`OmpProcessSpec.effectiveEnv()` 注入的环境变量，决定了 omp 子进程**所有文件读取的根路径**：

```java
public Map<String, String> effectiveEnv() {
    Map<String, String> m = new HashMap<>(env);
    m.put("PI_CODING_AGENT_DIR", agentDir.toAbsolutePath().toString());  // ★ 最关键
    m.putIfAbsent("PI_NOTIFICATIONS", "off");
    // API 端点（根据 api 协议选择）
    if ("anthropic-messages".equals(api)) {
        m.put("ANTHROPIC_BASE_URL", baseUrl);
    } else {
        m.put("OPENAI_BASE_URL", baseUrl);
    }
    return m;
}
```

**`PI_CODING_AGENT_DIR` 的决定性作用**：

| omp 内部路径 | 实际物理路径 | 由 `PI_CODING_AGENT_DIR` 决定 |
|-------------|-------------|:--------------------------:|
| `<agentDir>/mcp.json` | `/data/omp/agent/{username}/mcp.json` | ✅ 全量 |
| `<agentDir>/APPEND_SYSTEM.md` | 同上 | ✅ 全量 |
| `<agentDir>/SYSTEM.md` | 同上 | ✅ 全量 |
| `<agentDir>/models.yml` | 同上 | ✅ 全量 |
| `<agentDir>/agent.db` | 同上（会话历史 SQLite） | ✅ 全量 |
| `<agentDir>/sessions/` | 同上（JSONL 会话文件） | ✅ 全量 |
| `<cwd>/.omp/mcp.json` | `/data/omp/workspaces/{username}/{repoId}/.omp/mcp.json` | ❌ 由 cwd 决定 |
| `~/.omp/agent/extensions/*.ts` | macOS 开发机 `~/.omp/agent/extensions/` | ❌ 由 user.home 决定（仅在探测 RTK 时影响） |

**`PI_NOTIFICATIONS=off`**：关闭桌面通知。RPC 模式下通知通过 WebSocket 传递，不需要操作系统通知。

**API 端点 env**：根据 `api` 字段（`"anthropic-messages"` / `"openai-completions"`）选择设置 `ANTHROPIC_BASE_URL` 或 `OPENAI_BASE_URL`，保证 LLM 请求路由到正确的代理/网关。

---

## 3. 九大加载项详解

### 3.1 MCP 配置 (mcp.json)

| 属性 | 值 |
|------|-----|
| **文件位置** | `<agentDir>/mcp.json` |
| **备选位置**（未被 Java 配置覆盖时） | `<cwd>/.omp/mcp.json`、`<cwd>/mcp.json`、`<cwd>/.mcp.json`、`<cwd>/.cursor/mcp.json` 等（优先级递减） |
| **读取时机** | SDK 初始化时，`loadAllMCPConfigs()` |
| **加载策略** | Java 模板**每次 spawn 强覆盖**（`syncGlobalMcp`） |
| **是否用户私有** | ❌，全局统一 |

**内容示例**：

```json
{
  "mcpServers": {
    "codegraph": {
      "type": "stdio",
      "command": "codegraph",
      "args": ["serve", "--mcp"],
      "timeout": 30000
    }
  }
}
```

> 完整规范见 [`docs/omp-mcp-spec.md`](./omp-mcp-spec.md)

---

### 3.2 System Prompt 硬覆盖 (SYSTEM.md)

| 属性 | 值 |
|------|-----|
| **文件位置**（按优先级） | ① `<cwd>/.omp/SYSTEM.md`（项目级） ② `<agentDir>/SYSTEM.md`（用户级） |
| **读取时机** | `buildSessionOptions()` → `loadSystemPromptFiles()` |
| **加载策略** | ⚠️ **未管控** —— 人为放置即可生效 |
| **是否用户私有** | ✅ 理论上用户可自己放 |

**行为**：

```
SYSTEM.md 存在  → 整段替换 omp 的默认 system prompt
              → LLM 不再知道 omp 内置工具、思考机制等
SYSTEM.md 不存在 → 使用 omp 默认 system prompt（正确行为）
```

**当前状态：未管控的风险口**

```
⚠️ 如果有人（运维、用户自己、意外脚本）在 agentDir 或 workspace 的 .omp/ 下放了 SYSTEM.md，
   该用户的 omp 会话行为将完全偏离预期。
```

**建议**：
- 要么在 Java `spawn()` 中**主动清理** `<agentDir>/SYSTEM.md`（推荐）
- 要么改为全局模板管控（同 mcp.json 模式）

---

### 3.3 Append Prompt 追加 (APPEND_SYSTEM.md)

| 属性 | 值 |
|------|-----|
| **文件位置** | `<agentDir>/APPEND_SYSTEM.md`（主）→ `findConfigFile("APPEND_SYSTEM.md",{user:false})` 优先 |
| **读取时机** | `discoverAppendSystemPromptFile()` → 追加到 system prompt 末尾 |
| **加载策略** | Java 模板**每次 spawn 合并生成**（全局模板 + 用户 `.user.md`） |
| **是否用户私有** | ✅ 支持全局+用户私有合并 |

**合并规则**（详见 `oomp-mcp-spec.md` §3.2）：

| 全局模板 | 用户 `.user.md` | 生成的 `APPEND_SYSTEM.md` |
|---------|----------------|-------------------------|
| 有 | 有 | 全局内容 + `\n\n---\n\n` + 用户内容 |
| 有 | 无 | 仅全局内容 |
| 无 | 有 | 仅用户内容 |
| 无 | 无 | 文件被删除（无追加） |

---

### 3.4 项目上下文文件 (AGENTS.md)

| 属性 | 值 |
|------|-----|
| **文件位置** | 从 `<cwd>` 沿目录树向上递归查找所有 `AGENTS.md` |
| **读取时机** | `loadProjectContextFiles()` |
| **加载策略** | ❌ **被 `--no-rules` 禁用** |
| **是否用户私有** | 不适用（已禁用） |

**如果不禁用会怎样？**

```
AGENTS.md 沿目录向上递归搜索：
  <cwd>/AGENTS.md
  <cwd>/../AGENTS.md       ← 可能读到宿主机器上的 CLAUDE.md
  <cwd>/../../AGENTS.md    ← 安全边界破碎
  ... 直到 home 或 repoRoot
```

**所以 `--no-rules` 是必要的安全屏障**，不应解除。

---

### 3.5 Skills 技能包

| 属性 | 值 |
|------|-----|
| **文件位置** | `<cwd>/.omp/skills/`、`<cwd>/.skills/`、`<agentDir>/skills/` 等 |
| **读取时机** | 会话启动时发现并注册可调用 skill |
| **加载策略** | ❌ **被 `--no-skills` 禁用** |
| **是否用户私有** | 不适用（已禁用） |

---

### 3.6 Extensions 扩展 (RTK)

| 属性 | 值 |
|------|-----|
| **文件位置** | 双路径探测：`/root/.omp/agent/extensions/rtk.ts`（容器）→ `~/.omp/agent/extensions/rtk.ts`（开发机） |
| **加载方式** | Java `--extension <path>` **显式传入** |
| **加载策略** | ✅ 按需加载：文件存在时才传参 |
| **是否用户私有** | ❌ 全局统一 |

```typescript
// rtk.ts 作用：将每次 bash 命令改写为 rtk <cmd> 以节省 60-90% token
```

用户放在 `<agentDir>/extensions/` 下的其他 `.ts` **不会自动加载**——必须显式通过 `--extension` 指定。

---

### 3.7 Hooks 钩子 (rtk-proxy)

| 属性 | 值 |
|------|-----|
| **文件位置** | 双路径探测：`/root/.omp/hooks/rtk-proxy.ts`（容器）→ `~/.omp/hooks/rtk-proxy.ts`（开发机） |
| **加载方式** | Java `--hook <path>` **显式传入** |
| **加载策略** | ✅ 按需加载：文件存在时才传参 |
| **是否用户私有** | ❌ 全局统一 |

```typescript
// rtk-proxy.ts 作用：通过 ToolCallEventResult.updatedInput 修改工具调用参数
```

---

### 3.8 Memory 记忆后端 (mnemopi / hindsight)

| 属性 | 值 |
|------|-----|
| **配置方式** | omp setting `memory.backend` = `"mnemopi"` 或 `"hindsight"` |
| **默认值** | `"none"`（禁用） |
| **加载时机** | 会话启动时 `resolveMemoryBackend(settings)` → 生成记忆指令 → 追加到 append prompt |
| **当前状态** | ❌ **未启用**（默认 `none`） |

**mnemopi 启用后的效果**：

```
每次会话启动：
  → 从 <agentDir>/agent.db 读取以前的记忆摘要
  → 注入到 append prompt 末尾（"以下是您过去的记忆..."）
  → 会话结束时自动总结新记忆（消耗额外 LLM token）

记忆包含：用户声明过的偏好、项目背景、常用命令等
```

**建议**：目前不启用。原因：

1. Java 的 `--no-rules` 已经关闭了 AGENTS.md，记忆缺失"项目级"锚点
2. 多租户环境下记忆跨会话层级复杂，需额外设计隔离策略
3. 会显著增加 prompt token 消耗（每次会话多 500-2000 tokens）

如未来需要启用，应：

- 在 `application.yml` 的 `app.omp.settings` 中声明 `memory.backend: mnemopi`
- 在 `OmpProcessSpec.effectiveEnv()` 或 `OmpProperties` 中注入该 setting

---

### 3.9 Models 模型配置 (models.yml)

| 属性 | 值 |
|------|-----|
| **文件位置** | `<agentDir>/models.yml` |
| **读取时机** | SDK 初始化时 `ModelRegistry` 加载 |
| **加载策略** | Java `syncModelsYml()` 每次 spawn 增量合并 |
| **是否用户私有** | ❌ 全局共享（但写入每个用户的 agentDir） |

由 Java 端 `syncModelsYml()` 维护，写入内容：

```yaml
providers:
  deepseek:
    baseUrl: "https://api.deepseek.com/v1"
    models:
      deepseek-chat:
        context: 65536
        api: openai-completions
  anthropic:
    apiKey: "${ANTHROPIC_API_KEY}"
    models:
      claude-sonnet-4-5:
        context: 200000
        api: anthropic-messages
```

**同步策略**：如果 `models.yml` 已存在且目标 provider + model id 已有，**跳过**不覆盖。只做增量合并。

---

## 4. 加载优先级与去重规则

系统 prompt（SYSTEM.md）：

- 项目级 `<cwd>/.omp/SYSTEM.md` 优先
- 用户级 `<agentDir>/SYSTEM.md` 降级；
- 优先级：项目级 > 用户级

Append prompt（APPEND_SYSTEM.md）同样适用项目级优先的规则。

MCP 配置方面，高优先级的源不会合并相同名称的 server，只会根据已注册的 provider 按优先级覆盖。

Memory backend 是互斥的，只能选一个，切换需要根据 memory router 重新初始化。

对于 models.yml 只做增量合并，不会覆盖或去重，而是保留现有配置并追加新内容。

---

## 5. 会话启动时间线

```
T=0    Java 调用 OmpRpcClientFactory.spawn(userId, repoId, resumePath)
         ├─ WorkspaceService.userWorkspace()     → 创建/获取工作区目录
         ├─ WorkspaceService.userAgentDir()      → 创建/获取 agentDir  
         │
T=+2ms ├─ syncModelsYml()                       → 写/合并 models.yml
         ├─ syncGlobalMcp()                      → 强覆盖 mcp.json
         └─ syncAppendSystemMd()                 → 合并生成 APPEND_SYSTEM.md
         │
T=+3ms ├─ OmpProcessSpec.toArgv() 构建命令行
         ├─ OmpProcessSpec.effectiveEnv() 构建环境变量
         └─ ProcessBuilder.start() 启动 omp 子进程
         │
T=+5ms → omp main.ts:runRootCommand()
         ├─ 解析 Args (--mode, --no-rules, --extension, etc.)
         ├─ Capability 系统初始化
         ├─ Settings 加载
         │
T=+50ms → buildSessionOptions()
         ├─ 解析 SYSTEM.md        → 决定是否替换默认 prompt
         ├─ 解析 APPEND_SYSTEM.md  → 追加 prompt
         ├─ 应用 --no-rules        → 清空 rules
         ├─ 应用 --no-skills       → 清空 skills
         └─ 加载 Extensions/Hooks
         │
T=+100ms → SDK createAgentSession()
         ├─ loadAllMCPConfigs()   → 发现 MCP
         ├─ MCPManager.connectServers() → 并行连接
         │   └─ Fast gate 250ms → 部分连接成功即返回
         ├─ resolveMemoryBackend() → 如果启用则加载记忆
         ├─ 组装 System Prompt + Append Prompt + Memory Instructions
         ├─ 注册 MCP Tools 到 Tool Registry
         ├─ 发送初始消息给 LLM
         │
T=+500ms → 用户可交互（MCP 可能仍在后台连接中）
```

---

## 6. 配置速查表

### 6.1 哪些文件当前被全局管控

| 文件 | 管控方式 | 是否支持用户私有 | 文档章节 |
|------|---------|----------------|---------|
| `<agentDir>/mcp.json` | `syncGlobalMcp()` 每次 spawn 强覆盖 | ❌ | §3.1 |
| `<agentDir>/APPEND_SYSTEM.md` | `syncAppendSystemMd()` 每次 spawn 合并 | ✅ `.user.md` | §3.3 |
| `<agentDir>/models.yml` | `syncModelsYml()` 增量合并 | ❌ | §3.9 |

### 6.2 哪些文件当前未被管控（需要留意）

| 文件 | 风险 | 建议 |
|------|------|------|
| `<agentDir>/SYSTEM.md` | 可替换默认 prompt，行为失控 | 加清理或全局管控 |
| `<cwd>/.omp/SYSTEM.md` | 同上（优先级更高） | 加清理或全局管控 |

### 6.3 哪些机制被 Java 禁用

| 机制 | 禁用参数 | 是否建议恢复 |
|------|---------|------------|
| AGENTS.md / CLAUDE.md | `--no-rules` | ❌ 安全需要 |
| Skills | `--no-skills` | ❌ 暂不需要 |

### 6.4 哪些机制按需加载

| 机制 | 条件 | 是否启用 |
|------|------|---------|
| RTK Extension | `/root/.omp/agent/extensions/rtk.ts` 或 `~/.omp/agent/extensions/rtk.ts` 存在 | ✅ 部署时文件存在即启用 |
| rtk-proxy Hook | `/root/.omp/hooks/rtk-proxy.ts` 或 `~/.omp/hooks/rtk-proxy.ts` 存在 | ✅ 部署时文件存在即启用 |
| Memory backend | `memory.backend` setting 设为 `mnemopi` 或 `hindsight` | ❌ 默认 `none`，未启用 |

### 6.5 文件加载完整汇总

| 序号 | 文件/机制 | 读取方 | Java 是否管理 | 当前状态 |
|:----:|----------|--------|:------------:|:--------:|
| 1 | `mcp.json` | omp SDK | ✅ `syncGlobalMcp` 强覆盖 | ✅ 已管控 |
| 2 | `SYSTEM.md` | omp main.ts | ❌ 未管理 | ⚠️ 有风险 |
| 3 | `APPEND_SYSTEM.md` | omp main.ts | ✅ `syncAppendSystemMd` 合并 | ✅ 已管控 |
| 4 | `AGENTS.md` | omp SDK | `--no-rules` 禁用 | ❌ 已禁用 |
| 5 | Skills | omp SDK | `--no-skills` 禁用 | ❌ 已禁用 |
| 6 | Extensions | omp main.ts | `--extension` 显式加载 | ✅ 按需 |
| 7 | Hooks | omp main.ts | `--hook` 显式加载 | ✅ 按需 |
| 8 | Memory backend | omp SDK | ❌ setting 控制 | ❌ 未启用 |
| 9 | `models.yml` | omp SDK | ✅ `syncModelsYml` 合并 | ✅ 已管控 |

---

## 7. 附录：部署对照表

### 7.1 Java 模板源文件路径

| 模板 | 开发机（`application.yml`） | UAT（`application-uat.yml`） | 生产120（`application-120.yml`） |
|------|---------------------------|-----------------------------|-------------------------------|
| mcp 模板 | `/tmp/omp/mcp.json` | `/etc/omp/mcp.json` | `/etc/omp/mcp.json` |
| append prompt 模板 | `/tmp/omp/APPEND_SYSTEM.md` | `/etc/omp/APPEND_SYSTEM.md` | `/etc/omp/APPEND_SYSTEM.md` |
| 环境变量覆盖 | `OMP_MCP_TEMPLATE` | `OMP_MCP_TEMPLATE` | 硬编码不可覆盖 |
| 环境变量覆盖 | `OMP_APPEND_SYSTEM_TEMPLATE` | `OMP_APPEND_SYSTEM_TEMPLATE` | 硬编码不可覆盖 |

### 7.2 用户私有文件

| 文件 | 作用 | 创建方式 |
|------|------|---------|
| `<agentDir>/APPEND_SYSTEM.user.md` | 用户自定义追加 prompt | 运维手动放或客服后台 |
| `<agentDir>/SYSTEM.md` | 完全替换默认 prompt（当前未管控） | 任何人放即可生效（⚠️） |

### 7.3 关键配置项

```yaml
# application.yml 中与加载相关的配置项
app:
  omp:
    mcp-template-path: ${OMP_MCP_TEMPLATE:/tmp/omp/mcp.json}
    append-system-template-path: ${OMP_APPEND_SYSTEM_TEMPLATE:/tmp/omp/APPEND_SYSTEM.md}
    # models.yml 由 syncModelsYml 自动维护，无单独配置项
    # --no-rules / --no-skills 硬编码在 OmpProcessSpec.java 中，不可配
    # RTK extension/hook 硬编码在 OmpProcessSpec.java 中，不可配
```

---

**文档维护者**：后端组
**最后更新**：2026-06-23
**关联文档**：[`docs/omp-mcp-spec.md`](./omp-mcp-spec.md)（MCP 配置使用规范）