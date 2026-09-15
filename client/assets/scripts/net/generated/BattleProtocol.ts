/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 兵种类型。取值必须与 unit 表的 type 列、game-battle 的 UnitType 枚举一致（由 BattleContractParityTest 断言）。
 */
export type UnitType =
  | 'INFANTRY'
  | 'CAVALRY'
  | 'ARCHER'
  | 'SIEGE'

/**
 * 战斗结果方。与 game-battle 的 Winner 枚举一致。
 */
export type BattleSide =
  | 'ATTACKER'
  | 'DEFENDER'
  | 'DRAW'

/**
 * 战斗类型，决定死伤比例（B05 §1.5）。与 game-battle 的 BattleType 枚举一致。
 */
export type BattleType =
  | 'PVE'
  | 'PVP_SOLO'
  | 'PVP_RALLY'
  | 'SIEGE'

/**
 * 技能触发时机。与 game-battle 的 SkillPhase 枚举一致。
 */
export type SkillPhase =
  | 'ROUND_START'
  | 'EVERY_ROUND'
  | 'ON_HIT'
  | 'ON_DEATH'

/**
 * 一个兵种堆叠。<b>客户端渲染的就是这个，不是单个士兵</b>：B05 §三 要求「每排最多渲染 12 个单位，超出用 ×N 图标聚合」，而服务端下发的本来就是按兵种聚合的数量，所以 10 万兵力也只有 4 个堆叠。这是低端机保 60 帧的关键，客户端绝不要试图把它展开成单个单位。
 */
export interface UnitStack {
  unitType: UnitType
  count: number
}

/**
 * 一次技能触发，供战报 UI 展示与播放技能特效。
 */
export interface SkillTriggerView {
  phase: SkillPhase
  skillId: string
  /** 中文名，来自 skill 表，客户端不得自行翻译 */
  skillName: string | null
  /** 由哪一方触发 */
  side: string
  /** 触发的武将；null 表示非武将来源 */
  heroId: string | null
  /** 效果值（定点 ×10000） */
  valueFixed: number | null
}

/**
 * 一回合的完整快照。客户端播一回合就是播这一个对象，不需要任何计算。
 */
export interface RoundView {
  round: number
  /** 回合开始时攻方的兵力构成 */
  attackerUnits: UnitStack[]
  defenderUnits: UnitStack[]
  attackerLoss: number
  defenderLoss: number
  /** 攻方总攻击（已含全部乘区），供战报展开「这一回合为什么打这么多」 */
  attackerAttack: number
  defenderDefense: number
  /** 减员系数（定点）。B05 §1.4 的核心中间量，公示它才能让战报可解释 */
  attritionFixed: number
  skills: SkillTriggerView[]
}

/**
 * 一场战斗的完整结果。<b>这就是战报</b>：存下来即可无限次重播，重播不需要重算（B05 §三）。seed 一并下发用于服务端自查、问题复现与反外挂校验，客户端不参与任何基于 seed 的计算。
 */
export interface BattleResultView {
  winner: BattleSide
  battleType: BattleType
  totalRounds: number
  rounds: RoundView[]
  attackerSurvivors: UnitStack[]
  defenderSurvivors: UnitStack[]
  attackerDead: number
  attackerWounded: number
  /** 因医院超容量而死亡的伤兵。<b>必须单独下发</b>：B05 验收 7 要求「医院溢出：死亡数量与 UI 提示完全吻合」，如果把它并进 dead 里，玩家就看不到「有 300 个是因为医院不够而死」，也就没有升级医院的动机 */
  attackerOverflowDead: number
  defenderDead: number
  defenderWounded: number
  defenderOverflowDead: number
  /** 掠夺所得。按 resource 表顺序 */
  loot: LootEntry[]
  /** 本次负重上限（由兵种 load 决定）。UI 要显示「装满/未装满」 */
  lootCapacity: number
  seed: number
  serverNow: number
}

/**
 * 一条掠夺所得。
 */
