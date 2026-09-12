#!/usr/bin/env bash
# 职责：CI 静态检查 —— 错误码枚举的码值唯一性与段位归属（详见同名 .js 的注释）。
# 依赖：node。填错时运行时不报错（客户端只会拿到分不开的两个语义），所以只能静态兜。
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-error-codes.js
