// 2026-10-21 #2287 方案③：历史短行原样保留，只要求比较基线后新增或改写的表格行与表头等宽。
// 不维护历史缺陷豁免表。CI 显式给上一次 push 或 PR base 的 SHA；本地默认和 HEAD 比，
// 因而未提交的新增行也会被检查。若基线读不到必须判红，不能假装没有新增行。
'use strict'

const { execFileSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const MAXBUF = 64 * 1024 * 1024
const baseSha = process.env.TABLE_SHAPE_BASE_SHA || 'HEAD'
const separator = line => /^\|[\s:|-]+\|$/.test(line.trim())
const cells = line => line.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/).length

function tableRows(text) {
  const lines = text.split(/\r?\n/)
  const rows = []
  let header = null
  let fence = null
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index].trim()
    const marker = line.match(/^(`{3,}|~{3,})/)
    if (marker !== null) {
      if (fence === null) fence = marker[1][0]
      else if (fence === marker[1][0]) fence = null
      continue
    }
    if (fence !== null || !line.startsWith('|')) {
      continue
    }
    if (separator(line)) continue
    const next = lines[index + 1]?.trim() ?? ''
    if (next.startsWith('|') && separator(next)) {
      header = { line, columns: cells(line) }
      continue
    }
    if (header !== null) {
      rows.push({ line, number: index + 1, columns: cells(line),
        expected: header.columns, header: header.line })
    }
  }
  return rows
}

function baseline(file) {
  try {
    execFileSync('git', ['cat-file', '-e', `${baseSha}^{commit}`], { stdio: 'pipe' })
  } catch {
    throw new Error(`读不到表格基线 commit ${baseSha}；不能把缺失的基线当成空文档。`)
  }
  try {
    return execFileSync('git', ['show', `${baseSha}:${file}`],
      { encoding: 'utf8', maxBuffer: MAXBUF })
  } catch (error) {
    const message = String(error.stderr || error.message || '')
    if (/does not exist|exists on disk, but not in/i.test(message)) return ''
    throw new Error(`读不到表格基线 ${baseSha}:${file}：${message.split(/\r?\n/)[0]}`)
  }
}

function checkNewRows(file, currentText = fs.readFileSync(file, 'utf8')) {
  const previous = new Map()
  for (const row of tableRows(baseline(file))) {
    const key = JSON.stringify([row.header, row.line])
    previous.set(key, (previous.get(key) ?? 0) + 1)
  }
  const problems = []
  let changed = 0
  for (const row of tableRows(currentText)) {
    const key = JSON.stringify([row.header, row.line])
    const count = previous.get(key) ?? 0
    if (count > 0) {
      previous.set(key, count - 1)
      continue
    }
    changed++
    if (row.columns !== row.expected) {
      problems.push(`${file}:${row.number} 新增或改写的表格行有 ${row.columns} 格，`
        + `表头要求 ${row.expected} 格；行首「${row.line.slice(0, 50)}」`)
    }
    if (path.basename(file) === '收口清单.md' && !row.line.endsWith('|')) {
      problems.push(`${file}:${row.number} 新增或改写的台账表格行缺行尾竖线；`
        + '缺最后一格时 GFM 会静默丢掉内容。')
    }
  }
  return { changed, problems }
}

if (require.main === module) {
  const files = process.argv.slice(2)
  if (files.length === 0) {
    console.error('用法：node scripts/check-new-table-rows.js <doc.md>...')
    process.exit(2)
  }
  try {
    let changed = 0
    const problems = []
    for (const file of files) {
      const result = checkNewRows(file)
      changed += result.changed
      problems.push(...result.problems)
    }
    if (problems.length > 0) {
      problems.forEach(problem => console.error(`[new-table-rows][FAIL] ${problem}`))
      process.exit(1)
    }
    console.log(`[new-table-rows] ${files.length} 份文档相对 ${baseSha} 新增或改写 ${changed} 行，`
      + '格数均与表头一致；历史短行未回填。')
  } catch (error) {
    console.error(`[new-table-rows][FAIL] ${error.message}`)
    process.exit(1)
  }
}

module.exports = { checkNewRows, tableRows }
