# 服务端部署 Runbook — oh-my-pi / omp

> 本文档记录把 **本工程 omp（bun + Rust N-API + Java 后端 + Vue 前端）** 部署到 Linux x86_64 服务器的完整流程。

> [!NOTE] **本指南适用于任何全新的 Linux x86_64 服务器**。唯一需要从开发机带过去的、**不在本指南里列出的**，就是工程源码本身（`git clone` 或 rsync）。

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
