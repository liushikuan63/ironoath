#!/usr/bin/env bash
# 职责：客户端绑的每一个路径，服务端必须真的有 —— 少一个就是玩家点一下得到 404。
# 依赖：node（本项目的必需工具链）。刻意不用 python：Windows 上它常常只是 Store 的存根。
#
# 为什么这条必须是卡口而不是靠人记：契约里有类型、客户端里有方法、服务端少一个端点时，
# 从代码上完全看不出来 —— 编译过、测试过（没人调它）、类型也对。
# 2026-09-10 实测抓到 4 条：/squad/rally 与 /alliance/rally 是路径写反（服务端在 /rally/ 下），
# /squad/transfer 与 /alliance/expand 是领域方法早就写好、控制器没挂。
# 反向（服务端有、客户端没绑）刻意不判：那些是有意的运维/回调端点，客户端本来就不该调。
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-endpoint-paths.js
