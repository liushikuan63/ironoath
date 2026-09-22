/**
 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
 *
 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
 */

/**
 * 集结目标的类型。取值必须与 world 协议的 TargetType 一致（SocialContractParityTest 断言）。决定集结到达后的行为分支，与 B07 §2 的行军目标类型同一套口径。
 */
export type SocialTargetType =
  | 'EMPTY'
  | 'MONSTER'
  | 'RESOURCE'
  | 'PLAYER_CITY'
  | 'ALLIANCE_BUILDING'

/**
 * 小队职位（B10 §4 权限矩阵的 squad scope）。只有两级：小队 5~10 人，再多一层职位就是官僚主义 —— C00 公理七指出小队满足的是「我和兄弟们」，熟人圈子里没有副队长。
 */
export type SquadRole =
  | 'LEADER'
  | 'MEMBER'

/**
 * 联盟职位（B10 §4 权限矩阵的 alliance scope）。四级来自 B10 §2 的「盟主/副盟主/长老/成员」。**职位到权限的映射不在这里，在 role_permission 表**（B10 禁止项：不要把权限判断硬编码在代码里）。本枚举只提供「有哪些角色」，具体能不能做某件事由配置表回答。
 */
export type AllianceRole =
  | 'LEADER'
  | 'OFFICER'
  | 'ELDER'
  | 'MEMBER'

/**
 * 聊天频道（B10 §5：世界 / 联盟 / 小队 / 私聊）。四个频道是三层社交的可见化：世界频道让陌生人能被发现，联盟与小队频道让组织内部能协同，私聊让熟人关系能维持。少任何一个都会让某一层社交失去入口。
 */
export type ChatChannel =
  | 'WORLD'
  | 'ALLIANCE'
  | 'SQUAD'
  | 'PRIVATE'

/**
 * 社交事件类型。离线补偿（B10 验收 12）与推送（验收 5）共用这一套类型 —— 推送是「实时送到」，补偿是「上线后补齐」，两者的**内容必须一致**，否则玩家会看到两种不同的通知文案描述同一件事。
 *
 * 其中 SQUAD_JOINED / ALLIANCE_JOINED / ALLIANCE_REJECTED / HELP_REQUESTED 不对应任何验收项，但缺了它们玩家就会遇到「申请交上去石沉大海」——B10 禁止项明写绝不静默失败，礼貌性通知也是这条纪律的一部分。（原先这里写的是「后四个」，按位置数的说法在追加取值之后就会误导，改成点名。）
 *
 * **NATION_LEFT / NATION_DISBANDED 是 2026-09-13 裁决补的**（收口清单 §三·补 A7）：国家侧此前一条通知都不发，而「我的国没了」是只能靠玩家自己点一下才发现的那类事实 —— GET /nation 对一个刚失去国籍的人回 13000，他从中读不出「我原来那个国呢」。注意这两个类型**不会同时发给同一个人**：最后一个成员联盟退出导致的自动亡国，走的是 NATION_LEFT 一条并把「该国随之解散」写进标题，而不是给同一件事发两条。
 *
 * **PRIVATE_MESSAGE 是 C23 补的（2026-09-14）**：私聊此前「发得出去、对方不主动拉就永远不知道」。它是**「有人找你」的信标，不携带正文** —— 正文的家是聊天频道（{@code /chat/list}）。把内容抄进事件会出现两个版本，而且事件带 3 小时 TTL、聊天带条数裁剪，两边各自消失的时间还不一样，症状就是「通知说有人找我，点开却看不到那条」。同一个发信人在未读里只留一条，连发多条不叠加。
 */
export type SocialEventType =
  | 'MEMBER_ATTACKED'
  | 'SQUAD_DISBANDED'
  | 'ALLIANCE_APPLIED'
  | 'ALLIANCE_KICKED'
  | 'SQUAD_KICKED'
  | 'RALLY_INVITED'
  | 'RALLY_DEPARTED'
  | 'ALLIANCE_TRANSFERRED'
  | 'ALLIANCE_DISBANDED'
  | 'HELP_RECEIVED'
  | 'SQUAD_JOINED'
  | 'ALLIANCE_JOINED'
  | 'ALLIANCE_REJECTED'
  | 'HELP_REQUESTED'
  | 'ALLIANCE_ROLE_SET'
  | 'NATION_LEFT'
  | 'NATION_DISBANDED'
  | 'PRIVATE_MESSAGE'

/**
 * 帮助的目标类型（B10 §2：升级 / 治疗可请求帮助）。两者共用同一个每日额度 global.HELP_DAILY_LIMIT —— 各给一份等于把加速总量翻倍，而 B10 禁止项明写「不要让小队互助与联盟帮助简单叠加」。
 */
export type HelpTargetKind =
  | 'BUILDING'
  | 'TRAINING'
  | 'TREATING'

/**
 * 集结的发起层级。三级的人数上限分别取 global.RALLY_MAX_SIZE_SQUAD(5) / _ALLIANCE(20) / _NATION(50)。国家层在 B13 落地前不会产生，枚举先留位以免届时改协议。
 */
export type RallyScope =
  | 'SQUAD'
  | 'ALLIANCE'
  | 'NATION'

/**
 * 集结状态。PREPARING 期间成员可以加入或退出，DEPARTED 之后不可更改 —— B10 验收 11 要求「倒计时结束时所有参与部队统一出发」，若出发后还能加人，就会出现「大部队已经打完了我才到」的部队白送一次行军时间。
 */
export type RallyStatus =
  | 'PREPARING'
  | 'DEPARTED'
  | 'ARRIVED'
  | 'CANCELLED'

/**
 * 举报原因（B22 §一 3）。**是枚举而不是自由文本**：留痕表要能按原因聚合（"这周辱骂举报涨了多少"），自由文本答不了这个问题；而玩家要补充的细节另有 detail 字段。CHEAT_SUSPECT 只表示"玩家怀疑"，判定归运营与反作弊，代码不替它下结论。
 */
export type ReportReason =
  | 'ABUSE'
  | 'SPAM'
  | 'CHEAT_SUSPECT'
  | 'OTHER'

/**
 * 一个随军武将位在合并行军里的状态。
 *
 * **为什么必须把落选原因也下发**：武将位按加入顺序抢，成员点完「加入」只看到自己加成功了，如果面板不说他的武将落选以及为什么，他会以为加成生效了 —— 打输之后才发现在白送一次行军。「协商谁能上」是集结的核心社交动作，而协商需要一份看得见的事实。
 */
export type RallyHeroSlotState =
  | 'SELECTED'
  | 'OVER_CAP'
  | 'DUPLICATE'

