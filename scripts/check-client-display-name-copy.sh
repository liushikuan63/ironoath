#!/usr/bin/env bash
# 职责：客户端不得把**配置表的显示名**（中文名 / 标题 / 标签）抄在自己代码里。
# 依赖：node。判据与边界见同名 .js 的头注释。
#
# 反空转：表读不到、唯一那份不在、一个文件都没扫到，都按失败处理。
#
# 可选输入口（不设时与默认扫描完全一致，check-gates-can-fail.sh 靠它们做零污染三读数）：
#   DISPLAYNAME_SCAN_DIR / DISPLAYNAME_CONFIG_DIR
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-client-display-name-copy.js
