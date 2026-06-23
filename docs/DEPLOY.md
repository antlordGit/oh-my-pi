# 服务端部署 Runbook — oh-my-pi / omp

> 本文档记录把 **本工程 omp（bun + Rust N-API + Java 后端 + Vue 前端）** 部署到 Linux x86_64 服务器的完整流程。

> [!NOTE] **本指南适用于任何全新的 Linux x86_64 服务器**。唯一需要从开发机带过去的、**不在本指南里列出的**，就是工程源码本身（`git clone` 或 rsync）。

> 📋 **变更历史**：本文件的镜像内容 / 卷结构 / 运维流程改动记录在 [`DEPLOY-CHANGELOG.md`](./DEPLOY-CHANGELOG.md)。
> CodeGraph 使用说明见 §20。

---

## 0. 部署到新服务器 — 材料清单

**你要准备什么（从本地开发机带过去的）：**

| 材料 | 怎么得到 | 详情见节 |
|------|---------|---------|
| **`omp-source.tar.gz`**（工程 + node_modules） | `tar -czf /tmp/omp-source.tar.gz --exclude='.git' --exclude='target' --exclude='crates/*/target' .`，在工程根目录运行 | §7 |
| **`pi_natives.linux-x64-baseline.node`**（原生模块） | 本地交叉编译（zig + rustup），或用已经编好的那份 | §4 |
| **`omp-backend-0.1.0.jar`**（后端 fat-jar） | `cd backend && mvn -DskipTests package` | §6 |
| **`web/dist/`**（前端静态产物） | `cd web && bun run build` | §5 |

**本地如何一键生成所有 4 个产物（已验证可执行）：**

```bash
# 在工程根目录运行，输出到 /tmp/omp-build/
mkdir -p /tmp/omp-build

# 1) 交叉编译原生模块（需要 zig + rustup，见 §4）
export PATH="/opt/homebrew/opt/zig@0.14/bin:$HOME/.cargo/bin:$PATH"
export CFLAGS_x86_64_unknown_linux_gnu="-UNDEBUG -O2"
CI=1 CROSS_TARGET=x86_64-unknown-linux-gnu TARGET_PLATFORM=linux TARGET_ARCH=x64 TARGET_VARIANT=baseline \
  bun packages/natives/scripts/build-native.ts
cp packages/natives/native/pi_natives.linux-x64-baseline.node /tmp/omp-build/

# 2) 构建前端
cd web && bun install && bun run build && cd ..
tar -czf /tmp/omp-build/omp-frontend.tar.gz -C web/dist .

# 3) 打包后端（skip tests 加速）
cd backend && mvn -DskipTests package && cd ..
cp backend/target/omp-backend-0.1.0.jar /tmp/omp-build/

# 4) 打包工程源 + node_modules（不含 .git / target / web/dist）
tar -czf /tmp/omp-build/omp-source.tar.gz \
  --exclude='.git' --exclude='target' --exclude='crates/*/target' \
  --exclude='web/dist' --exclude='node_modules/.cache' \
  --exclude='*.log' --exclude='.DS_Store' .

echo "产出:"
ls -lh /tmp/omp-build/

**服务器上需要装的（纯 yum/curl 搞定，不依赖外部文件上传）：**
- **bun** ≥ 1.3.14（从 gh-proxy 或官方下载 `bun-linux-x64.zip`）
- **java** ≥ 21（`yum install java-21-openjdk`）
- **nginx**（`yum install nginx`）
- **unzip**（解压 bun zip 包）

**关键配置（不需要文件上传，直接写）：**
- `OMP_BIN` 环境变量指向 `omp-dev.sh`
- `nginx omp.conf`
- application.yml 启动参数

---

---

## 1. 架构概览

```
浏览器 / curl
    │  REST + WebSocket
    ▼
┌────────────────────────────────────────────────────────────┐
│ nginx (:80 / :443)                                           │
│   - /                 → 静态文件 web/dist                   │
│   - /api/*            → 反代 → localhost:8080              │
│   - /ws               → 反代 → localhost:8080              │
└────────────────────────────────────────────────────────────┘
    │
    ▼
┌────────────────────────────────────────────────────────────┐
│ Java 后端  omp-backend-0.1.0.jar  (:8080)                   │
│   - Spring Boot + WebSocket                                  │
│   - 进程池 (ProcessPool, max 10)                            │
│   - 每会话 lazy spawn 一个 omp 子进程                        │
└────────────────────────────────────────────────────────────┘
    │  ProcessBuilder spawn, stdin/stdout NDJSON
    ▼
┌────────────────────────────────────────────────────────────┐
│ omp 子进程  bun packages/coding-agent/src/cli.ts --mode rpc │
│   - 每用户 / 每会话一个                                       │
│   - 工作区 cwd = /home/omp/workspaces/<uid>/<repo>          │
└────────────────────────────────────────────────────────────┘
```

### 关键事实

| 项 | 值 |
|---|---|
| omp 是按需子进程，**不是常驻服务** | 后端按会话 spawn，用 `bun cli.ts --mode rpc` 通过 stdin/stdout NDJSON 通信 |
| 原生模块平台敏感 | `pi_natives.darwin-arm64.node` 不能在 Linux 上跑，必须本地交叉编译出 `pi_natives.linux-x64-baseline.node` |
| 工作区边界 | `omp` 的 cwd = `/home/omp/workspaces/<uid>/<repo>`，所有文件操作受 workspace 沙箱保护 |
| 包管理器 | **bun 必需**（工程 `package.json` 钉死 `bun@1.3.14`）。yarn 仅作为辅助工具 |

---

## 2. 本地环境依赖（macOS arm64 开发机）

### 2.1 必需

| 工具 | 版本 | 用途 | 安装 |
|---|---|---|---|
| bun | ≥ 1.3.14 | 跑 `cli.ts`、安装依赖、运行 `build-native.ts` | `curl -fsSL https://bun.sh/install \| bash` |
| rustup | 最新 | nightly toolchain 管理 | `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \| sh` |
| rust nightly | 2026-04-29 或更新 | 工程 `rust-toolchain.toml` 钉死 | `rustup toolchain install nightly` |
| rust target x86_64-unknown-linux-gnu | 任意 | 交叉编译 Linux native | `rustup target add x86_64-unknown-linux-gnu` |
| zig | 0.14.x（**不是 0.16**）| `cargo-zigbuild` 交叉链接 C 依赖（tree-sitter）| `brew install zig@0.14` |
| cargo-zigbuild | ≥ 0.23 | 跨平台链接器（zig cc 替代 gcc）| `cargo install cargo-zigbuild` |
| Java (JDK) | ≥ 21 | 构建后端 | `brew install openjdk@21` |
| Maven | ≥ 3.9 | 构建后端（可选，工程已带 mvnw） | `brew install maven` |
| nginx | 任意 | **本地无需**，仅服务端用 |
| ssh | 任意 | 上传 / 远程操作 | 系统自带 |

### 2.2 钉版本要点

```bash
# zig 不能用 0.16+（--target 命名空间改了，cargo-zigbuild 0.23 不识别）
brew uninstall zig
brew install zig@0.14
export PATH="/opt/homebrew/opt/zig@0.14/bin:$HOME/.cargo/bin:$PATH"

# rustup 装 nightly 的 linux target（通过阿里云镜像加速）
export RUSTUP_DIST_SERVER="https://mirrors.aliyun.com/rustup"
export RUSTUP_UPDATE_ROOT="https://mirrors.aliyun.com/rustup/rustup"
rustup target add x86_64-unknown-linux-gnu --toolchain nightly-2026-04-29-aarch64-apple-darwin
```

### 2.3 ⚠️ Homebrew Rust 干扰（踩过的坑）

- 系统上 `which cargo` / `which rustc` 默认指向 **Homebrew 装的独立 Rust 1.96**（`/opt/homebrew/bin/`），**没有 cargo proxy**。
- `rust-toolchain.toml` 只对 **rustup 管理的 toolchain** 生效，Homebrew cargo 看不到。
- **必须把 `~/.cargo/bin` 放到 PATH 最前**（并且在其中建立 cargo/rustc 软链到 rustup nightly）：

```bash
# 临时屏蔽 Homebrew rust
sudo mv /opt/homebrew/bin/cargo /opt/homebrew/bin/rustc /tmp/ 2>/dev/null \
  || mv /opt/homebrew/bin/cargo /tmp/cargo.bak
#    && mv /opt/homebrew/bin/rustc /tmp/rustc.bak

# 建立 rustup proxy 软链（cargo / rustc 都要）
mkdir -p ~/.cargo/bin
ln -sf ~/.rustup/toolchains/nightly-2026-04-29-aarch64-apple-darwin/bin/cargo ~/.cargo/bin/cargo
ln -sf ~/.rustup/toolchains/nightly-2026-04-29-aarch64-apple-darwin/bin/rustc ~/.cargo/bin/rustc

# 验证：cargo --version 应是 nightly
export PATH="$HOME/.cargo/bin:$PATH"
cargo --version  # → cargo 1.97.0-nightly
```

---

## 3. 本地构建产物（要上传到服务器的文件）

| 文件 | 来源 | 大小 |
|---|---|---|
| `backend/target/omp-backend-0.1.0.jar` | `./mvnw -pl backend package -DskipTests` | ~57 MB |
| `web/dist/` | `(cd web && bun run build)` | 几 MB |
| `packages/natives/native/pi_natives.linux-x64-baseline.node` | 交叉编译（见 §4） | ~10 MB |
| `node_modules/` (rsync 整个目录) | `bun install` 已在本地 | ~1.4 GB |
| `scripts/omp-dev.sh` | 工程根目录已存在 | ~300 B |

> **不传** `target/` 目录（cargo 构建产物），服务器不需要重新编译 Rust。

---

## 4. 交叉编译 pi_natives（Linux x64 .node）

### 4.1 命令（一条）

```bash
cd /path/to/oh-my-pi-main
export PATH="/opt/homebrew/opt/zig@0.14/bin:$HOME/.cargo/bin:$PATH"
export RUSTUP_DIST_SERVER="https://mirrors.aliyun.com/rustup"
export RUSTUP_UPDATE_ROOT="https://mirrors.aliyun.com/rustup/rustup"
export CFLAGS_x86_64_unknown_linux_gnu="-UNDEBUG -O2"   # 关掉 zig cc -O3 自带的 NDEBUG
export CI=1                                              # 跳过 strip 检查

CI=1 CROSS_TARGET=x86_64-unknown-linux-gnu \
TARGET_PLATFORM=linux \
TARGET_ARCH=x64 \
TARGET_VARIANT=baseline \
  bun packages/natives/scripts/build-native.ts
```

### 4.2 产物

```
packages/natives/native/pi_natives.linux-x64-baseline.node
```

### 4.3 为什么需要这些参数

| 参数 | 原因 |
|---|---|
| `CROSS_TARGET=x86_64-unknown-linux-gnu` | 让 napi 调 `cargo-zigbuild --target linux-x64` |
| `TARGET_PLATFORM=linux` + `TARGET_ARCH=x64` | 决定产物文件名后缀 |
| `TARGET_VARIANT=baseline` | 兼容老 CPU（不强制 AVX2），服务端无需探测 |
| `CI=1` | 跳过 `strip + verify` 步骤（本地 debug 阶段不需要） |
| `CFLAGS_x86_64_unknown_linux_gnu="-UNDEBUG"` | zig cc 默认 `-O3 -DNDEBUG`，tree-sitter 的 `scanner.c` 在 `#error "expected assertions to be enabled"` 编译失败 |

### 4.4 故障排查速查

| 报错 | 原因 | 解决 |
|---|---|---|
| `can't find crate for core` | rustup target 没装或损坏 | `rustup target remove x86_64-unknown-linux-gnu --toolchain nightly && rustup target add ...` |
| `failed to find tool "x86_64-linux-gnu-gcc"` | cc-rs 找不到 Linux gcc | 必须走 `cargo-zigbuild`，让 zig cc 替代 |
| `UnknownOperatingSystem` (zig) | zig 0.16 不识别 `x86_64-unknown-linux-gnu` 命名 | 装 `zig@0.14` |
| `expected assertions to be enabled` | zig cc `-O3 -DNDEBUG` 关了 assert | 加 `CFLAGS_x86_64_unknown_linux_gnu="-UNDEBUG -O2"` |
| `cargo: command not found: +nightly` | Homebrew cargo 不识别 `+toolchain` 语法 | 用 rustup proxy 软链覆盖 |

---

## 5. 前端构建

```bash
cd /path/to/oh-my-pi-main/web
bun install        # 或 yarn install（仅依赖管理）
bun run build      # = vue-tsc --noEmit && vite build
```

产物：`web/dist/`。

`web/dist` 是纯静态文件，nginx 直接 serve。

---

## 6. 后端打包

```bash
cd /path/to/oh-my-pi-main
./mvnw -pl backend -am package -DskipTests
```

产物：`backend/target/omp-backend-0.1.0.jar`。

后端是 fat-jar，单文件即可运行。

---

## 7. 上传到服务器

### 7.1 服务器前置（一次性）

```bash
ssh root@119.29.237.63
mkdir -p /home/omp/{app/{dist,native,jar},workspaces,agent,logs,run}
```

### 7.2 上传文件

```bash
SERVER=root@119.29.237.63
REMOTE=/home/omp
LOCAL=/path/to/oh-my-pi-main

# 后端 jar（最关键，单文件 ~57 MB）
rsync -avz --progress \
  $LOCAL/backend/target/omp-backend-0.1.0.jar \
  $SERVER:$REMOTE/app/jar/

# 前端静态产物
rsync -avz --progress --delete \
  $LOCAL/web/dist/ \
  $SERVER:$REMOTE/app/dist/

# 原生模块（覆盖 mac 版的，仅 Linux 留）
rsync -avz --progress \
  $LOCAL/packages/natives/native/pi_natives.linux-x64-baseline.node \
  $SERVER:$REMOTE/app/native/

# 工程源码（omp 子进程直接 bun cli.ts，需要整个工程结构）
rsync -avz --progress --exclude='.git' --exclude='node_modules' --exclude='target' --exclude='web/node_modules' --exclude='crates/*/target' \
  $LOCAL/ \
  $SERVER:$REMOTE/app/omp/

