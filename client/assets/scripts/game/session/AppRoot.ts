/**
 * 职责：客户端组合根 —— 把「面板按钮的意图」接到「网络请求」，并把响应送回面板。
 * 依赖：`GameApi` / `GameSession` / `Store` / `TimeSync` 与各面板的窄接口 {@link PanelTargets}。
 *       **不 import `cc`**，所以本文件能在 node:test 里被完整驱动（场景层做不到这件事）。
 *
 * <p><b>为什么要有这个文件</b>：此前 `GameApi` 只在单测里被构造过，没有任何生产代码 new 它，
 * 于是每个面板 View 暴露的回调字段永远是 null —— 按钮画出来了、按下去什么都不发生，
 * 而且不报错。本文件是那根缺失的线。
 *
 * <p><b>三条写死的纪律</b>：
 * <ol>
 *   <li><b>写操作成功后必须重拉受影响的面板列表</b>。客户端不自己改本地数字 ——
 *       那会产生「资源显示为负」这类只有玩家才会发现的差异。</li>
 *   <li><b>失败只报原因，不刷新也不半成功</b>。刷新会把玩家正在看的错误提示覆盖掉，
 *       而「飘了成功 toast 却没刷新」是本项目反复出现的那类不一致。</li>
 *   <li><b>信息不足时不猜</b>。出战阵容、道具目标这类需要选择器的动作统一走
 *       {@link PanelTargets.error} 明确说出「还缺什么」，而不是替玩家挑一个阵容 ——
 *       挑错阵容消耗的是玩家的体力与兵，且不会有任何报错。</li>
 * </ol>
 */

import { TRACK_EVENTS, trackParam } from '../track/TrackEvents'
import type { GameApi } from './GameApi'
import type { GameSession } from './GameSession'
import type { NetOutcome } from '../../net/NetModule'
import type { Store } from '../store/Store'
import type { TimeSync } from '../../core/TimeSync'
import type { OfflineReportView, PowerDetailResp, StaminaBuyResp, StaminaResp } from '../../net/generated/Protocol'
import type {
  CityCancelResp, CityCollectResp, CityListResp, SpeedUpSource,
} from '../../net/generated/CityProtocol'
import type { ArmyListResp } from '../../net/generated/ArmyProtocol'
import type { BagListResp, OpenBatchResp, ResourceDetailResp } from '../../net/generated/BagProtocol'
import type { GachaDrawResp, GachaPoolsResp, HeroListResp } from '../../net/generated/HeroProtocol'
import type { ChallengeStageResp, StageListResp, SweepResp } from '../../net/generated/StageProtocol'
import type {
  AllianceMember, AllianceRole, AllianceSyncResp, ChatChannel, ChatMessageView, FriendView, HelpRequestView,
  ReportReason, RallyPolicyResp, RallyPolicyView, SocialCreatePolicy, SocialCreatePolicyResp,
  SocialEventView, SocialSummaryResp,
} from '../../net/generated/SocialProtocol'
import {
  ackablePrivateEventIds, buildChatPanel, chatFailureText, chatKey, chatPageCount,
  CHAT_FETCH_OLDER, CHAT_LOCAL_HISTORY_MAX, CHAT_PAGE_ROWS, mergeChatHistory,
} from '../social/ChatPanel'
import type { ChatPanelData } from '../social/ChatPanel'
import { buildRankBoard, buildRankSnapshotView } from '../power/RankBoard'
import { buildStaminaBoard } from '../stage/StaminaBoard'
import {
  adjustRallyNumber, buildCompose, marchUnitsOf, rallyFormBlocked, rallyFormOf, rallyNumbersOf,
  rallySwitchBlocked, rememberMarch, repeatBlockedReason, setPick,
} from '../world/MarchCompose'
import type {
  ComposeView, MarchSpec, RallyField, RallyForm, RallyNumberRow, RallyScope, RallyScopeRow,
} from '../world/MarchCompose'
import type { RankBoardView, RankSnapshotView, RankTabKey } from '../power/RankBoard'
import type { RankListResp } from '../../net/generated/RankProtocol'
import { buildSeasonPanel } from '../season/SeasonPanel'
import type { SeasonPanelView } from '../season/SeasonPanel'
import type { SeasonStatusResp } from '../../net/generated/SeasonProtocol'
import type { ScoutListResp } from '../../net/generated/WorldProtocol'
import { blockReasonText, buildTechPanel } from '../tech/TechPanel'
import type { TechPanelView } from '../tech/TechPanel'
import type {
  TechCancelResp, TechListView, TechSpeedUpResp,
} from '../../net/generated/TechProtocol'
import { buildEquipPanel } from '../equip/EquipPanel'
import type { EquipPanelView } from '../equip/EquipPanel'
import type { EquipInstanceListView, EquipSlot } from '../../net/generated/EquipProtocol'
import { buildExpPick, bumpPick, expPickRows, pickedPayload } from '../hero/ExpPick'
import type { ExpPickView } from '../hero/ExpPick'
import { buildAwakenPick } from '../hero/AwakenPick'
import type { AwakenPickView, AwakenStage } from '../hero/AwakenPick'
import { buildSkillPick } from '../hero/SkillPick'
import type { SkillPickView, SkillStage } from '../hero/SkillPick'
import { buildComposeView } from '../hero/HeroCompose'
import type { HeroComposeView } from '../hero/HeroCompose'
import { buildHeroPanel } from '../hero/HeroPanel'
import { buildLineupEdit, lineupBody } from '../hero/LineupEdit'
import type { LineupEditView, LineupSlot } from '../hero/LineupEdit'
import { EMPTY_PERMISSIONS, gate, withPermissionScope, withoutNationPermissions } from '../social/PermissionGates'
import type { PermissionState } from '../social/PermissionGates'
import { buildCreateForm, createEntries } from '../social/SocialCreate'
import type { CreateEntry, CreateForm, CreateScope } from '../social/SocialCreate'
import { buildDiscovery, canApply, EMPTY_DISCOVERY } from '../social/AllianceDiscovery'
import { buildSquadDiscovery, canJoin, EMPTY_SQUAD_DISCOVERY } from '../social/SquadDiscovery'
import type { DiscoveryView } from '../social/AllianceDiscovery'
import type { SquadListView } from '../social/SquadDiscovery'
import { buildApplications, EMPTY_APPLICATIONS } from '../social/AllianceApplications'
import type { ApplicationView } from '../social/AllianceApplications'

import type { ExitAction, ExitKey, ExitScope } from '../social/SocialExit'
import { buildGachaPanel, TEN_DRAW_COUNT } from '../gacha/GachaPanel'
import type { GachaBalances, GachaPanelView } from '../gacha/GachaPanel'
import { buildDisclosure } from '../gacha/GachaDisclosure'
import type { GachaDisclosure } from '../gacha/GachaDisclosure'
import { buildGachaHistory } from '../gacha/GachaHistory'
import type { GachaHistoryView } from '../gacha/GachaHistory'
import { buildNationPanel, amountText, cooldownText } from '../nation/NationPanel'
import type { NationCandidate, NationPanelView, NationTabKey, SpendDraft } from '../nation/NationPanel'
import {
  APPOINTABLE_OFFICES, DIPLOMACY_OPTIONS, buildNationSections, diplomacyNotice,
} from '../nation/NationSections'
import type {
  DiplomacyRelation, NationLeaveResp, NationOffice, NationRelationView, NationResp, NationTreasuryResp,
} from '../../net/generated/NationProtocol'
import type { NationTechListView } from '../../net/generated/NationTechProtocol'
import type { NationPolicyRoundView, WarStatusResp } from '../../net/generated/NationProtocol'
import { buildPolicyPanel } from '../nation/NationPolicyPanel'
import type { PolicyPanel } from '../nation/NationPolicyPanel'
import { gameBus } from '../../core/EventBus'
import type { MarchUnit, SearchTargetsResp } from '../../net/generated/WorldProtocol'
import type { QuestListResp } from '../../net/generated/QuestProtocol'
import type {
  BattleReportBrief, BattleReportListResp, BattleReportResp, ShareChannel,
} from '../../net/generated/BattleProtocol'
import type { MailClaimAllResp, MailListResp } from '../../net/generated/MailProtocol'
import type { ActivityClaimResp, ActivityListResp } from '../../net/generated/ActivityProtocol'
import type { GuideAction, GuideProgressResp, GuideScriptResp } from '../../net/generated/GuideProtocol'
import { claimActivityReq } from '../activity/ActivityPanel'
import {
  buildArmyQueueChoices, buildChestOpenChoices, buildChatActionChoices, buildLineupChoices,
  buildResearchSpeedupChoices, buildShareChannelChoices, buildSpeedupChoices,
  buildTrainSpeedupChoices,
} from './Choices'
import type {
  ChatActionChoice, ChoiceOption, LineupChoice, ResearchSpeedupChoice, ShareChannelChoice, SpeedupChoice,
} from './Choices'
import type { GachaHistoryResp, GiftPopupResp } from '../../net/generated/PayProtocol'
import type { PayView } from '../pay/GiftPayFlow'
import { GiftPayFlow } from '../pay/GiftPayFlow'
import { requestMidasPayment } from '../../net/MidasPayment'
import { ClientReddotTree } from '../reddot/ReddotTree'
import { pickUnavailable, actionUnavailable } from '../ui/BlockedPickCopy'
import { buildStaminaDetail } from '../ui/StaminaDetail'
import type { StaminaDetailView } from '../ui/StaminaDetail'
import { autoTrainBlockedReason, autoTrainRequest, rememberTrain } from '../army/AutoTrain'
import { buildShopPanel, buyBodyOf, buyResultText, shopRowStateText } from '../shop/ShopPanel'
import type { ShopPanelView } from '../shop/ShopPanel'
import type { ShopCurrency, ShopListResp } from '../../net/generated/ShopProtocol'
import { buildAvatarFramePanel, wearBodyOf, wearResultText } from '../avatar/AvatarFramePanel'
import type { AvatarFramePanelView } from '../avatar/AvatarFramePanel'
import type { AvatarFrameListResp } from '../../net/generated/Protocol'
import type { BattlePassStatusResp, BattlePassTrack } from '../../net/generated/BattlePassProtocol'
import type { RallyListResp } from '../../net/generated/SocialProtocol'
import { buildBattlePassPanel, claimBodyOf, claimResultText } from '../battlePass/BattlePassPanel'
import { buildOfflineItems, offlineReportGate } from '../offline/OfflineReport'
import type { OfflineItem } from '../offline/OfflineReport'
import type { TrainMemory } from '../army/AutoTrain'

/**
 * 军队还没拉到时用的空样本：编成面板在拿到真实军队之前也要能画出来（全 0、不可提交），
 * 而不是等到拉完才第一次出现 —— 面板突然弹出的观感比"先画个空的"差得多。
 */
const EMPTY_ARMY: ArmyListResp = {
  units: [], troopCap: 0, troopsInUse: 0, trainingInUse: 0, queueSlots: 0, queueSlotsMax: 0,
  hospital: {
    capacity: 0, used: 0, treating: false, treatFinishAt: null, treatRemainingSeconds: 0,
    treatSecondsPerWounded: 0, treatCostRatio: 0,
  },
  autoTrain: {
    enabled: false, unitId: '', batchCount: 0, batchBudget: 0, targetCount: 0, stopReason: null,
  },
  serverNow: 0,
}

/** 面板需要落地的一类数据。全部可选：某个场景里没有这个面板时就不实现。 */
/** 出征编成面板的整块视图：目标 + 编成 + 提示。 */
export interface MarchComposeView {
  readonly targetId: string
  readonly targetName: string
  readonly coordText: string
  readonly compose: ComposeView
  /** 提交失败/成功后的提示行；没有时为 null */
  readonly notice: string | null
  /** 正在提交（面板据此禁用确认键，防双击发两份） */
  readonly submitting: boolean
  /**
   * 这一份编成是要**发起集结**、**派侦察**还是普通出征（B26 S12 / B26 S18）。省略等于 MARCH：
   * 「再次出征」那几条提示走的是同一个面板，它们永远不出集结也不派侦察，就不该各自补一遍字段。
   */
  readonly mode?: 'MARCH' | 'RALLY' | 'SCOUT'
  /** 确认键上的字（出征 / 发起集结）。面板不自己翻，免得两处写两份 */
  readonly submitLabel?: string
  /** 不能发起集结时那句原因（读不到权限时**不为它**置灰：那是"暂时不知道"，不是"你不行"） */
  readonly rallyBlocked?: string | null
  /**
   * 集结态下的两个层级入口（B26 S14）。出征态不画这一行，所以省略即"没有层级可选"。
   * 「联盟集结要收两个数」这件事不能让玩家在别处填 —— 同一份兵、同一个目标，
   * 换的只是命令种类与召集范围，所以层级就摆在编成面板里。
   */
  readonly rallyScopes?: readonly RallyScopeRow[]
  /** 当前选中的层级（省略 = 小队，与 `mode` 省略等于 MARCH 同一口径） */
  readonly rallyScope?: RallyScope
  /** 联盟层的两个数（含界）；政策还没拉到时是空数组，面板就少画两行而不是画一对猜出来的数 */
  readonly rallyNumbers?: readonly RallyNumberRow[]
}

/** 商店面板：货架那块来自 game/shop/ShopPanel.ts，notice 是**上一次兑换的结果**（临时提示）。 */
export interface ShopView extends ShopPanelView {
  readonly notice: string | null
}

/** 「自上次登录以来」那一屏：只装条目，判定与阈值全在 game/offline/OfflineReport.ts 里。 */
export interface OfflineReportPopup {
  readonly items: readonly OfflineItem[]
}

/** 外观面板（B24 块③ 头像框）：框列表那块来自 game/avatar/AvatarFramePanel.ts，notice 是上一次操作的结果。 */
export interface AvatarFramesView extends AvatarFramePanelView {
  readonly notice: string | null
}

/**
 * 战令面板（B24 S-d-e）。**递的是原始响应 + 提示行**，不是组装好的视图：
 * 一屏画几档取决于实测可视高度（只有视图知道），所以窗口在视图里现算 ——
 * 在编排层算死会让"窗口高度变了"这件事在换设备时静默错位。
 */
export interface BattlePassPanelData {
  readonly source: BattlePassStatusResp
  readonly notice: string | null
}

/**
 * 集结面板（V02-S1）：原始响应 + 我自己的 playerId + 上一次动作的结果。
 *
 * <p>**playerId 必须由编排层给**：`RallyView.members` 是服务端下发的权威名单，
 * 而"我是谁"只有登录响应里有 —— 纯逻辑层拿它去对名单，不重新判任何规则。
 */
export interface RallyPanelData {
  readonly source: RallyListResp
  readonly myPlayerId: string
  readonly notice: string | null
}

export interface PanelTargets {
  city?(resp: CityListResp, offsetMs: number): void
  /** 一次收割的即时结果（要立刻飘字，之后再被 city 列表覆盖）。 */
  cityCollect?(resp: CityCollectResp): void
  /** 体力详情弹层（B09 §5）。视图模型已经算好文案与置灰，表现层只负责画。 */
  staminaDetail?(view: StaminaDetailView): void
  /** 一次取消建造的即时回执（退回来多少，照服务端给的数念）。 */
  cityCancelled?(resp: CityCancelResp): void
  /**
   * 军队面板。`trainMemory` 是客户端记住的上一次成功训练 —— 面板只用它决定
   * 「自动续训」现在能不能开（开关本身的策略全部来自响应，见 game/army/AutoTrain.ts）。
   */
  army?(resp: ArmyListResp, offsetMs: number, trainMemory: TrainMemory | null): void
  hero?(resp: HeroListResp): void
  resources?(resp: ResourceDetailResp): void
  bag?(resp: BagListResp): void
  stage?(resp: StageListResp): void
  /**
   * 体力那一屏（画在关卡面板表头下面）。`gold` 由本层从资源明细里取来 ——
   * 场景层不参与"够不够"的算账，它只把 `StaminaBoard` 给的字画出来。
   */
  stamina?(resp: StaminaResp, gold: number | null): void
  /** 一次购买的回执（到账 / 扣币 / 今日已购）。画在关卡面板那条摘要带上。 */
  staminaBought?(resp: StaminaBuyResp): void
  /**
   * 一次挑战的结算（星级、掉落、达成条件、体力）。同样画在那条摘要带上 ——
   * 只刷关卡列表等于把「这一把到底打成什么样」丢掉，而那是玩家刚花掉一次体力的结果。
   */
  challengeResult?(resp: ChallengeStageResp): void
  /**
   * 一次批量扫荡的结算。`requested` 是客户端发出去的次数：响应里没有这个字段，
   * 而没有它就解释不了「我要 10 次为什么只扫了 7 次」。
   */
  sweepResult?(resp: SweepResp, requested: number): void
  /** 取消研究的回执：取消了哪一行、退回来多少资源（比例服务端算，与城建同一份配置）。 */
  techCancelled?(resp: TechCancelResp): void
  /** 研究加速用哪一张（候选按 `effectKind` 筛，不按 id 硬编码；一份选项自带张数）。 */
  researchSpeedupChoice?(options: readonly ResearchSpeedupChoice[],
    onPick: (choice: ResearchSpeedupChoice) => void): void
  /** 一次研究加速的回执：减了多少秒、还剩多少、是否因此完成。 */
  techSpeededUp?(resp: TechSpeedUpResp): void
  /**
   * 社交面板。`members` 走 `/alliance/sync` 的 diff 通道，`helps` 走
   * `/social/helpRequests`；两者都由服务端给出，客户端只转手，不自己拼列表。
   */
  social?(resp: SocialSummaryResp, helps: readonly HelpRequestView[],
    members: readonly AllianceMember[], offsetMs: number): void
  /**
   * 聊天页签（B22 §一 1）。频道、会话列表、消息与提示行都从这一份数据来 ——
   * 拆成多个回调就会出现"未读变了但会话列表还停在旧数"这类两半不同步。
   */
  chat?(data: ChatPanelData): void
  /**
   * 聊天消息行上能做什么（B22 §一 3）：举报的四种原因 + 拉黑/取消拉黑，一层选择器里全给出。
   * 选项由根给出（它才知道我拉黑过谁），场景层只画和回调。
   */
  chatActionChoice?(options: readonly ChatActionChoice[], onPick: (choice: ChatActionChoice) => void): void
  /**
   * 礼包弹窗（B19 S3-iv）。`resp.popup=false` 时面板自己藏起来 —— 判"弹不弹"的是服务端，
   * 客户端只负责画。
   */
  giftPopup?(resp: GiftPopupResp, serverNowMs: number): void
  /** 一次购买的结果（由 `GiftPayFlow` 判定；面板只显示）。 */
  payResult?(view: PayView): void
  power?(resp: PowerDetailResp): void
  targets?(resp: SearchTargetsResp): void
  /**
   * 出征编成（B25-S1 首次出征入口）。整块视图由编排层组装：目标是谁、可带哪些兵、够不够提交，
   * 表现层只画不判。**没有这个回调时确认键不会扣兵也不会发请求**（裁决：确认之前不发请求）。
   */
  marchCompose?(view: MarchComposeView): void
  /**
   * 排行榜面板（B23 §一 3）。**整块视图由编排层组装好下发**（名次、页号、我的名次都在里面）：
   * 表现层只画，不做任何名次计算 —— 客户端算一遍的结局是与服务端差一位，而名次决定发不发奖。
   */
  rank?(view: RankBoardView): void
  /**
   * 赛季页（V04-S1）。与榜同一个面板的第六个页签，但**数据源不同**：
   * 阶段/时限/三条闸门来自 `/season/status`，不是从榜里推的。
   */
  season?(view: SeasonPanelView): void
  /**
   * 研究页（V03-a-S1）。入口在内城「学院」：学院等级就是科技的前置，
   * 玩家问"我这学院能研究什么"的地方就在那儿。
   */
  tech?(view: TechPanelView): void
  /**
   * 装备实例页（V03-b-S1）。入口在武将页：装备穿在武将身上，需要它的人就在那一页。
   */
  equip?(view: EquipPanelView): void
  /**
   * 升级喂道具的弹层（V03-d）。**每次都带一份完整视图**：加减之后编排层会重发一次，
   * 视图不认识计数规则（夹取/全 0 能不能发都在纯逻辑里）。
   */
  expPick?(view: ExpPickView, heroName: string): void
  /**
   * 觉醒选石头的弹层（V03-d）。与升级弹层同形：**每次都带一份完整视图**，
   * 哪块石点亮由纯逻辑按当前这一阶判（`game/hero/AwakenPick.ts`）。
   */
  awakenPick?(view: AwakenPickView, heroName: string): void
  /**
   * 技能选书的弹层（V03-d 最后一条）。行上带 `slot` —— **槽位是那本书决定的**（`effectTarget`），
   * 不是玩家先选一个再赌一本对得上的书。
   */
  skillPick?(view: SkillPickView, heroName: string): void
  /**
   * 碎片合成弹层（V03-d 第六条线）。第二个参数是**碎片钱包**那几行（各档持有多少），
   * 由 `HeroPanel.fragmentTexts` 给出 —— 同一个格式化函数只有一份，弹层不重抄一遍（#281 那笔账）。
   */
  composePick?(view: HeroComposeView, purse: readonly string[]): void
  /**
   * 抽卡面板（抽卡入口）。整块视图由编排层组装：池页签、两个键亮不亮、余额、上一次抽到了什么。
   * 表现层只画不判 —— 判"够不够"的数全在 `game/gacha/GachaPanel.ts`，而那些数全来自服务端。
   */
  gacha?(view: GachaPanelView): void
  /**
   * 编队编辑弹层（`POST /hero/lineup` 的唯一入口）。整块视图由编排层组装：
   * 三槽现在站着谁、正在选哪一槽、名单里谁灰、能不能保存 —— 视图只画。
   */
  lineupEdit?(view: LineupEditView): void
  /**
   * 社交页的三道门（B26 S1 + S2）：两个 scope 的权限、一份创建政策。两者总是同时到齐，
   * 所以走同一个回调 —— 分成两条就会有一条先到，面板按半份数据画一次。
   *
   * <p>接不上时的症状很隐蔽：`permissions` 永远是空数组 ⇒ 已经接好线的「踢出」「捐献」行
   * **永远置灰**，玩家看得见按钮却永远点不动，界面还不说原因。
   */
  socialGates?(state: PermissionState, create: Record<CreateScope, CreateEntry>): void
  /** 创建小队/联盟那一屏（null = 关掉）。全部文字与「确认」能不能点都由编排层算好。 */
  socialCreate?(form: CreateForm | null): void
  /** 退出/解散里"已按下第一下"的那一行（null = 没有）。视图只改字，不发请求。 */
  socialExit?(armed: ExitKey | null): void
  /** 转让里"已按下第一下"的那个成员（null = 没有）。 */
  socialTransfer?(armed: { scope: ExitScope, memberId: string } | null): void
  /** 可申请联盟那一屏（B26 S6）：行与那句总量说明都由纯逻辑算好。 */
  allianceDiscovery?(view: DiscoveryView): void
  /**
   * 国家面板（V13-S1 · B13）。整块视图由 `game/nation/NationPanel` 组装：
   * 有没有国家、能做什么、国库余额与流水全在里面。
   *
   * <p>**「不在任何国家」不是错误**：那是 13000 `NATION_NOT_FOUND`，被折成 `mode: 'NONE'` 那一态。
   */
  nation?(view: NationPanelView): void
  /**
   * 可选的国库收款人（联盟成员，id → 昵称）。**只在玩家真的要点「发给成员」时才发那一枪** ——
   * 为一颗还没按的键多发一次读，弱网下就是白等一个来回。
   */
  nationPayees?(payees: readonly { id: string; name: string }[]): void
  /** 可加入小队那一屏（B26 S7）：同一族，只是小队不要审核。 */
  squadDiscovery?(view: SquadListView): void
  /** 入盟申请那一屏（B26 S8）：**只有能审核的人才拿得到这一份**，画不画由有没有行决定。 */
  allianceApplications?(view: ApplicationView): void

  /**
   * 合规公示那一屏（B06 §6「原文呈现」）。**没有这个回调时按钮不会发请求**：
   * 公示面板此前是一个从没被挂载过的组件 —— 它的组装函数吃配置行，而客户端只有类型没有数据。
   */
  gachaDisclosure?(view: GachaDisclosure): void

  /**
   * 抽取记录那一屏（B15 §三 合规三件套的第三件：最近 N 次可查）。
   * 与 `gachaDisclosure` 分开两个口：一个是事前告知，一个是事后可查，合成一个会让两种失败分不开。
   */
  gachaHistory?(view: GachaHistoryView): void
  /**
   * 任务面板（B12 §1）。**行里带 {@code heroChoices}**：首日那条主线送将任务是三选一，
   * 界面必须先让玩家选一个再领（服务端刻意不替玩家默认挑）。
   */
  quest?(resp: QuestListResp): void
  /**
   * 邮件面板（B12 §2）。列表本身是「带副作用的读」：服务端在这次读里顺手清过期。
   */
  mail?(resp: MailListResp, serverNowMs: number): void
  /** 活动列表（B17）：口子与邮件同形 —— 响应 + 服务端时刻（剩余时间由两者相减得出）。 */
  activity?(resp: ActivityListResp, serverNowMs: number): void
  /** 战报列表（B12 §3）。时刻由外层给：列表里每行都写着「N 天后过期」，那是相对时间。 */
  reports?(resp: BattleReportListResp, serverNowMs: number): void
  /** 敌情列表（B26 S19）：与战报同一块面板的第二个页签，同样由视图自己装配 */
  scoutIntel?(resp: ScoutListResp, serverNowMs: number): void
  /** 「自上次登录以来」那一屏（B25-S3）。条目为空时编排层不会调它 —— 一个空面板比不弹更糟。 */
  offlineReport?(view: OfflineReportPopup): void
  /** 汇总里点了一条：跳到那一页（key 与 PanelNav 的 key 一致）。 */
  offlineJump?(key: string): void
  /** 商店面板（B24 S-b）。整块视图由编排层组装好递过来。 */
  shop?(view: ShopView): void
  /** 外观面板（B24 块③）。同形：整块视图由编排层组装好递过来。 */
  avatarFrames?(view: AvatarFramesView): void
  /** 战令面板（B24 S-d-e）：原始响应 + 上一次领取的结果。 */
  battlePass?(data: BattlePassPanelData): void
  /** 集结面板（V02-S1）。 */
  rallies?(data: RallyPanelData): void
  /** 一场的完整战果 + 回放参数。回放怎么演由 {@code playbackOptionsOf} 装配，本类不算。 */
  reportReplay?(resp: BattleReportResp): void
  /**
   * 一键领取的回执单独递一次：它要落在「刚才那一下」的结果行上，
   * 而不是等下一次列表拉取（列表拉回来的是"领完之后"的样子，玩家看不到自己领到了什么）。
   */
  mailClaimed?(resp: MailClaimAllResp): void
  /** 领完一次活动奖励的回执（界面用它飘字「领到了什么」）。 */
  activityClaimed?(resp: ActivityClaimResp): void
  /**
   * 引导脚本（B18）。整个客户端引导层唯一的数据来源：
   * 步骤、文案、遮罩、能不能跳，全部由服务端下发，本类一个字段都不补。
   */
  guide?(resp: GuideScriptResp): void
  /** 服务端权威红点树。导航与面板只读取它，不在业务层重算。 */
  reddot?(tree: ClientReddotTree): void
  /** 失败或不能做的说明。`panel` 是分流用的面板名，不是错误码。 */
  error?(panel: string, message: string): void
  /** 家坐标（登录、进世界、迁城之后）。场景用它接「回城」按钮。 */
  home?(x: number, y: number): void
  /** 加速道具目标选择器。回调由场景层在选择后触发一次。 */
  speedupTargetChoice?(options: readonly SpeedupChoice[], onPick: (targetId: string) => void): void
  /** 军队那一行的「队列」菜单（B26 S15）：选项与"点了做什么"都由编排层给，面板只画与回抛 */
  armyQueueChoice?(options: readonly ChoiceOption[], onPick: (id: string) => void): void
  /**
   * 用哪一张训练令加速治疗（V12）。选项由编排层按 `effectKind` 筛好，面板只画与回抛 ——
   * 与 `speedupTargetChoice` 分开，是因为两者回抛的东西不同：那个回 `targetId`（给 `/item/use`），
   * 这个回 `itemId`（给 `/army/treatSpeedUp`，一次只吃一张）。
   */
  treatSpeedupChoice?(options: readonly ResearchSpeedupChoice[],
    onPick: (choice: ResearchSpeedupChoice) => void): void
  /** 宝箱那一行的「开几个」选择器。选项按手里有几个给（逐箱上限在 chest 表里，没下发）。 */
  chestOpenChoice?(options: readonly ChoiceOption[], onPick: (id: string) => void): void
  /** 一次开箱的回执：实际开了几个、开出什么、装不下的那部分转了邮件。 */
  chestOpened?(resp: OpenBatchResp): void
  /** 关卡出战阵容选择器。回调由场景层在选择后触发一次。 */
  lineupChoice?(options: readonly LineupChoice[], onPick: (choice: LineupChoice) => void): void
  /**
   * 战报分享的目标频道选择器（B22 §一 2）。与出战阵容同一个形状：
   * 选项由根给出、场景层只负责画和回调。
   */
  shareChannelChoice?(options: readonly ShareChannelChoice[], onPick: (choice: ShareChannelChoice) => void): void
  /**
   * 一次分享的结果（成功与失败都走这里）：界面在回放页顶那行显示一句话。
   * **失败也要走这里**：targets.error 只进 console，玩家看不见 —— 而"分享没成功"
   * 是玩家必须看得见的一件事（他会以为战友看到了）。
   */
  reportShared?(text: string, warning: boolean): void
}

