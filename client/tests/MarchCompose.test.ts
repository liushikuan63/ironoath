/**
 * 职责：出征编成与「再次出征」的纯逻辑用例（B25-S1，裁决②(a) / ④(b)）。
 * 依赖：node:test + game/world/MarchCompose（不碰 cc）。
 *
 * <p><b>这些用例盯的是三件事</b>：① **不自动勾选全军**（④(b)——默认倾巢打野是给玩家挖坑）；
 * ② 记住的是**最后一次成功**的参数，重发时**新 requestId + 业务字段逐字相同**（②(a)）；
 * ③ 上次的队伍现在凑不齐时**明确说清**，绝不静默按现有数量发出去。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  adjustRallyNumber, buildCompose, marchUnitsOf, rallyFormBlocked, rallyFormOf, rallyNumbersOf,
  rallySwitchBlocked, rallyTakesNumbers, rememberMarch, repeatBlockedReason, repeatRequestOf, setPick,
} from '../assets/scripts/game/world/MarchCompose'
import type { MarchSpec, RallyForm } from '../assets/scripts/game/world/MarchCompose'
import type { RallyPolicyView } from '../assets/scripts/net/generated/SocialProtocol'
import type { ArmyListResp, UnitView } from '../assets/scripts/net/generated/ArmyProtocol'

function unit(overrides: Partial<UnitView> & { unitId: string }): UnitView {
  return {
    name: overrides.unitId, type: 'INFANTRY', tier: 1, count: 100, wounded: 0, training: 0,
    finishAt: null, remainingSeconds: null, unlocked: true, unlockHint: null, trainTimeSec: 10,
    ...overrides,
  } as UnitView
}

function army(units: UnitView[]): ArmyListResp {
  return { units, troopCap: 1000, troopsInUse: 0, trainingInUse: 0, queueSlots: 0, queueSlotsMax: 2,
    hospital: { capacity: 0, wounded: 0 } as never,
    autoTrain: { enabled: false, unitId: '', batchCount: 0, batchBudget: 0, targetCount: 0,
      stopReason: null },
    serverNow: 1 } as ArmyListResp
}

const T1 = unit({ unitId: 'unit_infantry_t1', name: '重步', count: 500 })
const T2 = unit({ unitId: 'unit_archer_t2', name: '长弓', tier: 2, count: 200 })
const LOCKED = unit({ unitId: 'unit_cavalry_t3', name: '铁骑', tier: 3, count: 80, unlocked: false,
  unlockHint: '需要马厩 10 级，当前 6 级' })

test('不自动勾选全军：没勾就是 0，且不能提交时给的是人话原因', () => {
  const view = buildCompose(army([T1, T2]), {})
  assert.deepEqual(view.options.map(o => o.selected), [0, 0])
  assert.equal(view.hasTroops, false)
  assert.equal(view.canSubmit, false)
  assert.match(view.blockedReason ?? '', /至少带一个兵/)
})

test('勾选数量被夹在 [0, 可用] 之内；未解锁的兵种即使有存量也带不出去', () => {
  const a = army([T1, LOCKED])
  let picked: Record<string, number> = {}
  picked = setPick(a, picked, 'unit_infantry_t1', 99999)
  assert.equal(picked['unit_infantry_t1'], 500, '超过可用兵力要夹到上限')
  picked = setPick(a, picked, 'unit_infantry_t1', -5)
  assert.equal(picked['unit_infantry_t1'], 0, '负数夹到 0')
  picked = setPick(a, picked, 'unit_cavalry_t3', 50)
  assert.equal(picked['unit_cavalry_t3'], 0, '未解锁的兵种带上也只会被服务端拒，所以夹到 0')

  const view = buildCompose(a, { 'unit_infantry_t1': 120, 'unit_cavalry_t3': 80 })
  assert.equal(view.options[0]?.selected, 120)
  assert.equal(view.options[1]?.selected, 0)
  assert.equal(view.totalText, '120')
  assert.equal(view.canSubmit, true)
  assert.equal(view.blockedReason, null)
})

test('拼给服务端的 units 只含选中的行（带 0 会让一次正常出征被回「数量必须为正」）', () => {
  const a = army([T1, T2])
  const view = buildCompose(a, { 'unit_infantry_t1': 0, 'unit_archer_t2': 30 })
  assert.deepEqual(marchUnitsOf(view), [{ unitId: 'unit_archer_t2', count: 30 }])
})

test('记住的是最后一次成功：失败不记；记的 spec 不带兵就是记错了', () => {
  const spec: MarchSpec = { toX: 12, toY: 34, units: [{ unitId: 'unit_infantry_t1', count: 200 }],
    heroes: ['H1'], action: 'ATTACK' }
  assert.deepEqual(rememberMarch(null, spec), spec)
  assert.throws(() => rememberMarch(null, { ...spec, units: [] }), /不可能不带兵/)
})

test('再次出征：新 requestId + 业务字段逐字相同（复用旧键会被 REQUEST_DUPLICATED 拒）', () => {
  const spec: MarchSpec = { toX: 12, toY: 34,
    units: [{ unitId: 'unit_infantry_t1', count: 200 }, { unitId: 'unit_archer_t2', count: 30 }],
    heroes: ['H1', 'H2'], action: 'ATTACK' }
  const req = repeatRequestOf(spec, 'req-new-1')
  assert.equal(req.requestId, 'req-new-1')
  assert.equal(req.toX, 12)
  assert.equal(req.toY, 34)
  assert.deepEqual(req.units, [
    { unitId: 'unit_infantry_t1', count: 200 }, { unitId: 'unit_archer_t2', count: 30 },
  ])
  assert.deepEqual(req.heroes, ['H1', 'H2'])
  assert.equal(req.action, 'ATTACK')
  assert.throws(() => repeatRequestOf(spec, ''), /requestId 不得为空/)
})

test('过时的队伍不静默改小：凑不齐时说清差多少，而不是按现有的数量发出去', () => {
  const spec: MarchSpec = { toX: 12, toY: 34,
    units: [{ unitId: 'unit_infantry_t1', count: 800 }], heroes: [], action: 'ATTACK' }
  // 现在的重步只剩 300
  const shrunk = army([unit({ unitId: 'unit_infantry_t1', name: '重步', count: 300 })])

  const reason = repeatBlockedReason(spec, shrunk)
  assert.match(reason ?? '', /800/)
  assert.match(reason ?? '', /300/)
  assert.match(reason ?? '', /重步/)

  // 兵种不在了、以及从未出征过，各有各的说法
  assert.match(repeatBlockedReason(spec, army([])) ?? '', /不在了/)
  assert.match(repeatBlockedReason(null, shrunk) ?? '', /还没有成功出征过/)
  assert.match(repeatBlockedReason(spec, null) ?? '', /军队信息还没拉到/)

  // 情况不变（数量足够）时没有理由拦：再次出征应当可发
  const enough = army([unit({ unitId: 'unit_infantry_t1', name: '重步', count: 800 })])
  assert.equal(repeatBlockedReason(spec, enough), null)
})

// ---------- B26 S14：层级与那两个数（界全部来自服务端政策）----------

/** 一份联盟层的政策。默认「此刻只有 12 人可召集」，与 global 表的 20 刻意不同。 */
function policy(overrides: Partial<RallyPolicyView> = {}): RallyPolicyView {
  return {
    minMembers: 2, maxMembers: 12, minPrepareMinutes: 10, maxPrepareMinutes: 30,
    defaultPrepareMinutes: 30, canStart: true, reason: null, ...overrides,
  } as RallyPolicyView
}

