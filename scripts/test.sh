#!/usr/bin/env bash
# 职责：跑全部单测 —— 服务端 JUnit（含纯 Java 层）+ 客户端逻辑层 node:test。
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

echo "[test] === 服务端 JUnit ==="
mvn -f server/pom.xml test

echo "[test] === 客户端逻辑层 ==="
bash scripts/test-client.sh

echo "[test] 全部单测通过。"
