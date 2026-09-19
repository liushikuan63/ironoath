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
 * 玩家档案（展示用，不含任何数值判定）
 */
export interface PlayerProfile {
  /** 玩家唯一 id（服务端生成，雪花或 UUID） */
  playerId: string
  nickName: string
  /** 头像 id，指向配置表 avatar（B12 交付） */
  avatarId: number
  /** 创角时间（服务端毫秒时间戳） */
  createdAt: number
  /** 最近登录时间（服务端毫秒时间戳） */
  lastLoginAt: number
}

/**
 * 单一资源的惰性结算快照。current 为已结算值，客户端展示时需按 (serverNow - lastSettle) * perHour / 3600 做本地预测，服务端结果到达后强制纠偏。
 */
export interface ResourceState {
  /** 已结算的资源量 */
  current: number
  /** 仓库容量上限 */
  cap: number
  /** 受保护不可掠夺量（B04 由仓库/护盾决定，B01 恒为配置初始值） */
  protectedAmount: number
  /** 每小时产量（B01 取配置底产，B03 起由建筑聚合） */
  perHour: number
  /** 上次惰性结算的服务端时间戳（毫秒） */
  lastSettle: number
}

/**
 * 战力三元组。displayPower 仅展示；matchPower 用于 PVP 匹配校验（含峰值记忆）；peakPower 为历史峰值。铁律 11：匹配一律用 matchPower。
 */
export interface PowerSnapshot {
  displayPower: number
  matchPower: number
  peakPower: number
}

/**
 * 战力明细（B08 §1）。**UI 必须能点开看这一层** —— 玩家对「我为什么是这个战力」极度敏感，只给一个总数的话，任何一次数值调整都会被理解成「偷偷削我」。
 *
 * 五项之和必须精确等于 displayPower，这条不变量在服务端由 PowerCalculator.Result 的构造器强制（对不上就抛异常），所以客户端可以放心把五行逐项列出来再加一个总计。
 */
export interface PowerBreakdown {
  /** 建筑战力：各建筑按 POWER_CONTRIB 曲线的贡献之和 */
  building: number
  /** 部队战力：Σ(兵数 × 该兵种的攻+防+血) */
  troops: number
  /** 武将战力：全部已拥有武将之和，含未上阵的 —— 展示战力要反映「我练了多少」 */
  heroes: number
  /** 科技战力。科技系统属 B12，落地前恒为 0；字段先占位，否则 B12 上线时要改协议，而改协议意味着强制客户端更新 */
  tech: number
  /** 装备战力。当前已并入 heroes（HeroCalculator 的入参含装备固定值），所以恒为 0；单列一项是为了让明细的行数与玩家的直觉一致 —— 玩家认为装备是独立的一块，看不到这一行会以为装备没算进去 */
  equipment: number
}

/**
 * GET /player/power 的响应：战力明细面板的全部数据（B08 §1）。
 *
 * 战力三元组直接复用 PowerSnapshot，不重复声明 —— 那三个字段总是一起出现，两处各写一份迟早会漂移。
 *
 * **额外下发 currentMatchPower 与 peakMemoryFloor，是为了让 matchPower 可被玩家自己复算。** matchPower = max(currentMatchPower, peakMemoryFloor)。只给结果的话，一个刚打完大仗、兵力折损的玩家会看到一个比部队实际战力更高的数字，而他没有任何线索知道那是峰值记忆在起作用 —— 那正是「这游戏在骗我」的观感来源。把两个输入都摊开，规则就变成可以自查的，而不是需要相信的。
 */
export interface PowerDetailResp {
  power: PowerSnapshot
  /** 当前部队 + 上阵主将的实际战力，不含峰值记忆 */
  currentMatchPower: number
  /** 峰值记忆托底线 = round(peakPower × PEAK_POWER_MEMORY_RATIO)。它存在的唯一目的是堵「战前卸兵压分」：卸兵之后 matchPower 不会立刻掉下去，所以压分没有收益 */
  peakMemoryFloor: number
  breakdown: PowerBreakdown
  /** 服务端时间戳。客户端不得用自己的时钟推算峰值衰减 */
  serverNow: number
}

