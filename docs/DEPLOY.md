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
├── jdk21/                     # JDK 21（打进镜像）
├── bun                        # bun 二进制（打进镜像）
├── omp-backend-0.1.0.jar      # 后端 fat-jar
├── dist/                      # 前端静态产物
└── omp/                       # omp 源码 + node_modules + linux .node
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
# 合计：镜像增加约 2.2 GB。生产实例不需要这些，dev-only。
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

# 部署 Maven 全局 settings.xml（镜像内路径已脱敏，localRepository 指向持久化卷）
mkdir -p /root/.m2
cp /etc/omp/maven-settings.xml /root/.m2/settings.xml
echo "[entrypoint] Maven $(/usr/local/maven/bin/mvn --version 2>&1 | head -1)"

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

# 备份 workspaces 卷
ssh $SERVER 'docker run --rm -v omp-workspaces:/data -v /home/omp/backup:/backup \
  ubuntu tar -czf /backup/workspaces-$(date +%Y%m%d).tar.gz -C /data .'

# 备份 code-server 数据（扩展、设置）
ssh $SERVER 'tar -czf /home/omp/backup/code-server-data-$(date +%Y%m%d).tar.gz /home/omp/app/ide/'
```

### 19.6 容器内 Maven 自检流程

镜像带 Maven 3.9.9 + JDK 21（来自 `/usr/local/jdk21`）后，可走容器内自检：

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

