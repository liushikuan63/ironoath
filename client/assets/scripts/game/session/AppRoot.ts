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
import type { OfflineReportView, PowerDetailResp } from '../../net/generated/Protocol'
import type { CityCollectResp, CityListResp, SpeedUpSource } from '../../net/generated/CityProtocol'
import type { ArmyListResp } from '../../net/generated/ArmyProtocol'
import type { BagListResp, ResourceDetailResp } from '../../net/generated/BagProtocol'
import type { GachaDrawResp, GachaPoolsResp, HeroListResp } from '../../net/generated/HeroProtocol'
import type { StageListResp } from '../../net/generated/StageProtocol'
import type {
  AllianceMember, AllianceRole, AllianceSyncResp, ChatChannel, ChatMessageView, FriendView, HelpRequestView,
  ReportReason, SocialCreatePolicy, SocialCreatePolicyResp, SocialEventView, SocialSummaryResp,
} from '../../net/generated/SocialProtocol'
import {
  ackablePrivateEventIds, buildChatPanel, chatFailureText, chatKey, CHAT_LOCAL_HISTORY_MAX,
  mergeChatHistory,
} from '../social/ChatPanel'
import type { ChatPanelData } from '../social/ChatPanel'
import { buildRankBoard } from '../power/RankBoard'
import {
  buildCompose, marchUnitsOf, rememberMarch, repeatBlockedReason, setPick,
} from '../world/MarchCompose'
import type { ComposeView, MarchSpec } from '../world/MarchCompose'
import type { RankBoardView, RankTabKey } from '../power/RankBoard'
import type { RankListResp } from '../../net/generated/RankProtocol'
import { buildSeasonPanel } from '../season/SeasonPanel'
import type { SeasonPanelView } from '../season/SeasonPanel'
import type { SeasonStatusResp } from '../../net/generated/SeasonProtocol'
import { buildTechPanel } from '../tech/TechPanel'
import type { TechPanelView } from '../tech/TechPanel'
import type { TechListView } from '../../net/generated/TechProtocol'
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
import { EMPTY_PERMISSIONS, gate, withPermissionScope } from '../social/PermissionGates'
import type { PermissionState } from '../social/PermissionGates'
import { buildCreateForm, createEntries } from '../social/SocialCreate'
import type { CreateEntry, CreateForm, CreateScope } from '../social/SocialCreate'
import { buildDiscovery, canApply, EMPTY_DISCOVERY } from '../social/AllianceDiscovery'
import { buildSquadDiscovery, canJoin, EMPTY_SQUAD_DISCOVERY } from '../social/SquadDiscovery'
import {
  blockedReason, defaultParams, kindSubmitLabel, nextKind, rebind, stepControl,
} from '../social/RallyCompose'
import type { RallyKind, RallyParams } from '../social/RallyCompose'
import type { DiscoveryView } from '../social/AllianceDiscovery'
import type { SquadListView } from '../social/SquadDiscovery'
import { buildApplications, EMPTY_APPLICATIONS } from '../social/AllianceApplications'
import type { ApplicationView } from '../social/AllianceApplications'

