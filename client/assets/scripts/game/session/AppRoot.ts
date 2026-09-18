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
  AllianceMember, AllianceSyncResp, HelpRequestView, SocialSummaryResp,
} from '../../net/generated/SocialProtocol'
import type { SearchTargetsResp } from '../../net/generated/WorldProtocol'
import type { QuestListResp } from '../../net/generated/QuestProtocol'
import type { BattleReportListResp, BattleReportResp } from '../../net/generated/BattleProtocol'
import type { MailClaimAllResp, MailListResp } from '../../net/generated/MailProtocol'
import type { ActivityClaimResp, ActivityListResp } from '../../net/generated/ActivityProtocol'
import type { GuideAction, GuideProgressResp, GuideScriptResp } from '../../net/generated/GuideProtocol'
import { claimActivityReq } from '../activity/ActivityPanel'
import { buildLineupChoices, buildSpeedupChoices } from './Choices'
import type { LineupChoice, SpeedupChoice } from './Choices'
import type { GiftPopupResp } from '../../net/generated/PayProtocol'
import type { PayView } from '../pay/GiftPayFlow'
import { GiftPayFlow } from '../pay/GiftPayFlow'
import { requestMidasPayment } from '../../net/MidasPayment'
import { ClientReddotTree } from '../reddot/ReddotTree'

/** 面板需要落地的一类数据。全部可选：某个场景里没有这个面板时就不实现。 */
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
   * 礼包弹窗（B19 S3-iv）。`resp.popup=false` 时面板自己藏起来 —— 判"弹不弹"的是服务端，
   * 客户端只负责画。
   */
  giftPopup?(resp: GiftPopupResp, serverNowMs: number): void
  /** 一次购买的结果（由 `GiftPayFlow` 判定；面板只显示）。 */
  payResult?(view: PayView): void
  power?(resp: PowerDetailResp): void
  targets?(resp: SearchTargetsResp): void
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
  private heroResp: HeroListResp | null = null

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
        this.targets.social?.(summary.data, this.helpRequests, this.allianceMembers, offsetMs)
        if (degraded.length > 0) {
          this.targets.error?.('social', `${degraded.join('、')}，稍后会自动重试`)
        }
        return
      }
      case 'power':
        this.deliver('power', await this.api.playerPower(), r => this.targets.power?.(r))
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

  /** 卖出：服务端没有对应端点（`GameApi` 里也没有 `bagSell`），所以只能明确拒绝而不是静默。 */
  sellItem(itemId: string): void {
    this.track(TRACK_EVENTS.itemSell, { itemId })
    this.rejectNeeds('bag', `"${itemId}" 暂时不能卖：服务端还没有出售接口`)
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

  // ---------- 目标搜索与流亡 ----------

  searchTargets(radius: number): Promise<void> {
    this.track(TRACK_EVENTS.targetsSearch, { radius: trackParam(radius) })
    return this.write('targets', this.api.searchTargets({ radius, maxCount: 30 }), [],
      r => this.targets.targets?.(r))
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
