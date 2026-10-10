/**
 * 职责：StagePanel 的单测 —— B09 §三（挑战/结算）、§6（扫荡）、验收 1（失败不扣体力）、
 * 验收 9（10 次扫荡 1 次请求）。
 * 依赖：node:test / node:assert。
 *
 * <p>重点盯两类错误：
 * <ol>
 *   <li><b>从星数总数反推达成情况</b>。三星是三个独立条件之和，2 星可能是「通关+限时」
 *       而不是「通关+无损」。反推会告诉玩家一个假消息，比不显示更糟</li>
 *   <li><b>静默失败</b>。未解锁不给原因、扫荡少扫了不说明、失败扣没扣体力不说清 ——
 *       这三种情况玩家都会理解成 bug 或者被偷扣</li>
 * </ol>
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildChallengeSummary, buildStageList, buildStageRow, buildSweepSummary,
  mechanicText, restrictionText, starConditionText, starText,
} from '../assets/scripts/game/stage/StagePanel'
import type {
  BossMechanic, ChallengeStageResp, StageEntry, StageListResp, StageReward, SweepResp, UnitRestriction,
} from '../assets/scripts/net/generated/StageProtocol'

function progress(stars: number, bestRounds: number, clearedAt: number): StageEntry['progress'] {
  return { stageId: 'stage_01_01', stars, bestRounds, clearedAt, sweepCount: 0 }
}

function entry(overrides: Partial<StageEntry> = {}): StageEntry {
  return {
    stageId: 'stage_01_01',
    chapterId: 'chapter_01',
    stageNo: 1,
    name: '边境哨站',
    staminaCost: 6,
    roundLimit: 8,
    unitRestriction: 'NONE',
    bossMechanic: 'NONE',
    unlocked: true,
    lockedReason: null,
    attempted: false,
    progress: progress(0, 0, 0),
    ...overrides,
  }
}

function listResp(stages: StageEntry[], stamina = 100): StageListResp {
  return { stages, stamina, serverNow: 0 }
}

function reward(id: string, name: string, count: number): StageReward {
  return { type: 'RESOURCE', id, count, name }
}

function challengeResp(overrides: Partial<ChallengeStageResp> = {}): ChallengeStageResp {
  return {
    reportId: 'r1',
    stars: { cleared: true, noLoss: false, withinRounds: true, total: 2 },
    starsEarned: 2,
    newBest: true,
    rewards: [reward('WOOD', '木材', 100)],
    losses: [{ unitId: 'unit_infantry_t1', name: '重步兵', count: 3 }],
    staminaCost: 6,
    staminaCharged: 6,
    progress: progress(2, 5, 1700000000000),
    serverNow: 1700000000000,
    ...overrides,
  }
}

function sweepResp(executed: number, overrides: Partial<SweepResp> = {}): SweepResp {
  return {
    results: Array.from({ length: executed }, (_, index) => ({
      reportId: `sr${index}`,
      stars: { cleared: true, noLoss: false, withinRounds: true, total: 2 },
      rewards: [reward('GOLD', '金币', 10)],
    })),
    totalRewards: [reward('GOLD', '金币', 10 * executed)],
    staminaCost: 6 * executed,
    staminaCharged: 6 * executed,
    executed,
    progress: progress(2, 5, 1700000000000),
    serverNow: 1700000000000,
    ...overrides,
  }
}

// ---------- 三态可区分 ----------

test('关卡标题使用服务端序号与名称，不向玩家显示内部章节或关卡 id', () => {
  const row = buildStageRow(entry({
    chapterId: 'chapter_09_internal',
    stageId: 'stage_09_07_internal',
    stageNo: 7,
    name: '山口防线',
  }))
  assert.equal(row.title, '第 7 关 山口防线')
  assert.doesNotMatch(row.title, /chapter_|stage_/)
  assert.equal(row.stageId, 'stage_09_07_internal', '操作标识仍使用服务端关卡 id')
})

test('「未挑战」「打过但 0 星」「N 星」三态必须能区分', () => {
  assert.equal(starText(entry({ attempted: false, progress: progress(0, 0, 0) })), '未挑战')
  assert.equal(starText(entry({ attempted: true, progress: progress(0, 0, 0) })), '0 星')
  assert.equal(starText(entry({ attempted: true, progress: progress(3, 4, 1700000000000) })), '3 星')

  // 协议专门拆了 attempted 与 progress 两个字段就是为了这个区别：
  // 把「没打过」显示成 0 星，玩家会以为自己打砸了，从而不敢再点
  const never = buildStageRow(entry({ attempted: false }))
  const failed = buildStageRow(entry({ attempted: true, progress: progress(0, 0, 0) }))
  assert.notEqual(never.starText, failed.starText)
  assert.notEqual(never.conditionText, failed.conditionText)
})

test('星数一律取服务端下发的总数，不从三个布尔值反推、也不从总数反推达成情况', () => {
  // 2 星可能是「通关 + 限时」而没有无损。列表页拿不到三个布尔值（StageProgressView 只有 stars），
  // 所以它绝不能声称知道差的是哪一条
  const row = buildStageRow(entry({
    attempted: true,
    progress: progress(2, 5, 1700000000000),
    roundLimit: 8,
  }))
  assert.match(row.conditionText, /已得 2\/3 星/)
  assert.match(row.conditionText, /历史最少 5 回合/)
  assert.match(row.conditionText, /上限 8 回合/)
  assert.doesNotMatch(row.conditionText, /无损/, '列表页不得声称知道无损这一条达成没有')
  assert.doesNotMatch(row.conditionText, /[✓✗]/, '打勾打叉只在有布尔值可用的结算页出现')
})

test('结算页有三个布尔值，逐条打勾；三条互相独立，不从总数推', () => {
  // 通关 + 限时，但没有无损 ⇒ total=2。若按 total>=2 反推「无损达成」就会给出假消息
  assert.equal(starConditionText(true, false, true), '✓通关 ✗无损 ✓限时')
  assert.equal(starConditionText(true, true, false), '✓通关 ✓无损 ✗限时')
  assert.equal(starConditionText(false, false, false), '✗通关 ✗无损 ✗限时')

  const summary = buildChallengeSummary(challengeResp({
    stars: { cleared: true, noLoss: false, withinRounds: true, total: 2 },
  }))
  assert.equal(summary.conditionText, '✓通关 ✗无损 ✓限时')
})

// ---------- 解锁与门槛 ----------

test('未解锁的关卡不可点，且原因原样透传（绝不静默失败）', () => {
  const row = buildStageRow(entry({
    unlocked: false,
    lockedReason: '需要先通关第 1 章第 5 关并建造医院',
  }))
  assert.equal(row.tappable, false)
  assert.equal(row.lockedReason, '需要先通关第 1 章第 5 关并建造医院')

  const open = buildStageRow(entry({ unlocked: true, lockedReason: null }))
  assert.equal(open.tappable, true)
  assert.equal(open.lockedReason, null)
})

test('兵种限制是入场门槛，文案里不能出现「星」——它不是三星条件之一', () => {
  assert.equal(restrictionText('NONE'), null)
  for (const restriction of ['NO_SIEGE', 'CAVALRY_ONLY', 'RANGED_ONLY'] as UnitRestriction[]) {
    const text = restrictionText(restriction)
    assert.notEqual(text, null)
    assert.doesNotMatch(text ?? '', /星/)
  }
  assert.equal(restrictionText('NO_SIEGE'), '禁带攻城器')
  assert.equal(restrictionText('CAVALRY_ONLY'), '仅限骑兵')
  assert.equal(restrictionText('RANGED_ONLY'), '仅限远程')
})

test('BOSS 机制要显示出来：内核补齐前服务端会拒绝，提前写明玩家才不会把报错当 bug', () => {
  assert.equal(mechanicText('NONE'), null)
  for (const mechanic of ['REINFORCEMENT', 'SHIELD_PHASE', 'COUNTER_STRIKE'] as BossMechanic[]) {
    assert.match(mechanicText(mechanic) ?? '', /^BOSS 机制：/)
  }
})

test('列表行数与服务端下发严格一一对应，不排序、不过滤', () => {
  const stages = [
    entry({ stageId: 'c', stageNo: 3, unlocked: false, lockedReason: '未解锁' }),
    entry({ stageId: 'a', stageNo: 1 }),
    entry({ stageId: 'b', stageNo: 2 }),
  ]
  const list = buildStageList(listResp(stages, 42))
  assert.deepEqual(list.rows.map((row) => row.stageId), ['c', 'a', 'b'], '顺序必须照搬服务端')
  assert.equal(list.rows.length, 3, '未解锁的行不能被过滤掉，玩家要看得见它以及为什么锁着')
  assert.equal(list.staminaText, '体力 42')
  assert.equal(list.lockedCountText, '1 个关卡尚未解锁')
})

test('全部解锁时不再提示「N 个尚未解锁」', () => {
  assert.equal(buildStageList(listResp([entry(), entry({ stageId: 'x' })])).lockedCountText, null)
})

// ---------- 挑战结算（验收 1） ----------

test('失败不扣体力必须可见：应扣与实扣两个数都摆出来，并单独说明已退回', () => {
  const summary = buildChallengeSummary(challengeResp({
    stars: { cleared: false, noLoss: false, withinRounds: false, total: 0 },
    starsEarned: 0,
    newBest: false,
    staminaCost: 6,
    staminaCharged: 0,
    rewards: [],
  }))
  assert.equal(summary.refunded, true)
  assert.equal(summary.staminaText, '体力 应扣 6 / 实扣 0')
  assert.equal(summary.earnedText, '本次未得新星')
  assert.equal(summary.newBest, false)
  assert.deepEqual(summary.rewardLines, [])
})

test('通关时不算「退回体力」，即使实扣恰好为 0（免费关卡）', () => {
  const summary = buildChallengeSummary(challengeResp({
    stars: { cleared: true, noLoss: true, withinRounds: true, total: 3 },
    staminaCost: 0,
    staminaCharged: 0,
  }))
  assert.equal(summary.refunded, false, '本来就不扣体力的关卡不能说「已退回」')
  assert.equal(summary.staminaText, '体力 应扣 0 / 实扣 0')
})

test('损失逐阶级列出且用服务端下发的名字：只给总数的话玩家看不出掉的是 T1 还是 T5', () => {
  const summary = buildChallengeSummary(challengeResp({
    losses: [
      { unitId: 'unit_infantry_t1', name: '重步兵', count: 30 },
      { unitId: 'unit_siege_t5', name: '重型投石车', count: 2 },
    ],
  }))
  assert.deepEqual(summary.lossLines, ['重步兵 −30', '重型投石车 −2'])
  // 这一族第七次复发的形态就是把 unitId 印给玩家（#255/#268/#278/#281/#288/#290）。
  // 原来这条断言写的正是缺陷本身（'unit_infantry_t1 −30'），所以它一直绿。
  for (const line of summary.lossLines) {
    assert.doesNotMatch(line, /unit_|_t\d/, `损失行里露出内部 id：${line}`)
  }
  assert.match(summary.starsText, /2 星（历史最好）/)
  assert.equal(summary.reportId, 'r1')
})

// ---------- 扫荡结算（验收 9） ----------

test('实际执行次数少于请求次数时必须说明，且已执行的奖励照常发放', () => {
  const summary = buildSweepSummary(sweepResp(7), 10)
  assert.equal(summary.executedText, '实际扫荡 7 / 10 次')
  assert.equal(summary.shortfallText, '少扫 3 次：体力不足。已执行的 7 次奖励照常发放')
  assert.deepEqual(summary.rewardLines, ['金币 ×70'])
  assert.equal(summary.reportIds.length, 7, '每次扫荡都是一场独立战斗，都要有战报可查')
})

test('次数足额时不提「少扫」，免得玩家以为出了岔子', () => {
  const summary = buildSweepSummary(sweepResp(10), 10)
  assert.equal(summary.executedText, '实际扫荡 10 / 10 次')
  assert.equal(summary.shortfallText, null)
  assert.equal(summary.reportIds.length, 10)
})

test('扫荡请求次数必须是 1~10 的正整数：协议规定超过 10 直接拒绝而不是截断', () => {
  assert.throws(() => buildSweepSummary(sweepResp(1), 0), /正整数/)
  assert.throws(() => buildSweepSummary(sweepResp(1), 1.5), /正整数/)
  assert.throws(() => buildSweepSummary(sweepResp(1), -3), /正整数/)
})

test('空列表也能组装（新号第一次打开面板）', () => {
  const list = buildStageList(listResp([]))
  assert.deepEqual(list.rows, [])
  assert.equal(list.lockedCountText, null)
})
