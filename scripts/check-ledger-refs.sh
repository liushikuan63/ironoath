#!/usr/bin/env bash
# 职责：核对「收口清单 #N」交叉引用是否真的落在 收口清单.md 里（#2510）。
#
# 为什么值得做成 CI 一项：四份文档里到处写「修收口清单 #2304」这类引用，而台账是
# 唯一活得过会话的载体。引用一旦指向不存在的编号，下一轮要么找不到依据、要么把同一件
# 事重新发现一遍。2026-11-13 现跑抓到一处真实笔误（开发包引 #767，清单里那条是 #759）。
#
# 判据分四层：
#   ① 台账文件存在，且编号数低于下限即判红（谓词失效不判绿）
#   ② 每份被检文档至少命中 1 处引用（拦"文档结构改了导致扫空"）
#   ③ 活引用（剥掉 `~~…~~` 更正痕迹之后）必须命中台账编号（两种形状都算）
#   ④ 引用总数不低于下限（拦"整体被删后扫空退 0"）
# 自测口：LEDGER_REF_LEDGER / LEDGER_REF_DOCS 可换输入，实现零污染反证。
set -euo pipefail
cd "$(dirname "$0")/.."

command -v node >/dev/null 2>&1 || {
  echo "[ledger-refs][FAIL] 找不到 node：要按两种台账形状解析编号，不能用 shell 的近似正则" >&2
  exit 1
}

LEDGER="${LEDGER_REF_LEDGER:-收口清单.md}"
DOCS="${LEDGER_REF_DOCS:-验收矩阵.md 上线检查清单.md 待完善收口_VibeCoding开发包.md CC开发全流程.md}"
MIN_REFS="${LEDGER_REF_MIN_REFS:-60}"
MIN_LEDGER_IDS="${LEDGER_REF_MIN_LEDGER_IDS:-800}"

node -e '
const fs = require("node:fs")
const { ledgerIds, ledgerRefs, ledgerShape } = require("./scripts/lib/ledger-refs.js")
const [ledgerPath, docsRaw, minRefsRaw, minLedgerIdsRaw] = process.argv.slice(1)
const docs = docsRaw.split(/\s+/).filter((s) => s !== "")
const minRefs = Number(minRefsRaw)
const minLedgerIds = Number(minLedgerIdsRaw)
const fail = (msg) => {
  console.error(`[ledger-refs][FAIL] ${msg}`)
  process.exit(1)
}

if (!fs.existsSync(ledgerPath)) fail(`台账不存在：${ledgerPath}`)
const ledgerText = fs.readFileSync(ledgerPath, "utf8")
const ids = ledgerIds(ledgerText)
const shape = ledgerShape(ledgerText)
// ① 谓词失效判红：两种形状都数不到，说明解析规则与文件形状已经脱节
if (shape.rows === 0 && shape.bullets === 0) fail(`台账里数不到任何编号（表格行 0、编号段 0）：${ledgerPath}`)
if (ids.size < minLedgerIds) fail(`台账编号数低于下限：${ids.size} < ${minLedgerIds}（台账被截断或解析规则失效）`)

const missing = []
let total = 0
for (const doc of docs) {
  if (!fs.existsSync(doc)) fail(`被检文档不存在：${doc}`)
  const refs = ledgerRefs(fs.readFileSync(doc, "utf8"))
  // ② 单份文档扫空 = 结构改了，必须红
  if (refs.length === 0) fail(`${doc} 里一处「收口清单 #N」都没扫到（文档结构变了或引用被整体删掉）`)
  total += refs.length
  // ③ 活引用必须落在台账里
  for (const id of new Set(refs)) {
    if (!ids.has(id)) missing.push(`${doc} #${id}`)
  }
}

// ④ 引用整体规模反空转
if (total < minRefs) fail(`「收口清单 #N」引用总数低于下限：${total} < ${minRefs}`)
if (missing.length > 0) {
  console.error("[ledger-refs][FAIL] 引用了台账里不存在的编号（改正引用，或把那条登记补进 收口清单.md）：")
  for (const row of missing) console.error(`  - ${row}`)
  process.exit(1)
}
console.log(`[ledger-refs] ✓ ${docs.length} 份文档共 ${total} 处「收口清单 #N」活引用全部命中；` +
  `台账现有编号 ${ids.size} 个（表格行 ${shape.rows} + 编号段 ${shape.bullets}）`)
' "$LEDGER" "$DOCS" "$MIN_REFS" "$MIN_LEDGER_IDS"
