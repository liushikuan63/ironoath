#!/usr/bin/env bash
# 职责：客户端对**每个资源**只能有一种中文名，且那一种必须与表一致（见同名 .js 的注释）。
# 依赖：node。
#
# 反空转：资源表读不到、唯一那份不在、一个文件都没扫到，都按失败处理。
#
# 可选输入口（不设时与默认扫描完全一致，check-gates-can-fail.sh 靠它们做零污染三读数）：
#   RESNAME_SCAN_DIR / RESNAME_TABLE —— 见扫描器头注释
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-client-resource-names.js
