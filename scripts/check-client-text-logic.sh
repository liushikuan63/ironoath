#!/usr/bin/env bash
# 职责：客户端不得拿**中文表值**做逻辑判断（见同名 .js 的注释）。
# 依赖：node。
#
# 反空转：一个文件都没扫到，也按失败处理（路径过期 ≠ "没问题"）。
#
# 可选输入口（不设时与默认扫描完全一致，check-gates-can-fail.sh 靠它们做零污染三读数）：
#   TEXTLOGIC_SCAN_DIR —— 见扫描器头注释
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-client-text-logic.js
