/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 任务类型，决定重置周期（不决定奖励，奖励在 quest 表里）。ACHIEVEMENT（B17 §4 成就）永不重置、跨赛季保留 —— 成就不另开一套框架，它就是任务表里的一种 questType。取值与 game-core 的 QuestProgress.QuestType 逐一对应，由 NationPayEnumParityTest 一类的枚举守卫钉住。
 */
export type QuestType =
  | 'MAIN'
  | 'SIDE'
  | 'DAILY'
  | 'WEEKLY'
  | 'ACHIEVEMENT'

/**
 * 任务目标类型（B12 §1 的统一 GoalType）。19 个取值分成累加型与状态型两类 —— 前者进度只增不减（累计训练 20 个兵），后者进度是当前状态、可升可降（当前持有 10000 粮）。分类在服务端的 GoalType 枚举上，由 CI 的枚举一致性守卫保证两边同名同序。LOGIN_DAY / ALLIANCE_DONATE / PVP_WIN 三项是 B17 活动系统补的累加型目标，任务表暂未使用。 PVE_WIN为新增实际PvE胜利累加目标，关卡和野怪只有胜利结算才发布稳定事实。 EXPLORE_CHUNK仅接受已实际合法到达的不同地图块稳定事实，不从派遣预亮雾区或出生点亮推断。 SOLO_MONSTER_WIN只接受真实独立野怪胜利，目标是结算快照等级1至50，集结不计。
 */
export type GoalType =
  | 'UPGRADE_BUILDING'
  | 'REACH_RESOURCE'
  | 'TRAIN_UNIT'
  | 'KILL_MONSTER'
  | 'GACHA_PULL'
  | 'JOIN_SQUAD'
  | 'JOIN_ALLIANCE'
  | 'RESEARCH_TECH'
  | 'CLEAR_CHAPTER'
  | 'CLEAR_STAGE'
  | 'GATHER_RESOURCE'
  | 'HELP_SQUAD'
  | 'JOIN_RALLY'
  | 'LOGIN_DAY'
  | 'ALLIANCE_DONATE'
  | 'PVP_WIN'
  | 'PVE_WIN'
  | 'EXPLORE_CHUNK'
  | 'SOLO_MONSTER_WIN'

/**
 * 一条任务的当前视图。**进度由事件推动累加**（B12 禁止项：任务进度不得轮询），所以这里的 current 是账本里的值，不是每次读的时候扫出来的。
 */
export interface QuestView {
  /** quest 表的行 id。领取奖励时原样回传。 */
  questId: string
  /** 任务名（表里的 name）。客户端不得自行翻译或拼接进度文案。 */
  name: string
  type: QuestType
  goalType: GoalType
  /** 目标细分（建筑 id / 资源 id / 兵种 id / 野怪 id / 卡池 id）。null = 不限定（任意建筑升级都算）。 SOLO_MONSTER_WIN按等级数字字符串1至50匹配，不是野怪配置id。 */
  goalTarget: string | null
  /** 目标值。恒 >= 1：为 0 的任务一创建就是完成态（领域层在构造期就拒绝）。 */
  goalValue: number
  /** 当前进度。累加型只增不减；状态型是最近一次事件的快照值，可以回落（花掉粮食就退回去）。 */
  current: number
  /** current >= goalValue。**它与 claimable 不同**：完成但没领、完成且领过、以及前置没做完，是三种不同状态。 */
  complete: boolean
  /** 奖励是否已领。重复领取会被服务端拒（幂等），所以这个字段是客户端按钮置灰的唯一依据。 */
  claimed: boolean
  /** 此刻能不能领：complete 且未 claimed 且前置已完成。客户端不要自己算这个布尔（前置链一变就会漂）。 */
  claimable: boolean
  /** 前置任务未完成 ⇒ 还不能做。与 claimed 分开是为了让客户端知道该提示「先完成前置」还是「已领取」。 */
  locked: boolean
  /** 前置任务 id，null 表示无前置。下发它是因为客户端要能画出任务链（「完成 X 后解锁」）。 */
  preQuestId: string | null
  /** 本条任务的可选武将列表（含名字）；空数组表示这条任务的奖励里没有「挑一名」这一项。客户端据此在领奖前弹出选择界面，选择结果随 claim 的 heroChoice 提交。 */
  heroChoices: HeroChoice[]
  /** 可选奖励预览；永久金币取保存的资格额度，普通任务保持空列表。旧回应缺少该字段时沿用原展示。 */
  rewards: QuestReward[] | null
  /** 首次独立野怪胜利的服务端Unix毫秒；未获胜或可靠时间未知时为空。旧响应可缺省；不得用领取时间或客户端时间替代。 */
  firstVictoryAt: number | null
}

/**
 * GET /quest/list 的响应。**读这个端点会顺手做一次日切/周切**（每日/每周任务跨期清零），所以它是惰性推进点而不是纯读 —— 服务端不跑定时器（B00 铁律），跨期清零必须挂在有人读的那一刻。
 */
