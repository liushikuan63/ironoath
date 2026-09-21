#!/usr/bin/env bash
# 职责：客户端逻辑层单测。先用 tsc 编译到 build-test（CommonJS），再用 node --test 运行。
# 说明：Cocos Creator 无法在 CI 中运行，故只测「引擎无关」的 core/net/store/game 逻辑（B00 铁律 2）。
#
# 为什么传 **glob** 而不是目录 `build-test/tests/`：Node 24 起 `node --test <目录>` 会把目录
# 当成**单个测试文件**去 require，得到 `tests 1 / pass 0 / fail 1` + MODULE_NOT_FOUND
# （收口清单 #314 那轮实测：`MSYS_NO_PATHCONV=1` 对照仍红，排除路径转换因素）。
# 症状极具误导性 —— 它看起来像"某个用例坏了"，实际是一条用例都没跑。
# 引号是必需的（交给 node 自己展开，不让 shell 先展开成 argv；两种展开在 Node 24 下都验过）。
# 判据：新增用例数必须体现在 `ℹ tests N` 上；若哪天它又变回 1，就是这条又坏了。
set -euo pipefail
cd "$(dirname "$0")/../client"

rm -rf build-test
npx --no-install tsc -p tsconfig.test.json
node --test "build-test/tests/*.test.js"
