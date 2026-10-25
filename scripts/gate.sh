#!/usr/bin/env bash
# 编译门禁：由 .zcode/config.json 的 PostToolUse 钩子在 AI 每次改动文件后自动调用。
# 规则：只要 backend 源码相对上次门禁有变化，就必须通过 mvn compile，否则非零退出。
# 设计说明（D006）：让"AI 改坏立刻被拦"成为默认态，而不是依赖人的自觉。
set -u
cd "$(dirname "$0")/.."

STATE=.gate-state
CHANGED=$(git status --porcelain -- backend/src 2>/dev/null || true)
HASH=$(printf '%s' "$CHANGED" | sha1sum | cut -d' ' -f1)

# 与上次门禁时无差异（或 git 不可用）→ 直接放行，避免重复跑 Maven
if [ "$HASH" = "$(cat "$STATE" 2>/dev/null || true)" ]; then
  exit 0
fi
if [ -z "$CHANGED" ]; then
  echo "$HASH" > "$STATE"
  exit 0
fi

echo "[gate] backend 源码有变动，执行编译门禁..."
if mvn -q compile -f backend/pom.xml; then
  echo "$HASH" > "$STATE"
  echo "[gate] 编译通过"
  exit 0
else
  echo "[gate] 编译失败 —— AI 必须先修复，才能继续后续工作" >&2
  exit 1
fi
