# Oh My Pi (omp) — 项目指南

> 一个将 IDE 内建的 AI 编程代理。Fork 自 [Pi](https://github.com/badlogic/pi-mono)，持续扩展。

## 快速概览

| 维度 | 说明 |
|------|------|
| **版本** | 15.12.6 |
| **核心语言** | TypeScript (Bun) + Rust + Python |
| **包管理** | Bun workspaces (monorepo) + Cargo |
| **主要入口** | `packages/coding-agent/src/cli.ts` |
| **文档** | [omp.sh](https://omp.sh) · `README.md` · `docs/` (70+ 篇内部架构文档) |

## 项目架构

这是一个**多语言 monorepo**，包含三个主要层：

```
oh-my-pi-main/
├── crates/              # Rust 核心 (~55K LOC) — 文本搜索、grep、Shell/PTY、tree-sitter 代码智能、N-API 绑定
│   ├── pi-ast           # AST 表示
│   ├── pi-iso           # 隔离/沙箱
│   ├── pi-natives       # N-API 绑定（Bun 调用 Rust 性能操作）
│   ├── pi-shell         # Shell/PTY/进程管理
│   ├── brush-core-vendored     # vendored bash 解析器
│   └── brush-builtins-vendored # bash 内置命令
├── packages/            # 16 个 TypeScript/Bun 包 (@oh-my-pi/pi-*)
│   ├── coding-agent/    # ★ 主 CLI 应用（主要工作区）
│   ├── tui/             # 终端 UI（增量渲染）
│   ├── ai/              # 多提供者 LLM 客户端（流式）
│   ├── agent/           # Agent 运行时（工具调用、状态管理）
│   ├── catalog/         # 模型目录（bundled models.json）
│   ├── natives/         # TS 绑定 → Rust (N-API)
│   ├── wire/            # Wire 协议 / IPC
│   ├── utils/           # 共享工具（logger、streams、临时文件、环境）
│   ├── stats/           # 本地可观测仪表板 (`omp stats`)
│   ├── hashline/        # Hashline 编辑
│   ├── mnemopi/         # 记忆系统
│   ├── snapcompact/     # 上下文压缩
│   └── ...
├── python/
│   ├── omp-rpc/         # RPC 协议客户端（TS ↔ Rust 通信）
│   └── robomp/          # Worker 后端（FastAPI + 沙箱 + GitHub 集成）
├── scripts/             # 37 个 CI/发布/分析脚本
├── docs/                # 70+ 篇设计/内部架构文档
└── types/               # 共享 TypeScript 类型资产
```

## 开发工作流

### 环境要求

- **Bun** ≥ 1.3.14（主运行时）
- **Rust** 稳定版（edition 2024）
- **Python** 3.10+（仅 robomp worker）

### 常用命令

```bash
# 安装依赖
bun install

# TypeScript 类型检查（不用 tsc！）
bun check

# 运行测试
bun test

# 构建 Rust 原生模块
cargo build --workspace

# 运行 coding-agent 的测试
bun --cwd=packages/coding-agent test

# 生成模型目录
bun --cwd=packages/catalog run generate-models

# 发布
bun run release
```

## 核心约定

> 完整规则见 [`AGENTS.md`](./AGENTS.md)。以下是高频要点。

### 1. Bun > Node

优先使用 Bun API，仅在 Bun 未覆盖时回退到 `node:*`。

| 操作 | 使用 | 避免 |
|------|------|------|
| 文件读写 | `Bun.file()`, `Bun.write()` | `readFileSync`, `writeFileSync` |
| 进程生成 | `` $`cmd` ``, `Bun.spawn()` | `child_process` |
| 休眠 | `Bun.sleep(ms)` | `new Promise(r => setTimeout(r, ms))` |
| SQLite | `bun:sqlite` | `better-sqlite3` |
| 哈希 | `Bun.hash()` | `node:crypto` |
| 路径解析 | `import.meta.dir` | `fileURLToPath` |

永远不要用 shell 命令替代有对应 API 的操作（如 `mkdir -p` → 直接写文件，Bun 自动创建目录）。

### 2. 禁止事项

- **No `any`**：除非绝对必要
- **No `ReturnType<>`**：使用实际类型名
- **No 动态导入**：始终顶层 `import`，不用 `await import()` 或 `import("pkg").Type`
- **No `console.log`**（coding-agent 包）：会破坏 TUI 渲染，使用 `@oh-my-pi/pi-utils` 的 `logger`
- **Never edit `packages/catalog/src/models.json`**：由 `generate-models.ts` 自动生成，手动修改会被覆盖

### 3. 代码风格

- **Barrel exports**：优先 `export * from "./module"`，包括类型导出
- **类私有字段**：使用 ES `#private`，不用 `private`/`protected` 关键字（构造函数参数属性除外）
- **Promise 构造**：使用 `Promise.withResolvers()` 而非 `new Promise((resolve, reject) => ...)`
- **Node 模块导入**：始终用命名空间导入 `import * as fs from "node:fs/promises"`

### 4. Prompt 管理

所有 prompt 文本存储在 `.md` 文件中，禁止在代码中拼接 prompt。使用 Handlebars 处理动态内容：

```typescript
import content from "./prompt.md" with { type: "text" };
```

### 5. Worker 脚本

Workers 通过重入 CLI 入口点启动，不产生独立的 worker 入口模块。新增 worker 种类必须：
- 在 `cli.ts` 的分发表中添加选择器
- 保持回退分支完整

### 6. 日志

```typescript
import { logger } from "@oh-my-pi/pi-utils";

logger.error("MCP request failed", { url, method });
logger.warn("Theme file invalid, using fallback", { path });
logger.debug("LSP fallback triggered", { reason });
```

日志输出到 `~/.omp/logs/omp.YYYY-MM-DD.log`，自动轮转。

### 7. TUI 渲染消毒

所有在工具渲染器中显示的文本必须消毒：
- Tab → 空格（`replaceTabs()`）
- 长行截断（`truncateToWidth()` / `ui.truncate()`）
- 路径缩短（`shortenPath()`，家目录替换为 `~`）
- 使用 `PREVIEW_LIMITS` 常量，禁止硬编码数字

## 包导入约定

```typescript
// 目录值从 @oh-my-pi/pi-catalog/<module> 导入，不用 pi-ai
import { models } from "@oh-my-pi/pi-catalog/models";

// pi-ai 只导出类型（Model, Api, ThinkingConfig, Effort 等）
import type { Model, Effort } from "@oh-my-pi/pi-ai";
```

## 测试原则

- 测试系统暴露的**契约**（行为、输出形状、状态转换），而非内部实现细节
- 无占位测试（`expect(true).toBe(true)`、空字符串检查等）
- 不使用 `mock.module()`（Bun 的已知问题），用 `vi.spyOn` + `vi.restoreAllMocks()` 替代
- 避免全局副作用：不污染 `Bun.*`、`process.env`、`process.platform`
- 不做跨层重复测试：集成测试已覆盖的行为，不再写窄化单元测试
- 烟测仅用于捕获窄范围故障模式，"包能启动"不是足够理由

## ChangeLog

每个包维护自己的 `CHANGELOG.md`，格式：

```markdown
## [Unreleased]
### Breaking Changes
### Added
### Changed
### Fixed
### Removed
```

内部引用：`Fixed foo bar ([#123](链接))`
外部贡献：`Added feature X ([#456](链接) by [@username](链接))`

## 目录快速索引

| 路径 | 内容 |
|------|------|
| `docs/tools/` | 28 篇工具文档 |
| `docs/skills/` | Skills 编写指南 |
| `scripts/install-tests/` | 安装测试脚本 |
| `.nezha/` | 部署配置 |
| `.omp/` | 运行时配置 |