/** 一次写操作影响的列表：成功后重拉这些面板。 */
export type PanelKey =
  'city' | 'army' | 'hero' | 'bag' | 'resources' | 'stage' | 'stamina' | 'social' | 'power' | 'world'
  | 'quest' | 'reddot' | 'mail' | 'reports' | 'activity' | 'guide' | 'shop' | 'avatarFrames'
  | 'battlePass' | 'rallies' | 'tech' | 'equip' | 'gacha'

/** 「全开」这一档的天花板：`OpenBatchReq.count` 的协议上界（逐箱上限由服务端判，超了会明确拒）。 */
const CHEST_OPEN_CEILING = 100

/** 埋点出口。只要一个 `track`，为的是单测能塞一个数组进来，而不是塞整个 TrackClient。 */export interface Tracker {
  track(name: string, params?: Record<string, string>): void
}

export class AppRoot {
  private readonly api: GameApi
  private readonly session: GameSession
  private readonly store: Store
  private readonly timeSync: TimeSync
  private readonly targets: PanelTargets
  private readonly tracker: Tracker | null
  /** 双来源红点树。服务端下发权威结论，本地来源将来仍通过同一棵树注册。 */
  private readonly reddot = new ClientReddotTree()
  /** 未绑定 tracker 时只提醒一次：每次动作都刷一行日志，等于把这条信号埋进噪音里。 */
  private warnedNoTracker = false
  /** 联盟成员 diff 的游标。由服务端每次返回的 version 推进，绝不自己加一。 */
  private memberVersion = 0
  private allianceMembers: AllianceMember[] = []
  private helpRequests: HelpRequestView[] = []

  /** 二级选择器的最近一次权威响应；不参与任何数值判断。 */
  private cityResp: CityListResp | null = null
  private armyResp: ArmyListResp | null = null
  /** 商店：当前页签与最近一次兑换的临时提示。货架来自 /shop/list 的响应，不缓存计算 */
  private shopTab: ShopCurrency = 'GOLD'
  private shopResp: ShopListResp | null = null
  private shopNotice: string | null = null
  /** 外观：最近一次框列表与上一次操作的结果。拥有/佩戴两位都取自响应，本地不改。 */
  private frameResp: AvatarFrameListResp | null = null
  private frameNotice: string | null = null
  /** 预览头像上那个字用的昵称（登录时玩家填的那个） */
  private nickName = ''
  /** 集结：最近一次列表与上一次动作的结果（临时提示）。 */
  private rallyResp: RallyListResp | null = null
  private rallyNotice: string | null = null
  /** 正在为哪一支集结编队；null = 普通出征的编成。**加入集结必须带兵力**（服务端要锁兵）。 */
  private composeRallyId: string | null = null
  /** 编成面板当前是"发起小队集结"还是"出征"（B26 S12）。换目标就回到出征 */
  private composeRally = false
  /** 这一份编成是派去侦察的（B26 S18）。与集结互斥：一条命令只有一个种类 */
  private composeScout = false
  /** 集结发给哪一层（B26 S14）。每次进集结态都从小队层起步：那是玩家已经点过的那条路 */
  private composeRallyScope: RallyScope = 'SQUAD'
  /**
   * 联盟集结那两个数（人数上限 / 等待时长）。null = 还没按政策填出来，
   * 而 null 会让确认键拦下一句人话 —— 猜一组数发出去是最坏的结果。
   */
  private rallyForm: RallyForm | null = null
  /** 集结政策（B26 S13 的读口）：两个层级的界、起始值、此刻能不能发起。没拉到是 null */
  private rallyPolicy: RallyPolicyResp | null = null
  /** 战令：最近一次状态与上一次领取的结果（临时提示）。**进度两位都取自响应，本地不改**。 */
  private battlePassResp: BattlePassStatusResp | null = null
  private battlePassNotice: string | null = null
  /** 服务端给的边界与阈值（init 响应里那一块）；登录失败时为 null。 */
  private offlineConfig: OfflineReportView | null = null
  /** 已经给玩家看过的那一批明细的指纹：同一批不再弹（明细变了 = 指纹变了，会再弹一次）。 */
  private offlineShownFingerprint: string | null = null
  /**
   * 客户端记住的「上一次成功训练」，自动续训要续的就是这一批（B25-S2d）。
   * 只在内存里：换设备后没有它，玩家重新训一批即可 —— 与「上一次出征」同一条口径（裁决②(a)）。
   */
  private lastTrain: TrainMemory | null = null
  /** 最近一次目标搜索的结果：出征要知道目标的坐标，而坐标只在下发的 brief 里（B25-S1） */
  private searchResp: SearchTargetsResp | null = null
  /** 当前正在编成的目标；没有编成时为 null */
  private composeTarget: { id: string; name: string; x: number; y: number } | null = null
  /** 编成的勾选表（unitId → 数量）。**由本层持有**：面板重画时不能丢，也不该由表现层记 */
  private composePicks: Record<string, number> = {}
  private composeNotice: string | null = null
  private composeSubmitting = false
  /** 最后一次**成功**出征的参数（裁决②(a)）；「再次出征」重发它 */
  private lastMarch: MarchSpec | null = null
  private heroResp: HeroListResp | null = null

  // ---------- 排行榜状态（B23 §一 3） ----------

  /**
   * 一屏要几条。**按面板能画几行来要**，不是按服务端上限：面板放不下 20 行时，
   * 显示 20 行里的前 8 行会让第 9~20 名永远看不到，而翻页又会跳过它们。
   * 服务端仍会把它夹在上限内（上限管的是体积预算，见 RankBoardService#effectivePageSize）。
   */
  private static readonly RANK_SCREEN_ROWS = 8

  /**
   * 服务端「你不在任何国家里」那一条错误码（`ErrorCode.NATION_NOT_FOUND`）。
   *
   * <p>**必须与网络失败严格分开**：它是面板的正常起点（`mode: 'NONE'` 那一屏给创建与可加入两条路），
   * 而网络失败要提示重试。合并处理的后果是"断网时看见一张'你还没有国家'的表单"。
   */
  private static readonly NATION_NOT_FOUND = 13000

  /**
   * 可加入国家要几条。**版式常量**（国家面板那一屏排得下 6 行，面板自己再截），
   * 候选来自国家榜而不是 `/nation/list`（服务端没有那个端点，见 {@link loadNationCandidates}）。
   */
  private static readonly NATION_CANDIDATE_ROWS = 8

  /** 当前页签。默认停在「明细」：那是这个页面原本的内容，四类榜是新加的邻居 */
  private rankTab: RankTabKey = 'DETAIL'
  /** 最近一次 `/rank/list` 的响应。**只有当前页签那一张**（切页签就换掉，不缓存多张 —— 榜是会变的） */
  private rankResp: RankListResp | null = null
  /**
   * 今日快照那一块（B23 §一 2）。**用 `/rank/list` 下发的 `dayKey` 去查**，客户端自己不算日期
   * —— 契约明写"不许出现第二个日切轴"（2026-09-22 接上，此前这一格是"有口没读"）。
   */
  private rankSnapshot: RankSnapshotView | null = null
  /** 请求的页码。由服务端回显的 `page` 推进，**不自己加一**（出界时服务端会夹到最后一页） */
  private rankPage = 1
  /** 拉榜失败的可读原因（限流/断网）；成功一次或切页签后清空 */
  private rankNotice: string | null = null
  /** 最近一次 `/season/status` 的响应与失败原因（V04-S1）。与榜同一条纪律：失败不清空上一次的 */
  private seasonResp: SeasonStatusResp | null = null
  private seasonNotice: string | null = null
  /** 最近一次 `/tech/list` 的响应与失败原因（V03-a-S1）。同样：失败只加一行理由，不清空 */
  private techResp: TechListView | null = null
  private techNotice: string | null = null
  /** 最近一次 `/equip/instances` 的响应与失败原因（V03-b-S1）。同样：失败只加一行理由，不清空 */
  private equipResp: EquipInstanceListView | null = null
  private equipNotice: string | null = null
  /** 换装目标（V03-d 第一批）：从武将行进装备库时带上的那个武将；null = 只读浏览 */
  private equipTarget: { heroId: string, heroName: string } | null = null
  /** 最近一次 `/bag/list` 的响应（V03-d）：升级弹层的候选取它，不额外发请求 */
  private bagResp: BagListResp | null = null
  /** 升级弹层的状态：给谁喂、每样选了几件。null = 没开着 */
  private expPick: { heroId: string, heroName: string, picks: Record<string, number> } | null = null
  /** 觉醒弹层的状态：给谁觉醒、选了哪块石（单选）。null = 没开着 */
  private awakenPick: { heroId: string, heroName: string, stage: AwakenStage, itemId: string | null } | null = null
  /** 技能弹层的状态：给谁升、选了哪本书（槽位由那本书决定）。null = 没开着 */
  private skillPick: { heroId: string, heroName: string, stage: SkillStage, itemId: string | null } | null = null
  /** 合成弹层的状态：选了哪个**还没拥有**的武将。null = 没开着 */
  private composePick: { heroId: string | null } | null = null
  /** 编队编辑的状态：改哪一队、三槽站着谁（编辑中，未提交）、正在选哪一槽。null = 没开着 */
  private lineupEdit: {
    presetIndex: number,
    slots: Record<LineupSlot, string | null>,
    pickingSlot: LineupSlot | null
  } | null = null
  /** 社交权限（两个 scope 各一份）。`permissionsLoaded` 只在拉过之后为 true（见 'social' 那一支的注释） */
  private permissions: PermissionState = EMPTY_PERMISSIONS
  private permissionsLoaded = false
  /** 正在飞的那一次权限拉取（并发调用共享同一份，见 loadSocialGates） */
  private gatesFlight: Promise<void> | null = null
  /** 创建小队/联盟的门槛与消耗（B26 S2），一份回两个层级；没读到就是 null（行上写「读取中」） */
  private createPolicy: SocialCreatePolicyResp | null = null
  /** 创建表单开着时的输入态（null = 没开）。打字只改这里，一条请求都不发。 */
  private creating: { scope: CreateScope, name: string, tag: string } | null = null
  /** 退出/解散里已经按下第一下的那一行（第二下才真发请求，见 `SocialExit` 的那条不对称）。 */
  private armedExit: ExitKey | null = null
  /** 转让里已经按下第一下的那个成员（B26 S4，同样两下才算数）。 */
  private armedTransfer: { scope: ExitScope, memberId: string } | null = null
  /** 可申请联盟那一屏（B26 S6）。只在"没有联盟"的时候拉，入盟的人不需要看别人家。 */
  private discovery: DiscoveryView = EMPTY_DISCOVERY
  /** 可加入小队那一屏（B26 S7）。同样只在没有小队的时候拉。 */
  private squadDiscoveryView: SquadListView = EMPTY_SQUAD_DISCOVERY
  /** 入盟申请名单（B26 S8）。没权限时恒为空 —— 这一段根本不该出现。 */
  private applications: ApplicationView = EMPTY_APPLICATIONS

  /** 玩家是否真进过社交页（决定两份发现型列表拉不拉，见 loadSocialDiscovery）。 */
  private socialVisited = false
  /** 最近一次社交摘要：发现型列表要按它决定拉哪一份（没队才拉小队列表）。 */
  private lastSocialSummary: SocialSummaryResp | null = null
  /** 最近一次 `/gacha/pools` 与 `/resource/detail`（抽卡面板比余额要，与 bagResp 同一条做法） */
  private gachaResp: GachaPoolsResp | null = null
  private resourceResp: ResourceDetailResp | null = null
  /** 最近一次 `/stamina`：买不买得动由它决定，买完之后服务端回的那一份立刻覆盖它 */
  private staminaResp: StaminaResp | null = null
  /** 抽卡面板：选中哪个池、上一次抽取的结果、上一次失败的理由 */
  private gachaPoolId: string | null = null
  private gachaLast: GachaDrawResp | null = null
  private gachaNotice: string | null = null
  /** 抽取记录那一屏：响应留一份（翻页要在本地切段），页码是"看的是哪一页"的唯一来源。 */
  private gachaHistoryResp: GachaHistoryResp | null = null
  private gachaHistory: GachaHistoryView | null = null
  private gachaHistoryPage = 0

  // ---------- 国家状态（V13-S1 · B13） ----------

  /** `GET /nation` 的结果。不在任何国家时为 null（13000，不是网络失败）。 */
  private nationResp: NationResp | null = null
  /** 国库余额与流水。读不到时为 null（与"账上没流水"是两种状态，面板要说得清不同）。 */
  private nationTreasuryResp: NationTreasuryResp | null = null
  /** 可加入的国家（来自 `GET /rank/list?type=NATION` 的 id + 名字，都是服务端下发）。 */
  private nationCandidates: NationCandidate[] = []
  /** 上一次操作的结果（成功一句 / 服务端拒绝的理由）。没有则 null。 */
  private nationNotice: string | null = null
  /** notice 的语气。**默认失败** —— 编排层每一次成功都要显式翻成 `'ok'`。 */
  private nationNoticeTone: 'ok' | 'warn' = 'warn'
  /** 国库收款人名单已拉到没有 —— 拉过就别反复发那一枪。 */
  private nationPayeesLoaded = false
  /** S2：当前页签。默认停在国库（余额与流水是这一屏最该先看见的东西）。 */
  private nationTab: NationTabKey = 'TREASURY'
  /**
   * 国战状态（`GET /nation/war`）。**「没拉到」与「没有仗」是两个值**：前者 null，
   * 后者是 `hasWar=false` 的那一份响应 —— 面板据此说不同的话（见 `buildWarSection` 的三分支）。
   */
  private nationWarResp: WarStatusResp | null = null
  /** S2：国家科技那一棵树。没拉过为 null（面板据它说"这一次没读到"，不是空白）。 */
  private nationTechResp: NationTechListView | null = null
  /** 国策轮次。没拉过为 null —— 与「拉到了但本轮没有提案」是两件事。 */
  private nationPolicyResp: NationPolicyRoundView | null = null
  /**
   * S2：外交关系表。**null = 还没打过一次交道**，不是"没有关系" ——
   * 服务端只有写口（`/nation/diplomacy`）能拿到这张表，所以第一次打开之前是空的。
   */
  private nationRelations: NationRelationView[] | null = null

  // ---------- 聊天状态（B22 §一 1） ----------

  /** 当前频道；私聊且 `chatPeerId` 为 null 时画会话列表 */
  private chatChannel: ChatChannel = 'WORLD'
  private chatPeerId: string | null = null
  /** 各频道/会话已拉到的历史，键见 `chatKey`。**只在内存里**：B10 §5 说的"本地保留 200 条"是本次会话的窗口，不落盘 */
  private readonly chatHistory = new Map<string, ChatMessageView[]>()
  /**
   * 聊天当前页（V15：0 = 最新那一页）。**住在编排层而不是视图**：
   * 往更早翻翻到本地窗口之外时要发一次请求（游标 = 手里最旧那条），
   * 而视图只会重画、不该发请求。
   */
  private chatPage = 0
  /**
   * 服务端说"还有更早的"（`/chat/list` 的 `hasMore`）。V15 之前这一位**下发了但客户端零读取** ——
   * 于是历史消息在面板里没有入口。现在它决定最旧那一页的「更早」键还亮不亮。
   */
  private chatHasMoreOlder = false
  /** 已学到的昵称（打开过会话就从消息里学到）。会话列表没有它时只能显示事件标题 */
  private readonly chatPeerNames = new Map<string, string>()
  /**
   * 未读社交事件。**推送与离线补偿共用这一份**（服务端两条路发的是同一个对象）：
   * 分开存两份就会出现"推送加过、离线又加一遍"的重复计数。
   */
  private unreadEvents: SocialEventView[] = []
  /** 上一次聊天失败的可读原因（限流/没资格）。成功一次或切频道后清空 */
  private chatNotice: string | null = null
  /** 成功发送的计数：面板靠它决定"这一次该把输入框清空了"（失败时不清，玩家要能重试） */
  private chatSentSeq = 0
  /** 我拉黑的名单（B22 §一 3）。菜单里显示"拉黑"还是"取消拉黑"要看它 */
  private myBlocked: readonly string[] = []
  /** 名单里的显示名（服务端解析好下发，见 #322）；id → 名字，只给"取消拉黑"那一行用。 */
  private myBlockedNames: ReadonlyMap<string, string> = new Map()
  /** 名单是否已经从服务端取过。懒取：首屏不必为它多打一轮请求 */
  private blocksLoaded = false
  /** 我关注的人（B22 §一 4）。与黑名单同一套懒取策略：第一次点消息菜单时才拉 */
  private myFriends: readonly FriendView[] = []
  private friendsLoaded = false

  constructor(deps: {
    api: GameApi
    session: GameSession
    store: Store
    timeSync: TimeSync
    targets?: PanelTargets
    tracker?: Tracker | null
  }) {
    this.api = deps.api
    this.session = deps.session
    this.store = deps.store
    this.timeSync = deps.timeSync
    this.targets = deps.targets ?? {}
    this.tracker = deps.tracker ?? null
  }

  /**
   * 打一个点。
   *
   * <p>tracker 是**可选**的，两件事同时成立才有意义：埋点不能成为玩法的前置（拿不到
   * `TrackPolicy` 时游戏必须还能玩），但也不能静默消失（漏斗少一环而没人知道原因，
   * 是分析侧最难查的一种事故）。所以未绑定时告警一次并继续。
   */
  private track(name: string, params: Record<string, string> = {}): void {
    if (this.tracker === null) {
      if (!this.warnedNoTracker) {
        this.warnedNoTracker = true
        console.warn('[track] 未绑定 tracker，埋点不会上报（本次会话只提醒一次）。'
          + '通常是 /ops/app/version 没拿到 TrackPolicy')
      }
      return
    }
    this.tracker.track(name, params)
  }

  // ---------- 启动 ----------

  /**
   * 登录并拉一遍首屏。
   *
   * <p>登录失败时**不**继续发请求：那些端点全都要求身份头，拿不到 playerId 就是一片 400，
   * 表现会是「一进游戏就被七八个错误弹窗糊住」。
   */
  async start(deviceId: string, nickName: string, wxCode: string | null = null): Promise<boolean> {
    this.nickName = nickName
    const outcome = await this.session.login(deviceId, nickName, wxCode)
    if (outcome.kind !== 'ok') {
      this.say('session', outcome)
      return false
    }
    this.track(TRACK_EVENTS.login, {
      playerId: outcome.data.playerId,
      mainLevel: trackParam(this.store.getState().cityLevel),
    })
    // 边界与阈值随登录一起下发（B25-S3）：previousLoginAt 是"自上次登录以来"的起点，
    // 而 profile.lastLoginAt 此刻已被推进成现在 —— 两者差一个"永远是 0 秒"的 bug
    this.offlineConfig = outcome.data.offlineReport ?? null
    await this.prefetch('city', 'army', 'hero', 'bag', 'resources', 'stage', 'stamina', 'social', 'power',
      'world', 'quest', 'reddot')
    // 首屏拉齐之后再弹「自上次登录以来」（B25-S3）：它要读城市/社交那两份已到的数据，
    // 早于首屏弹会少条目 —— 而少条目正是这个功能最容易骗人的地方
    await this.deliverOfflineReport()
    return true
  }

  /**
   * 「自上次登录以来」那一屏（B25-S3，裁决①(a)）。
   *
   * <p><b>它只做取数与投递</b>：判定（四条闸门）与条目组装全在 `game/offline/OfflineReport.ts`
   * 那份纯逻辑里，阈值来自服务端随登录下发的两个数（客户端一个都不填）。
   *
   * <p><b>战报是这一屏唯一的额外请求</b>，而且只在"过了时长门槛"时才发 ——
   * 几分钟内切号重连的人不该为一份不会弹的汇总多打一次接口。拉不到就不列那一条
   * （留痕但不上报错），因为把登录后的第一屏变成错误弹窗，比少说一场仗糟得多。
   */
  private async deliverOfflineReport(): Promise<void> {
    const boundary = this.offlineConfig
    if (boundary === null || boundary.previousLoginAt === null) {
      return   // 新号（或老服务端）：没有「上一次」可言，这一屏没有起点
    }
    const idleMinutes = Math.floor((this.timeSync.serverNow() - boundary.previousLoginAt) / 60_000)
    if (idleMinutes < boundary.minIdleMinutes) {
      return   // 离得太近：连战报都不拉，免得为一份不会弹的汇总多打一次接口
    }
    let reports: readonly BattleReportBrief[] = []
    const outcome = await this.api.battleReports()
    if (outcome.kind === 'ok') {
      reports = outcome.data.reports
    } else {
      console.warn('[offline] 战报拉不到，这一屏会少一条明细', outcome.kind)
    }
    const serverNow = this.timeSync.serverNow()
    const items = buildOfflineItems({
      offlineReport: boundary,
      serverNow,
      resources: this.cityResp?.resources ?? {},
      buildings: this.cityResp?.buildings ?? [],
      reports,
      events: this.unreadEvents,
    })
    const gate = offlineReportGate(boundary, items, serverNow, this.offlineShownFingerprint)
    if (!gate.show) {
      console.log(`[offline] 这一屏不弹：${gate.reason ?? ''}`)
      return
    }
    this.offlineShownFingerprint = gate.fingerprint
    this.track(TRACK_EVENTS.offlineReport, { items: trackParam(items.length) })
    this.targets.offlineReport?.({ items })
  }

  /**
   * 点汇总里的一条：跳到那一页。
   *
   * <p>跳转由场景层执行（导航条在那边），这里只把意图转出去并记一次 ——
   * 看板据此能回答"哪一类汇总最常被点开"，那正是这个功能值不值得继续投的方向。
   */
  offlineReportJump(jump: string): void {
    this.track(TRACK_EVENTS.offlineReportJump, { target: jump })
    this.targets.offlineJump?.(jump)
  }

  /**
   * 首屏预拉：**并发**取回这一批面板。
   *
   * <p><b>为什么与 {@link refresh} 分成两个方法</b>：首屏要这批全部到齐才算「可交互」，
   * 而串行时墙钟是它们之和 —— 实测（无头桌面）这一串占掉约 1.8 秒，把可交互时刻推到 3.9 秒，
   * 越过 `PERF_FIRST_SCREEN_MAX_MS` 的 3 秒线。那个参数自己的口径写的是「首屏**可交互**」
   * 而不是「首屏可见」（`why` 里那句「能看见但不能点的 3 秒，体感和白屏没有区别」），
   * 所以该被压的是这条串行链。面板之间没有先后依赖，各自写各自的 target，
   * 串行唯一的产出是一个没有任何调用方在读的顺序。
   *
   * <p>点完按钮之后的重拉仍走 {@link refresh} 的串行：那里一次只有两三个键，
   * 而且已有断言盯着它们的落地顺序（例如「先落社交再刷红点」），改它没有收益、只有回归面。
   *
   * <p>隔离性与串行一致：用 allSettled，一个面板失败不会把其它面板拖住。
   */
  private async prefetch(...keys: PanelKey[]): Promise<void> {
    await Promise.allSettled(keys.map(key => this.refreshOne(key)))
  }

  /** 逐个面板拉取。单个失败只让那个面板显示原因，不牵连其它面板。 */
  async refresh(...keys: PanelKey[]): Promise<void> {
    for (const key of keys) {
      await this.refreshOne(key)
    }
  }

