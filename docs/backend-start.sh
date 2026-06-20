#!/bin/bash
# 启动 omp 后端（不依赖 start.sh）
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 检查并停旧进程
PID=$(lsof -ti:8080 2>/dev/null | head -1)
if [ -n "$PID" ]; then
    echo "Stopping old backend (PID $PID)..."
    kill "$PID" 2>/dev/null || true
    sleep 2
fi

mkdir -p /tmp/omp/{workspaces,agent,logs}

export OMP_BIN="$ROOT/scripts/omp-dev.sh"
export OMP_AGENT_ROOT="/tmp/omp/agent"
export OMP_STDERR_LOG_DIR="/tmp/omp/logs"
export PATH="$ROOT/node_modules/.bin:$PATH"

cd "$ROOT/backend"
nohup java -jar target/omp-backend-0.1.0.jar > /tmp/omp/backend.log 2>&1 &
PID=$!
echo "Backend started (PID $PID)"
echo "Log: tail -f /tmp/omp/backend.log"
echo ""
echo "等待后端就绪..."
for i in $(seq 1 30); do
    if curl -s -m 2 http://localhost:8080/api/auth/login -X POST \
        -H 'Content-Type: application/json' \
        -d '{"username":"admin","password":"admin"}' >/dev/null 2>&1; then
        echo "✅ Backend ready in ${i}s"
        exit 0
    fi
    sleep 1
done
echo "❌ Backend failed to start, check: tail -f /tmp/omp/backend.log"
exit 1
