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
import { buildReportList, playbackOptionsOf } from '../assets/scripts/game/battle/BattleReportPanel'
import type { BattleReportBrief, BattleReportListResp } from '../assets/scripts/net/generated/BattleProtocol'

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
