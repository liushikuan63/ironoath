/**
 * 职责：流亡迁城按钮的判定与文案单测（B08 §5 反击工具箱）。
 * 依赖：node:test。
 *
 * <p>盯三件事：能不能点（含与服务端一致的判定顺序）、点之前告知了什么、
 * 以及倒计时用的是服务端时刻还是本地时刻 —— 最后这条错了不会报错，
 * 只会让玩家看到一个还在走的灰按钮，或者一个已经能点却还灰着的按钮。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  exileAvailability, exileCanRequest, exileConfirmText, exileHint, exileLabel, formatRemaining,
} from '../assets/scripts/game/world/ExileAction'
import type { ExileFacts } from '../assets/scripts/game/world/ExileAction'

const HOUR = 3_600_000
const DAY = 24 * HOUR

function facts(overrides: Partial<ExileFacts> = {}): ExileFacts {
  return {
    nextExileAt: null, troopsAway: 0, requesting: false, serverNow: 1_700_000_000_000, ...overrides,
  }
}

test('随时可以迁：按钮亮、写着「流亡迁城」、不占一行说明', () => {
  const f = facts()
  assert.equal(exileAvailability(f), 'ready')
  assert.equal(exileCanRequest(f), true)
  assert.equal(exileLabel(f), '流亡迁城')
  assert.equal(exileHint(f), '')
})

test('冷却中：按钮灰掉并把剩余时间写在文字里，而不是灰着不解释', () => {
  const f = facts({ nextExileAt: 1_700_000_000_000 + 2 * DAY + 3 * HOUR })
  assert.equal(exileAvailability(f), 'cooling')
  assert.equal(exileCanRequest(f), false)
  assert.equal(exileLabel(f), '冷却 2 天 3 小时')
  assert.match(exileHint(f), /3 天/)
})

test('有队伍在外：拒绝的理由是「先召回」，因为 returnFrom 存的是坐标而不是城', () => {
  const f = facts({ troopsAway: 2 })
  assert.equal(exileAvailability(f), 'troops-away')
  assert.equal(exileLabel(f), '先召回队伍')
  assert.match(exileHint(f), /召回/)
})

test('判定顺序与服务端一致：冷却和在外队伍同时成立时报冷却（服务端先查冷却）', () => {
  const f = facts({ nextExileAt: 1_700_000_000_000 + DAY, troopsAway: 3 })
  assert.equal(exileAvailability(f), 'cooling',
    '顺序反了就会出现「按钮提示先召回、点下去回冷却中」，玩家看到的是按钮在骗他')
})

test('请求在途时不可重复发起（迁城改世界坐标，两次点击不该搬两次）', () => {
  const f = facts({ requesting: true })
  assert.equal(exileAvailability(f), 'requesting')
  assert.equal(exileCanRequest(f), false)
  assert.match(exileLabel(f), /迁城中/)
})

test('倒计时按服务端时刻判：本地时钟慢半个钟头也不该把已过期的冷却显示成还能等', () => {
  // serverNow 已经越过 nextExileAt，哪怕墙上的本地时刻还差 30 分钟
  const expired = facts({ nextExileAt: 1_700_000_000_000 - 1, serverNow: 1_700_000_000_000 })
  assert.equal(exileAvailability(expired), 'ready')
  const stillCooling = facts({ nextExileAt: 1_700_000_000_000 + 1, serverNow: 1_700_000_000_000 })
  assert.equal(exileAvailability(stillCooling), 'cooling')
})

test('二次确认必须一次说全三件事：落点随机、免战多久、多久之后才能再搬', () => {
  const text = exileConfirmText(facts())
  assert.match(text, /随机/)
  assert.match(text, /12 小时/)
  assert.match(text, /3 天/)
  assert.match(text, /盟友会暂时找不到你/,
    '搬家最大的隐性代价是社交断联，不写出来玩家会在搬完之后才知道')
})

test('剩余时长的折叠：分钟 → 小时 → 天，且一律向上取整不显示 0', () => {
  assert.equal(formatRemaining(0), '现在')
  assert.equal(formatRemaining(60_000), '1 分钟')
  assert.equal(formatRemaining(59_000), '1 分钟', '不足一分钟也要显示 1 分钟，不能让按钮显示「冷却 0 分钟」')
  assert.equal(formatRemaining(60 * 60_000), '1 小时')
  assert.equal(formatRemaining(75 * 60_000), '1 小时 15 分')
  assert.equal(formatRemaining(25 * HOUR), '1 天 1 小时')
})
