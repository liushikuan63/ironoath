/**
 * 职责：客户端 FixedPoint 单测 —— 验证与服务端 Java FixedPoint 的舍入语义一致。
 * 依赖：node:test / node:assert。
 *
 * 重点不是「算得对」，而是「和服务端算得一样」：
 * 客户端预测的倒计时如果与服务端结算差 1 个单位，玩家就会看到「倒计时归零了但资源没到」。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import * as Fixed from '../assets/scripts/core/FixedPoint'

test('parse 与 format 往返，且拒绝超过 4 位小数', () => {
  assert.equal(Fixed.parse('1.18'), 11800)
  assert.equal(Fixed.parse('-0.95'), -9500)
  assert.equal(Fixed.parse('0'), 0)
  assert.equal(Fixed.parse(' 2.0 '), 20000)
  assert.equal(Fixed.format(11800), '1.18')
  assert.equal(Fixed.format(10000), '1')
  assert.equal(Fixed.format(0), '0')
  assert.equal(Fixed.format(15000), '1.5')

  assert.throws(() => Fixed.parse('1.00001'), RangeError)
  assert.throws(() => Fixed.parse(''), Error)
  assert.throws(() => Fixed.parse('abc'), Error)
})

test('parse 不受浮点尾巴影响：0.29 × 10000 在 JS 里是 2899.999…', () => {
  assert.equal(Fixed.parse('0.29'), 2900)
  assert.equal(Fixed.parse('0.07'), 700)
  assert.equal(Fixed.parse('1.005'), 10050)
})

test('HALF_UP 舍入与服务端一致，负数向远离零方向进位', () => {
  // 服务端 FixedPointTest 的同一组断言
  assert.equal(Fixed.mul(3, 5000), 2)
  assert.equal(Fixed.mul(-3, 5000), -2)
  assert.equal(Fixed.mul(2, 5000), 1)

  assert.equal(Fixed.div(Fixed.ONE, Fixed.of(3)), 3333)
  assert.equal(Fixed.div(Fixed.of(2), Fixed.of(3)), 6667)
  assert.equal(Fixed.div(-Fixed.ONE, Fixed.of(3)), -3333)
  assert.equal(Fixed.div(-Fixed.of(2), Fixed.of(3)), -6667)

  assert.equal(Fixed.div(1, Fixed.of(2)), 1)
  assert.equal(Fixed.div(-1, Fixed.of(2)), -1)
})

test('不用 Math.round：它对负数是向 +∞ 取整，会与服务端差 1', () => {
  // Math.round(-1.5) === -1，而服务端 HALF_UP 要求 -2
  assert.equal(Math.round(-1.5), -1)
  assert.equal(Fixed.round(-15000), -2)
  assert.equal(Fixed.round(15000), 2)
  assert.equal(Fixed.round(14999), 1)
})

test('四则运算与 clamp', () => {
  assert.equal(Fixed.add(Fixed.ONE, Fixed.ONE), Fixed.of(2))
  assert.equal(Fixed.sub(Fixed.ONE, Fixed.ONE), Fixed.ZERO)
  assert.equal(Fixed.percent(2000, Fixed.parse('0.15')), 300)
  assert.equal(Fixed.truncate(19999), 1)
  assert.equal(Fixed.truncate(-19999), -1)
  assert.equal(Fixed.clamp(150, 100, 200), 150)
  assert.equal(Fixed.clamp(50, 100, 200), 100)
  assert.equal(Fixed.clamp(500, 100, 200), 200)
  assert.throws(() => Fixed.clamp(150, 200, 100), RangeError)
  assert.throws(() => Fixed.div(Fixed.ONE, 0), RangeError)
})

test('超出 JS 安全整数范围时抛错，不静默失真', () => {
  assert.throws(() => Fixed.mul(Number.MAX_SAFE_INTEGER, Fixed.ONE), RangeError)
  assert.throws(() => Fixed.of(Number.MAX_SAFE_INTEGER), RangeError)
})

test('几何曲线：1.18^7 的量级与服务端一致（用于建筑倒计时显示）', () => {
  // 服务端 Formula 算 30 × 1.18^7 = 95.564…，四舍五入 96 秒
  const base = Fixed.of(30)
  const ratio = Fixed.parse('1.18')
  const fixed = Fixed.geometric(base, ratio, 7)
  assert.equal(Fixed.round(fixed), 96)
  assert.equal(Fixed.round(Fixed.geometric(base, ratio, 0)), 30)
  assert.equal(Fixed.round(Fixed.geometric(base, ratio, 1)), 35)
  assert.throws(() => Fixed.geometric(base, ratio, -1), RangeError)
})

test('客户端几何曲线是逐步舍入的近似值：与服务端精确值的偏差必须很小', () => {
  // 服务端用 BigDecimal 一次舍入，客户端逐步定点相乘；高等级下会累积偏差。
  // 这里锁定偏差量级，确保它不足以影响倒计时显示（1 秒以内）。
  const base = Fixed.of(30)
  const ratio = Fixed.parse('1.18')
  const client = Fixed.geometric(base, ratio, 20)

  let exact = 30
  for (let i = 0; i < 20; i++) {
    exact *= 1.18
  }
  const deviationSeconds = Math.abs(Fixed.toNumber(client) - exact)
  assert.ok(deviationSeconds < 1, `偏差 ${deviationSeconds} 秒，超出倒计时显示可接受范围`)
})
