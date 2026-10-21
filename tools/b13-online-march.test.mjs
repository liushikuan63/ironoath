import assert from 'node:assert/strict'
import test from 'node:test'
import { runMarchLoad } from './b13-online-march.mjs'

const players = () => Array.from({ length: 5000 }, (_, n) => ({ playerId: `p${n}`,
  token: `secret-${n}`, march: { toX: 10, toY: 20, action: 'SCOUT',
    units: [{ unitId: 'unit_infantry_t1', count: 1 }] } }))
const fleet = () => ({ state: { bound: 5000, peakBound: 5000, unexpectedClosures: 0 } })
const clock = () => {
  let ms = 0
  return { now: () => ms, sleep: async (waitMs) => { ms += waitMs }, advance: (by) => { ms += by } }
}
const ok = async () => ({ status: 200, json: async () => ({ code: 0 }) })
const options = (time, extras = {}) => ({ targetOrigin: 'http://127.0.0.1:8299', runId: 'fixture',
  durationSeconds: 1, rate: 200, notAfterUtc: '2100-01-01T00:00:00Z',
  monotonicNow: time.now, wallNow: () => 0, sleep: time.sleep, fetchImpl: ok, ...extras })

test('200 scheduled slots send 200 unique real MarchReq-shaped POSTs without leaking tokens in report', async () => {
  const time = clock()
  const requests = []
  const report = await runMarchLoad(players(), fleet(), options(time, {
    fetchImpl: async (url, init) => { requests.push({ url, init }); return ok() },
  }))
  assert.equal(report.planned, 200)
  assert.equal(report.launched, 200)
  assert.equal(report.successful, 200)
  assert.deepEqual(report.launchedBySecond, [200])
  assert.equal(report.minBound, 5000)
  assert.equal(report.stoppedReason, null)
  assert.equal(requests[0].url, 'http://127.0.0.1:8299/world/march')
  assert.equal(requests[0].init.headers['X-Player-Id'], 'p0')
  assert.equal(JSON.parse(requests[199].init.body).requestId, 'fixture-199')
  assert.ok(!JSON.stringify(report).includes('secret-'))
})

test('late slots are recorded as missed rather than replayed in a catch-up burst', async () => {
  const time = clock()
  const lateSleep = async (waitMs) => { time.advance(waitMs + 50) }
  const report = await runMarchLoad(players(), fleet(), options(time, { sleep: lateSleep }))
  assert.ok(report.launched < 200)
  assert.ok(report.missedSchedule > 0)
  assert.equal(report.launched + report.missedSchedule, 200)
  for (let i = 1; i < report.samples.length; i++) {
    assert.ok(report.samples[i].launchedAtMs - report.samples[i - 1].launchedAtMs >= 4.9,
      '延迟后不能在同一时刻补发多条请求')
  }
})

test('capacity pressure, lost bound connections and UTC expiry stay visible in results', async () => {
  const time = clock()
  const slow = () => new Promise((resolve) => setTimeout(() => resolve(ok()), 15))
  const capacity = await runMarchLoad(players(), fleet(), options(time, {
    maxInFlight: 2, fetchImpl: slow,
  }))
  assert.equal(capacity.launched, 2)
  assert.equal(capacity.missedCapacity, 198)

  const disconnected = fleet()
  const time2 = clock()
  const dropSleep = async (waitMs) => {
    time2.advance(waitMs)
    if (time2.now() >= 50) { disconnected.state.bound = 4999; disconnected.state.unexpectedClosures = 1 }
  }
  const lost = await runMarchLoad(players(), disconnected, options(time2, { sleep: dropSleep }))
  assert.equal(lost.stoppedReason, 'bound_connections_lost')
  assert.equal(lost.minBound, 4999)

  const time3 = clock()
  const expired = await runMarchLoad(players(), fleet(), options(time3, {
    wallNow: time3.now, notAfterUtc: '1970-01-01T00:00:00.050Z',
  }))
  assert.equal(expired.stoppedReason, 'admission_window_expired')
  assert.ok(expired.launched < 200)
})
