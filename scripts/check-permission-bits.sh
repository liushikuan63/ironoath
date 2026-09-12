#!/usr/bin/env bash
# 职责：CI 静态检查 —— 代码传给 requirePermission 的权限位必须存在于 role_permission 表。
# 依赖：node。填错时运行时不报错（查表查不到而已），所以只能静态兜。
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-permission-bits.js