  private async refreshOne(key: PanelKey): Promise<void> {
    const offsetMs = this.timeSync.offsetMs()
    switch (key) {
      case 'city':
        this.deliver('city', await this.api.cityList(), r => {
          this.cityResp = r
          this.targets.city?.(r, offsetMs)
        })
        return
      case 'army':
        this.deliver('army', await this.api.armyList(), r => {
          this.armyResp = r
          this.targets.army?.(r, offsetMs, this.lastTrain)
        })
        return
      case 'hero':
        this.deliver('hero', await this.api.heroList(), r => {
          this.heroResp = r
          this.targets.hero?.(r)
        })
        return
      case 'bag':
        this.deliver('bag', await this.api.bagList(), r => {
          // 存一份给升级弹层用（V03-d）：它按 effectKind 筛候选，不额外发请求
          this.bagResp = r
          this.targets.bag?.(r)
        })
        return
      case 'resources':
        this.deliver('resources', await this.api.resourceDetail(), r => {
          // 存一份给抽卡面板比余额（按资源计价的池）：与 bagResp 同一条做法，不额外发请求
          this.resourceResp = r
          this.targets.resources?.(r)
        })
        return
      case 'stage':
        this.deliver('stage', await this.api.stageList(), r => this.targets.stage?.(r))
        return
      case 'stamina':
        // `GET /stamina` 是个会写库的读（惰性恢复与容量随主城等级变化都在这里推进），
        // 所以它必须与 `/stage/list` 分开拉：关卡列表只带 current，不带 cap / 价格 / 今日已购。
        this.deliver('stamina', await this.api.staminaView(), r => {
          this.staminaResp = r
          this.targets.stamina?.(r, this.goldBalance())
        })
        return
      case 'social': {
        const summary = await this.api.socialSummary()
        if (summary.kind !== 'ok') {
          this.say('social', summary)
          return
        }
        const degraded: string[] = []
        // 成员走 diff 通道（B10 验收 10）：首次 version=0 拿全量，之后带上服务端给的版本号
        // 只取变化的那几个。每次全量拉 150 人既是浪费，也让"谁刚刚变了"这件事看不出来。
        //
        // **没入盟时这一问根本不该发**：服务端会回 10010「联盟不存在或已解散」，那是
        // 「本来就没有」而不是「暂时拉不到」。当成可重试的降级，玩家每次刷新都会看到一句
        // 永远等不到结果的话，而这一次请求每次刷新也白发。摘要里的 alliance 为 null
        // 就是权威答案 —— 判定仍在服务端，客户端只是不再对一个已知为空的状态发起查询。
        if (summary.data.alliance === null) {
          this.clearMemberSync()
        } else {
          const sync = await this.api.allianceSync({ version: this.memberVersion, wantMembers: true })
          if (sync.kind === 'ok') {
            this.applyMemberDiff(sync.data)
          } else {
            degraded.push('成员列表暂时拉不到')
          }
        }
        // 互助列表与徽标由服务端同一次遍历给出，客户端只转手，不自己数
        const help = await this.api.socialHelpList()
        if (help.kind === 'ok') {
          this.helpRequests = help.data.requests
        } else {
          this.helpRequests = []
          degraded.push('互助列表暂时拉不到')
        }
        // 面板照常落地：摘要本身是好的。让整块不显示等于把"网络抖了一下"
        // 升级成"我好像没进联盟"，而玩家会去做一件本来不必要的事（重新申请）
        // 同一份摘要也是聊天页签的未读账本（不另发一次请求）：两处读同一份数据，
        // 就不会出现"事件页签说 3 条、聊天徽标说 2 条"
        this.unreadEvents = summary.data.events
        this.lastSocialSummary = summary.data
        // 两份发现型列表（可申请联盟 B26 S6 / 可加入小队 B26 S7）都**不在这里拉**：
        // 这一支是首屏预取批次的一员，而「没队又没盟」恰好是新号的默认状态 ——
        // 挂在这里等于给每一个新玩家的首屏多加两条他还没点开的请求（实测：那条预算门从 14 变 15）。
        // 只在玩家真进过社交页之后，才跟着社交页一起刷（与上面的权限门同一条纪律）。
        if (this.socialVisited) {
          await this.loadSocialDiscovery()
        } else {
          this.deliverDiscovery(EMPTY_DISCOVERY, EMPTY_SQUAD_DISCOVERY)
        }
        this.targets.social?.(summary.data, this.helpRequests, this.allianceMembers, offsetMs)
        this.deliverChat()
        // 权限两份：只在**玩家真进过社交页之后**才跟着社交页一起刷。
        // 放进首屏预拉会挤那 3 秒可交互预算（与邮件/商店/外观同一条纪律）；
        // 而一旦拉过，之后的每次 `refresh('social')`（踢人、捐献、退盟之后）都顺手刷新 ——
        // 职位变了权限就变了，缓存会让"刚刚被降职的人还能看到能点的按钮"。
        if (this.permissionsLoaded) {
          await this.loadSocialGates()
        }
        if (degraded.length > 0) {
          this.targets.error?.('social', `${degraded.join('、')}，稍后会自动重试`)
        }
        return
      }
      case 'power':
        this.deliver('power', await this.api.playerPower(), r => this.targets.power?.(r))
        // 榜单与明细同一个面板：打开战力页时，顺手把当前页签（若是一张榜）拉回来。
        // 明细页签不需要请求 —— 但那时也不该显示上一次留下的榜，所以照发一次视图（空榜）
        await this.loadRankIfBoard()
        return
      case 'tech':
        await this.loadTech()
        return
      case 'equip':
        await this.loadEquip()
        return
      case 'quest':
        this.deliver('quest', await this.api.questList(), r => this.targets.quest?.(r))
        return
      case 'reports': {
        // 战报与敌情同一次刷新一起拉（B26 S19）：它们是同一块面板的两个页签，
        // 分两次进就会让玩家切页签时看到「读取中」
        const [battles, scouts] = await Promise.all([this.api.battleReports(), this.api.scoutReports()])
        this.deliver('reports', battles, r => this.targets.reports?.(r, this.timeSync.serverNow()))
        this.deliver('reports', scouts, r => this.targets.scoutIntel?.(r, this.timeSync.serverNow()))
        return
      }
      case 'shop':
        this.deliver('shop', await this.api.shopList(this.shopTab), r => {
          this.shopResp = r
          this.deliverShop()
        })
        return
      case 'gacha':
        this.deliver('gacha', await this.api.gachaPools(), r => {
          this.gachaResp = r
          this.deliverGacha()
        })
        return
      case 'rallies':
        this.deliver('rallies', await this.api.rallyList(), r => {
          this.rallyResp = r
          this.deliverRallies()
        })
        return
      case 'battlePass':
        this.deliver('battlePass', await this.api.battlePassStatus(), r => {
          this.battlePassResp = r
          this.deliverBattlePass()
        })
        return
      case 'avatarFrames':
        this.deliver('avatarFrames', await this.api.playerFrames(), r => {
          this.frameResp = r
          this.deliverAvatarFrames()
        })
        return
      case 'mail':
        this.deliver('mail', await this.api.mailList(),
          r => this.targets.mail?.(r, this.timeSync.serverNow()))
        return
      case 'activity':
        // 用响应自带的 serverNow 而不是本地时钟：剩余时间是这两个服务端时刻相减（铁律 5）
        this.deliver('activity', await this.api.activityList(),
          r => this.targets.activity?.(r, r.serverNow))
        return
      case 'guide':
        // 引导只在登录后拉一次（它是"新号前 5 分钟"的东西，不该每次切面板都发一遍请求）
        this.deliver('guide', await this.api.guideScript(), r => this.targets.guide?.(r))
        return
      case 'reddot':
        this.deliver('reddot', await this.api.socialReddot(), r => {
          this.reddot.applyServer(r.nodes)
          this.targets.reddot?.(this.reddot)
        })
        return
      case 'world':
        this.deliver('world', await this.api.enterWorld(),
          r => this.targets.home?.(r.home.x, r.home.y))
    }
  }

  /**
   * 未入盟与退盟走同一条：成员清空、游标归零。
   *
   * <p>游标必须一起清：留着上一个联盟的版本号，入新盟后的第一次 diff 会拿着
   * 一个对端从没发过的游标去要增量，表现是成员列表缺一截而请求全都成功。
   */
  private clearMemberSync(): void {
    this.memberVersion = 0
    this.allianceMembers = []
  }

  /**
   * 合并一次成员 diff。
   *
   * <p>游标只能用服务端返回的 `version`，不能本地 +1：成员变动与踢人是并发发生的，
   * 版本号是服务端那份账的游标。自己加一个数就会与对端错位，表现是
   * "某人早就退盟了却一直挂在列表里"或"新成员永远进不来"，而两边都有日志、都没报错。
   */
  private applyMemberDiff(resp: AllianceSyncResp): void {
    if (resp.changedMembers.length > 0 || resp.removedMemberIds.length > 0) {
      const byId = new Map(this.allianceMembers.map(m => [m.id, m]))
      for (const id of resp.removedMemberIds) {
        byId.delete(id)
      }
      for (const member of resp.changedMembers) {
        byId.set(member.id, member)
      }
            this.allianceMembers = Array.from(byId.values())
    }
    this.memberVersion = resp.version
  }

  // ---------- 城建 ----------

  /**
   * 升级。生成物把 `gridX` / `gridY`（原地升级时为空）声明成**必填但可空**，
   * 所以这里必须写 null —— 这不是样板，而是「漏参数会在编译期报错」这条契约的正常样子。
   */
  upgradeBuilding(configId: string, gridX: number | null = null,
                  gridY: number | null = null): Promise<void> {
    const params = gridX === null || gridY === null
      ? { buildingId: configId }
      : { buildingId: configId, gridX: String(gridX), gridY: String(gridY) }
    this.track(TRACK_EVENTS.buildingUpgradeStart, params)
    return this.write('city', this.api.cityUpgrade({ configId, gridX, gridY }),
      ['city', 'power', 'reddot'])
  }

  /** `itemId: null` = 用金币加速而不是用加速道具。 */
  speedUpBuilding(buildingId: string, source: SpeedUpSource): Promise<void> {
    this.track(TRACK_EVENTS.speedupUsed, { target: buildingId, source })
    return this.write('city', this.api.citySpeedUp({ buildingId, source, itemId: null }),
      ['city', 'reddot'])
  }

  /** 军队：加速正在训练的那一批（B05）。`seconds` 与 `itemId` 由服务端按来源裁定，客户端不自己算时长。 */
  speedUpTraining(unitId: string): Promise<void> {
    this.track(TRACK_EVENTS.speedupUsed, { target: unitId, source: 'TRAIN' })
    return this.write('army', this.api.armySpeedUp({ unitId, seconds: null, itemId: null }),
      ['army', 'reddot'])
  }

  /**
   * 军队：取消正在训练的那一批（B05），按规格返还一部分资源。
   *
   * <p>刷新里带 resources：退的资源要让玩家立刻看见（与城建那边的取消同一条口径）。
   */
  cancelTraining(unitId: string): Promise<void> {
    this.track(TRACK_EVENTS.armyTrain, { unitId, action: 'cancel' })
    return this.write('army', this.api.armyCancel({ unitId, seconds: null, itemId: null }),
      ['army', 'resources', 'reddot'])
  }

  /**
   * 收取治好的伤兵（`/army/collectTreated`）。
   *
   * <p>请求体只有 `requestId`（`TreatReq`）—— 治疗是**全局一批**，不按兵种，所以这里没有 unitId。
   * 军队四格里它是唯一一只"纯接线"的：另外三只各有前置（训练两只要上阵武将，加速治疗要道具选择器）。
   */
  collectTreated(): Promise<void> {
    this.track(TRACK_EVENTS.armyTreat, { action: 'collect' })
    return this.write('army', this.api.armyCollectTreated({}), ['army', 'reddot'])
  }

  /**
   * 军队：加速正在治疗的那一批（V12，`/army/treatSpeedUp`）。
   *
   * <p><b>请求体只有 `{requestId, itemId}`</b>（`ArmyTreatSpeedUpReq`）—— 治疗是**全局一批**，
   * 没有 unitId / targetId。`itemId` 必须是 `REDUCE_TRAIN_SECONDS` 那一类（治疗与训练共用，
   * 服务端原话："两者的语义完全相同"），候选取 `buildTrainSpeedupChoices`。
   *
   * <p><b>一次只吃一张</b>（服务端扣 1 个并把它减掉的秒数回在回执里），与研究的 `count` 不同 ——
   * 所以这里没有档位可挑，选择器只回答"用哪一种训练令"。
   */
  armyTreatSpeedUp(itemId: string): Promise<void> {
    this.track(TRACK_EVENTS.speedupUsed, { target: 'hospital', source: 'TREAT' })
    return this.write('army', this.api.armyTreatSpeedUp({ itemId }), ['army', 'bag', 'reddot'])
  }

  /**
   * 玩家点了「加速治疗」：先问用哪一张训练令，手里一张都没有就明说。
   *
   * <p>与 `requestResearchSpeedUp` 同一条纪律：**不给一颗点开只会失败的键** ——
   * "没在治疗"与"没有可用道具"都在这里拦下来并给人话，而不是让玩家按下去收一个错误码。
   * 判"在不在治疗"读的是服务端下发的 `hospital.treating`，客户端不自己算倒计时。
   */
  requestTreatSpeedUp(): void {
    const hospital = this.armyResp?.hospital
    if (hospital === null || hospital === undefined || hospital.treating !== true) {
      this.rejectNeeds('army', '现在没有在治疗的伤兵，用不了加速')
      return
    }
    if (this.bagResp === null) {
      // "还没读到"与"手里没有"是两句话（与研究加速同一条口径）
      this.rejectNeeds('army', '道具清单还没读到，稍后再试')
      return
    }
    const options = buildTrainSpeedupChoices(this.bagResp)
    if (options.length === 0) {
      this.rejectNeeds('army', '手里没有训练令（建造令与研究令用不到治疗上）')
      return
    }
    if (this.targets.treatSpeedupChoice === undefined) {
      this.rejectNeeds('army', pickUnavailable('加速道具'))
      return
    }
    this.targets.treatSpeedupChoice(options, (picked) => {
      void this.armyTreatSpeedUp(picked.itemId)
    })
  }

  /**
   * 批量开箱（B04 验收 3）。`count` 是持有数量，这里按**协议天花板 100** 夹一次；
   * 逐箱的实际上限（`chest.maxBatchCount`）由服务端再取小 —— 客户端不查表（铁律 2）。
   */
  openChestBatch(itemId: string, count: number): Promise<void> {
    const capped = Math.max(1, Math.min(100, Math.floor(count)))
    this.track(TRACK_EVENTS.itemUse, { itemId, batch: trackParam(capped) })
    return this.write('bag', this.api.itemOpenBatch({ itemId, count: capped }), ['bag', 'reddot'])
  }

  /**
   * 打开体力详情（B09 §5）：拉 `/stamina`，把读数翻成弹层要画的那一帧。
   *
   * <p>金币余额取自**资源条那一份**（`cityResp`）—— 它只用来决定买体力按钮置不置灰，
   * 真正能不能买仍然由服务端裁定（铁律 2）。
   */
  openStaminaDetail(): Promise<void> {
    this.track(TRACK_EVENTS.staminaView)
    return this.write('city', this.api.staminaView(), [], r => {
      this.targets.staminaDetail?.(buildStaminaDetail(r, this.goldOf()))
    })
  }

  /**
   * 买一次体力（`POST /stamina/buy`）。
   *
   * <p>**不叠二次确认**：价格就印在按钮上、弹层本身就是确认面，再叠一层只是噪音
   * （与"解散组织"那类不可逆且没把代价写在按钮上的动作不同）。
   * 买完**重新拉一次详情**再画：次数、下一次价格、余额都会变，靠本地推算会与服务端分家。
   */

  /** 当前金币（资源条那一份；读不到就当 0 —— 那只会让按钮置灰，不会让判定失真）。 */
  private goldOf(): number {
    return this.cityResp?.resources?.GOLD?.current ?? 0
  }

  /** 顶栏的「一键收割」= `buildingId: null`，由服务端裁定收哪些；具体行则收那一格。 */
  collect(buildingId: string | null): Promise<void> {
    this.track(TRACK_EVENTS.gatherCollect, { buildingId: trackParam(buildingId), all: trackParam(buildingId === null) })
    return this.write('city', this.api.cityCollect({ buildingId }),
      ['city', 'resources', 'reddot'], r => this.targets.cityCollect?.(r))
  }

  /**
   * 离开世界地图时的收尾（`GameApi.leaveWorld`：解绑 world requester + 清 `worldReady`）。
   *
   * <p>由 `GameBootstrap` 在"从 world 切到别的面板"时调用（2026-09-22 之前这个方法**一处调用都没有**）。
   * **不是玩家可见功能**：它的作用是"绑了要解"——别把世界那套 requester 一直挂在适配层上，
   * 并让下次进场重新初始化世界。放在编排层而不是视图里，是因为它改的是会话级状态。
   */
  leaveWorld(): void {
    // 埋点是门禁要求的（`check-track-coverage`：每个面板动作都要有上报点），
    // 也确实有读法：它是"世界地图这一屏的会话有多长"的唯一信号（与 march_send 分开，别让它冲淡出征率）
    this.track(TRACK_EVENTS.worldLeave)
    this.api.leaveWorld()
  }

  /**
   * 取消一格建造。按钮只在服务端说"这一格在升级"时才存在（`BuildingRow.upgrading`），
   * 所以这里不再自己判一遍该不该让按 —— 但**列表还没到手时不能放行**，
   * 那等于把一次读侧故障变成一次注定失败的写请求。
   */
  cancelBuild(buildingId: string): Promise<void> {
    const row = this.cityResp?.buildings.find((b) => b.id === buildingId)
    if (row === undefined) {
      this.rejectNeeds('city', '这一格的状态还没读到，稍后再试')
      return Promise.resolve()
    }
    if (row.status !== 'UPGRADING') {
      this.rejectNeeds('city', '这一格现在没有在建，不用取消')
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.buildingCancel, { buildingId })
    return this.write('city', this.api.cityCancel({ buildingId }),
      ['city', 'resources', 'reddot'], r => this.targets.cityCancelled?.(r))
  }

  // ---------- 武将养成（V03 前置：把已有的养成能力接到玩家手上） ----------

  /**
   * 暂停一栋正在升级的建筑（B03 §2："队列中可暂停 / 取消"，收口清单 #324）。
   *
   * <p>与升级/收割同一套路：**只发请求、按服务端回执刷新**，客户端不自己改本地那一行 ——
   * 暂停要冻的是服务端的剩余时间，本地改状态只会让两边不一致（面板显示的"已暂停"必须来自服务端）。
   */
  pauseBuilding(buildingId: string): Promise<void> {
    this.track(TRACK_EVENTS.buildingUpgradeStart, { target: buildingId, action: 'pause' })
    return this.write('city', this.api.cityPause({ buildingId }), ['city', 'reddot'])
  }

  /** 恢复一栋已暂停的建筑：服务端会把暂停的那段时间还给这栋楼。 */
  resumeBuilding(buildingId: string): Promise<void> {
    this.track(TRACK_EVENTS.buildingUpgradeStart, { target: buildingId, action: 'resume' })
    return this.write('city', this.api.cityResume({ buildingId }), ['city', 'reddot'])
  }

  /**
   * 取消升级，按 B03 §2 返还 60% 资源（这一半规格原先也只有服务端）。
   *
   * <p>刷新里**必须带 resources**：取消会真的把资源退回来，不刷资源玩家会以为白扣了。
   * 与暂停一样，客户端不自己算返还额 —— 退多少由服务端算好，界面照着刷新即可。
   */
  cancelBuilding(buildingId: string): Promise<void> {
    this.track(TRACK_EVENTS.buildingUpgradeStart, { target: buildingId, action: 'cancel' })
    return this.write('city', this.api.cityCancel({ buildingId }), ['city', 'resources', 'reddot'])
  }

  // ---------- 武将养成（V03 前置：把已有的养成能力接到玩家手上） ----------
  /**
   * 武将升星。**只带 heroId** —— 六条养成接口里只有升星与碎片合成不需要先选道具/技能槽，
   * 所以这两个能从武将行直接点出去；升级（要喂经验道具）、觉醒与技能（要选道具）、装备（要选装备）
   * 各自的"选择输入"是独立的一格（现在点它们仍只打日志，见 `GameBootstrap` 的派发）。
   *
   * 刷新 `hero` 与 `bag`：升星吃的是碎片（在背包里），不刷背包玩家会看到碎片没扣。
   */
  heroStarUp(heroId: string): Promise<void> {
    this.track(TRACK_EVENTS.heroStarUp, { heroId: trackParam(heroId) })
    return this.write('hero', this.api.heroStarUp({ heroId }), ['hero', 'bag'])
  }

  // ---------- 军队 ----------

  train(unitId: string, count: number): Promise<void> {
    this.track(TRACK_EVENTS.armyTrain, { unitId, count: trackParam(count) })
    return this.write('army', this.api.armyTrain({ unitId, count }), ['army'], () => {
      // 只记**成功**的那一批：被拒的训练（兵营没到级、队列满）不该成为"要续的那一批"，
      // 否则玩家之后开的自动续训会一直盯着一支永远排不出来的兵种
      this.lastTrain = rememberTrain(unitId, count)
    })
  }

  /**
   * 打开某一行的「队列」菜单（B26 S15）。
   *
   * <p>没有可做的动作时回一句人话，**不画一颗空菜单**：点了什么都不发生的键，
   * 在玩家眼里就是"这功能坏了"（与 `useItem` 那条"没有可用目标就明说"同一口径）。
   */
  openArmyQueue(unitId: string): void {
    if (this.armyResp === null) {
      this.rejectNeeds('army', '军队数据还没到，稍后再试')
      return
    }
    const options = buildArmyQueueChoices(this.armyResp, unitId)
    if (options.length === 0) {
      this.rejectNeeds('army', '这一口没有在训练的队伍')
      return
    }
    if (this.targets.armyQueueChoice === undefined) {
      this.rejectNeeds('army', pickUnavailable('取消哪一口训练'))
      return
    }
    this.targets.armyQueueChoice(options, (id) => {
      if (id === 'CANCEL_TRAIN') {
        void this.cancelTrain(unitId)
      }
    })
  }

  /** 取消某一口的训练。退多少由服务端按比例算（配置来的），客户端不参与计算也不猜。 */
  cancelTrain(unitId: string): Promise<void> {
    this.track(TRACK_EVENTS.armyTrainCancel, { unitId })
    return this.write('army', this.api.armyCancel({ unitId, seconds: null, itemId: null }),
      ['army', 'resources'])
  }

  /**
   * 开关自动续训 / 自动补兵（B25-S2d）。
   *
   * <p><b>续的是哪一批</b>由 {@link lastTrain} 决定 —— 服务端保存的是一份策略，
   * 而"哪一批值得重复"只有玩家的上一手操作知道（裁决③(a) 把策略放服务端，
   * 裁决②(a) 把"上一次"放客户端）。没训过就没有可续的那一批：明确说清，不发一个必然被拒的请求。
   *
   * <p>关掉只发 `enabled:false`（契约明写不必再报一遍目标）—— 也因此关得掉这件事不依赖任何本地记忆。
   */
  toggleAutoTrain(): Promise<void> {
    const policy = this.armyResp?.autoTrain ?? null
    if (policy === null) {
      this.rejectNeeds('army', '军队数据还没到，稍后再试')
      return Promise.resolve()
    }
    if (!policy.enabled) {
      const blocked = autoTrainBlockedReason(this.lastTrain)
      if (blocked !== null) {
        this.rejectNeeds('army', blocked)
        return Promise.resolve()
      }
    }
    this.track(TRACK_EVENTS.autoTrain, { on: trackParam(!policy.enabled) })
    return this.write('army', this.api.armyAutoTrain(autoTrainRequest(policy, this.lastTrain)), ['army'])
  }

  /** 治哪些伤兵由服务端裁定（面板只给一个「治疗伤兵」按钮），所以这里不挑。 */
  treatWounded(): Promise<void> {
    this.track(TRACK_EVENTS.armyTreat)
    return this.write('army', this.api.armyTreat({}), ['army'])
  }

  // ---------- 背包 ----------

  /**
   * 使用道具。加速类（`needsTarget`）先要一个目标选择器，而它还没有 ——
   * 明说比替玩家挑一个目标好：猜错目标消耗掉的是真金白银买来的道具，且不会有任何报错。
   *
   * <p>宝箱类走另一条路：服务端对宝箱的 `/item/use` **直接拒绝**并写明"请走 /item/openBatch"
   * （它的产出可能是道具或武将碎片，不是资源），所以这一按过去只会拿到一句报错。
   */
  useItem(itemId: string, needsTarget: boolean,
          targetId: string | null = null): Promise<void> {
    const held = this.bagResp?.items.find((it) => it.itemId === itemId)
    if (held !== undefined && held.type === 'CHEST') {
      this.track(TRACK_EVENTS.itemUse, { itemId, blocked: 'chest_picker' })
      const options = buildChestOpenChoices(held.count)
      if (options.length === 0) {
        this.rejectNeeds('bag', '手里这一种宝箱已经没有了')
        return Promise.resolve()
      }
      if (this.targets.chestOpenChoice === undefined) {
        this.rejectNeeds('bag', pickUnavailable('开几个'))
        return Promise.resolve()
      }
      this.targets.chestOpenChoice(options, (picked) => {
        void this.openChest(itemId, picked === 'all'
          ? Math.min(held.count, CHEST_OPEN_CEILING) : Number(picked))
      })
      return Promise.resolve()
    }
    if (needsTarget && targetId === null) {
      this.track(TRACK_EVENTS.itemUse, { itemId, blocked: 'picker' })
      const options = buildSpeedupChoices(this.cityResp, this.armyResp)
      if (options.length === 0) {
        this.rejectNeeds('bag', '当前没有正在升级或训练的队列，加速道具没有可用目标')
        return Promise.resolve()
      }
      if (this.targets.speedupTargetChoice === undefined) {
        this.rejectNeeds('bag', pickUnavailable('加速的队列'))
        return Promise.resolve()
      }
      this.targets.speedupTargetChoice(options, (picked) => {
        void this.useItem(itemId, false, picked)
      })
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.itemUse, { itemId, blocked: 'false' })
    return this.write('bag', this.api.itemUse({ itemId, count: 1, targetId }),
      ['bag', 'city', 'army', 'reddot'])
  }

  /**
   * 开 N 个宝箱。随机、逐箱上限、溢出转邮件全在服务端（B04 禁止项：不得在客户端本地开箱），
   * 本层只发一次请求、把回执交给面板，并重拉那四本账（背包 / 资源 / 武将碎片 / 红点）。
   *
   * <p>**不重拉邮件**：邮件本来就不在首屏预拉里（点开那一格才拉），溢出那件事由红点那条通路
   * 告诉玩家就够了 —— 为一件玩家可能根本不看的事多塞一次首屏预算外的请求，是本仓库反复避的那种浪费。
   */
  openChest(itemId: string, count: number): Promise<void> {
    this.track(TRACK_EVENTS.chestOpen, { itemId, count: trackParam(count) })
    return this.write('bag', this.api.itemOpenBatch({ itemId, count }),
      ['bag', 'resources', 'hero', 'reddot'],
      (resp) => this.targets.chestOpened?.(resp))
  }

  // ---------- 关卡 ----------

  /** 先选一套已编成的阵容，再把当前全部可用兵力交给服务端裁定。 */
  challenge(stageId: string): void {
    const options = buildLineupChoices(this.heroResp, this.armyResp)
    if (options.length === 0) {
      this.track(TRACK_EVENTS.battleStart, { battleType: 'stage', stageId, blocked: 'lineup_empty' })
      this.rejectNeeds('stage', '没有已编成的阵容，请先在武将面板设置主将')
      return
    }
    const withTroops = options.filter((option) => option.units.length > 0)
    if (withTroops.length === 0) {
      this.track(TRACK_EVENTS.battleStart, { battleType: 'stage', stageId, blocked: 'troops_empty' })
      this.rejectNeeds('stage', '没有可出战兵力，请先训练士兵')
      return
    }
    if (this.targets.lineupChoice === undefined) {
      this.track(TRACK_EVENTS.battleStart, { battleType: 'stage', stageId, blocked: 'lineup_picker' })
      this.rejectNeeds('stage', pickUnavailable('出战阵容'))
      return
    }
    this.track(TRACK_EVENTS.battleStart, { battleType: 'stage', stageId, blocked: 'lineup_picker' })
    this.targets.lineupChoice(withTroops, (choice) => {
      void this.submitChallenge(stageId, choice)
    })
  }

  private submitChallenge(stageId: string, choice: LineupChoice): Promise<void> {
    this.track(TRACK_EVENTS.battleStart, {
      battleType: 'stage',
      stageId,
      heroes: choice.heroes.join(','),
      units: String(choice.units.length),
      blocked: 'false',
    })
    return this.write('stage', this.api.stageChallenge({
      stageId,
      units: Array.from(choice.units),
      heroes: Array.from(choice.heroes),
    }), ['stage', 'army', 'hero'], (resp) => this.targets.challengeResult?.(resp))
  }

  /** ×10 只发**一个** `count=10` 的请求（B09 验收 9：一次请求做完一件事，弱网下不会只成一半）。 */
  sweep(stageId: string, count: number): Promise<void> {
    this.track(TRACK_EVENTS.battleStart, { battleType: 'sweep', stageId, count: trackParam(count) })
    return this.write('stage', this.api.stageSweep({ stageId, count }), ['stage'],
      (resp) => this.targets.sweepResult?.(resp, count))
  }

  // ---------- 体力（B09 §5） ----------

  /**
   * 买一次体力。买不买得动、扣多少金币、实际到账多少全由服务端裁定，本层只做协议
   * 明确要求客户端做的两件事：① 买不动时把原因说在按下去<b>之前</b>（体力已满还要买是
   * 「到账 0、金币照扣、溢出永久损失」，协议注释点名要客户端先提示）；② 买完把响应里
   * 的 {@code granted / costGold / boughtToday} 照实念出来，不自己算。
   */
  buyStamina(): Promise<void> {
    const resp = this.staminaResp
    if (resp === null) {
      // 「还没读到」与「买不了」是两件事：把读侧故障写成玩家没金币，他会去做一件不必要的事
      this.rejectNeeds('stage', '体力信息还没读到，稍后再试')
      return Promise.resolve()
    }
    const board = buildStaminaBoard(resp, this.goldBalance())
    if (board.buyBlocked) {
      this.rejectNeeds('stage', board.buyBlockedReason ?? '现在买不了')
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.staminaBuy, { priceGold: trackParam(resp.buyCostGold) })
    return this.write('stage', this.api.staminaBuy({ times: 1 }), ['stamina', 'resources', 'stage'],
      (outcome) => this.targets.staminaBought?.(outcome))
  }

  /** 金币余额：资源明细里那一行。读不到返回 null，<b>不当 0 用</b>（那会把有钱的玩家灰掉）。 */
  private goldBalance(): number | null {
    // `?? []` 与 `gachaBalances` 同一条：协议类型写着 resources 必有，但一份缺字段的响应
    // 不该把整个体力那条炸掉（实测：桩里给的是 entries 时这里直接 TypeError）
    const row = (this.resourceResp?.resources ?? []).find((r) => r.type === 'GOLD')
    return row === undefined ? null : row.current
  }

  // ---------- 任务（B12 §1） ----------

