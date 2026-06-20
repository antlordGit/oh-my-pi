#!/bin/bash
# =============================================================================
# omp 启动脚本 — 一键启动前后端
#
# 使用方式:
#   ./docs/start.sh              # 前台阻塞运行（Ctrl+C 退出时自动清理）
#   ./docs/start.sh --daemon     # 后台启动（守护进程模式）
#
# 环境变量:
#   OMP_BACKEND_PORT  后端端口，默认 8080
#   OMP_WEB_PORT      前端端口，默认 5173
#   OMP_API_KEY       LLM API Key，默认从 settings 读取
#
# 日志:
#   后端启动日志: /tmp/omp/backend.log
#   前端启动日志: /tmp/omp/vite.log
#   清理日志:     /tmp/omp/cleanup.log
# =============================================================================

set -e

# ── 配置 ──
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_PORT="${OMP_BACKEND_PORT:-8080}"
WEB_PORT="${OMP_WEB_PORT:-5173}"
DAEMON_MODE=false

[[ "$1" == "--daemon" || "$1" == "-d" ]] && DAEMON_MODE=true

mkdir -p /tmp/omp/{workspaces,agent,logs,web}

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; RESET='\033[0m'
log()  { echo -e "${GREEN}[omp]${RESET} $1"; }
warn() { echo -e "${YELLOW}[warn]${RESET} $1"; }
err()  { echo -e "${RED}[error]${RESET} $1"; }

# ── 清理函数 ──
cleanup() {
    log "Cleaning up..."
    [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null && log "  Stopped backend (PID $BACKEND_PID)"
    [ -n "$WEB_PID" ]     && kill "$WEB_PID" 2>/dev/null     && log "  Stopped Vite dev server (PID $WEB_PID)"
    exit 0
}
trap cleanup INT TERM

# ── 预检 ──
command -v bun  >/dev/null 2>&1 || { err "bun not found. Install with: brew install bun"; exit 1; }
command -v java >/dev/null 2>&1 || { err "java not found. Install JDK 21+"; exit 1; }
log "bun $(bun --version) | java $(java -version 2>&1 | head -1) | root: $ROOT_DIR"

# ── 端口冲突检查 ──
if lsof -ti:"$BACKEND_PORT" 2>/dev/null | grep -v "$$" >/dev/null; then
    warn "Port $BACKEND_PORT is already in use"
    read -p "  Kill existing process? [y/N] " -n 1 -r; echo
    [[ $REPLY =~ ^[Yy]$ ]] && kill "$(lsof -ti:"$BACKEND_PORT")" 2>/dev/null || exit 1
    sleep 1
fi
if lsof -ti:"$WEB_PORT" 2>/dev/null | grep -v "$$" >/dev/null; then
    warn "Port $WEB_PORT is already in use"
    read -p "  Kill existing process? [y/N] " -n 1 -r; echo
    [[ $REPLY =~ ^[Yy]$ ]] && kill "$(lsof -ti:"$WEB_PORT")" 2>/dev/null || exit 1
    sleep 1
fi

# ── 启动后端 ──
log "Starting backend (:${BACKEND_PORT})..."
export OMP_BIN="$ROOT_DIR/scripts/omp-dev.sh"
export OMP_AGENT_ROOT="/tmp/omp/agent"
export OMP_STDERR_LOG_DIR="/tmp/omp/logs"

JAVA_LOG="/tmp/omp/backend.log"
cd "$ROOT_DIR/backend"

JAVA_CMD="java -jar target/omp-backend-0.1.0.jar"
if [ "$DAEMON_MODE" = true ]; then
    nohup $JAVA_CMD > "$JAVA_LOG" 2>&1 &
    BACKEND_PID=$!
    log "  Backend launched in background (PID $BACKEND_PID, log: $JAVA_LOG)"
else
    $JAVA_CMD > "$JAVA_LOG" 2>&1 &
    BACKEND_PID=$!
    log "  Backend launched (PID $BACKEND_PID, log: $JAVA_LOG)"
fi

# ── 等待后端就绪 ──
log "  Waiting for backend to be ready..."
for i in $(seq 1 30); do
    if curl -s -m 2 http://localhost:$BACKEND_PORT/api/auth/login >/dev/null 2>&1; then
        log "  Backend ready in ${i}s"
        break
    fi
    sleep 1
done
if ! curl -s -m 5 http://localhost:$BACKEND_PORT/api/auth/login >/dev/null 2>&1; then
    err "Backend failed to start within 30s. Check: tail -f $JAVA_LOG"
    cleanup
    exit 1
fi

# ── 启动前端 ──
log "Starting Vite dev server (:${WEB_PORT})..."
cd "$ROOT_DIR/web"
VITE_LOG="/tmp/omp/vite.log"
bun run dev -- --host 0.0.0.0 --port "$WEB_PORT" > "$VITE_LOG" 2>&1 &
WEB_PID=$!
log "  Vite launched (PID $WEB_PID, log: $VITE_LOG)"

# 等待 Vite 就绪
sleep 3
if curl -s -m 5 http://localhost:$WEB_PORT/ >/dev/null 2>&1; then
    log "  Vite ready (:${WEB_PORT})"
else
    warn "  Vite may still be starting – check: tail -f $VITE_LOG"
fi

# ── 输出状态 ──
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════╗${RESET}"
echo -e "${CYAN}║  omp 已启动                            ║${RESET}"
echo -e "${CYAN}╠══════════════════════════════════════════╣${RESET}"
echo -e "${CYAN}║  后端:  http://localhost:$BACKEND_PORT       ║${RESET}"
echo -e "${CYAN}║  前端:  http://localhost:$WEB_PORT      ║${RESET}"
echo -e "${CYAN}║  日志:  tail -f $JAVA_LOG ║${RESET}"
echo -e "${CYAN}╚══════════════════════════════════════════╝${RESET}"

if [ "$DAEMON_MODE" = true ]; then
    log "Running as daemon. Use 'lsof -ti:$BACKEND_PORT' to find backend PID."
    disown $BACKEND_PID $WEB_PID 2>/dev/null
else
    echo ""
    log "Press Ctrl+C to stop"
    # 持续监控子进程
    wait $BACKEND_PID $WEB_PID
fi
