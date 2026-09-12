/**
 * 职责：TimeSync 单测 —— 覆盖 B01 验收 7 的客户端侧（时钟快/慢 5 分钟，误差 < 1s）。
 * 依赖：node:test / node:assert、core/FixedPoint。
 *
 * 与服务端 TimeServiceTest.assertConverges 是同一个场景的两端：
 * 服务端算 offset，客户端做 rtt/2 补偿 + 加权移动平均。两边都要过，链路才算通。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import * as Fixed from '../core/FixedPoint'
import { TimeSync } from '../core/TimeSync'

const SERVER_BASE_MS = 1_788_000_000_000
const FIVE_MINUTES_MS = 5 * 60 * 1000
const TOLERANCE_MS = 1000

/** 参数取自 contract/config/global.json 的 TIME_SYNC_* 四项。 */
function newTracker(now: () => number = () => SERVER_BASE_MS): TimeSync {
  return new TimeSync(
    {
      alphaFixed: Fixed.parse('0.2'),
      jitterFactorFixed: Fixed.parse('3.0'),
      initialBestRttMs: 150,
    },
    now,
  )
}

/**
 * 完整模拟一次时间同步：服务端时钟前进、客户端有固定偏移、网络有单程延迟。
 * 返回每次同步后「客户端估计的服务端时间」与真实服务端时间的误差。
 */
function simulate(skewMs: number, rounds: number, oneWayLatencyMs: number): number[] {
  let serverNow = SERVER_BASE_MS
  const tracker = newTracker()
  const errors: number[] = []

  for (let i = 0; i < rounds; i++) {
    const serverSendMs = serverNow
    const clientSendMs = serverSendMs + skewMs

    serverNow += oneWayLatencyMs
    const rawOffset = serverNow - clientSendMs

    serverNow += oneWayLatencyMs
    const clientReceiveMs = serverNow + skewMs
    const rttMs = clientReceiveMs - clientSendMs

    tracker.offer(rttMs, rawOffset)
    errors.push(Math.abs(tracker.serverNow(clientReceiveMs) - serverNow))
  }
  return errors
}

test('验收7：客户端时钟慢 5 分钟，12 次同步后每次误差都 < 1s', () => {
  for (const error of simulate(-FIVE_MINUTES_MS, 12, 40)) {
    assert.ok(error < TOLERANCE_MS, `误差 ${error}ms 超出 ${TOLERANCE_MS}ms`)
  }
})

test('验收7：客户端时钟快 5 分钟，12 次同步后每次误差都 < 1s', () => {
  for (const error of simulate(FIVE_MINUTES_MS, 12, 40)) {
    assert.ok(error < TOLERANCE_MS, `误差 ${error}ms 超出 ${TOLERANCE_MS}ms`)
  }
})

test('rtt/2 补偿是必要的：不补偿会系统性超前一个单程延迟', () => {
  // 单程 200ms（RTT 400ms）。补偿后误差应为 0，不补偿则会稳定超前 200ms
  const errors = simulate(0, 20, 200)
  assert.ok(errors.every((e) => e < 5), `补偿后误差不应超过 5ms，实际最大 ${Math.max(...errors)}ms`)
})

test('未校准时 isCalibrated 为 false，serverNow 原样返回本地时间', () => {
  const tracker = newTracker()
  assert.equal(tracker.isCalibrated(), false)
  assert.equal(tracker.offsetMs(), 0)
  assert.equal(tracker.serverNow(12345), 12345)
})

test('抖动极值被剔除：RTT 超过历史最佳值 3 倍的样本不进入平均线', () => {
  const tracker = newTracker()
  tracker.offer(50, 1000)
  assert.equal(tracker.bestRttMs(), 50)

  tracker.offer(5000, 999999)
  assert.equal(tracker.rejectedSamples(), 1)
  assert.notEqual(tracker.offsetMs(), 999999)

  tracker.offer(60, 1010)
  assert.equal(tracker.acceptedSamples(), 2)
})

test('加权移动平均按 alpha=0.2 收敛，与服务端 TimeOffsetTracker 逐步一致', () => {
  const tracker = newTracker()
  tracker.offer(20, 1000)
  assert.equal(tracker.offsetMs(), 990)

  tracker.offer(20, 2000)
  assert.equal(tracker.offsetMs(), 1190)

  tracker.offer(20, 2000)
  assert.equal(tracker.offsetMs(), 1350)

  for (let i = 0; i < 60; i++) {
    tracker.offer(20, 2000)
  }
  // 定点 EMA 的不动点带宽是 2，稳态残差最大 2ms
  assert.ok(Math.abs(tracker.offsetMs() - 1990) <= 2, `实际 ${tracker.offsetMs()}`)
})

test('remainingMs 不会返回负数（倒计时归零后停在 0）', () => {
  const tracker = newTracker(() => 10_000)
  tracker.offer(0, 0)
  assert.equal(tracker.remainingMs(5000), 0)
  assert.equal(tracker.remainingMs(20_000), 10_000)
})

test('构造参数校验：alpha 必须落在 (0,1.0]，jitterFactor 不得小于 1.0', () => {
  assert.throws(
    () => new TimeSync({ alphaFixed: 0, jitterFactorFixed: Fixed.of(3), initialBestRttMs: 100 }),
    RangeError,
  )
  assert.throws(
    () => new TimeSync({ alphaFixed: Fixed.ONE + 1, jitterFactorFixed: Fixed.of(3), initialBestRttMs: 100 }),
    RangeError,
  )
  assert.throws(
    () => new TimeSync({ alphaFixed: Fixed.parse('0.2'), jitterFactorFixed: Fixed.ONE - 1, initialBestRttMs: 100 }),
    RangeError,
  )
  assert.throws(
    () => new TimeSync({ alphaFixed: Fixed.parse('0.2'), jitterFactorFixed: Fixed.of(3), initialBestRttMs: 0 }),
    RangeError,
  )
  assert.throws(() => newTracker().offer(-1, 0), RangeError)
})