  /**
   * 领取一条任务的奖励。
   *
   * <p><b>{@code heroChoice} 必须由界面在「三选一」弹窗里选出来再传</b>：
   * 带候选的任务不选就领会被服务端拒（它刻意不替玩家默认挑一个 —— 那会让三选一变成系统内定）。
   * 没有候选的任务传 null（多传也会被拒），所以这里的判断只有一处：
   * {@code heroChoice === null ? … : …}，而「这个任务有没有候选」由服务端的
   * {@code QuestView.heroChoices} 决定，不在客户端另判。
   *
   * <p>成功之后重拉任务面板与武将面板：前者是「已领取」状态，后者是因为整卡武将会进册
   * （`/hero/list` 才是武将册的权威读法，奖励回执只说明发了什么）。
   */
  claimQuest(questId: string, heroChoice: string | null): Promise<void> {
    this.track(TRACK_EVENTS.questClaim, {
      questId,
      needsChoice: trackParam(heroChoice !== null),
      heroId: trackParam(heroChoice),
    })
    return this.write('quest', this.api.questClaim({ questId, heroChoice }),
      ['quest', 'hero'])
  }

  // ---------- 新手引导（B18） ----------

  /**
   * 上报引导的一步（`COMPLETE` 是"请检查我"，`SKIP` 是可跳的那几步）。
   *
   * <p><b>不重拉任何面板</b>：位置只按回执里的 `nextStepIndex` 交给 `onAdvanced`，
   * 服务端说没达成（`advanced=false`）时引导就留在原步 —— 这是正常路径而不是失败，
   * 所以这里既不报错也不改位置。失败（顺序不对、脚本过期）走 `write` 的统一提示口。
   */
  guideProgress(stepId: string, action: GuideAction,
                onAdvanced: (resp: GuideProgressResp) => void): Promise<void> {
    return this.write('guide', this.api.guideProgress({ stepId, action }), [], onAdvanced)
  }

  /**
   * 引导埋点的唯一出口：动作、步骤 id、脚本版本三件一起走。
   *
   * <p>刻意由 `GuideDriver` 决定"什么时候该记"（enter 一步一次、乱点一步一次），
   * 本方法只负责把它送到埋点通道上 —— 记不记的口径如果在两处各判一遍，
   * 看板上"完成率"就会随调用顺序漂移。
   */
  trackGuideStep(action: string, stepId: string, guideVersion: string): void {
    this.track(TRACK_EVENTS.guideStep, {
      action,
      stepId,
      guideVersion: trackParam(guideVersion),
    })
  }

  // ---------- 邮件（B12 §2） ----------

  /**
   * 一键领取全部。成功后重拉收件箱（那几封要变成「已领取」），
   * 并把回执单独递一次给结果行 —— 只重拉的话玩家看到的是"列表变灰了"，
   * 而不是"我刚才领到了什么"，而后者才是他来这一趟想知道的。
   */
  claimAllMail(): Promise<void> {
    this.track(TRACK_EVENTS.mailClaimAll)
    return this.write('mail', this.api.mailClaimAll({}), ['mail'],
      r => this.targets.mailClaimed?.(r))
  }

  /**
   * 点开一封未读邮件。重拉一次列表而不是本地把那行改成已读：
   * 未读封数是服务端算的（红点与列表同源），本地改法迟早和徽标各说一套。
   */
  /** 组装并递一次商店视图。`notice` 是**上一次兑换的结果**，进货架时一并带上（面板只画一次）。 */
  private deliverShop(): void {
    const resp = this.shopResp
    if (resp === null) {
      return
    }
    this.targets.shop?.({ ...buildShopPanel(resp, this.shopTab), notice: this.shopNotice })
  }

  /**
   * 切商店页签（B24 S-b）。切完重拉那一页 —— 四个币种的余额与限购各是各的账本，
   * 客户端不能拿金币页的数据去画赛季币页。
   */
  openShopTab(currency: ShopCurrency): Promise<void> {
    this.shopTab = currency
    this.shopNotice = null
    this.track(TRACK_EVENTS.shopTab, { currency })
    return this.refresh('shop')
  }

  /**
   * 兑换一行。一次点一个（数量选择器属编辑器资产，见 `game/shop/ShopPanel.ts`）。
   *
   * <p><b>不能兑换的那一行不发请求</b>：服务端已经把原因（等级/限购/余额/不在盟里）算好了，
   * 直接说给玩家听比发一次注定被拒的请求好；真发了也会被同一套规则拒。
   *
   * <p>成功后重拉**四样**：货架（限购与余额变了）、背包（拿到了道具）、资源（金币页会扣金币）、
   * 红点（部分货品的获得会点亮对应入口）。
   */
  buyShopRow(rowId: string): Promise<void> {
    const resp = this.shopResp
    if (resp === null) {
      this.rejectNeeds('shop', '货架还没拉回来，稍后再试')
      return Promise.resolve()
    }
    const view = buildShopPanel(resp, this.shopTab)
    const body = buyBodyOf(view, rowId)
    if (body === null) {
      const row = view.rows.find((candidate) => candidate.rowId === rowId)
      this.rejectNeeds('shop', row === undefined ? '这一行不在货架上了' : shopRowStateText(row))
      return Promise.resolve()
    }
    const name = view.rows.find((candidate) => candidate.rowId === rowId)?.name ?? ''
    this.track(TRACK_EVENTS.shopBuy, { currency: body.currency, rowId })
    return this.write('shop', this.api.shopBuy(body), ['shop', 'bag', 'resources', 'reddot'], r => {
      // 花费与余额都取服务端回执：本地那份表可能已经过期（热更），自己乘出来的数字会和实际扣的对不上
      this.shopNotice = buyResultText(name, r.spent)
    })
  }

  /** 组装并递一次集结视图。 */
  private deliverRallies(): void {
    const resp = this.rallyResp
    if (resp === null) {
      return
    }
    this.targets.rallies?.({
      source: resp,
      myPlayerId: this.playerId ?? '',
      notice: this.rallyNotice,
    })
  }

  /**
   * 交上去：把编好的队伍作为**加入集结的承诺兵力**（服务端按这份锁兵）。
   *
   * <p>与出征只差三处：路径不同、带 `rallyId`、武将位留空（集结的武将位按加入顺序抢，
   * 那一点由服务端判，客户端不预占）。
   */
  private async confirmRallyJoin(rallyId: string, units: readonly MarchUnit[]): Promise<void> {
    this.composeSubmitting = true
    this.composeNotice = null
    this.deliverCompose()
    this.track(TRACK_EVENTS.rallyJoin, {
      rallyId,
      troops: trackParam(units.reduce((sum, unit) => sum + unit.count, 0)),
    })
    const troops = units.map((unit) => ({ unitId: unit.unitId, count: unit.count }))
    const outcome = await this.api.rallyJoin({ rallyId, troops, heroes: null })
    this.composeSubmitting = false
    this.composeRallyId = null
    if (outcome.kind === 'ok') {
      // 回执里就是这一支的最新样子：先就地并进本地列表并立刻重画（玩家这一刻要看到"我加进去了"），
      // 再在后台跟服务端对一次齐 —— 只依赖那次网络往返的话，提示行要等它回来才出现
      this.rallyResp = {
        rallies: this.replaceRally(outcome.data.rally),
        serverNow: outcome.data.serverNow,
      }
      this.rallyNotice = '已加入集结：承诺的兵力已锁定'
      this.composeTarget = null
      this.composePicks = {}
      this.composeNotice = '已加入集结'
      this.deliverCompose()
      this.deliverRallies()
      void this.refresh('rallies')
      return
    }
    this.composeNotice = outcome.kind === 'biz'
      ? (outcome.detail ?? outcome.msg)
      : AppRoot.reason(outcome)
    this.say('rallies', outcome)
    this.deliverCompose()
  }

  /**
   * 为「加入集结」编队：复用出征那套编成面板（同一个 `composeTarget` 与同一份勾选表）。
   *
   * <p><b>为什么加入要先编队</b>：`/rally/join` 要带 `troops`（服务端要按这份承诺锁兵），
   * 所以"点一下加入"在协议上根本不成立 —— 那是另一支队伍的兵力构成，不能空手加入。
   */
  async beginRallyCompose(rallyId: string): Promise<void> {
    const rally = this.rallyResp?.rallies.find((it) => it.rallyId === rallyId) ?? null
    if (rally === null) {
      this.rejectNeeds('rallies', '这一支集结已经结束了')
      return
    }
    // 编成面板要军队列表才画得出来。玩家可能是**先点集结、还没进过军队面板**才来加入的，
    // 那一刻 armyResp 还是 null —— 少了这一句，点「加入」什么都不弹（探针首跑抓到的真缺陷）
    if (this.armyResp === null) {
      await this.refresh('army')
    }
    this.composeRallyId = rallyId
    this.composeTarget = { id: rallyId, name: '集结目标', x: rally.targetCoord.x, y: rally.targetCoord.y }
    this.composeNotice = null
    this.deliverCompose()
  }

  /** 退出一支集结（自己走，队伍还在）。 */
  quitRally(rallyId: string): Promise<void> {
    this.track(TRACK_EVENTS.rallyQuit, { rallyId })
    return this.write('rallies', this.api.rallyQuit({ rallyId, troops: [], heroes: null }), ['rallies'], r => {
      this.rallyResp = { rallies: r.rally ? this.replaceRally(r.rally) : (this.rallyResp?.rallies ?? []), serverNow: r.serverNow }
      this.rallyNotice = '已退出集结'
    })
  }

  /** 发起人取消整支集结（与退出分开：这一下会退掉所有人承诺的兵）。 */
  cancelRally(rallyId: string): Promise<void> {
    this.track(TRACK_EVENTS.rallyCancel, { rallyId })
    return this.write('rallies', this.api.rallyCancel({ rallyId }), ['rallies'], () => {
      this.rallyNotice = '已取消集结（大家承诺的兵已退回）'
    })
  }

  /** 把服务端回执里的那一支替换进本地列表（退出之后它可能就不在列表里了）。 */
  private replaceRally(updated: RallyListResp['rallies'][number]): RallyListResp['rallies'] {
    const current = this.rallyResp?.rallies ?? []
    const others = current.filter((it) => it.rallyId !== updated.rallyId)
    return [...others, updated]
  }

  /** 组装并递一次战令视图（窗口由视图按实测高度现算，这里只递原始响应与提示行）。 */
  private deliverBattlePass(): void {
    const resp = this.battlePassResp
    if (resp === null) {
      return
    }
    this.targets.battlePass?.({ source: resp, notice: this.battlePassNotice })
  }

  /**
   * 领某一档的某一条线（B24 S-d-e）。`track` 是 `FREE` / `PAID`。
   *
   * <p><b>不能领的那一次不发请求</b>：服务端已经把原因算好了（没达成 / 没买战令 / 已领），
   * 面板根本不给不可领的那条线画亮按钮（见 `game/battlePass/BattlePassPanel.ts`），
   * 这里是第二道：真点到了也只是提示一句，不发请求。
   *
   * <p>成功后用**回执里的全量状态**重画（不再多发一次 GET），并顺带刷新背包与资源 ——
   * 奖励进的是那两个地方，不刷会让玩家在背包里看不见刚到的东西。
   */
  claimBattlePassTier(tier: number, track: BattlePassTrack): Promise<void> {
    const resp = this.battlePassResp
    if (resp === null) {
      this.rejectNeeds('battlePass', '战令进度还没拉回来，稍后再试')
      return Promise.resolve()
    }
    const view = buildBattlePassPanel(resp, resp.tiers.length)
    const row = view.rows.find((candidate) => candidate.tier === tier) ?? null
    const body = row === null ? null : claimBodyOf(row, track)
    if (body === null) {
      this.rejectNeeds('battlePass', track === 'PAID'
        ? '付费线现在还领不了 —— 要么这一档还没达成，要么本赛季战令还没买'
        : '这一档现在还领不了 —— 要么还没达成，要么已经领过了')
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.battlePassClaim, { tier: String(tier), track })
    return this.write('battlePass', this.api.battlePassClaim(body), ['bag', 'resources'], r => {
      // 回执里的 status 就是领取之后的全量状态：直接落进缓存并重画，不再多发一次 GET
      this.battlePassResp = r.status
      this.battlePassNotice = claimResultText(r.reward)
      this.deliverBattlePass()
    })
  }

  /** 组装并递一次外观视图。`notice` 是**上一次操作的结果**，进面板时一并带上（面板只画一次）。 */
  private deliverAvatarFrames(): void {
    const resp = this.frameResp
    if (resp === null) {
      return
    }
    this.targets.avatarFrames?.({
      ...buildAvatarFramePanel(resp, this.nickName), notice: this.frameNotice,
    })
  }

  /**
   * 戴上 / 卸下（B24 块③）。`frameId` 为 null = 卸下。
   *
   * <p><b>没拥有的框不发请求</b>：服务端已经把 `owned` 算好下发，点一颗注定被拒的按钮
   * 只会换来一句报错 —— 面板根本不给未拥有的行画按钮（见 `game/avatar/AvatarFramePanel.ts`），
   * 这里是第二道：真点到了也只是提示一句，不发请求。
   *
   * <p>成功后重画**整块视图**（响应本身就是操作之后的完整列表，不再多发一次 GET）：
   * 面板上的「佩戴中」、预览圈的颜色、按钮文案全都跟着这份响应走，
   * 客户端一处都不在本地翻状态 —— 本地翻法在「服务端拒了但界面已经翻过去了」时会骗人。
   */
  wearFrame(frameId: string | null): Promise<void> {
    const resp = this.frameResp
    if (resp === null) {
      this.rejectNeeds('avatarFrames', '外观列表还没拉回来，稍后再试')
      return Promise.resolve()
    }
    const view = buildAvatarFramePanel(resp, this.nickName)
    const target = frameId === null
      ? view.rows.find((row) => row.worn) ?? null
      : view.rows.find((row) => row.frameId === frameId) ?? null
    const body = target === null ? null : wearBodyOf(target)
    if (body === null) {
      this.rejectNeeds('avatarFrames', target === null
        ? '这一枚不在外观列表里了'
        : '这一枚还没有拿到 —— 先在商店里兑换')
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.frameWear, { frameId: frameId ?? 'none' })
    return this.write('avatarFrames', this.api.wearFrame(body), [], r => {
      // 回执就是操作之后的完整列表：直接落进缓存并重画，不再多发一次 GET
      this.frameResp = { frames: r.frames, serverNow: r.serverNow }
      this.frameNotice = wearResultText(r.frames)
      this.deliverAvatarFrames()
    })
  }

  /**
   * 点开一场战报进回放。走 {@link deliver} 而不是 {@link write}：拉详情没有副作用，
   * 成功后也不重拉列表 —— 列表刚刚拉过，而重拉会把玩家正看着的那一屏换掉。
   */
  async openReport(reportId: string): Promise<void> {
    this.deliver('reports', await this.api.battleReport(reportId),
      r => this.targets.reportReplay?.(r))
  }

  readMail(mailId: string): Promise<void> {
    this.track(TRACK_EVENTS.mailRead, { mailId })
    return this.write('mail', this.api.mailRead({ mailId }), ['mail'])
  }

  // ---------- 活动（B17） ----------

  /**
   * 领一次活动奖励。成功后重拉列表（那一行要变成「本轮已领取」），
   * 并把回执单独递一次给结果行 —— 与一键领邮件同一条：玩家想知道的是「我刚才领到了什么」。
   */
  claimActivity(activityId: string): Promise<void> {
    this.track(TRACK_EVENTS.activityClaim, { activityId })
    return this.write('activity', this.api.activityClaim(claimActivityReq(activityId)), ['activity'],
      r => this.targets.activityClaimed?.(r))
  }

  // ---------- 社交 ----------

  help(helpRequestId: string): Promise<void> {
    this.track(TRACK_EVENTS.socialHelp, { requestId: helpRequestId })
    return this.write('social', this.api.socialHelp({ helpRequestId }),
      ['social', 'army', 'city', 'reddot'])
  }

  helpAll(): Promise<void> {
    this.track(TRACK_EVENTS.socialHelpAll)
    return this.write('social', this.api.socialHelpAll(), ['social', 'army', 'city', 'reddot'])
  }

  donate(tier: number): Promise<void> {
    this.track(TRACK_EVENTS.allianceDonate, { tier: trackParam(tier) })
    return this.write('social', this.api.allianceDonate({ tier }), ['social', 'resources'])
  }

  /**
   * 拉社交页的四道门（B26 S1 + S2 + S14）：两个 scope 的权限、一份创建政策、一份集结政策
   * （各一次回两个层级）。
   *
   * <p>权限两份都到齐才把 `loaded` 置 true：只拿到一半就放开按钮，等于拿缺的那一半去猜。
   * 失败时保持原样并说一句"暂时拉不到"—— 灰着的按钮比一个点了会被拒的按钮诚实。
   */
  loadSocialGates(): Promise<void> {
    if (this.gatesFlight !== null) {
      return this.gatesFlight
    }
    this.permissionsLoaded = true
    this.gatesFlight = this.pullSocialGates().finally(() => {
      this.gatesFlight = null
    })
    return this.gatesFlight
  }

  private async pullSocialGates(): Promise<void> {    const [squad, alliance, create, rally] = await Promise.all([
      this.api.socialPermissions('SQUAD'), this.api.socialPermissions('ALLIANCE'),
      this.api.socialCreatePolicy(), this.api.rallyPolicy(),
    ])
    let state = EMPTY_PERMISSIONS
    if (squad.kind === 'ok') {
      state = withPermissionScope(state, 'SQUAD', squad.data.permissions, squad.data.role, false)
    } else {
      this.say('social', squad)
    }
    if (alliance.kind === 'ok') {
      state = withPermissionScope(state, 'ALLIANCE', alliance.data.permissions,
        alliance.data.role, squad.kind === 'ok' && alliance.kind === 'ok')
    } else {
      this.say('social', alliance)
    }
    if (create.kind === 'ok') {
      this.createPolicy = create.data
    } else {
      this.say('social', create)
    }
    if (rally.kind === 'ok') {
      this.rallyPolicy = rally.data
      // 编成面板正停在联盟层等这两个数：政策晚到一步就要立刻补上，否则那一屏停在"读取中"
      if (this.rallyForm === null && this.composeRally && this.composeRallyScope === 'ALLIANCE') {
        this.rallyForm = rallyFormOf(this.rallyPolicyOf('ALLIANCE'))
      }
    } else {
      this.say('social', rally)
    }
    this.permissions = state
    this.targets.socialGates?.(this.permissions, createEntries(this.createPolicy,
      this.createBalance('squad'), this.createBalance('alliance')))
    this.deliverSocialCreate()
    // 编成面板可能正停在联盟层等那两个数：政策晚到也要立刻补上那一屏
    this.deliverCompose()
  }

  /**
   * 打开创建表单（B26 S2）。
   *
   * <p>余额没拉过就在这里补一次：这一屏要比「够不够扣」，而 `/resource/detail` 不在首屏预拉里
   * （与抽卡/商店同一条预算纪律）。拉不到也只是少一句「还差多少」，不挡着表单。
   */
  async openSocialCreate(scope: CreateScope): Promise<void> {
    this.creating = { scope, name: '', tag: '' }
    if (this.resourceResp === null) {
      await this.refresh('resources')
    }
    this.deliverSocialCreate()
  }

  /** 打字不是意图动作：只改界面，一条请求都不发。 */
  typeSocialCreate(field: 'name' | 'tag', value: string): void {
    if (this.creating === null) {
      return
    }
    this.creating = field === 'name'
      ? { ...this.creating, name: value }
      : { ...this.creating, tag: value }
    this.deliverSocialCreate()
  }

  cancelSocialCreate(): void {
    this.creating = null
    this.deliverSocialCreate()
  }

  async submitSocialCreate(): Promise<void> {
    const form = this.creating
    if (form === null) {
      return
    }
    const view = buildCreateForm(form.scope, this.policyOf(form.scope),
      this.createBalance(form.scope), form.name, form.tag)
    if (!view.canSubmit) {
      // 灰着的确认点了不该发那一枪（与抽卡"钱不够就不发"同一条）
      this.rejectNeeds('social', view.hint)
      return
    }
    this.track(TRACK_EVENTS.socialCreate, {
      scope: trackParam(form.scope === 'squad' ? 'SQUAD' : 'ALLIANCE'),
    })
    const name = form.name.trim()
    return this.write('social',
      form.scope === 'squad'
        ? this.api.squadCreate({ name })
        : this.api.allianceCreate({ name, tag: form.tag.trim() }),
      // 'social' 那一路会顺手重拉门（权限与创建政策）：刚建成盟主，按钮必须当场亮起来
      ['social', 'resources', 'reddot'],
      () => {
        this.creating = null
        this.deliverSocialCreate()
      })
  }

  /**
   * 按下「退出 / 解散」：第一下只把那一行改成"确认…"，第二下才真发请求（B26 S3）。
   *
   * <p>解散与退队都不可逆，一键生效的代价是整个联盟没了；而"要不要再确认一次"是界面行为，
   * 不该为此在服务端请求里多塞一个参数。换一行按就等于重新数第一下。
   */
  requestExit(scope: ExitScope, action: ExitAction): Promise<void> {
    const armed = this.armedExit
    if (armed === null || armed.scope !== scope || armed.action !== action) {
      this.armedExit = { scope, action }
      this.targets.socialExit?.(this.armedExit)
      return Promise.resolve()
    }
    this.armedExit = null
    this.targets.socialExit?.(null)
    this.track(action === 'leave' ? TRACK_EVENTS.socialLeave : TRACK_EVENTS.socialDisband, {
      scope: trackParam(scope === 'squad' ? 'SQUAD' : 'ALLIANCE'),
    })
    const call = scope === 'squad'
      ? (action === 'leave' ? this.api.squadLeave({}) : this.api.squadDisband({}))
      : (action === 'leave' ? this.api.allianceLeave({}) : this.api.allianceDisband({}))
    // 'social' 那一路顺手重拉三道门：退了队权限就该空掉，解散之后连"未加入"那一行都要换字
    return this.write('social', call, ['social', 'reddot'])
  }

  /**
   * 按下「转让」（B26 S4）：与退出/解散同样两下才算数 —— 转让之后自己降为成员，
   * 想反悔就得求新队长把人换回来，所以它和那两条一样不可逆。
   */
  requestTransfer(scope: ExitScope, memberId: string): Promise<void> {
    const armed = this.armedTransfer
    if (armed === null || armed.scope !== scope || armed.memberId !== memberId) {
      this.armedTransfer = { scope, memberId }
      this.targets.socialTransfer?.(this.armedTransfer)
      return Promise.resolve()
    }
    this.armedTransfer = null
    this.targets.socialTransfer?.(null)
    this.track(TRACK_EVENTS.socialTransfer, {
      scope: trackParam(scope === 'squad' ? 'SQUAD' : 'ALLIANCE'),
      memberId: trackParam(memberId),
    })
    return this.write('social',
      scope === 'squad'
        ? this.api.squadTransfer({ memberId })
        : this.api.allianceTransfer({ memberId }),
      ['social', 'reddot'])
  }

  /**
   * 扩建联盟人数上限（B26 S5）：花联盟资金，不改成员，所以与捐献一样一按就发，
   * 不做"两下才算数"（那套是给不可逆的组织去留用的）。
   */
  expandAlliance(): Promise<void> {
    this.track(TRACK_EVENTS.allianceExpand)
    return this.write('social', this.api.allianceExpand({}), ['social', 'reddot'])
  }

  /**
   * 拉可申请联盟列表（B26 S6）。读不到就写「读取中」，不把空表当成"没有联盟可加"——
   * 那是两件事：前者过一会儿会自己好，后者会让人放弃找队。
   */
  private async loadAllianceDiscovery(): Promise<void> {
    const outcome = await this.api.allianceList()
    if (outcome.kind === 'ok') {
      this.discovery = buildDiscovery(outcome.data)
      return
    }
    this.say('social', outcome)
    this.discovery = buildDiscovery(null)
  }

  /**
   * 拉可加入小队列表（B26 S7）。与联盟那一条同样的分工：读不到写「读取中」，
   * 不把空表当成「没人建队」。
   */
  /**
   * 拉那两份发现型列表（B26 S6 + S7），由「玩家打开社交页」触发。
   *
   * <p>为什么不是一个公共方法两次内部调用就完事：这一条同时是**首屏预算的边界**。
   * 摘要说 squad / alliance 为 null 就是权威答案，只拉缺的那一份 —— 已经有队的人
   * 一次请求都不该为别人家的名单发。
   */
  async loadSocialDiscovery(): Promise<void> {
    this.socialVisited = true
    // 门要先于名单：社交页打开时 loadSocialGates 与这一条是并发跑的，权限还没回来就把
    // APPROVE_APPLICATION 判成"不能"，结果是那一问一次都不发、而屏上安静得像"没人申过"。
    // 实测踩过：探针 G 相六条全红，而三条"不该出现"的断言反而全绿 —— 那是空转，不是通过。
    if (!this.permissions.loaded) {
      await this.loadSocialGates()
    }
    const summary = this.lastSocialSummary
    if (summary === null) {
      // 摘要还没落地（首屏还在飞）：先记下"玩家进来了"，等这一轮刷完自然带上
      return
    }
    if (summary.alliance === null) {
      await this.loadAllianceDiscovery()
    } else {
      this.discovery = EMPTY_DISCOVERY
    }
    if (summary.squad === null) {
      await this.loadSquadDiscovery()
    } else {
      this.squadDiscoveryView = EMPTY_SQUAD_DISCOVERY
    }
    // 入盟申请（B26 S8）：能不能审由服务端那份权限码说，客户端不自己按职位推。
    // 没有这一问的时候 `/alliance/review` 是玩家永远打不到的端点 —— 名单读不出来，批谁？
    if (gate(this.permissions, 'ALLIANCE', 'APPROVE_APPLICATION').allowed) {
      await this.loadApplications()
    } else {
      this.applications = EMPTY_APPLICATIONS
    }
    this.deliverDiscovery(this.discovery, this.squadDiscoveryView)
    this.targets.allianceApplications?.(this.applications)

  }

  /**
   * 拉本盟待审申请（B26 S8）。读不到写「读取中」，不把空表当成"没人申" ——
   * 盟主据此决定要不要去喊人，写错一句就会让人去做一件本来不必要的事。
   */
  private async loadApplications(): Promise<void> {
    const outcome = await this.api.allianceApplications()
    if (outcome.kind === 'ok') {
      this.applications = buildApplications(outcome.data)
      return
    }
    this.say('social', outcome)
    this.applications = buildApplications(null)
  }

  /**
   * 审核一条申请（B26 S8）。批准与拒绝都是真动作，一按就发：不做"两下才算数"，
   那套只留给不可逆的组织去留（退盟 / 解散 / 转让）。
   */
  /**
   * 研究一级联盟科技（B26 S9）。一次一级、扣的是全盟公账，所以带幂等键；
   * 灰态（到上限 / 资金不够 / 职位不够）由服务端那两条结论决定，客户端只在"这一行现在能发"时发。
   */
  researchAllianceTech(techId: string): Promise<void> {
    this.track(TRACK_EVENTS.allianceResearch, { techId: trackParam(techId) })
    return this.write('social', this.api.allianceResearchTech({ techId, levels: 1 }),
      ['social', 'reddot'])
  }

  /**
   * 任命联盟成员（B26 S11）。职位能不能给、压不压得下去都由服务端裁决
   * （表里的 SET_ROLE 位 + `Alliance.setRole` 的域内规则），客户端只负责把这一枪发出去。
   */
  setAllianceRole(memberId: string, role: AllianceRole): Promise<void> {
    this.track(TRACK_EVENTS.allianceSetRole, {
      memberId: trackParam(memberId),
      role: trackParam(role),
    })
    return this.write('social', this.api.allianceSetRole({ memberId, role }),
      ['social', 'reddot'])
  }

  reviewApplication(applicantId: string, approve: boolean): Promise<void> {
    this.track(TRACK_EVENTS.allianceReview, {
      applicantId: trackParam(applicantId),
      approve: trackParam(approve ? 'yes' : 'no'),
    })
    return this.write('social', this.api.allianceReview({ applicantId, approve }),
      ['social', 'reddot'])
  }

  private deliverDiscovery(alliance: DiscoveryView, squad: SquadListView): void {
    this.targets.allianceDiscovery?.(alliance)
    this.targets.squadDiscovery?.(squad)
  }