import type { ExitAction, ExitKey, ExitScope } from '../social/SocialExit'
import { buildGachaPanel, TEN_DRAW_COUNT } from '../gacha/GachaPanel'
import type { GachaBalances, GachaPanelView } from '../gacha/GachaPanel'
import { buildDisclosure } from '../gacha/GachaDisclosure'
import type { GachaDisclosure } from '../gacha/GachaDisclosure'
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
  buildChatActionChoices, buildLineupChoices, buildShareChannelChoices, buildSpeedupChoices,
} from './Choices'
import type {
  ChatActionChoice, LineupChoice, ShareChannelChoice, SpeedupChoice,
} from './Choices'
import type { GiftPopupResp } from '../../net/generated/PayProtocol'
import type { PayView } from '../pay/GiftPayFlow'
import { GiftPayFlow } from '../pay/GiftPayFlow'
import { requestMidasPayment } from '../../net/MidasPayment'
import { ClientReddotTree } from '../reddot/ReddotTree'
import { pickUnavailable, actionUnavailable } from '../ui/BlockedPickCopy'
import { autoTrainBlockedReason, autoTrainRequest, rememberTrain } from '../army/AutoTrain'
import { buildShopPanel, buyBodyOf, buyResultText, shopRowStateText } from '../shop/ShopPanel'
import type { ShopPanelView } from '../shop/ShopPanel'
import type { ShopCurrency, ShopListResp } from '../../net/generated/ShopProtocol'
import { buildAvatarFramePanel, wearBodyOf, wearResultText } from '../avatar/AvatarFramePanel'
import type { AvatarFramePanelView } from '../avatar/AvatarFramePanel'
import type { AvatarFrameListResp } from '../../net/generated/Protocol'
import type { BattlePassStatusResp, BattlePassTrack } from '../../net/generated/BattlePassProtocol'
import type { RallyListResp, RallyPolicyResp } from '../../net/generated/SocialProtocol'
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
   * 这一份编成是哪一种命令（B26 S12 两态 → S13b 三态）。省略等于 MARCH：
   * 「再次出征」那几条提示走的是同一个面板，它们永远不出集结，就不该各自补一遍字段。
   */
  readonly kind?: RallyKind
  /** 确认键上的字（出征 / 发起小队集结 / 发起联盟集结）。面板不自己翻，免得两处写两份 */
  readonly submitLabel?: string
  /** 不能发起集结时那句原因（读不到政策时**不为它**置灰：那是"暂时不知道"，不是"你不行"） */
  readonly rallyBlocked?: string | null
  /**
   * 集结参数那一行（人数 / 准备时长）。MARCH 或读口没到时为 null ⇒ 那一行整条不画。
   * 界与默认值都是服务端给的，面板只显示、不夹取。
   */
  readonly rally?: RallyParams | null
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
  'city' | 'army' | 'hero' | 'bag' | 'resources' | 'stage' | 'social' | 'power' | 'world'
  | 'quest' | 'reddot' | 'mail' | 'reports' | 'activity' | 'guide' | 'shop' | 'avatarFrames'
  | 'battlePass' | 'rallies' | 'tech' | 'equip' | 'gacha'

/** 埋点出口。只要一个 `track`，为的是单测能塞一个数组进来，而不是塞整个 TrackClient。 */
export interface Tracker {
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
  /** 编成面板当前是哪一种命令（B26 S12 两态 → S13b 三态）。换目标就回到出征 */
  private composeKind: RallyKind = 'MARCH'
  /**
   * 发起集结的政策读口（`GET /rally/policy`，B26 S13a 落的服务端）。
   * 人数与准备时长的**上下界和默认值**全从这里来 —— 客户端不抄 `global.RALLY_*`，
   * 也不自己挑一个默认时长（那是"显示名必须服务端下发"同族的第十条：入参上下界同属必须下发）。
   */
  private rallyPolicyResp: RallyPolicyResp | null = null
  /** 面板上那两个可调项当前的值；null = 当前这一档没有参数（出征）或读口还没到 */
  private rallyParams: RallyParams | null = null
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

  /** 当前页签。默认停在「明细」：那是这个页面原本的内容，四类榜是新加的邻居 */
  private rankTab: RankTabKey = 'DETAIL'
  /** 最近一次 `/rank/list` 的响应。**只有当前页签那一张**（切页签就换掉，不缓存多张 —— 榜是会变的） */
  private rankResp: RankListResp | null = null
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
  /** 抽卡面板：选中哪个池、上一次抽取的结果、上一次失败的理由 */
  private gachaPoolId: string | null = null
  private gachaLast: GachaDrawResp | null = null
  private gachaNotice: string | null = null

  // ---------- 聊天状态（B22 §一 1） ----------