/**
 * 时间校准结果。offset = serverNow - clientNow，客户端用加权移动平均吸收网络抖动（B00 铁律 5）。
 */
export interface TimeSync {
  offset: number
  /** 服务端发出本次校准的时间戳 */
  syncAt: number
}

/**
 * POST /player/init 请求体
 */
export interface PlayerInitReq {
  /** 幂等键。同一 requestId 重复提交只创建一次玩家（B01 验收 11） */
  requestId: string
  /** 设备唯一标识，同设备重复 init 返回同一存档 */
  deviceId: string
  nickName: string
  /** 客户端本地时间，服务端据此返回校准 offset */
  clientTime: number
  /** 微信小游戏 wx.login 拿到的临时登录凭证（B15 §三）。服务端用它调 code2session 换 openid/session_key；不传时退回 deviceId 建档（浏览器与旧客户端）。开发环境用本地兑换器，真机走微信服务器 */
  wxCode: string | null
}

/**
 * POST /player/init 响应体（data 字段内容）
 */
export interface PlayerInitResp {
  playerId: string
  /** 会话票据（B15 §三）。客户端之后每个请求都要带 X-Auth-Token；没有微信登录体系的环境返回空串 */
  authToken: string | null
  /** 服务端时间戳，客户端据此算偏移（铁律 5） */
  serverNow: number
  profile: PlayerProfile
  /** 主城等级，新号 = 1 */
  cityLevel: number
  /** 五种资源快照 */
  resources: Record<ResourceType, ResourceState>
  power: PowerSnapshot
  /** 新手保护到期时间（服务端毫秒时间戳）；null 表示无保护 */
  protectUntil: number | null
  /** 「自上次登录以来」的汇总**判定依据**（B25-S3）。下发的是时间边界与两个阈值，不是一个算好的汇总 —— 汇总由客户端从它已经拉到的面板数据里聚合（裁决①(a)：只聚合既有账本，不新造第二本账） */
  offlineReport: OfflineReportView
}

/**
 * POST /time/sync 请求体
 */
export interface TimeSyncReq {
  clientTime: number
}

/**
 * POST /time/sync 响应体（data 字段内容）
 */
export interface TimeSyncResp {
  sync: TimeSync
}

/**
 * 统一响应封装。code=0 表示成功，非 0 见 ErrorCode。traceId 用于线上排查（铁律 10）。
 */
export interface ApiResult {
  code: number
  msg: string
  /** 业务负载，类型随接口而定 */
  data: unknown
  traceId: string
  serverNow: number
  /** 错误详情，成功时为 null */
  detail: string | null
}

/**
 * GET /stamina 的响应（B09 §5）。
 *
 * **对 B09 契约的一处偏离**：文档给的是 `recoverPerMin`，但恢复速率是「每 6 分钟 1 点」，换算成每分钟就是 1/6 点 —— 一个 long 装不下，而铁律禁止用 double 表示这类数值。所以这里下发 `recoverPerHour`（整数，精确）加 `nextPointAt`（下一点恢复的服务端时刻）。UI 真正需要的是倒计时，`nextPointAt` 直接就是它，比一个每分钟速率更有用。
 */
export interface StaminaResp {
  /** 当前体力（已完成惰性恢复结算） */
  current: number
  /** 体力上限 = STAMINA_CAP_BASE + STAMINA_CAP_PER_LEVEL × 主城等级。溢出部分永久损失，不结转（B09 §5 禁止项：不要让体力溢出超过上限） */
  cap: number
  /** 每小时恢复点数，来自 resource 表 STAMINA 行的 basePerHour */
  recoverPerHour: number
  /** 下一点恢复的服务端毫秒时刻；已满时为 null（满了就不该再显示倒计时）。客户端不得用自己的时钟推算：本字段与 serverNow 一起下发，倒计时用两者之差，这样校时误差不会让倒计时跳变 */
  nextPointAt: number | null
  /** 今日已购买次数。下发它是为了让客户端能显示「今日还剩 N 次」，但判定仍然只在服务端做（铁律 2） */
  boughtToday: number
  /** 下一次购买所需金币。已达每日上限时为 0，客户端据此把按钮置灰 —— 置灰而不是隐藏：体力是付费点，让玩家看见「明天还能买」比让它消失更有价值 */
  buyCostGold: number
  serverNow: number
}

