# DEPLOY.md 变更日志

> 本文档记录 `docs/DEPLOY.md` 中涉及**镜像内容、卷结构、运维流程**的实质性变更。
> 文字/格式微调不进此处。

---

## 2026-06-23 — 会话 spawn 触发 CodeGraph 索引刷新

### 动机

entrypoint.sh 只在容器冷启时 `codegraph init` 一次，运行中新增工作区 / git pull 等外部改动
不会自动刷新索引（CodeGraph watcher 在容器重启后失联期间漏改动）。

### 改动

- `OmpRpcClientFactory.spawn()` 新增 `ensureCodeGraphIndexed(workspace)` 调用
- 异步 daemon 线程，30s throttle（看 `.codegraph/codegraph.db` mtime），失败 log warn 不阻塞 omp
- DEPLOY.md §20.3 末尾追加"会话级触发"小节

| 工作区状态 | 触发命令 | 频率 |
|---|---|---|
| 无 `.codegraph/` | `codegraph init -i` | 立即 |
| 有 `.codegraph/`，db mtime ≥ 30s | `codegraph index --quiet` | 立即 |
| 有 `.codegraph/`，db mtime < 30s | 跳过 | 30s throttle |

### 验证

```bash
# 新工作区场景
rm -rf /tmp/omp/workspaces/<uid>/<repo>/.codegraph
# 前端发起会话；后端日志：
# [omp.codegraph] init OK workspace=/tmp/omp/workspaces/<uid>/<repo>

# throttle 验证：立即再发会话，应跳过（无日志）
# 30s 后再发：[omp.codegraph] index OK workspace=...

# 失败兜底
docker exec omp-app mv /usr/local/bin/codegraph /usr/local/bin/codegraph.bak
# 发起会话——仍正常启动，日志有 [omp.codegraph] cannot spawn ...
```

---

## 2026-06-23 — MCP 配置统一到共享路径（per-user 拷贝 → agentRoot symlink）

### 动机

CodeGraph MCP 配置从 per-user 拷贝改为共享 symlink，避免每次更新配置都要清理所有用户的
`agentDir/mcp.json`。改后在 `agentRoot/mcp.json`（如 `/data/omp/agent/mcp.json`）改一次，
所有用户即时生效。

### 改动清单

| 类型 | 内容 | 位置 |
|------|------|------|
| Java 改动 | `syncGlobalMcp` 方法删除，替换为 `ensureGlobalMcpSymlink`（检查 `agentRoot/mcp.json` 存在则建 `../mcp.json` symlink） | `OmpRpcClientFactory.java` |
| Java 配置 | 删除 `app.omp.mcp-template-path` 字段和 yml 配置 | `OmpProperties.java`、`application*.yml` × 3 |
| 镜像文件 | **`/etc/omp/mcp.json`** 统一命名（不再用 `codegraph-mcp.json`） | DEPLOY.md §18.4 |
| entrypoint | 新加 `cp /etc/omp/mcp.json /data/omp/agent/mcp.json` | DEPLOY.md §18.4 |
| 目录树 | `└── codegraph-mcp.json` → `└── mcp.json` | DEPLOY.md §18.4 |

### 部署验证

```bash
# 共享 mcp.json 存在
ssh root@10.126.2.120 'docker exec omp-app ls -la /data/omp/agent/mcp.json'

# 每个用户 agentDir 下是 symlink
ssh root@10.126.2.120 'docker exec omp-app ls -la /data/omp/agent/<username>/mcp.json'
# 预期: mcp.json -> ../mcp.json

# 更新 MCP 配置（一行命令，即时生效）
ssh root@10.126.2.120 'docker cp ... omp-app:/data/omp/agent/mcp.json'
```

### 历史记录 — 改动前描述（存档）

> **同步机制**（改动前，见 DEPLOY-CHANGELOG.md 2026-06-23 初始 L2 集成记录）：
> Java 端 OmpRpcClientFactory.spawn() 在每次创建会话时调用 `syncGlobalMcp(mcpTemplatePath, agentDir/mcp.json)`，
> 把模板 `/etc/omp/mcp.json` → 强覆盖到每个用户的 `agentDir/mcp.json`。用户级私有 MCP 不支持。

---

## 2026-06-23 — CodeGraph 安装方式：tarball COPY → npm install -g

### 动机

