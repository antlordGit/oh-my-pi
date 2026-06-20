# OMP Web 平台实现命令清单

> 防止重复实现：每次实现新命令前，先查这里。

## 平台命令清单

| 命令 | 来源 | 后端 | 前端 | 备注 |
|---|---|---|---|---|
| `/tree` | 内建斜杠 | `GET /api/sessions/{id}/messages` | ChatView send() 拦截 + tree-panel | 完整复刻 CLI：↑↓选 + ↵跳到 entry（`branch(entryId)` RPC） |
| `/compact` | 内建斜杠 | `POST /api/sessions/{id}/compact` | AdminView 等可加按钮 | RPC `compact` 直接调 |
| `/fork` | 内建斜杠 | `POST /api/sessions/{id}/branch` | — | omp `branch(entryId)` |
| `/switch` | 内建斜杠 | `POST /api/sessions/{id}/switch` | — | omp `switch_session(sessionPath)` |
| `/model` | 内建斜杠 | `GET /admin/config` 拿 `model.active` | — | 后端已有 `set_model` RPC，但前端没接 |
| `/new` | 内建斜杠 | `POST /api/sessions/{id}/new-session`（RPC `new_session`） | ChatView composer-toolbar「新对话」按钮 + doNew() | **不走 prompt RPC**：`builtin-registry.ts` 只注册了 `handleTui`，没有 `handle`，所以 web 端经 `prompt()` 发 `/new` 文本不会被 RPC 层斜杠拦截，会被 LLM 当普通文本；正确做法是直接调 `new_session` RPC，对应 coding-agent 端 `session.newSession()` → `sessionManager.newSession()`，与 CLI `/new` 行为完全一致（创建新 session 文件、`sessionFile` 切换、返回 `{cancelled}`） |
| `/rewind` | 内建斜杠 (LLM 工具) | `POST /api/sessions/{id}/prompt`（作为文本） | ChatView composer-toolbar「回退」按钮 + doRewind() | 例外：不走 RPC，而是把 `/rewind` 当文本 prompt 喂给 LLM；后端 coding-agent 端识别为 `RewindTool`（见 `packages/coding-agent/src/tools/checkpoint.ts`，需 `checkpoint.enabled=true`）；按钮点击效果等同在输入框输入 `/rewind` 并回车，会先推入操作员轮次再发送 |
| `/settings` `/setup` `/plan` `/goal` `/loop` `/share` `/export` `/dump` `/join` `/leave` `/browser` | 内建斜杠 | — | — | 未实现，RPC 模式下大多走 `handle()` 路径，可调起 |

## 已有 RPC 命令（已封 Java 端点，可直接用）

| RpcCommand | Java 入口 | REST 端点 | 前端是否接入 |
|---|---|---|---|
| `prompt` | `RpcCommands.prompt(msg)` | `POST /api/sessions/{id}/prompt` | ✅ `prompt(sessionId, msg)` |
| `steer` | `RpcCommands.steer(msg)` | `POST /api/sessions/{id}/steer` | ❌ |
| `follow_up` | `RpcCommands.followUp(msg)` | `POST /api/sessions/{id}/follow-up` | ❌ |
| `abort` | `RpcCommands.abort()` | `POST /api/sessions/{id}/abort` | ✅ 中断按钮 |
| `abort_and_prompt` | `RpcCommands.abortAndPrompt(msg)` | — | ❌ 可作"中断并改问"按钮 |
| `new_session` | `RpcCommands.newSession()` / `newSessionFromParent` | `POST /api/sessions/{id}/new-session` | ❌ |
| `get_state` | `RpcCommands.getState()` | `GET /api/sessions/{id}/state` | ✅ 加载时 |
| `get_available_commands` | — | — | ❌ |
| `set_model` | — | — | ❌ |
| `set_thinking_level` | — | — | ❌ |
| `set_todos` | — | — | ❌ |
| `get_messages` | `RpcCommands.getMessages()` | `GET /api/sessions/{id}/messages` | ✅ `getMessages()`，/tree 用它 |
| `get_branch_messages` | `RpcCommands.getBranchMessages()` | — | ❌ 可作"切分支看消息"按钮 |
| `branch` | `RpcCommands.branch(entryId)` | `POST /api/sessions/{id}/branch` | ✅ /tree 回车跳转 |
| `switch_session` | `RpcCommands.switchSession(path)` | `POST /api/sessions/{id}/switch` | ❌ |
| `compact` | `RpcCommands.compact()` / `compact(instr)` | `POST /api/sessions/{id}/compact` | ❌ |
| `bash` | — | — | ❌ |
| `export_html` | — | — | ❌ |
| `get_session_stats` | — | — | ❌ |

