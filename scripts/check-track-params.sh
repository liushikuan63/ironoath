#!/usr/bin/env bash
# 职责：`this.track(...)` 实际打出去的关键参数，必须逐个写进 `数据看板需求.md` §七 那一行的"关键参数"。
# 依赖：node（本项目必需工具链）。判什么、放过什么、为什么会失败 —— 全在 scripts/check-track-params.js 的头注释里。
#
# 为什么和第 27 道门分开放（而不是塞进 check-track-dictionary.sh）：那道门管"有没有这一行"，
# 这道门管"这一行里的参数名对不对"。两道的失效形状不同（前者漏行、后者编参数），
# 红的时候给的修法也不同，混在一个脚本里改一个会碰坏另一个。
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-track-params.js
