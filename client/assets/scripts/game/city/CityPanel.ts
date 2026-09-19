/**
 * 职责：城建面板的展示数据组装（B03 §2/§4，验收 7、10）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：能不能升级由服务端裁定并把理由放进 ErrorDetail，
 * 队列够不够由 QueueView 的 used/available/max 说明，产出多少由服务端结算后下发。
 * 这里只做两件事：把结构化错误拼成人话，把服务端时间戳换成本地倒计时文本。
 *
 * <p><b>倒计时的两条纪律</b>（B03 §4）：
 * <ol>
 *   <li>用 finishAt（服务端时间戳）+ TimeSync 的偏移换算，<b>不用本地 Date 直接算</b> ——
 *       玩家把手机时间调快一小时，升级就该真的还要一小时，而不是立刻完成</li>
 *   <li><b>绝不为负</b>。协议明写「remainingSeconds 由服务端算好下发，客户端不得自行推算负数」；
 *       本地倒计时走到 0 就停在 0，并提示「已完成，点击收割」——
 *       显示「-3 秒」会让玩家以为服务端算错了</li>
 * </ol>
 */

import { resourceName } from '../ui/ResourceNames'
import { countdownMs, formatCountdown as formatCountdownOf } from '../../core/Countdown'
import type {
  BuildingView, BuildOptionView, CityCollectResp, CityListResp, ErrorDetail, QueueView, ResourceAmount,
} from '../../net/generated/CityProtocol'

/** 一栋建筑在面板上的一行。 */
export interface BuildingRow {
  readonly id: string
  readonly configId: string
  /** 服务端下发的建筑中文名（来自 building.json 的 name）。客户端不自己翻译：表数据不在包里 */
  readonly name: string
  readonly level: number
  readonly gridX: number
  readonly gridY: number
  /** 「伐木场 Lv6」这样的标题。等级是已达成的等级，升级中也是它（不是目标等级） */
  readonly title: string
  /** 状态文本：空闲 / 升级中 / 已暂停 / 已完成待收割 */
  readonly statusText: string
  /** 本地倒计时文本；非升级中为 null */
  readonly countdownText: string | null
  /** 进度百分比文本，来自服务端的定点 progress，客户端只除 10000 */
  readonly progressText: string | null
  readonly helpText: string | null
  /**
   * 是否可以点「收割」。
   *
   * <p>协议里没有 FINISHED 状态：升级到点的建筑<b>仍然是 UPGRADING</b>，
   * 要等 /city/collect 才 +1 级并回到 IDLE。所以「可收割」= 升级中且本地倒计时已归零。
   * 这只是个显示上的可点性 —— 真正的裁定在服务端，客户端算错了顶多是被拒绝一次。
   */
  readonly collectable: boolean
  readonly upgrading: boolean
  /** 升级被暂停（PAUSED）。暂停时 finishAt 仍在但倒计时不走，必须与「升级中」区分开 */
  readonly paused: boolean
}

export const CITY_GRID_WIDTH = 6
export const CITY_GRID_HEIGHT = 6

export interface CityGrid {
  /** 以 gridY * CITY_GRID_WIDTH + gridX 为下标；空位为 null。 */
  readonly cells: readonly (BuildingRow | null)[]
  /** 坐标非法或重复的建筑。场景层必须显式提示，不能静默丢掉。 */
  readonly unplaced: readonly BuildingRow[]
}

/** 整个城建面板的数据。 */
export interface CityPanelView {
  readonly rows: readonly BuildingRow[]
  /** 尚未放置的建筑配置；场景层用它弹出首次建造选择器。 */
  readonly buildOptions: readonly BuildOptionView[]
  readonly queueText: string
  /** 还能再开几条队列；已满时为 null（B03 验收 7：客户端据此显示「可开启第 N 队列」） */
  readonly queueExpandText: string | null
  readonly resourceLines: readonly string[]
  /** 有建筑升级已到时但还没收割时的提示 */
  readonly collectHint: string | null
}

/**
 * 组装城建面板。
 *
 * @param resp         GET /city/list 的响应
 * @param offsetMs     服务端时刻 - 本地时刻，来源 core/TimeSync
 * @param localNow     当前本地时刻
 */
