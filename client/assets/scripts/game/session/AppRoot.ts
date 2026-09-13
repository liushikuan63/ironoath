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
import { buildLineupChoices, buildSpeedupChoices } from './Choices'
import type { LineupChoice, SpeedupChoice } from './Choices'
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
  power?(resp: PowerDetailResp): void
  targets?(resp: SearchTargetsResp): void
  /**
   * 任务面板（B12 §1）。**行里带 {@code heroChoices}**：首日那条主线送将任务是三选一，
   * 界面必须先让玩家选一个再领（服务端刻意不替玩家默认挑）。
   */
  quest?(resp: QuestListResp): void
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
  | 'quest' | 'reddot'

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
    await this.refresh('city', 'army', 'hero', 'bag', 'resources', 'stage', 'social', 'power',
      'world', 'quest', 'reddot')
    return true
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
        const sync = await this.api.allianceSync({ version: this.memberVersion, wantMembers: true })
        if (sync.kind === 'ok') {
          this.applyMemberDiff(sync.data)
        } else {
          degraded.push('成员列表暂时拉不到')
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
}
