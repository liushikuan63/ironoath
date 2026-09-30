#!/usr/bin/env bash
# 门：client/assets/scripts 下每个 .ts 必须有一份同名 .ts.meta 入库。
# 实现见同名 .js（含 --self-test）。
set -euo pipefail
cd "$(dirname "$0")/.."
exec node scripts/check-ts-meta.js "$@"