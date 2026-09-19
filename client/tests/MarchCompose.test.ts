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
  buildCompose, marchUnitsOf, rememberMarch, repeatBlockedReason, repeatRequestOf, setPick,
} from '../assets/scripts/game/world/MarchCompose'
import type { MarchSpec } from '../assets/scripts/game/world/MarchCompose'
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
    hospital: { capacity: 0, wounded: 0 } as never, serverNow: 1 } as ArmyListResp
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
