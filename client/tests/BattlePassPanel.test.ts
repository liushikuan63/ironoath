/**
 * 职责：战令面板的纯逻辑用例（B24 S-d-e）。
 * 依赖：node:test + game/battlePass/BattlePassPanel（不碰 cc）。
 *
 * <p><b>这些用例盯的是四处最容易做假的地方</b>：① 能不能领**只信服务端那三/四个结论位**
 * （客户端不重新比积分）；② 两条线各点各的（领了免费那份不影响付费那份）；
 * ③ 付费线没解锁时付费那颗**不能可点**（否则玩家点下去得到一句报错）；
 * ④ 窗口起点跟着"接下来该领哪一档"走，且被跳过的档位由表头交代（不静默少画）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildBattlePassPanel, buildBattlePassRow, claimBodyOf, claimResultText, remainTextOf,
  trackStateText, windowStartOf,
} from '../assets/scripts/game/battlePass/BattlePassPanel'
import type {
  BattlePassRewardView, BattlePassStatusResp, BattlePassTierView,
} from '../assets/scripts/net/generated/BattlePassProtocol'

const NOW = 1_700_000_000_000
const DAY = 86_400_000

function reward(name: string, count: number, id = 'item_res_wood_10k'): BattlePassRewardView {
  return { rewardType: 'ITEM', rewardId: id, name, count }
}

function tier(overrides: Partial<BattlePassTierView> = {}): BattlePassTierView {
  return {
    tier: 1, requiredPoints: 150, reached: false, freeClaimed: false, paidClaimed: false,
    freeReward: reward('木材箱(1万)', 1), paidReward: reward('金币', 50, 'GOLD'),
    ...overrides,
  }
}

/** 20 档的标准响应：第 n 档需要 150n 分。 */
function resp(overrides: Partial<BattlePassStatusResp> = {}): BattlePassStatusResp {
  // tiers 单独取出来：它是**整份替换**（不是逐档叠加），混在 rest 里会被后面的键悄悄盖掉
  const { tiers: replaced, ...rest } = overrides
  const points = rest.points ?? 0
  const base: BattlePassTierView[] = []
  for (let n = 1; n <= 20; n += 1) {
    // reached 由积分现推 —— 与生产同一形状（服务端就是这么下发的）：手写 reached 的夹具
    // 会造出一个「3000 分但一档都没达成」的假状态，测出来的窗口起点也就不是生产的样子
    base.push(tier({ tier: n, requiredPoints: n * 150, reached: points >= n * 150 }))
  }
  return {
    seasonId: 'season_01', points: 0, paidUnlocked: false,
    seasonEndAt: NOW + 12 * DAY, serverNow: NOW,
    ...rest, tiers: replaced ?? base,
  }
}

test('能不能领只信服务端：reached 与 claimed 都由响应给，客户端不重新比积分', () => {
  const reached = buildBattlePassRow(tier({ reached: true }), false)
  assert.equal(reached.freeClaimable, true)
  assert.equal(reached.reachedText, '已达成')

  const notReached = buildBattlePassRow(tier({ reached: false }), true)
  assert.equal(notReached.freeClaimable, false, '没达成时不许可点')
  assert.equal(notReached.reachedText, '还差 150 分')

  const claimed = buildBattlePassRow(tier({ reached: true, freeClaimed: true }), false)
  assert.equal(claimed.freeClaimable, false, '领过了不许再点')
  assert.equal(claimed.freeClaimed, true)
})

test('两条线各点各的：领了免费那份，付费那份仍然可点（前提是解锁了）', () => {
  const row = buildBattlePassRow(tier({ reached: true, freeClaimed: true }), true)
  assert.equal(row.freeClaimable, false)
  assert.equal(row.paidClaimable, true, '免费线领过不该把付费线也关掉')
  assert.equal(trackStateText(row, 'FREE'), '已领取')
  assert.equal(trackStateText(row, 'PAID'), '可领取')

  const body = claimBodyOf(row, 'PAID')
  assert.deepEqual(body, { tier: 1, track: 'PAID' })
  assert.equal(claimBodyOf(row, 'FREE'), null, '领过的线不发请求')
})

