/**
 * 职责：自动续训 / 自动补兵的客户端纯逻辑用例（B25-S2d，裁决③(a)）。
 * 依赖：node:test + game/army/AutoTrain（不碰 cc）。
 *
 * <p><b>这些用例盯的是三件事</b>：① 开着 / 关着的按钮与状态行说的是真话（兵种、数量、还剩几批、
 * 为什么停 —— 全部来自服务端下发的那份策略，客户端不自己算）；② 开的请求带的是**上一次成功训练**
 * 的那一批，关的请求**只带 enabled:false**（契约明写"不必再报一遍目标"）；③ 没东西可续时
 * **明确拦下并说清**，不发一个必然被拒的请求。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  AUTO_TRAIN_BATCH_BUDGET, autoTrainBlockedReason, autoTrainRequest, autoTrainRunningText,
  autoTrainStopText, autoTrainToggleCaption, prepareAutoRefill, rememberTrain,
} from '../assets/scripts/game/army/AutoTrain'
import type { AutoTrainView } from '../assets/scripts/net/generated/ArmyProtocol'
import type { ArmyListResp } from '../assets/scripts/net/generated/ArmyProtocol'
import type { MarchSpec } from '../assets/scripts/game/world/MarchCompose'

const refillArmy: Pick<ArmyListResp, 'units'> = { units: [{ unitId: 'unit_infantry_t1',
  name: '重步兵 T1', type: 'INFANTRY', tier: 1, count: 9, wounded: 0, training: 2,
  unlocked: true, finishAt: null, remainingSeconds: 0, unlockHint: null,
  trainTimeSec: 60, trainCost: [] }] }
const refillMarch: MarchSpec = { toX: 3, toY: 4, action: 'ATTACK', heroes: [],
  units: [{ unitId: 'unit_infantry_t1', count: 20 }] }

test('补兵确认只采用同兵种的成功出征目标与成功训练单批，沿既有预算', () => {
  const prepared = prepareAutoRefill(rememberTrain('unit_infantry_t1', 5), refillMarch, refillArmy)
  assert.equal(prepared.kind, 'ready')
  if (prepared.kind !== 'ready') assert.fail('应当准备确认而不是直接发送')
  assert.deepEqual(prepared.request, { enabled: true, unitId: 'unit_infantry_t1', count: 5,
    targetCount: 20, batchBudget: AUTO_TRAIN_BATCH_BUDGET })
  assert.deepEqual(prepared.view, { unitName: '重步兵 T1', availableCount: 9, trainingCount: 2,
    targetCount: 20, batchCount: 5, batchBudget: AUTO_TRAIN_BATCH_BUDGET,
    submitting: false, notice: null })
  assert.equal('requestId' in prepared.request, false, '确认之前不分配业务请求键')
})

test('补兵缺任一真实输入时说明缺哪一份，不猜目标或兵力', () => {
  for (const [memory, march, army, reason] of [
    [null, refillMarch, refillArmy, /先.*训练|先手动训/],
    [rememberTrain('unit_infantry_t1', 5), null, refillArmy, /成功出征/],
    [rememberTrain('unit_infantry_t1', 5), refillMarch, null, /军队/],
  ] as const) {
    const prepared = prepareAutoRefill(memory, march, army)
    assert.equal(prepared.kind, 'blocked')
    if (prepared.kind !== 'blocked') assert.fail('无输入不应构造请求')
    assert.match(prepared.reason, reason)
    assert.equal(prepared.reason.includes('unit_'), false)
  }
})

test('训练兵种不匹配上次编成，或兵种消失/锁定时不生成补兵请求', () => {
  const wrong = prepareAutoRefill(rememberTrain('unit_archer_t1', 5), refillMarch, refillArmy)
  assert.equal(wrong.kind, 'blocked')
  for (const army of [{ units: [] }, { units: [{ ...refillArmy.units[0]!, unlocked: false }] }]) {
    const prepared = prepareAutoRefill(rememberTrain('unit_infantry_t1', 5), refillMarch, army)
    assert.equal(prepared.kind, 'blocked')
    if (prepared.kind === 'blocked') assert.equal(prepared.reason.includes('unit_infantry_t1'), false)
  }
})

test('不安全数量和重复兵种编成不能通过确认准备而被静默改小', () => {
  for (const count of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(prepareAutoRefill({ unitId: 'unit_infantry_t1', count }, refillMarch, refillArmy).kind, 'blocked')
    assert.equal(prepareAutoRefill(rememberTrain('unit_infantry_t1', 5), { ...refillMarch,
      units: [{ unitId: 'unit_infantry_t1', count }] }, refillArmy).kind, 'blocked')
  }
  assert.equal(prepareAutoRefill(rememberTrain('unit_infantry_t1', 5), { ...refillMarch,
    units: [...refillMarch.units, ...refillMarch.units] }, refillArmy).kind, 'blocked')
})

test('确认面上的名字只取军队下发名，空名使用泛称而不是内部ID', () => {
  const prepared = prepareAutoRefill(rememberTrain('unit_infantry_t1', 5), refillMarch,
    { units: [{ ...refillArmy.units[0]!, name: '' }] })
  assert.equal(prepared.kind, 'ready')
  if (prepared.kind === 'ready') assert.equal(prepared.view.unitName, '该兵种')
})

function policy(overrides: Partial<AutoTrainView> = {}): AutoTrainView {
  return {
    enabled: false, unitId: 'none', batchCount: 1, batchBudget: 0, targetCount: 0,
    stopReason: null, ...overrides,
  }
}

test('按钮与状态行：关着只有按钮，开着才有「盯着哪一批、还剩几批」', () => {
  const off = policy()
  assert.equal(autoTrainToggleCaption(off), '自动续训')
  assert.equal(autoTrainRunningText(off, '重步兵'), null, '关着的时候不该有"正在盯着什么"的说明')

  const on = policy({ enabled: true, unitId: 'unit_infantry_t1', batchCount: 50, batchBudget: 2 })
  assert.equal(autoTrainToggleCaption(on), '停止自动')
  assert.equal(autoTrainRunningText(on, '重步兵'), '重步兵 ×50 · 还剩 2 批')
})

test('补兵模式的状态行说的是目标而不是每批数量', () => {
  const refill = policy({ enabled: true, unitId: 'unit_infantry_t1', batchCount: 500,
    targetCount: 800, batchBudget: 3 })
  assert.equal(autoTrainRunningText(refill, '重步兵'), '重步兵 补到 800 · 还剩 3 批')
})

test('停止原因原样透传：服务端写的那句人话就是玩家读到的那句', () => {
  const stopped = policy({ stopReason: '资源不够，自动续训已停下（不会自动恢复，想继续请再开一次）' })
  assert.match(autoTrainStopText(stopped) ?? '', /资源不够/)
  assert.equal(autoTrainStopText(policy()), null)
})

test('记住的是最后一次成功训练的那一批；0/负数当场拒（续训会照着它一直排）', () => {
  const first = rememberTrain('unit_infantry_t1', 50)
  assert.deepEqual(first, { unitId: 'unit_infantry_t1', count: 50 })
  const second = rememberTrain('unit_archer_t2', 20)
  assert.deepEqual(second, { unitId: 'unit_archer_t2', count: 20 })
  assert.throws(() => rememberTrain('unit_infantry_t1', 0), /数量必须为正/)
  assert.throws(() => rememberTrain('', 10), /兵种不得为空/)
})

test('开：请求带的是上一次那一批 + 默认批次预算（服务端还有更小的上限，这里是 UI 默认）', () => {
  const body = autoTrainRequest(policy(), rememberTrain('unit_infantry_t1', 50))
  assert.deepEqual(body, {
    enabled: true, unitId: 'unit_infantry_t1', count: 50,
    batchBudget: AUTO_TRAIN_BATCH_BUDGET, targetCount: null,
  })
  assert.ok(AUTO_TRAIN_BATCH_BUDGET > 0 && AUTO_TRAIN_BATCH_BUDGET <= 5,
    '默认预算要在服务端上限（现 5）之内，否则一开就被拒')
})

test('关：只带 enabled:false（契约明写不必再报一遍目标，否则玩家会以为关不掉）', () => {
  const body = autoTrainRequest(policy({ enabled: true, unitId: 'unit_infantry_t1', batchCount: 50 }),
    rememberTrain('unit_infantry_t1', 50))
  assert.deepEqual(body, { enabled: false, unitId: null, count: null, batchBudget: null, targetCount: null })
})

test('没东西可续：明确拦下并说清原因，不构造请求', () => {
  assert.match(autoTrainBlockedReason(null) ?? '', /先手动训一批/)
  assert.equal(autoTrainBlockedReason(rememberTrain('unit_infantry_t1', 50)), null)
  assert.throws(() => autoTrainRequest(policy(), null), /先手动训一批/)
})
