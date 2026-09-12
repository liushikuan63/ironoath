/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 兵种类型。取值必须与 unit 表的 type 列一致。
 */
export type UnitType =
  | 'INFANTRY'
  | 'CAVALRY'
  | 'ARCHER'
  | 'SIEGE'

/**
 * 资源类型枚举。取值必须与 contract/config/resource.json 的 rows[].id 完全一致（CI 校验）。
 *
 * 取值与配置表 resource 的 id 集合强制一致，由生成器在 CI 中校验。
 */
export type ResourceType =
  | 'WOOD'
  | 'STONE'
  | 'IRON'
  | 'GRAIN'
  | 'GOLD'
  | 'STAMINA'

/**
 * 资源数量条目。与 city/bag 协议里的同名结构逐字一致 —— 三处漂移由 CI 的生成物 diff 暴露。用数组而不是 Map 是为了让客户端能保持配置表顺序展示。
 */
export interface ResourceAmount {
  type: ResourceType
  amount: number
}

/**
 * 一个兵种的完整视图：现有兵力、伤兵、训练中、是否已解锁。未解锁的也要下发（带 unlockHint），否则玩家看不到「再升 3 级兵营就能训 T3」这条最重要的养成牵引。
 */
export interface UnitView {
  unitId: string
  name: string
  type: UnitType
  tier: number
  /** 现有可用兵力（不含训练中、不含伤兵） */
  count: number
  /** 该兵种在医院的伤兵数 */
  wounded: number
  /** 该兵种训练中的数量；0 表示没有在训 */
  training: number
  /** 训练完成时刻；未在训练时为 null */
  finishAt: number | null
  /** 训练剩余秒数。<b>客户端本地每秒递减，不要轮询服务端</b>（B03 §4 的同一口径） */
  remainingSeconds: number | null
  /** 该阶级是否已解锁（由 unit 表的 unlockBuilding + unlockBuildingLevel 与城建状态共同决定） */
  unlocked: boolean
  /** 未解锁时的结构化提示，如「需要兵营 10 级，当前 6 级」。已解锁时为 null */
  unlockHint: string | null
  /** 单个兵的训练秒数，来自 unit 表。下发给客户端是为了让「训 1000 个要多久」这个预估不必客户端自己乘 */
  trainTimeSec: number
  /** 单个兵的训练消耗 */
  trainCost: ResourceAmount[]
}

/**
 * 医院状态。capacity 为 0 时所有伤兵都会因超容量直接死亡（B05 §1.5），客户端必须据此显示红色警告。
 */
export interface HospitalView {
  capacity: number
  used: number
  treating: boolean
  treatFinishAt: number | null
  treatRemainingSeconds: number
  /** 每个伤兵的治疗秒数。下发是为了让客户端能预估「治好这些要多久」而不必自己读配置 */
  treatSecondsPerWounded: number
  /** 治疗消耗占训练消耗的比例（定点 ×10000） */
  treatCostRatio: number
}

/**
 * GET /army/list 响应体。这个「读」接口有副作用：它会顺带收割到点的训练与治疗（惰性结算，服务端不跑定时器）。
 */
export interface ArmyListResp {
  /** 全部 20 个兵种（4 类型 × 5 阶级），含未解锁的 */
  units: UnitView[]
  /** 带兵上限 = Σ上阵武将统帅值 × TROOP_PER_COMMAND + 科技加成（B05 §二、B06 验收 8） */
  troopCap: number
  troopsInUse: number
  /** 训练中已占用的兵力。<b>它计入上限</b>，否则玩家可以先塞满队列再换低统率武将来绕过上限 */
  trainingInUse: number
  queueSlots: number
  queueSlotsMax: number
  hospital: HospitalView
  serverNow: number
}

/**
 * POST /army/train 请求体。时间 = 单位时间 × count（B05 §二），批量不等于加速。
 */
export interface TrainReq {
  requestId: string
  unitId: string
  count: number
}

/**
 * POST /army/train 响应体。cost 是本次实际扣掉的资源（= 单个消耗 × count），客户端据此播扣减动画。
 */
export interface TrainResp {
  unitId: string
  count: number
  finishAt: number
  remainingSeconds: number
  /** 本次加速实际提前的秒数（会被剩余时间截断，不会出现负数）。开始训练时恒为 0。<b>必须单独下发</b>：客户端要据此播「-1小时」的飘字，而用 remainingSeconds 的前后差反推会受时钟抖动影响；/item/use 的契约也要求回「实际提前了多少」。 */
  reducedSeconds: number
  cost: ResourceAmount[]
  troopsInUse: number
  troopCap: number
  serverNow: number
}

/**
 * 只带 unitId 的请求（取消训练、加速某一批训练）。
 */
export interface ArmyUnitReq {
  requestId: string
  unitId: string
  /** 加速秒数；加速请求必填 */
  seconds: number | null
  /** 用加速道具时填道具 id，否则为 null */
  itemId: string | null
}

/**
 * POST /army/cancel 响应体。refund 是返还的资源（与城建取消同一口径：按比例返还，比例来自配置）。
 */
export interface TrainCancelResp {
  unitId: string
  count: number
  refund: ResourceAmount[]
  serverNow: number
}

/**
 * POST /army/treat 请求体。一次治疗全部伤兵（B05 §二没有「按兵种分次治疗」，那会让医院队列变成第二个训练队列）。
 */
export interface TreatReq {
  requestId: string
}

/**
 * 开始治疗 / 加速治疗 / 收割治疗的共用响应。returned 只在收割时非空。
 */
export interface TreatResp {
  woundedCount: number
  treating: boolean
  treatFinishAt: number | null
  treatRemainingSeconds: number
  /** 开始治疗时是实际扣掉的资源；收割/查询时为空 */
  cost: ResourceAmount[]
  /** 本次归队的伤兵 */
  returned: UnitReturned[]
  serverNow: number
}

/**
 * 归队的某个兵种数量。
 */
export interface UnitReturned {
  unitId: string
  count: number
}
