#!/usr/bin/env bash
# 职责：客户端逻辑层单测。先用 tsc 编译到 build-test（CommonJS），再用 node --test 运行。
# 说明：Cocos Creator 无法在 CI 中运行，故只测「引擎无关」的 core/net/store/game 逻辑（B00 铁律 2）。
set -euo pipefail
cd "$(dirname "$0")/../client"

# `node --test <目录>` 这种写法实测只在 Node 20 上成立：**Node 24 下同一份产物直接跑不起来**（症状是
# 找不到 build-test/tests，而不是用例红 —— 看着像产物坏了）。21/22/23 本机没装、**未实测**，所以这一条
# 只挡"实测会坏的那一档"，不假装知道边界在哪；哪天 22 也坏，把条件收紧即可（收紧比放宽安全）。
# ⚠ 两道边界要说清：① `client/package.json` 里的 `npm test` 走的是同一条 `node --test <目录>`，
#    **没有这道门**，从那个入口进来仍然会在 24 上失败；② 本脚本目前**不在 CI 里跑**
#    （`ci.yml` 只跑 `scripts/build.sh` 与 `scripts/check.sh`），所以这道门保护的是本地，不是主分支。
node_major=$(node -p 'process.versions.node.split(".")[0]')
if [ "$node_major" -ge 24 ] 2>/dev/null; then
  ver=$(node -v)
  echo "[test-client] 当前 node 是 $ver，而 node --test 的目录形态在 24 上实测跑不起来。"
  echo '  本机做法：PATH="/d/Java/nodejs/node20.13.0:$PATH" bash scripts/test-client.sh'
  echo '  或者把你环境里的 node 切到 20（nvm use 20 之类）。这不是用例红，是量具没架对。'
  exit 1
fi

# 必须先清 build-test：tsc 不会删掉源码已不存在的旧产物，而 `node --test <目录>` 会把它们一起数进通过数。
# 2026-09-22 实测：删掉两份测试文件后 `npm test` 仍报 925（真数 920），幽灵就是留在 outDir 里的 .js。
rm -rf build-test
npx --no-install tsc -p tsconfig.test.json
node --test build-test/tests/
