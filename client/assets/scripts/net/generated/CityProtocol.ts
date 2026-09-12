/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

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
 * 建筑状态。与 game-core 的 BuildingStatus 枚举一一对应。
 */
export type BuildingStatus =
  | 'IDLE'
  | 'UPGRADING'
  | 'PAUSED'

/**
 * 加速来源。免费与付费共用同一接口，用本字段区分，供埋点与防刷使用（B03 §3）。AD 有每日次数上限，GOLD 走扣费。
 */
export type SpeedUpSource =
  | 'AD'
  | 'ALLIANCE'
  | 'SQUAD'
  | 'ITEM'
  | 'GOLD'

/**
 * 资源快照（城建列表用）。字段语义与 player.schema.json 的 ResourceState 一致。
 */
export interface ResourceStateView {
  current: number
  cap: number
  protectedAmount: number
  perHour: number
  lastSettle: number
}

/**
 * 资源数量条目。用数组而不是 Map 是为了让客户端能保持配置表顺序展示。
 */
export interface ResourceAmount {
  type: ResourceType
  amount: number
}

/**
 * 结构化错误详情（B03 §2）。客户端直接拼成「还缺 XXX」，不显示笼统的「条件不足」。need 与 current 都必填，缺一即退化成笼统提示。
 */
export interface ErrorDetail {
  /** 需要什么，如「主城 8 级」「木材 12000」 */
  need: string
  /** 当前是什么，如「主城 6 级」「木材 3400」 */
  current: string
}

/**
 * 单个建筑的客户端视图。finishAt 是服务端时间戳，客户端用 TimeSync 换算成本地倒计时后每秒本地刷新，不再请求服务端（B03 §4）。
 */
export interface BuildingView {
  /** 建筑实例 id（玩家城内唯一） */
  id: string
  /** 配置表 building.json 的行 id */
  configId: string
  level: number
  gridX: number
  gridY: number
  status: BuildingStatus
  /** 升级完成时刻；非升级中为 null */
  finishAt: number | null
  /** 剩余秒数，服务端算好后下发，客户端不得自行推算负数 */
  remainingSeconds: number | null
  /** 进度（定点 0~10000）。客户端除以 10000 显示百分比，绝不自己算，避免与服务端不一致 */
  progress: number
  /** 已获得的联盟/小队帮助次数 */
  helpCount: number
}

/**
 * 建造队列视图。used 与 available 都下发，客户端据此显示「可开启第 N 队列」（B03 验收 7）。
 */
export interface QueueView {
  used: number
  /** 当前可用队列数，已含新手保护期的额外队列与特权队列 */
  available: number
  /** 队列上限（含特权），来源 city_rule_max_queue_count */
  max: number
}

/**
 * POST /city/upgrade 请求体。gridX/gridY 用于新建 placement，已存在的建筑升级时可省略（服务端按 configId 找实例）。
 */
export interface CityUpgradeReq {
  /** 幂等键。同一 requestId 只扣一次资源（B03 验收 10） */
  requestId: string
  /** building.json 的行 id，如 barracks */
  configId: string
  /** 首次放置时的地块坐标 */
  gridX: number | null
  gridY: number | null
}

/**
 * POST /city/upgrade 响应体。
 */
export interface CityUpgradeResp {
  buildingId: string
  /** 升级后的等级（升级中为目标等级） */
  level: number
  /** 完成时刻（服务端毫秒时间戳） */
  finishAt: number
  /** 本次实际扣除的资源 */
  cost: ResourceAmount[]
  /** 战力变化量，客户端飘字用（B03 §4） */
  powerDelta: number
}

/**
 * POST /city/speedUp 请求体。免费与付费共用（B03 §3），source 决定校验与埋点路径。
 */
export interface SpeedUpReq {
  requestId: string
  buildingId: string
  source: SpeedUpSource
  /** source=ITEM 时必填，指向 item.json 的行 id */
  itemId: string | null
}

/**
 * POST /city/speedUp 响应体。
 */
export interface SpeedUpResp {
  buildingId: string
  /** 实际提前的秒数，会被剩余时间截断（不会出现负数，B03 禁止项） */
  reducedSeconds: number
  remainingSeconds: number
  /** 是否已加速到完成 */
  finished: boolean
}

/**
 * POST /city/cancel 请求体。
 */
export interface CityCancelReq {
  requestId: string
  buildingId: string
}

/**
 * POST /city/cancel 响应体。refund 是实际返还量（B03 §2：取消返还 60%）。
 */
export interface CityCancelResp {
  buildingId: string
  refund: ResourceAmount[]
}

/**
 * GET /city/list 响应体 —— 含离线结算后的资源与到点收割后的建筑状态（B03 §2）。
 */
export interface CityListResp {
  buildings: BuildingView[]
  queues: QueueView
  /** 离线结算后的资源快照 */
  resources: Record<ResourceType, ResourceStateView>
  serverNow: number
}

/**
 * POST /city/collect 请求体。收割已到点的升级并结算其离线产出（B03 §2：升级中不产资源，完成后一次性结算）。
 */
export interface CityCollectReq {
  requestId: string
  /** 指定收割某个建筑；为空表示收割全部已到点的建筑 */
  buildingId: string | null
}

/**
 * POST /city/collect 响应体。output 是本次结算实际入账的产量：产出走连续惰性结算（建筑按等级计入每小时产率，升级中不计，完成时刻起按新等级计），所以「收割升级」与「结算产量」是同一个动作的两面。离线累积的上限由仓储容量承担（满仓即停产），不另设追溯时长上限。buildings 是本次被收割的建筑（等级已 +1），客户端据此播放升级动效。
 */
export interface CityCollectResp {
  /** 本次完成升级的建筑（等级已 +1、状态回到 IDLE） */
  collected: BuildingView[]
  /** 补结算的离线产出合计 */
  output: ResourceAmount[]
  serverNow: number
}
