# 继承官方 code-server，叠加签名验证代理 + code-server 源码覆盖，打成单个镜像。
# - code-server 仅绑 127.0.0.1:8080（容器内）
# - 验证代理对外监听 9000，验证 ?folder=...&sig= HMAC，有效 → 反代到 code-server | 无效 → 403
# - 3 个编译后的 JS 文件覆盖 code-server 原文件（验签中间件 + CLI + 工具）
FROM codercom/code-server:latest

USER root

# 腾讯云镜像源加速 + node（跑验证代理）+ tini
RUN sed -i "s|http://deb.debian.org|http://mirrors.tencentyun.com|g" /etc/apt/sources.list.d/debian.sources     && apt-get update     && apt-get install -y --no-install-recommends nodejs npm tini     && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/ide-proxy
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund --registry=https://registry.npmmirror.com
COPY server.js ./

# 覆盖 code-server 自身的 3 个 JS 文件
COPY verifySignature.js /usr/lib/code-server/out/node/verifySignature.js
COPY vscode.js          /usr/lib/code-server/out/node/routes/vscode.js
COPY cli.js             /usr/lib/code-server/out/node/cli.js

COPY entrypoint.sh /opt/entrypoint.sh
RUN chmod +x /opt/entrypoint.sh

# 对外端口:验证代理(9000)，code-server 的 8080 只在容器内 localhost
ENV IDE_PROXY_PORT=9000     CODE_SERVER_UPSTREAM=http://127.0.0.1:8080
EXPOSE 9000

# tini 作 PID 1 回收子进程；entrypoint 在任一进程退出时整体退出
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/entrypoint.sh"]
