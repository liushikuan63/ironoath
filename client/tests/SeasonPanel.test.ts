/**
 * 职责：赛季页的纯逻辑用例（V04-S1）。
 * 依赖：node:test + game/season/SeasonPanel（不碰 cc）。
 *
 * <p><b>四处最容易做假的地方</b>：① 未启用赛季时**整块收起**（服务端把相位与日期给成 null，
 * 那是权威答案，不是"第 0 天"）；② 倒计时**只用服务端两个时刻相减**（铁律 5）；
 * ③ 三条闸门的话**照抄服务端的布尔**，不许从图标颜色反推；④ 保留项那一句**照抄 B14**，
 * 不自己发明"全部清零"或"全部保留"。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildSeasonPanel, phaseLabel, remainTextOf, SEASON_KEEP_NOTE, tierLabel,
} from '../assets/scripts/game/season/SeasonPanel'
import type { SeasonStatusResp } from '../assets/scripts/net/generated/SeasonProtocol'

const NOW = 1_700_000_000_000

function resp(overrides: Partial<SeasonStatusResp> = {}): SeasonStatusResp {
  return {
    seasonId: 'season_01', phase: 'EXPAND', seasonStartAt: NOW - 12 * 86_400_000,
    dayIndex: 11, totalDays: 45, phaseEndAt: NOW + 5 * 86_400_000,
    allowsPvp: true, allowsCapitalWar: false, readOnly: false,
    glory: { gloryLevel: 3, highestTier: 'GOLD', badges: ['season_01', 'season_02', 'season_03'] },
    myRank: 12, serverNow: NOW,
    ...overrides,
  }
}

test('未启用赛季时整块收起：不显示"第 0 天"（相位为 null 是服务端的权威答案）', () => {
  const view = buildSeasonPanel(resp({ phase: null, seasonStartAt: null, dayIndex: null, phaseEndAt: null }))
  assert.equal(view.visible, false)
  assert.equal(view.titleText, '')
  assert.deepEqual(view.gates, [])
  assert.match(view.noticeText ?? '', /尚未启用赛季/)
})

test('列表还没拉回来时画一句说明，不画一个假赛季', () => {
  const view = buildSeasonPanel(null)
  assert.equal(view.visible, false)
  assert.match(view.noticeText ?? '', /还没拉回来/)
})

test('天数按 1 起算：dayIndex 是 0-based（第 12 天 = dayIndex 11）', () => {
  const view = buildSeasonPanel(resp())
  assert.equal(view.visible, true)
  assert.equal(view.titleText, '赛季 · 第 12 / 45 天')
})

/**
 * 这一条**以前是反的**：它原来断言 `titleText === 'season_01 赛季 · …'`，
 * 也就是把"把 season 表行 id 印给玩家"钉成了规格（与 #268 那两条 `'WOOD 12000/12000'`、
 * #255 的 `'building_wood Lv6'` 同一种绿灯替缺陷作证）。
 * 赛季中文名在表里而响应没带 ⇒ 客户端不许自己查表拼（只有类型没有数据），
 * 在 `SeasonStatusResp` 补 name 之前，标题里就不该出现任何行 id。
 */
test('标题不印 season 表行 id（客户端也不许自己查表拼名字）', () => {
  const view = buildSeasonPanel(resp({ seasonId: 'season_01_phase_3' }))
  assert.ok(!/season_\d/.test(view.titleText), `标题里漏出行 id：「${view.titleText}」`)
  assert.ok(!view.titleText.includes('phase_'), `标题里漏出阶段行 id：「${view.titleText}」`)
})

test('阶段名是玩家语言，五个取值都有（含休赛期）', () => {
  assert.deepEqual(
    (['PREPARE', 'EXPAND', 'CAPITAL_WAR', 'SETTLE', 'REST'] as const).map((p) => phaseLabel(p)),
    ['开垦期', '立盟期', '问鼎期', '结算期', '休赛期'],
  )
  assert.equal(phaseLabel(null), '')
})

test('段位是 B14 的原词，不是枚举名：六个档次都对，未知取值退回原文而不是空白', () => {
  assert.deepEqual(
    (['BRONZE', 'SILVER', 'GOLD', 'PLATINUM', 'DIAMOND', 'KING'] as const).map((t) => tierLabel(t)),
    ['青铜', '白银', '黄金', '铂金', '钻石', '王者'],
  )
  assert.equal(tierLabel(null), '')
  assert.equal(tierLabel('MYTHIC' as never), 'MYTHIC', '新档位上线时显示陌生的词，而不是空行')
})

test('倒计时用服务端两个时刻相减（铁律 5）：天/小时/即将切换三档，不给负数', () => {
  assert.equal(remainTextOf(NOW + 5 * 86_400_000, NOW), '还剩 5 天')
  assert.equal(remainTextOf(NOW + 3 * 3_600_000, NOW), '还剩 3 小时')
  assert.equal(remainTextOf(NOW - 1, NOW), '即将切换阶段')
  assert.equal(remainTextOf(null, NOW), '')
  assert.equal(buildSeasonPanel(resp()).phaseText, '立盟期 · 还剩 5 天')
})

test('三条闸门照抄服务端的布尔：允许/禁止各有各的话，不写成"维护中"', () => {
  const open = buildSeasonPanel(resp({ allowsPvp: true, allowsCapitalWar: true, readOnly: false }))
  assert.deepEqual(open.gates.map((g) => g.allowed), [true, true, true])
  assert.equal(open.gates[0]?.text, '可以攻击其他玩家')

  const closed = buildSeasonPanel(resp({ allowsPvp: false, allowsCapitalWar: false, readOnly: true }))
  assert.equal(closed.gates[0]?.text, '当前阶段禁止玩家间攻击')
  assert.equal(closed.gates[1]?.text, '中央王城尚未开放（问鼎期才开）')
  assert.match(closed.gates[2]?.text ?? '', /只展示荣耀/)
})

test('名次与荣耀：没上榜（0）或没带身份（null）都不显示名次，并说明为什么', () => {
  const ranked = buildSeasonPanel(resp({ myRank: 12 }))
  assert.equal(ranked.rankText, '我的名次：第 12 名')
  assert.equal(ranked.noticeText, null)
  assert.equal(ranked.gloryText, '荣耀 3 级 · 最高段位 黄金 · 徽章 3 枚')

  const unranked = buildSeasonPanel(resp({ myRank: 0 }))
  assert.equal(unranked.rankText, null, '0 名不是"第 0 名"，是没上榜')

  const anonymous = buildSeasonPanel(resp({ myRank: null, glory: null }))
  assert.equal(anonymous.rankText, null)
  assert.equal(anonymous.gloryText, null)
  assert.match(anonymous.noticeText ?? '', /带上身份/)
})

test('保留项那一句照抄 B14：说清保留什么、降段、归档几季', () => {
  assert.match(SEASON_KEEP_NOTE, /保留：荣耀等级、历史最高段位、赛季徽章/)
  assert.match(SEASON_KEEP_NOTE, /降 1~2 段/)
  assert.match(SEASON_KEEP_NOTE, /归档保留 3 个赛季/)
  assert.equal(buildSeasonPanel(resp()).keepText, SEASON_KEEP_NOTE)
})
