/**
 * 职责：ArmyPanel 的单测 —— B05 §二（训练）、§1.5（医院与伤兵）、验收 7（溢出提示）、
 * B06 验收 8（带兵上限）。
 * 依赖：node:test / node:assert。
 *
 * <p>重点盯两条：
 * <ol>
 *   <li><b>医院容量为 0 时必须给红色警告</b>。协议在 HospitalView 上明写了这条：
 *       capacity 为 0 时所有伤兵都会因超容量直接死亡。不警告的话，
 *       玩家会把「伤兵」当成安全缓冲，然后一次次白白损失兵力</li>
 *   <li><b>训练中占用的兵力要计入带兵上限</b>。协议明写了理由：
 *       否则玩家可以先塞满训练队列，再换一个低统率的武将，从而绕过上限</li>
 * </ol>
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildArmyPanel, buildHospitalPanel, buildUnitRow, estimateTrainMs, estimateTreatMs,
} from '../game/army/ArmyPanel'
import type { ArmyListResp, HospitalView, UnitView } from '../net/generated/ArmyProtocol'

const HOUR = 3600_000

function unit(overrides: Partial<UnitView> = {}): UnitView {
  return {
    unitId: 'unit_infantry_t1',
    name: '民兵',
    type: 'INFANTRY',
    tier: 1,
    count: 500,
    wounded: 0,
    training: 0,
    finishAt: null,
    remainingSeconds: null,
    unlocked: true,
    unlockHint: null,
    trainTimeSec: 6,
    trainCost: [{ type: 'GRAIN', amount: 10 }, { type: 'IRON', amount: 5 }],
    ...overrides,
  }
}

function hospital(overrides: Partial<HospitalView> = {}): HospitalView {
  return {
    capacity: 1000,
    used: 200,
    treating: false,
    treatFinishAt: null,
    treatRemainingSeconds: 0,
    treatSecondsPerWounded: 3,
    treatCostRatio: 5000,
    ...overrides,
  }
}

function armyResp(units: UnitView[], overrides: Partial<ArmyListResp> = {}): ArmyListResp {
  return {
    units,
    troopCap: 2000,
    troopsInUse: 800,
    trainingInUse: 0,
    queueSlots: 1,
    queueSlotsMax: 3,
    hospital: hospital(),
    serverNow: 0,
    ...overrides,
  }
}

// ---------- 兵种行 ----------

test('训练中的兵种显示数量与本地倒计时；未训练时两者都不显示', () => {
  const training = buildUnitRow(unit({
    training: 300, finishAt: 10_000, wounded: 40,
  }), 0, 5_000)
  assert.equal(training.trainingText, '训练中 300')
  assert.equal(training.countdownText, '00分05秒')
  assert.equal(training.woundedText, '伤兵 40')
  assert.equal(training.countText, '可用 500')
  assert.equal(training.unitType, 'INFANTRY')
  assert.equal(training.tierText, 'T1')

  const idle = buildUnitRow(unit(), 0, 5_000)
  assert.equal(idle.trainingText, null)
  assert.equal(idle.countdownText, null)
  assert.equal(idle.woundedText, null, '伤兵为 0 时不显示，「伤兵 0」只是噪音')
})

test('倒计时走 TimeSync 偏移且绝不为负（协议在 remainingSeconds 上明写的两条纪律）', () => {
  // 玩家把手机时间调快一小时 ⇒ offsetMs = -1 小时
  const row = buildUnitRow(unit({ training: 10, finishAt: 10 * HOUR }), -HOUR, 9 * HOUR)
  assert.equal(row.countdownText, '2小时00分00秒', '本地 9 点其实是服务端 8 点，还剩 2 小时')

  const done = buildUnitRow(unit({ training: 10, finishAt: 1000 }), 0, 5000)
  assert.equal(done.countdownText, '已完成，可收取')
})

test('未解锁兵种的提示原样透传：一个灰掉的兵种不说明为什么，玩家会以为是 bug', () => {
  const locked = buildUnitRow(unit({
    unlocked: false, unlockHint: '需要兵营 10 级，当前 6 级', tier: 5,
  }), 0, 0)
  assert.equal(locked.unlocked, false)
  assert.equal(locked.unlockHint, '需要兵营 10 级，当前 6 级')
  assert.equal(buildUnitRow(unit(), 0, 0).unlockHint, null)
})

test('训练消耗按配置表顺序列出，名字用服务端下发的资源 id', () => {
  assert.equal(buildUnitRow(unit(), 0, 0).trainCostText, 'GRAIN 10 · IRON 5')
})

// ---------- 医院（B05 §1.5、验收 7） ----------

test('医院容量为 0 时给最严重的一档警告：所有伤兵都会因超容量直接死亡', () => {
  const panel = buildHospitalPanel(hospital({ capacity: 0, used: 0 }), 0, 0)
  assert.equal(panel.noCapacity, true)
  assert.equal(panel.overflowing, false, '容量为 0 是更严重的一档，不再叠加「已满」')
  assert.match(panel.warningText ?? '', /医院容量为 0/)
  assert.match(panel.warningText ?? '', /直接死亡/)
})

test('医院已满时警告「再产生的伤兵会直接死亡」——这是玩家升级医院的唯一动机来源', () => {
  const panel = buildHospitalPanel(hospital({ capacity: 1000, used: 1000 }), 0, 0)
  assert.equal(panel.noCapacity, false)
  assert.equal(panel.overflowing, true)
  assert.equal(panel.warningText, '医院已满（1000/1000）：再产生的伤兵会直接死亡')
})

test('医院正常时不给警告；治疗中显示倒计时与消耗比例', () => {
  const normal = buildHospitalPanel(hospital(), 0, 0)
  assert.equal(normal.warningText, null)
  assert.equal(normal.capacityText, '医院 200/1000')
  assert.equal(normal.treatingText, null)
  assert.equal(normal.countdownText, null)
  assert.equal(normal.treatCostRatioText, '治疗消耗为训练消耗的 50%')

  const treating = buildHospitalPanel(hospital({ treating: true, treatFinishAt: 90_000 }), 0, 30_000)
  assert.equal(treating.treatingText, '治疗中')
  assert.equal(treating.countdownText, '01分00秒')
})

// ---------- 带兵上限（B06 验收 8） ----------

test('训练中占用的兵力计入带兵上限，并在文案里单独点出来', () => {
  const panel = buildArmyPanel(armyResp([unit()], {
    troopCap: 2000, troopsInUse: 800, trainingInUse: 1200,
  }), 0, 0)
  assert.equal(panel.troopCapText, '带兵 2000/2000（含训练中 1200）')
  assert.equal(panel.troopCapFull, true,
    '不计入的话玩家可以先塞满训练队列再换低统率武将来绕过上限')

  const spare = buildArmyPanel(armyResp([unit()], {
    troopCap: 2000, troopsInUse: 800, trainingInUse: 0,
  }), 0, 0)
  assert.equal(spare.troopCapText, '带兵 800/2000')
  assert.equal(spare.troopCapFull, false)
})

test('训练队列显示已用/上限', () => {
  assert.equal(buildArmyPanel(armyResp([unit()], { queueSlots: 2, queueSlotsMax: 3 }), 0, 0).queueText,
    '训练队列 2/3')
})

test('troopCap 为 0 时不算「已满」：0/0 是数据还没就绪，不是玩家把兵塞满了', () => {
  const panel = buildArmyPanel(armyResp([unit()], { troopCap: 0, troopsInUse: 0 }), 0, 0)
  assert.equal(panel.troopCapFull, false)
})

test('20 个兵种（4 类型 × 5 阶级）全部进面板，含未解锁的 —— 玩家要看得见自己还差什么', () => {
  const units: UnitView[] = []
  for (const type of ['INFANTRY', 'CAVALRY', 'ARCHER', 'SIEGE'] as UnitView['type'][]) {
    for (let tier = 1; tier <= 5; tier++) {
      units.push(unit({ unitId: `${type}_t${tier}`, type, tier, unlocked: tier <= 3 }))
    }
  }
  const panel = buildArmyPanel(armyResp(units), 0, 0)
  assert.equal(panel.rows.length, 20)
  assert.equal(panel.rows.filter((row) => !row.unlocked).length, 8)
  assert.equal(panel.hospital.capacityText, '医院 200/1000')
})

// ---------- 预估 ----------

test('训练时长预估 = 单个秒数 × 数量：协议明写「批量不等于加速」', () => {
  assert.equal(estimateTrainMs(6, 1), 6_000)
  assert.equal(estimateTrainMs(6, 100), 600_000, '100 个就是 100 倍时间，不是同一份时间')
  assert.throws(() => estimateTrainMs(6, 0), /正整数/)
  assert.throws(() => estimateTrainMs(-1, 10), /非负整数/)
})

test('治疗时长预估 = 每个伤兵秒数 × 伤兵数', () => {
  assert.equal(estimateTreatMs(hospital({ treatSecondsPerWounded: 3 }), 200), 600_000)
  assert.equal(estimateTreatMs(hospital(), 0), 0)
  assert.throws(() => estimateTreatMs(hospital(), -1), /非负整数/)
})
