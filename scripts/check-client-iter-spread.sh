#!/usr/bin/env bash
# 职责：客户端源码里不得对**迭代器**做 spread（`[...map.values()]` 这一类）。
#
# 为什么这条只能靠静态检查：Cocos 的构建（SWC loose 假设）把 `[...map.values()]` 编成
# `[].concat(map.values())` —— concat 不展开迭代器，运行时得到的是"装着迭代器的一元数组"，
# 于是 sort/map 全对着迭代器做，症状是**面板整个画不出来**。
# 而 `test-client.sh` 走 tsc（真展开），所以这条缺陷在几百条 node:test 里永远全绿，
# 只在浏览器/真机上炸 —— 2026-09-19 的 B22 聊天页签就是踩在它上面（一轮真机级验证才逮到）。
#
# 反空转：一个文件都没扫到也按失败处理（路径过期不该等于"没问题"）。
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-client-iter-spread.js
