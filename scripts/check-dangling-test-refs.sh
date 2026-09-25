#!/usr/bin/env bash
# 门：注释里「判据见 XxxTest」指向的测试类必须真的存在（实现见同名 .js）。
# 用法：bash scripts/check-dangling-test-refs.sh [--verbose]｜bash scripts/check-dangling-test-refs.sh --self-test
set -euo pipefail
cd "$(dirname "$0")/.."
exec node scripts/check-dangling-test-refs.js "$@"
