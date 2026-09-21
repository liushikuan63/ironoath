/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 距离档位（NEAR / MID / FAR）。
 *
 * **它不是信息隐藏手段，这一点必须写清楚。** TargetBrief 必然带 coord —— 不知道坐标就无法出兵，而客户端本来就知道自己的家坐标，所以精确距离它自己就能算出来。把 distanceBand 说成「防止客户端推演行军时间」是错的，写着错的理由会让下一个人在真正需要保密的地方（resourceHint）也放松警惕。
 *
 * 它的真实作用是**把分档口径收到服务端**：列表要按远近分组、要打「近/中/远」标签，如果只给坐标，每个客户端都得自己算距离再自己定分界，于是 iOS / 安卓 / 编辑器三端会给出三套分界，同一个目标在一端显示「近」、在另一端显示「中」。分界来自 SEARCH_NEAR_RATIO / SEARCH_MID_RATIO，下发的是结论而不是原料。
 *
 * B08 验收 12 的要求是「响应体中无距离数值字段，只有 distanceBand」，本字段就是那条要求的落地：距离只以档位形式出现，服务端不下发一个可以让客户端直接渲染成「3.2 小时可达」的数字。
 */
export type DistanceBand =
  | 'NEAR'
  | 'MID'
  | 'FAR'

/**
 * 目标资源富度的提示（B08 §8）。同样只给档位不给数字：给数字等于把对方的仓库状态下发出去，而那应当是侦查才能拿到的情报 —— 否则 B07 §3 的「情报有误差」就失去意义了。
 */
export type ResourceHint =
  | 'RICH'
  | 'NORMAL'
  | 'POOR'

/**
 * 暴虐档位（B08 §4）。与 game-core 的 Tyranny.Level 一致（由 PowerContractParityTest 断言）。
 */
export type TyrannyLevel =
  | 'COMMONER'
  | 'TYRANT'
  | 'BRUTE'
  | 'PUBLIC_ENEMY'

/**
 * 地图实体类型。与 game-core 的 WorldGenerator.EntityType 一致，另外多一个 MARCH —— 行军不是格子上的静态内容而是移动实体，所以生成器不产出它，只由 viewport 组装时按当前位置投影进来。BUILDING 预留给 B10 联盟建筑。
 */
export type WorldEntityType =
  | 'EMPTY'
  | 'CITY'
  | 'MONSTER'
  | 'RESOURCE'
  | 'MARCH'
  | 'BUILDING'

/**
 * 行军状态。与 game-core 的 March.Status 一致（由 WorldContractParityTest 断言）。
 */
export type MarchStatus =
  | 'MARCHING'
  | 'STATIONED'
  | 'GATHERING'
  | 'RETURNING'
  | 'FIGHTING'

/**
 * 行军目标类型，决定到达后的行为分支（B07 §2 的表格）。
 */
export type TargetType =
  | 'EMPTY'
  | 'MONSTER'
  | 'RESOURCE'
  | 'PLAYER_CITY'
  | 'ALLIANCE_BUILDING'

/**
 * 玩家选择的到达行为。与 TargetType 分开：目标是什么由地图决定，做什么由玩家决定。
 */
export type MarchAction =
  | 'ATTACK'
  | 'GATHER'
  | 'STATION'
  | 'SCOUT'
  | 'GARRISON'

/**
 * 世界坐标（格）。
 */
export interface Coord {
  x: number
  y: number
}

/**
 * 一个可攻击目标的摘要。**不含距离数值、不含精确资源量、不含 isBot**（B08 验收 12、B07 禁止项、B11 合规）。
 *
 * coord 是必须给的：不知道坐标就无法出兵。所以「不下发精确距离」在这里不是保密，而是不给客户端一个可以直接渲染成倒计时数字的原料，理由见 DistanceBand。
 * resourceHint 才是真正的情报保护 —— 精确库存无法从响应里的任何其它字段推导出来，它只能靠侦查获得，给数字就等于把 B07 §3 的「情报有误差」删掉。
 *
 * tyrannyLevel 高于 COMMONER 时表示攻击他可以拿到围剿加成 —— 这是「给弱者制造反击靶子」在客户端的入口。
 */
export interface TargetBrief {
  id: string
  name: string
  coord: Coord
  /** 目标的匹配战力（不是展示战力）。圈层校验用的是它 */
  matchPower: number
  /** 相对自己的倍率（定点 ×10000）。下发倍率而不是只下发对方战力，是因为玩家真正要判断的是「我打得过吗」，而那个判断需要知道自己的匹配战力 —— 让他自己做除法等于把口径交给客户端 */
  powerRatio: number
  distanceBand: DistanceBand
  resourceHint: ResourceHint
  /** 是否处于护盾。护盾目标不进候选池（B08 §8），但已下发的目标要能被标出来 */
  isShielded: boolean
  /** 暴虐档位；平民或无记录时为 null（不下发 COMMONER，因为那一档没有任何效果，下发只会让 UI 多一个无意义的标签） */
  tyrannyLevel: string | null
}

