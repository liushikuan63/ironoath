/**
 * 职责：core/Countdown 的单测 —— 城建升级、兵种训练、伤兵治疗、行军返程共用的那一套换算。
 * 依赖：node:test / node:assert。
 *
 * <p>这两条纪律一旦写错就不会报错，只会让玩家看到负数倒计时、
 * 或者发现「把手机时间调快就能立刻完成」，所以单独钉住。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { countdownMs, formatCountdown } from '../assets/scripts/core/Countdown'

const HOUR = 3600_000

test('换算必须走 TimeSync 偏移：本地时钟快一小时也不会让升级立刻完成（铁律 5）', () => {
  const finishAt = 10 * HOUR
  // 玩家把手机时间调快一小时 ⇒ offsetMs = 服务端时刻 - 本地时刻 = -1 小时
  assert.equal(countdownMs(finishAt, -HOUR, 9 * HOUR), 2 * HOUR,
    '本地 9 点其实是服务端 8 点，距 10 点还有 2 小时')
  // 反过来，本地慢一小时
  assert.equal(countdownMs(finishAt, HOUR, 9 * HOUR), 0,
    '本地 9 点其实是服务端 10 点，已经到点了')
  // 不带偏移直接减会算成 1 小时，正好差出玩家调的那一小时
  assert.notEqual(countdownMs(finishAt, -HOUR, 9 * HOUR), finishAt - 9 * HOUR)
})

test('绝不为负：到点之后停在 0，不在进行中时为 null', () => {
  assert.equal(countdownMs(1000, 0, 5000), 0)
  assert.equal(countdownMs(1000, 0, 1000), 0)
  assert.equal(countdownMs(1000, 0, 999), 1)
  assert.equal(countdownMs(null, 0, 0), null)
})

test('偏移不是有限数时立刻抛错：静默用 NaN 会让所有倒计时都变成 NaN，画面上是一片空白', () => {
  assert.throws(() => countdownMs(1000, Number.NaN, 0), /有限数/)
  assert.throws(() => countdownMs(1000, Number.POSITIVE_INFINITY, 0), /有限数/)
  // 不在进行中时不校验偏移：没有 finishAt 就根本不参与换算
  assert.equal(countdownMs(null, Number.NaN, 0), null)
})

test('文本按 时/分/秒 分档，个位数补零，不足一秒显示 00 秒', () => {
  assert.equal(formatCountdown(3_600_000, '完成'), '1小时00分00秒')
  assert.equal(formatCountdown(3_723_000, '完成'), '1小时02分03秒')
  assert.equal(formatCountdown(123_000, '完成'), '02分03秒')
  assert.equal(formatCountdown(999, '完成'), '00分00秒', '不显示毫秒：跳到毫秒根本看不清')
  assert.equal(formatCountdown(59_999, '完成'), '00分59秒', '截断而不是四舍五入')
})

test('到点（含负数）时返回调用方给的行动提示，各面板措辞不撞车', () => {
  assert.equal(formatCountdown(0, '可收割'), '可收割')
  assert.equal(formatCountdown(-5000, '可收割'), '可收割', '负数也必须落到同一个提示，绝不能显示 -5 秒')
  assert.equal(formatCountdown(0, '已完成，可收取'), '已完成，可收取')
})