  private async loadSquadDiscovery(): Promise<void> {
    const outcome = await this.api.squadList()
    if (outcome.kind === 'ok') {
      this.squadDiscoveryView = buildSquadDiscovery(outcome.data)
      return
    }
    this.say('social', outcome)
    this.squadDiscoveryView = buildSquadDiscovery(null)
  }

  /**
   * 加入某一支小队（B26 S7）。满不满由服务端那个布尔决定，客户端只判断
   * 「这一行现在能不能发」；进了队之后能不能用，是摘要里那一份 squad 说话。
   */
  joinSquad(squadId: string): Promise<void> {
    if (!canJoin(this.squadDiscoveryView, squadId)) {
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.squadJoin, { squadId: trackParam(squadId) })
    return this.write('social', this.api.squadJoin({ squadId }), ['social', 'reddot'])
  }

  /**
   * 申请加入某个联盟（B26 S6）。灰态（已满 / 已申请）由服务端那两个布尔决定，
   * 客户端只在"这一行现在能点"时发请求；其余判断（是否还在人数上限内）由服务端裁决。
   */
  applyToAlliance(allianceId: string): Promise<void> {
    if (!canApply(this.discovery, allianceId)) {
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.allianceApply, { allianceId: trackParam(allianceId) })
    return this.write('social', this.api.allianceApply({ allianceId }), ['social', 'reddot'])
  }

  /** 表单没开着就不发视图（开着才画，避免每次刷新都弹人一脸表单）。 */
  private deliverSocialCreate(): void {
    const form = this.creating
    if (form === null) {
      this.targets.socialCreate?.(null)
      return
    }
    this.targets.socialCreate?.(buildCreateForm(form.scope, this.policyOf(form.scope),
      this.createBalance(form.scope), form.name, form.tag))
  }

  private policyOf(scope: CreateScope): SocialCreatePolicy | null {
    if (this.createPolicy === null) {
      return null
    }
    return scope === 'squad' ? this.createPolicy.squad : this.createPolicy.alliance
  }

  /** 创建消耗那种资源的余额；没读到返回 null（读不到不等于零，不能因此把确认按死）。 */
  private createBalance(scope: CreateScope): number | null {
    const cost = this.policyOf(scope)?.costResource
    const rows = this.resourceResp?.resources
    if (cost === undefined || cost === null || rows === undefined) {
      return null
    }
    const row = rows.find(r => r.type === cost)
    return row === undefined ? null : row.current
  }

  /** 踢人必须知道从哪个组织踢：View 的两个页签共用一个行内回调，分流由调用方给 `from`。 */
  kick(memberId: string, from: 'squad' | 'alliance'): Promise<void> {
    this.track(TRACK_EVENTS.memberKick, { memberId, from })
    return this.write('social',
      from === 'squad' ? this.api.squadKick({ memberId }) : this.api.allianceKick({ memberId }),
      ['social'])
  }

  /** 事件已读（红点与离线补偿都挂在这本账上）。 */
  ackEvents(eventIds: readonly string[]): Promise<void> {
    this.track(TRACK_EVENTS.eventsAck, { kind: 'ack', count: trackParam(eventIds.length) })
    return this.write('social', this.api.socialAckEvents({ eventIds: [...eventIds] }),
      ['social', 'reddot'])
  }

  // ---------- 聊天（B22 §一 1） ----------

  /**
   * 进聊天页签：拉当前这条会话/频道的历史。
   *
   * <p>每次进都拉一次（而不是"拉过就不拉"）：聊天没有推送的是世界/联盟/小队三条，
   * 离开又回来时那三条的消息只能靠这一拉补上。私聊那条虽然有推送，推送也只说"有人找你"
   * 不带正文（C23 的刻意设计），正文同样得从这里拉。
   */
  async openChat(): Promise<void> {
    await this.loadChat(this.chatChannel, this.chatPeerId)
  }

  /**
   * 切频道。**一律回到"未选会话"**：私聊的会话键由双方 id 拼成，
   * 留着上一个对象就等于在联盟频道里悄悄带着别人的 id 发消息。
   */
  async selectChatChannel(channel: ChatChannel): Promise<void> {
    this.chatChannel = channel
    this.chatPeerId = null
    this.chatNotice = null
    await this.loadChat(channel, null)
  }

  /**
   * 打开一个私聊会话，并把它标记为已读。
   *
   * <p>消账走 `/social/ackEvents`（服务端那本账），消完由 `refresh('social')` 的返回值
   * 把新的未读账本送回面板 —— 客户端不在本地把未读减一：减错了没人能发现，
   * 而红点亮着、计数为零正是这条的典型症状。
   */
  async openConversation(peerId: string): Promise<void> {
    this.chatChannel = 'PRIVATE'
    this.chatPeerId = peerId
    this.chatNotice = null
    await this.loadChat('PRIVATE', peerId)
    const eventIds = ackablePrivateEventIds(this.unreadEvents, peerId)
    if (eventIds.length > 0) {
      await this.ackEvents(eventIds)
    }
  }

  /**
   * 发一条消息。
   *
   * <p>失败**不吞**：原因写进提示行（限流那句"慢一点"是可读文案），成功才清输入框 ——
   * 失败时把玩家打的字清掉，等于让他重打一遍。
   */
  async sendChat(text: string): Promise<void> {
    const content = text.trim()
    if (content === '' || (this.chatChannel === 'PRIVATE' && this.chatPeerId === null)) {
      return
    }
    const outcome = await this.api.chatSend({
      channel: this.chatChannel,
      content,
      toPlayerId: this.chatChannel === 'PRIVATE' ? this.chatPeerId : null,
    })
    if (outcome.kind !== 'ok') {
      this.chatNotice = outcome.kind === 'biz'
        ? chatFailureText(outcome.code, outcome.detail, outcome.msg)
        : AppRoot.reason(outcome)
      // 面板的提示行是玩家唯一看得见这句话的地方（targets.error 只进 console），
      // 所以这里既要 say（埋点/日志）也要 deliverChat（提示行）
      this.say('chat', outcome)
      this.deliverChat()
      return
    }
    const key = chatKey(this.chatChannel, this.chatPeerId)
    // 用服务端回执里的那条消息，而不是本地拼一条：id 与时刻都是权威的，
    // 本地拼的那条会在下一次拉取时与本尊撞成两条
    this.chatHistory.set(key,
      mergeChatHistory(this.chatHistory.get(key) ?? [], [outcome.data.message]))
    this.chatNotice = null
    this.chatSentSeq += 1
    // **发完一条回到最新那一页**：停在"更早"那一页时，自己刚发的话落在最新页上，
    // 屏上什么都不动 —— 玩家会以为没发出去（这正是 V15 之前那个 slice(-5) 在补的坑）
    this.chatPage = 0
    this.deliverChat()
  }

  /**
   * 订阅服务端推送（B22 §一 1 的"最后一公里"）。
   *
   * <p>此前 `gameBus.serverPush` 全仓库零订阅：服务端两条路都发了，帧到了客户端被直接丢掉。
   * 私聊是唯一会推的聊天事件（C23），它**不带正文** —— 所以这里收到事件只加未读账
   * 并把红点树刷成服务端的结论，不顺手去拉正文（那是"信标"这个设计要避免的事）。
   *
   * <p>返回退订函数：`gameBus` 是模块级单例，重登/切场景会再构造一个组合根，
   * 不退订就会在同一条推送上被调 N 次（红点变成"第 N 次才亮"）。
   */
  bindPush(): () => void {
    const offPush = gameBus.on('serverPush', (push) => {
      if (push.type !== 'PRIVATE_MESSAGE') {
        return
      }
      this.absorbPrivateEvent(push.data)
    })
    const offReconnected = gameBus.on('netReconnected', () => {
      // 断线期间的推送全丢了，B01 要求走 HTTP 补拉。不补拉的话未读账会一直缺一块，
      // 直到玩家下一次手动刷新社交面板才可能被纠正
      void this.refresh('social', 'reddot')
    })
    return () => {
      offPush()
      offReconnected()
    }
  }

  /**
   * 把一条推送的私信事件并进未读账。
   *
   * <p>**同一 eventId 只记一次**：重连之后补拉（`/social/summary`）与推送可能带来同一条事件，
   * 不去重就会数出两条未读。
   */
  private absorbPrivateEvent(payload: unknown): void {
    const event = asPrivateMessageEvent(payload)
    if (event === null) {
      return
    }
    if (this.unreadEvents.some((item) => item.eventId === event.eventId)) {
      return
    }
    this.unreadEvents = [...this.unreadEvents, event]
    // 页签上那颗红点只读服务端权威树 —— 推送把树刷一次，而不是在客户端替它点亮
    void this.refresh('reddot')
    this.deliverChat()
  }

  /** 拉一段历史并并进本地缓存。`PRIVATE` 且没有对象时只画会话列表，不发请求。 */
  private async loadChat(channel: ChatChannel, peerId: string | null): Promise<void> {
    this.deliverChat()
    if (channel === 'PRIVATE' && peerId === null) {
      return
    }
    const outcome = await this.api.chatList({
      channel,
      toPlayerId: channel === 'PRIVATE' ? peerId : null,
      beforeMessageId: null,
      limit: CHAT_LOCAL_HISTORY_MAX,
    })
    if (outcome.kind !== 'ok') {
      // 没资格看这个频道（未入盟看联盟频道）走的就是这一支：服务端的 detail
      // 比一句"加载失败"有用得多，直接摆在提示行上
      this.chatNotice = outcome.kind === 'biz'
        ? chatFailureText(outcome.code, outcome.detail, outcome.msg)
        : AppRoot.reason(outcome)
      this.say('chat', outcome)
      this.deliverChat()
      return
    }
    const key = chatKey(channel, peerId)
    this.chatHistory.set(key,
      mergeChatHistory(this.chatHistory.get(key) ?? [], outcome.data.messages))
    // 打开/切频道/换会话一律回到最新那一页，并把服务端的 `hasMore` 收下来（V15）
    this.chatPage = 0
    this.chatHasMoreOlder = outcome.data.hasMore
    this.learnPeerNames(outcome.data.messages)
    this.chatNotice = null
    this.deliverChat()
  }

  /** 从拉到的人消息里学昵称。会话列表在没学到之前只能显示事件标题（「X 给你发来一条私信」）。 */
  private learnPeerNames(messages: readonly ChatMessageView[]): void {
    for (const message of messages) {
      if (message.senderId !== this.playerId) {
        this.chatPeerNames.set(message.senderId, message.senderName)
      }
    }
  }

  // ---------- 排行榜（B23 §一 3） ----------

  /**
   * 切页签。点「明细」不发请求（那一页由 `/player/power` 供数），点四类榜才拉 `/rank/list`。
   *
   * <p>**页码回到第 1 页**：换了一张榜还停在第 5 页，会让玩家看到某个榜的第 5 页却说不出为什么。
   */
  async openRankTab(key: RankTabKey): Promise<void> {
    if (key === 'SEASON') {
      // 赛季页不是一张榜：混进 rank_view 会让"玩家看哪张榜"那一栏多出一个假榜
      this.track(TRACK_EVENTS.seasonView)
    } else if (key !== 'DETAIL') {
      // 榜的关注度只有这里能答（明细页是原本就有的页面，不算"看榜"这个动作）
      this.track(TRACK_EVENTS.rankView, { type: trackParam(key) })
    }
    this.rankTab = key
    this.rankPage = 1
    this.rankNotice = null
    await this.loadRankIfBoard()
  }

  /**
   * 面板每次被打开时重拉当前页签那一份（榜或赛季）。
   *
   * <p>为什么必须重拉而不是复用上一次：赛季页那一行是**倒计时**，而面板节点是常驻的、
   * 不会自己重画 —— 复用旧值会显示一个已经过期的「还剩 N 天」，而玩家正好会拿它决定
   * 今天要不要把这一仗打完。榜那一侧同理（名次一直在变）。
   * 明细页不在此列：它的数字由既有的 `refresh('power')` 那条通路负责。
   */
  async reloadRankTab(): Promise<void> {
    if (this.rankTab === 'DETAIL') {
      return
    }
    await this.loadRankIfBoard()
  }

  /** 下一页。能不能翻**由服务端的 hasMore 决定**（客户端不猜最后一页在哪）。 */
  async rankNextPage(): Promise<void> {
    if (this.rankResp === null || !this.rankResp.hasMore) {
      return
    }
    this.rankPage = this.rankResp.page + 1
    await this.loadRankIfBoard()
  }

  /** 上一页。第 1 页时什么都不做（面板也会把按钮画灰）。 */
  async rankPrevPage(): Promise<void> {
    if (this.rankResp === null || this.rankResp.page <= 1) {
      return
    }
    this.rankPage = this.rankResp.page - 1
    await this.loadRankIfBoard()
  }

  /**
   * 按当前页签拉一次榜并下发视图。<b>明细页签只重画不拉榜</b>。
   *
   * <p>失败时把服务端的理由原样放进 notice 那一行（限流/断网）：榜拉不到不该清空面板 ——
   * 清空会让玩家以为"榜没了"，而实际只是这一次没拉到。
   */
  private async loadRankIfBoard(): Promise<void> {
    if (this.rankTab === 'DETAIL') {
      this.rankResp = null
      this.rankSnapshot = null
      this.deliverRank()
      return
    }
    if (this.rankTab === 'SEASON') {
      // 赛季页与榜无关：手里那份榜响应属于别的页签，留着会在赛季页签下画出一张榜
      this.rankResp = null
      this.rankSnapshot = null
      await this.loadSeason()
      this.deliverRank()
      return
    }
    const outcome = await this.api.rankList(this.rankTab, this.rankPage, AppRoot.RANK_SCREEN_ROWS)
    if (outcome.kind === 'ok') {
      this.rankResp = outcome.data
      this.rankNotice = null
      // 快照：用**服务端下发的 dayKey** 去查（客户端自己算日期就是第二条日切轴）。
      // 拉不到不算错误 —— 快照是"申诉时间线"，拉不到就不显示那一块，不打断榜本身。
      const snapshot = await this.api.rankSnapshot(this.rankTab, outcome.data.dayKey)
      this.rankSnapshot = snapshot.kind === 'ok' ? buildRankSnapshotView(snapshot.data) : null
    } else {
      // 榜拉不到时把服务端给的**理由**原样放上提示行（业务拒绝看 detail，网络失败看 reason）。
      // 不自己编一句"加载失败"：限流与断网的下一步动作完全不同（等一会儿 / 检查网络）
      this.rankNotice = outcome.kind === 'biz'
        ? (outcome.detail ?? outcome.msg)
        : AppRoot.reason(outcome)
      this.say('rank', outcome)
    }
    this.deliverRank()
  }

  /**
   * 拉一次赛季状态（V04-S1）。失败时把服务端给的理由原样放到说明行，
   * **不清空手里那一份**：赛季阶段不会因为一次限流就变成"不存在"，
   * 清空会让玩家以为赛季没了（与榜同一条纪律）。
   */
  private async loadSeason(): Promise<void> {
    const outcome = await this.api.seasonStatus()
    if (outcome.kind === 'ok') {
      this.seasonResp = outcome.data
      this.seasonNotice = null
    } else {
      this.seasonNotice = outcome.kind === 'biz'
        ? (outcome.detail ?? outcome.msg)
        : AppRoot.reason(outcome)
      this.say('season', outcome)
    }
  }

  /** 组装并下发整块视图。表现层不参与任何计算（名次/页号/倒计时全部来自上面那两份响应）。 */
  private deliverRank(): void {
    this.targets.rank?.(buildRankBoard(this.rankResp, this.rankTab, this.playerId ?? '',
      this.rankNotice, this.rankSnapshot))
    // 赛季页与榜同屏（第六个页签），但正文来自另一份响应：页签高亮与正文必须同一次下发，
    // 否则会出现"赛季页签亮着、正文还是上一张榜"
    if (this.rankTab === 'SEASON') {
      this.targets.season?.(buildSeasonPanel(this.seasonResp, undefined, this.seasonNotice))
    }
  }

  // ---------- 研究页（V03-a-S1 读侧） ----------

  /**
   * 打开研究页。入口在内城「学院」（由编排层发起）。**每次打开都重拉**：
   * 队列剩余时间会走，复用上一次的值会显示一个过期的「还剩 N 分」。
   */
  async openTech(): Promise<void> {
    this.track(TRACK_EVENTS.techView)
    await this.loadTech()
  }

  /**
   * 拉一次研究列表并下发整块视图。
   *
   * <p>失败时把服务端的理由原样放到说明行，**不清空手里那份**：研究进度不会因为一次限流就消失
   * （与榜/赛季同一条纪律）。"能不能研究"的判定全部来自响应，本方法不自己判一遍。
   */
  private async loadTech(): Promise<void> {
    const outcome = await this.api.techList()
    if (outcome.kind === 'ok') {
      this.techResp = outcome.data
      this.techNotice = null
    } else {
      this.techNotice = outcome.kind === 'biz'
        ? (outcome.detail ?? outcome.msg)
        : AppRoot.reason(outcome)
      this.say('tech', outcome)
    }
    this.deliverTech()
  }

  /**
   * 组装并递一次科技面板。**纯重递、不发请求** —— 被拒的说明要走这条路
   * （`showNeedsInPanel`），一次拒绝不该变成一次读放大。
   */
  private deliverTech(): void {
    this.targets.tech?.(buildTechPanel(this.techResp, this.techNotice))
  }

  /**
   * 开始研究一行科技（V03-a-S1 只接了读侧，这一按才是玩家真正要做的动作）。
   *
   * <p>点之前先看服务端怎么说这一行：`canResearch=false` 就把那句原因报出去、**不发请求** ——
   * 灰着的行点下去只换来一个报错，那是本仓库反复在抓的"界面画了但动作是死的"。
   * 判定本身仍在服务端（它会再校验一遍队列 / 学院等级 / 资源），这里只是不让人对着一句"不行"再按一次。
   */
  researchTech(techId: string): Promise<void> {
    const row = this.techResp?.techs.find((t) => t.techId === techId)
    if (row === undefined) {
      this.rejectNeeds('tech', '这一行研究项还没拉到，稍后再试')
      return Promise.resolve()
    }
    if (!row.canResearch) {
      this.rejectNeeds('tech', blockReasonText(row.blockedReason) ?? '这一行现在研究不了')
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.techResearch, { techId, nextLevel: trackParam(row.level + 1) })
    return this.write('tech', this.api.techResearch({ techId }), ['tech', 'resources'])
  }

  /**
   * 取消当前研究。请求不带 techId（一次一队列，服务端知道是哪一行），返还比例与城建共用一份配置，
   * 客户端只把服务端回的那份 `refund` 念出来 —— 自己按比例重算就是第二个真相。
   *
   * <p>**回执在重拉之后才交**：那句回执占的正是队列那一行的位置，而重拉会把这一行重画一遍 ——
   * 走 `write()` 的 `onOk`（在刷新之前）会被紧接着的渲染清掉（实测：取消成功了但屏幕上什么都没有）。
   */
  async cancelResearch(): Promise<void> {
    const queueId = this.techResp?.queue.techId ?? null
    if (queueId === null || queueId === undefined) {
      // "没在研究"与"取消失败"是两件事：前者不该发请求，后者由服务端说原因
      this.rejectNeeds('tech', '现在没有在研究的项目，不用取消')
      return
    }
    this.track(TRACK_EVENTS.techCancel, { techId: queueId })
    const outcome = await this.api.techCancel()
    if (outcome.kind !== 'ok') {
      this.say('tech', outcome)
      return
    }
    await this.refresh('tech', 'resources')
    this.targets.techCancelled?.(outcome.data)
  }

  /**
   * 用一张研究加速道具推进当前研究。候选由 `effectKind` 筛（不按 id 硬编码），
   * 减多少秒、还剩多少秒、有没有完成都由服务端回。
   *
   * <p>与 `cancelResearch()` 同一条时序教训：**回执在重拉之后交**，因为它占的是队列那一行的位置。
   */
  async speedUpResearch(itemId: string, count: number): Promise<void> {
    this.track(TRACK_EVENTS.techSpeedUp, { itemId, count: trackParam(count) })
    const outcome = await this.api.techSpeedUp({ itemId, count })
    if (outcome.kind !== 'ok') {
      this.say('tech', outcome)
      return
    }
    await this.refresh('tech', 'bag', 'resources')
    this.targets.techSpeededUp?.(outcome.data)
  }

  /**
   * 玩家点了队列那一行的「加速」：先问用哪一张（只有 `REDUCE_RESEARCH_SECONDS` 那一种能用），
   * 手里一张都没有就明说 —— 不给一颗点开只会失败的键。
   */
  requestResearchSpeedUp(): void {
    const queueId = this.techResp?.queue.techId ?? null
    if (queueId === null || queueId === undefined) {
      this.rejectNeeds('tech', '现在没有在研究的项目，用不了加速')
      return
    }
    if (this.bagResp === null) {
      // "还没读到"与"手里没有"是两句话：写成后者会把一次读侧故障说成玩家的错
      this.rejectNeeds('tech', '道具清单还没读到，稍后再试')
      return
    }
    const options = buildResearchSpeedupChoices(this.bagResp)
    if (options.length === 0) {
      this.rejectNeeds('tech', '手里没有研究加速道具（建造令与训练令用不到研究上）')
      return
    }
    if (this.targets.researchSpeedupChoice === undefined) {
      this.rejectNeeds('tech', pickUnavailable('加速道具'))
      return
    }
    this.targets.researchSpeedupChoice(options, (picked) => {
      void this.speedUpResearch(picked.itemId, picked.count)
    })
  }

  // ---------- 装备实例页（V03-b-S1 读侧） ----------

  /**
   * 打开装备页。入口在武将页（由编排层发起）。**每次打开都重拉**：
   * 穿戴与强化都会改这里的数据（`wornByHeroId` / `forgeLevel`），复用旧值会显示一件已经被换下的装备。
   */
  async openEquip(heroId?: string): Promise<void> {
    this.track(TRACK_EVENTS.equipView)
    this.equipTarget = heroId === undefined
      ? null
      : { heroId, heroName: this.heroNameOf(heroId) }
    await this.loadEquip()
  }

  /**
   * 武将名字从**服务端给的武将列表**里查，查不到也不印 id（#255 的同一根因：id 不是名字）。
   */
  private heroNameOf(heroId: string): string {
    const hero = this.heroResp?.heroes?.find((h) => h.heroId === heroId)
    return hero === undefined ? '该武将' : hero.name
  }

  /**
   * 给选中的武将穿上某件（`uid`/`slot` 来自装备库那一行）。
   * 刷新 `hero` 与 `equip`：一行是"谁穿了什么"，另一行是"这件在谁身上"，两边都得跟着变。
   */
  equipWear(uid: string, slot: EquipSlot): Promise<void> {
    const heroId = this.equipTarget?.heroId
    if (heroId === undefined) {
      // 只有带着武将进来时视图才会给动作，这里不会发生；真发生了也什么都不发（不发一个注定被拒的请求）
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.heroEquip, { heroId, slot: trackParam(slot), action: 'wear' })
    return this.write('equip', this.api.heroEquip({ heroId, slot, equipUid: uid }), ['hero', 'equip'])
  }

  /**
   * 从选中武将身上卸下某个槽位（`equipUid: null` 就是卸下 —— 服务端的口径）。
   */
  equipTakeOff(slot: EquipSlot): Promise<void> {
    const heroId = this.equipTarget?.heroId
    if (heroId === undefined) {
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.heroEquip, { heroId, slot: trackParam(slot), action: 'takeOff' })
    return this.write('equip', this.api.heroEquip({ heroId, slot, equipUid: null }), ['hero', 'equip'])
  }

  /**
   * 强化**一件**装备一级（V11；B20 块②：纯消耗、必成）。
   *
   * <p><b>成功后重拉三样</b>：`equip`（等级、三维与下一级价格都在服务端那份视图里，**不本地 +1**）、
   * `resources`（扣的是铁，资源条必须跟着变）、`hero`（这件穿在身上时战力才涨 ——
   * 契约里 `powerDelta` 那句"不是 0 就说明它此刻确实作用于某个武将"就是这个意思）。
   *
   * <p><b>不带 heroId</b>：强化看的是"这一件"，与它此刻穿在谁身上无关；能不能强化由服务端的
   * `canForge` / `blockReason` 说了算，客户端不自己拿 `forgeLevel` 与 `forgeMax` 比一遍
   * （契约注释写明：两份判定的分叉不报错，症状是"按钮亮着却按失败"）。
   */
  forgeEquip(uid: string): Promise<void> {
    this.track(TRACK_EVENTS.equipForge)
    return this.write('equip', this.api.forgeEquip({ equipUid: uid }), ['equip', 'resources', 'hero'])
  }

  // ---------- 武将升级（V03-d：口径＝逐件选数量） ----------

  /**
   * 打开升级弹层。候选是背包里 `effectKind = GRANT_HERO_EXP` 的行 —— **不额外发请求**：
   * 背包没拉过就先拉一次（弹层空着比多一个请求更糟）。
   */
  async openExpPick(heroId: string): Promise<void> {
    if (this.bagResp === null) {
      await this.refresh('bag')
    }
    this.expPick = { heroId, heroName: this.heroNameOf(heroId), picks: {} }
    this.deliverExpPick()
  }

  /** 加减一件。夹取在纯逻辑里做（按到边界再按一下是正常操作，不该报错）。 */
  bumpExpPick(itemId: string, delta: number): void {
    if (this.expPick === null) {
      return
    }
    const rows = expPickRows(this.bagResp?.items ?? [])
    this.expPick = { ...this.expPick, picks: bumpPick(rows, itemId, delta) }
    this.deliverExpPick()
  }

  /**
   * 确认喂：只带**选了**的那些发上去。全 0 不发（服务端要求 `expItems` 非空，
   * 发了只是把一次失误变成一条错误提示）。
   *
   * <p>提交后即清空选择：失败（道具不够/已满级）时服务端的理由走统一上报口，
   * 玩家重新点「升级」会拿到一份干净的候选（而不是一份"上次选了但没发出去"的残留）。
   */
  confirmExpPick(): Promise<void> {
    const state = this.expPick
    if (state === null) {
      return Promise.resolve()
    }
    const payload = pickedPayload(expPickRows(this.bagResp?.items ?? [], state.picks))
    if (payload.length === 0) {
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.heroLevelUp, {
      heroId: trackParam(state.heroId), kinds: trackParam(payload.length),
    })
    this.expPick = null
    return this.write('hero', this.api.heroLevelUp({ heroId: state.heroId, expItems: [...payload] }),
      ['hero', 'bag'])
  }

  /** 组装并下发弹层视图。视图不认识计数规则（夹取 / 全 0 能不能发都在纯逻辑里）。 */
  private deliverExpPick(): void {
    if (this.expPick === null) {
      return
    }
    const view = buildExpPick(this.bagResp?.items ?? [], this.expPick.picks)
    this.targets.expPick?.(view, this.expPick.heroName)
  }

  /** 取消：关掉弹层，什么都不发（选择清掉，重开是干净的一份）。 */
  cancelExpPick(): void {
    this.expPick = null
  }

  // ---------- 武将觉醒（V03-d 第二条：一次只吃一块石，所以是单选） ----------

  /**
   * 打开觉醒弹层。候选取手里那份背包，"这一阶认哪块石"取手里那份 `/hero/list` 的两个数 ——
   * 都不额外发请求（武将页上那个「觉醒」按钮只会来自 heroResp，所以它必然已在）。
   */
  async openAwakenPick(heroId: string): Promise<void> {
    if (this.bagResp === null) {
      await this.refresh('bag')
    }
    const hero = this.heroResp?.heroes.find((h) => h.heroId === heroId)
    if (hero === undefined) {
      // 列表里没这个人就弹一个空弹层，比不弹更糟：玩家会以为觉醒线坏了
      return
    }
    this.awakenPick = {
      heroId, heroName: hero.name,
      stage: { awaken: hero.awaken, maxAwaken: hero.maxAwaken }, itemId: null,
    }
    this.deliverAwakenPick()
  }

