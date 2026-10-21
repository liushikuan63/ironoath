import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const checker = path.resolve(import.meta.dirname, 'check-checklist-table.js')
const header = '| A | B | C |\n|---|---|---|\n'
const oldShort = '| historical | value |\n'

function runFixture(change, fileName = 'fixture.md', baseSha = null) {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'ironoath-new-table-rows-'))
  try {
    for (const args of [['init', '-q'], ['config', 'user.email', 'fixture@example.test'],
      ['config', 'user.name', 'Fixture']]) {
      const result = spawnSync('git', args, { cwd: dir, encoding: 'utf8' })
      assert.equal(result.status, 0, result.stderr)
    }
    const doc = path.join(dir, fileName)
    writeFileSync(doc, header + oldShort, 'utf8')
    for (const args of [['add', fileName], ['commit', '-qm', 'baseline']]) {
      const result = spawnSync('git', args, { cwd: dir, encoding: 'utf8' })
      assert.equal(result.status, 0, result.stderr)
    }
    if (change !== null) writeFileSync(doc, change(readFileSync(doc, 'utf8')), 'utf8')
    // CI 比较的是远端 base；合成小仓库只认识它自己的 HEAD，不能继承外层基线 SHA。
    const env = { ...process.env }
    delete env.TABLE_SHAPE_BASE_SHA
    if (baseSha !== null) env.TABLE_SHAPE_BASE_SHA = baseSha
    const run = spawnSync(process.execPath, [checker, fileName], {
      cwd: dir, env, encoding: 'utf8', timeout: 10_000,
    })
    return { status: run.status, output: `${run.stdout}\n${run.stderr}` }
  } finally {
    const base = path.resolve(os.tmpdir()) + path.sep
    if (!path.resolve(dir).startsWith(base)) throw new Error('fixture outside temp root')
    rmSync(dir, { recursive: true, force: true })
  }
}

test('historical short row remains allowed without an exemption list', () => {
  assert.equal(runFixture(null).status, 0)
})

test('new short row fails and names the new row', () => {
  const result = runFixture(text => text + '| new | short |\n')
  assert.equal(result.status, 1, result.output)
  assert.match(result.output, /new.*short|第 4 行/)
})

test('editing a historical short row counts as a new row', () => {
  const result = runFixture(text => text.replace('historical', 'edited'))
  assert.equal(result.status, 1, result.output)
})

test('new complete row and escaped pipe remain valid', () => {
  const result = runFixture(text => text + '| new | a \\| b | complete |\n')
  assert.equal(result.status, 0, result.output)
})

test('copying an old short row does not gain another historical allowance', () => {
  const result = runFixture(text => text + oldShort)
  assert.equal(result.status, 1, result.output)
})

test('a new pipe example inside a fenced code block is not a table row', () => {
  const result = runFixture(text => text + '\n```md\n| example | short |\n```\n')
  assert.equal(result.status, 0, result.output)
})

test('a new checklist row must keep its final pipe', () => {
  const result = runFixture(text => text + '| new | middle | last\n', '收口清单.md')
  assert.equal(result.status, 1, result.output)
})

test('a late implementation row after a heading still uses the existing table header', () => {
  const result = runFixture(text => text + '\n## later record\n\n| new | short |\n')
  assert.equal(result.status, 1, result.output)
})

test('an unreadable base commit fails even when every current row is full width', () => {
  const result = runFixture(text => text.replace(oldShort, '| historical | value | filled |\n'),
    'fixture.md', 'a'.repeat(40))
  assert.equal(result.status, 1, result.output)
  assert.match(result.output, /基线|commit/)
})