  /** 当前频道；私聊且 `chatPeerId` 为 null 时画会话列表 */
  private chatChannel: ChatChannel = 'WORLD'
  private chatPeerId: string | null = null
  /** 各频道/会话已拉到的历史，键见 `chatKey`。**只在内存里**：B10 §5 说的"本地保留 200 条"是本次会话的窗口，不落盘 */
  private readonly chatHistory = new Map<string, ChatMessageView[]>()
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
    await this.prefetch('city', 'army', 'hero', 'bag', 'resources', 'stage', 'social', 'power',
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
      case 'reports':
        this.deliver('reports', await this.api.battleReports(),
          r => this.targets.reports?.(r, this.timeSync.serverNow()))
        return
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

  /** 顶栏的「一键收割」= `buildingId: null`，由服务端裁定收哪些；具体行则收那一格。 */
  collect(buildingId: string | null): Promise<void> {
    this.track(TRACK_EVENTS.gatherCollect, { buildingId: trackParam(buildingId), all: trackParam(buildingId === null) })
    return this.write('city', this.api.cityCollect({ buildingId }),
      ['city', 'resources', 'reddot'], r => this.targets.cityCollect?.(r))
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
   * 使用道具。`needsTarget` 为真时（加速类）需要目标选择器，而它还没有 ——
   * 明说比替玩家挑一个目标好：猜错目标消耗掉的是真金白银买来的道具，且不会有任何报错。
   */
  useItem(itemId: string, needsTarget: boolean,
          targetId: string | null = null): Promise<void> {
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
    }), ['stage', 'army', 'hero'])
  }

  /** ×10 只发**一个** `count=10` 的请求（B09 验收 9：一次请求做完一件事，弱网下不会只成一半）。 */
  sweep(stageId: string, count: number): Promise<void> {
    this.track(TRACK_EVENTS.battleStart, { battleType: 'sweep', stageId, count: trackParam(count) })
    return this.write('stage', this.api.stageSweep({ stageId, count }), ['stage'])
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
   * 拉社交页的三道门（B26 S1 + S2）：两个 scope 的权限、一份创建政策（一次回两个层级）。
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

  private async pullSocialGates(): Promise<void> {    const [squad, alliance, create] = await Promise.all([
      this.api.socialPermissions('SQUAD'), this.api.socialPermissions('ALLIANCE'),
      this.api.socialCreatePolicy(),
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
    this.permissions = state
    this.targets.socialGates?.(this.permissions, createEntries(this.createPolicy,
      this.createBalance('squad'), this.createBalance('alliance')))
    this.deliverSocialCreate()
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
      this.deliverRank()
      return
    }
    if (this.rankTab === 'SEASON') {
      // 赛季页与榜无关：手里那份榜响应属于别的页签，留着会在赛季页签下画出一张榜
      this.rankResp = null
      await this.loadSeason()
      this.deliverRank()
      return
    }
    const outcome = await this.api.rankList(this.rankTab, this.rankPage, AppRoot.RANK_SCREEN_ROWS)
    if (outcome.kind === 'ok') {
      this.rankResp = outcome.data
      this.rankNotice = null
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
      this.rankNotice))
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
    this.targets.tech?.(buildTechPanel(this.techResp, this.techNotice))
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
    }))
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
        this.applyBlockList(blocks.data.blockedPlayerIds)
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
    this.applyBlockList(outcome.data.blockedPlayerIds)
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
    this.applyBlockList(outcome.data.blockedPlayerIds)
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
    // 名单里只有 playerId（`/social/blocks` 只回 id）——**不许把 id 拼进这一行**：
    // 那串 `P9179c…` 是内部编号，印给玩家就是 #255 同族（2026-09-21 普查抓到）。
    // 服务端补显示名之前，这里按"名单里的第几位"给一句人话：玩家仍能逐条解除，
    // 只是暂时看不出是谁；这条升级已记进收口清单（要动协议形状，需拍板）。
    const options = this.myBlocked.map((id, index) => ({
      id, kind: 'UNBLOCK' as const, reason: null,
      label: `取消拉黑：名单第 ${index + 1} 位`, detail: '恢复与他的私聊与频道可见',
    }))
    this.targets.chatActionChoice(options, (choice) => {
      void this.unblockPlayer(choice.id)
    })
  }

  private applyBlockList(ids: readonly string[]): void {
    this.myBlocked = [...ids]
    this.blocksLoaded = true
  }

  private async ensureBlocks(): Promise<void> {
    if (this.blocksLoaded) {
      return
    }
    const outcome = await this.api.socialBlocks()
    if (outcome.kind === 'ok') {
      this.applyBlockList(outcome.data.blockedPlayerIds)
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
    this.composeKind = 'MARCH'
    this.rallyParams = null
    this.composePicks = {}
    this.composeNotice = null
    this.deliverCompose()
    // 政策在打开编成之后才拉：它只决定"能不能切、界是多少"，晚到只会让那一行晚点出现，
    // 不该把玩家点开面板这一下挡在后面
    void this.loadRallyPolicy()
  }

  /**
   * 拉一次发起集结的政策。公开是为了**用例能 await 它**：`beginMarchCompose` 里那一发是
   * 后台补拉，用例不等它就永远读不到政策，"切到联盟档要长出参数行"这条断言就只能靠时序碰运气。
   * 失败不弹错误条：这一格读不到只是"暂时不知道能不能"，不是玩家做错了什么。
   */
  async loadRallyPolicy(): Promise<void> {
    const outcome = await this.api.rallyPolicy()
    if (outcome.kind !== 'ok') {
      return
    }
    this.rallyPolicyResp = outcome.data
    if (this.rallyParams !== null) {
      this.rallyParams = rebind(this.rallyParams, this.composeKind, outcome.data)
    }
    this.deliverCompose()
  }

  /**
   * 在编成面板上循环切换命令种类：出征 → 小队集结 → 联盟集结 → 出征（B26 S12 两态扩成 S13b 三态）。
   *
   * <p>为什么放在编成面板而不是在目标行上再加一颗按钮：同一份兵、同一个目标，
   * 换的只是**命令种类** —— 在目标行上摆两颗键会把"我打他"这件事拆成两个入口，
   * 而玩家在选目标那一刻通常还没决定要派多少兵。
   *
   * <p>能不能发起：改由 `GET /rally/policy` 判（组织在不在、职位有没有那一位、人数够不够最低档
   * 三条都在服务端算），**政策读不到时不置灰**，因为"还没拉到"不是"你不行"
   * （同一口径见 PermissionGates 的注释），真发出去由服务端裁决并回一句人话。
   */
  toggleComposeRally(): void {
    if (this.composeTarget === null) {
      return
    }
    const next = nextKind(this.composeKind)
    if (next !== 'MARCH') {
      const blocked = blockedReason(next, this.rallyPolicyResp)
      if (blocked !== null) {
        this.composeNotice = blocked
        this.deliverCompose()
        return
      }
    }
    this.composeKind = next
    this.rallyParams = defaultParams(next, this.rallyPolicyResp)
    this.composeNotice = null
    this.deliverCompose()
  }

  /** 调人数或准备时长。± 越界是夹住（夹取在纯逻辑里做，表现层不夹、判定在服务端）。 */
  adjustRallyParams(field: 'members' | 'prepare', direction: number): void {
    if (this.rallyParams === null) {
      return
    }
    this.rallyParams = {
      ...this.rallyParams,
      [field]: stepControl(this.rallyParams[field], direction),
    }
    this.deliverCompose()
  }

  /**
   * 发起集结（B26 S12 小队 / S13b 联盟）。目标与兵力用的是与出征同一份编成，只是命令种类不同：
   * 出征是"我打他"，集结是"我打他，等人一起"。发起人自己的兵在这一枪里就交出去
   * （服务端把发起人算作第一个参与者，之后他不能再 join 自己的集结），所以缺了这份兵
   * 一次集结永远只能带别人的兵出发。
   */
  private async confirmStartRally(target: { x: number, y: number, name: string },
                                  units: readonly { unitId: string, count: number }[]): Promise<void> {
    const alliance = this.composeKind === 'ALLIANCE_RALLY'
    const params = this.rallyParams
    if (alliance && params === null) {
      // 只有联盟档吃这两个数。读口没到就点发起：不猜一个人数发出去，也不静默失败，
      // 直接把"再等一下"说清楚。小队档本来就没有参数行，不能被这条挡住。
      this.composeNotice = '集结的人数与时长还没拉到，稍等一下再发'
      this.deliverCompose()
      return
    }
    this.track(TRACK_EVENTS.rallyInitiate, {
      scope: trackParam(alliance ? 'ALLIANCE' : 'SQUAD'),
      troops: trackParam(units.reduce((sum, unit) => sum + unit.count, 0)),
      // 只有联盟档真的带了这两个数；小队档写 0 会在看板上读成"0 人集结"
      ...(alliance && params !== null ? {
        members: trackParam(params.members.value),
        prepareMinutes: trackParam(params.prepare.value),
      } : {}),
    })
    this.composeSubmitting = true
    this.composeNotice = null
    this.deliverCompose()
    const body = {
      targetCoord: { x: target.x, y: target.y }, targetType: 'PLAYER_CITY' as const,
      troops: units.map(unit => ({ ...unit })), heroes: [],
    }
    const outcome = alliance && params !== null
      ? await this.api.allianceRally({ ...body, maxMembers: params.members.value,
        prepareMinutes: params.prepare.value })
      : await this.api.squadRally(body)
    this.composeSubmitting = false
    if (outcome.kind === 'ok') {
      this.composeKind = 'MARCH'
      this.rallyParams = null
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
    if (this.composeKind !== 'MARCH') {
      await this.confirmStartRally(target, units)
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
        kind: 'MARCH', submitLabel: '出征', rallyBlocked: null, rally: null,
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
      kind: this.composeKind,
      submitLabel: kindSubmitLabel(this.composeKind),
      rallyBlocked: blockedReason(this.composeKind, this.rallyPolicyResp),
      rally: this.rallyParams,
    })
  }

  // ---------- 目标搜索与流亡 ----------

  searchTargets(radius: number): Promise<void> {
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