export function buildCityPanel(resp: CityListResp, offsetMs: number, localNow: number): CityPanelView {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  const rows = resp.buildings.map((building: BuildingView): BuildingRow =>
    buildBuildingRow(building, offsetMs, localNow))

  let collectable = 0
  for (const row of rows) {
    if (row.collectable) {
      collectable++
    }
  }
  const resources: string[] = []
  for (const type of Object.keys(resp.resources)) {
    const state = resp.resources[type as keyof typeof resp.resources]
    if (state === undefined) {
      continue
    }
    // 满仓要标出来：产出停了而玩家不知道，他会以为产量被偷偷改了（B04 验收 1 的同一条纪律）。
    // 但体力这类不参与生产的资源到上限不代表停产，不能把“满了”说成“停产”。
    const full = state.perHour > 0 && state.current >= state.cap
    // 名字走 `game/ui/ResourceNames`（客户端唯一一份），不打 `WOOD` 这种枚举原文 ——
    // 那和 #255 内城印 `main_city` 是同一条缺陷，只是这次漏在资源条上。
    resources.push(`${resourceName(type)} ${state.current}/${state.cap}${full ? '（已满，停产）' : ''}`)
  }

  return {
    rows,
    // 滚动升级期间旧服务端不会带这个新字段；缺字段按“没有可建造项”处理，不能让面板崩掉。
    buildOptions: resp.buildOptions ?? [],
    queueText: queueText(resp.queues),
    queueExpandText: queueExpandText(resp.queues),
    resourceLines: resources,
    collectHint: collectable === 0 ? null : `${collectable} 个建筑已升级完成，点击收割（收割同时结算离线产出）`,
  }
}

/** 组装一行建筑。 */
export function buildBuildingRow(building: BuildingView, offsetMs: number, localNow: number): BuildingRow {
  if (building === undefined || building === null) {
    throw new Error('building 不得为空')
  }
  const upgrading = building.status === 'UPGRADING'
  const paused = building.status === 'PAUSED'
  // 暂停时不算倒计时：服务端对 PAUSED 的 remainingSeconds 恒为 0，
  // 客户端若照 finishAt 算，会显示一个「在走但永远不会到」的倒计时
  const countdown = upgrading ? countdownMs(building.finishAt, offsetMs, localNow) : null
  const done = countdown !== null && countdown <= 0
  return {
    id: building.id,
    configId: building.configId,
    name: building.name,
    level: building.level,
    gridX: building.gridX,
    gridY: building.gridY,
    // level 是<b>已达成</b>的等级：升级途中它仍是旧等级，完成收割后才 +1。
    // 把它显示成目标等级会让玩家在升级途中就以为已经拿到了新等级的产量
    title: `${building.name} Lv${building.level}`,
    statusText: statusText(building.status, done),
    countdownText: countdown === null ? null : formatCountdown(countdown),
    progressText: upgrading ? `${formatPercent(localProgressFixed(building, offsetMs, localNow))}%` : null,
    helpText: building.helpCount > 0 ? `已获帮助 ${building.helpCount} 次` : null,
    collectable: done,
    upgrading,
    paused,
  }
}

/** 把建筑放进 6×6 城内网格；无效或重复坐标进入 unplaced，由表现层明示。 */
export function buildCityGrid(rows: readonly BuildingRow[]): CityGrid {
  const cells: Array<BuildingRow | null> = Array.from(
    { length: CITY_GRID_WIDTH * CITY_GRID_HEIGHT }, () => null)
  const unplaced: BuildingRow[] = []
  for (const row of rows) {
    if (!Number.isInteger(row.gridX) || !Number.isInteger(row.gridY)
        || row.gridX < 0 || row.gridX >= CITY_GRID_WIDTH
        || row.gridY < 0 || row.gridY >= CITY_GRID_HEIGHT) {
      unplaced.push(row)
      continue
    }
    const index = row.gridY * CITY_GRID_WIDTH + row.gridX
    if (cells[index] !== null) {
      unplaced.push(row)
      continue
    }
    cells[index] = row
  }
  return { cells, unplaced }
}

/**
 * 本地推算进度（定点 0~10000）。
 *
 * <p><b>为什么不能直接用服务端下发的 progress</b>：它是<b>响应那一刻</b>的快照，而倒计时每秒在
 * 本地走 —— 只显示快照会出现「时间在减少、百分比不动」，玩家一眼就看出两处对不上。
 * 这里复刻 {@code BuildingInstance#progressFixed} 的同一条公式：
 * {@code elapsed = nowServer - startedAt}，{@code progress = elapsed / (totalSeconds × 1000)}。
 *
 * <p><b>分母必须用 totalSeconds，不能用 (finishAt - startedAt)</b>：加速会把两者一起压缩，
 * 但服务端的算法以 totalSeconds 为准，客户端换一个分母就是第二套口径。
 *
 * <p>用浮点除法而不是定点库：它只影响**显示**（最终按整数百分比取整），不参与任何结算。
 * 为了让展示数字两端逐位一致而把定点除法搬进客户端，代价大于收益（铁律 2：客户端从不结算）。
 *
 * @param totalSeconds 为 0 时回退到服务端下发的 progress（旧数据或非升级中）
 */
