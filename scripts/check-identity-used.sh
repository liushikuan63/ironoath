#!/usr/bin/env bash
# 职责：检查有没有 public 方法**收了 playerId 却一次都没用它**（身份被静默丢弃）。
# 依赖：node（解析在 scripts/lib/java-method-scan.js，与只读清单 scan-identity-dropped.js 同一套）。
#
# 为什么值得做成 CI 一项而不是靠人记住：身份是框架解出来后当第一个参数传下去的，
# 所以"忘了做归属校验"这件事编译期看不见、契约同步看不见、单测也看不见
# （夹具常常只有一个玩家）。它的运行时表现是甲拿自己的登录态填乙的资源 id 就读到、改到，
# 而且**没有一行日志**。收口清单 #256 那轮横扫全仓 4017 个方法，public 且丢身份的只剩 3 处，
# 且都是端口形状导致的（实现用不到那个参数）—— 正是"现在钉住最便宜"的时刻。
set -euo pipefail
cd "$(dirname "$0")/.."

node scripts/check-identity-used.js
