// 职责：见同名 check-checklist-table.sh。规则只有一条，而且是有意的单向：
// 单元格数**多于**表头列数才报错 —— 少几格只是末尾留空（照常渲染，不丢内容），
// 多出来的那一格会被 Markdown 整格丢掉：写进去的待办在文件里看得见、在渲染后的页面上不存在。
'use strict'

const fs = require('fs')

const file = process.argv[2] || '收口清单.md'
const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)

const isSeparator = (line) => /^\|[\s:|-]+\|$/.test(line.trim())
const cells = (line) => line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').length

let headerColumns = 0
let headerLine = 0
const problems = []

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
}

if (problems.length > 0) {
  console.error('[check-checklist-table] 表格形状有问题：')
  problems.forEach((p) => console.error('  ' + p))
  process.exit(1)
}
console.log(`[check-checklist-table] ${file} 每一行的单元格数都不多于所在表头，无内容会被丢掉。`)
