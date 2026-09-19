/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 当前规则阶段。取值与 game-core 的 SeasonTimeline.Phase 逐一对应。
 *
 * 注意它是**规则阶段**而不是**目标阶段**：season 表里的五段（开垦/立盟/争锋/问鼎/结算）是目标阶段，而这里的 PREPARE/EXPAND/... 决定「能不能 PVP、王城是否开放、是否结算」。立盟期与争锋期是两个目标、同一条 EXPAND 规则，所以两者不能按序号对齐 —— 映射走 season 表的 rulePhase 列。
 */
export type SeasonPhase =
  | 'PREPARE'
  | 'EXPAND'
  | 'CAPITAL_WAR'
  | 'SETTLE'
  | 'REST'

/**
 * 赛季段位（B14 §2）。取值与 game-core 的 {@code SeasonTier.Tier} 逐一对应，名字与<b>顺序</b>都由 {@code NationPayEnumParityTest} 断言 —— 段位是玩家互相报的称呼，两侧名字一旦漂移，面板就会把王者显示成未知；而顺序漂移更阴，因为「哪一档更高」是按 ordinal 比的。
 *
 * <b>按 matchPower 而不是 displayPower 划分</b>：后者含峰值记忆与全部已拥有武将，用它分段的后果是「卸兵压分反而掉段」，把 B08 明确要堵的行为变成了收益。
 */
export type SeasonTier =
  | 'BRONZE'
  | 'SILVER'
  | 'GOLD'
  | 'PLATINUM'
  | 'DIAMOND'
  | 'KING'

/**
 * 荣耀三件套（B14 §4：赛季数据不进主表，只有这三项抄回主存档）。
 *
 * <b>真相在赛季账本，不在这里</b>：这三项是逐季点查账本派生出来的（{@code SeasonLedgerStore#gloryOf}），主存档里那一份只是给面板读的<b>派生缓存</b> —— 缓存与账本不一致时以账本为准并就地修一次。所以这个对象<b>不代表任何独立于账本的事实</b>，客户端也不该把它当写回来的入口。
 *
 * <b>徽章按「每季一枚」记</b>，因此集合等于「我参与并已结算的赛季 id」；赛季数是归档保留数（个位数），逐季点查的代价可以接受。
 */
export interface SeasonGloryView {
  /** 荣耀等级 = 参与过并结算过的赛季数。0 是合法值（本赛季还没结算），与「从没进过赛季」同义 —— 结算账本里没有记录时它就是 0。 */
  gloryLevel: number
  /** 历史最高段位（各季里最好的一次，不是本赛季的）。从未结算过的人回 BRONZE 而不是 null：「没打过」与「打过但最低档」在面板上要长成同一个入口，缺一个非空值会让客户端到处判 null。 */
  highestTier: SeasonTier
  /** 赛季徽章，每季一枚（= 参与并已结算的赛季 id）。刻意不下发「徽章外观」那类字段：本版本没有徽章美术表，编一个外观 id 就是给下一个批次埋一个假契约。 */
  badges: string[]
}

/**
 * GET /season/status 响应：赛季时间轴的当前状态。
 */
export interface SeasonStatusResp {
  /** 赛季 id（从 season 表行 id 的前缀反推，如 season_01）。未配置赛季锚点时为空串。归档集合名 season_<id> 由它拼出。 */
  seasonId: string
  /** 当前规则阶段；**null = 赛季未启用**（没配 SEASON_START_AT），不是「第 0 天」。这一位不在 required 里 —— 生成器按「非必填 = 可空」产出 `SeasonPhase | null`，可空性由所在位置声明而不是 $defs 里那个 type 数组（那个数组对枚举不起作用）。此前它被列进 required，于是生成出来的类型说非空而 SeasonAppService#status 会下发 null：客户端 `switch (resp.phase)` 照样编译通过，把 null 当成兜底分支。 */
  phase: SeasonPhase | null
  /** 赛季开始的服务端时刻（毫秒）。null = 未配置。客户端要它才能把自己的服务器时钟换算成「赛季第几天」，而不该自己拿本地时间算 —— 那是 B00 铁律 5 禁止的事。 */
  seasonStartAt: number | null
  /** 赛季第几天（0-based），整天数向下取整，所以阶段切换精确落在整日边界。<b>null = 赛季未启用</b>，不是「第 0 天」。 */
  dayIndex: number | null
  /** 本赛季总天数（season 表各行 durationDays 之和）。超过它就进入休赛期。 */
  totalDays: number
  /** 当前阶段结束的服务端时刻（毫秒），UI 画倒计时用，绝不由客户端自己减。休赛期指向赛季终点。null = 赛季未启用。 */
  phaseEndAt: number | null
  /** 当前是否允许玩家间攻击。**这是服务端的权威答案**，客户端只能据此把按钮置灰，真正的拦截在 AttackGuardService 里 —— 只在 UI 上禁 while 服务端放行，等于让改包的客户端照打。 */
  allowsPvp: boolean
  /** 当前是否开放中央王城（问鼎期）。 */
  allowsCapitalWar: boolean
  /** 是否只读期（休赛期：只展示荣耀，不再产生新的赛季行为）。 */
  readOnly: boolean
  /** 我的荣耀三件套。<b>需要身份</b>：没带 {@code X-Player-Id} 时为 null（与本接口的 {@code myRank} 同一条规则 —— 全服信息不该被身份门槛挡住，属于个人的东西才要身份）。可空由「不在 required 里」声明；这里曾写过一个 {@code nullable: true}，但生成器不读它，留着只会让人以为那是开关。 服务端优先读主存档里的那份派生缓存；缓存缺失或落后于账本时（重启、或结算写缓存失败过），以赛季账本为准重算并就地修一次。 */
  glory: SeasonGloryView | null
  /** 当前玩家在本赛季实时榜上的名次，1-based。三种取值的含义必须分清： - **null**：赛季未启用，或这次请求没带 `X-Player-Id`（本接口不要求身份）； - **0**：赛季在跑，但这个玩家还没上报过战力 —— 也就是「未上榜」，不是「第 0 名」； - **>=1**：实时榜上的名次。 注意这是**实时榜**而不是快照榜：面板要让玩家看到自己现在的位置，而结算只认快照（B14 禁止项），两者刻意分开。 */
  myRank: number | null
  /** 服务端时间戳，用于客户端校准时钟。 */
  serverNow: number
}

/**
 * POST /season/settle 请求体。
 *
 * **结算必须带 requestId**：它是幂等键的第一半（另一半是 seasonId+playerId，在领域层）。没有 requestId 的话，运营脚本重试一次就多结算一次，而多出来的那一次不会报错 —— 只会让玩家发现奖励数字对不上。
 */
export interface SeasonSettleReq {
  /** 幂等键。 */
  requestId: string
  /** 一页处理多少人。缺省取 global.SEASON_SETTLE_PAGE_SIZE，可以调小、不得超过它。 */
  pageSize: number | null
}

/**
 * 一次结算触发的结果。
 */
export interface SeasonSettleResp {
  /** 被结算的赛季。 */
  seasonId: string
  /** 本次新结算的人数（重复触发的部分是幂等跳过，不计入）。 */
  settledPlayers: number
  /** 本次发出的赛季币总额。 */
  distributedSeasonCoin: number
  /** 本次发出的金币总额。 */
  distributedGold: number
  /** 结算依据的快照时刻。B14 禁止项：结算只按快照 —— 按实时榜会让最后一秒刷分的人挤掉从头打到尾的人。 */
  snapshotAt: number
  /** 服务端时间戳。 */
  serverNow: number
}