  /** 选哪块石。**这里不判可用不可用**：判据在纯逻辑里，灰掉的行既点不动也发不出去。 */
  pickAwakenItem(itemId: string): void {
    if (this.awakenPick === null) {
      return
    }
    this.awakenPick = { ...this.awakenPick, itemId }
    this.deliverAwakenPick()
  }

  /**
   * 确认觉醒：只带**选中的那一块**发上去。没选中可用的石就不发
   * （服务端对未知 itemId 一律拒绝，发了只是把一次误点变成一条提示）。
   *
   * <p>提交后即关掉：成功后 `hero` 与 `bag` 都要重读（觉醒阶数与石头余数各在一边）。
   */
  confirmAwakenPick(): Promise<void> {
    const state = this.awakenPick
    if (state === null) {
      return Promise.resolve()
    }
    const view = buildAwakenPick(this.bagResp?.items ?? [], state.stage, state.itemId)
    const itemId = view.selectedItemId
    if (itemId === null) {
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.heroAwaken, {
      heroId: trackParam(state.heroId), itemId: trackParam(itemId),
      tier: trackParam(state.stage.awaken + 1),
    })
    this.awakenPick = null
    return this.write('hero', this.api.heroAwaken({ heroId: state.heroId, itemId, skillSlot: null }),
      ['hero', 'bag'])
  }

  /** 组装并下发弹层视图（哪块点亮、进度那一行，全在纯逻辑里判）。 */
  private deliverAwakenPick(): void {
    const state = this.awakenPick
    if (state === null) {
      return
    }
    this.targets.awakenPick?.(buildAwakenPick(this.bagResp?.items ?? [], state.stage, state.itemId),
      state.heroName)
  }

  /** 取消：关掉弹层，什么都不发。 */
  cancelAwakenPick(): void {
    this.awakenPick = null
  }

  // ---------- 武将技能（V03-d 最后一条：选一本书，槽位由那本书决定） ----------

  /**
   * 打开技能弹层。候选取手里那份背包，两路技能的当前等级与上限取手里那份 `/hero/list` ——
   * 都不额外发请求（武将页上那个「技能」按钮只会来自 heroResp）。
   */
  async openSkillPick(heroId: string): Promise<void> {
    if (this.bagResp === null) {
      await this.refresh('bag')
    }
    const hero = this.heroResp?.heroes.find((h) => h.heroId === heroId)
    if (hero === undefined) {
      return
    }
    this.skillPick = {
      heroId, heroName: hero.name,
      stage: { mainLevel: hero.mainSkillLevel, subLevel: hero.subSkillLevel, maxLevel: hero.maxSkillLevel },
      itemId: null,
    }
    this.deliverSkillPick()
  }

  /** 选哪本书。**这里不判可用不可用**：判据在纯逻辑里，灰掉的行既点不动也发不出去。 */
  pickSkillItem(itemId: string): void {
    if (this.skillPick === null) {
      return
    }
    this.skillPick = { ...this.skillPick, itemId }
    this.deliverSkillPick()
  }

  /**
   * 确认升技能：`skillSlot` **只从选中的那本书拿**（`effectTarget`）——
   * 客户端先选槽位再挑一本书，就是把自己送进服务端那句"书与槽位不一致"的拒绝里。
   */
  confirmSkillPick(): Promise<void> {
    const state = this.skillPick
    if (state === null) {
      return Promise.resolve()
    }
    const view = buildSkillPick(this.bagResp?.items ?? [], state.stage, state.itemId)
    const itemId = view.selectedItemId
    const slot = view.selectedSlot
    if (itemId === null || slot === null) {
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.heroSkillUp, {
      heroId: trackParam(state.heroId), itemId: trackParam(itemId), slot: trackParam(slot),
    })
    this.skillPick = null
    return this.write('hero', this.api.heroSkillUp({ heroId: state.heroId, itemId, skillSlot: slot }),
      ['hero', 'bag'])
  }

  /** 组装并下发弹层视图（哪一本点亮、升哪一路，全在纯逻辑里判）。 */
  private deliverSkillPick(): void {
    const state = this.skillPick
    if (state === null) {
      return
    }
    this.targets.skillPick?.(buildSkillPick(this.bagResp?.items ?? [], state.stage, state.itemId),
      state.heroName)
  }

  /** 取消：关掉弹层，什么都不发。 */
  cancelSkillPick(): void {
    this.skillPick = null
  }

  // ---------- 武将碎片合成（V03-d 第六条线：合的是"还没有的武将"，所以入口在页眉、不在行上） ----------

  /**
   * 打开合成弹层。门槛、余额、候选名单全取手里那份 `/hero/list` —— 不额外发请求
   * （入口按钮就在武将页页眉，heroResp 必然已在）。
   *
   * <p><b>为什么这里没有 heroId 参数</b>：合成得到的是玩家**没有**的那个武将，而武将页每一行
   * 都是已有的 —— 从行进出的话第一下就撞上服务端那句"已拥有不能重复合成"。
   */
  async openComposePick(): Promise<void> {
    if (this.heroResp === null) {
      await this.refresh('hero')
    }
    if (this.heroResp === null) {
      // 读不到武将列表就弹一个空弹层，比不弹更糟：玩家会以为合成线坏了
      return
    }
    this.composePick = { heroId: null }
    this.deliverComposePick()
  }

  /** 选哪个武将。**这里不判够不够**：判据在纯逻辑里，灰掉的行既点不动也发不出去。 */
  pickComposeHero(heroId: string): void {
    if (this.composePick === null) {
      return
    }
    this.composePick = { heroId }
    this.deliverComposePick()
  }

  /**
   * 确认合成：只带**选中的那个武将**发上去。碎片不够的那一行压根发不出去 ——
   * 服务端按 hero_rarity 扣碎片、扣不动就拒绝，客户端不该把一次误点变成一条拒绝提示。
   *
   * <p>成功后 `hero` 与 `bag` 都要重读：新武将进名册、碎片从背包扣，一边一样。
   */
  confirmComposePick(): Promise<void> {
    const state = this.composePick
    if (state === null) {
      return Promise.resolve()
    }
    const view = buildComposeView(this.heroResp?.fragments ?? [], state.heroId)
    const heroId = view.selectedHeroId
    if (heroId === null) {
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.heroCompose, { heroId: trackParam(heroId) })
    this.composePick = null
    return this.write('hero', this.api.heroCompose({ heroId }), ['hero', 'bag'])
  }

  /** 组装并下发弹层视图（够不够、键上写什么，全在纯逻辑里判）。 */
  private deliverComposePick(): void {
    const resp = this.heroResp
    if (this.composePick === null || resp === null) {
      return
    }
    this.targets.composePick?.(buildComposeView(resp.fragments, this.composePick.heroId),
      buildHeroPanel(resp).fragmentTexts)
  }

  /** 取消：关掉弹层，什么都不发。 */
  cancelComposePick(): void {
    this.composePick = null
  }

  // ---------- 编队编辑（B06 §4：三套预设 × 每队三人；`/hero/lineup` 此前生产零调用点） ----------

  /**
   * 打开某一队的编辑器。槽位从手里那份 `/hero/list` 的 `lineups` 起步 ——
   * 编队行本来就是从那份响应画出来的，再拉一次只是多一个请求与一次不一致的机会。
   */
  async openLineupEdit(presetIndex: number): Promise<void> {
    if (this.heroResp === null) {
      await this.refresh('hero')
    }
    const resp = this.heroResp
    if (resp === null) {
      return
    }
    const lineup = resp.lineups.find((row) => row.presetIndex === presetIndex)
    this.lineupEdit = {
      presetIndex,
      slots: {
        main: lineup?.main ?? null,
        sub1: lineup?.sub1 ?? null,
        sub2: lineup?.sub2 ?? null,
      },
      pickingSlot: null,
    }
    this.deliverLineupEdit()
  }

  /** 点某一槽 = 要换这一槽的人。名单由纯逻辑出（谁灰、为什么灰、谁在别的队）。 */
  pickLineupSlot(slot: LineupSlot): void {
    if (this.lineupEdit === null) {
      return
    }
    this.lineupEdit = { ...this.lineupEdit, pickingSlot: slot }
    this.deliverLineupEdit()
  }

  /** 换上新选的人（只改编辑中的槽位，**不发请求**：编队是"改完一起提交"的动作）。 */
  chooseLineupHero(heroId: string): void {
    const state = this.lineupEdit
    if (state === null || state.pickingSlot === null) {
      return
    }
    this.lineupEdit = {
      ...state,
      slots: { ...state.slots, [state.pickingSlot]: heroId },
      pickingSlot: null,
    }
    this.deliverLineupEdit()
  }

  /** 清空正在选的那一槽（服务端接受 null = 这一位没人）。 */
  clearLineupSlot(): void {
    const state = this.lineupEdit
    if (state === null || state.pickingSlot === null) {
      return
    }
    this.lineupEdit = {
      ...state,
      slots: { ...state.slots, [state.pickingSlot]: null },
      pickingSlot: null,
    }
    this.deliverLineupEdit()
  }

  /**
   * 保存这一队：一次发出三槽的最终状态。
   *
   * <p>**逐次换人不逐次发**：每换一次就发一次的后果是中途失败时存档停在"半支队"，
   * 而玩家以为保存过了。发不出去的那两种（名册没读到 / 选的人不在名册里）只说理由，不发。
   */
  saveLineup(): Promise<void> {
    const state = this.lineupEdit
    const resp = this.heroResp
    if (state === null || resp === null) {
      return Promise.resolve()
    }
    const view = buildLineupEdit(resp.heroes, resp.lineups, state.presetIndex,
      state.slots, state.pickingSlot)
    if (!view.canSave) {
      this.rejectNeeds('hero', view.saveText)
      return Promise.resolve()
    }
    this.track(TRACK_EVENTS.heroLineupSave, {
      presetIndex: trackParam(String(state.presetIndex)),
      main: trackParam(state.slots.main ?? ''),
      sub1: trackParam(state.slots.sub1 ?? ''),
      sub2: trackParam(state.slots.sub2 ?? ''),
    })
    this.lineupEdit = null
    return this.write('hero',
      this.api.heroSetLineup(lineupBody(state.presetIndex, state.slots)), ['hero'])
  }

  /** 取消：关掉编辑器。编辑中的槽位随之丢弃（没保存过就不该有任何东西被改）。 */
  cancelLineupEdit(): void {
    this.lineupEdit = null
  }

  /** 组装并下发编队编辑视图。 */
  private deliverLineupEdit(): void {
    const state = this.lineupEdit
    const resp = this.heroResp
    if (state === null || resp === null) {
      return
    }
    this.targets.lineupEdit?.(buildLineupEdit(resp.heroes, resp.lineups,
      state.presetIndex, state.slots, state.pickingSlot))
  }

  // ---------- 抽卡（B06 §2 抽取与 §6 合规公示；公示那半在 scene/GachaDisclosureView） ----------

  /**
   * 打开抽卡面板。卡池与两份余额各存一份快照，缺哪一份补哪一份 ——
   * 首屏预拉通常已经带着 bag 与 resources，所以正常路径这里是**零额外请求**。
   */
  async openGacha(): Promise<void> {
    if (this.gachaResp === null) {
      await this.refresh('gacha')
    }
    if (this.resourceResp === null) {
      await this.refresh('resources')
    }
    if (this.bagResp === null) {
      await this.refresh('bag')
    }
    this.deliverGacha()
  }

  /** 换选中哪个池：只重画，不发请求（概率那一屏走「概率公示」，是另一次读）。 */
  selectGachaPool(poolId: string): void {
    this.gachaPoolId = poolId
    this.gachaNotice = null
    this.deliverGacha()
  }

  /**
   * 抽一次 / 抽十次。
   *
   * <p><b>灰掉的键不发请求</b>：抽满与余额不足这两种情况服务端都算好了理由，
   * 客户端把同一句原样说出来即可（与商店那条纪律同一句），发一次只是多一条拒绝日志。
   *
   * <p><b>失败的理由要进面板</b>，不能只进 console：抽卡是花钱的动作，"为什么没抽成"
   * 玩家必须看得见 —— 而 `write` 的统一出口只到 `targets.error`（那里是 console.warn）。
   * 成功后重拉四样：卡池（已抽次数变了）、武将（新将进名册）、背包与资源（扣的与转的各在一边）。
   */
  async drawGacha(count: number): Promise<void> {
    const resp = this.gachaResp
    if (resp === null) {
      this.rejectNeeds('gacha', '卡池还没拉回来，稍后再试')
      return
    }
    const view = buildGachaPanel(resp.pools, this.gachaBalances(), this.gachaPoolId)
    const row = view.selected
    if (row === null) {
      this.rejectNeeds('gacha', '先选一个卡池')
      return
    }
    const reason = count === TEN_DRAW_COUNT ? row.tenReason : row.onceReason
    if (reason !== null) {
      this.rejectNeeds('gacha', reason)
      return
    }
    this.track(TRACK_EVENTS.gachaDraw, {
      poolId: trackParam(row.poolId), count: trackParam(String(count)),
    })
    const outcome = await this.api.gachaDraw({ poolId: row.poolId, count })
    if (outcome.kind !== 'ok') {
      this.gachaNotice = AppRoot.reason(outcome)
      this.say('gacha', outcome)
      this.deliverGacha()
      return
    }
    this.gachaLast = outcome.data
    this.gachaNotice = null
    // 'gacha' 放最后：`refresh` 是**顺序** await 的，先重拉卡池就会用抽之前的余额快照去画
    // （表现是"刚抽完那 1200 金币还在"，要等下一次重画才对得上）
    await this.refresh('hero', 'bag', 'resources', 'reddot', 'gacha')
  }

  /** 抽卡面板比余额要的那两份快照。缺哪份就是真没读到，纯逻辑会写成"余额还没读到"。 */
  private gachaBalances(): GachaBalances {
    return {
      resources: this.resourceResp?.resources ?? [],
      items: this.bagResp?.items ?? [],
    }
  }

  /**
   * 拉选中那个池的概率公示并交给合规那一屏（B06 §6：原文呈现，不得删减或折叠）。
   *
   * <p>每次点都拉而不是缓存：公示数字是"服务端此刻怎么说"，缓存到热更之后就会与
   * 真实掉率脱节 —— 而脱节的公示是要被追责的那一份。
   */
  async openGachaProbability(): Promise<void> {
    const resp = this.gachaResp
    if (resp === null) {
      this.rejectNeeds('gacha', '卡池还没拉回来，稍后再试')
      return
    }
    const view = buildGachaPanel(resp.pools, this.gachaBalances(), this.gachaPoolId)
    const poolId = view.selectedPoolId
    if (poolId === null) {
      this.rejectNeeds('gacha', '没有可公示的卡池')
      return
    }
    const outcome = await this.api.gachaProbability(poolId)
    if (outcome.kind !== 'ok') {
      this.say('gacha', outcome)
      return
    }
    // 四档之和与"恰好一种计价"那两条断言在 buildDisclosure 里，它可能抛：
    // 抛出来就是公示不实，宁可一句错误糊在脸上，也不要把加起来 99% 的面板摆给玩家
    try {
      this.targets.gachaDisclosure?.(buildDisclosure(outcome.data))
    } catch (error) {
      this.rejectNeeds('gacha', `概率公示数据异常：${(error as Error).message}`)
    }
  }

  /**
   * 打开「抽取记录」（B15 §三 合规三件套的第三件：最近 N 次可查）。
   *
   * <p><b>每次打开都重拉</b>：它要答的问题是"我刚抽的那一枪在不在"，缓存一份就会漏掉刚抽的那次。
   * 翻页只重画不重拉 —— 服务端给的是完整窗口（最近 50 条），翻页是本地切段。
   */
  async openGachaHistory(): Promise<void> {
    this.gachaHistoryPage = 0
    const outcome = await this.api.gachaHistory()
    if (outcome.kind !== 'ok') {
      this.rejectNeeds('gacha', AppRoot.reason(outcome))
      return
    }
    this.gachaHistoryResp = outcome.data
    this.deliverGachaHistory()
  }

  /** 翻页：只重画不重拉。页码越界由数据层夹回，这里不做第二遍判定。 */
  turnGachaHistoryPage(delta: number): void {
    const view = this.gachaHistory
    if (view === null) {
      return
    }
    this.gachaHistoryPage = view.page + delta
    this.deliverGachaHistory()
  }

  /**
   * 组装并递一次记录页。
   *
   * <p>名字表来自**随行下发的那两份**（`/gacha/pools` 与 `/hero/list`）：记录里只有
   * `poolId` / `heroId`，客户端没有 gacha 表也没有 hero 表，自己拼名字会在表改名之后对玩家说谎
   * （#255 建筑名 / #268 资源名 / #303 兵种名那一族）。
   */
  private deliverGachaHistory(): void {
    const resp = this.gachaHistoryResp
    if (resp === null) {
      return
    }
    const view = buildGachaHistory(resp, {
      poolNames: new Map((this.gachaResp?.pools ?? []).map(pool => [pool.poolId, pool.name])),
      heroNames: new Map((this.heroResp?.heroes ?? []).map(hero => [hero.heroId, hero.name])),
    }, this.gachaHistoryPage)
    this.gachaHistory = view
    this.gachaHistoryPage = view.page
    this.targets.gachaHistory?.(view)
  }

  // ---------- 国家（V13-S1 · B13：入籍 + 国库） ----------

  /**
   * 打开国家面板。每次都重拉：国家身份、官员、国库余额与流水都是会变的（周税惰性结清、别人也在花钱），
   * 拿缓存会让玩家照着一份过期的账做决定。
   *
   * <p>**"不在任何国家"走的是正常路径**：13000 `NATION_NOT_FOUND` 被折成 `nationResp = null`，
   * 与网络失败严格分开 —— 后者要提示重试，前者是面板的起点。
   */
  async openNation(): Promise<void> {
    this.nationNotice = null
    const view = await this.api.nationView()
    if (view.kind === 'ok') {
      this.nationResp = view.data
    } else if (view.kind === 'biz' && view.code === AppRoot.NATION_NOT_FOUND) {
      this.nationResp = null
    } else {
      // 读不到身份就不能画面板：宁可说一句"这次没读到"，也不要画一份"你没有国家"的假面板
      this.rejectNeeds('nation', AppRoot.reason(view))
      return
    }
    this.nationTreasuryResp = null
    if (this.nationResp !== null) {
      // 在国里才读国库：不在国里发这一枪只会换一个同义的错误码回来
      const treasury = await this.api.nationTreasury()
      if (treasury.kind === 'ok') {
        this.nationTreasuryResp = treasury.data
      } else {
        this.say('nation', treasury)
      }
      // 国家层的权限位（V13-d：灰键的权威来源）。**只有在国里才有意义**，
      // 所以与国库一起在这一支里拉，而不是并进进社交页那次 pullSocialGates
      await this.loadNationPermissions()
      // 国战状态：轻量读（一次内存/库读，顺带惰性结算到期的那一场）。**只在国里拉** ——
      // 不在国里时那一页根本不画（sections 为 null），发了只是换一个没人看的响应回来。
      await this.loadNationWar()
    } else {
      // 不在国里：把上一次那份清掉。留着就是让一个已经离开的国家继续授权
      this.permissions = withoutNationPermissions(this.permissions)
      await this.loadNationCandidates()
    }
    this.deliverNation()
  }

  /**
   * 拉一次国家层的权限码（`GET /social/permissions?scope=NATION`）。
   *
   * <p>失败时**把 `nationLoaded` 留在 false**：面板据此把三颗键画成"权限还没读到"，
   * 而不是画成"你不能" —— 一次读失败被说成身份结论，玩家会去申请升职。
   */
  private async loadNationPermissions(): Promise<void> {
    const outcome = await this.api.socialPermissions('NATION')
    if (outcome.kind !== 'ok') {
      this.permissions = withoutNationPermissions(this.permissions)
      this.say('nation', outcome)
      return
    }
    this.permissions = withPermissionScope(this.permissions, 'NATION',
      outcome.data.permissions, outcome.data.role, false)
  }

  /**
   * 可加入的国家列表。
   *
   * <p><b>服务端没有 `/nation/list` 这个端点**（B13 交付的只有 found/join/leave/disband/appoint/
   * diplomacy/view/treasury/…），所以候选名单取自**国家榜**（`GET /rank/list?type=NATION`）：
   * 那一栏的 `id` 就是 nationId、`name` 就是国名，两样都是服务端下发的 ——
   * 客户端一份表都不用抄（红线：不抄配置表）。"榜上有的国家"不等于"全部国家"，
   * 面板据此写「不是全部」。
   */
  /**
   * 重新读一次关系表。**宣战之后必须调**：宣战会把对目标国那一行置成 HOSTILE，
   * 而国战状态里没有关系表 —— 不重读的话玩家切到外交页看到的还是旧关系。
   * 失败时**不清空手里那一份**（与科技/国策同一条纪律）。
   */
  private async reloadNationRelations(): Promise<void> {
    const outcome = await this.api.nationRelations()
    if (outcome.kind === 'ok') {
      this.nationRelations = outcome.data.allRelations
    }
  }

  private async loadNationCandidates(): Promise<void> {
    const outcome = await this.api.rankList('NATION', 1, AppRoot.NATION_CANDIDATE_ROWS)
    if (outcome.kind === 'ok') {
      this.nationCandidates = outcome.data.entries.map(entry => ({
        nationId: entry.id, name: entry.name,
      }))
    } else {
      this.nationCandidates = []
      this.say('nation', outcome)
    }
  }

  /**
   * 建国。
   *
   * <p>三条前置（主城 16 级 / 开服 D14 / 在某联盟中）全在服务端判，协议里也没有
   * 「我已满足前置」这种字段 —— 所以客户端不预判，被拒时把服务端那句 `detail` 原样显示。
   */
  async foundNation(name: string, capitalX: number, capitalY: number): Promise<void> {
    const outcome = await this.api.foundNation({ name, capitalX, capitalY })
    this.track(TRACK_EVENTS.nationFound)
    if (outcome.kind !== 'ok') {
      this.nationNotice = AppRoot.reason(outcome)
      this.deliverNation()
      return
    }
    this.nationResp = outcome.data
    this.nationNotice = `已建国：${outcome.data.nation.name}`
    // 成功语气：这一句是"做成了"，不能染成失败红（V13-S2 目视截图抓到的那处）
    this.nationNoticeTone = 'ok'
    await this.reloadNationTreasury()
    this.deliverNation()
  }

  /** 联盟入籍。能不能加入（冷却 / 名额 / 是否已属他国）由服务端判，拒绝理由原样显示。 */
  async joinNation(nationId: string): Promise<void> {
    const outcome = await this.api.joinNation({ nationId })
    this.track(TRACK_EVENTS.nationJoin, { scope: 'ALLIANCE' })
    if (outcome.kind !== 'ok') {
      this.nationNotice = AppRoot.reason(outcome)
      this.deliverNation()
      return
    }
    this.nationResp = outcome.data
    this.nationNotice = `已加入 ${outcome.data.nation.name}`
    // 成功语气：这一句是"做成了"，不能染成失败红（V13-S2 目视截图抓到的那处）
    this.nationNoticeTone = 'ok'
    await this.reloadNationTreasury()
    this.deliverNation()
  }

  /**
   * 退出国家。
   *
   * <p>响应只回"什么时候能再入籍"，所以**清掉手里那份国家视图** ——
   * 留着它会让面板继续画一个我已经被踢出国的国家（下一读才回 13000）。
   * 冷却到期时刻由服务端下发，客户端不自己加 24 小时。
   */
  async leaveNation(): Promise<void> {
    const outcome = await this.api.leaveNation()
    // 埋点打在**这一枪**上，不在共用的收尾里：`recordLeave` 只是复用响应形状的那段收尾，
    // 意图的位置要落在"玩家按了退国"的方法上（`check-track-coverage` 也是这么判的）
    this.track(TRACK_EVENTS.nationLeave)
    if (outcome.kind !== 'ok') {
      this.nationNotice = AppRoot.reason(outcome)
      this.deliverNation()
      return
    }
    this.recordLeave(outcome.data)
  }

  /** 解散国家。同上：回的是审计四件套，**没有国家视图可留**。 */
  async disbandNation(): Promise<void> {
    const outcome = await this.api.disbandNation()
    this.track(TRACK_EVENTS.nationDisband)
    if (outcome.kind !== 'ok') {
      this.nationNotice = AppRoot.reason(outcome)
      this.deliverNation()
      return
    }
    this.nationResp = null
    this.nationTreasuryResp = null
    // 亡国了，那一份国家权限也必须跟着没（同上：留着就是让一个不存在的国家继续授权）
    this.permissions = withoutNationPermissions(this.permissions)
    this.nationNotice = `${outcome.data.nationName} 已解散：`
      + `${outcome.data.memberAllianceCount} 个成员联盟进入入籍冷却，`
      + `核销国库 ${amountText(outcome.data.treasuryWrittenOff)}`
    // 成功语气：这一句是"做成了"，不能染成失败红（V13-S2 目视截图抓到的那处）
    this.nationNoticeTone = 'ok'
    this.deliverNation()
  }

  /** 退国成功后的收尾：清掉手里那份国家视图（下一读才回 13000），并把冷却提示摆出来。 */
  private recordLeave(resp: NationLeaveResp): void {
    this.nationResp = null
    this.nationTreasuryResp = null
    // 国籍没了，那一份国家权限也必须跟着没 —— 留着就是让一个已经离开的国家继续授权
    this.permissions = withoutNationPermissions(this.permissions)
    this.nationNotice = `已退出 ${resp.nationName}：${cooldownText(resp.cooldownUntil, resp.serverNow)}`
    // 成功语气：这一句是"做成了"，不能染成失败红（V13-S2 目视截图抓到的那处）
    this.nationNoticeTone = 'ok'
    this.deliverNation()
  }

  /**
   * 国库支出。
   *
   * <p>**两种失败是两个错误码，客户端不合成一句话**：13010 余额不足（下一步是等进钱）、
   * 13011 超本周限额（下一步是等下周额度刷新）。合成一句"国库不够"会让玩家做错那一步。
   */
  async spendTreasury(draft: SpendDraft): Promise<void> {
    // 落点是枚举（`TreasuryPayeeType` / `TreasurySink`），而表单那侧是展示态的 string ——
    // 这里做一次**词面**收窄：选项只有那两个 sink（`spendSinkOptions` 给的），别的值一律不成立。
    // 写成 `as TreasurySink` 的话，协议将来多一个用途就会静默地按错的发出去。
    const sink = draft.payeeType === 'SINK' && (draft.sink === 'NATIONAL_TECH' || draft.sink === 'WAR_BOOST')
      ? draft.sink
      : null
    if (draft.payeeType === 'SINK' && sink === null) {
      this.nationNotice = '先选一个消耗性用途'
      this.deliverNation()
      return
    }
    const outcome = await this.api.spendTreasury({
      amount: draft.amount,
      reason: draft.reason,
      payeeType: draft.payeeType,
      payeeId: draft.payeeType === 'PLAYER' ? draft.payeeId : null,
      sink,
    })
    if (outcome.kind !== 'ok') {
      this.nationNotice = AppRoot.reason(outcome)
      this.deliverNation()
      return
    }
    this.track(TRACK_EVENTS.treasurySpend, {
      payeeType: draft.payeeType,
      amount: String(draft.amount),
    })
    this.nationNotice = `已支出 ${amountText(outcome.data.amount)}，国库余额 ${amountText(outcome.data.balance)}`
    // 成功语气：这一句是"做成了"，不能染成失败红（V13-S2 目视截图抓到的那处）
    this.nationNoticeTone = 'ok'
    await this.reloadNationTreasury()
    this.deliverNation()
  }

  /**
   * 拉一次可收款的成员名单（`PLAYER` 落点专用）。
   *
   * <p>用手里那份联盟成员 diff（`/alliance/sync` 已经推过的），**不额外发请求** ——
   * 国库只能发给本盟成员，而本盟名单社交页一直在维护。
   */
  requestNationPayees(): void {
    this.nationPayeesLoaded = true
    this.targets.nationPayees?.(this.allianceMembers.map(member => ({ id: member.id, name: member.name })))
  }

  /** 名单还没拉过时点「发给成员」会来这么一下：社交页打开过就一定拉过一次，这里不重复。 */
  get nationPayeesReady(): boolean {
    return this.nationPayeesLoaded
  }

