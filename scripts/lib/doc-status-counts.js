// 职责：只做一件可复算的事 —— 从两份台账里数出「状态分布」与「勾选计数」。
//
// 为什么单独抽出来（V23-d）：`check-doc-status-counts.sh` 要拿现跑值与文档里声明的数字比对，
// 而**计数规则本身**是这个判据里最容易出错的地方：状态列是 emoji（✅/🟡/⬜ 都是代理对），
// 用 `line.length`、`split('')` 或正则 `.` 去数会把一个 emoji 数成两个码元，得到"看起来很像"
// 的错数；一旦错数被写进文档，下一道门就会拿它当基线，错得再也回不来。
// ⇒ 只认 `codePointAt(0)`，并对"状态列第一个码点不是这三种"的行单独计数（不静默丢弃）。
const fs = require('node:fs')

const STATUS = new Set(['✅', '🟡', '⬜'])

/** 验收矩阵：`| 编号 | 验收项 | 状态 | 证据 |` —— 只认第二个格是纯数字的行（表头/小节行不算）。 */
function matrixStatusCounts(text) {
  const counts = { numbered: 0, '✅': 0, '🟡': 0, '⬜': 0, other: 0 }
  const others = []
  for (const [i, line] of text.split('\n').entries()) {
    if (!line.startsWith('|')) continue
    const cells = line.split('|').map((c) => c.trim())
    if (cells.length < 5) continue
    if (!/^[0-9]+$/.test(cells[1])) continue
    counts.numbered++
    const status = cells[3]
    const first = status && status.length ? String.fromCodePoint(status.codePointAt(0)) : ''
    if (STATUS.has(first)) counts[first]++
    else {
      counts.other++
      others.push(`L${i + 1}: [${cells[1]}] "${status.slice(0, 24)}"`)
    }
  }
  return { counts, others }
}

/** 上线检查清单：`      - [x] …` / `      - [ ] …`（含缩进的子项）。 */
function checklistCounts(text) {
  let done = 0
  let todo = 0
  for (const line of text.split('\n')) {
    if (/^\s*- \[x\]/.test(line)) done++
    else if (/^\s*- \[ \]/.test(line)) todo++
  }
  return { done, todo }
}

/** 静态门道数：与 check-doc-counts.sh 同一谓词（行首 bash scripts/check-*.sh，非注释）。 */
function gateCount(checkShText) {
  return checkShText.split('\n').filter((line) => /^\s*bash scripts\/check-[a-z0-9_-]+\.sh/.test(line)).length
}

module.exports = { matrixStatusCounts, checklistCounts, gateCount }
