#!/usr/bin/env bash
# 职责：客户端不得把**配置表里的数值**写死在自己代码里（见同名 .js 的注释）。
# 依赖：node。
#
# 反空转：表读不到、唯一那份不在、一个文件都没扫到，都按失败处理。
#
# 可选输入口（不设时与默认扫描完全一致，check-gates-can-fail.sh 靠它们做零污染三读数）：
#   NUMCOPY_SCAN_DIR / NUMCOPY_CONFIG_DIR —— 见扫描器头注释
# （NUMCOPY_CONFIG_DIR 指向**一个配置表目录**，门会读它下面的全部 *.json）
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-client-numeric-copy.js
