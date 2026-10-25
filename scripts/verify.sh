#!/usr/bin/env bash
# 收尾验证：每个开发 session 结束时手动跑一次。
# 完整 verify（编译 + 单测）+ 留痕提醒。CI 跑的是同一套命令。
set -e
cd "$(dirname "$0")/.."
mvn -B verify -f backend/pom.xml
echo ""
echo "[verify] 全部通过。收尾三件事："
echo "  1. docs/METRICS.md 填一行（5 分钟）"
echo "  2. AI 今天出过错吗？进 docs/AI-FAILURES.md"
echo "  3. git commit 带署名尾注：Authorship: ai | human | mixed"
