#!/usr/bin/env bash
# 职责：CI 静态检查 —— 配置表里的道具外键必须指向 item 表真实存在的行（按字段名通用识别）。
# 依赖：node。
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-config-refs.js