  /** 国库那一块单独重拉（写操作之后要看到最新余额与流水）。 */
  private async reloadNationTreasury(): Promise<void> {
    if (this.nationResp === null) {
      this.nationTreasuryResp = null
      return
    }
    const treasury = await this.api.nationTreasury()
    if (treasury.kind === 'ok') {
      this.nationTreasuryResp = treasury.data
    } else {
      this.say('nation', treasury)
    }
  }

  /**
   * 组装并递一次国家面板。
   *
   * <p>成员名表来自**手里那份联盟成员 diff**（id → 昵称）：国库流水里的"谁做的 / 支给谁"只有
   * 玩家 id，客户端不印裸 id（同 #255 建筑名 / #268 资源名那一族）。
   */
  private deliverNation(): void {
    const members = this.allianceMembers.map(member => ({ id: member.id, name: member.name }))
    const view = buildNationPanel({
      nation: this.nationResp?.nation ?? null,
      treasury: this.nationTreasuryResp,
      candidates: this.nationCandidates,
      playerId: this.playerId ?? '',
      memberNames: new Map(members.map(member => [member.id, member.name])),
      notice: this.nationNotice,
      noticeTone: this.nationNoticeTone,
      permissions: this.permissions.nation,
      permissionsLoaded: this.permissions.nationLoaded,
      tab: this.nationTab,
      sections: buildNationSections(this.nationTechResp, this.nationRelations,
        this.nationCandidates, members, this.permissions.nation, this.permissions.nationLoaded,
        this.nationPolicyPanel(), this.nationWarResp),
    })
    this.targets.nation?.(view)
  }

  /**
   * 国策那一页的视图模型。
   *
   * <p><b>「没拉过」与「拉到了」要能分开</b>：拉之前给 null，面板说「这一次没拉到」；
   * 拉到了而本轮一条提案都没有，给的是「本轮还没有提案」。
   * 两者混成同一个空面板，玩家会以为这个国家没有国策可议。
   */
  private nationPolicyPanel(): PolicyPanel | null {
    const resp = this.nationPolicyResp
    if (resp === null) {
      return null
    }
    return buildPolicyPanel(resp, this.playerId ?? '')
  }

  // ---------- 国家 S2：页签 + 科技 / 外交 / 任命 ----------

  /**
   * 切页签。
   *
   * <p><b>每次切都重拉当前那一份</b>：国家等级、官员、国库余额与关系都一直在变
   * （周税惰性结清、别人也在花钱与改关系），拿缓存会让玩家照着一份过期的账做决定。
   *
   * <p>切到「科技」时才发 `/nation/tech` 那���枪 —— 打开面板不预拉它：
   * 那是这一屏里最贵的一份，而大多数玩家只是来看一眼国库。
   */
  async selectNationTab(tab: NationTabKey): Promise<void> {
    this.nationTab = tab
    this.nationNotice = null
    if (tab === 'TECH' && this.nationTechResp === null && this.nationResp !== null) {
      await this.loadNationTech()
    }
    if (tab === 'DIPLO') {
      // 外交页每次切都重读一次关系表：**别的写入也会改它**（宣战把目标国置 HOSTILE、
      // 对方亡国会把它从表里去掉），只靠写口回的那一份会旧。
      await this.reloadNationRelations()
    }
    if ((tab === 'DIPLO' || tab === 'WAR') && this.nationCandidates.length === 0) {
      // 外交页与国战页的候选目标都来自国家榜（服务端没有"列出全部国家"的端点）：
      // 国战页也要它 —— 宣战要选一个国家，而候选从同一次读里来（不另开一个口）
      await this.loadNationCandidates()
    }
    // 国策页**每次切都重拉**：轮次是在服务端惰性推进的，而投票窗只有 24 小时 ——
    // 拿缓存的话玩家会在窗口已经关掉的屏上点「赞成」，点回去才被拒。
    if (tab === 'POLICY' && this.nationResp !== null) {
      await this.loadNationPolicy()
    }
    // 国战页**每次切都重拉**（与国策同一条理由，而且更硬）：读这一下顺带把到期的仗结算掉，
    // 剩余时间也是服务端在那一次读里现算的 —— 拿缓存会让玩家盯着一份停住的时间。
    if (tab === 'WAR' && this.nationResp !== null) {
      await this.loadNationWar()
    }
    this.deliverNation()
  }

  /** 拉一次国策轮次。失败时理由进 notice，**不清空手里那一份**（与科技同一条纪律）。 */
  private async loadNationPolicy(): Promise<void> {
    const outcome = await this.api.nationPolicy()
    if (outcome.kind === 'ok') {
      this.nationPolicyResp = outcome.data
      return
    }
    this.nationPolicyResp = null
    this.nationNotice = AppRoot.reason(outcome)
    this.nationNoticeTone = 'warn'
  }

  /**
   * 提案。
   *
   * <p>回执带整份轮次视图，所以**不再多发一次查询** —— 多发一次就会看到两个时刻的数
   * （提案刚被投掉、面板还挂着上一份）。
   */
  async proposeNationPolicy(policyId: string): Promise<void> {
    if (this.nationPolicyResp === null) {
      return
    }
    const outcome = await this.api.proposeNationPolicy({ policyId })
    if (outcome.kind === 'ok') {
      this.nationPolicyResp = outcome.data.round
      const name = outcome.data.round.policies.find(p => p.policyId === policyId)?.name ?? '那一条国策'
      this.nationNotice = `已把「${name}」放进本轮提案，等开票`
      this.nationNoticeTone = 'ok'
      this.track(TRACK_EVENTS.nationPolicyPropose, { policy: policyId })
    } else {
      this.nationNotice = AppRoot.reason(outcome)
      this.nationNoticeTone = 'warn'
    }
    this.deliverNation()
  }

  /**
   * 投票。
   *
   * <p><b>不做本地乐观更新</b>：票数与参与者名单都从服务端那一份账本算出来，
   * 本地先把数字 +1 的话，屏上会出现「赞成了但名单里没有我」的那一刻。
   */
  async voteNationPolicy(proposalId: string, support: boolean): Promise<void> {
    if (this.nationPolicyResp === null) {
      return
    }
    const outcome = await this.api.voteNationPolicy({ proposalId, support })
    if (outcome.kind === 'ok') {
      this.nationPolicyResp = outcome.data.round
      this.nationNotice = support ? '已投赞成' : '已投反对'
      this.nationNoticeTone = 'ok'
      this.track(TRACK_EVENTS.nationPolicyVote, { support: support ? 'yes' : 'no' })
    } else {
      this.nationNotice = AppRoot.reason(outcome)
      this.nationNoticeTone = 'warn'
    }
    this.deliverNation()
  }

  /** 拉一次国家科技。失败时理由进 notice，**不清空手里那一份**（与榜/赛季同一条纪律）。 */
  /**
   * 拉一次国战状态。失败时理由进 notice，**不清空手里那一份**（与科技同一条纪律）。
   */
  private async loadNationWar(): Promise<void> {
    const outcome = await this.api.warStatus()
    if (outcome.kind === 'ok') {
      this.nationWarResp = outcome.data
      return
    }
    this.nationWarResp = null
    this.nationNotice = AppRoot.reason(outcome)
    this.nationNoticeTone = 'warn'
  }

  private async loadNationTech(): Promise<void> {
    const outcome = await this.api.nationTech()
    if (outcome.kind === 'ok') {
      this.nationTechResp = outcome.data
      return
    }
    this.nationTechResp = null
    this.nationNotice = AppRoot.reason(outcome)
    this.say('nation', outcome)
  }

  /**
   * 研究一级国家科技（国库出资）。
   *
   * <p>成功后**立刻重拉科技表**：等级、下一级花费与国库余额全变了，
   * 而这三样都由服务端算 —— 客户端改一个 `level + 1` 就是第二个家（#281 那一族）。
   */
  async researchNationTech(techId: string): Promise<void> {
    const outcome = await this.api.researchNationTech({ techId })
    if (outcome.kind !== 'ok') {
      this.nationNotice = AppRoot.reason(outcome)
      this.deliverNation()
      return
    }
    this.track(TRACK_EVENTS.nationTechResearch, {
      techId,
      cost: String(outcome.data.costTreasury),
    })
    // **说行名不说 techId**：行 id 是 `nation_tech_wood` 这种内部值，印上玩家面就是
    // 「不许把内部 id / 枚举原文印给玩家」那条红线（S1 的国库流水已经栽过一次）。
    // 名字从刚拉的那份科技表里按 id 取 —— 服务端下发的，客户端不自己拼。
    const techName = this.nationTechResp?.techs.find(tech => tech.techId === techId)?.name ?? '那一行'
    this.nationNotice = `研究完成：${techName} 到 Lv${outcome.data.level}，`
      + `花掉 ${amountText(outcome.data.costTreasury)}，国库剩 ${amountText(outcome.data.treasuryAfter)}`
    // 成功语气：这一句是"做成了"，不能染成失败红（V13-S2 目视截图抓到的那处）
    this.nationNoticeTone = 'ok'
    await this.loadNationTech()
    await this.reloadNationTreasury()
    this.deliverNation()
  }

  /**
   * 变更与另一个国家的外交关系。
   *
   * <p>**成功后整张关系表换掉**（`allRelations` 是变更之后那张全表），
   * 于是第一次打完交道之后，这一页就能显示"与所有国家现在各是什么关系"。
   */
  /**
   * 宣战（B13 §一 §7 的开局那一步）。**不可逆、影响全服**：面板那边要按两次才走到这里
   * （第一次只是武装），这里只管发那一枪并把结果折成一句给玩家看的话。
   *
   * <p><b>冷却与权限一律由服务端判</b>：客户端不自己算 24 小时、也不自己查"我是不是国王"——
   * 两者服务端都会给带理由的拒绝码，原样显示。自己算一遍就是留一个与表分叉的第二真源，
   * 而表改了的那天症状是"面板上那颗键亮着、点下去被拒"（V13 那轮已经栽过一次）。
   *
   * <p>成功之后**重拉一次国战状态**（写口回的也是那一份）：面板随即从空态变成"两行参战方"，
   * 而不用等玩家手动切页签。
   */
  async declareNationWar(targetNationId: string): Promise<void> {
    const name = this.nationCandidates.find(item => item.nationId === targetNationId)?.name ?? '那个国家'
    const outcome = await this.api.declareWar({ targetNationId })
    if (outcome.kind !== 'ok') {
      this.nationNotice = AppRoot.reason(outcome)
      this.nationNoticeTone = 'warn'
      this.deliverNation()
      return
    }
    this.nationWarResp = outcome.data
    // 宣战改了外交关系（对目标国置 HOSTILE）⇒ 手里那张关系表旧了，当场重读一次，
    // 免得玩家切到外交页看到"宣战了却没敌对"
    await this.reloadNationRelations()
    // 只记"做成了"的那一枪（与 `setNationRelation` 同口径）：被冷却/权限挡住的不进这一格，
    // 它们的读数在服务端错误码里，混进来会把"想宣战"和"宣战成功"揉成一个数
    this.track(TRACK_EVENTS.warDeclare)
    this.nationNotice = `已对 ${name} 宣战：这一场打 3 小时，打完按人发赛季分`
    this.nationNoticeTone = 'ok'
    this.deliverNation()
  }

  /**
   * 领全服目标奖励（B13 §一 §7）。**不需要二次确认**：领失败不会造成损失（服务端名单挡着），
   * 与宣战那种不可逆动作不是一回事。回执里带着金额 —— 那句话用服务端给的数，不抄配置。
   */
  async claimWarGoal(): Promise<void> {
    const outcome = await this.api.claimWarGoal({})
    if (outcome.kind !== 'ok') {
      this.nationNotice = AppRoot.reason(outcome)
      this.nationNoticeTone = 'warn'
      this.deliverNation()
      return
    }
    this.track(TRACK_EVENTS.warGoalClaim)
    this.nationNotice = `全服奖励已领取：${amountText(outcome.data.gold)} 金币`
    this.nationNoticeTone = 'ok'
    // 领完重拉一次：`myGoalClaimed` 要跟着翻（键从"可以领"变成"已领取"）
    await this.loadNationWar()
    this.deliverNation()
  }

  async setNationRelation(targetNationId: string, relation: DiplomacyRelation): Promise<void> {
    // 关系是协议里的四个枚举之一；不认识的值一律不发出去 ——
    // 发出去等于让服务端替我们猜一个玩家没选过的关系
    if (!DIPLOMACY_OPTIONS.some(option => option.key === relation)) {
      this.nationNotice = '先选一种关系'
      this.deliverNation()
      return
    }
    const name = this.nationCandidates.find(item => item.nationId === targetNationId)?.name ?? '那个国家'
    const outcome = await this.api.setNationDiplomacy({ targetNationId, relation })
    if (outcome.kind !== 'ok') {
      this.nationNotice = AppRoot.reason(outcome)
      this.deliverNation()
      return
    }
    this.track(TRACK_EVENTS.nationDiplomacy, { relation })
    this.nationRelations = outcome.data.allRelations
    this.nationNotice = diplomacyNotice(name, outcome.data.relation)
    // 成功语气：这一句是"做成了"，不能染成失败红（V13-S2 目视截图抓到的那处）
    this.nationNoticeTone = 'ok'
    this.deliverNation()
  }

  /**
   * 任命一名成员。
   *
   * <p>回执是**操作后的完整国家视图**，所以直接换掉手里那份国家视图 ——
   * 不必再查一次（少一次往返在弱网下就是少一次超时机会）。
   */
  async appointNationOffice(playerId: string, office: NationOffice): Promise<void> {
    if (!APPOINTABLE_OFFICES.some(item => item.key === office)) {
      this.nationNotice = '这个官职没有任命入口'
      this.deliverNation()
      return
    }
    const name = this.allianceMembers.find(member => member.id === playerId)?.name ?? '那名成员'
    const outcome = await this.api.appointNationOffice({ playerId, office })
    if (outcome.kind !== 'ok') {
      this.nationNotice = AppRoot.reason(outcome)
      this.deliverNation()
      return
    }
    this.track(TRACK_EVENTS.nationAppoint, { office })
    this.nationResp = outcome.data
    this.nationNotice = `已任命 ${name}`
    // 成功语气：这一句是"做成了"，不能染成失败红（V13-S2 目视截图抓到的那处）
    this.nationNoticeTone = 'ok'
    this.deliverNation()
  }

  /** 组装并递一次抽卡面板。 */
  private deliverGacha(): void {
    const resp = this.gachaResp
    if (resp === null) {
      return
    }
    this.targets.gacha?.(buildGachaPanel(resp.pools, this.gachaBalances(),
      this.gachaPoolId, this.gachaNotice, this.gachaLast))
  }

  /**
   * 拉一次装备实例并下发整块视图。失败时理由原样进说明行、**不清空手里那份**（与科技/榜/赛季同一条纪律）。
   * "能不能强化"的判定全部来自响应的 `canForge`/`blockReason`，本方法不自己判。
   */
  private async loadEquip(): Promise<void> {
    const outcome = await this.api.equipInstances()
    if (outcome.kind === 'ok') {
      this.equipResp = outcome.data
      this.equipNotice = null
    } else {
      this.equipNotice = outcome.kind === 'biz'
        ? (outcome.detail ?? outcome.msg)
        : AppRoot.reason(outcome)
      this.say('equip', outcome)
    }
    this.targets.equip?.(buildEquipPanel(this.equipResp, this.equipNotice, this.equipTarget))
  }

  // ---------- 聊天（B22 §一 1） ----------

  /** 组装并递一次聊天页签的数据。任何一处状态变了都走这里，面板因此永远只有一份输入。 */
  private deliverChat(): void {
    this.targets.chat?.(buildChatPanel({
      channel: this.chatChannel,
      peerId: this.chatPeerId,
      messages: this.chatHistory.get(chatKey(this.chatChannel, this.chatPeerId)) ?? [],
      events: this.unreadEvents,
      myPlayerId: this.playerId,
      peerNames: this.chatPeerNames,
      offsetMs: this.timeSync.offsetMs(),
      localNow: Date.now(),
      sentSeq: this.chatSentSeq,
      notice: this.chatNotice,
      blockedCount: this.myBlocked.length,
      friends: this.myFriends,
      page: this.chatPage,
      hasMoreOlder: this.chatHasMoreOlder,
    }))
  }

  /**
   * 聊天翻页（V15 真分页）。
   *
   * <p><b>`step` 是页码增量，而页码从最新往回数**（0 = 最新那一页）⇒
   * `step = +1` 是往**更早**翻、`-1` 是往**更新**翻。** 这个方向与直觉相反，
   * 所以调用方（表现层）按语义传 `+1/-1`，而这里只认页码增量 —— 两处各转一次必然错一次
   * （第一版表现层按"更早 = -1"传，于是页码被夹回 0，点了没反应）。
   *
   * <p>往更早翻到本地窗口之外时**要先去拉一段更旧的**（`beforeMessageId` = 当前手里最旧那条，
   * `hasMore` 由服务端给）。这就是 `hasMore` 从"下发但没人读"变成真在用的那一步。
   *
   * <p>拉不到就**不动页码**：往前翻一格显示一片空白，比"停在原地说一句拉不到"更让人以为记录丢了。
   */
  async turnChatPage(step: number): Promise<void> {
    if (this.chatChannel === 'PRIVATE' && this.chatPeerId === null) {
      return
    }
    const key = chatKey(this.chatChannel, this.chatPeerId)
    const loaded = this.chatHistory.get(key) ?? []
    const pages = chatPageCount(loaded.length, CHAT_PAGE_ROWS)
    const target = this.chatPage + step
    if (step < 0) {
      // 往**更新**翻：只是在本地窗口里往回走，**不发请求**
      this.chatPage = Math.max(0, Math.min(target, pages - 1))
      this.deliverChat()
      return
    }
    if (target < pages) {
      this.chatPage = Math.max(0, target)
      this.deliverChat()
      return
    }
    // 已经翻到本地最旧那一页：服务端说还有，就去补一段
    if (!this.chatHasMoreOlder) {
      return
    }
    const oldest = loaded[0] ?? null
    if (oldest === null) {
      return
    }
    const outcome = await this.api.chatList({
      channel: this.chatChannel,
      toPlayerId: this.chatChannel === 'PRIVATE' ? this.chatPeerId : null,
      beforeMessageId: oldest.messageId,
      limit: CHAT_FETCH_OLDER,
    })
    if (outcome.kind !== 'ok') {
      this.chatNotice = outcome.kind === 'biz'
        ? chatFailureText(outcome.code, outcome.detail, outcome.msg)
        : AppRoot.reason(outcome)
      this.say('chat', outcome)
      this.deliverChat()
      return
    }
    this.chatHistory.set(key, mergeChatHistory(loaded, outcome.data.messages))
    this.chatHasMoreOlder = outcome.data.hasMore
    this.learnPeerNames(outcome.data.messages)
    // 补到的这一段正好接在旧窗口之前，所以页码只前进一格
    const grown = this.chatHistory.get(key) ?? []
    this.chatPage = Math.min(target, chatPageCount(grown.length, CHAT_PAGE_ROWS) - 1)
    this.deliverChat()
  }

  // ---------- 举报与拉黑（B22 §一 3） ----------

  /**
   * 点了某条别人发的消息上的动作按钮：先把"这条消息能做什么"问出来
   * （举报的四种原因 + 拉黑/取消拉黑），选中之后再发请求。
   *
   * <p>名单是懒取的（第一次点才拉）：它只在生成选项时需要，而首屏那 13 次拉取每一次都要钱。
   */
  async openChatActions(senderId: string, messageId: string): Promise<void> {
    if (senderId === this.playerId) {
      // 自己的消息没有可举报/拉黑的：按钮本来就不该出现在那一行（面板已按 mine 过滤）
      return
    }
    if (this.targets.chatActionChoice === undefined) {
      this.rejectNeeds('chat', actionUnavailable('举报或拉黑'))
      return
    }
    await this.ensureChatLists()
    const following = this.myFriends.some((friend) => friend.playerId === senderId)
    this.targets.chatActionChoice(buildChatActionChoices(this.myBlocked.includes(senderId), following),
      (choice) => {
        if (choice.kind === 'REPORT' && choice.reason !== null) {
          void this.reportMessage(senderId, messageId, choice.reason)
        } else if (choice.kind === 'BLOCK') {
          void this.blockPlayer(senderId)
        } else if (choice.kind === 'FOLLOW') {
          void this.followPlayer(senderId)
        } else if (choice.kind === 'UNFOLLOW') {
          void this.unfollowPlayer(senderId)
        } else {
          void this.unblockPlayer(senderId)
        }
      })
  }

  /**
   * 关注一个人（B22 §一 4，单向）。**不通知对方**：关注是"我想看他在不在线、想随时找他"，
   * 通知就成了请求，而请求那套流程联盟入盟已经有一份了。
   */
  async followPlayer(targetId: string): Promise<void> {
    this.track(TRACK_EVENTS.followChanged, { action: 'add' })
    const outcome = await this.api.socialFollow({ targetPlayerId: targetId })
    if (outcome.kind !== 'ok') {
      this.say('chat', outcome)
      this.chatNotice = AppRoot.reason(outcome)
      this.deliverChat()
      return
    }
    this.applyFriends(outcome.data.friends)
    this.chatNotice = '已关注，打开私聊页就能找到他'
    this.deliverChat()
  }

  /** 取消关注（幂等）。 */
  async unfollowPlayer(targetId: string): Promise<void> {
    this.track(TRACK_EVENTS.followChanged, { action: 'remove' })
    const outcome = await this.api.socialUnfollow({ targetPlayerId: targetId })
    if (outcome.kind !== 'ok') {
      this.say('chat', outcome)
      this.chatNotice = AppRoot.reason(outcome)
      this.deliverChat()
      return
    }
    this.applyFriends(outcome.data.friends)
    this.chatNotice = '已取消关注'
    this.deliverChat()
  }

  /**
   * 从关注列表发起一段私聊（B22 §一 4 的"互相发私聊入口"，也是 S1 里刻意留下的那半件事）。
   *
   * <p>与"点开一个已有会话"走同一条路（{@link openConversation}），唯一的差别是这次没有历史消息：
   * `/chat/list` 会回一个空列表，而名字来自关注列表 —— 所以关注的人要先喂进昵称表，
   * 否则会话页会显示成一串 id。
   */
  private applyFriends(friends: readonly FriendView[]): void {
    this.myFriends = [...friends]
    this.friendsLoaded = true
    for (const friend of friends) {
      this.chatPeerNames.set(friend.playerId, friend.name)
    }
  }

  /**
   * 动作菜单要知道两件事：我拉黑过谁（决定显示"拉黑"还是"取消拉黑"）与我关注过谁（同理）。
   * 两份都懒取 —— 首屏那十几次拉取每一次都要钱，而这两份只有点开消息菜单时才用得上。
   */
  private async ensureChatLists(): Promise<void> {
    if (!this.blocksLoaded) {
      const blocks = await this.api.socialBlocks()
      if (blocks.kind === 'ok') {
        this.applyBlockList(blocks.data.blocked)
      }
    }
    if (!this.friendsLoaded) {
      const follows = await this.api.socialFollows()
      if (follows.kind === 'ok') {
        this.applyFriends(follows.data.friends)
      }
    }
  }

  /**
   * 举报一条消息（B22 §一 3）。**只做留痕**：回执只说"记下了"，不说"会不会封"
   * （说成"报了就封"会让举报变成一种攻击工具）。
   */
  async reportMessage(senderId: string, messageId: string, reason: ReportReason): Promise<void> {
    this.track(TRACK_EVENTS.reportSubmit, { reason })
    const outcome = await this.api.socialReport({
      targetPlayerId: senderId, messageId, reason, detail: null,
    })
    if (outcome.kind !== 'ok') {
      this.say('chat', outcome)
      this.chatNotice = AppRoot.reason(outcome)
      this.deliverChat()
      return
    }
    this.chatNotice = '举报已受理，运营会看到这条记录'
    this.deliverChat()
  }

  /** 拉黑（幂等）。拉黑之后他的消息在我的频道里立刻消失 —— 本地那份缓存也要一起丢。 */
  async blockPlayer(targetId: string): Promise<void> {
    this.track(TRACK_EVENTS.blockChanged, { action: 'add' })
    const outcome = await this.api.socialBlock({ targetPlayerId: targetId })
    if (outcome.kind !== 'ok') {
      this.say('chat', outcome)
      this.chatNotice = AppRoot.reason(outcome)
      this.deliverChat()
      return
    }
    this.applyBlockList(outcome.data.blocked)
    // 先重拉再写提示：loadChat 成功时会把提示行清空（它自己的规矩），
    // 顺序反了的话玩家看不到"已拉黑"这一句
    await this.reloadChatAfterFilterChange()
    this.chatNotice = '已拉黑，他的消息不再显示（不影响战斗）'
    this.deliverChat()
  }

  /** 取消拉黑（幂等）。 */
  async unblockPlayer(targetId: string): Promise<void> {
    this.track(TRACK_EVENTS.blockChanged, { action: 'remove' })
    const outcome = await this.api.socialUnblock({ targetPlayerId: targetId })
    if (outcome.kind !== 'ok') {
      this.say('chat', outcome)
      this.chatNotice = AppRoot.reason(outcome)
      this.deliverChat()
      return
    }
    this.applyBlockList(outcome.data.blocked)
    await this.reloadChatAfterFilterChange()
    this.chatNotice = '已取消拉黑'
    this.deliverChat()
  }

  /**
   * 打开黑名单（B22 §一 3 的"本人列表（加/删）"里的删）：**这是解除拉黑的唯一入口**。
   *
   * <p>为什么不复用消息行：拉黑之后那个人的消息就看不见了，"再点他一条消息"根本够不着。
   */
  async manageBlocks(): Promise<void> {
    await this.ensureBlocks()
    if (this.targets.chatActionChoice === undefined) {
      this.rejectNeeds('chat', actionUnavailable('解除拉黑'))
      return
    }
    if (this.myBlocked.length === 0) {
      this.chatNotice = '黑名单是空的'
      this.deliverChat()
      return
    }
    // 2026-09-22（收口清单 #322）：名单现在是**对象列表**，显示名由服务端解析好下发 ——
    // 所以这一行印真名（「取消拉黑：卫无咎」）。旧服务端只回 id 列表时退回"名单第 N 位"，
    // **绝不把 id 拼进这一行**（那串 `P9179c…` 是内部编号，印给玩家就是 #255 同族）。
    const options = this.myBlocked.map((id, index) => ({
      id, kind: 'UNBLOCK' as const, reason: null,
      label: `取消拉黑：${this.myBlockedNames.get(id) ?? `名单第 ${index + 1} 位`}`,
      detail: '恢复与他的私聊与频道可见',
    }))
    this.targets.chatActionChoice(options, (choice) => {
      void this.unblockPlayer(choice.id)
    })
  }

  private applyBlockList(blocked: readonly { playerId: string, name: string }[]): void {
    this.myBlocked = blocked.map((entry) => entry.playerId)
    // 名字单独存一份：门控逻辑只认 id（`includes`），显示那一行才要名字。
    this.myBlockedNames = new Map(blocked.map((entry) => [entry.playerId, entry.name]))
    this.blocksLoaded = true
  }

  private async ensureBlocks(): Promise<void> {
    if (this.blocksLoaded) {
      return
    }
    const outcome = await this.api.socialBlocks()
    if (outcome.kind === 'ok') {
      this.applyBlockList(outcome.data.blocked)
    }
    // 拉不到就按"没拉黑过任何人"生成选项：点在"拉黑"上仍然会被服务端受理（幂等），
    // 而如果已经拉黑过，菜单会多出一个"拉黑"项 —— 它点了也只是幂等地再拉一次，不会出错
  }

  /**
   * 拉黑状态变了之后重拉当前频道的消息。
   *
   * <p>**本地缓存要整份丢掉**：过滤发生在服务端，而 `mergeChatHistory` 只增不减 ——
   * 不丢缓存的话，被拉黑的人刚才那几句仍然挂在我的聊天记录里，
   * 玩家会以为"拉黑没生效"（而服务端已经滤掉了）。
   */
  private async reloadChatAfterFilterChange(): Promise<void> {
    this.chatHistory.clear()
    await this.loadChat(this.chatChannel, this.chatPeerId)
  }

