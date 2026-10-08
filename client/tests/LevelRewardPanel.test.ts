/**
 * 职责：等级奖励面板纯逻辑层的用例（收口清单 #829 裁决③）。
 * 依赖：node:test + game/levelReward/LevelRewardPanel（不碰 cc）。
 *
 * <p><b>这些用例盯的是五处最容易做假的地方</b>：
 * ① 三态**只信服务端那三个结论位**（客户端不拿 mainLevel 与 level 自己重算能不能领 ——
 * 前置口径哪天变了，本地那份就会与服务器分叉，症状是"亮着的键点下去被拒"）；
 * ② 奖励串里**不许出现内部码**（`WOOD` / `GOLD` 印到屏上是本仓反复出过的事故）；
 * ③ 名称缺失的那一项**整项不画**，而不是回退成 id，也不留下悬空分隔符（#349 那一族）；
 * ④ 窗口起点跟着"接下来该领哪一级"走，被跳过的等级由表头交代（不静默少画）；
 * ⑤ 不可领的那一行**不产出请求体**（一次注定被拒的写入还会吃掉一个幂等键）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildLevelRewardPanel, claimBodyOf, claimResultText, windowStartOf,
} from '../assets/scripts/game/levelReward/LevelRewardPanel'
import type {
  LevelRewardClaimResp, LevelRewardItem, LevelRewardListResp, LevelRewardRow,
} from '../assets/scripts/net/generated/LevelRewardProtocol'

const NOW = 1_700_000_000_000

function item(id: string, name: string, count: number): LevelRewardItem {
  return { type: 'RESOURCE', id, count, name }
}

function row(level: number, mainLevel: number, opts: {
  claimed?: boolean
  rewards?: LevelRewardItem[]
} = {}): LevelRewardRow {
  const claimed = opts.claimed ?? false
  const locked = !claimed && level > mainLevel
  return {
    level,
    name: `主城 ${level} 级奖励`,
    rewards: opts.rewards ?? [item('WOOD', '木材', 50 * level), item('GOLD', '金币', 200)],
    locked,
    claimed,
    claimable: !locked && !claimed,
  }
}

function resp(rows: LevelRewardRow[], mainLevel: number, claimableCount: number): LevelRewardListResp {
  return { rows, claimableCount, mainLevel, serverNow: NOW }
}

test('三态各说各的话，且判定完全照服务端那三位', () => {
  const view = buildLevelRewardPanel(resp([
    row(1, 3, { claimed: true }),
    row(2, 3),
    row(5, 3),
  ], 3, 1), 10)
  assert.equal(view.rows[0]!.stateText, '已领取')
  assert.equal(view.rows[1]!.stateText, '待领取')
  // 未达那一态必须报出还差几级：只说"不可领"玩家不知道该干什么
  assert.equal(view.rows[2]!.stateText, '主城 3 级，还差 2 级')
  assert.deepEqual(view.rows.map(r => r.claimable), [false, true, false])
})

test('奖励串只用服务端给的中文名，内部码一次都不出现', () => {
  const view = buildLevelRewardPanel(resp([row(1, 1)], 1, 1), 10)
  const text = view.rows[0]!.rewardText
  assert.match(text, /木材×50/)
  assert.match(text, /金币×200/)
  for (const banned of ['WOOD', 'STONE', 'GOLD', 'RESOURCE']) {
    assert.doesNotMatch(text, new RegExp(banned), `屏上不许出现裸 id：${banned}`)
  }
})

test('六位数带千分位分隔符（31 级起奖励就上万）', () => {
  const view = buildLevelRewardPanel(resp(
    [row(1, 1, { rewards: [item('WOOD', '木材', 97440)] })], 1, 1), 10)
  assert.equal(view.rows[0]!.rewardText, '木材×97,440')
})

test('名称缺失的那一项整项不画，且不留下悬空分隔符', () => {
  const view = buildLevelRewardPanel(resp([row(1, 1, {
    rewards: [item('WOOD', '木材', 50), item('GOLD', '', 200), item('STONE', '石料', 60)],
  })], 1, 1), 10)
  // 中间那一项被丢掉之后，剩下的两项之间只有一个分隔符
  assert.equal(view.rows[0]!.rewardText, '木材×50 · 石料×60')
})

test('整行一个名都没有时是空串，不是「 · 」', () => {
  const view = buildLevelRewardPanel(resp([row(1, 1, {
    rewards: [item('WOOD', '', 50)],
  })], 1, 1), 10)
  assert.equal(view.rows[0]!.rewardText, '')
})

test('窗口起落在第一个待领等级往前回看两级，让「刚领的」与「要领的」同屏', () => {
  const main = 12
  const rows = Array.from({ length: 40 }, (_, i) => row(i + 1, main, { claimed: i < 4 }))
  // 锚点仍是第一个待领（index 4 = 第 5 级）
  assert.equal(windowStartOf(resp(rows, main, 8)), 4)
  const view = buildLevelRewardPanel(resp(rows, main, 8), 6)
  // 起屏回看到第 3 级：上面两行是已领、下面四行是待领 —— 两态在同一屏可辨（量具 C 相的前提）
  assert.equal(view.windowStart, 2)
  assert.equal(view.rows[0]!.level, 3)
  assert.deepEqual(view.rows.map(r => r.stateText), [
    '已领取', '已领取', '待领取', '待领取', '待领取', '待领取',
  ])
  assert.equal(view.headerText, '等级奖励 · 第 3–8 级 / 共 40 级')
})

test('全领完时窗口退到第一个还没达成的等级（那是玩家接下来的目标）', () => {
  const main = 3
  const rows = Array.from({ length: 40 }, (_, i) => row(i + 1, main, { claimed: i < 3 }))
  assert.equal(windowStartOf(resp(rows, main, 0)), 3)
})

test('窗口起点不会把最后一屏切成空屏', () => {
  const main = 40
  const rows = Array.from({ length: 40 }, (_, i) => row(i + 1, main, { claimed: i < 38 }))
  const view = buildLevelRewardPanel(resp(rows, main, 2), 6)
  assert.equal(view.rows.length, 6)
  assert.equal(view.rows[5]!.level, 40)
})

test('表是空的时表头不带范围段、也给得出那句话', () => {
  const view = buildLevelRewardPanel(resp([], 1, 0), 6)
  assert.equal(view.headerText, '等级奖励')
  assert.equal(view.noticeText, '暂时没有任何等级奖励')
  assert.equal(view.rows.length, 0)
  assert.equal(view.totalRows, 0)
})

test('汇总行念的是服务端的两个数，客户端一个都不自己数', () => {
  const rows = [row(1, 5), row(2, 5), row(6, 5)]
  const view = buildLevelRewardPanel(resp(rows, 5, 2), 10)
  assert.equal(view.summaryText, '主城 5 级 · 可领 2 级')
})

test('不可领的那一行不产出请求体', () => {
  const main = 3
  const view = buildLevelRewardPanel(resp([
    row(1, main, { claimed: true }), row(2, main), row(9, main),
  ], main, 1), 10)
  assert.deepEqual(claimBodyOf(view.rows[1]!), { level: 2 })
  assert.equal(claimBodyOf(view.rows[0]!), null)
  assert.equal(claimBodyOf(view.rows[2]!), null)
})

test('领取回执的飘字念实发的那份；明细为空时说的是邮件而不是「获得 ：」', () => {
  const ok: LevelRewardClaimResp = {
    level: 31, claimableCount: 0, serverNow: NOW,
    rewards: [item('WOOD', '木材', 19488), item('GOLD', '金币', 800)],
  }
  assert.equal(claimResultText(ok), '已领取 木材×19,488 · 金币×800')
  const truncated: LevelRewardClaimResp = {
    level: 31, claimableCount: 0, serverNow: NOW, rewards: [],
  }
  assert.equal(claimResultText(truncated), '主城 31 级已领取，装不下的部分请查看邮件')
})