/**
 * POST /world/searchTargets 请求体（B08 §8）。
 */
export interface SearchTargetsReq {
  /** 搜索半径（格）；null 表示用服务端的 SEARCH_DEFAULT_RADIUS —— 与 maxCount 同一条口径：客户端在第一次响应之前不知道上下界，不许它自己猜一个数。超过 SEARCH_MAX_RADIUS 会被截断到上限并记录日志 —— 不拒绝，因为玩家拖动滑块时很容易越界，拒绝会让他以为搜索坏了 */
  radius: number | null
  /** 最多返回几个；null 表示用服务端默认值 */
  maxCount: number | null
}

/**
 * POST /world/searchTargets 响应体。targets 已按 B08 §8 的四项权重排好序。**targets 里没有任何逐目标的距离数值字段**（B08 验收 12，只给 NEAR/MID/FAR 三档）。顶层那三个 radius* 不是距离读数，而是搜索参数本身的上下界与默认值：判定仍然只在服务端做，客户端拿它们只为了把 ± 键摆到正确的范围上。
 */
export interface SearchTargetsResp {
  targets: TargetBrief[]
  /** 自己的匹配战力（含峰值记忆）。下发它是为了让客户端能解释「为什么这些目标可选」—— 但判定仍然只在服务端做（B08 禁止项：不要在客户端做战力校验） */
  selfMatchPower: number
  bandLower: number
  bandUpper: number
  /** 半径下界：服务端把请求夹成的地板值。下发它而不是让客户端写死 1，是为了让 ± 键能按到的最小值与真正的截断口径同源（客户端猜的数会随服务端改夹取规则而说谎） */
  radiusMin: number
  /** 首次搜索的半径（SEARCH_DEFAULT_RADIUS）。客户端的 ± 键在收到这份响应之前连起点都不知道，两颗键于是静默 no-op —— 半径这个玩家参数必须是下发的，铁律 1 不许场景里写死 */
  radiusDefault: number
  /** 半径上界（SEARCH_MAX_RADIUS）。超过它服务端只截断并记日志、不拒绝，所以下发上来客户端才不会把「已经到顶」演成「按了没反应」 */
  radiusMax: number
  serverNow: number
}

/**
 * 地图实体的精简结构 —— 控制单次 payload ≤ 20KB（B07 验收 5）。<b>刻意不包含 isBot</b>：B07 禁止项与 B11 合规都明写「前 7 天不做任何 Bot 标识」，一旦这个字段进了协议，客户端就可能拿它做差异化展示，而监管会认定为「人机混排未告知」。字段不存在比字段值为 false 更安全 —— 不存在的字段无法被误用。
 */
export interface WorldEntity {
  /** 实体 id：玩家城 = playerId，野怪 = 坐标派生的稳定 id，资源点同 */
  id: string
  type: WorldEntityType
  x: number
  y: number
  /** 野怪等级 / 主城等级；空地与资源点为 null */
  level: number | null
  /** 玩家城的拥有者昵称；野怪与资源点为 null */
  ownerName: string | null
  /** 联盟标签（B10 落地前恒为 null，字段先留位以免届时改协议） */
  allianceTag: string | null
  marchStatus: MarchStatus | null
  /** 资源点产出的资源 id；其余为 null */
  resourceType: string | null
  /** 行军实体的当前负载 */
  load: number | null
}

/**
 * 一个 chunk 的完整内容。<b>版本号是增量下发的核心</b>：客户端上报手里的版本，服务端只回版本更高的块（B07 验收 6）。
 */
export interface ChunkData {
  /** chunk 键，格式 "cx:cy" */
  key: string
  version: number
  entities: WorldEntity[]
  /** 本块因 payload 上限被截断（实体没发完）。<b>必须下发</b>：客户端据此立刻再请求一次，否则玩家会看到一个「明明有怪却显示为空地」的地图，而他没有任何线索知道为什么 */
  truncated: boolean | null
}

/**
 * GET/POST /world/viewport 请求体。chunkVersions 是客户端手里各块的版本，服务端只回更新的那些。
 */
export interface ViewportReq {
  centerX: number
  centerY: number
  /** 缩放档位（B07 §1：0=世界 / 1=区域 / 2=城市）。缩到 2 时客户端应切换到城内场景 */
  zoom: number
  /** 客户端缓存的各块版本。首次请求传空数组 */
  chunkVersions: ChunkVersion[] | null
}

/**
 * 客户端持有的某个 chunk 的版本号。
 */
