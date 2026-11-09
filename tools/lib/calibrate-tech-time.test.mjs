import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtempSync, readFileSync, writeFileSync, rmSync, copyFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
function run(t, change) {
  const dir = mkdtempSync(path.join(tmpdir(), 'ironoath-tech-calibration-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  for (const name of ['tech.json', 'curve.json', 'season.json']) copyFileSync(path.join(root, 'contract/config', name), path.join(dir, name))
  const tech = JSON.parse(readFileSync(path.join(dir, 'tech.json'), 'utf8'))
  change(tech.rows)
  writeFileSync(path.join(dir, 'tech.json'), JSON.stringify(tech))
  return spawnSync(process.execPath, [path.join(root, 'tools/calibrate-tech-time.mjs'), `--config-dir=${dir}`, '--json'], { encoding: 'utf8', timeout: 10000 })
}
test('legacy missing, null and zero inputs retain common-base calibration', t => {
  const result = run(t, rows => rows.forEach((r, i) => {
    if (i % 3 === 0) delete r.timeBaseSec
    else r.timeBaseSec = i % 3 === 1 ? null : 0
  }))
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /"baseSec": 13/)
  assert.match(result.stdout, /全树总时长/)
})
test('positive row base cannot silently receive a common-base calibration report', t => {
  const result = run(t, rows => { rows[0].timeBaseSec = 20 })
  assert.equal(result.status, 1, result.stdout)
  assert.match(result.stderr, /timeBaseSec.*tech_agri_wood/)
  assert.doesNotMatch(result.stdout, /全树总时长/)
})
test('invalid row-base values fail instead of being coerced', t => {
  for (const value of [-1, 1.5, '20']) {
    const result = run(t, rows => { rows[0].timeBaseSec = value })
    assert.equal(result.status, 1, result.stdout)
    assert.match(result.stderr, /timeBaseSec/)
  }
})
