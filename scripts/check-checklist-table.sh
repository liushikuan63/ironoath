#!/usr/bin/env bash
# 职责：保留收口清单已有的多列/撞号门，并按 #2287 方案③检查活文档新增表格行必须与表头等宽。
# 依赖：node（不用 python：本机的 python 存根会中途 Permission denied，见 windows-toolchain-quirks）。
#
# 为什么值得做成 CI 一项而不是靠人记住：
# 这份清单是本项目**唯一活得过会话的待修载体**（会话任务列表与导出都留不住）。
# 而 Markdown 表格的多余单元格在渲染时会被**整格丢掉且不报错** ——
# 于是"记下来了"等于"没记下来"，下一轮会重新发现一遍，正是这份文件存在的理由被反向实现。
# 竖线在代码块里同样会被当成分列符（`a|b` 就足以触发），所以这个错极易犯、犯了自己看不见。
set -euo pipefail
cd "$(dirname "$0")/.."

node scripts/check-checklist-table.js 收口清单.md
node --test scripts/check-new-table-rows.test.mjs
node scripts/check-new-table-rows.js \
  验收矩阵.md 上线检查清单.md 待完善收口_VibeCoding开发包.md \
  CC开发全流程.md DSH线台账_2026-10-19.md 客户端发送口缺口清单.md
