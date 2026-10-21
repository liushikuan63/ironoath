import assert from 'node:assert/strict'
import test from 'node:test'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { mkdtemp, open, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { executeB13Load, replaceReservedReport } from './run-b13-online-load.mjs'

const sockets = []
class FixtureSocket {
  constructor(url) {
    this.url = new URL(url)
    this.readyState = 0
    this.listeners = new Map()
    sockets.push(this)
    queueMicrotask(() => {
      this.readyState = 1
      this.emit('open', {})
      this.emit('message', { data: JSON.stringify({ type: 'connected', heartbeatSeconds: 10 }) })
    })
  }
  addEventListener(type, fn) { this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]) }
  emit(type, event) { for (const fn of this.listeners.get(type) ?? []) fn(event) }
  send(raw) {
    const message = JSON.parse(raw)
    if (message.type !== 'bind') return
    const id = this.url.searchParams.get('playerId')
    queueMicrotask(() => this.emit('message', { data: JSON.stringify({ type: 'bound',
      playerId: id === 'p99' && FixtureSocket.wrongBind ? 'another-player' : id }) }))
  }
  close() {
    if (this.readyState === 3) return
    this.readyState = 3
    this.emit('close', {})
  }
}
FixtureSocket.wrongBind = false
const manifest = () => ({ schemaVersion: 1, targetOrigin: 'http://127.0.0.1:8299',
  durationSeconds: 1,
  players: Array.from({ length: 5000 }, (_, n) => ({ playerId: `p${n}`, token: `secret-${n}`,
    march: { toX: 10, toY: 20, action: 'SCOUT', units: [{ unitId: 'unit_infantry_t1', count: 1 }] } })) })
const admission = () => ({ sourceSha: 'a'.repeat(40), targetOrigin: 'http://127.0.0.1:8299',
  maxConnections: 5000, maxMarchQps: 200, runId: 'fixture',
  notBeforeUtc: '1970-01-01T00:00:00.000Z', notAfterUtc: '2100-01-01T00:00:00Z' })
const receipt = () => ({ sourceSha: 'a'.repeat(40), targetOrigin: 'http://127.0.0.1:8299',
  manifestSha256: 'manifest-fixture', resourceProofSha256: 'resource-fixture' })
const clock = () => {
  let ms = 0
  return { now: () => ms, sleep: async (waitMs) => { ms += waitMs } }
}

test('the integrated fixture counts 5000 bound clients and 200 HTTP slots, then closes all', async () => {
  sockets.length = 0
  FixtureSocket.wrongBind = false
  const time = clock()
  const report = await executeB13Load({ manifest: manifest(), admission: admission(),
    receipt: receipt(), WebSocketCtor: FixtureSocket,
    fetchImpl: async () => ({ status: 200, json: async () => ({ code: 0 }) }),
    wallNow: () => 1, monotonicNow: time.now, sleep: time.sleep })
  assert.equal(report.status, 'CANDIDATE_MEASURED_NOT_ACCEPTED')
  assert.equal(report.load.launched, 200)
  assert.equal(report.load.successful, 200)
  assert.equal(report.load.minBound, 5000)
  assert.deepEqual(report.cleanup, { closed: 5000, total: 5000 })
  assert.ok(!JSON.stringify(report).includes('secret-'))
})

test('wrong bind fails before any march and every opened fixture socket closes', async () => {
  sockets.length = 0
  FixtureSocket.wrongBind = true
  const report = await executeB13Load({ manifest: manifest(), admission: admission(),
    receipt: receipt(), WebSocketCtor: FixtureSocket,
    fetchImpl: async () => { throw new Error('must not march') }, wallNow: () => 1 })
  FixtureSocket.wrongBind = false
  assert.equal(report.status, 'FAILED_BEFORE_COMPLETE_MEASUREMENT')
  assert.equal(report.failureStage, 'connect')
  assert.equal(report.load, null)
  assert.ok(sockets.every((socket) => socket.readyState === 3))
})

test('a bound connection lost during marching prevents candidate status', async () => {
  sockets.length = 0
  const time = clock()
  let calls = 0
  const report = await executeB13Load({ manifest: manifest(), admission: admission(),
    receipt: receipt(), WebSocketCtor: FixtureSocket,
    fetchImpl: async () => {
      if (++calls === 1) sockets[0].close()
      return { status: 200, json: async () => ({ code: 0 }) }
    }, wallNow: () => 1, monotonicNow: time.now, sleep: time.sleep })
  assert.equal(report.status, 'MEASURED_TARGET_NOT_MET_OR_CLEANUP_INCOMPLETE')
  assert.equal(report.load.stoppedReason, 'bound_connections_lost')
  assert.equal(report.load.minBound, 4999)
  assert.equal(report.cleanup.closed, 5000)
})

test('CLI without an admission cannot open sockets or report a pass', () => {
  const cli = spawnSync(process.execPath,
    [fileURLToPath(new URL('./run-b13-online-load.mjs', import.meta.url))],
    { encoding: 'utf8' })
  assert.equal(cli.status, 2)
  assert.match(cli.stderr, /用法/)
})

test('a reserved report is rewritten at byte zero with no stale placeholder tail', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'b13-report-'))
  const filename = path.join(dir, 'report.json')
  const handle = await open(filename, 'wx')
  try {
    await handle.writeFile(JSON.stringify({ status: 'RUNNING_NO_RESULT_YET', old: 'x'.repeat(800) }))
    await replaceReservedReport(handle, { status: 'MEASURED_TARGET_NOT_MET_OR_CLEANUP_INCOMPLETE' })
    const bytes = await readFile(filename)
    assert.deepEqual(JSON.parse(bytes.toString('utf8')),
      { status: 'MEASURED_TARGET_NOT_MET_OR_CLEANUP_INCOMPLETE' })
    assert.ok(!bytes.includes(Buffer.from('RUNNING_NO_RESULT_YET')))
  } finally {
    await handle.close()
    assert.equal(path.dirname(dir), path.resolve(tmpdir()))
    await rm(dir, { recursive: true, force: true })
  }
})
