#!/usr/bin/env bash
# 职责：CI 静态检查 —— 每张配置表必须有生产代码读它，否则它就是装饰品（详见同名 .js 的注释）。
# 依赖：node。
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-config-consumers.js