test('那两个数的起始值照政策取：上界是"此刻实际人数"12，不是 global 表里的配置上限', () => {
  assert.deepEqual(rallyFormOf(policy()), { maxMembers: 12, prepareMinutes: 30 })
  assert.equal(rallyFormOf(null), null, '政策没拉到 ⇒ 不猜一组数')
  // 政策本身越界（配置改过、旧响应还在飞）时按界夹，不把越界的数带到写口
  assert.deepEqual(rallyFormOf(policy({ maxMembers: 1, defaultPrepareMinutes: 999 })),
    { maxMembers: 1, prepareMinutes: 30 })
})

test('政策说这一层发起不了 ⇒ 用它那句原话；政策没拉到不是"你不行"', () => {
  assert.equal(rallySwitchBlocked(policy()), null)
  assert.equal(rallySwitchBlocked(policy({ canStart: false, reason: '你还没有联盟' })), '你还没有联盟')
  assert.equal(rallySwitchBlocked(policy({ canStart: false, reason: null })),
    '现在还不能发起这一层的集结', '服务端偶尔不给句子 ⇒ 客户端要有兜底文案，不能显示空')
  assert.equal(rallySwitchBlocked(null), null)
})

test('加减一档夹在政策的界内：人数按 1 走、分钟按 5 走，越界不绕回', () => {
  const pol = policy({ maxMembers: 5, defaultPrepareMinutes: 12 })
  let form = rallyFormOf(pol) as RallyForm
  form = adjustRallyNumber(form, pol, 'maxMembers', 1) as RallyForm
  assert.equal(form.maxMembers, 5, '已经在上界 ⇒ 加不动（越界的数发出去会被服务端夹回去，玩家以为设了 8 人）')
  form = adjustRallyNumber(form, pol, 'maxMembers', -1) as RallyForm
  assert.equal(form.maxMembers, 4)
  for (let i = 0; i < 20; i++) {
    form = adjustRallyNumber(form, pol, 'maxMembers', -1) as RallyForm
  }
  assert.equal(form.maxMembers, 2, '下界是最少参与人数：一个人「集结」就是普通出征')
  assert.equal(adjustRallyNumber({ maxMembers: 4, prepareMinutes: 12 }, pol, 'prepareMinutes', -1)?.prepareMinutes,
    10, '最短那一档')
  assert.equal(adjustRallyNumber({ maxMembers: 4, prepareMinutes: 12 }, pol, 'prepareMinutes', 1)?.prepareMinutes,
    17, '一分钟一分钟地磨太累：时长按 5 分跳')
  assert.equal(adjustRallyNumber(null, pol, 'maxMembers', 1), null)
  assert.equal(adjustRallyNumber({ maxMembers: 4, prepareMinutes: 12 }, null, 'maxMembers', 1)?.maxMembers, 4,
    '没有界就不动')
})