export function localProgressFixed(building: BuildingView, offsetMs: number, localNow: number): number {
  // 字段缺失也要能活：新客户端遇到旧服务端（灰度/滚动升级期间）时这两个字段是 undefined，
  // 直接用会算出 NaN，再喂给 formatPercent 就抛异常 —— 而那只是显示层，不该让整块面板炸掉
  const startedAt = Number.isFinite(building.startedAt) ? building.startedAt : 0
  const totalSeconds = Number.isFinite(building.totalSeconds) ? building.totalSeconds : 0
  const totalMs = totalSeconds * 1000
  // 只以「总时长」判有效：startedAt=0 是合法时间戳（服务端对非升级中下发 0），
  // 而 totalSeconds=0 才真正表示"没有可推进的区间"，此时回退到服务端快照
  if (totalMs <= 0) {
    return building.progress
  }
  const elapsed = Math.max(0, localNow + offsetMs - startedAt)
  return Math.min(10000, Math.floor((elapsed / totalMs) * 10000))
}

/** 收割响应 → 底部提示。 */
export interface CollectMessage {
  readonly text: string
  /** done = 真的有建筑升级完成；output = 只有产出补结算，没有建筑升级 */
  readonly kind: 'done' | 'output'
}

/**
 * 收割响应 → 底部提示。
 *
 * <p><b>collected 为空时绝不能报「升级完成」</b>：一键收割会顺手补结算离线产出，
 * 那种情况下 collected 是空的，而玩家一核对等级没变就会认定提示是假的 ——
 * 假成功比不提示更伤信任。所以这里分成两态，分别说清发生了什么。
 *
 * @return null 表示这一次没有任何值得说的结果（既没升级也没产出）
 */
export function collectMessage(resp: CityCollectResp): CollectMessage | null {
  const output = outputText(resp.output)
  if (resp.collected.length > 0) {
    const names = resp.collected.map((building) => `${building.name} Lv${building.level}`).join(' · ')
    return { text: `升级完成 ${names}${output === null ? '' : ` · ${output}`}`, kind: 'done' }
  }
  return output === null ? null : { text: output, kind: 'output' }
}
/**
 * 结构化错误 → 人话（B03 §2）。
 *
 * <p><b>绝不显示笼统的「条件不足」</b>：need 与 current 都下发就是为了能拼出
 * 「需要 木材 12000，当前 3400」。笼统提示会让玩家去猜，而他猜不到就会认为游戏在骗他。
 */
export function errorText(detail: ErrorDetail | null, fallback: string): string {
  if (detail === null || detail === undefined) {
    return fallback
  }
  if (detail.need.length === 0 || detail.current.length === 0) {
    // 协议要求 need 与 current 都必填，「缺一即退化成笼统提示」。
    // 但退化不等于什么都不说：把服务端给的原始错误码/消息带出来，玩家至少能拿去问客服
    return fallback
  }
  return `需要 ${detail.need}，当前 ${detail.current}`
}

/** 队列状态文本。 */
export function queueText(queues: QueueView): string {
  return `建造队列 ${queues.used}/${queues.available}`
}

/**
 * 「还能再开几条队列」（B03 验收 7）。
 *
 * <p>available 已含新手保护期的额外队列与特权队列，max 是含特权的总上限 ——
 * 两个都由服务端下发，客户端不做任何推算，否则「第 N 队列什么时候能开」就会出现两套答案。
 */
export function queueExpandText(queues: QueueView): string | null {
  if (queues.available >= queues.max) {
    return null
  }
  return `可开启第 ${queues.available + 1} 条队列（上限 ${queues.max}）`
}

/** 收割响应的产出合计文本。 */
export function outputText(output: readonly ResourceAmount[]): string | null {
  if (output.length === 0) {
    return null
  }
  return `补结算产出 ${output.map((entry) => `${entry.type} +${entry.amount}`).join(' · ')}`
}

/**
 * 倒计时的换算与格式化都在 core/Countdown 里，本模块只负责给出城建自己的到点提示。
 * 城建、军队、行军用的是同一套换算，各写一份迟早会有一份漏掉「绝不为负」或「必须走 TimeSync」其中一条。
 */
export { countdownMs }

/** 到点提示。措辞刻意与 statusText 的「已完成，待收割」错开，否则同一行会出现两句几乎一样的话。 */
export function formatCountdown(remainingMs: number): string {
  return formatCountdownOf(remainingMs, '可收割')
}

/**
 * 定点进度（0~10000）→ 整数百分比。
 *
 * <p>截断而不是四舍五入：进度条走到 100% 而升级还没完成，玩家会去点按钮然后发现点不动，
 * 那比停在 99% 更让人恼火。协议也明写「客户端除以 10000 显示百分比，绝不自己算」。
 */
export function formatPercent(progressFixed: number): number {
  if (!Number.isInteger(progressFixed)) {
    throw new Error(`progress 必须是定点整数（×10000），实际=${progressFixed}`)
  }
  return Math.min(99, Math.max(0, Math.floor(progressFixed / 100)))
}

function statusText(status: BuildingView['status'], done: boolean): string {
  switch (status) {
    case 'IDLE':
      return '空闲'
    case 'UPGRADING':
      return done ? '已完成，待收割' : '升级中'
    case 'PAUSED':
      return '已暂停（倒计时不走）'
    default:
      return status
  }
}