第一次实测部署到 UAT 服务器（10.126.2.120）时，CodeGraph 上游的 `install.sh` 在国内连 GitHub raw / releases
失败（SSL_read EOF、gh-proxy 522/524）。临时改用 **本地预下载 tarball + COPY** 解决；但每次升级要重 scp 50 MB，
不够好。

切到 **npm 全局安装** 走 `npmmirror.com` 国内镜像，升级一行 ARG 即可，省掉 scp 步骤。

### 改动清单

| 类型 | 内容 | 位置 |
|---|---|---|
| Dockerfile 副本 | 删除 `COPY codegraph-linux-x64.tar.gz` + `tar/ln -s` 段（在 Node 22 之前） | DEPLOY.md §18.4 |
| Dockerfile 副本 | Node 22 装好后追加 `ARG CODEGRAPH_VERSION=1.0.1` + `npm config set registry npmmirror` + `npm i -g` | DEPLOY.md §18.4 |
| 目录树 | `└── （CodeGraph 由 Dockerfile 内 curl \| sh 安装...）` → `└── （CodeGraph 由 Dockerfile 内 npm install -g 安装...）` | DEPLOY.md §18.4 |
| §20.5 故障排查 | 升级命令从 `curl install.sh` 改为 `npm i -g @latest`；镜像缺命令的修复从 tarball 改为 `npm i -g` | DEPLOY.md §20.5 |
| §20.7 卸载 | Dockerfile 删除目标从 `RUN curl ... install.sh` 改为 npm 段 | DEPLOY.md §20.7 |
| §20.8.1 重写 | 从「**tarball 踩坑修正**」改为「**3 个方案演进 A→B→C**」，C 是 npm（当前生效） | DEPLOY.md §20.8.1 |
| 文字校准 | "通过 `curl \| sh` 安装" → "通过 `npm install -g` 安装"；二进制位置 `/root/.local/bin/` → `/usr/local/bin/` | DEPLOY.md §20.8 开头 |

### 服务器侧需要的清理（可选）

新部署 / 重建镜像后，旧的 tarball 已不再需要：
```bash
ssh root@10.126.2.120 'rm -f /home/omp/docker-build/codegraph-linux-x64.tar.gz'
```
不删也无害（Dockerfile 不再 COPY 它），只是占 50 MB 磁盘。

### 升级流程对比

| 操作 | 旧方案（tarball COPY） | 新方案（npm 全局）|
|---|---|---|
| 升级到 v1.0.2 | 开发机 `curl -o ... v1.0.2/codegraph-linux-x64.tar.gz` → `scp` 到服务器 → 改 Dockerfile 中的 tarball 引用 → 重建 | 服务器 `sed -i 's\|CODEGRAPH_VERSION=1.0.1\|1.0.2\|' Dockerfile` → 重建 |
| 临时验证新版 | 不支持（必须重建镜像）| `docker exec omp-app npm i -g @colbymchenry/codegraph@latest` |
| 回滚到 v1.0.0 | 重新 curl + scp tarball | 改 ARG 重建即可 |

### 验证

新部署镜像构建后：
```bash
ssh root@10.126.2.120 'docker exec omp-app codegraph --version'
# 预期：CodeGraph 1.0.1
ssh root@10.126.2.120 'docker exec omp-app which codegraph'
# 预期：/usr/local/bin/codegraph
ssh root@10.126.2.120 'docker exec omp-app ls /usr/local/lib/node_modules/@colbymchenry/codegraph'
# 预期：dist/ npm-shim.js npm-sdk.js package.json
```

### 回滚

如果 npmmirror 出问题，临时回到方案 B（tarball COPY）：
1. 把方案 B 的 Dockerfile 段从 §20.8.1 表格复制回 Dockerfile
2. 重新 `curl -o codegraph-linux-x64.tar.gz ...` + `scp` 到服务器
3. 重建镜像

---

## 2026-06-23 — L2 部署实测：镜像构建踩坑修正 + 本地验证记录

### 动机

L2 集成（CodeGraph MCP）实际部署到 UAT 服务器时，发现 §18.4 Dockerfile 中多个公网下载地址在国内不稳定/失败，
镜像无法构建。**这次的改动是把实测可用的安装路径固化到文档里**，让下一次新部署不再踩坑。

### 改动清单

