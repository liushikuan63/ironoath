/**
 * 职责：BattlePlayback 单测 —— B05 §三（播放服务端快照、1x/2x/跳过、战报可重播）。
 * 依赖：node:test / node:assert。
 *
 * 这里刻意不测任何战斗数值：客户端不算战斗（B05 头号禁止项）。
 * 要测的是时间轴的三条性质 —— 顺序、时长换算、可重播，
 * 以及「跳过」不等于「把倍速调大」这个最容易做错的区分。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { BattlePlayback, parseSpeeds } from '../game/battle/BattlePlayback'
import type { PlaybackOptions } from '../game/battle/BattlePlayback'
import type { BattleResultView, RoundView, SkillTriggerView } from '../net/generated/BattleProtocol'

/** 与 global.json 一致的默认播放参数。 */
function options(overrides: Partial<PlaybackOptions> = {}): PlaybackOptions {
  return {
    roundMs: 600,        // global.BATTLE_ROUND_DISPLAY_MS
    openingMs: 500,
    settlementMs: 800,
    skillMs: 400,
    speeds: [1, 2],      // global.BATTLE_PLAYBACK_SPEEDS
    ...overrides,
  }
}

function skill(id: string, round: number): SkillTriggerView {
  void round
  return { phase: 'ROUND_START', skillId: id, skillName: id, side: 'ATTACKER', heroId: null, valueFixed: 3500 }
}

function round(n: number, skills: SkillTriggerView[] = []): RoundView {
  return {
    round: n,
    attackerUnits: [{ unitType: 'INFANTRY', count: 1000 - n * 50 }],
    defenderUnits: [{ unitType: 'CAVALRY', count: 1000 - n * 40 }],
    attackerLoss: 50,
    defenderLoss: 40,
    attackerAttack: 12000,
    defenderDefense: 9000,
    attritionFixed: 1250,
    skills,
  }
}

function result(rounds: RoundView[], overrides: Partial<BattleResultView> = {}): BattleResultView {
  return {
    winner: 'ATTACKER',
    battleType: 'PVP_SOLO',
    totalRounds: rounds.length,
    rounds,
    attackerSurvivors: [{ unitType: 'INFANTRY', count: 600 }],
    defenderSurvivors: [{ unitType: 'CAVALRY', count: 200 }],
    attackerDead: 80,
    attackerWounded: 320,
    attackerOverflowDead: 0,
    defenderDead: 300,
    defenderWounded: 500,
    defenderOverflowDead: 120,
    loot: [{ resourceType: 'WOOD', amount: 5000 }],
    lootCapacity: 20000,
    seed: 20260906,
    serverNow: 1700000000000,
    ...overrides,
  }
}

// ---------- 时间轴：顺序与结构 ----------

test('时间轴结构：开场 → 每回合（技能在前、回合在后）→ 结算', () => {
  const playback = new BattlePlayback(
    result([round(1, [skill('skill_a', 1)]), round(2), round(3, [skill('skill_b', 3), skill('skill_c', 3)])]),
    options())
  const steps = playback.buildTimeline()

  assert.equal(steps[0]?.kind, 'opening')
  assert.equal(steps[steps.length - 1]?.kind, 'settlement')
  // 3 回合 + 3 次技能 + 开场 + 结算
  assert.equal(steps.length, 3 + 3 + 2)
  // 技能必须排在它所属回合之前：技能是因、损失是果，先播因玩家才看得懂这一回合为什么掉这么多兵
  assert.deepEqual(steps.map((s) => `${s.kind}${s.round}`), [
    'opening0',
    'skill1', 'round1',
    'round2',
    'skill3', 'skill3', 'round3',
    'settlement0',
  ])
})

test('每回合的 snapshot 就是服务端下发的那一份，客户端不做任何加工', () => {
  const rounds = [round(1), round(2)]
  const playback = new BattlePlayback(result(rounds), options())
  const roundSteps = playback.buildTimeline().filter((s) => s.kind === 'round')
  assert.equal(roundSteps.length, 2)
  assert.equal(roundSteps[0]?.snapshot, rounds[0], '必须是同一个对象引用，不能是重算出来的副本')
  assert.equal(roundSteps[1]?.snapshot, rounds[1])
})

test('没有技能触发时不产生 skill 步骤', () => {
  const playback = new BattlePlayback(result([round(1), round(2)]), options())
  assert.equal(playback.buildTimeline().filter((s) => s.kind === 'skill').length, 0)
  assert.equal(playback.skillCount(), 0)
})

test('0 回合的战斗（双方未接触）也要有开场与结算，不能播出一个空时间轴', () => {
  const playback = new BattlePlayback(result([]), options())
  const steps = playback.buildTimeline()
  assert.deepEqual(steps.map((s) => s.kind), ['opening', 'settlement'])
})

// ---------- 时长换算 ----------

test('1x 下每回合 600ms，8 回合总时长约 5s（B05 §三 的明写要求）', () => {
  const rounds = Array.from({ length: 8 }, (_, i) => round(i + 1))
  const playback = new BattlePlayback(result(rounds), options())
  const steps = playback.buildTimeline()
  for (const step of steps.filter((s) => s.kind === 'round')) {
    assert.equal(step.durationMs, 600)
  }
  // 500 开场 + 8×600 回合 + 800 结算 = 6100ms，B05 说的「≈5s」是不含开场结算的 4.8s
  assert.equal(playback.totalDurationMs(), 500 + 8 * 600 + 800)
  assert.equal(steps.filter((s) => s.kind === 'round')
    .reduce((acc, s) => acc + s.durationMs, 0), 4800)
})

