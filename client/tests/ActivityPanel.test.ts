/**
 * 职责：活动面板的展示组装用例（B17 §六）。
 * 依赖：node:test + game/activity/ActivityPanel（纯逻辑，不碰 cc）。
 *
 * <p><b>这些用例盯的是三件事</b>：① 客户端<b>不自己判</b>能不能领（铁律 2）—— 服务端的 state 变了，
 * 按钮就该跟着变；② 剩余时间由两个<b>服务端</b>时刻相减得出，不许出现负数或"0 秒"；
 * ③ 三种状态（含 EXPIRED「上一轮已结束」）各有各的文案，不能被错译成"进行中"。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildActivityList, claimActivityReq, claimReceiptText,
} from '../assets/scripts/game/activity/ActivityPanel'
import type { ActivityView } from '../assets/scripts/net/generated/ActivityProtocol'

const NOW = 1_800_000_000_000
const DAY = 86_400_000

function view(overrides: Partial<ActivityView> & { id: string }): ActivityView {
  return {
    name: '剿匪令', state: 'RUNNING', progress: 30, goal: 50,
    windowEndAt: NOW + 2 * DAY, claimed: false, ...overrides,
  }
}

test('进度与目标照搬服务端，大数字带千分位（捐献 2000 在面板上要读得出来）', () => {
  const list = buildActivityList({
    activities: [view({ id: 'a1', progress: 1200, goal: 2000 })],
    serverNow: NOW, claimableCount: 0,
  }, NOW)
  assert.equal(list.rows[0]?.progressText, '1,200/2,000')
})

test('可领 = 服务端说 state 是 CLAIMABLE：客户端不看进度够不够（铁律 2）', () => {
  // 进度已经超过目标，但服务端说 RUNNING（比如已领过）⇒ 不许亮按钮
  const claimed = buildActivityList({
    activities: [view({ id: 'a1', progress: 50, goal: 50, state: 'RUNNING', claimed: true })],
    serverNow: NOW, claimableCount: 0,
  }, NOW)
  assert.equal(claimed.rows[0]?.claimable, false)
  assert.equal(claimed.rows[0]?.statusText, '本轮已领取')

  // 反过来：进度没到目标但服务端说 CLAIMABLE ⇒ 按钮必须亮（判定权在服务端）
  const claimable = buildActivityList({
    activities: [view({ id: 'a1', progress: 0, goal: 50, state: 'CLAIMABLE' })],
    serverNow: NOW, claimableCount: 1,
  }, NOW)
  assert.equal(claimable.rows[0]?.claimable, true)
  assert.equal(claimable.rows[0]?.statusText, '可领取')
})

test('EXPIRED 有自己的一句话（「上一轮已结束」），不被翻译成进行中', () => {
  const list = buildActivityList({
    activities: [view({ id: 'a1', state: 'EXPIRED', progress: 30 })],
    serverNow: NOW, claimableCount: 0,
  }, NOW)
  assert.equal(list.rows[0]?.statusText, '上一轮已结束')
  assert.equal(list.rows[0]?.claimable, false)
})

test('剩余时间四种量级各一句话：天 / 小时 / 分 / 已结束；常驻活动是「长期开放」', () => {
  const rows = buildActivityList({
    activities: [
      view({ id: 'd', windowEndAt: NOW + 2 * DAY + 3 * 3_600_000 }),
      view({ id: 'h', windowEndAt: NOW + 5 * 3_600_000 + 30 * 60_000 }),
      view({ id: 'm', windowEndAt: NOW + 90_000 }),
      view({ id: 'over', windowEndAt: NOW }),
      view({ id: 'ever', windowEndAt: null }),
    ],
    serverNow: NOW, claimableCount: 0,
  }, NOW)
  assert.equal(rows.rows[0]?.remainingText, '剩余 2 天 3 小时')
  assert.equal(rows.rows[1]?.remainingText, '剩余 5 小时 30 分')
  assert.equal(rows.rows[2]?.remainingText, '剩余 1 分')
  assert.equal(rows.rows[3]?.remainingText, '已结束')
  assert.equal(rows.rows[4]?.remainingText, '长期开放')
})

test('剩余时间用服务端给的两个时刻相减：本地时钟拨快一年也改不了读数（铁律 5）', () => {
  const resp = {
    activities: [view({ id: 'a1', windowEndAt: NOW + DAY })],
    serverNow: NOW, claimableCount: 0,
  }
  // 同一条响应配不同的"现在"：剩下的天数由参数决定，与进程时钟无关
  assert.equal(buildActivityList(resp, NOW).rows[0]?.remainingText, '剩余 1 天')
  assert.equal(buildActivityList(resp, NOW + DAY / 2).rows[0]?.remainingText, '剩余 12 小时')
})

test('可领数为 0 时不显示「0 个奖励可领取」', () => {
  const zero = buildActivityList({ activities: [], serverNow: NOW, claimableCount: 0 }, NOW)
  assert.equal(zero.claimableText, null)
  const some = buildActivityList({ activities: [], serverNow: NOW, claimableCount: 2 }, NOW)
  assert.equal(some.claimableText, '2 个奖励可领取')
})

test('领取请求只带 activityId；空 id 当场抛（那是一次注定打不中的请求）', () => {
  assert.deepEqual(claimActivityReq('activity_pvp_win'), { activityId: 'activity_pvp_win' })
  assert.throws(() => claimActivityReq(''), /activityId/)
})

test('回执用的是服务端回来的入账量：背包满时一部分走邮件，界面不能替它说"已发"', () => {
  assert.equal(claimReceiptText({
    claimed: true, state: 'RUNNING',
    rewards: [
      { type: 'RESOURCE', id: 'GOLD', count: 500, name: '金币' },
      { type: 'ITEM', id: 'item_speedup_train_1h', count: 2, name: '一小时训练令' },
    ],
  }), '已领取：金币 ×500、一小时训练令 ×2')
  // 什么都没入账（全走邮件兜底）⇒ 空串，界面据此不显示那一行，而不是显示断句的「已领取：」
  assert.equal(claimReceiptText({ claimed: true, state: 'RUNNING', rewards: [] }), '')
})

test('响应为空 / 现在不是有限数：当场抛，而不是画一屏假数据', () => {
  assert.throws(() => buildActivityList(undefined as never, NOW), /resp/)
  assert.throws(() => buildActivityList({ activities: [], serverNow: NOW, claimableCount: 0 }, Number.NaN),
    /nowMs/)
})