| 类型 | 内容 | 位置 |
|---|---|---|
| 新增章节 | **§20.8.1 镜像构建踩坑**（CodeGraph tarball 路径 / Maven 直连 archive / Go 走 aliyun）| DEPLOY.md §20.8.1 |
| 新增章节 | **§20.9 本地验证记录**（开发机端到端走通的证据 + stdio 握手测试命令）| DEPLOY.md §20.9 |
| 故障排查 | §20.5 表追加 4 行：agent 不调 codegraph（两种情形）+ 第一次 spawn 时 MCP 卡握手是预期 + 镜像没 codegraph 命令的正确修法 | DEPLOY.md §20.5 |

### 实测踩坑要点（已固化到 §20.8.1）

| 踩坑点 | 原版（不可用） | 实测可用方案 |
|---|---|---|
| CodeGraph 安装 | `curl install.sh \| sh` 直连 GitHub raw → 国内 SSL_read EOF；`gh-proxy` → 522/524 | **本地预下载 tarball 后 COPY 进镜像**（约 50 MB） |
| CodeGraph 解压路径 | `tar -xzf -C /usr/local/bin/ codegraph` ← 错（包内不是单个二进制） | `tar -xzf -C /usr/local/` 后 `ln -s codegraph-linux-x64/bin/codegraph /usr/local/bin/codegraph`（带 lib 运行时） |
| Maven tarball | `gh-proxy.com/archive.apache.org/...` → 524 | 直连 `archive.apache.org`（apache 国内可访问） |
| Go tarball | `gh-proxy.com/go.dev/...` 或 `go.dev` 直连 → 都不稳 | `mirrors.aliyun.com/golang/...` |

### 本地验证证据

| 检查项 | 实际输出 |
|---|---|
| `omp-<session-id>.err.log` | `Connecting to MCP servers: codegraph, ...` — codegraph 已在 MCP server 列表 |
| `ps aux \| grep "codegraph serve --mcp"` | 看到 N 个 node 子进程（每个 omp session 一个） |
| stdio 协议握手测试 | `codegraph serve --mcp` 接收 `tools/list` 后返回 9 个 `codegraph_*` 工具 |

### 受影响范围

- **不改任何 Java/TS 代码** —— Java 改动已在前一轮（OmpRpcClientFactory.copyTemplateIfAbsent）完成且本地验证通过
- **§18.4 Dockerfile 副本**已根据 §20.8.1 修正过，**直接抄即可**
- 服务器需 scp 一次 `codegraph-linux-x64.tar.gz`（约 50 MB），每次升级 CodeGraph 版本时重 scp

---

## 2026-06-23 — L2 集成：CodeGraph MCP server（omp agent 自动调 codegraph_explore）

### 动机

§20.1–20.7 装了 CodeGraph **CLI**，运维人员可手工 `codegraph explore`，但 **omp agent 看不到**。
让 omp agent 在每次会话里自动暴露 `mcp__codegraph__codegraph_explore` 工具——LLM 直接调，不再依赖
LLM 在 shell tool 里手工拼 `codegraph` 命令。

### 改动清单

| 类型 | 内容 | 位置 |
|---|---|---|
| 新增镜像文件 | **`/etc/omp/codegraph-mcp.json`**（CodeGraph stdio MCP 配置） | DEPLOY.md §18.4 Dockerfile 行 `COPY codegraph-mcp.json ...` |
| 新增镜像文件 | **`/etc/omp/APPEND_SYSTEM.md`**（引导 LLM 优先用 codegraph_explore） | DEPLOY.md §18.4 Dockerfile 行 `COPY APPEND_SYSTEM.md ...` |
| Java 改动 | `OmpRpcClientFactory.spawn()` 加 2 行 `copyTemplateIfAbsent` 调用 + 1 个 ~12 行私有静态方法 | `backend/src/main/java/com/yourorg/omp/rpc/OmpRpcClientFactory.java` |
| 新增章节 | **§20.8 L2 集成：CodeGraph MCP server**（含模板内容 + 热更新 + 验证 + 卸载） | DEPLOY.md §20.8 |

### 受影响范围

**已运行实例**：
- 不需要重建容器（卷挂载未变）；但**必须重建镜像**（COPY 两个新模板 + 新 jar）
- 已有用户的 agentDir 下首次没有 mcp.json/APPEND_SYSTEM.md → Java 端在下次会话 spawn 时自动 cp