export interface LootEntry {
  /** 资源类型，取值同 resource 表的 id */
  resourceType: string
  amount: number
}

/**
 * 战报列表里的一条摘要。**不含 rounds** —— 一场 8 回合的战斗逐回合展开有几十个数字，列表页只要「打赢了没有、损失多少、什么时候打的」。把 rounds 塞进列表会让一次「看看最近的战报」变成几百 KB 的下发，而 B07 为地图视野定的 20KB 上限就是为了让弱网玩家不被一次响应卡住，战报列表没有理由例外。
 */
export interface BattleReportBrief {
  reportId: string
  battleType: BattleType
  /** 对手 id。打野时是 mapmonster 的行 id，PVP 时是对方玩家 id */
  opponentId: string | null
  /** 对手显示名，服务端下发。客户端不得自行翻译或拼接：「LV12 野蛮人营地」这种名字是策划在表里写的，客户端自己拼就会与服务端日志、客服工单里的称呼对不上 */
  opponentName: string | null
  winner: BattleSide
  /** 我方是否获胜。冗余于 winner，但列表页的「胜/败」标签不该让客户端自己去判断「winner==ATTACKER 且我是攻方」—— 那个判断需要知道自己是哪一方，而列表项里没有这个信息 */
  won: boolean
  totalRounds: number
  attackerLoss: number
  defenderLoss: number
  createdAt: number
  /** 过期时刻。过期的战报会被清理（惰性，不跑定时器），下发这个字段是为了让客户端能置灰「即将失效」的条目，而不是让玩家点开一条已经被清掉的战报再收到一个错误 */
  expiresAt: number
}

/**
 * GET /battle/reports 的响应：我的战报列表，按时间倒序（最新的在前）。
 */
export interface BattleReportListResp {
  reports: BattleReportBrief[]
  serverNow: number
}

/**
 * GET /battle/report 的响应：一场战斗的完整回放数据。
 *
 * **rounds 必须逐回合完整下发**：B05 交付的客户端 BattlePlayback 就是按这个结构做时间轴的（1x/2x/跳过），少一个字段回放就会跳帧。而战报只存 seed + 输入就能由服务端 100% 复算（铁律 4），所以下发完整 rounds 不是「把计算结果泄露给客户端」——客户端本来就可以自己重放，服务端下发只是省掉它一次重算。
 *
 * **刻意不下发双方的完整属性**：RoundView 里只有每回合的兵力、损失、有效攻击/防御与减员系数，没有对方的兵种属性表与武将明细。回放需要的是「发生了什么」，不是「对方有多强」—— 后者是侦查的职责（B07 §3），白送会让情报系统失去意义。
 */
export interface BattleReportResp {
  reportId: string
  result: BattleResultView
  createdAt: number
  expiresAt: number
  serverNow: number
  /** 这一场回放该怎么演。**放在详情而不是列表**：列表每次进面板都拉，而时长参数只在真正放一场时用得上。 */
  playback: BattlePlaybackParams
}

/**
 * 回放的时间参数。**为什么由服务端下发而不是客户端写死**：这两个数住在 `global` 表里
 * （`BATTLE_ROUND_DISPLAY_MS` / `BATTLE_PLAYBACK_SPEEDS`），客户端再写一份就是同一个事实两个家 ——
 * 表现层参数也是参数：调一次「一回合演多久」不该发版，与埋点攒批策略随版本检查下发是同一条理由。
 * **只下发表里真有的那两条**：开场/结算/技能三段时长表里没有，客户端按「与回合时长同量级」推导
 * （`BattleReportPanel.playbackOptionsOf`），在这里再造三个数就是发明。
 */
export interface BattlePlaybackParams {
  /** 一回合演多少毫秒。来源 `global.BATTLE_ROUND_DISPLAY_MS`。 */
  roundMs: number
  /** 允许的倍速档位，原样下发表里的字符串（形如 "1,2"）。解析在客户端 `BattlePlayback.parseSpeeds`，**只有一处解析**：服务端解析完再拼成数组会把规则复制两份。 */
  speeds: string
}
