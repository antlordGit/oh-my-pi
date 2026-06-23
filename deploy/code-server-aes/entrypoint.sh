#!/bin/bash
# 除 code-server 容器：启动 code-server(仅 localhost) + 签名验证代理(对外 9000)。
# 任一进程退出 → 整体退出，由 docker --restart 重启整个容器。
set -euo pipefail

# 1) code-server 仅绑 127.0.0.1:8080
code-server --auth none --bind-addr 127.0.0.1:8080 "$@" &
CODE_PID=$!

# 2) 签名验证代理(对外 9000)
node /opt/ide-proxy/server.js &
PROXY_PID=$!

# 任一子进程退出即收尾
term() {
  kill "$CODE_PID" "$PROXY_PID" 2>/dev/null || true
}
trap term TERM INT

# 等任意一个退出
wait -n "$CODE_PID" "$PROXY_PID"
EXIT=$?
echo "[entrypoint] 子进程退出 (code=$EXIT)，关闭容器"
term
exit "$EXIT"