**进程行为变化**：
- 每个 omp session 启动时多 spawn 一个 `codegraph serve --mcp` stdio 子进程
- 启动延迟 +~250-500ms（MCP discovery 握手，超时走 fast startup gate 降级）
- agent 工具集多出 6 个 `mcp__codegraph__*` 工具

**失败影响**：
- CodeGraph MCP 启动失败 → DeferredMCPTool 占位 + 错误记录，**omp 正常启动**（详见 `packages/coding-agent/src/mcp/manager.ts:80-81` 熔断逻辑）
- 模板缺失（开发机本地） → `copyTemplateIfAbsent` 静默跳过，本机 omp 进程照常启动

### 回滚方法

**全卸（含 CLI 和 MCP）**：参考 §20.7 + §20.8 末尾"与 §20.7 卸载的关系"。

**只卸 MCP（保留 CLI）**：
1. Dockerfile 删两行 `COPY codegraph-mcp.json ...` + `COPY APPEND_SYSTEM.md ...`
2. `OmpRpcClientFactory.java` 删两行 `copyTemplateIfAbsent(...)` 调用
3. 清理已有用户配置：
   ```bash
   ssh root@10.126.2.120 'docker exec omp-app find /data/omp/agent -maxdepth 2 \
     \( -name mcp.json -o -name APPEND_SYSTEM.md \) -delete'
   ```

### 验证清单

按 DEPLOY.md **§20.8 验证段** 走 5 步：
- 1) `codegraph serve --help` 列出 `--mcp` 选项
- 2) 新会话 spawn 后 agentDir 下应有 mcp.json + APPEND_SYSTEM.md
- 3) `ps -ef` 看到 `codegraph serve` 子进程
- 4) `docker logs omp-app | grep -i 'mcp\|codegraph'` 有 MCP discovery 日志
- 5) 让 agent 跑次"理解某模块"对话，前端面板/后端日志看到 `tool_call name=mcp__codegraph__codegraph_explore`

### 相关文档

- DEPLOY.md §20.8：本次新增完整说明（模板 + 验证 + 卸载）
- DEPLOY.md §20.1–20.7：CodeGraph CLI 使用说明（L1，之前已加）
- `packages/coding-agent/src/mcp/`：omp MCP 客户端实现（20 个文件，完整支持）
- `docs/mcp-config.md`：omp MCP 配置 schema 与规范

---

## 2026-06-23 — 加 Maven 3.9.9 + CodeGraph + 新增 omp-maven 卷

### 动机

1. **容器内直接构建后端**：原流程只能"开发机 `mvn package` → rsync jar → 重建镜像"，不能在 `omp-app` 容器内直接重打 jar；缺 Maven 也无法在容器内跑 `mvn dependency:tree` / `mvn test` 等诊断命令。
2. **持久化 Maven 本地仓库**：避免每次重建镜像重新下载 Spring 全家桶（~300–500 MB）。
3. **CodeGraph 集成**：omp agent 在容器内执行代码任务时，需要代码智能图（精准的符号/调用边/依赖）替代慢速 grep/glob/Read，提升会话质量与 token 效率。

### 改动清单

| 类型 | 内容 | 位置 |
|---|---|---|
| 新增镜像工具 | **Maven 3.9.9** tarball 安装（`/usr/local/maven`，~15 MB） | DEPLOY.md §18.4 Dockerfile 行 790–798 |
| 新增镜像工具 | **CodeGraph** `curl \| sh` 安装（`/root/.local/bin/codegraph`，~90 MB） | DEPLOY.md §18.4 Dockerfile 行 808–813 |
| 新增镜像文件 | **`/etc/omp/maven-settings.xml`**（脱敏，路径替换为容器内路径） | DEPLOY.md §18.4 Dockerfile 行 803–805 |
| 新增 entrypoint 步骤 | mkdir `/data/omp/maven-repository` | DEPLOY.md §18.4 entrypoint.sh 行 918 |
| 新增 entrypoint 步骤 | 部署 `/root/.m2/settings.xml` + mvn --version 自检 | DEPLOY.md §18.4 entrypoint.sh 行 920–923 |
| 新增 entrypoint 步骤 | CodeGraph --version 自检 + **后台异步 init 已有工作区** | DEPLOY.md §18.4 entrypoint.sh 行 934–948 |
| 新增卷 | **`omp-maven`**（持久化 `/data/omp/maven-repository`） | DEPLOY.md §18.3 行 741、§18.4 行 1067 |
| 新增 docker run 参数 | `-v omp-maven:/data/omp/maven-repository` | DEPLOY.md §18.4 行 1079、§19.2 行 1157 |
| 新增章节 | **§19.6 容器内 Maven / CodeGraph 自检流程** | DEPLOY.md §19.6 行 1276 |
| 新增章节 | **§19.7 服务器端 maven-settings.xml 模板**（含完整 XML + 热更新方法） | DEPLOY.md §19.7 行 1308 |
| 新增章节 | **§20 CodeGraph 使用说明**（是什么、怎么用、生命周期、命令速查、故障排查） | DEPLOY.md §20（追加在末尾） |

