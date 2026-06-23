# Dockerfile.allinone — 120 UAT 服务器 All-in-One 镜像

> 本目录的 `Dockerfile.allinone.base` + `Dockerfile.allinone` 是 **10.126.2.120 UAT 服务器**用的 all-in-one 镜像构建文件，
> 取代了直接在 120 上手维护的 `/home/omp/docker-build/Dockerfile*`。
> 仓库与服务器同步以此处为准 —— 改了之后 rsync 上去重建即可。

---

## 镜像分层

```
omp-allinone-base:latest  (~2.5 GB, 不常改)
├── Ubuntu 22.04 (tencent 镜像源)
├── JDK 21 / Bun / Maven / CodeGraph / RTK
├── Go / Rust (清华镜像) / Node / Python / uv
└── nginx / tini / vim / jq / build-essential ...
        ↓ FROM
omp-allinone:latest       (~100 MB, 每次发版重建)
├── omp-backend-0.1.0.jar  (后端 jar)
├── /app/dist              (PC 前端)
├── /app/h5                (H5 移动端，挂载在 80 端口的 /h5/)
├── /app/omp               (omp 源码 + node_modules)
├── prod-application.yml   (Spring Boot 生产配置)
├── nginx/                 (容器内 nginx 全套配置)
└── entrypoint.sh
```

### 暴露端口

| 端口 | 用途 |
|---|---|
| 80 | nginx 前端（PC 端 + H5 + API 反代） |
| 8080 | Java 后端（容器内直连，可调试） |
| 8888 | 业务工程反代预留（omp-h5.conf 监听） |

---

## 构建流程

### 首次部署（构建 base + 业务）

```bash
# 0. 准备产物到 /home/omp/docker-build/（本地打包好后 rsync 上去）
#    需要的文件：
#      jdk21/                      JDK tar 解压目录
#      bun                         bun 二进制
#      codegraph-linux-x64.tar.gz  CodeGraph tarball
#      mcp.json                    CodeGraph MCP 配置
#      APPEND_SYSTEM.md            CodeGraph system prompt
#      maven-settings.xml          Maven 全局镜像配置
#      omp-backend-0.1.0.jar       后端打包产物
#      dist/                       web/dist
#      h5/                         uniapp/dist/build/h5
#      omp/                        omp 源码 + node_modules
#      prod-application.yml        Spring Boot 配置
#      omp-dev.sh                  omp 入口脚本
#      nginx/                      nginx 配置（nginx.conf + conf.d/）
#      entrypoint.sh               容器启动脚本
#      Dockerfile.base
#      Dockerfile

# 1. 构建 base 镜像（首次 5-10 min，含多语言开发环境）
cd /home/omp/docker-build
docker build -f Dockerfile.base -t omp-allinone-base:latest .

# 2. 构建业务镜像（10-30 s，base 缓存命中）
docker build -t omp-allinone:latest .

# 3. 启动容器
docker rm -f omp-app 2>/dev/null
docker run -d --restart unless-stopped --name omp-app \
  -p 8000:80 -p 8080:8080 -p 8888:8888 \
  -v omp-workspaces:/data/omp/workspaces \
  -v omp-agent:/data/omp/agent \
  -v omp-logs:/data/omp/logs \
  -v omp-maven:/data/omp/maven-repository \
  omp-allinone:latest
```

### 日常发版（只改业务代码）

```bash
# 本地打包
cd backend && mvn -DskipTests package -q
cd web && bun run build
cd uniapp && yarn build:h5

# rsync 到 120
SERVER=root@10.126.2.120
rsync -az backend/target/omp-backend-0.1.0.jar $SERVER:/home/omp/docker-build/
rsync -az --delete web/dist/ $SERVER:/home/omp/docker-build/dist/
rsync -az --delete uniapp/dist/build/h5/ $SERVER:/home/omp/docker-build/h5/

# 只重建业务镜像（base 缓存命中，10-30s 完成）
ssh $SERVER 'cd /home/omp/docker-build && docker build -t omp-allinone:latest . && \
  docker rm -f omp-app && \
  docker run -d --restart unless-stopped --name omp-app \
    -p 8000:80 -p 8080:8080 -p 8888:8888 \
    -v omp-workspaces:/data/omp/workspaces \
    -v omp-agent:/data/omp/agent \
    -v omp-logs:/data/omp/logs \
    -v omp-maven:/data/omp/maven-repository \
    omp-allinone:latest'
```

### 环境层有变更（很少需要）

只要改了 `Dockerfile.allinone.base`（升级 JDK / 加新工具等），需要重建 base：

```bash
# 1. 同步 base Dockerfile
rsync -az Dockerfile.allinone.base $SERVER:/home/omp/docker-build/Dockerfile.base

# 2. 重建 base + 业务
ssh $SERVER 'cd /home/omp/docker-build && \
  docker build -f Dockerfile.base -t omp-allinone-base:latest . && \
  docker build -t omp-allinone:latest .'
```

---

## 关键设计点

### Rust 工具链镜像（解决 sh.rustup.rs 慢的坑）

```dockerfile
ENV RUSTUP_DIST_SERVER=https://mirrors.tuna.tsinghua.edu.cn/rustup \
    RUSTUP_UPDATE_ROOT=https://mirrors.tuna.tsinghua.edu.cn/rustup/rustup

# 优先清华镜像安装，失败时回退到官方源
RUN if curl -fsSL ... https://mirrors.tuna.tsinghua.edu.cn/rustup/rustup-init.sh ...; then
        ...
    else
        # fallback to sh.rustup.rs
        ...
    fi
```

### H5 部署到 /h5/ 子路径（而非 8888）

- `uniapp/src/manifest.json` 的 `router.base = "/h5/"`
- `uniapp/vite.config.ts` 的 `base = "/h5/"`（H5 平台特定）
- nginx `omp-main.conf` 的 `location /h5/ { alias /app/h5/; ... }`
- **8888 端口保留给业务工程反代**（`deploy/nginx/conf.d/omp-h5.conf`）

### 不重建 base 的常见改动

- 改 Java 后端代码 → 只 rsync jar
- 改 PC/H5 前端代码 → 只 rsync dist/h5
- 改 omp 源码 → 只 rsync omp/
- 改 nginx 配置 → 进容器 vim + `nginx -s reload`（免重建）
- 改 prod-application.yml → rsync 后 docker restart

### 必须重建 base 的改动

- 升级 JDK / Bun / Maven 版本
- 加新工具到镜像
- 改 apt 源 / 升级 ubuntu base

---

## 同步约定

| 位置 | 角色 |
|---|---|
| 工程仓库 `Dockerfile.allinone.base` / `Dockerfile.allinone` | **唯一可信源** |
| 120 服务器 `/home/omp/docker-build/Dockerfile.base` / `Dockerfile` | 部署副本（由 rsync 同步） |

**禁止**直接在 120 上手改 Dockerfile，所有改动必须先改本地仓库，再 rsync。

---

## 关联文档

- 详细部署 runbook：[`docs/DEPLOY.md`](./docs/DEPLOY.md) §18 (120 UAT)
- 系统维护功能：[`docs/MAINTENANCE.md`](./docs/MAINTENANCE.md)