export interface QuestListResp {
  /** 全部任务（含未解锁与已领取的行）。列表由服务端按章节/类型稳定排序，客户端不需要再排。 */
  quests: QuestView[]
  /** 此刻可领的任务数。给红点/徽标用 —— 与 quests 里 claimable=true 的行数同源（同一次遍历算出来的两个值，不允许各算一遍）。 */
  claimableCount: number
  /** 服务端时刻。 */
  serverNow: number
}

/**
 * POST /quest/claim 请求体：领取一条已完成任务的奖励（B12 §1「奖励走 B04 的 grantReward」）。 heroChoice 是「三选一」那类奖励的选择结果，一并发在这里而不是单开端点的理由：领奖本来就是一次性操作（幂等键 + 已领标记都在这一条路径上），把选择挂上去就不再需要第二份「是否已经选过」的状态。
 */
export interface QuestClaimReq {
  /** 幂等键。重放一次领取不该再发一份奖励 —— 而「领了没到账」的玩家会自然地再点一次，所以这个键是常规路径而不是边缘情况。 */
  requestId: string
  /** 要领取的任务 id。未完成 / 已领取 / 前置未完成都会被拒（三种各有各的业务码）。 */
  questId: string
  /** 从候选武将里挑的那一个（B06 §1「主线赠送：首日必得 1 名 SR」）。只有带候选列表的任务需要它：不传时若该任务有候选，服务端拒绝并回可用候选（而不是替玩家默认挑一个 —— 那会让「三选一」变成「系统选中一个」）。没有候选的任务传了它也会被拒，理由同上：多传的东西静默忽略会让客户端以为自己选上了。 */
  heroChoice: string | null
}

/**
 * 一条已发放的任务奖励。与 bag 协议的 RewardItemView、stage 协议的 StageReward 形状相同，但生成器只支持同文件 $ref，所以这里各有一份。**三份的字段与枚举取值必须一致**，由 StageContractParityTest 一族的断言钉住 —— 复制而不校验才是真正的危险：漂移的症状是服务端下发的字符串在客户端解析成 undefined，而 TS 侧不会报错，UI 只会空白。
 */
export interface QuestReward {
  /** 奖励类型，取值与 bag 协议的 RewardType 一致（CI 校验）。任务表目前产出 RESOURCE / HERO_FRAGMENT / HERO 三种（HERO 是整卡武将，首日主线赠送用）。 */
  type: string
  /** 资源 id（WOOD/IRON/GRAIN/GOLD）、道具 id、或武将 id（HERO 直接给这名武将；HERO_FRAGMENT 由发放器换算成按稀有度的碎片道具）。 */
  id: string
  count: number
  /** 服务端从配置表解析后的展示名，客户端不得自行翻译。 */
  name: string
}

/**
 * POST /quest/claim 的响应。**回实际发出的奖励明细**而不是只回 ok：客户端要弹「获得 X×N」，而那份明细必须来自发放器真正发出去的东西（装不下转邮件的那部分也在里面），不是客户端自己按表猜的。
 */
export interface QuestClaimResp {
  /** 领的哪一条任务。 */
  questId: string
  /** 本次实际发放的奖励明细（已按 type+id 聚合）。 */
  rewards: QuestReward[]
  /** 领取之后还剩几条可领。与 list 的同一字段同源，省掉客户端再查一次列表。 */
  claimableCount: number
  /** 服务端时刻。 */
  serverNow: number
  /** 可选服务端到账说明，永久金币区分实际入余额和转邮箱；旧回应缺少时沿用原奖励明细。 */
  message: string | null
}

/**
 * 三选一里的一个候选。**id 与 name 成对下发**：不给两条平行数组（ids 与 names）是因为它们迟早会不同长，而不同长的表现是「点了第三个选项却领到第一个武将」，全链路不报错。
 * 名称、基础三维和技能说明由服务端解析真实 hero/skill 配置，客户端不查表、不推导强度。新增字段可选且可空，兼容只有 id/name 的旧响应；选择与领取仍只回传 heroId。
 */
export interface HeroChoice {
  /** hero 表的行 id。选它之后随 claim 的 heroChoice 回传。 */
  heroId: string
  /** 武将名（hero 表的 name）；缺名字安全回退未知武将，不以内部 id 展示。 */
  name: string
  /** 真实 hero.rarity 的既有 N/R/SR/SSR 档记号；缺配置为空，不发明新档名。 */
  rarity: string | null
  /** hero.might 基础武力原值，不含等级、装备或编队加成；缺配置为空。 */
  might: number | null
  /** hero.command 基础统率原值；缺配置为空。 */
  command: number | null
  /** hero.wisdom 基础智力原值；缺配置为空。 */
  wisdom: number | null
  /** 主技能的 skill.name 中文名，缺配置显示未知技能。 */
  mainSkillName: string | null
  /** 主技能基础配置的玩家说明：实际触发时机、概率、效果、幅度与适用持续回合，不下发设计 why 或裸枚举；缺信息显示技能信息暂不可用。 */
  mainSkillDescription: string | null
  /** 副技能的 skill.name 中文名，缺配置显示未知技能。 */
  subSkillName: string | null
  /** 副技能基础配置的玩家说明，与主技能同口径。 */
  subSkillDescription: string | null
  /** 从实际主、副技能效果派生的简短特点，不使用 hero/skill why，不给武将新增定位或强度数值。 */
  featureText: string | null
}
