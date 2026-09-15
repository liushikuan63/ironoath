/**
 * 职责：首屏自报计时（`BootClock`）的证据（B16 §一「首屏可交互」在小游戏运行时里的唯一输入）。
 * 依赖：真实的 BootClock 模块（纯函数 + 一个模块级锚点）。
 *
 * <p><b>为什么要单测一个"看表"的模块</b>：这条读数在小游戏运行时里没有任何东西可以对照
 * —— 那边没有外部墙钟，DevTools 里也只有一个数字。所以它坏了不会有人发现，
 * 而它坏的方向恰好是"看起来更好"：锚点被推迟 → 数变小 → 首屏"达标"。
 * 这里断言的三件事都是朝那个方向兜底的。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { bootElapsedMs } from '../assets/scripts/game/session/BootClock'

test('刚导入就来读：是一个有限的非负毫秒数，且没有落在错误的时间原点上', () => {
  const ms = bootElapsedMs()

  assert.equal(typeof ms, 'number', '必须是数，不能是 Date 或字符串 —— 它要直接进 [boot] 的 JSON')
  assert.ok(Number.isFinite(ms), `读数不是有限数：${ms}`)
  assert.ok(ms >= 0, `读数不该为负：${ms}`)
  // BootClock 的锚点在本文件 body 之前就已求值（import 先执行），两者之间只有几毫秒。
  // 上限给到 60 秒不是为了容忍慢机器，是为了抓住"锚点根本不在本包 JS 求值时"这一类错
  //（比如有人把它改成取当前时刻 —— 那会永远读出 0，而 0 是这条指标最假的绿灯）
  assert.ok(ms < 60_000, `读数大到不像"本模块刚被求值"：${ms}ms`)
})

test('结束时刻越晚读数越大，且差值就是两个时刻之差（计时是差值而不是绝对时刻）', () => {
  const base = Date.now()

  assert.equal(bootElapsedMs(base + 5_000) - bootElapsedMs(base), 5_000)
  assert.ok(bootElapsedMs(base + 30_000) > bootElapsedMs(base + 5_000))
})

test('时钟被往回调（玩家改表或系统 NTP 校正）⇒ 读 0 而不是负数', () => {
  // 负数经 JSON 出去会被读成一个荒谬的小值，"启动只用了 0ms"正是最不该出现的绿灯；
  // 但按 0 之外没有其他可解释的值 —— 夹在 0 上是让坏掉的测量显形为"没有信息"而不是"信息很好"
  assert.equal(bootElapsedMs(0), 0, '时钟倒拨时给出 0')
  assert.equal(bootElapsedMs(-1_000), 0)
})
