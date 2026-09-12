#!/usr/bin/env bash
# 职责：客户端逻辑层单测。先用 tsc 编译到 build-test（CommonJS），再用 node --test 运行。
# 说明：Cocos Creator 无法在 CI 中运行，故只测「引擎无关」的 core/net/store/game 逻辑（B00 铁律 2）。
set -euo pipefail
cd "$(dirname "$0")/../client"

rm -rf build-test
npx --no-install tsc -p tsconfig.test.json
node --test build-test/assets/scripts/test/