# node_modules（1.4 GB，本地编好的全量上传，省去服务器 npm install）
rsync -avz --progress --exclude='*/.cache' --exclude='*/.bin' \
  $LOCAL/node_modules/ \
  $SERVER:$REMOTE/app/omp/node_modules/

# omp-dev.sh 启动脚本（可选，工程已自带）
rsync -avz --progress \
  $LOCAL/scripts/omp-dev.sh \
  $SERVER:$REMOTE/app/omp/scripts/
```

> **不要 rsync** `target/`（rust 编译产物）、`web/dist/node_modules/`、`.DS_Store`、`*.log`。
> **不要 rsync** `bun.lockb`（bun 二进制 lockfile，平台无关但几 MB；服务器装 bun 后 `bun install` 会重建）。

### 7.3 服务端安装 runtime

```bash
ssh root@119.29.237.63
curl -fsSL https://bun.sh/install | bash
export PATH="$HOME/.bun/bin:$PATH"
# 验证
bun --version  # ≥ 1.3.14
java -version  # 确认 java 已装（如果没有：yum install java-21-openjdk）
```

> 不需要 nginx / maven / rust / zig / yarn —— 后端是 fat-jar、omp 用 bun 跑源码、原生模块已交叉编好上传。

---

## 8. 服务端结构

```
/home/omp/
├── app/
│   ├── jar/omp-backend-0.1.0.jar         # 后端 fat-jar
│   ├── dist/                            # 前端静态（nginx 直接 serve）
│   │   ├── index.html
│   │   └── assets/
│   ├── native/
│   │   └── pi_natives.linux-x64-baseline.node
│   └── omp/                             # omp 子进程需要的工程根
│       ├── packages/coding-agent/src/cli.ts
│       ├── node_modules/                # 本地 rsync 上来
│       ├── crates/pi-natives/...         # Rust 源码（bun 跑 cli.ts 不会重编）
│       └── scripts/omp-dev.sh
├── workspaces/                          # 每用户会话的工作区
│   └── <uid>/<repo>/
├── agent/                               # omp agent 资源（PI_CODING_AGENT_DIR）
├── logs/                                 # 后端日志 / omp 子进程 stderr
└── run/                                  # pid 文件
```

---

## 9. 启动 / 停止 / 日志

### 9.1 启动后端

```bash
ssh root@119.29.237.63
export PATH="$HOME/.bun/bin:$PATH"
export OMP_BIN=/home/omp/app/omp/scripts/omp-dev.sh
cd /home/omp/app/jar
nohup java -jar omp-backend-0.1.0.jar > /home/omp/logs/backend.log 2>&1 &
echo $! > /home/omp/run/backend.pid
```

`OMP_BIN` 必须指向**工程内 wrapper**（不是 `~/bin/omp`），否则服务端会拿全局版本替换本工程代码。

### 9.2 启动 nginx

```bash
# nginx 通常已装（systemd 管理）
systemctl status nginx
systemctl reload nginx   # 应用新配置后
```

### 9.3 验证

```bash
# 后端健康
curl -s http://localhost:8080/api/auth/login \
  -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'
# → {"token":"eyJ...", "username":"admin", "role":"admin"}

# 前端静态
curl -sI http://localhost/ | head -1  # HTTP/1.1 200

# 浏览器
# http://119.29.237.63/
```

### 9.4 停止 / 重启

```bash
# 停止后端
kill $(cat /home/omp/run/backend.pid)

# 重启后端
kill $(cat /home/omp/run/backend.pid) 2>/dev/null
sleep 2
export OMP_BIN=/home/omp/app/omp/scripts/omp-dev.sh
cd /home/omp/app/jar
nohup java -jar omp-backend-0.1.0.jar > /home/omp/logs/backend.log 2>&1 &
echo $! > /home/omp/run/backend.pid

# 看日志
tail -f /home/omp/logs/backend.log
# omp 子进程的 stderr（按 session id 分文件）
ls /home/omp/logs/omp-*.err.log
tail -f /home/omp/logs/omp-<session-id>.err.log
```

---

## 10. nginx 反代配置

```nginx
# /etc/nginx/conf.d/omp.conf

upstream omp_backend {
    server 127.0.0.1:8080;
    keepalive 32;
}

