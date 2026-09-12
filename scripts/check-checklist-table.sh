#!/usr/bin/env bash
# 职责：检查 收口清单.md 的表格形状 —— 每行的单元格数必须等于它所在小节的表头列数。
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
