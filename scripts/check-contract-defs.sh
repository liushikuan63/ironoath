#!/usr/bin/env bash
# 职责：CI 静态检查 —— contract/proto 各 schema 的同名 $defs 必须同形（详见同名 .js 的注释）。
# 依赖：node。
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-contract-defs.js