server {
    listen 80;
    server_name _;  # 或你的域名

    root /home/omp/app/dist;
    index index.html;

    # 前端 SPA
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 后端 REST
    location /api/ {
        proxy_pass http://omp_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # 后端 WebSocket
    location /ws {
        proxy_pass http://omp_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400;   # 流式响应长连接
    }

    # 前端静态资源缓存
    location ~* \.(js|css|woff2?|svg|png|ico)$ {
        expires 7d;
        add_header Cache-Control "public, max-age=604800, immutable";
    }
}
```

```bash
nginx -t   # 测配置
systemctl reload nginx
```

---

## 11. application.yml 关键字段（不变）

`backend/src/main/resources/application.yml`：

```yaml
app:
  omp:
    binary: ${OMP_BIN:/Users/chenzhiwei/bin/omp}    # ← 环境变量 OMP_BIN 优先
    workspaces-root: ${OMP_WORKSPACES_ROOT:/tmp/omp/workspaces}
    agent-root: ${OMP_AGENT_ROOT:/tmp/omp/agent}
    stderr-log-dir: ${OMP_STDERR_LOG_DIR:/tmp/omp/logs}
```

**服务器上运行时一定要设 `OMP_BIN=/home/omp/app/omp/scripts/omp-dev.sh`**（见 §9.1）。后端 jar 的默认值 `~/bin/omp` 是开发机的，不能用。

---

## 12. 端到端冒烟测试（部署后必做）

```bash
# 1. 后端健康
TOKEN=$(curl -s http://localhost/api/auth/login \
  -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

# 2. 越界拦截（workspace 沙箱）
echo "TOP SECRET - outside" > /home/omp/workspaces/2/outside.txt
SID=$(curl -s -X POST http://localhost/api/sessions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"repoId":"chat-app","title":"smoke"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['sessionId'])")
curl -s -X POST "http://localhost/api/sessions/$SID/prompt" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"message":"用 read 工具读取 ../outside.txt"}'

# 3. 看 omp 子进程 stderr 日志，确认触发 Access denied
tail /home/omp/logs/omp-$SID.err.log | grep -i "Access denied\|outside workspace"
# 预期: "Access denied: read path '/home/omp/workspaces/2/outside.txt' is outside workspace '/home/omp/workspaces/2/chat-app'."
```

清理：
```bash
rm -f /home/omp/workspaces/2/outside.txt
```

---

## 13. 故障排查

### 13.1 后端启动后立刻退出

```bash
tail -50 /home/omp/logs/backend.log
```

常见：
- `OMP_BIN` 路径错或 `omp-dev.sh` 无 `+x` 权限
- `/home/omp/app/omp/node_modules` 缺失（bun 跑 `cli.ts` 会失败）
- `java -version` < 21（jar 用 21 编的）

### 13.2 omp 子进程一启动就崩

```bash
tail -100 /home/omp/logs/omp-*.err.log
```

- `Cannot find module '@oh-my-pi/pi-natives'` → `pi_natives.linux-x64-baseline.node` 没放到 `native/` 目录
- `ELIBBAD` / `wrong ELF class` → 拷了 mac 版 `.node` 到 Linux

### 13.3 前端 502

```bash
curl -sI http://localhost/         # 应 200
nginx -t                            # 测配置
tail /var/log/nginx/error.log       # 看 nginx 错误
```

---

## 14. 升级流程（日常发版）

```bash
# 本地
1. 改代码
2. 重跑 §4（native 重编）、§5（前端 build）、§6（后端 mvn package）
3. 跑 §12 冒烟测试

# 上传
4. rsync §7.2 列的四个目标

# 服务端
5. kill $(cat /home/omp/run/backend.pid)  # 停旧后端
6. nohup java -jar omp-backend-0.1.0.jar &  # 启新后端
7. nginx 不需要重启（dist 是直接 serve）
```

零停机升级可以做：先传 `app/dist/` 和 `app/omp/`（不重启），再 kill 旧后端、启新后端（≈ 1 秒中断）。Java 后端不支持热加载。

---

## 15. 安全提醒

- 默认 admin/admin 是 **本地开发密码**，生产环境必须改：
  ```yaml
  app.security.jwt-secret: <32+ 字符随机>
  app.security.bootstrap-admin.password: <强密码>
  ```
- 后端 8080 端口不应直接暴露公网 — 必须走 nginx 80/443，nginx 加 basic auth 或 IP 白名单。
- WebSocket `/ws` 必须鉴权（前端已有 JWT header），nginx 反代时不要去掉 `Authorization` 头。
- `OMP_API_KEY`（LLM provider key）放 `app.vault.api-key`，不要写 yml。

---

## 16. 部署记录

### 2026-06-20（首次部署）

| 项 | 详情 |
|---|---|
| **服务器** | 119.29.237.63, OpenCloudOS 8.10, 2核 3.6G 无swap |
| **bun** | 1.3.14（从 gh-proxy 下载 zip，解压到 /usr/local/bin） |
| **java** | OpenJDK 21（yum 装，需先禁用超时 antigravity 仓库） |
| **nginx** | 已装（yum 重装默认配置，omp.conf 接管 80 → 反代 8080） |

**修复的关键问题**：

| 问题 | 修复 |
|------|------|
| omp-dev.sh 指向 macOS 路径（`/opt/homebrew/bin/bun /Users/.../cli.ts`） | 改成 `/usr/local/bin/bun /home/omp/app/omp/.../cli.ts` |
| GitHub 下载 bun 超时（curl timeout 300s） | 用 `gh-proxy.com` 镜像下载 35MB zip，2s 完成 |
| yum antigravity 仓库超时堵住 JDK 安装 | `yum --disablerepo=antigravity-rpm install java-21-openjdk` |
| nginx 默认 server 块与 omp.conf 冲突 | 清空 nginx.conf 后只留 `include conf.d/*.conf` |

**公网验证** (2026-06-20 部署后):

| 测试项 | 结果 |
|---|---|
| `http://119.29.237.63/` | HTTP 200 (前端 index.html) |
| `POST /api/auth/login` | JSON token 正常返回 |
| WS 升级 `/ws/sessions/{id}?token=` | nginx → 426 Upgrade Required (tomcat WebSocket 握手就绪) |
| Workspace 沙箱 | read ../outside.txt → Access denied; bash cp ../outside.txt → Access denied ✅ |

---

## 17. 部署到新服务器 — 速查清单

### 本地准备（一次性，产出的文件可复用）

1. 交叉编译原生模块：
   ```bash
   export PATH="/opt/homebrew/opt/zig@0.14/bin:$HOME/.cargo/bin:$PATH"
   export CFLAGS_x86_64_unknown_linux_gnu="-UNDEBUG -O2"
   CI=1 CROSS_TARGET=x86_64-unknown-linux-gnu TARGET_PLATFORM=linux TARGET_ARCH=x64 TARGET_VARIANT=baseline bun packages/natives/scripts/build-native.ts
   ```
   产物：`packages/natives/native/pi_natives.linux-x64-baseline.node`

2. 构建前端：
   ```bash
   cd web && bun install && bun run build
   ```
   产物：`web/dist/`

3. 打包后端：
   ```bash
   cd backend && mvn -DskipTests package
   ```
   产物：`backend/target/omp-backend-0.1.0.jar`

4. 打包工程源 + node_modules：
   ```bash
   cd <工程根目录>
   tar -czf /tmp/omp-source.tar.gz      --exclude='.git' --exclude='target' --exclude='crates/*/target' .
   ```
   产物：`/tmp/omp-source.tar.gz`（约 560 MB）

5. 上传到新服务器：
   ```bash
   SERVER=root@<新服务器IP>
   scp /tmp/omp-source.tar.gz $SERVER:/tmp/
   scp /tmp/omp-frontend.tar.gz $SERVER:/tmp/
   scp omp-backend-0.1.0.jar $SERVER:/tmp/
   scp pi_natives.linux-x64-baseline.node $SERVER:/tmp/
   ```

### 服务器端步骤（在新服务器上跑）

```bash
ssh root@<新服务器IP>

# 1. 装运行时
export PATH="/usr/local/bin:$PATH"
# bun: 从镜像下载 zip 解压到 /usr/local/bin（避免 GitHub 超时）
curl -fsSL -o /tmp/bun-linux-x64.zip --retry 3   https://gh-proxy.com/https://github.com/oven-sh/bun/releases/latest/download/bun-linux-x64.zip
unzip -o /tmp/bun-linux-x64.zip -d /usr/local/bin/
ln -sf /usr/local/bin/bun-linux-x64/bun /usr/local/bin/bun
yum install -y java-21-openjdk nginx unzip
bun --version && java -version

# 2. 建目录 + 解压
mkdir -p /home/omp/{app/{jar,dist,native,omp},workspaces,agent,logs,run}
tar -xzf /tmp/omp-source.tar.gz -C /home/omp/app/omp/
tar -xzf /tmp/omp-frontend.tar.gz -C /home/omp/app/dist/
cp /tmp/omp-backend-0.1.0.jar /home/omp/app/jar/
cp /tmp/pi_natives.linux-x64-baseline.node /home/omp/app/omp/packages/natives/native/

# 3. ***** 关键 ***** 修 omp-dev.sh（本地写的路径是 macOS 的！）
cat > /home/omp/app/omp/scripts/omp-dev.sh <<'SH'
#!/bin/bash
exec /usr/local/bin/bun /home/omp/app/omp/packages/coding-agent/src/cli.ts "$@"
SH
chmod +x /home/omp/app/omp/scripts/omp-dev.sh

# 4. 配 nginx（见 §10 完整配置）
cat > /etc/nginx/conf.d/omp.conf <<'NGX'
upstream omp_backend { server 127.0.0.1:8080; keepalive 32; }
server {
    listen 80; server_name _;
    root /home/omp/app/dist; index index.html;
    location /api/ { proxy_pass http://omp_backend; proxy_http_version 1.1; proxy_set_header Host $host; }
    location /ws/ { proxy_pass http://omp_backend; proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; proxy_set_header Host $host; proxy_read_timeout 86400; }
    location / { try_files $uri $uri/ /index.html; }
}
NGX
nginx -t && systemctl reload nginx

# 5. 启动后端
export PATH="/usr/local/bin:$PATH"
export OMP_BIN="/home/omp/app/omp/scripts/omp-dev.sh"
cd /home/omp/app/jar
nohup java -jar omp-backend-0.1.0.jar > /home/omp/logs/backend.log 2>&1 &
echo $! > /home/omp/run/backend.pid

# 6. 验证（等 15 秒启动）
sleep 15
curl -s http://localhost/api/auth/login -X POST -H 'Content-Type: application/json'   -d '{"username":"admin","password":"admin"}'
```

### 踩坑速查

| 症状 | 根因 | 修复 |
|------|------|------|
| `curl https://github.com/...` 超时 | 国内 GitHub 不通 | 用 `gh-proxy.com` 镜像 |
| `yum install java` 超时 | 第三方 yum 源不通 | `--disablerepo=antigravity-rpm` |
| omp spawn 后立即 exitCode=127 | `omp-dev.sh` 里 bun 路径是 macOS 的 | 改成 `/usr/local/bin/bun` |
| nginx 启动报 `duplicate listen` | 默认 server 块冲突 | 清空主 conf 只留 `include conf.d/*` |
| 前端返回 index.html 但 API 也返回 index.html | nginx `location /` 的 try_files 覆盖了 API 路由 | 把 `location /api/` 和 `location /ws/` 放 `location /` 前面 |
| omp 崩溃 `Cannot find module pi_natives` | 用的还是 macOS .node | 确认 `pi_natives.linux-x64-baseline.node` 已覆盖 |
| bun 命令找不到 | PATH 没有 `/usr/local/bin` | `export PATH="/usr/local/bin:$PATH"` 写入 `~/.bashrc` |

---

## 18. 部署记录 — 10.126.2.120 UAT 服务器

> 本节记录 10.126.2.120 上的真实部署状态。所有路径 / 端口 / 卷名都与服务器
> 一致；改服务器配置时同步更新本节。

### 18.1 部署概览

| 项 | 值 |
|---|---|
| 服务器 | 10.126.2.120, TencentOS 3.3, 61G 内存, x86_64 |
| Java | OpenJDK 21.0.9（打进 all-in-one 镜像） |
| bun | 1.3.14（打进 all-in-one 镜像） |
| 数据库 | MySQL 8（容器外，宿主机 127.0.0.1:3306，库名 `omp`，user `root`） |
| code-server | v4.124.2（codercom/code-server:latest，独立容器） |
| 部署形态 | **omp-app**（前端+后端+omp 三合一）+ **code-server**（独立容器） |

### 18.2 服务地址

| 服务 | 地址 | 来源 |
|---|---|---|
| 前端 | http://10.126.2.120:8000/ | omp-app 容器（nginx 80 → 宿主 8000） |
| 后端 API | http://10.126.2.120:8080 | omp-app 容器 |
| code-server | http://10.126.2.120:5000/ | code-server 独立容器（8080 → 宿主 5000） |
| MySQL | 172.17.0.1:3306（容器内）/ 宿主 3306 | 宿主机已有的 MySQL 8 实例 |

容器内访问 MySQL 用宿主 docker0 网关 `172.17.0.1`，不能用 127.0.0.1。

### 18.3 Docker 卷（数据持久化）

| 卷名 | 宿主物理路径 | 容器内挂载 |
|---|---|---|
| `omp-workspaces` | `/data/docker/volumes/omp-workspaces/_data` | omp-app: `/data/omp/workspaces`、code-server: `/data/omp/workspaces` |
| `omp-agent` | `/data/docker/volumes/omp-agent/_data` | omp-app: `/data/omp/agent` |
| `omp-logs` | `/data/docker/volumes/omp-logs/_data` | omp-app: `/data/omp/logs` |
| `omp-maven` | `/data/docker/volumes/omp-maven/_data` | omp-app: `/data/omp/maven-repository` |

> ⚠️ **同卷同路径**：`omp-workspaces` 必须在两个容器内挂到**完全相同**的路径
> `/data/omp/workspaces`。后端 IDE 按钮生成的 URL 是 `?folder=/data/omp/workspaces/<user>/<repo>`（绝对路径），
> code-server 容器内必须存在同名路径，否则报 "Workspace does not exist"。

### 18.4 omp-app 容器（all-in-one：前端 + 后端 + omp）

**构建上下文** `/home/omp/docker-build/`：

```
/home/omp/docker-build/
├── Dockerfile
├── entrypoint.sh              # nginx + java，容器启动脚本
├── nginx/                     # 容器内 nginx 整套配置（详见 18.4.1）
│   ├── nginx.conf             #   顶层：只负责调度
│   └── conf.d/
│       ├── omp-main.conf             #   本工程主 location（前端 + 后端）
│       └── omp-services-locations.conf  # 业务工程 location 片段（热加载）
├── omp-dev.sh                 # 容器内 omp 入口（被后端 spawn）
├── prod-application.yml       # 生产配置（DB / IDE / JWT）
├── maven-settings.xml         # Maven 镜像（脱敏，路径替换为容器内路径，参考 §19.7）
├── mcp.json                    # CodeGraph MCP 配置（共享，entrypoint cp 到 agentRoot，参考 §20.8）
├── APPEND_SYSTEM.md            # CodeGraph 使用引导 system prompt（Java 端按用户 cp 到 agentDir/，参考 §20.8）
├── jdk21/                     # JDK 21（打进镜像）
├── bun                        # bun 二进制（打进镜像）
├── omp-backend-0.1.0.jar      # 后端 fat-jar
├── dist/                      # 前端静态产物
├── omp/                       # omp 源码 + node_modules + linux .node
└── （CodeGraph 由 Dockerfile 内 npm install -g 安装，依赖上层 Node 22 LTS）
```

**Dockerfile**：

```dockerfile
FROM ubuntu:22.04

# 装运行时依赖: nginx(前端) + git(workspace) + ca-certificates + tini(进程管理)
RUN sed -i 's|http://archive.ubuntu.com|http://mirrors.tencentyun.com|g; s|http://security.ubuntu.com|http://mirrors.tencentyun.com|g' /etc/apt/sources.list && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      nginx git ca-certificates tini curl && \
    rm -rf /var/lib/apt/lists/*

# JDK 21
COPY jdk21 /usr/local/jdk21
ENV PATH=/usr/local/jdk21/bin:/usr/local/bin:$PATH

# bun
COPY bun /usr/local/bin/bun
RUN chmod +x /usr/local/bin/bun

# ============================================================================
# oh-my-pi CLI 安装 —— 共享 /data/omp/agent/ 配置 + 生态扩展能力
# ----------------------------------------------------------------------------
# 目标：
#   1) 容器内可直接用 `omp` 命令（plugin install / skill add / 等生态命令）
#   2) Java 后端继续通过 /app/omp/scripts/omp-dev.sh 跑源码，
#      与安装的 omp 共享同一套 /data/omp/agent/ 下的 extensions/skills/hooks/tools
#   3) entrypoint.sh 在 /root/.omp/agent 上建 symlink → /data/omp/agent，
#      Java spawn 时为每个用户建 extensions/skills/hooks/tools 子 symlink
#
# 安装源：官方脚本 https://omp.sh/install（GitHub 国内走 gh-proxy 中转）。
# 安装失败时镜像构建会失败（fail-fast），便于 CI 第一时间发现。
# 升级：install 脚本自动拉最新 stable；想钉版本可改 URL。
# ============================================================================
RUN curl -fsSL "https://gh-proxy.com/https://omp.sh/install" -o /tmp/omp-install.sh \
    && sh /tmp/omp-install.sh \
    && rm /tmp/omp-install.sh \
    && omp --version

# Maven 3.9.9 —— Java 后端容器内构建（tarball 安装，~15 MB；不走 apt 避免拖 openjdk 依赖）。
# 版本与 DEPLOY.md §2.1 本地构建要求对齐（"Maven ≥ 3.9"）；apt 仓库的 3.6.3 不满足。
ARG MAVEN_VERSION=3.9.9
RUN curl -fsSL "https://gh-proxy.com/https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      -o /tmp/maven.tar.gz \
    && tar -xzf /tmp/maven.tar.gz -C /usr/local/ \
    && mv /usr/local/apache-maven-${MAVEN_VERSION} /usr/local/maven \
    && rm /tmp/maven.tar.gz \
    && /usr/local/maven/bin/mvn --version

ENV MAVEN_HOME=/usr/local/maven
ENV PATH=/usr/local/maven/bin:/usr/local/jdk21/bin:/usr/local/bin:$PATH

# Maven 全局 settings（aliyun 镜像 + 本地仓库 /data/omp/maven-repository）。
# 容器启动时由 entrypoint.sh cp 到 /root/.m2/settings.xml。
COPY maven-settings.xml /etc/omp/maven-settings.xml

# CodeGraph MCP 配置 + system prompt 模板（参考 §20.8）。
# entrypoint.sh 启动时复制到 /data/omp/agent/mcp.json（共享，所有用户用同一份）。
# Java 端 OmpRpcClientFactory.spawn() 为每个用户创建 symlink 指向 ../mcp.json。
# 注意：CodeGraph CLI 自身的安装下移到 Node 22 装好之后（走 npm 国内镜像，见下方）。
COPY mcp.json /etc/omp/mcp.json
COPY APPEND_SYSTEM.md /etc/omp/APPEND_SYSTEM.md

# RTK (Rust Token Killer) —— 压缩 LLM agent 调 shell 时的输出，60-90% token 节省。
# 装 musl 静态二进制（不挑 libc，无运行时依赖）。固定版本以保证可复现，
# 升级见 https://github.com/rtk-ai/rtk/releases。
ARG RTK_VERSION=v0.42.4
RUN curl -fsSL "https://gh-proxy.com/https://github.com/rtk-ai/rtk/releases/download/${RTK_VERSION}/rtk-x86_64-unknown-linux-musl.tar.gz" \
      -o /tmp/rtk.tar.gz \
    && tar -xzf /tmp/rtk.tar.gz -C /usr/local/bin/ rtk \
    && chmod +x /usr/local/bin/rtk \
    && rm /tmp/rtk.tar.gz \
    && rtk --version

# RTK Pi-style 扩展安装到 omp 认的用户扩展目录。`rtk init --agent pi` 会把扩展
# 写到 ~/.pi/agent/extensions/rtk.ts（Pi 上游约定），但 omp 原生只扫描
# ~/.omp/agent/extensions/，所以拷一份到那里。后端 spawn omp 时还会显式传
# --extension 指向这个绝对路径（见 OmpProcessSpec.toArgv），双保险。
RUN rtk init -g --agent pi && mkdir -p /root/.omp/agent/extensions && cp /root/.pi/agent/extensions/rtk.ts /root/.omp/agent/extensions/rtk.ts

# rtk-proxy hook —— 在 omp 的 Bash 工具执行前自动把命令喂给 `rtk rewrite`，
# 对能改写的子命令（git、cargo、docker、gh、…）前置加 `rtk`，节省 60-90% token；
# 不匹配的子命令（echo、cd、ssh…）原样放行。
# 需 omp 源码具备 ToolCallEventResult.updatedInput 扩展（当前 dev 分支已合入）。
# 后端 spawn omp 时按 OmpProcessSpec.toArgv 的 Files.isRegularFile 条件加载，
# 文件缺失就跳过 —— dev 环境无该文件也能跑（fallback 到上面 RTK extension）。
RUN mkdir -p /root/.omp/hooks \
    && curl -fsSL "https://raw.githubusercontent.com/<your-org>/<your-repo>/main/backend/src/main/resources/hooks/rtk-proxy.ts" \
         -o /root/.omp/hooks/rtk-proxy.ts \
    && chmod 0644 /root/.omp/hooks/rtk-proxy.ts

# ============================================================================
# 多语言开发环境（开发容器用）
# ----------------------------------------------------------------------------
# 装 Node 22 / Python 3 / uv / Go 1.23 / Rust stable，便于在容器内直接开发
# 各种语言的服务。所有源走镜像加速（国内服务器拉取稳定）。
# 版本与增量：
#   - Node 22 LTS    +~120 MB（NodeSource 仓库）
#   - Python 3.10+   +~150 MB（Ubuntu apt + pip + venv）
#   - uv             +~30 MB（astral-sh 静态二进制）
#   - Go 1.23        +~700 MB（官方 tarball）
#   - Rust stable    +~1.2 GB（rustup 含 cargo + rustc）
#   - Maven 3.9.9    +~15 MB（官方 tarball，dev-only）
#   - CodeGraph       +~90 MB（npm global install，带 bundled Node + SQLite 运行时）
# 合计：镜像增加约 2.3 GB。生产实例不需要这些，dev-only。
# ============================================================================
ARG GO_VERSION=1.23.4
ARG UV_VERSION=0.11.23

# Node 22 LTS（官方 tarball，可控版本。NodeSource 仓库国内不友好，弃用）
ARG NODE_VERSION=22.11.0
RUN curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.gz" \
      -o /tmp/node.tar.gz \
    && tar -C /usr/local -xzf /tmp/node.tar.gz --strip-components=1 \
    && rm /tmp/node.tar.gz \
    && node --version && npm --version

# CodeGraph —— 代码智能知识图（编译期符号图 + 调用边 + 依赖，一次 codegraph_explore
# 替代多次 grep/glob/Read）。每个 omp agent 会话通过 §20.8 模板自动接入 MCP。
#
# 历史踩坑（详见 §20.8.1）：
#   - `curl install.sh | sh` 直连 GitHub raw 国内 SSL_read EOF / gh-proxy 522 不稳定
#   - 预下载 tarball + COPY 体积大、升级要 scp 50 MB
# 当前方案：走 npmmirror.com 装 npm 包，依赖前面装好的 Node 22。
# 版本锁定在 1.0.1，升级时改 ARG 即可，**不需要** scp 任何文件到 docker-build。
ARG CODEGRAPH_VERSION=1.0.1
RUN npm config set registry https://registry.npmmirror.com \
    && npm install -g @colbymchenry/codegraph@${CODEGRAPH_VERSION} \
    && codegraph --version

# Python 3 + pip + venv（Ubuntu 22.04 自带 3.10，腾讯云 apt 镜像源已在上面配好）
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && python3 --version && pip3 --version

# uv（Python 极速包管理器，astral-sh 静态二进制）
# uv tarball 顶层是 uv-<triple>/ 目录，需要 --strip-components=1
RUN curl -fsSL "https://gh-proxy.com/https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-x86_64-unknown-linux-gnu.tar.gz" \
      -o /tmp/uv.tar.gz \
    && tar -xzf /tmp/uv.tar.gz -C /usr/local/bin/ --strip-components=1 \
    && rm /tmp/uv.tar.gz \
    && uv --version

# Go 1.23（官方 tarball，控制版本）
RUN curl -fsSL "https://gh-proxy.com/https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" \
      -o /tmp/go.tar.gz \
    && tar -C /usr/local -xzf /tmp/go.tar.gz \
    && rm /tmp/go.tar.gz \
    && /usr/local/go/bin/go version

ENV PATH=/usr/local/go/bin:/root/go/bin:$PATH

# Rust stable（rustup，装到 /root/.cargo/bin，并加到 PATH）
RUN curl -fsSL --proto '=https' --tlsv1.2 https://sh.rustup.rs \
      | sh -s -- -y --default-toolchain stable --profile minimal \
    && echo 'source $HOME/.cargo/env' >> /root/.bashrc

ENV PATH=/root/.cargo/bin:$PATH
ENV CARGO_HOME=/root/.cargo RUSTUP_HOME=/root/.rustup

# 核心开发工具:
#   vim            —— 容器内编辑(改 nginx conf、查日志改文件等)
#   jq             —— JSON 解析(看 API 响应/日志)
#   build-essential—— gcc/g++/make,Rust cargo build / Python pip 装 C 扩展 / Go cgo 都需要
#   net-tools      —— netstat 等网络/端口诊断(配合 iproute2 的 ss)
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
         vim jq build-essential net-tools \
    && rm -rf /var/lib/apt/lists/*

# 应用产物
WORKDIR /app
COPY omp-backend-0.1.0.jar /app/omp-backend-0.1.0.jar
COPY dist /app/dist
COPY omp /app/omp
COPY prod-application.yml /app/prod-application.yml
COPY omp-dev.sh /app/omp/scripts/omp-dev.sh
RUN chmod +x /app/omp/scripts/omp-dev.sh

# nginx 配置
COPY nginx/ /etc/nginx/
# 顶层 nginx.conf 在 /etc/nginx/nginx.conf
# 本工程主 location 在 /etc/nginx/conf.d/omp-main.conf
# 业务工程 location 片段在 /etc/nginx/conf.d/omp-services-locations.conf

# 启动脚本
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 80 8080
ENTRYPOINT ["/usr/bin/tini", "--", "/entrypoint.sh"]
```

**entrypoint.sh**：

```bash
#!/bin/bash
set -e
mkdir -p /data/omp/workspaces /data/omp/agent /data/omp/logs /data/omp/maven-repository

# ============================================================================
# 初始化共享 omp 生态目录（运维统一管理 extensions/skills/hooks/tools）
# ----------------------------------------------------------------------------
# /data/omp/agent/ 是持久的共享根，容器重建不丢失。
# entrypoint 在 /root/.omp/agent 上建 symlink → 这里，
# 让 `omp plugin install` 等运维命令直接落到持久化卷。
# Java 端在 spawn() 中（OmpRpcClientFactory.ensureSharedSymlinks）为每个用户
# 在 /data/omp/agent/{user}/ 下建 extensions/skills/hooks/tools 子目录的 symlink
# 指向 ../<subdir>，让 omp 子进程自动发现共享生态。
# ============================================================================
SHARED_AGENT=/data/omp/agent
mkdir -p "$SHARED_AGENT"/extensions \
         "$SHARED_AGENT"/skills \
         "$SHARED_AGENT"/hooks/pre \
         "$SHARED_AGENT"/hooks/post \
         "$SHARED_AGENT"/tools \
         "$SHARED_AGENT"/commands \
         "$SHARED_AGENT"/rules \
         "$SHARED_AGENT"/prompts

# 把 root 用户的 omp agent 目录 symlink 到共享位置（仅当 root 默认 agent 目录不存在时）
ROOT_OMP_AGENT=/root/.omp/agent
if [ ! -e "$ROOT_OMP_AGENT" ]; then
  mkdir -p /root/.omp
  ln -s "$SHARED_AGENT" "$ROOT_OMP_AGENT"
  echo "[entrypoint] /root/.omp/agent → $SHARED_AGENT"
elif [ -L "$ROOT_OMP_AGENT" ] && [ "$(readlink -f "$ROOT_OMP_AGENT")" = "$SHARED_AGENT" ]; then
  : # 已是正确 symlink，无需操作
elif [ ! -L "$ROOT_OMP_AGENT" ] && [ -z "$(ls -A "$ROOT_OMP_AGENT" 2>/dev/null)" ]; then
  # 是空目录 → 改成 symlink，避免两份独立配置漂移
  rmdir "$ROOT_OMP_AGENT"
  ln -s "$SHARED_AGENT" "$ROOT_OMP_AGENT"
  echo "[entrypoint] /root/.omp/agent (empty dir) → $SHARED_AGENT"
else
  echo "[entrypoint] WARN: $ROOT_OMP_AGENT 已有内容，跳过 symlink（保留现有配置）"
fi

# 部署 Maven 全局 settings.xml（镜像内路径已脱敏，localRepository 指向持久化卷）
mkdir -p /root/.m2
cp /etc/omp/maven-settings.xml /root/.m2/settings.xml
echo "[entrypoint] Maven $(/usr/local/maven/bin/mvn --version 2>&1 | head -1)"

# 部署共享 MCP 配置（所有 omp 会话共用）
# Java 端在 spawn 时为每个用户创建 symlink 指向此文件
if [ -f /etc/omp/mcp.json ]; then
  cp /etc/omp/mcp.json /data/omp/agent/mcp.json
  echo "[entrypoint] MCP config → /data/omp/agent/mcp.json"
fi

# 部署共享 APPEND_SYSTEM 模板（运维级 system prompt 注入）。
# Java 端在 spawn 时合并模板 + 用户私有 → /data/omp/agent/{user}/APPEND_SYSTEM.md。
if [ -f /etc/omp/APPEND_SYSTEM.md ]; then
  cp /etc/omp/APPEND_SYSTEM.md /data/omp/agent/APPEND_SYSTEM.template.md
  echo "[entrypoint] APPEND_SYSTEM template → /data/omp/agent/APPEND_SYSTEM.template.md"
fi

# omp CLI 自检（来自安装脚本）
echo "[entrypoint] omp $(omp --version 2>&1 | head -1)"

# CodeGraph 自检（安装到 /root/.local/bin，安装时已加 PATH）
echo "[entrypoint] CodeGraph $(codegraph version 2>&1)"

# 后台初始化已有工作区的 .codegraph/ 索引（不阻塞 nginx/java 启动）
# codegraph init 首次全量索引约 15-40 秒/工作区，后续自动增量同步。
if command -v codegraph &>/dev/null; then
  (
    for ws in /data/omp/workspaces/*/*/; do
      [ -d "$ws" ] || continue
      [ -d "$ws/.codegraph" ] && continue   # 已初始化，跳过
      echo "[entrypoint] codegraph init $ws" &
      codegraph init "$ws" --quiet || true
    done
    wait
  ) &
fi

echo "[entrypoint] 启动 nginx..."
nginx
echo "[entrypoint] 启动后端 java..."
export OMP_BIN=/app/omp/scripts/omp-dev.sh
cd /app
exec /usr/local/jdk21/bin/java -jar /app/omp-backend-0.1.0.jar \
  --spring.config.additional-location=file:/app/prod-application.yml \
  --spring.profiles.active=prod
```

**生产配置 `prod-application.yml`** 关键字段：

```yaml
spring:
  datasource:
    # ⚠️ 容器内用宿主网关访问 MySQL，不是 127.0.0.1
    url: jdbc:mysql://172.17.0.1:3306/omp?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: <db-password>
  jpa:
    hibernate:
      ddl-auto: none
  flyway:
    enabled: false    # 表手动创建，不跑 migration

app:
  omp:
    binary: /app/omp/scripts/omp-dev.sh       # 容器内 omp 入口
    workspaces-root: /data/omp/workspaces     # 与 code-server 容器同路径
    agent-root: /data/omp/agent
    stderr-log-dir: /data/omp/logs
    # 共享 agent 根目录（运维统一管理 extensions/skills/hooks/tools 的入口）。
    # entrypoint 在 /root/.omp/agent 上建 symlink → 此目录。
    # Java spawn 时为每个用户在 agentRoot/{user} 下建子目录 symlink 指向 ../<subdir>。
    shared-agent-root: /data/omp/agent
    # 启用 skills 加载（推荐 true）。false 时追加 --no-skills。
    enable-skills: true
    # 禁用 rules（CLAUDE.md / AGENTS.md）加载 —— 安全考虑，
    # 用户工作区内 rules 不属于 omp 平台运营规则，避免污染会话。
    enable-rules: false
    ide:
      enabled: true
      public-base-url: http://10.126.2.120:5000   # 浏览器访问 code-server 的地址
  security:
    jwt-secret: <64 字符随机值>
    jwt-ttl-hours: 24
    bootstrap-admin:
      username: admin
      password: admin
```

#### 18.4.1 nginx 配置文件拆分(本工程 vs 业务工程)

容器内 nginx 配置文件被拆成 3 份,**职责明确、避免互相干扰**:

| 文件路径 | 职责 | 修改频率 |
|---|---|---|
| `/etc/nginx/nginx.conf` | 顶层调度:events / http / include | 极低,几乎不改 |
| `/etc/nginx/conf.d/omp-main.conf` | **本工程主 location**:web 前端(`/`)+ java 后端(`/api/`、`/admin/`、`/ws/`)+ 业务 location 引用 | 改本工程时 → 重建镜像 |
| `/etc/nginx/conf.d/omp-services-locations.conf` | **业务工程反代 location 片段**。注释模板覆盖 Java/Go/Python/Vue SPA/WebSocket | **日常开发改这个即可,免重建** |

**加载关系**:
```
nginx.conf
  └─ http { include /etc/nginx/conf.d/*.conf; }
       ├─ omp-main.conf        # 定义 server { listen 80; ... include ...-locations.conf; }
       └─ omp-services-locations.conf  # 仅作为 location 片段被 omp-main 引入
```

> ⚠️ `omp-services-locations.conf` 是被 include 进 server 块内部的 location 片段,
> **不能写 `server {}`**,否则 nginx 会报端口冲突。

**开发流程(加业务服务)**:

1. 在容器内启动业务服务监听容器内某端口(9001、9002 等)
2. 进容器 `docker exec -it omp-app bash`
3. `vim /etc/nginx/conf.d/omp-services-locations.conf`,取消对应模板的注释、改路径
4. `nginx -t` 测语法 → `nginx -s reload` 热加载
5. 浏览器访问 `http://<server>:8000/<你的前缀>/...` 测试

**模板覆盖 5 个常见场景**:
- ① Java/Go/Python 纯 API(末尾 `/` strip 路径前缀)
- ② Vue SPA(history 模式 + fallback)
- ③ WebSocket(`Upgrade/Connection` 头 + 拉长 read_timeout)
- ④ 大请求体上传(`client_max_body_size`)
- ⑤ 大文件下载(`proxy_buffering off`)

**重要事项**:
- 容器内 nginx 监听 **80**;外部访问经 `8000:80` 端口映射
- 容器内自测: `curl -sI http://127.0.0.1:80/<前缀>/`
- 容器外自测: `curl -sI http://<server>:8000/<前缀>/`
- 容器内改的 `/etc/nginx/conf.d/omp-services-locations.conf` 不会被外面
  重新部署覆盖,直到下次 `docker run` 重建容器(开发期间无影响,生产前
  应把新增 location 同步到本工程源码 `deploy/nginx/conf.d/` 纳入版本控制)

**nginx.conf**（容器内）：

```nginx
worker_processes 1;
error_log /var/log/nginx/error.log warn;
pid /run/nginx.pid;
events { worker_connections 1024; }
http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    sendfile on;
    keepalive_timeout 65;
    server {
        listen 80;
        server_name _;
        root /app/dist;
        index index.html;
        location / { try_files $uri $uri/ /index.html; }
        location ~* \.(js|css|woff2?|svg|png|ico|jpg|gif)$ {
            expires 7d;
            add_header Cache-Control "public, max-age=604800, immutable";
        }
        location /api/ {
            proxy_pass http://127.0.0.1:8080;
            proxy_http_version 1.1;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
        location /admin/ {
            proxy_pass http://127.0.0.1:8080;
            proxy_http_version 1.1;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        }
        location /ws/ {
            proxy_pass http://127.0.0.1:8080;
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "upgrade";
            proxy_set_header Host $host;
            proxy_read_timeout 86400;
        }
    }
}
```

**构建与启动**：

```bash
# 1) 创建持久化卷（一次性）
docker volume create omp-workspaces
docker volume create omp-agent
docker volume create omp-logs
docker volume create omp-maven

# 2) 构建镜像
cd /home/omp/docker-build
docker build -t omp-allinone:latest .

# 3) 启动容器
docker run -d --restart unless-stopped --name omp-app \
  -p 8000:80 -p 8080:8080 \
  -v omp-workspaces:/data/omp/workspaces \
  -v omp-agent:/data/omp/agent \
  -v omp-logs:/data/omp/logs \
  -v omp-maven:/data/omp/maven-repository \
  omp-allinone:latest
```

### 18.5 code-server 容器（独立）

```bash
docker run -d --restart unless-stopped --name code-server \
  -p 5000:8080 \
  --user root \
  -v omp-workspaces:/data/omp/workspaces \
  -v /home/omp/app/ide/data:/root/.local \
  -v /home/omp/app/ide/config:/root/.config \
  codercom/code-server:latest \
  --auth none --port 8080
```

> ⚠️ **关键约束**：
> 1. **同路径挂载**：`omp-workspaces` 卷必须挂到容器内 `/data/omp/workspaces`，
>    与后端 `app.omp.workspaces-root` 完全一致。否则 IDE 按钮生成的 URL
>    `?folder=/data/omp/workspaces/<user>/<repo>` 在容器内找不到目录。
> 2. **启动命令结尾不能带目录**：`--port 8080` 后面不要追加 `.` 或任何路径，
>    否则工作区会被锁死、`?folder=` 被忽略。
> 3. **`--user root`**：工作区文件由 root 创建时需要。data/config 卷相应
>    挂到 `/root/.local`、`/root/.config`（持久化扩展和设置）。

### 18.6 修复过的关键问题（历史记录）

| 问题 | 修复 |
|---|---|
| 服务器 git 2.27.0 不支持 `git init -b main` | 改为 `git init` + `git branch -M main` |
| omp-dev.sh 指向 macOS 路径 | 重写为 `/usr/local/bin/bun /app/omp/.../cli.ts` |
| code-server `-it --rm` 模式退出即消失 | 改为 `-d --restart unless-stopped` 守护模式 |
| code-server 3000 端口被 one-api-prod 占用 | 改用 5000 端口 |
| code-server 报 "Workspace does not exist" | ① 启动命令结尾误带 `.` 锁死工作区；② 工作区需**同卷同路径挂载**；③ root 创建的文件需 `--user root` |
| 前端浏览器缓存旧版导致页面混乱 | rsync `--delete` 清空旧文件 + 用户 Cmd+Shift+R |
| nginx `rewrite or internal redirection cycle` | SSH heredoc 多层转义把 `$uri` 写成 `\$uri` 字面量；改用 `scp` 上传 nginx.conf |
| 容器连不上 MySQL | 容器内 127.0.0.1 是容器自己 → 改用 `172.17.0.1` 宿主网关 |

---

## 19. 日常更新（120 容器化方案）

### 19.1 改了什么 → 需要做什么

| 改动 | 操作 |
|---|---|
| Java 代码（`backend/src/`） | 本地 `mvn package` → rsync jar → 重建 omp-allinone 镜像 → 重启 omp-app |
| Java 代码（`backend/src/`，**容器内调试**） | 容器内 `cd /app/backend && mvn -DskipTests package` → `docker cp` 出来替换 → 重启 omp-app |
| Vue 代码（`web/src/`） | 本地 `bun run build` → rsync dist → 重建 omp-allinone 镜像 → 重启 omp-app |
| 配置 `prod-application.yml` | scp 到 `/home/omp/docker-build/` → 重建镜像 → 重启 omp-app |
| omp 源码 / omp-dev.sh | rsync omp 目录 → 重建镜像 → 重启 omp-app |
| Rust 代码（`crates/`） | 重交叉编译 .node + 重打 jar → rsync → 重建镜像 → 重启 |

> 数据在 docker volume 里，容器重建不丢数据。改前端/后端时 omp 层 docker 缓存命中，构建很快。

### 19.2 一键更新（前后端）

```bash
SERVER=root@10.126.2.120

# 本地：构建产物
cd /Users/chenzhiwei/work/github/oh-my-pi-main
export JAVA_HOME="/Users/chenzhiwei/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home"
(cd backend && mvn -DskipTests package -q) && \
  rsync -az backend/target/omp-backend-0.1.0.jar $SERVER:/home/omp/docker-build/

(cd web && bun run build) && \
  rsync -az --delete web/dist/ $SERVER:/home/omp/docker-build/dist/

# 服务器：重建镜像 + 重启容器
ssh $SERVER 'cd /home/omp/docker-build && docker build -t omp-allinone:latest . && \
  docker rm -f omp-app && \
  docker run -d --restart unless-stopped --name omp-app \
    -p 8000:80 -p 8080:8080 \
    -v omp-workspaces:/data/omp/workspaces \
    -v omp-agent:/data/omp/agent \
    -v omp-logs:/data/omp/logs \
    -v omp-maven:/data/omp/maven-repository \
    omp-allinone:latest && \
  sleep 10 && docker logs omp-app | tail -3'
```

### 19.3 仅更新前端

```bash
cd /Users/chenzhiwei/work/github/oh-my-pi-main/web && bun run build
# 镜像里前端是 COPY 进去的，必须重建镜像才能让 nginx serve 新内容
rsync -az --delete dist/ root@10.126.2.120:/home/omp/docker-build/dist/
ssh root@10.126.2.120 'cd /home/omp/docker-build && docker build -t omp-allinone:latest . && docker restart omp-app'
```

> ⚠️ **加 Maven 卷后必须走 §19.2 重建容器**，`docker restart` 不会应用新卷挂载，
> 老容器仍然看不到 `/data/omp/maven-repository` 卷。

### 19.4 仅改 prod 配置

```bash
scp prod-application.yml root@10.126.2.120:/home/omp/docker-build/
ssh root@10.126.2.120 'cd /home/omp/docker-build && docker build -t omp-allinone:latest . && docker restart omp-app'
```

### 19.5 运维命令速查

```bash
SERVER=root@10.126.2.120

# 状态
ssh $SERVER 'docker ps --format "{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}" | grep -E "omp-app|code-server"'

# 后端日志（实时）
ssh $SERVER 'docker logs -f omp-app'

# omp 子进程日志（按 session 分文件，在 volume 里）
ssh $SERVER 'docker exec omp-app ls /data/omp/logs/'
ssh $SERVER 'docker exec omp-app tail -f /data/omp/logs/omp-<session-id>.err.log'

# 进容器排查
ssh $SERVER 'docker exec -it omp-app bash'

# 重启 omp-app
ssh $SERVER 'docker restart omp-app'

# 重启 code-server
ssh $SERVER 'docker restart code-server'

# 升级 code-server 镜像（注意：同路径挂载 + 结尾不带 . + --user root）
ssh $SERVER 'docker pull codercom/code-server:latest && \
  docker rm -f code-server && \
  docker run -d --restart unless-stopped --name code-server \
    -p 5000:8080 --user root \
    -v omp-workspaces:/data/omp/workspaces \
    -v /home/omp/app/ide/data:/root/.local \
    -v /home/omp/app/ide/config:/root/.config \
    codercom/code-server:latest --auth none --port 8080'

# 容器内 Maven 版本自检
ssh $SERVER 'docker exec omp-app /usr/local/maven/bin/mvn --version'

# 容器内打后端 jar（源码已 COPY 进 /app/backend）
ssh $SERVER 'docker exec -it -w /app/backend omp-app /usr/local/maven/bin/mvn -DskipTests package'

# 拷新 jar 出来替换 docker-build 里的旧 jar
ssh $SERVER 'docker cp omp-app:/app/backend/target/omp-backend-0.1.0.jar /home/omp/docker-build/omp-backend-0.1.0.jar && docker restart omp-app'

# 看 maven 本地仓库大小（首次冷启 ~0；下完依赖后约 300–500 MB）
ssh $SERVER 'docker exec omp-app du -sh /data/omp/maven-repository'

# 清理 maven 本地仓库（强制重新下载）
ssh $SERVER 'docker exec omp-app rm -rf /data/omp/maven-repository/*'

# CodeGraph 版本自检
ssh $SERVER 'docker exec omp-app codegraph version'

# CodeGraph 索引状态（各工作区图的文件数/符号数）
ssh $SERVER 'for ws in /data/omp/workspaces/*/*/; do echo "--- $ws"; docker exec omp-app codegraph status "$ws" 2>/dev/null || echo "  (未初始化)"; done'

# 强制重建某个工作区的 CodeGraph 索引
ssh $SERVER 'docker exec omp-app codegraph index /data/omp/workspaces/uid/repo --force'

# 看 .codegraph/ 索引文件总大小
ssh $SERVER 'docker exec omp-app du -sh /data/omp/workspaces/*/*/.codegraph'

# 备份 workspaces 卷
ssh $SERVER 'docker run --rm -v omp-workspaces:/data -v /home/omp/backup:/backup \
  ubuntu tar -czf /backup/workspaces-$(date +%Y%m%d).tar.gz -C /data .'

# 备份 code-server 数据（扩展、设置）
ssh $SERVER 'tar -czf /home/omp/backup/code-server-data-$(date +%Y%m%d).tar.gz /home/omp/app/ide/'
```

### 19.6 容器内 Maven / CodeGraph 自检流程

镜像带 Maven 3.9.9 + JDK 21（来自 `/usr/local/jdk21`）+ CodeGraph 后，可走容器内自检：

```bash
ssh root@10.126.2.120

# 1) Maven 版本（应输出 Apache Maven 3.9.9 + Java version: 21.x）
docker exec omp-app /usr/local/maven/bin/mvn --version

# 2) settings.xml 已部署到 /root/.m2/（localRepository 指向持久化卷）
docker exec omp-app cat /root/.m2/settings.xml | grep -E "localRepository|mirror"

# 3) 卷挂载生效（首启空目录；跑过 mvn 后会有依赖目录）
docker exec omp-app ls -la /data/omp/maven-repository

# 4) 端到端构建测试（需先把 backend 源码 COPY 进镜像或挂到 /app/backend）
docker exec -it -w /app/backend omp-app /usr/local/maven/bin/mvn -DskipTests package

# 5) 看仓库大小（下完 Spring 全家桶后约 300–500 MB）
docker exec omp-app du -sh /data/omp/maven-repository

# 6) CodeGraph 版本（应输出版本号）
docker exec omp-app codegraph version

# 7) 查看工作区 CodeGraph 索引状态（符号数/文件数）
docker exec omp-app codegraph status /data/omp/workspaces/<uid>/<repo>

# 8) 测试 codegraph explore（不指定工作区也行，MCP server 自动识别）
docker exec omp-app codegraph explore "OmpProcessSpec" --path /data/omp/workspaces/<uid>/<repo>
```

### 19.7 服务器端 `maven-settings.xml` 模板

放在 `/home/omp/docker-build/maven-settings.xml`，被 Dockerfile `COPY` 进镜像
`/etc/omp/maven-settings.xml`，entrypoint.sh 启动时再 cp 到 `/root/.m2/settings.xml`。

与本机 `~/.m2/settings.xml` 的差异：本地仓库路径替换为容器内路径，
其余（aliyun 镜像、spring milestones、verapdf 仓库）保留以便内外一致。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">

  <!-- 本地仓库路径：容器内持久化卷挂载点 -->
  <localRepository>/data/omp/maven-repository</localRepository>

  <pluginGroups>
  </pluginGroups>

  <proxies>
  </proxies>

  <!-- 已清空所有账号密码（镜像为公共制品，无凭据） -->
  <servers>
  </servers>

  <!-- 阿里云镜像（下载速度飞快） -->
  <mirrors>
    <mirror>
      <id>aliyunmaven</id>
      <mirrorOf>external:*,!verapdf-release</mirrorOf>
      <name>阿里云公共仓库</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>

  <profiles>
    <!-- Spring 里程碑仓库 -->
    <profile>
      <id>spring</id>
      <repositories>
        <repository>
          <id>spring-milestones</id>
          <name>Spring Milestones</name>
          <url>https://repo.spring.io/milestone</url>
        </repository>
      </repositories>
    </profile>
    <!-- veraPDF 仓库（aliyun 镜像未覆盖，按原仓直连） -->
    <profile>
      <id>verapdf-repo</id>
      <repositories>
        <repository>
          <id>verapdf-release</id>
          <name>veraPDF Release</name>
          <url>https://artifactory.openpreservation.org/artifactory/verapdf-release</url>
        </repository>
      </repositories>
    </profile>
  </profiles>

  <activeProfiles>
    <activeProfile>spring</activeProfile>
    <activeProfile>verapdf-repo</activeProfile>
  </activeProfiles>
</settings>
```

> **修改后部署**：本模板更新后无需重建镜像——直接 `docker cp` 进运行中容器即可：
> ```bash
> ssh root@10.126.2.120 'docker cp /home/omp/docker-build/maven-settings.xml \
>   omp-app:/etc/omp/maven-settings.xml && \
>   docker exec omp-app cp /etc/omp/maven-settings.xml /root/.m2/settings.xml'
> ```

---

## 20. CodeGraph 使用说明

omp-app 镜像在构建阶段通过 **`npm install -g @colbymchenry/codegraph`** 安装了 CodeGraph CLI（`/usr/local/bin/codegraph`，依赖前面装好的 Node 22 LTS）。
omp agent 在容器内执行代码任务时，可借助它获得比 grep/glob/Read 更精准的上下文。

### 20.1 是什么 & 为什么用

**CodeGraph** 是基于 tree-sitter AST 的本地代码知识图：每个符号（函数/方法/类）、每条调用边、每个依赖都编译期抽取并落到 SQLite（`.codegraph/codegraph.db`），配合 FTS5 全文索引。
omp agent 在容器内调用 `codegraph_explore` 时，一次返回：

- 相关符号的**逐字源码**（按文件分组）
- 它们之间的**调用路径**（含动态分发跳转：回调、React 重渲染、interface→impl）
- 改动的**爆炸半径**（callers + callees + 反向依赖）

收益：在每个仓库上都得到 **~58% 更少的工具调用**、**~22% 更快**、**file reads 接近 0**（来源：colbymchenry/codegraph README 基准测试）。

**为什么在容器里也装**：omp 用户的代码仓库（工作区）就在容器卷里。在开发机上跑 CodeGraph 是给开发者的 Claude Code 用；在容器里跑是给 omp spawn 出来的 omp agent 用。**两个场景的索引互相独立**——开发机的 `.codegraph/` 与容器内同一份工作区的 `.codegraph/` 是不同 OS 上生成的 SQLite，不能混用。

### 20.2 在容器里怎么用

#### 容器内 CLI

CodeGraph CLI 装在 `/root/.local/bin/codegraph`，已加到 PATH（Dockerfile 第 813 行 `ENV PATH=/root/.local/bin:$PATH`）。
容器内直接调用：

```bash
docker exec omp-app codegraph version               # 版本自检
docker exec omp-app codegraph status /data/omp/workspaces/<uid>/<repo>   # 索引状态
docker exec omp-app codegraph explore "OmpProcessSpec" --path /data/omp/workspaces/<uid>/<repo>   # 单次探索
docker exec omp-app codegraph node "OmpRpcClient" --path /data/omp/workspaces/<uid>/<repo>        # 单个符号
docker exec omp-app codegraph callers "spawnOmp" --path /data/omp/workspaces/<uid>/<repo>         # 调用方
docker exec omp-app codegraph impact "OmpProcessSpec" --path /data/omp/workspaces/<uid>/<repo>    # 爆炸半径
```

> `--path` 是工作区根（包含 `.codegraph/` 的目录）。省略时 CLI 自动从 cwd 向上找最近的 `.codegraph/` 父目录。

#### omp agent 集成

omp 后端 spawn omp 子进程时（参见 `OmpProcessSpec.toArgv`），已经把工作区 cwd 设到 `/data/omp/workspaces/<uid>/<repo>`。
omp agent 在容器内执行 shell 调用时，会自动 `cd` 到该工作区——而 CodeGraph 索引就在工作区根的 `.codegraph/`，**无需显式指定**：

```bash
# omp agent 在容器内类似这样调用：
cd /data/omp/workspaces/uid/repo
codegraph explore "OmpProcessSpec"   # 自动找到 ./.codegraph/codegraph.db
```

#### MCP server（如果未来需要）

当前 omp agent 没有直接挂 CodeGraph MCP server。如需启用：

1. 容器内跑 `codegraph install`（交互式或 `--target=omp --location=global`）
2. omp 后端 spawn 时加 `--extension` 或 `--mcp-server` 参数（取决于 omp 的 MCP 接入方式）

> 当前 §18.4 Dockerfile 只装了 CLI，**没跑 `codegraph install`**——因为 omp agent 的 MCP 配置不在本次改动范围。如需要，单独追加 1 行 `RUN codegraph install --yes`。

### 20.3 索引生命周期

```
┌─────────────────────────────────────────────────────────────┐
│  codegraph init <workspace>     首次全量索引（15-40 秒/工作区）│
│         │                                                    │
│         ▼                                                    │
│  .codegraph/codegraph.db       SQLite + FTS5（自动持续同步）  │
│         │                                                    │
│         │  file watcher（FSEvents/inotify）                  │
│         │  debounce ~2 秒后增量更新                          │
│         ▼                                                    │
│  长期运行；agent 每次 explore 拿到的都是最新图               │
└─────────────────────────────────────────────────────────────┘
```

**关键事实**：
- 索引位置：`<workspace>/.codegraph/`（**每个工作区独立**）
- 自动同步：默认开启（agent 编辑文件 ~2 秒后图更新）
- 与工作区同寿命：删工作区 = 删索引；不需要单独清理
- 跨 OS 不互通：开发机（macOS）与容器内（Linux）的 `.codegraph/` 是**不同 OS 上生成的 SQLite**，**不能拷来拷去**；同一份 checkout 要么在这台机器用、要么在那台机器用，分别 `codegraph init`。
- 默认排除：`node_modules/`、`dist/`、`build/`、`target/`、`.venv/`、`.git/`、单文件 > 1 MB（vendored blob）；具体见 CodeGraph README

#### entrypoint.sh 自动化

§18.4 entrypoint.sh 第 939–947 行在容器启动时**后台异步**对所有已有工作区执行 `codegraph init`：

```bash
if command -v codegraph &>/dev/null; then
  (
    for ws in /data/omp/workspaces/*/*/; do
      [ -d "$ws" ] || continue
      [ -d "$ws/.codegraph" ] && continue   # 已初始化，跳过
      codegraph init "$ws" --quiet || true
    done
    wait
  ) &
fi
```

- **不阻塞** nginx/java 启动（子 shell 后台跑）
- 已初始化的跳过（增量同步继续工作）
- 新工作区首次创建后，下次容器重启时自动 init；**无需手动跑**

#### 手动重建索引

```bash
# 完整重建某个工作区（图被破坏或大改后）
docker exec omp-app codegraph index /data/omp/workspaces/<uid>/<repo> --force

# 解锁（极端情况：stale lock 阻止索引）
docker exec omp-app codegraph unlock /data/omp/workspaces/<uid>/<repo>

# 看索引状态
docker exec omp-app codegraph status /data/omp/workspaces/<uid>/<repo>
```

#### 会话级触发（OmpRpcClientFactory.ensureCodeGraphIndexed）

除了 entrypoint.sh 容器启动时的批量 init，**每次新会话 spawn 也会刷新索引**（Java 端 `OmpRpcClientFactory.spawn()` 中调用 `ensureCodeGraphIndexed(workspace)`）：

| 工作区状态 | 触发命令 | 频率 |
|---|---|---|
| 无 `.codegraph/` | `codegraph init -i`（init + 首次全量 index）| 立即 |
| 有 `.codegraph/`，db mtime ≥ 30s | `codegraph index --quiet`（全量重扫，兜底 watcher）| 立即 |
| 有 `.codegraph/`，db mtime < 30s | 跳过 | 30s throttle，防同工作区频繁会话重复 |

后台 daemon 线程异步执行，**不阻塞** omp spawn。codegraph 命令失败（不在 PATH、进程崩）
log warn 兜底，omp 仍能正常启动；agent 调 codegraph_explore 时 MCP 层自动 fallback。

### 20.4 命令速查

#### 容器内（运维）

```bash
# 版本
docker exec omp-app codegraph version

# 看所有工作区索引状态
ssh root@10.126.2.120 'for ws in /data/omp/workspaces/*/*/; do \
  echo "--- $ws"; \
  docker exec omp-app codegraph status "$ws" 2>/dev/null || echo "  (未初始化)"; \
done'

# 索引大小
docker exec omp-app du -sh /data/omp/workspaces/*/*/.codegraph

# 重建
docker exec omp-app codegraph index /data/omp/workspaces/<uid>/<repo> --force

# 卸载（删 .codegraph/，下次 init 重生）
docker exec omp-app rm -rf /data/omp/workspaces/<uid>/<repo>/.codegraph
```

#### 容器内（开发调试）

```bash
# 一次性探索
docker exec omp-app codegraph explore "How does spawnOmp work" --path /data/omp/workspaces/<uid>/<repo>

# 找所有调用某方法的地方
docker exec omp-app codegraph callers "spawnOmp" --path /data/omp/workspaces/<uid>/<repo>

# 改某方法的影响范围
docker exec omp-app codegraph impact "OmpProcessSpec" --path /data/omp/workspaces/<uid>/<repo>

# 单文件 cat（带行号）
docker exec omp-app codegraph node "src/main/java/.../Foo.java" --path /data/omp/workspaces/<uid>/<repo>

# 只输出路径（CI 友好）
docker exec omp-app codegraph affected --stdin --quiet < <(echo "src/Foo.ts")
```

### 20.5 故障排查

| 症状 | 原因 | 处理 |
|---|---|---|
| `CodeGraph not initialized` | 工作区还没 `codegraph init` | 重启容器（自动后台 init）或手动 `docker exec omp-app codegraph init <ws>` |
| 索引很久没更新 | 文件 watcher 死了 | `docker exec omp-app codegraph status <ws>` 看状态；`codegraph sync <ws>` 强制一次增量同步 |
| `database is locked` | 旧版（< 0.9）install，bundled runtime 缺失 | `npm i -g @colbymchenry/codegraph@latest` 升级；或重装 |
| `Journal: other than wal` | 文件系统不支持 WAL（network share、WSL2 /mnt） | 把工作区迁到本地盘；或换 OS-local 的 CODEGRAPH_DIR |
| 缺失某符号 | 文件在 `.gitignore` 里 / 默认排除目录 / 不支持的语言 | `codegraph index --force` 重跑；检查 `.gitignore`；参考 README 的支持语言列表 |
| 跨 OS 拷索引发现冲突 | SQLite 跨 Windows/WSL 不安全 | 同份 checkout 在两台机器上分别 `codegraph init`；或用 `CODEGRAPH_DIR=.codegraph-win` 区分 |
| 镜像里没 `codegraph` 命令 | npm install 失败（npmmirror 网络/版本落后） | 手动 `docker exec omp-app npm i -g @colbymchenry/codegraph@1.0.1` 重装 |
| omp agent 调不到图 | 工作区 `.codegraph/` 不存在 | 看 §20.3 entrypoint.sh 自动 init 段；或手动 `docker exec omp-app codegraph init <ws>` |
| **agent 不调 `codegraph_*` 工具**（已确认 MCP 连接成功） | 工作区是空目录 / 只有 README / agent 已用 `read` 看到非代码内容 | 切换到有真实代码的工作区；或在 task 里明确点名（"用 codegraph_search 找一下 OmpRpcClient"） |
| **agent 不调 `codegraph_*` 工具**（即使工作区有索引） | LLM 选了 grep/find/Read | 检查 `APPEND_SYSTEM.md` 是否被加载（容器内：`docker exec omp-app cat {agentDir}/APPEND_SYSTEM.md`）；它必须指明优先级 |
| `Connecting to MCP servers: ...` 卡在 omp 启动 stderr | MCP discovery 还没完成 fast startup gate（250ms）| omp 已用 `DeferredMCPTool` 占位异步连接，**不会** 真阻塞——继续等几秒 LLM 第一次调工具时就 ready |

### 20.6 与开发机的关系

- **开发机**（macOS）跑 Claude Code + CodeGraph：索引在 `~/.codegraph/.../your-project/.codegraph/`
- **容器内**（Linux）跑 omp agent + CodeGraph CLI：索引在 `/data/omp/workspaces/<uid>/<repo>/.codegraph/`
- 同一份 git checkout 在两边都被各自的 `.codegraph/` 索引；**互不干扰**
- 开发机 `.gitignore` 应加上 `.codegraph/`（CodeGraph 默认已自动排除，但显式写更稳）

### 20.7 卸载

如果某天不想用 CodeGraph：

**容器内**（不影响镜像）：
```bash
ssh root@10.126.2.120 'for ws in /data/omp/workspaces/*/*/; do \
  docker exec omp-app rm -rf "$ws/.codegraph"; \
done'
```

**镜像内**（从 Dockerfile 删 1 行 + 可选删 2 个模板 COPY）：
```dockerfile
# 删掉这一段（CodeGraph npm 安装段，§18.4 Dockerfile 副本）：
ARG CODEGRAPH_VERSION=1.0.1
RUN npm config set registry https://registry.npmmirror.com \
    && npm install -g @colbymchenry/codegraph@${CODEGRAPH_VERSION} \
    && codegraph --version

# 可选：删模板（不删也无害，但留着没用）：
COPY mcp.json /etc/omp/mcp.json
COPY APPEND_SYSTEM.md /etc/omp/APPEND_SYSTEM.md
```

重建镜像后，新工作区不再自动 init；老的 `.codegraph/` 留在卷里无害（CodeGraph 不存在时 OMP agent 退化为 grep/glob）。

### 20.8 L2 集成：CodeGraph MCP server（让 omp agent 自动调用 codegraph_explore）

§20.1–20.7 装的是 **CodeGraph CLI**，容器内运维人员可以手工 `codegraph explore ...`。
**§20.8 让 omp agent 在每次会话里自动得到 `mcp__codegraph__codegraph_explore` 工具**——和你在开发机上的 Claude Code 体验一致。

#### 工作原理

omp agent 启动时通过 `PI_CODING_AGENT_DIR` 环境变量定位 user-scope 配置目录（默认 `~/.omp/agent/`，
本工程在 Java 端注入为 `/data/omp/agent/{username}/`）。omp 在这个目录下查找 **`mcp.json`** 自动发现
MCP servers，查找 **`APPEND_SYSTEM.md`** 把内容追加到每次会话的 system prompt。

**共享 MCP 配置**：所有用户共用同一份 `mcp.json`，统一维护在 `agentRoot/mcp.json`（如 `/data/omp/agent/mcp.json`）。
Java 后端在 `OmpRpcClientFactory.spawn()` 为每个用户创建 symlink：

```
agentRoot/mcp.json              ← 唯一维护点（共享）
agentRoot/{username1}/mcp.json  →  symlink → ../mcp.json
agentRoot/{username2}/mcp.json  →  symlink → ../mcp.json
```

```java
ensureGlobalMcpSymlink(props.agentRoot(), agentDir);
```

`ensureGlobalMcpSymlink` 行为：
1. `agentRoot/mcp.json` 不存在 → 静默跳过（开发机未放共享配置时不阻塞 omp 启动）
2. 已有正确 symlink → 不重建
3. 旧文件/旧链接 → 删除后重建 symlink `../mcp.json`
4. symlink 创建失败 → 不抛（Windows 不支持/权限不够时退化，omp 启动时不带 MCP）

#### 服务器端 `mcp.json` 共享配置

放在 `/home/omp/docker-build/mcp.json`，被 Dockerfile `COPY` 到 `/etc/omp/mcp.json`。
容器启动时 entrypoint 复制到 `/data/omp/agent/mcp.json`（共享路径）。

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
      }
    }
  }
}
```

**关键点**：
- `command: codegraph` —— 容器内 PATH 已含 `/root/.local/bin`（§18.4 Dockerfile 行 818）
- `args: ["serve", "--mcp"]` —— 子命令 `codegraph serve --mcp` 启动 stdio MCP server（`codegraph serve --help` 确认）
- **没有 `cwd` 字段** —— codegraph MCP server 通过 MCP `roots/list` 协议从客户端拿工作目录，omp 的 MCP client 在 initialize 阶段已声明 `roots` capability（`packages/coding-agent/src/mcp/client.ts:101`），自动闭环
- `CODEGRAPH_MCP_TOOLS` —— 默认只暴露 `explore`，这里全开 6 个工具给 omp agent 用

#### 服务器端 `APPEND_SYSTEM.md` 模板

放在 `/home/omp/docker-build/APPEND_SYSTEM.md`，被 Dockerfile `COPY` 到 `/etc/omp/APPEND_SYSTEM.md`。
Java 端在 spawn 时按用户合并生成 `{agentRoot}/{username}/APPEND_SYSTEM.md`（支持用户私有覆盖）。

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

> **修改后部署**：共享 MCP 配置更新后无需重建镜像——直接 `docker cp` 到共享路径即可：
> ```bash
> ssh root@10.126.2.120 'docker cp /home/omp/docker-build/mcp.json \
>   omp-app:/data/omp/agent/mcp.json'
> ```
> 所有用户的下次会话即时生效（symlink 指向同一份文件）。

#### 验证

```bash
# 1) 容器内 codegraph serve --mcp 子命令可用
docker exec omp-app codegraph serve --help | grep -- --mcp

# 2) 共享 mcp.json 存在
docker exec omp-app ls -la /data/omp/agent/mcp.json

# 3) 每个用户 agentDir 下是 symlink（不是拷贝）
docker exec omp-app ls -la /data/omp/agent/<username>/mcp.json
# 预期输出: lrwxrwxrwx  ...  mcp.json -> ../mcp.json

# 4) omp agent 进程跑起来后能看到 codegraph serve 子进程
docker exec omp-app sh -c 'ps -ef | grep -E "codegraph serve|omp" | grep -v grep'

# 5) omp session 日志里看 MCP discovery
docker logs omp-app 2>&1 | grep -i 'mcp\|codegraph'

# 6) 让 agent 跑一次"理解某模块"的对话，验证它调了 mcp__codegraph__codegraph_explore
#    （从前端会话面板看 tool_call 流；或后端日志 [omp→OUT] tool_call name=mcp__codegraph__...）
```

#### 失败兜底

- **codegraph 二进制 broken** → MCP server 启动失败 → omp 的 fast startup gate（250ms）超时 → `DeferredMCPTool` 占位 + 错误记录到 `errors` map → **omp 正常启动**，agent 调 codegraph 工具时返回 error，LLM 自动 fallback 到 `search`/`find`/`read`
- **共享 mcp.json 不存在** → symlink 创建跳过 → omp 启动时不带 CodeGraph MCP（开发机正常）
- **`.codegraph/` 索引不存在** → MCP server 启动成功但 explore 返回空 → LLM 看到空结果会改用其他工具
- **codegraph 重连风暴**：omp 已有 30s 滑窗 + burst 5 次的熔断（`packages/coding-agent/src/mcp/manager.ts:80-81`），不会拖垮 session

#### 与 §20.7 卸载的关系

§20.7 卸载的是 **CLI**——`/root/.local/bin/codegraph` 二进制。卸载后 §20.8 的 MCP server 自动失效
（`command: codegraph` 找不到），omp agent 退化到不含 CodeGraph 的工具集。

只卸载 §20.8（保留 CLI 给运维用），删 3 处：
1. Dockerfile 删 `COPY mcp.json /etc/omp/...` 和 `COPY APPEND_SYSTEM.md /etc/omp/...` 两行
2. entrypoint 删 `cp /etc/omp/mcp.json /data/omp/agent/mcp.json` 这段
3. `OmpRpcClientFactory.java` 删 `ensureGlobalMcpSymlink(...)` 调用

#### 20.8.1 镜像构建路径变迁

CodeGraph 安装方式演进过 3 版，**当前生效 = 方案 C（npm 全局安装）**。前两版的踩坑记录保留以便复盘。

| 方案 | 状态 | 命令 | 问题 |
|---|---|---|---|
| A. 官方 install.sh | ❌ 弃用 | `RUN curl -fsSL https://raw.githubusercontent.com/.../install.sh \| sh` | 国内直连 GitHub raw SSL_read EOF；`gh-proxy.com` 中转 522/524 不稳定 |
| B. 预编译 tarball + COPY | ❌ 弃用 | 开发机 `curl -o codegraph-linux-x64.tar.gz` + scp + Dockerfile `COPY + tar + ln -s` | 升级要重 scp 50 MB；tarball 路径不直观（`codegraph-linux-x64/bin/codegraph` + `lib/`） |
| **C. npm 全局安装**（当前）| ✅ 生效 | `RUN npm config set registry https://registry.npmmirror.com && npm i -g @colbymchenry/codegraph@${VERSION}` | 走 npmmirror 国内稳；升级只改 ARG，不需 scp |

**当前 Dockerfile 关键片段**（已在 §18.4 Dockerfile 副本中固化）：

```dockerfile
# Node 22 先装好（CodeGraph 依赖它）
ARG NODE_VERSION=22.11.0
RUN curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.gz" \
      -o /tmp/node.tar.gz && tar -C /usr/local -xzf /tmp/node.tar.gz --strip-components=1 \
    && rm /tmp/node.tar.gz && node --version && npm --version

# CodeGraph 走 npm 全局安装（国内 npmmirror 镜像，稳定）
ARG CODEGRAPH_VERSION=1.0.1
RUN npm config set registry https://registry.npmmirror.com \
    && npm install -g @colbymchenry/codegraph@${CODEGRAPH_VERSION} \
    && codegraph --version
```

**升级路径**：

```bash
# 方式 1：改 Dockerfile（推荐，可复现）
sed -i 's|CODEGRAPH_VERSION=1.0.1|CODEGRAPH_VERSION=1.0.2|' /home/omp/docker-build/Dockerfile
docker build -t omp-allinone:latest /home/omp/docker-build && docker restart omp-app

# 方式 2：进容器临时升级（验证用，重建镜像会丢失）
docker exec omp-app npm i -g @colbymchenry/codegraph@latest && docker exec omp-app codegraph --version
```

**其他配套踩坑修正**（同 Dockerfile 中，已固化）：

| 包 | 不可用 | 当前可用 |
|---|---|---|
| Maven | `gh-proxy.com/archive.apache.org/...` 524 | 直连 `archive.apache.org`（apache 国内可访问） |
| Go | `gh-proxy.com/go.dev/...` / `go.dev` 直连都不稳 | `mirrors.aliyun.com/golang/...` |

### 20.9 本地验证记录（2026-06-23）

L2 集成在**开发机 macOS + 本地 backend + 本地 omp**端到端走通，证据如下：

#### 验证步骤
1. 本地启动 backend（`mvn spring-boot:run` 或 IDEA debugger），并配置 `OMP_BIN=/Users/<you>/bin/omp`
2. 在共享路径放置 mcp.json（Java 端检测 `agentRoot/mcp.json` 存在后创建 symlink）：
   ```bash
   cat > /tmp/omp/agent/mcp.json << 'EOF'
   {
     "mcpServers": {
       "codegraph": {
         "type": "stdio",
         "command": "codegraph",
         "args": ["serve", "--mcp"],
         "env": { "CODEGRAPH_MCP_TOOLS": "explore,context,node,search,callers,impact" }
       }
     }
   }
   EOF
   ```
3. 选一个工作区跑 `codegraph init` + `codegraph index`（注：需要工作区**真有源代码文件**，README + index.html 类的空模板不会触发 `No files found to index`）
4. 前端创建会话 → 触发 omp 子进程

#### 关键证据

| 检查项 | 实际输出 |
|---|---|
| `cat /tmp/omp/logs/omp-<session-id>.err.log` | **`Connecting to MCP servers: codegraph, cdp-bridge, ...`** ← codegraph 在列表里 |
| `ps aux \| grep "codegraph serve --mcp"` | 看到多个 `node .../codegraph serve --mcp` 子进程（一个 omp session 一个） |
| stdio 直接握手测试（验证 MCP 协议层）| `codegraph serve --mcp` 接收 `tools/list` 返回 9 个 `codegraph_*` 工具：search / context / callers / callees / impact / node / explore / status / files |

#### stdio 直接握手测试命令

跳过 omp 中间层，直接验证 MCP 协议层是否正常：

```bash
cd /path/to/workspace-with-codegraph-index
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{"roots":{"listChanged":false}},"clientInfo":{"name":"test","version":"1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | codegraph serve --mcp 2>/dev/null
# 预期：返回包含 9 个 codegraph_* 工具的 JSON
```

#### 失败但已确认非 MCP 问题

| 现象 | 真因 | 不算 bug |
|---|---|---|
| Agent 第一次会话用 `read` + `find` 而非 `codegraph_explore` | 工作区目录只有 `.gitkeep` 占位文件，没有真实代码 | LLM 通过 `read` 几次就发现目录是空的，正确决策；要换成有真实代码的工作区 |
| 本地 `OmpRpcClientFactory.ensureGlobalMcpSymlink` 没建链接 | macOS 上 `/etc/omp/mcp.json` 不存在 → `agentRoot/mcp.json` 也不存在 → 静默跳过（设计如此） | 开发机预期就是手工放 `agentRoot/mcp.json`，Java 端自动创建 symlink |

#### 与服务器部署的关系

本地走通 = **MCP 协议 / Java 改动 / 系统提示注入** 三层都没问题。
服务器 L2 完整生效还需要 §20.8.1 的镜像构建踩坑修正落到位（已在 §18.4 Dockerfile 副本中固化）。

