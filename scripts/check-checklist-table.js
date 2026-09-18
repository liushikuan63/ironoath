// 职责：见同名 check-checklist-table.sh。两条规则：
// ① 单元格数**多于**表头列数才报错 —— 少几格只是末尾留空（照常渲染，不丢内容），
//    多出来的那一格会被 Markdown 整格丢掉：写进去的待办在文件里看得见、在渲染后的页面上不存在。
// ② 台账编号不能重复，且行内自报的「本行占 #N」必须与首列编号一致 ——
//    编号是全仓库互相引用的锚点（"见 #112"），撞号之后指哪一条没人说得清。
//    2026-09-19 加这条是因为同一天真撞了两次：#112 在历史上就重复过（两条不同条目共用一个号），
//    #205 又被两个并行会话同时占用。**没有这条卡口时，两种情况都只能靠人眼发现。**
'use strict'

const fs = require('fs')

const file = process.argv[2] || '收口清单.md'
const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)

/**
 * 已知并暂予放行的重复编号 ⇒ 为什么可以暂时红着不修。
 * **这是债务清单，不是豁免制度**：新撞号一律判失败；这里每一项都要在下次改动时清掉。
 */
const KNOWN_DUPLICATE_NUMBERS = {
  // 历史上两条不同条目都写了 #112（阶段 4.5/5.2 与"微信构建与包体预算"）。
  // 改号会牵动全仓库对 #112 的既有引用，待单独一轮收口。
  112: '两条历史条目同号，涉及跨文档引用，待单独一轮改号',
  // 205 曾在 2026-09-19 被两个并行会话同日占用，占用方已自行让号（见 df42091），
  // 豁免随即删除 —— **清单里不留已经失效的条目**，否则等于给未来的同号开后门。
}

const isSeparator = (line) => /^\|[\s:|-]+\|$/.test(line.trim())
const splitCells = (line) => line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|')
const cells = (line) => splitCells(line).length
const firstCell = (line) => splitCells(line)[0].trim()

let headerColumns = 0
let headerLine = 0
let headerIsIndex = false
const problems = []
/** 编号 → 出现过它的行号（1 起） */
const seenNumbers = new Map()

for (let index = 0; index < lines.length; index++) {
  const trimmed = lines[index].trim()
  if (!trimmed.startsWith('|')) {
    continue
  }
  const count = cells(trimmed)
  if (isSeparator(trimmed)) {
    continue
  }
  // 下一行是分隔行 ⇒ 当前行是表头，本节的列数由它定义（不检查表头自己）
  const next = lines[index + 1] === undefined ? '' : lines[index + 1].trim()
  if (next.startsWith('|') && isSeparator(next)) {
    headerColumns = count
    headerLine = index
    // 只有首列是「#」的表才谈编号：其它表（如"问题/裁定"表）首列不是编号
    headerIsIndex = firstCell(trimmed) === '#'
    continue
  }
  if (headerColumns === 0) {
    problems.push(`第 ${index + 1} 行出现在任何表格表头之前`)
    continue
  }
  if (count > headerColumns) {
    const first = trimmed.replace(/^\|\s*/, '').slice(0, 24)
    problems.push(
      `第 ${index + 1} 行有 ${count} 格，但第 ${headerLine + 1} 行的表头只有 ${headerColumns} 列` +
        ` —— 多出来的一格渲染时会被整格丢掉（行首「${first}…」）。` +
        ` 最常见的原因是文字里出现了裸竖线（如代码块内的 a|b），改用「与」或转义 \\|。`,
    )
  }
  if (!headerIsIndex) {
    continue
  }
  const head = firstCell(trimmed)
  if (!/^\d+$/.test(head)) {
    continue
  }
  const num = Number(head)
  const already = seenNumbers.get(num)
  if (already === undefined) {
    seenNumbers.set(num, [index + 1])
  } else {
    already.push(index + 1)
    if (KNOWN_DUPLICATE_NUMBERS[num] === undefined) {
      problems.push(
        `编号 #${num} 被第 ${already.join(' 与 ')} 行同时占用 —— 编号是全仓库互相引用的锚点，` +
          `撞号之后「见 #${num}」指哪一条无人能判。后写入的一方改成下一个空号（并同步行内「本行占 #」）。`,
      )
    }
  }
  // 自报号与首列不一致：只在写了自报号时判（老条目没这个约定，92 条为空）
  const declared = trimmed.match(/本行占 #(\d+)/)
  if (declared !== null && Number(declared[1]) !== num) {
    problems.push(
      `第 ${index + 1} 行首列是 #${num}，行内却自报「本行占 #${declared[1]}」—— 两者必须一致，` +
        `否则改号时只会改掉一处而留下互相矛盾的引用。`,
    )
  }
}

if (problems.length > 0) {
  console.error('[check-checklist-table] 表格形状或编号有问题：')
  problems.forEach((p) => console.error('  ' + p))
  process.exit(1)
}

const exempted = Object.keys(KNOWN_DUPLICATE_NUMBERS)
  .map(Number)
  .filter((num) => (seenNumbers.get(num) || []).length > 1)
console.log(`[check-checklist-table] ${file} 每一行的单元格数都不多于所在表头，无内容会被丢掉。`)
if (exempted.length > 0) {
  console.log(`[check-checklist-table] 编号唯一性：${exempted.length} 处历史撞号暂予放行 ` +
    `(${exempted.map(n => '#' + n).join('、')})，新撞号一律判失败 —— 清单见脚本内 KNOWN_DUPLICATE_NUMBERS`)
}