export interface ChunkVersion {
  key: string
  version: number
}

/**
 * POST /world/viewport 响应体。chunks 只含版本变化的块；staleChunks 列出客户端缓存已过期的块键（含因 payload 上限被截断而需要再取的块）。
 */
export interface ViewportResp {
  serverNow: number
  chunks: ChunkData[]
  staleChunks: string[]
  /** 本次视口内已探索（无迷雾）的块。不在列表里的块客户端要盖黑色遮罩（B07 §3） */
  exploredChunks: string[]
  /** 本次视口内仍是迷雾的块。<b>这些块的 entities 一律不下发</b> —— 下发了就等于把迷雾做成了纯客户端遮罩，改一下客户端就能透视全图 */
  fogChunks: string[]
}

/**
 * 行军携带的一个兵种。用 unit 表的行 id（含阶级），不用兵种类型 —— 战斗结算需要知道具体是 T几。
 */
export interface MarchUnit {
  unitId: string
  count: number
}

/**
 * POST /world/march 请求体。
 */
export interface MarchReq {
  requestId: string
  toX: number
  toY: number
  units: MarchUnit[]
  /** 随军武将 id，按主将→副将顺序；不传表示不带武将 */
  heroes: string[] | null
  action: MarchAction
}

/**
 * POST /world/march 响应体。durationSec 与 arriveAt 都由服务端算，客户端不参与（B07 禁止项：不要用客户端定时器决定到达）。
 */
export interface MarchResp {
  march: MarchView
  /** 曼哈顿距离（格）。下发它是为了让客户端能显示「距离 37 格」而不必自己算 —— 距离口径必须与服务端一致 */
  distance: number
  durationSec: number
  serverNow: number
}

/**
 * 一支行军的完整视图。<b>position 由服务端按真实时间插值算出</b>：客户端自己的 (now-startAt)/(arriveAt-startAt) 插值只是表现层平滑，权威位置以这里为准，中途收到推送立即纠偏（B07 验收 10）。
 */
export interface MarchView {
  marchId: string
  from: Coord
  to: Coord
  status: MarchStatus
  targetType: TargetType
  targetId: string | null
  /** 所属集结 id；普通行军为 null。 **为什么必须由服务端下发而不是让客户端去猜**：集结合并行军的主人是发起人，在成员眼里看到的就是「我自己的兵不见了」——他必须能在行军列表里认出「我的兵在那支集结队伍里」，否则每一次参与集结都会变成一条客服工单。而 March 与 Rally 的对应关系只有服务端知道（集结在社交域、行军在行军域），让客户端拿 rallyId 去 /rally/list 里比对，等于要求它自己做一次跨域 join。 刻意做成可空而不是必填：绝大多数行军与集结无关，为它们多带一个字段是噪声。 */
  rallyId: string | null
  action: MarchAction
  startAt: number
  arriveAt: number
  /** 返程开始时刻。<b>客户端插值返程时必须用它而不是 startAt</b>，否则召回瞬间位置会跳变 */
  returnStartAt: number | null
  returnArriveAt: number | null
  units: MarchUnit[]
  heroes: string[]
  load: number
  loadCap: number
  /** 队伍速度 = 最慢兵种的速度。下发是为了让玩家看懂「为什么带了攻城器就这么慢」 */
  teamSpeed: number
  position: Coord
  /** 行程进度（定点 0~10000） */
  progressFixed: number
  /** 采集完成（采满负载）的时刻；非采集状态为 null */
  gatherFinishAt: number | null
  serverNow: number
}

/**
 * 只带 marchId 的请求（召回、领取采集、查看单支行军）。
 */
export interface MarchIdReq {
  requestId: string
  marchId: string
}

/**
 * POST /world/recall 响应体。返回耗时 = 已行军距离 / 速度，兵力零损失（B07 验收 7）。
 */
export interface RecallResp {
  march: MarchView
  returnArriveAt: number
  /** 返程耗时（秒）。客户端要显示「X 分钟后到家」 */
  returnSeconds: number
  serverNow: number
}

/**
 * POST /world/collectGather 响应体。结束采集并让队伍返程，collected 是本次采到的资源。
 */
export interface GatherResp {
  /** 采到的资源。受负载上限约束（loadCap = Σ兵数 × 该兵 load） */
  collected: CollectedEntry[]
  returnArriveAt: number
  march: MarchView
  serverNow: number
}

/**
 * 一条采集所得。
 */
export interface CollectedEntry {
  resourceType: string
  amount: number
}

/**
 * POST /world/scout 请求体。侦查走的是行军系统（action=SCOUT），所以也需要派兵。
 */
export interface ScoutReq {
  requestId: string
  toX: number
  toY: number
  units: MarchUnit[]
}