/**
 * POST /stamina/buy 请求体。用金币买体力（B09 §5 的付费点；直购礼包属 B15）。
 */
export interface StaminaBuyReq {
  /** 幂等键。买体力要扣金币，没有幂等就等于允许重放请求刷体力 */
  requestId: string
  /** 购买几次；null 表示 1 次。合并成一次请求是为了让「连买 5 次」只扣一次锁、只写一次存档，而不是五次读改写打架 */
  times: number | null
}

/**
 * 头像框（B24 块③ 外观）的一行。**只有展示字段**：外观不参与任何数值判定（B15 §一 第 7 条 + 公理一），所以这里既没有战力也没有属性，客户端按 placeholderColor 画占位框。
 */
export interface AvatarFrameView {
  /** avatar_frame 表的行 id */
  frameId: string
  /** 显示名，服务端下发（改名字不该发一次版） */
  name: string
  /** 稀有度，取值与 avatar_frame 表的 rarity 列一致（N/R/SR/SSR） */
  rarity: string
  /** 占位框的颜色（#RRGGBB）。素材到位后这一列换成贴图键，客户端的画法跟着换，判定与协议不动 */
  placeholderColor: string
  /** 是否已拥有。**拥有是永久事实**：卸下之后它仍然是 true */
  owned: boolean
  /** 是否正戴着。**佩戴是当下选择**：与 owned 是两位，合成一位会让「卸下 = 失去」 */
  worn: boolean
}

/**
 * GET /player/frames 响应体：**全部**头像框（含没拥有的）。没拥有的也要下发 —— 让玩家看见有什么可拿，正是收集类外观存在的意义（与商店把等级不够的货也列出来同一条口径）。
 */
export interface AvatarFrameListResp {
  frames: AvatarFrameView[]
  serverNow: number
}

/**
 * POST /player/frame 请求体：佩戴或卸下。【frameId】为 null 表示卸下 —— 与「戴一个空框」区分开。
 */
export interface WearFrameReq {
  requestId: string
  /** 要戴上的头像框 id；null = 卸下 */
  frameId: string | null
}

/**
 * POST /player/frame 响应体：操作之后的完整框列表（客户端照它重画，不自己改本地状态）。
 */
export interface WearFrameResp {
  frames: AvatarFrameView[]
  serverNow: number
}

/**
 * 离线汇总的边界与阈值。「自上次登录以来」的起点是 previousLoginAt（**不是** profile.lastLoginAt —— 那个值在本次登录时已被推进成现在，拿它算出来永远是 0）。两个阈值来自 global 表（OFFLINE_REPORT_MIN_IDLE_MINUTES / OFFLINE_REPORT_MIN_ITEMS），客户端只按它们判定「值不值得弹」，不自己填数。
 */
export interface OfflineReportView {
  /** 上次登录时刻（服务端毫秒时间戳）。**新号是 null** —— 没有「上一次」可言，此时汇总没有起点，客户端不该弹 */
  previousLoginAt: number | null
  /** 距上次登录不足这么多分钟就不打扰（来源：global.OFFLINE_REPORT_MIN_IDLE_MINUTES） */
  minIdleMinutes: number
  /** 至少要有这么多条可汇总的明细才值得弹（来源：global.OFFLINE_REPORT_MIN_ITEMS） */
  minItems: number
}

/**
 * POST /stamina/buy 响应体。
 */
export interface StaminaBuyResp {
  stamina: StaminaResp
  /** 实际到账的体力。<b>可能小于请求量</b>：超出上限的部分永久损失，而金币照扣 —— 所以客户端必须在购买前用 StaminaResp.cap 提示玩家「继续购买会溢出」。服务端不替玩家做这个判断，但服务端会在响应里照实说给了多少，绝不静默吞掉 */
  granted: number
  /** 本次实际扣除的金币（多次购买时是递增单价之和） */
  costGold: number
  /** 今日累计购买次数 */
  boughtToday: number
}