## 已实现非斜杠功能

| 功能 | 后端 | 前端 | 备注 |
|---|---|---|---|
| 登录 | `POST /api/auth/login` | LoginView | JWT HS256 |
| 注册仓库 | `POST /api/repos` | SessionListView 表单 | 每个 repo 走 git init |
| 新建会话 | `POST /api/sessions` | SessionListView | 受 `per-user-session-limit` 限制（100） |
| 列出会话 | `GET /api/sessions` | SessionListView | 按 lastActiveAt desc |
| 归档会话 | `POST /api/sessions/{id}/archive` | 列表「归档」按钮 | status → archived + 立即 evict 进程 |
| **恢复会话** | `POST /api/sessions/{id}/unarchive` | 列表「恢复」按钮 | status → active + 计数校验 |
| 发送 prompt | `POST /api/sessions/{id}/prompt` | ChatView send() | 归档态自动 promote |
| 中断 | `POST /api/sessions/{id}/abort` | ChatView 中断按钮 | 发 `abort` RPC |
| 回退 | `POST /api/sessions/{id}/prompt`（发 `/rewind` 文本） | ChatView composer-toolbar「回退」按钮 | 等同操作员手动输入 `/rewind` 并回车；只在 `turnLog` 非空时显示（无历史可回退）；`sending`/`isStreaming` 时禁用；需后端 `checkpoint.enabled=true` |
| 新对话 | `POST /api/sessions/{id}/new-session`（RPC `new_session`） | ChatView composer-toolbar「新对话」按钮 | 调 `new_session` RPC，不发 prompt；coding-agent 端触发 `session.newSession()` 创建新 session 文件 + `chatContainer.clear()`；前端 `refresh()` 重新加载消息（旧的根消息会丢失，因为新 session 文件独立） |
| 消息复制 | `navigator.clipboard.writeText`（前端纯客户端，无后端） | MessageBubble 右上角按钮 | hover 消息框显示「⧉ 复制」按钮，点击复制 markdown 原始文本（不经 v-html 渲染，不带折叠后缀）；复制成功显示「✓ 已复制」1.6s；HTTP/老浏览器 fallback 到 `document.execCommand('copy')`；按钮 `@click.stop` 不触发消息框自身的展开/折叠 |
| WS 实时事件 | `WS /ws/sessions/{id}?token=` | ChatView connectWs | token 走 query auth |
| Admin 配置 CRUD | `/admin/config` GET/PUT/DELETE | AdminView | 支持编辑/删除/新增 |
| 审计查询 | `/admin/audit/{prompts,tools}` | AdminView | 最近 50 条 |
| Session 终止（admin） | `POST /admin/sessions/{id}/kill` | AdminView | pool.evict |

## 未实现但有 RPC 基础的功能

| 功能 | 思路 |
|---|---|
| `/compact` 按钮 | 加一个紧凑按钮调 `compact()`，前显示当前 token 数 |
| `/model` 切换 | AdminView 加下拉，调 `set_model(provider, modelId)` |
| 多分支切换 UI | /tree 已能跳 entry，再加一个"分支列表"页面用 `get_branch_messages` |
| 文件树展示 | `/api/repos/{id}/files` 已存在，ChatView 加 `/files` 命令 |
| Git diff 展示 | `/api/repos/{id}/diff?refA&refB` 已存在，可加 `/diff <refA> <refB>` 命令 |
| Token 用量显示 | `get_state` 返回的 model.cost + usage 字段，渲染到 topbar |
| TODO 面板 | `set_todos` RPC + 监听 session_info_update |

## 设计原则

1. **优先用现有 REST**：每个新功能先看后端接口有没有，没有再考虑加 RPC 命令
2. **斜杠命令优先拦截**：在 ChatView.send() 顶部加 `if (text === '/xxx')` 分支，命中就走专用渲染器（不走 LLM），不命中回退到 prompt
3. **/tree 类"读"操作不消耗 token**：直接读 RPC 状态渲染，零 LLM 成本
4. **/compact 类"写"操作走 RPC**：用对应 RPC 命令，不当 prompt 发
5. **未知斜杠回退到 LLM**：和 CLI 一致——omp 不认识的就当普通文本喂给 AI

## 通用 RPC 列表（omp RpcCommandType 全部 41 个）

详见 `backend/src/main/java/com/yourorg/omp/rpc/RpcCommandType.java`。新增时先查该文件判断是否已定义。
