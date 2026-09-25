/**
 * 职责：StaminaBoard 的单测 —— 体力那一屏的展示口径。
 * 依赖：node:test / node:assert。
 *
 * <p>盯的是三件会坑到玩家的事：
 * <ol>
 *   <li><b>把「今日还剩几次」编出来</b>。协议只下发 boughtToday 与 buyCostGold，
 *       每日上限没下来；客户端要显示"还剩几次"就得抄配置（铁律 2）</li>
 *   <li><b>体力已满时不告诉玩家会白扣金币</b>。协议注释写明溢出永久损失而金币照扣，
 *       服务端不替玩家判断，这句话必须由界面在点下去之前说</li>
 *   <li><b>用客户端自己的时钟推算倒计时</b>。校时误差会让倒计时跳变，
 *       判据是"响应整体平移一个偏移量时，剩下的秒数不变"</li>
 * </ol>
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { buildStaminaBoard } from '../assets/scripts/game/stage/StaminaBoard'
import type { StaminaResp } from '../assets/scripts/net/generated/Protocol'

const NOW = 1_700_000_000_000

function resp(overrides: Partial<StaminaResp> = {}): StaminaResp {
  return {
    current: 84, cap: 120, recoverPerHour: 5, nextPointAt: NOW + 192_000,
    boughtToday: 2, buyCostGold: 20, serverNow: NOW,
    ...overrides,
  }
}

test('正常态：数值、恢复、购买三行都按服务端下发的说', () => {
  const view = buildStaminaBoard(resp(), 500)
  assert.equal(view.valueText, '体力 84/120')
  assert.equal(view.buyText, '买 1 次 · 20 金币 · 今日已购 2 次')
  assert.equal(view.goldText, '金币 500')
  assert.equal(view.buyBlocked, false)
  assert.equal(view.buyBlockedReason, null)
})

test('倒计时用 nextPointAt 与 serverNow 之差，不碰客户端自己的时钟', () => {
  const offset = 3_600_000
  // 整个响应（含 serverNow）平移一小时：真实剩余没变，显示也不许变
  const shifted = buildStaminaBoard(resp({
    nextPointAt: NOW + 192_000 + offset, serverNow: NOW + offset,
  }), 500)
  assert.equal(shifted.recoverText, buildStaminaBoard(resp(), 500).recoverText)
  assert.match(shifted.recoverText, /3 分 12 秒后 \+1/)
})

test('已满时不显示倒计时，改说「不再恢复」', () => {
  const view = buildStaminaBoard(resp({ current: 120, nextPointAt: null }), 500)
  assert.equal(view.recoverText, '已满，不再恢复')
})

test('恢复时刻已经过了但还没结算进来：说「即将恢复」而不是 0 秒后', () => {
  const view = buildStaminaBoard(resp({ nextPointAt: NOW - 1000 }), 500)
  assert.equal(view.recoverText, '每小时 +5 · 即将恢复')
})

test('体力已满：这一按直接灰掉，并把「白扣金币」写在按下去之前', () => {
  const view = buildStaminaBoard(resp({ current: 120 }), 500)
  assert.equal(view.buyBlocked, true)
  assert.equal(view.buyBlockedReason, '体力已满，现在买会白扣金币（溢出部分不结转）')
  // 灰而不是藏：玩家要能看见这颗键、并知道为什么点不动
  assert.equal(view.buyText, '买 1 次 · 20 金币 · 今日已购 2 次')
})

test('今日买满（buyCostGold=0）：灰掉但仍在，且说清为什么', () => {
  const view = buildStaminaBoard(resp({ buyCostGold: 0 }), 500)
  assert.equal(view.buyBlocked, true)
  assert.equal(view.buyBlockedReason, '今日购买次数已达上限，明天再来')
  assert.equal(view.buyText, '今日 2 次已买满')
})

test('既满了又买满：说「今日上限」那一条（今天已经买不动，白扣与否轮不到说）', () => {
  const view = buildStaminaBoard(resp({ current: 120, buyCostGold: 0 }), 500)
  assert.equal(view.buyBlockedReason, '今日购买次数已达上限，明天再来')
})

test('金币不足：说清还差多少，而不是只灰一颗键', () => {
  const view = buildStaminaBoard(resp(), 15)
  assert.equal(view.buyBlocked, true)
  assert.equal(view.buyBlockedReason, '金币不足，还差 5')
})

test('金币余额没读到：不拦购买，也不谎称是 0', () => {
  const view = buildStaminaBoard(resp(), null)
  assert.equal(view.goldText, '金币余额还没读到')
  assert.equal(view.buyBlocked, false)
  assert.equal(view.buyBlockedReason, null)
})

test('绝不编出「今日还剩 N 次」—— 每日上限没下发', () => {
  const view = buildStaminaBoard(resp(), 500)
  for (const text of [view.valueText, view.recoverText, view.buyText, view.goldText,
    view.buyBlockedReason ?? '']) {
    assert.doesNotMatch(text, /还剩/)
  }
})
