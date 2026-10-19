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

// ---------- 侦察指标的资源名走唯一那份映射（#2208） ----------

test('侦察指标里的资源名与资源条上的一致：铁矿就是铁矿，不是「铁」', () => {
  // 侦察详情**只列前三项**（既有口径，见上面那条「只列前三项观测值」断言）⇒ 分两批看。
  const first = buildScoutIntel(scoutList(scoutReport({
    metrics: [
      { name: 'iron', value: 1_200 },
      { name: 'wood', value: 3_000 },
      { name: 'stone', value: 400 },
    ],
  })), 1_000)
  const detail = first.rows[0]?.detail ?? ''
  // 旧实现这里另抄了一份资源名，且把铁矿抄成了「铁」——
  // 玩家在战报里看到「铁」、在资源条上看到「铁矿」，说的是同一样东西。
  assert.match(detail, /铁矿 1200/, '铁矿必须与资源条上同一个名字（ResourceNames 是唯一那份）')
  assert.equal(detail.includes(' 铁 '), false, '不能出现「铁」这种简写：那是第二份家，且与资源条不一致')
  assert.match(detail, /木材 3000/)
  assert.match(detail, /石料 400/)

  const second = buildScoutIntel(scoutList(scoutReport({
    metrics: [
      { name: 'grain', value: 500 },
      { name: 'gold', value: 60 },
    ],
  })), 1_000)
  const detail2 = second.rows[0]?.detail ?? ''
  assert.match(detail2, /粮草 500/)
  assert.match(detail2, /金币 60/)
})

test('资源指标名走映射而不是靠「键名相同」：协议键是小写，资源枚举是大写', () => {
  // `ScoutMetric.name` 的合法取值是小写指标键（wood/iron/…），
  // `RESOURCE_NAMES` 的键是大写资源枚举（WOOD/IRON/…）—— **两个命名空间**。
  // 想当然地 `resourceName(metric.name)` 会全部落空、屏幕退回英文原名。
  // 这条用例钉住：映射存在，且映射方向正确。
  const view = buildScoutIntel(scoutList(scoutReport({
    metrics: [{ name: 'grain', value: 4_200 }],
  })), 1_000)
  assert.match(view.rows[0]?.detail ?? '', /粮草 4200/,
    '小写协议键必须映射到大写资源枚举，再取到唯一那份中文名')
  assert.equal((view.rows[0]?.detail ?? '').includes('grain'), false,
    '不该把协议原文露给玩家')
})

test('不属于资源的指标仍走显示词表，且表里没有的名字原样透出（宁可生僻也不猜）', () => {
  // 同样只列前三项 ⇒ 不认识的那条放第三个。
  const view = buildScoutIntel(scoutList(scoutReport({
    metrics: [
      { name: 'power', value: 12_400 },
      { name: 'totalUnits', value: 9_000 },
      { name: 'zz_unknown_metric', value: 7 },
    ],
  })), 1_000)
  const detail = view.rows[0]?.detail ?? ''
  assert.match(detail, /战力 1\.2 万/)
  assert.match(detail, /总兵力 9000/)
  assert.match(detail, /zz_unknown_metric 7/, '不认识的指标名原样透出，不猜一个错的中文')
})