  /**
   * 点回放里的「分享」（B22 §一 2）：先把目标频道问出来，选中之后才发请求。
   * 选择器没接上就说清楚，而不是静默什么都不发生（与挑战关卡那条同一条口径）。
   */
  requestShare(reportId: string): void {
    if (this.targets.shareChannelChoice === undefined) {
      this.rejectNeeds('reports', pickUnavailable('发到哪个频道'))
      return
    }
    this.targets.shareChannelChoice(buildShareChannelChoices(), (choice) => {
      void this.shareReport(reportId, choice.channel)
    })
  }

  /**
   * 战报回放里的「分享」（B22 §一 2）。目标频道由场景层用选择器问出来（`shareChannelChoice`），
   * 这里只负责发出去并把回执交给面板。
   *
   * <p>**分享不发奖励**（B15 禁止诱导分享）：成功后只是把那条消息落到频道里，
   * 客户端这边不做任何奖励或解锁。
   */
  async shareReport(reportId: string, channel: ShareChannel): Promise<void> {
    this.track(TRACK_EVENTS.reportShare, { reportId, channel })
    const outcome = await this.api.reportShare({ reportId, channel })
    if (outcome.kind !== 'ok') {
      this.say('reports', outcome)
      this.targets.reportShared?.(AppRoot.reason(outcome), true)
      return
    }
    this.targets.reportShared?.(
      `已分享到${channel === 'ALLIANCE' ? '联盟' : '小队'}频道`, false)
  }

  // ---------- 出征编成（B25-S1 首次出征入口；裁决④(b) 手动编成、②(a) 记成功参数） ----------

  /**
   * 点一个搜索到的目标 → 拉起编成面板。**只准备数据，不发任何请求**（裁决：确认之前不扣兵、不发请求）。
   *
   * <p>目标从最近一次搜索结果里找：搜到的目标都是**玩家城**（那份 brief 的 id 就是 playerId，
   * 见 `TargetSearchService.briefOf`），所以行动恒为 ATTACK —— 客户端不需要猜目标类型。
   */
  beginMarchCompose(targetId: string): void {
    const brief = this.searchResp?.targets.find(candidate => candidate.id === targetId)
    if (brief === undefined) {
      this.say('targets', {
        kind: 'biz', code: 0, msg: '目标不在最近一次搜索结果里', detail: '请重新搜索', traceId: '',
      })
      return
    }
    if (this.armyResp === null) {
      this.composeNotice = '军队信息还没拉到，先等一下'
      return
    }
    this.composeTarget = { id: brief.id, name: brief.name, x: brief.coord.x, y: brief.coord.y }
    this.composeRally = false
    this.composeScout = false
    this.composeRallyScope = 'SQUAD'
    this.rallyForm = null
    this.composePicks = {}
    this.composeNotice = null
    this.deliverCompose()
  }

  /**
   * 在编成面板上把这条命令从"出征"切成"发起小队集结"（B26 S12）。
   *
   * <p>为什么放在编成面板而不是在目标行上再加一颗按钮：同一份兵、同一个目标，
   * 换的只是**命令种类** —— 在目标行上摆两颗键会把"我打他"这件事拆成两个入口，
   * 而玩家在选目标那一刻通常还没决定要派多少兵。
   *
   * <p>能不能发起：读得到权限就按 `START_RALLY` 那一位说；**读不到不置灰**，
   * 因为"权限还没拉到"不是"你不行"（同一口径见 PermissionGates 的注释），
   * 真发出去由服务端裁决并回一句人话。
   */
  async toggleComposeRally(): Promise<void> {
    if (this.composeTarget === null) {
      return
    }
    if (!this.composeRally) {
      const blocked = this.rallyBlockedReason()
      if (blocked !== null) {
        this.composeNotice = blocked
        this.deliverCompose()
        return
      }
    }
    this.composeRally = !this.composeRally
    if (this.composeRally) {
      this.composeScout = false
    }
    if (!this.composeRally) {
      // 切回出征：层级与那两个数一起收掉，下次进集结态从小队层起步
      this.composeRallyScope = 'SQUAD'
      this.rallyForm = null
    }
    this.composeNotice = null
    this.deliverCompose()
    // 从来没开过社交页的玩家手里没有政策：不补这一次，他看到的就是一个
    // 「没有层级可切、两个数一行都不画」的集结面板，而缺的那一样看起来像功能坏了。
    // 放在切完之后：被门挡住的那一下不该预拉（与上面 rallyBlockedReason 同一条预算）。
    if (this.composeRally && this.rallyPolicy === null) {
      await this.loadSocialGates()
    }
  }

  /** 某一层级的政策；还没拉到就是 null（面板不为它猜界）。 */
  private rallyPolicyOf(scope: RallyScope): RallyPolicyView | null {
    if (this.rallyPolicy === null) {
      return null
    }
    return scope === 'SQUAD' ? this.rallyPolicy.squad : this.rallyPolicy.alliance
  }

  /**
   * 换集结的召集范围：小队 / 联盟（B26 S14）。
   *
   * <p>政策说不能时才拦下并给服务端那句原因；**政策没拉到时不拦**（"暂时不知道"不是"你不行"，
   * 同一口径见 {@link rallyBlockedReason}）。切到联盟才填那两个数：小队层的上限与时长
   * 由服务端按自己的配置定，客户端没有可填的字段。
   */
  setComposeRallyScope(scope: RallyScope): void {
    if (this.composeTarget === null || !this.composeRally || scope === this.composeRallyScope) {
      return
    }
    const blocked = rallySwitchBlocked(this.rallyPolicyOf(scope))
    if (blocked !== null) {
      this.composeNotice = blocked
      this.deliverCompose()
      return
    }
    this.composeRallyScope = scope
    this.rallyForm = scope === 'ALLIANCE' ? rallyFormOf(this.rallyPolicyOf(scope)) : null
    this.composeNotice = null
    this.deliverCompose()
  }

  /** 把人数上限 / 等待时长调一档。夹取在纯逻辑里按政策的界做，表现层不自己算。 */
  adjustComposeRallyNumber(field: RallyField, direction: number): void {
    if (!this.composeRally || this.composeRallyScope !== 'ALLIANCE') {
      return
    }
    this.rallyForm = adjustRallyNumber(this.rallyForm, this.rallyPolicyOf('ALLIANCE'), field, direction)
    this.composeNotice = null
    this.deliverCompose()
  }

  /**
   * 发起不了集结时那句原因；null = 可以发起（或政策还没拉到，交给服务端判）。
   *
   * <p>这一句**只能来自 `/rally/policy`**：客户端先前自己按权限位与摘要拼过一份同样的判定，
   * 那是把服务端已经写成人话的东西再翻一遍 —— 两边一漂，玩家看到的原因就和被拒的原因不是一回事。
   */
  private rallyBlockedReason(): string | null {
    return rallySwitchBlocked(this.rallyPolicyOf('SQUAD'))
  }

  /**
   * 在出征与派侦察之间切（B26 S18）。
   *
   * <p>落点与集结同一条理由：同一份兵、同一个目标，换的只是命令种类 —— `ScoutReq.units`
   * 要的就是玩家正在编的这一队，另开一屏只会让他再编一遍。
   * 这里不判"能不能侦察"（体力、目标合法性都在服务端），点下去由服务端裁决并回一句人话。
   */
  toggleComposeScout(): void {
    if (this.composeTarget === null) {
      return
    }
    this.composeScout = !this.composeScout
    if (this.composeScout) {
      this.composeRally = false
      this.composeRallyScope = 'SQUAD'
      this.rallyForm = null
    }
    this.composeNotice = null
    this.deliverCompose()
  }

  /**
   * 派侦察（B26 S18）。交出去的仍是这一份编成 —— 侦察队会被打，打光了就是打光了，
   * 所以"随手派个侦察"在数值上和派一支小队出去是一回事，不能当成免费的看一眼。
   */
  private async confirmScout(target: { x: number, y: number, name: string },
                             units: readonly { unitId: string, count: number }[]): Promise<void> {
    this.track(TRACK_EVENTS.scoutSend, {
      troops: trackParam(units.reduce((sum, unit) => sum + unit.count, 0)),
    })
    this.composeSubmitting = true
    this.composeNotice = null
    this.deliverCompose()
    const outcome = await this.api.worldScout({
      toX: target.x, toY: target.y, units: units.map(unit => ({ ...unit })),
    })
    this.composeSubmitting = false
    if (outcome.kind === 'ok') {
      this.composeScout = false
      this.composeNotice = `侦察队已出发：${target.name}`
      this.deliverCompose()
      void this.refresh('army')
      return
    }
    this.composeNotice = outcome.kind === 'biz'
      ? (outcome.detail ?? outcome.msg)
      : AppRoot.reason(outcome)
    this.say('targets', outcome)
    this.deliverCompose()
  }

  /**
   * 发起小队集结（B26 S12）。目标与兵力用的是与出征同一份编成，只是命令种类不同：
   * 出征是"我打他"，集结是"我打他，等人一起"。发起人自己的兵在这一枪里就交出去
   * （服务端把发起人算作第一个参与者，之后他不能再 join 自己的集结），所以缺了这份兵
   * 一次集结永远只能带别人的兵出发。
   */
  private async confirmSquadRally(target: { x: number, y: number, name: string },
                                  units: readonly { unitId: string, count: number }[]): Promise<void> {
    this.track(TRACK_EVENTS.rallyInitiate, {
      scope: trackParam('SQUAD'),
      troops: trackParam(units.reduce((sum, unit) => sum + unit.count, 0)),
    })
    this.composeSubmitting = true
    this.composeNotice = null
    this.deliverCompose()
    const outcome = await this.api.squadRally({
      targetCoord: { x: target.x, y: target.y }, targetType: 'PLAYER_CITY',
      troops: units.map(unit => ({ ...unit })), heroes: [],
    })
    this.composeSubmitting = false
    if (outcome.kind === 'ok') {
      this.composeRally = false
      this.composeTarget = null
      this.composePicks = {}
      this.composeNotice = `已发起集结：${target.name}`,
      this.deliverCompose()
      void this.refresh('social')
      return
    }
    this.composeNotice = outcome.kind === 'biz'
      ? (outcome.detail ?? outcome.msg)
      : AppRoot.reason(outcome)
    this.say('targets', outcome)
    this.deliverCompose()
  }

  /**
   * 发起联盟集结（B26 S14）。与小队那条的唯一差别是请求多带两个数，而那两个数的界
   * 来自 `/rally/policy`：客户端不抄 global 表，也不自己挑默认值（挑出来的数会真的发出去，
   * 玩家以为自己设了 30 人而服务端夹成 4 人）。
   *
   * <p>政策还没拉到时**不猜**：拦成一句人话，确认键按不下去。
   */
  private async confirmAllianceRally(target: { x: number, y: number, name: string },
                                     units: readonly { unitId: string, count: number }[]): Promise<void> {
    const form = this.rallyForm
    if (form === null) {
      this.composeNotice = rallyFormBlocked(form)
      this.deliverCompose()
      return
    }
    this.track(TRACK_EVENTS.rallyInitiate, {
      scope: trackParam('ALLIANCE'),
      troops: trackParam(units.reduce((sum, unit) => sum + unit.count, 0)),
    })
    this.composeSubmitting = true
    this.composeNotice = null
    this.deliverCompose()
    const outcome = await this.api.allianceRally({
      targetCoord: { x: target.x, y: target.y }, targetType: 'PLAYER_CITY',
      maxMembers: form.maxMembers, prepareMinutes: form.prepareMinutes,
      troops: units.map(unit => ({ ...unit })), heroes: [],
    })
    this.composeSubmitting = false
    if (outcome.kind === 'ok') {
      this.composeRally = false
      this.composeScout = false
      this.composeRallyScope = 'SQUAD'
      this.rallyForm = null
      this.composeTarget = null
      this.composePicks = {}
      this.composeNotice = `已发起联盟集结：${target.name}`
      this.deliverCompose()
      void this.refresh('social')
      return
    }
    this.composeNotice = outcome.kind === 'biz'
      ? (outcome.detail ?? outcome.msg)
      : AppRoot.reason(outcome)
    this.say('targets', outcome)
    this.deliverCompose()
  }

  /** 勾选/改数量。夹取在纯逻辑里做（表现层不做夹取，也不做判定）。 */
  pickMarchUnit(unitId: string, count: number): void {
    if (this.armyResp === null || this.composeTarget === null) {
      return
    }
    this.composePicks = setPick(this.armyResp, this.composePicks, unitId, count)
    this.deliverCompose()
  }

  /** 关掉编成面板（不扣兵、不发请求）。 */
  cancelMarchCompose(): void {
    this.composeTarget = null
    this.composeRally = false
    this.composeScout = false
    this.composeRallyScope = 'SQUAD'
    this.rallyForm = null
    this.composePicks = {}
    this.composeNotice = null
    this.deliverCompose()
  }

  /**
   * 确认出征：把编成拼成 `MarchReq` 交给服务端。
   *
   * <p><b>requestId 由 GameApi 每次新生成</b>，本层不碰 —— 一次确认 = 一次新意图；**成功之后才记**
   * 「上一次」，失败不记（记了会让「再次出征」重发一支本来就发不出去的队伍，第二次失败是纯噪声）。
   */
  async confirmMarch(): Promise<void> {
    if (this.composeTarget === null || this.armyResp === null || this.composeSubmitting) {
      return
    }
    const compose = buildCompose(this.armyResp, this.composePicks)
    if (!compose.canSubmit) {
      this.composeNotice = compose.blockedReason
      this.deliverCompose()
      return
    }
    const units = marchUnitsOf(compose)
    const target = this.composeTarget
    // 编队的目的地决定这条命令发给谁：给集结编队就发 join（承诺的兵力由服务端锁），
    // 否则还是普通出征。两条路的入参形状一样（unitId + count），但语义完全不同
    if (this.composeRallyId !== null) {
      await this.confirmRallyJoin(this.composeRallyId, units)
      return
    }
    if (this.composeScout) {
      await this.confirmScout(target, units)
      return
    }
    if (this.composeRally) {
      if (this.composeRallyScope === 'ALLIANCE') {
        await this.confirmAllianceRally(target, units)
      } else {
        await this.confirmSquadRally(target, units)
      }
      return
    }
    // 打点是"真的发出去"这一下：被 blockedReason 拦住的那些不计（它们不是出征意图）
    this.track(TRACK_EVENTS.marchSend, {
      action: 'ATTACK',
      troops: trackParam(units.reduce((sum, unit) => sum + unit.count, 0)),
    })
    this.composeSubmitting = true
    this.composeNotice = null
    this.deliverCompose()

    const outcome = await this.api.worldMarch({
      toX: target.x, toY: target.y, units, heroes: [], action: 'ATTACK',
    })
    this.composeSubmitting = false
    if (outcome.kind === 'ok') {
      this.lastMarch = rememberMarch(this.lastMarch, {
        toX: target.x, toY: target.y, units, heroes: [], action: 'ATTACK',
      })
      this.composeTarget = null
      this.composePicks = {}
      this.composeNotice = `已出征：${target.name}`
      this.deliverCompose()
      void this.refresh('world')
      return
    }
    this.composeNotice = outcome.kind === 'biz'
      ? (outcome.detail ?? outcome.msg)
      : AppRoot.reason(outcome)
    this.say('targets', outcome)
    this.deliverCompose()
  }

  /**
   * 「再次出征」：把**上一次成功**那支队伍原样重发（裁决②(a)）。
   *
   * <p><b>业务字段照搬、键由 GameApi 新生成</b>：复用旧键的语义是"这个键用过了"（`REQUEST_DUPLICATED`），
   * 不是幂等回放 —— 所以每次点都算一次新意图。
   *
   * <p><b>凑不齐就不发</b>：上一次带 800 兵、现在只剩 300 时**明确说清**（`repeatBlockedReason`），
   * 而不是按现有的数量发出去 —— 后者在玩家眼里是"我点的是同一支队伍，怎么输了"，
   * 而原因（兵力不够）他永远看不到。
   */
  async repeatLastMarch(): Promise<void> {
    const blocked = repeatBlockedReason(this.lastMarch, this.armyResp)
    if (blocked !== null) {
      this.targets.marchCompose?.({
        targetId: '', targetName: '', coordText: '',
        compose: buildCompose(this.armyResp ?? EMPTY_ARMY, {}),
        notice: blocked, submitting: false,
      })
      return
    }
    const spec = this.lastMarch as MarchSpec
    const outcome = await this.api.worldMarch({
      toX: spec.toX, toY: spec.toY, units: [...spec.units], heroes: [...spec.heroes],
      action: spec.action,
    })
    if (outcome.kind === 'ok') {
      this.track(TRACK_EVENTS.marchSend, {
        action: trackParam(spec.action),
        troops: trackParam(spec.units.reduce((sum, unit) => sum + unit.count, 0)),
      })
      this.targets.marchCompose?.({
        targetId: '', targetName: '', coordText: '',
        compose: buildCompose(this.armyResp ?? EMPTY_ARMY, {}),
        notice: `已再次出征：${spec.toX}, ${spec.toY}`, submitting: false,
      })
      void this.refresh('world')
      return
    }
    this.say('targets', outcome)
    this.targets.marchCompose?.({
      targetId: '', targetName: '', coordText: '',
      compose: buildCompose(this.armyResp ?? EMPTY_ARMY, {}),
      notice: outcome.kind === 'biz' ? (outcome.detail ?? outcome.msg) : AppRoot.reason(outcome),
      submitting: false,
    })
  }

  /** 把编成整块推给面板。**没有编成目标时也推一次**，面板据此收起。 */
  private deliverCompose(): void {
    const compose = buildCompose(this.armyResp ?? EMPTY_ARMY, this.composePicks)
    if (this.composeTarget === null) {
      this.targets.marchCompose?.({
        targetId: '', targetName: '', coordText: '', compose,
        notice: this.composeNotice, submitting: false,
        mode: 'MARCH', submitLabel: '出征', rallyBlocked: null,
      })
      return
    }
    this.targets.marchCompose?.({
      targetId: this.composeTarget.id,
      targetName: this.composeTarget.name,
      coordText: `${this.composeTarget.x}, ${this.composeTarget.y}`,
      compose,
      notice: this.composeNotice,
      submitting: this.composeSubmitting,
      mode: this.composeScout ? 'SCOUT' : this.composeRally ? 'RALLY' : 'MARCH',
      submitLabel: this.composeScout ? '派侦察' : this.composeRally ? '发起集结' : '出征',
      rallyBlocked: this.rallyBlockedReason(),
      rallyScope: this.composeRallyScope,
      rallyScopes: this.composeRally ? this.rallyScopeRows() : [],
      rallyNumbers: this.composeRally && this.composeRallyScope === 'ALLIANCE'
        ? rallyNumbersOf(this.rallyForm, this.rallyPolicyOf('ALLIANCE'))
        : [],
    })
  }

  /**
   * 集结态下的两行层级入口。原因直接抄政策那句（服务端已经把"不在盟/职位不够/人数不够"
   * 说成人话了，客户端不再判第二遍）。政策没拉到时两行都不带原因 —— 点下去由服务端裁决。
   */
  private rallyScopeRows(): RallyScopeRow[] {
    const rowOf = (scope: RallyScope, label: string): RallyScopeRow => ({
      scope, label, blocked: rallySwitchBlocked(this.rallyPolicyOf(scope)),
    })
    return [rowOf('SQUAD', '小队'), rowOf('ALLIANCE', '联盟')]
  }

  // ---------- 目标搜索与流亡 ----------

  /** `radius` 为 null = 客户端还不知道上下界（第一次搜索），服务端按 SEARCH_DEFAULT_RADIUS 搜。 */
  searchTargets(radius: number | null): Promise<void> {
    this.track(TRACK_EVENTS.targetsSearch, { radius: trackParam(radius) })
    return this.write('targets', this.api.searchTargets({ radius, maxCount: 30 }), [],
      r => {
        this.searchResp = r
        this.targets.targets?.(r)
      })
  }

  /**
   * 流亡迁城。`doExile` 内部已经成功与失败都重拉了行军列表（按钮的冷却与在外部队数
   * 全来自那份响应），所以这里不再声明要刷新的面板 —— 再刷一遍只会多打一轮请求。
   */
  exile(): Promise<void> {
    this.track(TRACK_EVENTS.exile)
    return this.write('world', this.api.doExile(), [])
  }

  // ---------- 内部 ----------

  /**
   * 统一的写操作收尾：成功就落数据 + 重拉受影响列表，失败就只报原因。
   *
   * <p>`onOk` 跑在刷新<b>之前</b>：刷新会把面板重画，先落地这次的结果才不会被覆盖掉
   * （收割飘字就是这一类）。
   */
  private async write<T>(panel: string, call: Promise<NetOutcome<T>>, refresh: PanelKey[],
                         onOk?: (resp: T) => void): Promise<void> {
    const outcome = await call
    if (outcome.kind !== 'ok') {
      this.say(panel, outcome)
      return
    }
    onOk?.(outcome.data)
    if (refresh.length > 0) {
      await this.refresh(...refresh)
    }
  }

  private deliver<T>(panel: string, outcome: NetOutcome<T>, ok: (resp: T) => void): void {
    if (outcome.kind === 'ok') {
      ok(outcome.data)
      return
    }
    this.say(panel, outcome)
  }

  private rejectNeeds(panel: string, message: string): void {
    this.targets.error?.(panel, message)
    this.showNeedsInPanel(panel, message)
  }

  /**
   * 把「这件事现在做不了」写进面板**自己那条提示行**，再纯重递一次。
   *
   * <p>#357 只接了关卡与内城（它们各有一条瞬时带）。这一族里还有一批面板的提示是
   * 随数据重算的 `notice`（商店、战令、外观、集结、聊天）—— 直接改标签会被下一次
   * `attach` 覆盖掉，所以正确落点是**它们本来就带着的那个字段**。
   *
   * <p>只调"不发请求"的重递口：一次拒绝不该变成一次读放大。
   * army / bag / hero / social / reports 没有瞬时提示面（要新建 UI 面才能接），
   * 这里刻意不给它们造落点 —— 见台账 #358 未做②。
   */
  private showNeedsInPanel(panel: string, message: string): void {
    switch (panel) {
      case 'tech':
        this.techNotice = message
        this.deliverTech()
        return
      case 'gacha':
        this.gachaNotice = message
        this.deliverGacha()
        return
      case 'shop':
        this.shopNotice = message
        this.deliverShop()
        return
      case 'battlePass':
        this.battlePassNotice = message
        this.deliverBattlePass()
        return
      case 'avatarFrames':
        this.frameNotice = message
        this.deliverAvatarFrames()
        return
      case 'rallies':
        this.rallyNotice = message
        this.deliverRallies()
        return
      case 'chat':
        this.chatNotice = message
        this.deliverChat()
        return
      case 'nation':
        this.nationNotice = message
        this.deliverNation()
        return
      default:
      }
  }

  private say(panel: string, outcome: NetOutcome<unknown>): void {
    this.targets.error?.(panel, AppRoot.reason(outcome))
    // 同一句话发给服务端：`targets.error` 的落点是 console.warn，只有开发者看得见，
    // 于是"某个面板一直是空的"这件事在没有人盯着 Console 的时候永远没人知道。
    // 这是所有面板读取的同一个收口点，所以只在这里加一次（刻意不写有几个：一写就会过期）。
    this.track(TRACK_EVENTS.panelLoadFailed, {
      panel,
      kind: outcome.kind,
      reason: AppRoot.reason(outcome),
    })
  }

  /**
   * 从失败里取一句人看得懂的话。
   *
   * <p>网络失败与业务失败必须分得开：前者说「网络不通」（玩家会等），后者说服务端给的
   * 具体原因（玩家会去改操作）。合并成一句「操作失败」是玩家投诉的头号来源。
   */
  private static reason(outcome: NetOutcome<unknown>): string {
    switch (outcome.kind) {
      case 'network':
        return outcome.queued
          ? `网络不通，本次操作已排队，恢复后会自动重发：${outcome.message}`
          : `网络不通：${outcome.message}`
      case 'biz':
        return outcome.detail ?? outcome.msg
    }
    return '操作未完成'
  }

  get playerId(): string | null {
    return this.store.getState().playerId
  }

  /** 当前红点树。场景层绑定时读取，避免各面板再维护一份副本。 */
  get reddotTree(): ClientReddotTree {
    return this.reddot
  }

  /**
   * 问一次"这一屏弹不弹"，把答案交给面板（B19 S3-iv）。
   *
   * <p>**在登录之后、以及每次回到前台时问**：弹窗的时机由服务端的触发与频控决定，
   * 客户端不轮询也不猜 —— 问早了没触发、问晚了报价过期，两个都由服务端说了算。
   */
  /**
   * 回到前台要做的事（B19 S3-iv 的再触发）。由平台的前台回调驱动（`wx.onShow`）。
   *
   * <p><b>为什么重校时</b>：挂后台期间本地时钟可能漂移，而产出倒计时、弹窗倒计时全靠它；
   * 断线重连那条路径已经在做同一件事（见 `GameSession.bindNetworkEvents`），这里是第二条入口。
   *
   * <p><b>为什么再问一次弹窗</b>：挂后台期间玩家看不到任何东西，而礼包的触发是**有时效**的
   * （报价 60 分钟）—— 回来不问，那次触发就白过了。
   */
  async afterForeground(): Promise<void> {
    await this.session.syncTime()
    await this.showGiftPopup()
  }

  async showGiftPopup(): Promise<void> {
    const outcome = await this.api.giftPopup()
    if (outcome.kind !== 'ok') {
      return // 弹窗是加法：拉不到就这一屏不弹，绝不因此报错打断玩家
    }
    this.targets.giftPopup?.(outcome.data, outcome.data.serverNow)
    if (outcome.data.popup) {
      // 只在「服务端说弹」时记一次：拉到接口但 popup=false 不是一次展示，
      // 拿它当展示会让漏斗虚高（B19 S3-iv 的埋点口径）
      this.tracker?.track(TRACK_EVENTS.payPopupShow, {
        giftId: outcome.data.giftId ?? '',
        productId: outcome.data.productId ?? '',
      })
    }
  }

  /**
   * 买一档礼包：下单 → 拉起支付 → 轮询 → 把结果交给面板。
   *
   * <p>流程本体在 `GiftPayFlow`（有独立用例），这里只把它需要的四样依赖接上：
   * 两个网络调用、拉起支付的桥、以及埋点与时钟。
   */
  async buyGift(productId: string): Promise<void> {
    const flow = new GiftPayFlow({
      createOrder: (id) => this.api.createPayOrder(id),
      orderStatus: (orderId) => this.api.payOrderStatus(orderId),
      invokePayment: (params) => requestMidasPayment(params),
      now: () => Date.now(),
      delay: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
      track: (name, params) => this.tracker?.track(name, params),
    })
    const view = await flow.buy(productId)
    this.targets.payResult?.(view)
  }
}

/**
 * 推送帧的最小校验，通过则按事件使用。
 *
 * <p>WS 帧是外部输入（服务端序列化 → 网络 → `JSON.parse`），字段缺失在这里是可能的，
 * 而缺一个字段的症状不是报错、是"未读账少一块"。所以按**我们要用的那几个字段**逐个确认；
 * 形状不对就当没收到：
 * 一条数得出来却读不出的未读，比少一条难查得多（前者永远清不掉）。
 *
 * <p>其余字段原样使用：服务端两条路（推送 / 离线补偿）发的是同一个视图结构
 * （`SocialEventView`，有 `coord` 与 `expired`），这一点由 `SocialPushShapeTest` 钉住 ——
 * 客户端不替它兜底重造字段，否则两边的形状会越走越远。
 */
function asPrivateMessageEvent(payload: unknown): SocialEventView | null {
  if (typeof payload !== 'object' || payload === null) {
    return null
  }
  const it = payload as Record<string, unknown>
  if (it.type !== 'PRIVATE_MESSAGE' || typeof it.eventId !== 'string'
      || typeof it.title !== 'string' || typeof it.relatedId !== 'string'
      || typeof it.occurredAt !== 'number') {
    console.warn('[chat] 私聊推送的字段不全，已忽略（未读账宁可少一条，也不能数出一条读不出的）')
    return null
  }
  return payload as SocialEventView
}
