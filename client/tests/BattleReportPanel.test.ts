/**
 * 职责：战报面板组装的用例（B12 §3 / 台账第 21 条）。
 * 依赖：node:test + game/battle/BattleReportPanel（纯逻辑）。
 *
 * <p>盯两件事：① 胜负与战损都是服务端算好的，客户端只做排版；
 * ② 回放参数从下发的那两个数装配出来，表里没有的三段按「同量级」推导 ——
 * 这两条分别是「客户端不判定」与「一个数一个家」在表现层的落点。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { buildReportList, buildScoutIntel, playbackOptionsOf } from '../assets/scripts/game/battle/BattleReportPanel'
import type { BattleReportBrief, BattleReportListResp } from '../assets/scripts/net/generated/BattleProtocol'
import type { ScoutListResp } from '../assets/scripts/net/generated/WorldProtocol'

const NOW = 1_800_000_000_000
const HOUR = 3_600_000
const DAY = 24 * HOUR

function brief(overrides: Partial<BattleReportBrief> & { reportId: string }): BattleReportBrief {
  return {
    battleType: 'PVE', opponentId: 'mob_1', opponentName: '叛军斥候', winner: 'ATTACKER',
    won: true, totalRounds: 6, attackerLoss: 120, defenderLoss: 400,
    createdAt: NOW - HOUR, expiresAt: NOW + 6 * DAY, ...overrides,
  }
}

function list(reports: readonly BattleReportBrief[]): BattleReportListResp {
  return { reports: reports as BattleReportBrief[], serverNow: NOW }
}

test('列表：胜/败用服务端算好的布尔，标题是「类型 + 对手」', () => {
  const view = buildReportList(list([
    brief({ reportId: 'r-1' }),
    brief({ reportId: 'r-2', won: false, battleType: 'PVP_RALLY', opponentName: '铁盟' }),
  ]), NOW)

  assert.deepEqual(view.rows.map(r => [r.title, r.outcomeText, r.won]),
    [['打野 叛军斥候', '胜', true], ['集结 铁盟', '败', false]])
  assert.equal(view.rows[1]?.lossText, '我方 120 · 对方 400')
  assert.equal(view.headerText, '2 场 · 胜 1 · 败 1')
  assert.equal(view.emptyText, '', '有内容时不该再有那句空邮箱似的话')
})

test('空列表有一句话，而且说清"打一仗就会出现在这里"（与"没连上"分得开）', () => {
  const view = buildReportList(list([]), NOW)

  assert.deepEqual(view.rows, [])
  assert.equal(view.headerText, '')
  assert.match(view.emptyText, /还没有战报/)
})

test('过期时间是相对服务端时刻算的：按小时/按天两档，过期了也不写负数', () => {
  const rows = buildReportList(list([
    brief({ reportId: 'r-a', expiresAt: NOW + 30 * HOUR }),
    brief({ reportId: 'r-b', expiresAt: NOW + 40 * HOUR }),
    brief({ reportId: 'r-c', expiresAt: NOW - HOUR }),
  ]), NOW).rows

  assert.deepEqual(rows.map(r => r.expiresIn), ['1 天后', '1 天后', '已到期'])
})

test('回放参数：表里那两个数原样用，剩下的按「与回合时长同量级」推导，不新造常量', () => {
  const options = playbackOptionsOf({ roundMs: 900, speeds: '1,2' })

  assert.equal(options.roundMs, 900, '一回合演多久来自 global.BATTLE_ROUND_DISPLAY_MS')
  assert.deepEqual(options.speeds, [1, 2], '倍速档位来自 global.BATTLE_PLAYBACK_SPEEDS')
  assert.equal(options.openingMs, 900)
  assert.equal(options.settlementMs, 900)
  assert.equal(options.skillMs, 450)
})

test('参数不对就响，而不是让玩家点开一场看到一屏不动的画', () => {
  assert.throws(() => playbackOptionsOf({ roundMs: 0, speeds: '1,2' }), /BATTLE_ROUND_DISPLAY_MS/)
  assert.throws(() => playbackOptionsOf({ roundMs: 900, speeds: '1,4' }), /不支持的倍速/)
  assert.throws(() => playbackOptionsOf({ roundMs: 900, speeds: '' }), /解析后为空/)
})

// ---------- 敌情页签（B26 S19：`GET /world/reports` 的读者）----------

function scoutReport(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    reportId: 'sr-1', target: { x: 60, y: 66 }, targetId: 'P9', targetLevel: 7,
    createdAt: 1_000, expiresAt: 1_000 + 3 * HOUR, expired: false, remainingMs: 3 * HOUR,
    errorFixed: 1200, seed: null, serverNow: 1_000,
    metrics: [{ name: 'power', value: 12_400 }, { name: 'infantry', value: 8000 },
      { name: 'grain', value: 4200 }, { name: 'gold', value: 900 }],
    ...overrides,
  }
}

function scoutList(...reports: Record<string, unknown>[]): ScoutListResp {
  return { reports, serverNow: 1_000 } as unknown as ScoutListResp
}

test('敌情行：误差与数字贴在一起、指标名说人话、一万以上折成万', () => {
  const view = buildScoutIntel(scoutList(scoutReport()), 1_000)
  const row = view.rows[0]
  assert.equal(row?.title, '侦察：60, 66 · 7 级', '等级是唯一无误差的字段，写在标题里')
  assert.match(row?.detail ?? '', /战力 1\.2 万/, '一万以上折成万：一长串数字读不出量级')
  assert.match(row?.detail ?? '', /步兵 8000/, '不到一万的原样写，不硬造小数')
  assert.match(row?.detail ?? '', /±12%/, '误差幅度必须和数字同屏（B07 验收 9）')
  assert.equal((row?.detail ?? '').includes('金币'), false, '只列前三项观测值，四行塞不下一行')
  assert.match(view.headerText, /1 份（1 份还有效）/)
})

test('过期的那份不藏起来，但明确说它不能再拿来定打法', () => {
  const view = buildScoutIntel(scoutList(
    scoutReport({ reportId: 'sr-old', expired: true, expiresAt: 500, remainingMs: 0 }),
    scoutReport({ reportId: 'sr-new' }),
  ), 1_000)
  assert.equal(view.rows.length, 2, '服务端连 expired=true 一起回，就是为了让玩家看见"我侦察过、但那份不作数了"')
  assert.equal(view.rows[0]?.expired, true)
  assert.match(view.rows[0]?.detail ?? '', /已过期，不能再拿来定打法/)
  assert.equal(view.rows[0]?.outcome, '过期')
  assert.equal(view.rows[1]?.outcome, '有效')
  assert.match(view.headerText, /2 份（1 份还有效）/)
})

test('没有敌情时那一格说的是怎么弄出一份，而不是"暂无数据"', () => {
  const empty = buildScoutIntel(scoutList(), 1_000)
  assert.equal(empty.rows.length, 0)
  assert.match(empty.emptyText, /在出征编成里把命令切成「侦察」/)
  const notYet = buildScoutIntel(null, 1_000)
  assert.equal(notYet.emptyText, '敌情读取中', '没连上和"连上了但空"要分得开')
})