/**
 * 世界坐标（格）。与 world 协议的 Coord 形状相同，但生成器只支持同文件 $ref，所以这里各有一份。**两份的字段名与类型必须一致**，由 SocialContractParityTest 断言 —— 复制而不校验才是真正的危险：漂移的症状是服务端下发的字段在客户端解析成 undefined，TS 侧不会报错，UI 只会空白。
 */
export interface SocialCoord {
  /** 横坐标（格） */
  x: number
  /** 纵坐标（格） */
  y: number
}

/**
 * 小队成员的一条摘要（B10 §二）。power 是**展示战力**而不是匹配战力 —— 小队里看的是「兄弟练得怎么样」，不是「我能不能打他」；圈层校验用的匹配战力只在 B08 的搜索与攻击链路里出现。
 */
export interface SquadMember {
  /** 玩家 id */
  id: string
  /** 昵称。服务端下发，客户端不得自行翻译或截断 */
  name: string
  /** 展示战力 */
  power: number
  /** 最近活跃的服务端时间戳。小队只有 5~10 人，谁三天没上线一眼就该看出来 —— 这是队长决定要不要补人的唯一依据 */
  lastActiveAt: number
  /** 职位 */
  role: SquadRole
  /** 主城等级。**必须下发**：小队人数上限的第二档门槛是「队长主城 8 级」（B10 §1），客户端要能解释「为什么现在只能 5 人」 */
  mainCityLevel: number
}

/**
 * 小队视图（B10 §二）。**isSubSquad 与 allianceId 是 B10 关键设计点 1 的落地**：玩家加入联盟后小队自动转为联盟内分队，保留小队聊天、互助与集结 —— 熟人小圈子不能被大组织稀释，这是留存的关键细节。所以本视图在玩家入盟后**仍然完整下发**，一个字段都不少。
 */