test('画出来的两行带界与格式化好的字：数字与上界同屏，玩家才知道自己被夹在哪一档', () => {
  const rows = rallyNumbersOf({ maxMembers: 7, prepareMinutes: 20 }, policy())
  assert.deepEqual(rows.map(r => [r.field, r.caption, r.text]),
    [['maxMembers', '人数', '7/12人'], ['prepareMinutes', '等待', '20分']])
  assert.deepEqual(rows.map(r => [r.min, r.max]), [[2, 12], [10, 30]])
  assert.deepEqual(rallyNumbersOf(null, policy()), [], '表单没填出来就一行都不画')
  assert.deepEqual(rallyNumbersOf({ maxMembers: 7, prepareMinutes: 20 }, null), [])
  assert.equal(rallyFormBlocked({ maxMembers: 7, prepareMinutes: 20 }), null)
  assert.match(rallyFormBlocked(null) ?? '', /还没拉到/)
})

test('V22-b：要填数的那几层是一条谓词，不是散在各处的 === "ALLIANCE"', () => {
  // 小队层那两个数由服务端自己定（协议里就没有那两个字段）；联盟与国家两层收的是同一对字段。
  // 判断只写一处：漏改任何一处的症状是"切到国家层不画数 ⇒ 确认被拦成政策还没拉到"。
  assert.deepEqual(['SQUAD', 'ALLIANCE', 'NATION'].map(s => rallyTakesNumbers(s as never)),
    [false, true, true])
})