test('2x 把所有步骤时长减半，但步骤数量与顺序完全不变', () => {
  const rounds = [round(1, [skill('skill_a', 1)]), round(2)]
  const battle = result(rounds)
  const at1x = new BattlePlayback(battle, options())
  const at2x = new BattlePlayback(battle, options())
  at2x.setSpeed(2)

  const a = at1x.buildTimeline()
  const b = at2x.buildTimeline()
  assert.deepEqual(a.map((s) => s.kind), b.map((s) => s.kind), '倍速只改时长，不改结构')
  assert.deepEqual(a.map((s) => s.round), b.map((s) => s.round))
  for (let i = 0; i < a.length; i++) {
    assert.equal(b[i]?.durationMs, Math.round((a[i]?.durationMs ?? 0) / 2))
  }
  assert.equal(at2x.totalDurationMs(), Math.round(at1x.totalDurationMs() / 2))
})

test('倍速只能取配置允许的档位；请求非法档位时保持原值而不是抛错', () => {
  const playback = new BattlePlayback(result([round(1)]), options())
  assert.equal(playback.currentSpeed, 1)
  assert.equal(playback.setSpeed(2), 2)
  assert.equal(playback.currentSpeed, 2)
  // 3 不在 global.BATTLE_PLAYBACK_SPEEDS 里：保持 2x，不给玩家弹报错
  assert.equal(playback.setSpeed(3 as never), 2)
  assert.equal(playback.currentSpeed, 2)
})

test('播放中途切倍速会影响之后的时间轴（时间轴按需生成，不是构造时定死的）', () => {
  const playback = new BattlePlayback(result([round(1), round(2)]), options())
  const before = playback.totalDurationMs()
  playback.setSpeed(2)
  assert.equal(playback.totalDurationMs(), Math.round(before / 2))
})

// ---------- 跳过 ----------

test('跳过 ≠ 把倍速调大：跳过后只剩结算一步，且时长为 0', () => {
  const playback = new BattlePlayback(result([round(1), round(2), round(3)]), options())
  assert.equal(playback.isSkipped, false)
  playback.skip()
  assert.equal(playback.isSkipped, true)

  const steps = playback.buildTimeline()
  assert.equal(steps.length, 1, '跳过就是把倍速调大会让所有回合动效同时启动，B05 要求顺序播放')
  assert.equal(steps[0]?.kind, 'settlement')
  assert.equal(steps[0]?.durationMs, 0)
  assert.equal(playback.totalDurationMs(), 0)
})

test('跳过之后仍然能读到完整战报数据（跳过的是播放，不是数据）', () => {
  const playback = new BattlePlayback(
    result([round(1)], { defenderOverflowDead: 300, attackerOverflowDead: 50 }), options())
  playback.skip()
  assert.equal(playback.skillCount(), 0)
  // 战报 UI 的分回合折叠列表在跳过后仍然要能展开
  assert.deepEqual(playback.overflowDead(), { attacker: 50, defender: 300, total: 350 })
})

// ---------- 可重播（验收 9 的客户端一侧） ----------

test('同一份战报播两次，步骤序列与时长逐条相同（杀进程重进后重播必须一致）', () => {
  const battle = result([round(1, [skill('skill_a', 1)]), round(2), round(3)])
  const first = new BattlePlayback(battle, options()).buildTimeline()
  const second = new BattlePlayback(battle, options()).buildTimeline()
  assert.deepEqual(first, second)
})

test('时间轴不依赖真实时钟：连续生成两次之间即使过了时间，结果也完全一致', async () => {
  const playback = new BattlePlayback(result([round(1), round(2)]), options())
  const a = playback.buildTimeline()
  await new Promise((resolve) => setTimeout(resolve, 20))
  const b = playback.buildTimeline()
  assert.deepEqual(a, b, '读了时钟的话这里就会不同，重播也就不可能与首播一致')
})

// ---------- 战报完整性与参数校验 ----------

test('快照数量与 totalRounds 不符时拒绝播放：战报被截断会让玩家看到错误的胜负过程', () => {
  const truncated = result([round(1), round(2)], { totalRounds: 8 })
  assert.throws(() => new BattlePlayback(truncated, options()), /战报不完整/)
})

test('非法参数在构造期就拒绝', () => {
  const battle = result([round(1)])
  assert.throws(() => new BattlePlayback(battle, options({ roundMs: 0 })), /roundMs/)
  assert.throws(() => new BattlePlayback(battle, options({ openingMs: -1 })), /不得为负/)
  assert.throws(() => new BattlePlayback(battle, options({ speeds: [] })), /speeds/)
  assert.throws(() => new BattlePlayback(undefined as never, options()), /result 不得为空/)
})

test('parseSpeeds 解析配置字符串，遇到不认识的档位直接拒绝而不是静默降级', () => {
  assert.deepEqual(parseSpeeds('1,2'), [1, 2])
  assert.deepEqual(parseSpeeds(' 2 , 1 '), [2, 1])
  assert.deepEqual(parseSpeeds('1,1,2'), [1, 2], '重复档位去重')
  assert.throws(() => parseSpeeds('1,4'), /不支持的倍速/,
    '配置里写了 4 说明有人想加 4x，静默改成 2x 会让那次改动看起来生效了却没生效')
  assert.throws(() => parseSpeeds(''), /解析后为空/)
})
