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
import type { PowerDetailResp } from '../../net/generated/Protocol'
import type { CityCollectResp, CityListResp, SpeedUpSource } from '../../net/generated/CityProtocol'
import type { ArmyListResp } from '../../net/generated/ArmyProtocol'
import type { BagListResp, ResourceDetailResp } from '../../net/generated/BagProtocol'
import type { HeroListResp } from '../../net/generated/HeroProtocol'
import type { StageListResp } from '../../net/generated/StageProtocol'
import type {
  AllianceMember, AllianceSyncResp, ChatChannel, ChatMessageView, FriendView, HelpRequestView,
  ReportReason, SocialEventView, SocialSummaryResp,
} from '../../net/generated/SocialProtocol'
import {
  ackablePrivateEventIds, buildChatPanel, chatFailureText, chatKey, CHAT_LOCAL_HISTORY_MAX,
  mergeChatHistory,
} from '../social/ChatPanel'
import type { ChatPanelData } from '../social/ChatPanel'
import { buildRankBoard } from '../power/RankBoard'
import {
  buildCompose, marchUnitsOf, rememberMarch, setPick,
} from '../world/MarchCompose'
import type { ComposeView, MarchSpec } from '../world/MarchCompose'
import type { RankBoardView, RankTabKey } from '../power/RankBoard'
import type { RankListResp } from '../../net/generated/RankProtocol'
import { gameBus } from '../../core/EventBus'
import type { SearchTargetsResp } from '../../net/generated/WorldProtocol'
import type { QuestListResp } from '../../net/generated/QuestProtocol'
import type {
  BattleReportListResp, BattleReportResp, ShareChannel,
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
}

export interface PanelTargets {
  city?(resp: CityListResp, offsetMs: number): void
  /** 一次收割的即时结果（要立刻飘字，之后再被 city 列表覆盖）。 */
  cityCollect?(resp: CityCollectResp): void
  army?(resp: ArmyListResp, offsetMs: number): void
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
  | 'quest' | 'reddot' | 'mail' | 'reports' | 'activity' | 'guide'

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
    const outcome = await this.session.login(deviceId, nickName, wxCode)
    if (outcome.kind !== 'ok') {
      this.say('session', outcome)
      return false
    }
    this.track(TRACK_EVENTS.login, {
      playerId: outcome.data.playerId,
      mainLevel: trackParam(this.store.getState().cityLevel),
    })
    await this.prefetch('city', 'army', 'hero', 'bag', 'resources', 'stage', 'social', 'power',
      'world', 'quest', 'reddot')
    return true
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
          this.targets.army?.(r, offsetMs)
        })
        return
      case 'hero':
        this.deliver('hero', await this.api.heroList(), r => {
          this.heroResp = r
          this.targets.hero?.(r)
        })
        return
      case 'bag':
        this.deliver('bag', await this.api.bagList(), r => this.targets.bag?.(r))
        return
      case 'resources':
        this.deliver('resources', await this.api.resourceDetail(), r => this.targets.resources?.(r))
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
        this.targets.social?.(summary.data, this.helpRequests, this.allianceMembers, offsetMs)
        this.deliverChat()
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
      case 'quest':
        this.deliver('quest', await this.api.questList(), r => this.targets.quest?.(r))
        return
      case 'reports':
        this.deliver('reports', await this.api.battleReports(),
          r => this.targets.reports?.(r, this.timeSync.serverNow()))
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

  // ---------- 军队 ----------

  train(unitId: string, count: number): Promise<void> {
    this.track(TRACK_EVENTS.armyTrain, { unitId, count: trackParam(count) })
    return this.write('army', this.api.armyTrain({ unitId, count }), ['army'])
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
        this.rejectNeeds('bag', '这个道具要先选择目标，目标选择器未接入')
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
      this.rejectNeeds('stage', `挑战 "${stageId}" 要先选出战阵容，阵容选择器未接入`)
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
    if (key !== 'DETAIL') {
      // 榜的关注度只有这里能答（明细页是原本就有的页面，不算"看榜"这个动作）
      this.track(TRACK_EVENTS.rankView, { type: trackParam(key) })
    }
    this.rankTab = key
    this.rankPage = 1
    this.rankNotice = null
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

  /** 组装并下发整块视图。表现层不参与任何计算（名次/页号全部来自上面那份响应）。 */
  private deliverRank(): void {
    this.targets.rank?.(buildRankBoard(this.rankResp, this.rankTab, this.playerId ?? '',
      this.rankNotice))
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
      this.rejectNeeds('chat', '这条消息能举报或拉黑，但动作选择器未接入')
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
      this.rejectNeeds('chat', '黑名单要先接入动作选择器才能解除')
      return
    }
    if (this.myBlocked.length === 0) {
      this.chatNotice = '黑名单是空的'
      this.deliverChat()
      return
    }
    const options = this.myBlocked.map((id) => ({
      id, kind: 'UNBLOCK' as const, reason: null,
      label: `取消拉黑：${id}`, detail: '恢复与他的私聊与频道可见',
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
      this.rejectNeeds('reports', `分享 "${reportId}" 要先选目标频道，频道选择器未接入`)
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
    this.composePicks = {}
    this.composeNotice = null
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

  /** 把编成整块推给面板。**没有编成目标时也推一次**，面板据此收起。 */
  private deliverCompose(): void {
    const compose = buildCompose(this.armyResp ?? EMPTY_ARMY, this.composePicks)
    if (this.composeTarget === null) {
      this.targets.marchCompose?.({
        targetId: '', targetName: '', coordText: '', compose,
        notice: this.composeNotice, submitting: false,
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
