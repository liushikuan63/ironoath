#!/usr/bin/env bash
# 职责：检查 收口清单.md「只增不删」—— 内容级核对，替掉会假报红的增删计数。
# 为什么值得做成 CI 一项：#731 实测发现「git diff --numstat 的删除数必须为 0」这条判据
# 在**就地补注**这一合法动作上会假报（那次 numstat 是 2/1，而逐行核对未找到 = 0）。
# 而「只增不删」是这份台账的生命线：它一被静默删改，下一轮就会把同一件事重新发现一遍。
# 依赖：node（与 check-checklist-table 同理由，不用 python）。
set -euo pipefail
cd "$(dirname "$0")/.."

node scripts/check-checklist-append-only.js 收口清单.md