/**
 * 情报里的一项观测值（含误差）。
 */
export interface ScoutMetric {
  /** 指标名：power / totalUnits / infantry / cavalry / archer / siege / wood / stone / iron / grain / gold */
  name: string
  value: number
}

/**
 * 一份敌情报告。<b>必须带 errorFixed 与情报时间</b>：不带误差幅度，玩家会把带误差的数字当精确值来做决策，那比没有情报更糟；不带时间，玩家无法判断这份情报还能不能用（B07 验收 9：超时置灰）。
 */
export interface ScoutReportView {
  reportId: string
  target: Coord
  targetId: string | null
  /** 目标等级。<b>这是报告里唯一无误差的字段</b> —— 等级从外观就能看出来，给它加误差只会让玩家觉得系统在耍他 */
  targetLevel: number
  createdAt: number
  expiresAt: number
  /** 是否已过期。true 时 UI 必须置灰且不可用于决策 */
  expired: boolean
  remainingMs: number
  /** 误差幅度（定点）。UI 显示成「±12%」 */
  errorFixed: number
  metrics: ScoutMetric[]
  /** 误差种子。下发是为了可复现（客服核查「这份情报为什么这么离谱」），不是为了让客户端重算 */
  seed: number | null
  serverNow: number
}

/**
 * GET /world/reports 响应体。已过期的报告也会返回（带 expired=true），因为「我曾经侦查过这里」本身是有用的信息。
 */
export interface ScoutListResp {
  reports: ScoutReportView[]
  serverNow: number
}

/**
 * GET /world/marches 响应体：我的全部行军 + 家坐标。也带三个地图布局参数（worldSize / chunkSize / maxChunks）——**为什么放在这个响应而不是 ViewportResp**：客户端要用它们建立地图模型，而第一个 viewport 请求必须等模型建立（要算视野中心块与 chunk 键），放在 viewport 响应里就是鸡生蛋；marches 是「进入世界」时建模之前唯一必拉的响应（家坐标也在它里面），所以布局参数和 home 一起下发。
 */
export interface MarchListResp {
  marches: MarchView[]
  home: Coord
  /** 同时出征上限，来源 global.MARCH_MAX_CONCURRENT。下发是为了让客户端能显示「2/3」而不是自己读配置 */
  maxConcurrent: number
  /** 自愿停战（免战）到期时刻，null 表示当前没有。三个来源都写同一本账（免战牌道具、闭城死守、流亡迁城），所以这里只有一个字段。下发是为了让地图上的「免战中」标记与真实判定同源 */
  peaceUntil: number | null
  /** 下一次可以流亡迁城的时刻，null 表示随时可以。必须由服务端给：客户端本地缓存上一次迁城时刻的话，重装或换设备就会看到一个「可以迁」而服务端回 6011 —— 冷却是滚动窗口，两端各算一份必然漂 */
  nextExileAt: number | null
  /** 世界边长（格）。来源 global.WORLD_SIZE。下发是为了让客户端的地图模型不再把 global.json 的值镜像成常数（铁律 1：一个数字只能有一个家） */
  worldSize: number
  /** 地图分块边长（格），必须是正的 2 的幂。来源 global.WORLD_CHUNK_SIZE。与服务端 Coord.chunkKey 的位移约束同一份 */
  chunkSize: number
  /** 客户端同时持有的块数上限，必须是完全平方数（3×3=9，视野没有中心块就没法增量下发）。来源 global.VIEWPORT_CHUNK_COUNT。下发是为了让客户端能算视野块数与缓存上界，而不是自己读配置 */
  maxChunks: number
  serverNow: number
}

/**
 * POST /world/exile 请求体。B08 §5 的流亡迁城：免费随机落点 + 落地后一段时间免战，且有冷却。玩家不能指定落点（能指定就等于可以精准迁到仇人隔壁或资源点正上方），所以除了幂等键什么都没有。
 */
export interface ExileReq {
  requestId: string
}

/**
 * POST /world/exile 响应体。
 */
export interface ExileResp {
  /** 新的城坐标。客户端必须以此为圆心重拉视野 */
  coord: Coord
  /** 免战到期时刻（服务端毫秒）。走的是自愿停战那本账（Protection.Kind.PEACE），双向生效：既不被打也不能打人 */
  peaceUntil: number
  /** 下一次可以再次流亡的时刻。下发而不是让客户端按冷却自己算，是因为两处算法一旦漂移，玩家看到的「还能不能迁」就会和实际拒绝结果不一致 */
  nextExileAt: number
  /** 落点由这个服务端种子推导（铁律 4：随机必须可复现）。客服接到「迁到了一个鸟不拉屎的地方」的申诉时，凭 seed + 探测次数能还原当时为什么只能落在那一格 */
  seed: number
  serverNow: number
}
