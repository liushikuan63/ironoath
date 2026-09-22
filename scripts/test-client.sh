#!/usr/bin/env bash
# 职责：客户端逻辑层单测。先用 tsc 编译到 build-test（CommonJS），再用 node --test 运行。
# 说明：Cocos Creator 无法在 CI 中运行，故只测「引擎无关」的 core/net/store/game 逻辑（B00 铁律 2）。
set -euo pipefail
cd "$(dirname "$0")/../client"

# `node --test <目录>` 这种写法只有 Node 20 认：Node 24 下同一份产物直接跑不起来（2026-09-22 实测，
# 症状是找不到 build-test/tests 而不是报用例红 —— 看着像产物坏了）。CI 用 setup-node 钉死 20，
# 本地默认 node 是 24，所以这条要在**跑之前**说清楚，而不是留一句看不懂的失败。
node_major=$(node -p 'process.versions.node.split(".")[0]')
if [ "$node_major" != "20" ]; then
  ver=$(node -v)
  echo "[test-client] 当前 node 是 $ver，这一份脚本要 Node 20.x —— node --test 的目录形态只在 20 上成立。"
  echo '  本机做法：PATH="/d/Java/nodejs/node20.13.0:$PATH" bash scripts/test-client.sh'
  echo '  或者把你环境里的 node 切到 20（nvm use 20 之类）。这不是用例红，是量具没架对。'
  exit 1
fi

# 必须先清 build-test：tsc 不会删掉源码已不存在的旧产物，而 `node --test <目录>` 会把它们一起数进通过数。
# 2026-09-22 实测：删掉两份测试文件后 `npm test` 仍报 925（真数 920），幽灵就是留在 outDir 里的 .js。
rm -rf build-test
npx --no-install tsc -p tsconfig.test.json
node --test build-test/tests/