test('付费线没解锁时付费那颗不可点，而且页面把原因说出来', () => {
  const row = buildBattlePassRow(tier({ reached: true }), false)
  assert.equal(row.paidClaimable, false)
  assert.equal(claimBodyOf(row, 'PAID'), null)

  const view = buildBattlePassPanel(resp({ points: 150, tiers: [tier({ reached: true })] }), 6)
  assert.equal(view.paidUnlocked, false)
  assert.match(view.paidHint ?? '', /付费线还没解锁/)
  assert.equal(view.paidHint !== null, true)
})

test('解锁之后那一句提示就该消失（别把已经买了的人当成没买）', () => {
  const view = buildBattlePassPanel(resp({ paidUnlocked: true }), 6)
  assert.equal(view.paidHint, null)
})

test('窗口起点跟着"接下来该领哪一档"走：全领完就落到下一个未达成的档位', () => {
  const allReached = resp({ points: 3000, paidUnlocked: true })
  assert.equal(windowStartOf(allReached), 0, '一档都没领时从第 1 档开始')

  const tiers = allReached.tiers.map((t) => ({ ...t, freeClaimed: true, paidClaimed: true }))
  assert.equal(windowStartOf(resp({ points: 3000, paidUnlocked: true, tiers })), 0,
    '全领完（没有未达成的）回到第 1 档 —— 表头会说清范围，不静默留空')

  const partly = allReached.tiers.map((t, i) => i < 2
    ? { ...t, freeClaimed: true, paidClaimed: true } : t)
  assert.equal(windowStartOf(resp({ points: 3000, paidUnlocked: true, tiers: partly })), 2,
    '前两档领完 ⇒ 从第 3 档开始画')
})

test('被跳过的档位由表头交代范围：第 a–b 档 / 共 20 档', () => {
  const tiers = resp().tiers.map((t, i) => i < 4 ? { ...t, reached: true, freeClaimed: true } : t)
  const view = buildBattlePassPanel(resp({ points: 600, tiers }), 5)
  assert.equal(view.windowStart, 4)
  assert.equal(view.rows.length, 5)
  assert.equal(view.rows[0]?.tier, 5)
  assert.equal(view.rangeText, '第 5–9 档 / 共 20 档')
})

test('积分与已领份数都由响应算出来：未解锁时总数只算免费那一条线', () => {
  const view = buildBattlePassPanel(resp({ points: 300, paidUnlocked: false,
    tiers: resp().tiers.map((t, i) => i < 2 ? { ...t, reached: true, freeClaimed: true } : t) }), 6)
  assert.equal(view.pointsText, '本赛季积分 300 / 3000',
    '分母是整条梯子的总目标（20 档 = 3000 分），不是窗口里最后一档的分数 —— 后者会随窗口滑动而变')
  assert.equal(view.claimedText, '已领 2 / 20 份', '没买战令时只有 20 份可领，不能按 40 算')

  const paidView = buildBattlePassPanel(resp({ points: 300, paidUnlocked: true,
    tiers: resp().tiers.map((t, i) => i < 2 ? { ...t, reached: true, freeClaimed: true } : t) }), 6)
  assert.equal(paidView.claimedText, '已领 2 / 40 份', '买了之后两条线各 20 份')
})

test('剩余时间用服务端时刻相减（铁律 5）：天/小时/已结束/未启用四种说法', () => {
  assert.equal(remainTextOf({ ...resp(), serverNow: NOW, seasonEndAt: NOW + 12 * DAY }),
    '本赛季还剩 12 天')
  assert.equal(remainTextOf({ ...resp(), serverNow: NOW, seasonEndAt: NOW + 3 * 3_600_000 }),
    '本赛季还剩 3 小时')
  assert.equal(remainTextOf({ ...resp(), serverNow: NOW, seasonEndAt: NOW - 1 }),
    '本赛季已结束，未领的档位会随结算发进邮箱')
  assert.equal(remainTextOf({ ...resp(), serverNow: NOW, seasonEndAt: 0 }),
    '赛季尚未启用，战令进度从赛季开启当天开始')
})

test('列表还没拉回来时画一句说明，不画一个假进度', () => {
  const view = buildBattlePassPanel(null, 6)
  assert.deepEqual(view.rows, [])
  assert.equal(view.noticeText, '战令进度还没拉回来，稍后再试')
  assert.equal(view.pointsText, '')
})

test('领到之后那句话取服务端回执里的名字与份数', () => {
  assert.equal(claimResultText({ rewardType: 'ITEM', rewardId: 'x', name: '木材箱(1万)', count: 2 }),
    '已领取 木材箱(1万) ×2')
})
