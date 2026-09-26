/**
 * 职责：钉住体力详情弹层**要画的那几行**（B09 §5）—— 判定与文案。
 * 依赖：node:test / node:assert。
 *
 * <p>这个文件是"`/stamina` 与 `/stamina/buy` 明明有端点、玩家却看不到也买不了"那一格的**判据**：
 * 三条协议硬要求各有用例（倒计时用 `nextPointAt - serverNow`、满了不显示倒计时、
 * 到每日上限按钮置灰而不是隐藏）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { buildStaminaDetail, formatCountdown } from '../assets/scripts/game/ui/StaminaDetail'
import type { StaminaReading } from '../assets/scripts/game/ui/StaminaDetail'

function reading(overrides: Partial<StaminaReading> = {}): StaminaReading {
  return {
    current: 87,
    cap: 100,
    recoverPerHour: 12,
    nextPointAt: 1_700_000_300_000,
    boughtToday: 0,
    buyCostGold: 50,
    serverNow: 1_700_000_000_000,
    ...overrides,
  }
}

test('标题与恢复速率照服务端读数画，不自己算', () => {
  const view = buildStaminaDetail(reading(), 1000)
  assert.equal(view.titleText, '体力 87/100')
  assert.equal(view.recoverText, '每小时恢复 12 点')
  assert.equal(view.buyLabel, '买 1 次（50 金币）')
  assert.equal(view.boughtText, '今日已买 0 次')
})

test('倒计时用 nextPointAt − serverNow（协议明写：不得用本机时钟推算）', () => {
  const view = buildStaminaDetail(reading(), 1000)
  assert.equal(view.nextText, '下一点恢复：05:00')
  // 本机时间往前跑不影响它：两者之差是服务端给的
  const later = buildStaminaDetail(reading({ serverNow: 1_700_000_120_000, nextPointAt: 1_700_000_300_000 }), 1000)
  assert.equal(later.nextText, '下一点恢复：03:00')
})

test('体力已满：不显示倒计时（协议：满了就不该再显示倒计时）', () => {
  const atCap = buildStaminaDetail(reading({ current: 100 }), 1000)
  assert.equal(atCap.nextText, null)
  // 服务端把 nextPointAt 给成 null 时同理
  assert.equal(buildStaminaDetail(reading({ nextPointAt: null }), 1000).nextText, null)
})

test('到每日上限：按钮**置灰而不是隐藏**，并说明明天还能买（协议给的理由）', () => {
  const soldOut = buildStaminaDetail(reading({ buyCostGold: 0 }), 100_000)
  assert.equal(soldOut.buyEnabled, false, '到上限时不给点')
  assert.equal(soldOut.buyLabel, '今日已买满')
  assert.equal(soldOut.noteText, '明天还能再买')
})

test('金币不足也要说清差多少（而不是默默点不动）', () => {
  const poor = buildStaminaDetail(reading({ buyCostGold: 50 }), 30)
  assert.equal(poor.buyEnabled, false)
  assert.equal(poor.noteText, '金币不足（还差 20）')
})

test('体力已满时买了会溢出：提示照实说（B09 §5：溢出不结转）', () => {
  const full = buildStaminaDetail(reading({ current: 100 }), 1000)
  assert.equal(full.buyEnabled, true, '金币够就能买（判定在服务端）')
  assert.equal(full.noteText, '体力已满，买了会溢出损失')
})

test('倒计时格式化：负数不出现负号（不许把"过点了"画成 -00:03）', () => {
  assert.equal(formatCountdown(-5000), '00:00')
  assert.equal(formatCountdown(65_000), '01:05')
  assert.equal(formatCountdown(3_725_000), '1:02:05')
})
