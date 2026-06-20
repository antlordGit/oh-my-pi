#!/bin/bash
# 本工程 omp 开发入口 —— 直接运行 packages/coding-agent 源码（改动即时生效）。
# 后端 app.omp.binary 指向此脚本，避免依赖 ~/bin/omp 全局环境。
exec /opt/homebrew/bin/bun /Users/chenzhiwei/work/github/oh-my-pi-main/packages/coding-agent/src/cli.ts "$@"
