/**
 * 职责：军队与医院面板的展示数据组装（B05 §二、验收 7；B06 验收 8 的带兵上限）。
 * 依赖：core/FixedPoint、生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：能不能训、训多久、治好要多少资源，全部由服务端裁定；
 * 这里只做两件客户端必须自己做的事 ——
 * 把服务端时间戳换成本地倒计时，把服务端下发的单价换成「批量要多久」的预估。
 *
 * <p><b>倒计时本地每秒递减，不轮询服务端</b>：协议在 UnitView.remainingSeconds 上明写了这条
 * （与 B03 §4 同一口径）。轮询会把一个纯显示需求变成每秒一次的请求。
 * 换算一律走 finishAt + TimeSync 偏移，<b>绝不直接拿本地 Date 相减</b> ——
 * 玩家把手机时间调快一小时就能让训练立刻完成（铁律 5）。
 *
 * <p><b>倒计时绝不为负</b>：到点后停在 0 并提示可收取。显示「-3 秒」会让玩家认为服务端算错了。
 *
 * <p><b>医院的两条红色警告</b>（B05 §1.5、验收 7）：
 * <ol>
 *   <li>{@code capacity == 0} ⇒ 所有伤兵都会因超容量直接死亡。协议明写客户端必须据此显示红色警告：
 *       不警告的话，玩家会以为「伤兵」是个安全的缓冲，然后一次次白白损失兵力</li>
 *   <li>{@code used >= capacity} ⇒ 再受伤兵就会溢出成死亡。这是玩家升级医院的唯一动机来源，
 *       而医院正是 B05 伤兵规则里唯一的那个决策点</li>
 * </ol>
 */

import { countdownMs, formatCountdown as formatCountdownOf } from '../../core/Countdown'
import * as FixedPoint from '../../core/FixedPoint'
import {
  autoTrainBlockedReason, autoTrainRunningText, autoTrainStopText, autoTrainToggleCaption,
} from './AutoTrain'
import type { TrainMemory } from './AutoTrain'
import type { ArmyListResp, HospitalView, UnitType, UnitView } from '../../net/generated/ArmyProtocol'

/** 一个兵种在面板上的一行。 */
export interface UnitRow {
  readonly unitId: string
  /** 名字来自配置表下发，客户端不得自行翻译 */
  readonly name: string
  /** 兵种类型。面板按它分页；放在行数据里是为了不用回查原响应 */
  readonly unitType: UnitType
  readonly tierText: string
  readonly countText: string
  readonly woundedText: string | null
  /** 训练中文本；未在训练为 null */
  readonly trainingText: string | null
  /** 本地倒计时文本；未在训练为 null */
  readonly countdownText: string | null
  readonly unlocked: boolean
  /** 未解锁原因，服务端算好的结构化提示，原样透传（绝不静默失败） */
  readonly unlockHint: string | null
  /** 单个兵的训练秒数与消耗，供「训 N 个要多久 / 要多少」的预估 */
  readonly trainTimeSec: number
  readonly trainCostText: string
}

/** 医院面板。 */
export interface HospitalPanel {
  readonly capacityText: string
  readonly treatingText: string | null
  readonly countdownText: string | null
  /** capacity == 0：所有伤兵都会直接死亡。必须标红 */
  readonly noCapacity: boolean
  /** used >= capacity > 0：再受伤兵就会溢出成死亡 */
  readonly overflowing: boolean
  /** 两条警告的合并文案；无警告为 null */
  readonly warningText: string | null
  readonly treatCostRatioText: string
}

/**
 * 自动续训 / 自动补兵那一条（B25-S2）。
 *
 * <p>四个字段全是**服务端下发那份策略的翻译**，客户端不自己算：
 * 按钮说什么、正在续哪一批、还剩几批、为什么停。唯一的例外是 `blockedReason` ——
 * 它来自"客户端记不记得上一次训练"，见 `game/army/AutoTrain.ts` 的说明。
 */
export interface AutoTrainPanel {
  /** 按钮上的字：自动续训 / 停止自动 */
  readonly caption: string
  readonly enabled: boolean
  /** 开着时的「重步兵 ×50 · 还剩 2 批」；没开为 null */
  readonly runningText: string | null
  /** 停下来的原因（服务端原话）；没停为 null */
  readonly stopText: string | null
  /** 现在开不了的原因（没有可续的那一批）；可以开为 null */
  readonly blockedReason: string | null
}

/** 整个军队面板。 */
export interface ArmyPanelView {
  readonly rows: readonly UnitRow[]
  readonly hospital: HospitalPanel
  /**
   * 带兵上限文本。
   *
   * <p><b>训练中占用的兵力计入上限</b>（协议明写理由）：否则玩家可以先塞满训练队列，
   * 再换一个低统率的武将，从而绕过上限。所以显示的是
   * 「已用 + 训练中 / 上限」，而不是只显示已用。
   */
  readonly troopCapText: string
  readonly queueText: string
  /** 已到上限时为 true，面板据此标红 */
  readonly troopCapFull: boolean
  /** 自动续训那一条（B25-S2） */
  readonly autoTrain: AutoTrainPanel
}

/**
 * 组装军队面板。
 *
 * @param resp        GET /army/list 的响应
 * @param offsetMs    服务端时刻 - 本地时刻，来源 core/TimeSync
 * @param localNow    当前本地时刻
 * @param trainMemory 客户端记住的上一次成功训练（`game/army/AutoTrain.ts`）。它只影响
 *                    「现在能不能开自动续训」这一句提示；策略本身全部来自响应
 */