export interface SquadView {
  /** 小队 id */
  id: string
  /** 小队名 */
  name: string
  /** 队长玩家 id */
  leaderId: string
  /** 成员列表，按加入时间升序（稳定顺序，客户端不重排） */
  members: SquadMember[]
  /** 小队等级。决定人数上限与商店货品（squad_config 表） */
  level: number
  /** 当前等级内已累计的活跃度 */
  exp: number
  /** 升到下一级还需多少活跃度；满级为 0 */
  expToNext: number
  /** 人数上限。**服务端算好后下发**：它同时取决于小队等级与队长主城等级（5→8→10 的第二档门槛是队长主城 8 级），让客户端自己查两张表再取小，必然会出现双端不一致 */
  memberCap: number
  /** 小队商店的货品档位。0 表示商店尚未解锁 */
  shopLevel: number
  /** 我的小队币余额（个人资产，不是小队公共资金） */
  squadCoin: number
  /** 所属联盟 id；独立小队为 null */
  allianceId: string | null
  /** 是否为联盟内分队。true 时小队功能**全部保留**（验收 1）—— 这个字段存在的意义就是让客户端能明确画出「既是分队又是小队」的双重身份，而不是把小队页签藏起来 */
  isSubSquad: boolean
  /** 今日小队任务已完成数（合计讨伐野怪数） */
  dailyQuestProgress: number
  /** 今日小队任务目标数。来源 global.SQUAD_QUEST_DAILY_MONSTER，下发是为了让客户端能显示「7/20」而不是自己读配置 */
  dailyQuestTarget: number
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * 联盟成员的一条摘要。比小队成员多了职位与贡献值 —— 联盟是 30~150 人的组织，「谁在这个组织里出了多少力」必须可见，否则盟主无从判断该提拔谁、该踢谁。
 */
export interface AllianceMember {
  /** 玩家 id */
  id: string
  /** 昵称 */
  name: string
  /** 展示战力 */
  power: number
  /** 职位 */
  role: AllianceRole
  /** 累计贡献值 */
  contribution: number
  /** 最近活跃的服务端时间戳 */
  lastActiveAt: number
  /** 该成员所属的小队 id（联盟内分队）；无小队为 null。**必须下发**：盟主集结时要能按分队点名，否则 150 人的名单就是一堆散沙 */
  squadId: string | null
  /** 该成员所属小队的名字，与 `squadId` 同步为 null 或同步为非 null。**必须下发**：客户端只有类型、没有小队表数据，拿 `squadId` 去拼只会印出「分队 squad_17」这种玩家读不懂的黑话（收口清单 #422 的成因）；小队名被改了要跟着改，那只有服务端知道 */
  squadName: string | null
}

/**
 * 一条联盟科技的当前进度（B10 §2：用联盟资金研究、全盟生效、上限随联盟等级）。
 */
export interface AllianceTechView {
  /** `alliance_tech` 表的行 id。 */
  techId: string
  /** 本盟已研究到的等级。表里 maxLevel 是绝对上限，实际还受联盟等级约束。 */
  level: number
  /** 当前联盟等级下这一项的上限。<b>必须下发</b>：它是 maxLevel × (1 + alliance_config.techCapBonus) 的结果，客户端要查两张表再乘才能算出来，而自己算门槛正是本项目反复在防的那类漂移。 */
  levelCap: number
  /** 累计效果值（定点，×10000）= 单级幅度 × 等级。<b>它只是「研究到的幅度」，不是最终生效的系数</b>：联盟科技该进哪个乘区、与个人科技/装备/编队加成是相加还是相乘，属于平衡口径（见收口清单 #31），定下来之前各消费方不擅自乘进去 —— 悄悄乘一遍的后果是「+1.5%/级 × 40 级」在某些组合下变成 +200%。 */
  effectFixed: number
  /** 科技名（`alliance_tech` 表那一行的 name）。<b>显示名一律服务端下发</b>：客户端只有类型没有表数据，自己拿 techId 翻名字就是第二真源（同族见收口清单 #255 / #281 / #303）。 */
  name: string
  /** 再研究<b>一级</b>要多少联盟资金（与服务端 `Alliance.researchCost(base, id, 1)` 同一次计算）。没有这一项，玩家只能点了之后才知道钱不够 —— 而那一枪带幂等键、扣的是全盟公共资产。 */
  nextLevelCost: number
  /** 这一项<b>此刻</b>研究得动吗：只判「没到本盟上限」与「资金够一级」两条（与写口同一句比较）。职位能不能研究不在这里判 —— 那份结论在 /social/permissions 的 RESEARCH_TECH 里，两处各说一件事，才不会互相打脸。 */
  canResearch: boolean
  /** 灰着的时候那句原因（玩家读得懂的话，不出现 id 与字段名）；能研究时为 null。 */
  reason: string | null
}

/**
 * 联盟视图。**version 是 diff 同步的核心**（B10 验收 10、禁止项：不要让联盟数据每帧全量同步）：客户端上报手里的 version，服务端只在版本更高时下发变化项。与 B07 地图 chunk 的版本号是同一套思路，只是粒度从「块」变成「联盟」。
 */
export interface AllianceView {
  /** 联盟 id */
  id: string
  /** 联盟名 */
  name: string
  /** 联盟标签（显示在成员昵称后的方括号里，也是地图实体的 allianceTag） */
  tag: string
  /** 盟主玩家 id */
  leaderId: string
  /** 联盟等级。决定人数上限、领地上限与科技上限（alliance_config 表） */
  level: number
  /** 当前等级内已累计的联盟经验 */
  exp: number
  /** 人数上限（30→50→80→120→150）。服务端按 alliance_config 查好后下发 */
  memberCap: number
  /** 当前成员数 */
  memberCount: number
  /** 联盟资金（公共资产，用于扩容与科技研究） */
  fund: number
  /** 已研究的联盟科技。<b>空数组表示一项都没研究</b>，与「没有这个字段」是两件事 —— 客户端要靠它区分「进度为 0」和「服务端还没实现」。表本身（alliance_tech）是随包下发的配置，客户端能自己画出货架，但研究到哪一级只有服务端知道。 */
  techs: AllianceTechView[]
  /** 已建造的堡垒/旗帜数 */
  territoryCount: number
  /** 领地上限。刻意不与人数同比例增长：人数决定「能打多大的仗」，领地决定「能占多少资源加成」，后者若随人数线性放开，大盟会把地图上的资源点全部圈走 */
  territoryCap: number
  /** 我在本盟的职位 */
  myRole: AllianceRole
  /** 我的贡献值 */
  myContribution: number
  /** 我今天已捐献的<b>档数</b>（= donateTiersUsed 的长度，冗余下发是为了让「X/N」这类展示不必客户端自己数） */
  myDonateToday: number
  /** 我今天<b>已经捐过哪几档</b>（档位序号，升序，取值 0..2）。2026-09-13 裁决把口径定成「每档每日一次」（B10 §2「每日 3 档（免费 / 资源 / 金币）」）之后这个列表才有意义 —— 在此之前服务端只给一个计数，客户端不知道今天该把哪个按钮摆出来，只能全摆出来等玩家点了收报错（收口清单 §三 那条 B10 缺口的原文）。**下发而不是让客户端猜**：铁律 1，「今天还能捐什么」只有一个答案。 */
  donateTiersUsed: number[]
  /** 今日可捐档数上限，来源 alliance_config 当前联盟等级那行的 donationDailyCap。下发是为了让客户端显示「2/3 档」而不是把 3 写死（客户端的 generated 只有类型没有值）。 */
  donateDailyCap: number
  /** 联盟公告 */
  announcement: string
  /** 联盟数据版本号。客户端下次同步时带上来 */
  version: number
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * 一次集结（B10 §2 集结进攻 / §1 小队集结）。**departAt 由服务端算，客户端不参与**（B07 的同一条纪律：不要用客户端定时器决定出发）。验收 11 要求「倒计时结束时所有参与部队统一出发，兵力合并正确」—— 统一出发的实现是服务端在 departAt 那一刻把 members 的兵力合成一支部队，而不是让每个人各自出发。
 */
export interface RallyView {
  /** 集结 id */
  rallyId: string
  /** 发起层级 */
  scope: RallyScope
  /** 发起组织 id（小队 id / 联盟 id / 国家 id） */
  groupId: string
  /** 发起人玩家 id */
  initiatorId: string
  /** 目标坐标 */
  targetCoord: SocialCoord
  /** 目标类型，决定到达后的行为 */
  targetType: SocialTargetType
  /** 参与人数上限。来源 global.RALLY_MAX_SIZE_*，服务端按 scope 取 */
  maxMembers: number
  /** 已加入的人数（不含发起人则为参与数，含发起人则为总队伍数） */
  joinedCount: number
  /** 已承诺出征的兵力合计。**在 PREPARING 期间就要显示** —— 集结的核心决策是「这波打得过吗」，而那个判断需要看到已经凑了多少兵 */
  totalTroops: number
  /** 准备阶段截止的服务端时间戳 */
  prepareUntil: number
  /** 统一出发时刻（= prepareUntil，除非被取消） */
  departAt: number
  /** 状态 */
  status: RallyStatus
  /** 参与者，按加入时间升序 */
  members: string[]
  /** 服务端时间戳 */
  serverNow: number
  /** 全部随军武将位及其状态，按加入顺序（发起人最先）。长度可以大于 LINEUP_HERO_COUNT —— 落选的那些也要出现在这里，见 RallyHeroSlotState。 */
  heroSlots: RallyHeroSlotView[]
}

/**
 * 一条待帮助请求（红点数据源，B10 验收 6「一键帮助全部」）。**alreadyHelped 必须下发**：一键帮助要跳过我已帮过的项，否则「帮助全部」会在同一个人身上重复消耗我的每日额度，而玩家看到的是「我点了 20 次却只帮到 5 个人」。
 */
export interface HelpRequestView {
  /** 请求 id */
  requestId: string
  /** 请求者玩家 id */
  fromPlayerId: string
  /** 请求者昵称 */
  fromPlayerName: string
  /** 帮助目标类型 */
  kind: HelpTargetKind
  /** 目标描述，如「伐木场 Lv7→8」。服务端拼好下发，客户端不得自行组装 —— 组装规则一旦分散到客户端就会出现三套文案 */
  targetDesc: string
  /** 剩余秒数（服务端算好，**绝不为负**） */
  remainingSeconds: number
  /** 已获得的帮助次数 */
  helpedCount: number
  /** 我是否已经帮过这一条 */
  alreadyHelped: boolean
}

/**
 * 一条聊天消息。
 */
export interface ChatMessageView {
  /** 消息 id */
  messageId: string
  /** 频道 */
  channel: ChatChannel
  /** 发送者玩家 id */
  senderId: string
  /** 发送者昵称 */
  senderName: string
  /** 消息正文。**原样下发，不做任何过滤后的替换** —— 敏感词处理属 B15 合规范畴，且必须在服务端做；客户端若自行替换，双端会显示不同的文本 */
  content: string
  /** 发送时刻（服务端时间戳） */
  sentAt: number
}

/**
 * 一条社交事件。推送与离线补偿共用同一结构（B10 验收 5 / 12）。**必须带 occurredAt**：离线补偿时玩家一次收到几十条，没有时间就无法判断哪条还值得响应 —— 三小时前的「盟友被攻击」已经支援不上了，点进去只会看到一片废墟。
 */
export interface SocialEventView {
  /** 事件 id */
  eventId: string
  /** 事件类型 */
  type: SocialEventType
  /** 标题。服务端拼好下发（如「盟友 张三 正在被攻击」） */
  title: string
  /** 正文，可空 */
  body: string | null
  /** 相关坐标（被攻击地点、集结目标）；与坐标无关的事件为 null。用一个可空的坐标对象而不是 coordX/coordY 两个独立可空整数 —— 后者允许「X 有值而 Y 为 null」这种无意义的组合，而一个整体为 null 的坐标不可能自相矛盾 */
  coord: SocialCoord | null
  /** 相关的组织或集结 id；无则为 null */
  relatedId: string | null
  /** 发生时刻（服务端时间戳） */
  occurredAt: number
  /** 是否已过期（响应窗口已过）。true 时 UI 必须置灰且不可跳转 —— 与 B07 侦查情报的同一条纪律：过期情报置灰，否则玩家会拿一个已经失效的目标去做决策 */
  expired: boolean
}

/**
 * GET /social/summary 响应体（B10 §二）。三层社交一屏给全：小队、联盟、国家（B13 接入前恒为 null）。pendingInvites 与 pendingHelps 是红点数据 —— B10 验收 6 要求「一键帮助全部，红点清零」，所以红点数必须由服务端给出而不是客户端自己数列表。
 */
export interface SocialSummaryResp {
  /** 我的小队；未加入为 null */
  squad: SquadView | null
  /** 我的联盟；未加入为 null */
  alliance: AllianceView | null
  /** 我的国家 id（B13 接入前恒为 null）；未加入为 null */
  nationId: string | null
  /** 待处理的邀请数（红点） */
  pendingInvites: number
  /** 可帮助但未帮助的请求数（红点）。**已经扣掉我帮过的与超出每日额度的**，否则红点会一直亮着而点进去发现什么都做不了 */
  pendingHelps: number
  /** 我今天还能帮助几次。来源 global.HELP_DAILY_LIMIT，下发是为了让客户端能显示「今日剩余 12 次」而不是自己读配置 */
  helpRemainingToday: number
  /** 未读的社交事件（离线补偿，验收 12）。已读的不重复下发 */
  events: SocialEventView[]
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * POST /alliance/sync 请求体（B10 验收 10：成员数据变更只下发 diff，不全量同步）。
 */
export interface AllianceSyncReq {
  /** 客户端手里的联盟数据版本号。首次同步传 0 */
  version: number
  /** 是否要成员列表的 diff。只想看资金与等级时传 false —— 150 人的成员列表是联盟数据里最大的一块，每次心跳都带上它就是把 diff 同步的意义抵消掉 */
  wantMembers: boolean
}

/**
 * POST /alliance/sync 响应体。**changed/removed 只含变化项**：version 相同且无变化时两个列表都为空（对应 B07 验收 6 的同一条纪律：无变化时二次请求的数据量为 0）。
 */
export interface AllianceSyncResp {
  /** 本次同步后的版本号 */
  version: number
  /** 客户端版本已是最新。true 时下面所有列表都为空，客户端直接复用缓存 */
  unchanged: boolean
  /** 新增或变化的成员 */
  changedMembers: AllianceMember[]
  /** 已离开的成员 id */
  removedMemberIds: string[]
  /** 联盟资金（标量小，每次都给，省得客户端自己累加 diff） */
  fund: number
  /** 联盟等级 */
  level: number
  /** 成员数 */
  memberCount: number
  /** 联盟公告 */
  announcement: string
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * POST /squad/create 请求体（B10 §二）。
 */
export interface SquadCreateReq {
  /** 幂等键。创建会写入组织表并占名字，重放会建出两个同名小队 */
  requestId: string
  /** 小队名。长度与敏感词校验在服务端 */
  name: string
}

/**
 * 可加入小队列表的一行（B26 S7）。与联盟那一行同一条纪律：**满不满由服务端算**，客户端不拿 memberCount 与 memberCap 自己比 —— 小队上限的第二档挂在**队长主城等级**上，那份读数只有服务端拿得到。
 */
export interface SquadDiscoveryView {
  /** 小队 id，加入时原样带回 */
  id: string
  /** 小队名（建队时填的那个） */
  name: string
  /** 小队等级 */
  level: number
  /** 当前人数 */
  memberCount: number
  /** 当前人数上限（按队长主城等级算出的生效值，不是等级表第一档） */
  memberCap: number
  /** 是否已满：与 Squad.join 会拒绝的条件同一次计算，界面据此把按钮灰掉 */
  full: boolean
}

/**
 * GET /squad/list 响应体（B26 S7）：可加入小队的前 limit 个。存在的理由与 /alliance/list 同一条 —— 加入是**直接进、不需要审核**的，但玩家连「世界上有哪些小队」都读不到，就只能自己建一支。响应必须有界，所以按等级、人数降序取前 N 个，并把 total 一起下发。
 */
export interface SquadListResp {
  /** 行，按等级降序、同级按人数降序；已解散的小队不在内 */
  squads: SquadDiscoveryView[]
  /** 服务器上共有多少个未解散小队（不是本页条数） */
  total: number
  /** 本次实际生效的条数上限（global.SQUAD_LIST_LIMIT） */
  limit: number
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * 只带小队 id 的请求（加入、邀请）。
 */
export interface SquadIdReq {
  /** 幂等键 */
  requestId: string
  /** 小队 id */
  squadId: string
}

/**
 * 带目标成员的请求（踢人、转让队长）。
 */
export interface SquadMemberReq {
  /** 幂等键 */
  requestId: string
  /** 目标成员玩家 id */
  memberId: string
}

/**
 * 只带幂等键的小队请求（退出、领取小队任务奖励、解散）。
 */
export interface SquadSelfReq {
  /** 幂等键 */
  requestId: string
}

/**
 * 某一层级发起集结的政策（B26 S13）。存在的理由与 /social/createPolicy 同一条：联盟集结要收 maxMembers 与 prepareMinutes 两个数，而它们的上下界都在 global 表里 —— 客户端不许抄表，也不许自己挑默认值，否则滑条越界、服务端悄悄夹住，玩家以为自己设的是 30 人而实际是 4 人。
 */
export interface RallyPolicyView {
  /** 最少参与人数（global.RALLY_MIN_SIZE）。低于它这一层根本开不起来，canStart 也会跟着变 false */
  minMembers: number
  /** **此刻**能设的最大参与人数：取「配置上限」与「我现在这个组织的实际人数」两者的小值。写口夹的就是这个式子，读口若给配置原值，滑条就会显示一个必然被夹掉的上限 */
  maxMembers: number
  /** 最短准备时长（global.RALLY_PREPARE_MIN_SECONDS 换算成分钟） */
  minPrepareMinutes: number
  /** 最长准备时长（global.RALLY_PREPARE_MAX_SECONDS 换算成分钟） */
  maxPrepareMinutes: number
  /** 滑条的起始值。**服务端给而不是客户端挑**：取的是最长那一档，理由是集结成败取决于等人，而配置的最大窗口就是设计者认定的「值得等」的上限 */
  defaultPrepareMinutes: number
  /** 此刻这个层级能不能发起：组织在不在、职位有没有 START_RALLY 那一位、人数够不够最低档，三条都在这里判 */
  canStart: boolean
  /** 不能发起时那句人话原因（不出现权限码与字段名）；能发起时为 null */
  reason: string | null
}

/**
 * GET /rally/policy 响应体（B26 S13）：小队与联盟两份政策一次给全（与 SocialCreatePolicyResp 同形状，少一次往返）。国家层级暂不在这里：B13 的国战集结还没有玩家入口，给了就是一个没人读的字段。
 */
export interface RallyPolicyResp {
  squad: RallyPolicyView
  alliance: RallyPolicyView
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * POST /squad/rally 请求体（B10 §二）。
 */
export interface SquadRallyReq {
  /** 幂等键 */
  requestId: string
  /** 集结目标坐标 */
  targetCoord: SocialCoord
  /** 目标类型 */
  targetType: SocialTargetType
  /** 发起人承诺出征的兵力（按 unitId → 数量，与行军同一口径）。**必填，且不得为空**：Rally.initiate 需要发起人的兵力才能建出第一个 Participant，而发起人一旦成为参与者就不能再 join 自己的集结（domain 会以「重复加入会让同一个人的兵被算两遍」拒绝），所以发起人的兵只有这一个入口。缺了这个字段的话，一次集结永远只能带着别人的兵出发。承诺即锁定：这些兵会当场从城内军队扣除，退出或集结取消时原路退回。 */
  troops: RallyTroop[]
  /** 发起人随军的武将 id，可为空。合计受 global.LINEUP_HERO_COUNT 约束（整支集结共用这些位，见 RallyHeroSlotView）。 */
  heroes: string[] | null
}

/**
 * 集结发起/加入的响应体（小队与联盟共用，靠 RallyView.scope 区分）。
 */
export interface RallyResp {
  /** 集结视图 */
  rally: RallyView
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * POST /alliance/create 请求体（B10 §二）。消耗 global.ALLIANCE_CREATE_COST_GOLD 金币。
 */
export interface AllianceCreateReq {
  /** 幂等键。创建会扣金币，没有幂等就等于允许重放刷掉一次扣费 */
  requestId: string
  /** 联盟名 */
  name: string
  /** 联盟标签（1~4 字符，显示在昵称后） */
  tag: string
}

/**
 * 只带联盟 id 的请求（申请入盟）。
 */
export interface AllianceIdReq {
  /** 幂等键 */
  requestId: string
  /** 联盟 id */
  allianceId: string
}

/**
 * POST /alliance/review 请求体（审核申请）。
 */
export interface AllianceReviewReq {
  /** 幂等键 */
  requestId: string
  /** 申请者玩家 id */
  applicantId: string
  /** 通过还是拒绝。**拒绝也要显式调用** —— 只是不处理会让申请永远挂着，申请者不知道自己被忽略了 */
  approve: boolean
}

/**
 * 带目标成员的联盟请求（踢人、转让盟主）。
 */
export interface AllianceMemberReq {
  /** 幂等键 */
  requestId: string
  /** 目标成员玩家 id */
  memberId: string
}

/**
 * POST /alliance/setRole 请求体（任命职位）。
 */
export interface AllianceRoleReq {
  /** 幂等键 */
  requestId: string
  /** 目标成员玩家 id */
  memberId: string
  /** 新职位。能不能任命由 role_permission 表决定，不由客户端判断 */
  role: AllianceRole
}

/**
 * 只带幂等键的联盟请求（退出、解散、扩容）。
 */
export interface AllianceSelfReq {
  /** 幂等键 */
  requestId: string
}

/**
 * POST /alliance/donate 请求体（B10 §二）。
 */
export interface AllianceDonateReq {
  /** 幂等键。捐献会扣资源/金币并发放贡献值，重放等于刷贡献 */
  requestId: string
  /** 捐献档位：0 免费 / 1 资源 / 2 金币。**档位而不是数额**：数额由 global.DONATE_TIER_* 决定，客户端传数额就等于把定价权交给客户端 */
  tier: number
}

/**
 * POST /alliance/donate 响应体（B10 §二 + 验收 8：捐献后资金与贡献值同步增加）。
 */
export interface AllianceDonateResp {
  /** 本次给联盟的资金 */
  fundGained: number
  /** 本次给我的贡献值 */
  contributionGained: number
  /** 捐献后的联盟资金总额。**必须下发**：只给增量的话客户端要自己累加，而累加一旦与服务端不同步就再也对不上了（验收 8 要求两者同步增加） */
  fund: number
  /** 捐献后的我的贡献值总额 */
  contribution: number
  /** 今天已捐档数 */
  donateToday: number
  /** 每日捐献档数上限（alliance_config.donationDailyCap） */
  donateDailyCap: number
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * POST /alliance/rally 请求体（B10 §二）。
 */
export interface AllianceRallyReq {
  /** 幂等键 */
  requestId: string
  /** 集结目标坐标 */
  targetCoord: SocialCoord
  /** 目标类型 */
  targetType: SocialTargetType
  /** 期望的参与人数上限。**服务端会夹到 RALLY_MAX_SIZE_ALLIANCE(20)** 而不是拒绝：发起人在滑块上很容易越界，拒绝会让他以为集结功能坏了 */
  maxMembers: number
  /** 准备时长（分钟）。服务端会夹到 [RALLY_PREPARE_MIN_SECONDS, RALLY_PREPARE_MAX_SECONDS] 区间 */
  prepareMinutes: number
  /** 发起人承诺出征的兵力（按 unitId → 数量，与行军同一口径）。**必填，且不得为空**：Rally.initiate 需要发起人的兵力才能建出第一个 Participant，而发起人一旦成为参与者就不能再 join 自己的集结（domain 会以「重复加入会让同一个人的兵被算两遍」拒绝），所以发起人的兵只有这一个入口。缺了这个字段的话，一次集结永远只能带着别人的兵出发。承诺即锁定：这些兵会当场从城内军队扣除，退出或集结取消时原路退回。 */
  troops: RallyTroop[]
  /** 发起人随军的武将 id，可为空。上限口径与小队集结一致。 */
  heroes: string[] | null
}

/**
 * 集结承诺出征的一个兵种条目。用 unit 表的行 id（含阶级），不用兵种类型 —— 与 MarchUnit / StageUnit 同一口径：按兵种类型会让 T5 兵被当成 T1 用。生成器不支持跨文件 $ref，所以这里又是一份拷贝，由 SocialContractParityTest 断言与 world 协议的 MarchUnit、stage 协议的 StageUnit 字段名与语义一致。
 */
export interface RallyTroop {
  /** unit 表的行 id，含阶级（如 unit_infantry_t3） */
  unitId: string
  /** 数量 */
  count: number
}

/**
 * POST /rally/join 与 /rally/quit 的请求体。
 */
export interface RallyJoinReq {
  /** 幂等键。加入会锁定兵力，重放会重复锁 */
  requestId: string
  /** 集结 id */
  rallyId: string
  /** 本次承诺出征的兵力（按 unitId → 数量，与行军同一口径） */
  troops: RallyTroop[]
  /** 该成员随军的武将 id，可为空。武将位按加入顺序抢，满了或与他人重复会落选（见 RallyView.heroSlots）。 */
  heroes: string[] | null
}

/**
 * POST /social/help 请求体（帮助某人一次）。
 */
export interface HelpReq {
  /** 幂等键。帮助会消耗我的每日额度并加速对方，重放等于双倍扣额度 */
  requestId: string
  /** 要帮助的请求 id（HelpRequestView.requestId） */
  helpRequestId: string
}

/**
 * 帮助类操作的响应体（单次帮助与一键帮助共用）。
 */
export interface HelpResp {
  /** 本次实际帮助了几条。一键帮助时可能少于可帮助项数 —— 每日额度用完就会停，照实返回而不是报错 */
  helped: number
  /** 跳过的条数（我已帮过的 + 额度不足的） */
  skipped: number
  /** 帮助后我今天还剩几次 */
  helpRemainingToday: number
  /** 帮助后的红点数。**验收 6 要求「红点清零」**，所以必须下发帮助后的值让客户端能直接对上 */
  pendingHelps: number
  /** 本次给对方合计削减的时长比例（定点）。受 global.HELP_SPEEDUP_TOTAL_CAP 约束，所以它可能小于「帮助次数 × 1%」—— 下发实际值而不是让客户端自己乘，否则玩家会以为被吞了 */
  speedupGranted: number
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * POST /social/report 请求体（B22 §一 3）。**举报只做留痕**（§五 裁决②）：服务端记下"谁、举报谁、哪条消息、什么原因、何时"，处置流程归运营侧 —— 代码不替它决定封不封号。
 */
export interface ReportReq {
  /** 幂等键。同一次举报重放会让留痕表多出一条重复记录，而运营看到的是一件事被报了两遍。 */
  requestId: string
  /** 被举报的人。<b>必填</b>：留痕与限频都按它记账，而客户端从聊天消息里本来就有发信人 id（`ChatMessageView.senderId`）—— 这不构成负担。"只有消息 id"那条路要在全服消息上建一个 id 索引，而除了它没有任何调用方需要那个索引（B22 §一 3 的原文给 messageId 打了问号，这里按"不留没人读的索引"取舍）。 */
  targetPlayerId: string
  /** 被举报的那条聊天消息 id。带上它，运营才能看到"被举报的原话"，否则只有一句转述 —— 而转述正是举报双方会各说各话的地方。 */
  messageId: string | null
  /** 举报原因。 */
  reason: ReportReason
  /** 补充说明，可空。**会过内容安全送检**（与聊天同一条）：举报框同样是玩家自由输入，不能因为它叫"举报"就免检。 */
  detail: string | null
}

/**
 * POST /social/report 响应体。只回执受理结果，**不回"是否处罚"** —— 那是运营的决定，且举报人也不该从响应里看出处置结果（表现成"报了就一定封"会让举报变成一种攻击工具）。
 */
export interface ReportResp {
  /** 留痕记录 id（运营侧按它查证）。 */
  reportId: string
  /** 服务端时间戳。 */
  serverNow: number
}

/**
 * POST /social/block 与 /social/unblock 的请求体（B22 §一 3）。**拉黑不是封禁**：它只切断交流（私聊拒收 + 频道消息过滤），不改变任何战斗 / PVP / 外交关系 —— B13 的冲突优先级写着国家 > 联盟 > 小队，私人恩怨不该凌驾其上。
 */
export interface BlockReq {
  /** 幂等键。重复拉黑同一个人应当是幂等的结果（在名单里就是成功），但重放不该产生两条账。 */
  requestId: string
  /** 要拉黑 / 取消拉黑的人。 */
  targetPlayerId: string
}

/**
 * GET /social/blocks 响应体：我拉黑了谁。**只回我自己的名单**：对方拉没拉黑我是看不到的（那会变成一种骚扰反馈），而发消息时服务端会给出"被对方拒收"的说清方向的错误。
 */
export interface BlockListView {
  /** 我拉黑的玩家 id，按加入顺序（最近的在前）。 */
  blockedPlayerIds: string[]
}

/**
 * 一条举报留痕（运营只读出口的行，B22 §一 3 / §五 裁决②）。字段就是运营查证时要看的那几件事：谁报的、报的谁、哪条消息、什么原因、补了什么、什么时候。
 */
export interface OpsReportRow {
  /** 留痕记录 id。 */
  reportId: string
  /** 举报人。 */
  reporterId: string
  /** 被举报人。 */
  targetPlayerId: string
  /** 被举报的消息 id；没带就是 null。带上它运营才能看到被举报的原话。 */
  messageId: string | null
  /** 举报原因。 */
  reason: ReportReason
  /** 举报人的补充说明；没写就是 null。 */
  detail: string | null
  /** 受理时刻（服务端时间戳）。 */
  createdAt: number
}

/**
 * GET /ops/report/recent 响应。**窗口回显**（与 `/ops/mail/recent` 同一条理由）：一个不说明自己看了多大窗口的空结果，区分不开"那段时间没人举报"与"我把窗口传错了"。
 */
export interface OpsReportRecentResp {
  /** 本次实际生效的窗口秒数。超出保留期会被服务端夹住（更早的记录已经不在表里了，给一个大窗口只会得到一张假表）。 */
  windowSeconds: number
  /** 窗口内匹配的条数，**不受 limit 影响**。 */
  total: number
  /** 本响应实际带出的条数。 */
  listed: number
  /** 按受理时刻倒序，最多 limit 条。 */
  rows: OpsReportRow[]
}

/**
 * POST /social/follow 与 /social/unfollow 的请求体（B22 §一 4 的"关注"，§五 裁决④：单向，不做双向申请）。
 *
 * **为什么单向**：双向申请就是第二套审批流程，而联盟入盟/申请已经把"请求-同意"这条路走通了；关注是一层更轻的社交（我想看他在不在线、想随时私聊他），**对方不需要做任何事**。
 */
export interface FollowReq {
  /** 幂等键：重复关注同一个人应当是幂等的结果，但重放不该产生第二条账。 */
  requestId: string
  /** 要关注 / 取消关注的玩家。 */
  targetPlayerId: string
}

/**
 * 一条关注（B22 §二 草案的 FriendView，按要求带上在线状态）。**没有 "互相关注" 这个状态**：单向关注不构成关系，服务端也不知道对方是否也关注你（知道也不该说 —— 那等于把"谁在看你"透给被看的人）。
 */
export interface FriendView {
  /** 被关注的玩家 id。 */
  playerId: string
  /** 昵称（服务端拼好下发）。 */
  name: string
  /** 此刻是否在线（来自 WS 网关的在线快照）。 */
  online: boolean
  /** 最近活跃时刻；在线时为当前时刻。 */
  lastSeenAt: number
}

/**
 * GET /social/follows 响应体：我关注的人，最近关注的在前。
 */
export interface FriendListView {
  /** 关注列表。**上限由 global.SOCIAL_FOLLOW_MAX 管**，超了在关注时就拒（见那边的 why）。 */
  friends: FriendView[]
}

/**
 * POST /chat/send 请求体。
 */
export interface ChatSendReq {
  /** 幂等键。聊天重放会导致同一句话发两遍，而这恰好会撞上防刷屏限流，玩家看到的是「我发一句话却提示刷屏」 */
  requestId: string
  /** 频道 */
  channel: ChatChannel
  /** 正文。长度上限在服务端校验 */
  content: string
  /** 私聊对象；非私聊频道为 null */
  toPlayerId: string | null
}

/**
 * POST /chat/send 响应体。**被限流时走业务错误码而不是本响应** —— 返回一条「假装发成功」的消息会让玩家以为对方收到了（B10 验收 9）。
 */
export interface ChatSendResp {
  /** 已落地的消息 */
  message: ChatMessageView
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * POST /chat/list 请求体（拉取某频道的最近消息）。用 POST 是因为要带频道与游标，而 GET 的查询串在私聊频道上会泄漏对象 id 到访问日志里。
 */
export interface ChatListReq {
  /** 频道 */
  channel: ChatChannel
  /** 私聊对象；<b>只有 PRIVATE 频道需要</b>，其余频道忽略。私聊的会话键是由<b>两个人</b>的 id 拼出来的，拉历史却不带对象就算不出键 —— 少这个字段的结果是「发得出去、刷新即丢」。它走请求体而不是 GET 查询串，理由与本 DTO 用 POST 同一条：对象 id 出现在 URL 里就会落进访问日志与代理日志。<b>缺它时报明确的错误，不返回空列表</b>：空列表会被客户端读成「这段会话没有历史」，从而安静地丢掉一整屏消息。 */
  toPlayerId: string | null
  /** 游标：只要这条之前的消息；首次拉取为 null */
  beforeMessageId: string | null
  /** 最多要几条。服务端会夹到 global.CHAT_LOCAL_HISTORY_MAX */
  limit: number
}

/**
 * POST /chat/list 响应体。
 */
export interface ChatListResp {
  /** 消息，按时间升序（客户端直接从上往下画） */
  messages: ChatMessageView[]
  /** 是否还有更早的消息 */
  hasMore: boolean
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * POST /social/ackEvents 请求体（标记事件已读）。离线补偿的事件必须能被标记已读，否则每次上线都会重新收到同一批（验收 12）。
 */
export interface SocialEventAckReq {
  /** 幂等键 */
  requestId: string
  /** 要标记已读的事件 id。空数组表示全部标记 */
  eventIds: string[]
}

/**
 * GET /social/permissions 响应体（B10 验收 4：权限矩阵配置化）。**下发的是结论而不是矩阵**：客户端拿到「我能做什么」的列表就能决定按钮灰不灰，不需要知道 role_permission 表长什么样 —— 把表下发出去等于把权限模型暴露给客户端，而客户端的任何判断都可以被绕过。
 */
export interface PermissionListResp {
  /** 权限所属层级 */
  scope: string
  /** 我在该层级的职位 */
  role: string
  /** 我在该层级拥有的权限码（role_permission.permission） */
  permissions: string[]
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * 创建小队/联盟之前玩家要知道的那几件事（B26 S2）。**门槛与消耗全部由服务端算**：解锁要的主城等级与开服天数写在 squad_config/alliance_config 里，客户端抄一份就是第二真相 —— 表一改，界面会写着「还差 2 级」而服务端其实已经放行（反过来也一样）。
 */
export interface SocialCreatePolicy {
  /** 现在能不能创建。为 false 时 reason 一定带着给人看的那句原因 */
  canCreate: boolean
  /** 创建要扣的数额（global.ALLIANCE_CREATE_COST_GOLD；小队创建不要钱，回 0）。下发数额而不是让客户端读表：定价权在服务端 */
  costGold: number
  /** 上面那笔数额扣的是**哪种资源**（ResourceType 取值，如 GOLD）。只下发类型不下发名字：资源中文名在客户端有唯一一份 `game/ui/ResourceNames.ts`（那份文件自己写着「在契约把名字下发之前它是唯一真源，不许再抄第四份」），服务端再下发一份就是两个真相 */
  costResource: string
  /** 不能创建时给人看的说法，与写路径抛出去的那条是**同一份字符串**（判定只写一遍）：「需要主城 5 级，当前 1 级」/「你已经在联盟「铁誓」里」/「还需等待 86400 秒」。能创建时不下发这个字段 */
  reason: string | null
}

/**
 * GET /social/createPolicy 响应体（B26 S2）。一次回两个层级：社交面板本来就要同时画小队与联盟两页，分两次拉会让两页的可用性来自不同时刻。
 */
export interface SocialCreatePolicyResp {
  /** 小队创建政策 */
  squad: SocialCreatePolicy
  /** 联盟创建政策 */
  alliance: SocialCreatePolicy
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * 可申请联盟列表的一行（B26 S6）。**只下发结论字段**：满不满、我有没有申请过、上限是多少都由服务端算 —— 客户端自己拿 memberCount 与 effectiveMemberCap 比会漏掉「队长临时提过的上限」这类只有服务端知道的口径。
 */
export interface AllianceDiscoveryView {
  /** 联盟 id，申请时原样带回 */
  id: string
  /** 联盟名（建盟时填的那个） */
  name: string
  /** 标签（显示在昵称后那几个字） */
  tag: string
  /** 联盟等级 */
  level: number
  /** 当前人数 */
  memberCount: number
  /** 当前人数上限（含盟主扩容后的值，不是等级表默认值） */
  memberCap: number
  /** 是否已满：服务端算的，客户端不再自己比 */
  full: boolean
  /** 我已经申请过这个联盟（服务端的应用账本）。没有这一项，界面就只能让玩家再吃一条「申请已提交，等待审核」 */
  applied: boolean
}

/**
 * GET /alliance/list 响应体（B26 S6）：可申请联盟的**前 limit 个**。这一版不做翻页 —— 联盟是玩家花金币建的、没有机器人批量造，总量天然小；但响应必须有界，所以按等级、人数降序取前 N 个，并把 total 一起下发，界面写「共 X 个，只显示前 Y 个」。
 */
export interface AllianceListResp {
  /** 行，按等级降序、同级按人数降序 */
  alliances: AllianceDiscoveryView[]
  /** 服务器上共有多少个联盟（不是本页条数） */
  total: number
  /** 本次实际生效的条数上限（global.ALLIANCE_LIST_LIMIT） */
  limit: number
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * 一条入盟申请（B26 S8）。**昵称与主城等级由服务端一起下发**：审核要看的正是「这个人现在什么水平」，而客户端既没有玩家表也不该拿 id 去猜 —— 只回 id 的列表等于让盟主对着一串 p_1a2b 点批准。
 */
export interface ApplicantView {
  /** 申请人 id，审核时原样带回 */
  playerId: string
  /** 申请人昵称（服务端查的那一份，客户端不自己拼） */
  nickname: string
  /** 申请人主城等级 */
  mainCityLevel: number
}

/**
 * GET /alliance/applications 响应体（B26 S8）：**本盟**待处理申请的前 limit 条。存在的理由与另两份发现口同族 —— `/alliance/review` 早就有，但没有任何地方能列出「谁申了」，于是那颗批准按钮永远按不下去。这一条**只对能审核的人开**（服务端按 role_permission 的 APPROVE_APPLICATION 判），因为它下发的是别人的身份。
 */
export interface AllianceApplicationListResp {
  /** 行，按申请人 id 升序（同一份库存上可复现） */
  applicants: ApplicantView[]
  /** 本盟待处理申请总数（与徽标那个数同源，不是本页条数） */
  total: number
  /** 本次实际生效的条数上限（global.ALLIANCE_APPLICATION_LIST_LIMIT） */
  limit: number
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * POST /alliance/tech 请求体（用联盟资金研究科技）。
 */
export interface AllianceTechReq {
  /** 幂等键。研究会扣联盟资金（公共资产），重放等于全盟被多扣一次 */
  requestId: string
  /** alliance_tech 表的行 id */
  techId: string
  /** 一次研究几级。上限由联盟等级决定（alliance_config.techCapBonus） */
  levels: number
}

/**
 * POST /alliance/tech 响应体。
 */
export interface AllianceTechResp {
  /** 科技 id */
  techId: string
  /** 研究后的等级 */
  level: number
  /** 当前联盟等级下的等级上限。**必须下发**：上限随联盟等级变，客户端自己算不出（要查两张表并做乘法） */
  levelCap: number
  /** 本次消耗的联盟资金 */
  fundCost: number
  /** 研究后的联盟资金余额 */
  fund: number
  /** 研究后的效果值（定点）。全盟生效 */
  effectValue: number
  /** 服务端时间戳 */
  serverNow: number
}

/**
 * GET /rally/list 响应：我所在的小队与联盟里**进行中**的集结。面板列表用 —— 只返回 PREPARING 的，已出发或已取消的集结留在面板上没有意义，而「点进去发现早就出发了」比「看不到」更让人困惑。
 */
export interface RallyListResp {
  /** 进行中的集结，按创建时刻升序（先发起的排前面，因为它的准备窗口先结束）。 */
  rallies: RallyView[]
  /** 服务端时间戳。客户端据此算准备窗口的剩余秒数（铁律 5：倒计时不得用本地时钟）。 */
  serverNow: number
}

/**
 * 一个随军武将位（按加入顺序，发起人最先）。
 */
export interface RallyHeroSlotView {
  /** 提交这个武将位的成员。 */
  playerId: string
  /** 武将 id。 */
  heroId: string
  /** 武将名，服务端从 hero 表下发。客户端不得自行拼接：那份名字要与战报、聊天、客服工单里的称呼一致。 */
  heroName: string
  /** 状态。 */
  state: RallyHeroSlotState
  /** 是否进入合并行军的名单。等于 state == SELECTED，单独给一个布尔是为了让客户端不必理解枚举就能画红点。 */
  selected: boolean
}

/**
 * 红点树的一个节点（含中间节点）。父节点的 lit 由服务端聚合，客户端只消费。
 */
export interface ReddotNodeView {
  /** 路径式 key，分隔符与服务端 ReddotTree.SEPARATOR 一致（形如 social/help）。整棵子树的根是空串，它不下发，只作为 children 的容器。 */
  key: string
  /** 此刻是否亮。叶子是注册条件本身，中间节点是「任一后代叶子亮」。既不存在的 key 也不是任何叶子的前缀 ⇒ 永远 false，这是 B12 验收 1「无假红点」的落点。 */
  lit: boolean
  /** 子节点。叶子是空数组而不是省略 —— 客户端要能区分「这是叶子」与「还没下发到这里」。 */
  children: ReddotNodeView[]
}

/**
 * GET /social/reddot 响应体：服务端算好的完整红点树。刻意是**整体下发而不是增量**：客户端合并会让已经消失的红点永远留着（假红点），而那是 B12 验收 1 直接判失败的现象。
 */
export interface ReddotTreeResp {
  /** 树的第一层节点（每个是一棵子树的根）。 */
  nodes: ReddotNodeView[]
  /** 已注册叶子数。下发是为了让「红点判断有没有散落到业务模块里」变成一个可断言的数：它应当随功能数增长，而不是长期停在个位数 */
  leafCount: number
  serverNow: number
}

/**
 * GET /social/helpRequests 响应体：可互助的请求列表 + 同一个数算出来的红点。两者必须由服务端同一次遍历给出 —— 列表与徽标分开算早晚漂移，表现是「红点说 5 条、点进去只有 3 条，一键帮助却帮了 5 次」。
 */
export interface SocialHelpListResp {
  /** 同组织里未过期、不是自己发的请求，**含已经帮过的**（每行的 alreadyHelped 标出来）。列表里放已经帮过的是因为玩家要知道「我帮过谁」，而不只是「还有谁能帮」。 */
  requests: HelpRequestView[]
  /** 徽标数：列表里 alreadyHelped=false 的行数，再按今日剩余额度截断。与 SocialSummaryResp.pendingHelps 同源同值 */
  pendingHelps: number
  /** 今日还能帮几次，与摘要里那个数是同一个来源 */
  helpRemainingToday: number
  serverNow: number
}
