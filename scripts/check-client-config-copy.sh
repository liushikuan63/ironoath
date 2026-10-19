#!/usr/bin/env bash
# 职责：客户端不得**抄** global 表里「值本身就是表内容」的那批字符串参数（见同名 .js 的注释）。
# 依赖：node。
#
# 反空转：表读不到、客户端一个文件都扫不到，都按失败处理（路径过期 ≠ "没问题"）。
#
# 可选输入口（不设时与默认扫描完全一致，check-gates-can-fail.sh 靠它们做零污染三读数）：
#   CONFIGCOPY_SCAN_DIR / CONFIGCOPY_GLOBAL_JSON —— 见扫描器头注释
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-client-config-copy.js