### 受影响范围

**已运行实例**：
- `omp-app` 容器**必须重建**才能让 `omp-maven` 卷挂载生效（`docker rm -f omp-app && docker run -d ... -v omp-maven:/data/omp/maven-repository ...`）
- 重建后第一次启动 `docker logs omp-app` 应看到：
  - `[entrypoint] Maven Apache Maven 3.9.9 (...)`
  - `[entrypoint] CodeGraph 1.x.y`
  - 后台异步输出 `[entrypoint] codegraph init /data/omp/workspaces/...`

**已运行 `omp-app` 容器上的卷**：
- 新卷 `omp-maven` 是新增，**没有迁移工作**
- 旧卷 `omp-workspaces / omp-agent / omp-logs` 不受影响

**镜像体积**：
- 增量：~105 MB（Maven 15 MB + CodeGraph 90 MB）
- §18.4 多语言开发环境段注释：合计从 ~2.2 GB 改为 ~2.3 GB（dev-only）

**持久化**：
- `/data/omp/maven-repository`：随 `omp-maven` 卷保留
- `.codegraph/`：随 `omp-workspaces` 卷保留（每个工作区独立一份）

### 回滚方法

若 Maven / CodeGraph 引发问题，回滚两步：

**1) 镜像回退**（无需撤销卷挂载——`docker restart` 不影响已挂载的卷）：
```bash
ssh root@10.126.2.120
cd /home/omp/docker-build
git checkout HEAD~ -- Dockerfile   # 退回上一版 Dockerfile
docker build -t omp-allinone:latest .
docker rm -f omp-app
docker run -d --restart unless-stopped --name omp-app \
  -p 8000:80 -p 8080:8080 \
  -v omp-workspaces:/data/omp/workspaces \
  -v omp-agent:/data/omp/agent \
  -v omp-logs:/data/omp/logs \
  omp-allinone:latest
```

**2) 清理 Maven 卷（可选）**：
```bash
ssh root@10.126.2.120 'docker volume rm omp-maven'
```

**CodeGraph 单独回退**：不需要动镜像——只需从 `entrypoint.sh` 删掉 934–948 行那段后台 init，容器重建即可。已生成的 `.codegraph/` 目录是只读的，不删不影响任何功能。

### 验证清单

按 DEPLOY.md **§19.6 自检流程**走 8 步：
- 1) `mvn --version` 输出 3.9.9 + Java 21
- 2) `/root/.m2/settings.xml` 的 `localRepository` = `/data/omp/maven-repository`
- 3) `/data/omp/maven-repository` 目录存在（首启空目录）
- 4) `mvn -f /app/backend/pom.xml package -DskipTests` → BUILD SUCCESS（需先把 backend 源码 COPY 进镜像）
- 5) `du -sh /data/omp/maven-repository` → 300–500 MB
- 6) `codegraph version` → 输出 1.x
- 7) `codegraph status /data/omp/workspaces/<uid>/<repo>` → 显示索引统计
- 8) `codegraph explore "OmpProcessSpec" --path /data/omp/workspaces/<uid>/<repo>` → 返回源码片段

### 相关文档

- DEPLOY.md §18.4：Dockerfile / entrypoint.sh 完整副本
- DEPLOY.md §19.5：运维命令速查（含 Maven / CodeGraph 5+5 行）
- DEPLOY.md §19.6：容器内自检流程（8 步）
- DEPLOY.md §19.7：maven-settings.xml 模板（含 docker cp 热更新）
- DEPLOY.md §20：CodeGraph 使用说明