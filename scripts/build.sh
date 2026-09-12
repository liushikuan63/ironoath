#!/usr/bin/env bash
# 职责：全量构建 = 代码生成 + 分层检查 + 契约一致性检查 + 编译 + 单测 + 客户端类型检查。
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/gen.sh
bash scripts/check-layering.sh
bash scripts/check-contract-sync.sh

source scripts/env.sh
mvn -f server/pom.xml -DskipTests package

cd client && npx --no-install tsc -p tsconfig.json --noEmit && cd ..

bash scripts/test.sh

echo "[build] 全量构建通过。"