export function buildArmyPanel(resp: ArmyListResp, offsetMs: number, localNow: number,
                               trainMemory: TrainMemory | null = null): ArmyPanelView {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  const rows = resp.units.map((unit: UnitView): UnitRow => buildUnitRow(unit, offsetMs, localNow))
  const inUse = resp.troopsInUse + resp.trainingInUse
  return {
    rows,
    hospital: buildHospitalPanel(resp.hospital, offsetMs, localNow),
    troopCapText: `带兵 ${inUse}/${resp.troopCap}`
      + (resp.trainingInUse > 0 ? `（含训练中 ${resp.trainingInUse}）` : ''),
    queueText: `训练队列 ${resp.queueSlots}/${resp.queueSlotsMax}`,
    troopCapFull: resp.troopCap > 0 && inUse >= resp.troopCap,
    autoTrain: buildAutoTrainPanel(resp, trainMemory),
  }
}

/**
 * 组装自动续训那一条。
 *
 * <p>兵种名从**响应里的 rows** 取（服务端下发的名字），取不到时退回一个明确的说法而不是露出
 * `unit_infantry_t1` 这样的内部 id —— 玩家读到的每一句都该是人话（见 `check-player-copy-jargon`）。
 */
export function buildAutoTrainPanel(resp: ArmyListResp,
                                    trainMemory: TrainMemory | null): AutoTrainPanel {
  const policy = resp.autoTrain
  const unit = resp.units.find((u: UnitView) => u.unitId === policy.unitId) ?? null
  const unitName = unit === null ? '这个兵种' : unit.name
  return {
    caption: autoTrainToggleCaption(policy),
    enabled: policy.enabled,
    runningText: autoTrainRunningText(policy, unitName),
    stopText: autoTrainStopText(policy),
    blockedReason: autoTrainBlockedReason(trainMemory),
  }
}

/** 组装一个兵种行。 */
export function buildUnitRow(unit: UnitView, offsetMs: number, localNow: number): UnitRow {
  if (unit === undefined || unit === null) {
    throw new Error('unit 不得为空')
  }
  const training = unit.training > 0
  const countdown = training ? countdownMs(unit.finishAt, offsetMs, localNow) : null
  return {
    unitId: unit.unitId,
    name: unit.name,
    unitType: unit.type,
    tierText: `T${unit.tier}`,
    countText: `可用 ${unit.count}`,
    woundedText: unit.wounded > 0 ? `伤兵 ${unit.wounded}` : null,
    trainingText: training ? `训练中 ${unit.training}` : null,
    countdownText: countdown === null ? null : formatCountdown(countdown),
    unlocked: unit.unlocked,
    unlockHint: unit.unlockHint,
    trainTimeSec: unit.trainTimeSec,
    trainCostText: unit.trainCost.map((cost) => `${cost.type} ${cost.amount}`).join(' · '),
  }
}

/** 组装医院面板。 */
export function buildHospitalPanel(hospital: HospitalView, offsetMs: number, localNow: number): HospitalPanel {
  if (hospital === undefined || hospital === null) {
    throw new Error('hospital 不得为空')
  }
  const noCapacity = hospital.capacity === 0
  const overflowing = !noCapacity && hospital.used >= hospital.capacity
  const countdown = hospital.treating
    ? countdownMs(hospital.treatFinishAt, offsetMs, localNow)
    : null

  const warnings: string[] = []
  if (noCapacity) {
    // 协议明写：capacity 为 0 时所有伤兵都会因超容量直接死亡，客户端必须显示红色警告。
    // 不警告的话玩家会把「伤兵」当成安全缓冲，然后一次次白白损失兵力
    warnings.push('医院容量为 0：所有伤兵都会因超容量直接死亡，请先建造医院')
  } else if (overflowing) {
    warnings.push(`医院已满（${hospital.used}/${hospital.capacity}）：再产生的伤兵会直接死亡`)
  }

  return {
    capacityText: `医院 ${hospital.used}/${hospital.capacity}`,
    treatingText: hospital.treating ? '治疗中' : null,
    countdownText: countdown === null ? null : formatCountdown(countdown),
    noCapacity,
    overflowing,
    warningText: warnings.length === 0 ? null : warnings.join('；'),
    treatCostRatioText: `治疗消耗为训练消耗的 ${FixedPoint.percentText(hospital.treatCostRatio)}`,
  }
}

/**
 * 「训 count 个要多久」的预估（毫秒）。
 *
 * <p>协议下发 trainTimeSec 就是为了这个预估不必让客户端去读 unit 表。
 * <b>这是预估不是结算</b>：真实时长由服务端在下单时算，
 * 中间若有加速/帮助，预估值会与实际不符 —— 所以文案上要写成「约」。
 */
export function estimateTrainMs(trainTimeSec: number, count: number): number {
  if (!Number.isInteger(trainTimeSec) || trainTimeSec < 0) {
    throw new Error(`trainTimeSec 必须是非负整数，实际=${trainTimeSec}`)
  }
  if (!Number.isInteger(count) || count < 1) {
    throw new Error(`count 必须是正整数，实际=${count}`)
  }
  return trainTimeSec * count * 1000
}

/** 「治好这些伤兵要多久」的预估（毫秒）。同样是预估，不是结算。 */
export function estimateTreatMs(hospital: HospitalView, wounded: number): number {
  if (!Number.isInteger(wounded) || wounded < 0) {
    throw new Error(`wounded 必须是非负整数，实际=${wounded}`)
  }
  return hospital.treatSecondsPerWounded * wounded * 1000
}

/** 倒计时的换算在 core/Countdown 里（与城建、行军同一套），本模块只给出军队自己的到点提示。 */
export { countdownMs }

export function formatCountdown(remainingMs: number): string {
  return formatCountdownOf(remainingMs, '已完成，可收取')
}
