/**
 * 职责：唯一的启动入口 —— 造出 transport / NetModule / GameSession / GameApi / AppRoot，
 *      登录、拉首屏，并把本场景里**已经挂着**的面板 View 接到根上。
 * 依赖：cc（本文件是场景层，只有这里允许 import 'cc'）、game/session/*、scene/*PanelView。
 *
 * <p><b>为什么根在场景层而编排在 `game/session/AppRoot`</b>：`AppRoot` 要能在 node:test 里
 * 被完整驱动（点了按钮有没有真的发请求、失败有没有刷新），一旦它 import 'cc' 就做不到了。
 * 所以这个文件只干三件只有场景能干的事：选 transport、找面板组件、把 `home` 转给 WorldMap。
 *
 * <p><b>面板必须与本组件挂在同一个节点上</b>（`getComponent` 而不是 `getComponentInChildren`）：
 * 这是刻意的窄约定。自动去子节点里翻找会带来一种更难查的状态——「面板在场景里，但没接到根上」，
 * 表现仍是按钮没反应；挂在同一节点上则接不上就是显式的装配错误。
 *
 * <p><b>两个 baseUrl/wsUrl 与 `worldLayout` 是编辑器可改字段</b>：客户端的 generated 里
 * 只有配置表的类型没有值（`global.json` 不进包），所以这几个数必须由启动流程注入 ——
 * 这是 `WorldContext` 与 `GameApiDeps` 的 TODO 早就记下的一条，不是本文件新造的口子。
 */

import { _decorator, Component, director, sys } from 'cc'
import { FetchHttpTransport } from '../net/FetchTransport'
import { NetModule } from '../net/NetModule'
import type { NetConfig, NetDeps } from '../net/NetModule'
import { WxHttpTransport, WxSocketTransport } from '../net/WxTransport'
import type { SocketTransport } from '../net/NetTransport'
import { Prng } from '../core/Prng'
import { TimeSync } from '../core/TimeSync'
import { gameStore } from '../game/store/Store'
import { AppRoot } from '../game/session/AppRoot'
import type { PanelTargets, Tracker } from '../game/session/AppRoot'
import { GameApi } from '../game/session/GameApi'
import type { GameApiDeps } from '../game/session/GameApi'
import { GameSession } from '../game/session/GameSession'
import { ApiTrackTransport } from '../game/track/ApiTrackTransport'
import { CrashReporter, installGlobalHooks } from '../game/session/CrashReporter'
import { TRACK_EVENTS } from '../game/track/TrackEvents'
import { TrackClient } from '../game/track/TrackClient'
import { CityPanelView } from './CityPanelView'
import { ArmyPanelView } from './ArmyPanelView'
import { HeroPanelView } from './HeroPanelView'
import { BagPanelView } from './BagPanelView'
import { StagePanelView } from './StagePanelView'
import { QuestPanelView } from './QuestPanelView'
import { SocialPanelView } from './SocialPanelView'
import { PowerPanelView } from './PowerPanelView'
import { TargetSearchView } from './TargetSearchView'
import { WorldMap } from './WorldMap'
import { PanelNav } from './PanelNav'

const { ccclass } = _decorator

/** 与 `global.json` 同值。客户端拿不到表，所以在这里镜像一份，改了表要同步改这里。 */
const NET_RETRY_MAX_ATTEMPTS = 3
const NET_RETRY_BASE_DELAY_MS = 500
const NET_RETRY_MAX_DELAY_MS = 8_000
const NET_OFFLINE_QUEUE_MAX = 50
const REQUEST_TIMEOUT_MS = 5_000
const TIME_SYNC_ALPHA_FIXED = 2_000
const TIME_SYNC_JITTER_FACTOR_FIXED = 30_000
const TIME_SYNC_INITIAL_BEST_RTT_MS = 150
const WORLD_SIZE = 512
const WORLD_CHUNK_SIZE = 32
const VIEWPORT_CHUNK_COUNT = 9

/**
 * 客户端自报版本。点分数字，服务端按段比较（`ReleaseSystemTest` 钉过 "1.10.0" > "1.9.0"）。
 * 它是**发出去的参数**而不是玩法数值，所以不进 `global.json`；真正权威的那个是表里的
 * `RELEASE_LATEST_VERSION`，这里只是"我是谁"。
 */
const CLIENT_VERSION = '1.0.0'
/** 离线未发批次的缓冲上限。`TrackQueue` 刻意不设默认值，必须由组合根给一个。 */
const TRACK_MAX_UNSENT_BATCHES = 3
/** `tick()` 的驱动间隔。埋点攒批按秒判定，所以一秒一次足够且不会每帧读时钟。 */
const TRACK_TICK_INTERVAL_MS = 1_000
/** 会话结束时若已连续空闲超过这个秒数，就把它记成一次流失信号。 */
const CHURN_IDLE_SECONDS = 120

/** `wx` 只在小游戏运行时存在；这就是全仓库唯一的平台判定。 */
function isWxRuntime(): boolean {
  return typeof (globalThis as Record<string, unknown>).wx !== 'undefined'
}

@ccclass('GameBootstrap')
export class GameBootstrap extends Component {
  /** HTTP 基址，无尾斜杠。 */
  baseUrl = 'http://localhost:8080'
  wsUrl = 'ws://localhost:8080/ws'
  deviceId = ''
  nickName = '无名君主'

  /** 编排本体。其它场景组件要调服务端就通过它，不要各自 new 一条网络栈。 */
  root: AppRoot | null = null

  private net: NetModule | null = null
  private unsubscribeNetworkEvents: (() => void) | null = null
  private booting = false
  private trackClient: TrackClient | null = null
  private crash: CrashReporter | null = null
  private uninstallCrashHooks: (() => void) | null = null
  private lastTickAt = 0
  private lastActionAt = 0

  /**
   * 崩溃时所在场景名。拿不到就返回 null ——
   * "崩在场景切换之间"本身就是有效信息，编一个名字反而会把排查的人带到别处去。
   */
  /**
   * 本机固定账号 id。
   *
   * <p><b>为什么必须持久化</b>：留空时原来每次刷新都生成 `web-<时间戳>` ——
   * 也就是说玩家每次刷页面都会变成一个新号，刚升的建筑、刚领的任务全都不见了。
   * 用 {@code sys.localStorage} 而不是 window.localStorage：后者在微信小游戏里不存在。
   *
   * <p>受限环境（隐私模式、部分 WebView）读写可能抛异常：那就退回"本次临时账号"，
   * 但**不能因此启动失败** —— 登不进去比丢档更糟。
   */
  private resolveDeviceId(): string {
    if (this.deviceId.length > 0) {
      return this.deviceId
    }
    const key = 'ironoath.deviceId'
    try {
      const saved = sys.localStorage.getItem(key)
      if (saved !== null && saved.length > 0) {
        return saved
      }
      const created = `web-${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
      sys.localStorage.setItem(key, created)
      return created
    } catch (error) {
      console.warn('[session] 本机存储不可用，本次使用临时账号（刷新后会换号）', error)
      return `web-${Date.now()}`
    }
  }
  private currentSceneName(): string | null {
    const d = director as unknown as { getScene?: () => { name?: string } | null }
    const name = typeof d.getScene === 'function' ? d.getScene()?.name : undefined
    return typeof name === 'string' && name.length > 0 ? name : null
  }

  override onLoad(): void {
    this.installViewportGuard()
    // 导航层：由它建出各面板节点（初始未激活，因此不会九个面板一起画满屏背景），
    // 本组件只按 key 去找它们。放在 boot 之前：targets() 在登录成功后要立刻找得到这些组件。
    this.node.addComponent(PanelNav)
    void this.boot()
  }

  /** 按导航 key 取面板组件。未激活的节点也能拿到组件对象（只是还没跑 onLoad）。 */
  private panel<T extends Component>(ctor: new () => T, key: string): T | null {
    const node = this.node.getChildByName(key)
    return node === null ? null : node.getComponent(ctor)
  }

  /**
   * 窄视口提示：游戏按 960×640 横屏设计，在窄高窗口（侧栏、竖屏手机）里等比缩放后
   * 字会小到看不清 —— 表现是"顶部一小条、下面全黑，看不出来是什么"。
   *
   * <p>与其让玩家面对一个缩小的画面猜，不如显式告诉他「把窗口拉宽」，并给一个全屏按钮。
   * 只在有 DOM 的运行时安装（微信小游戏走 wx API，不走这条）。
   */
  private installViewportGuard(): void {
    if (typeof document === 'undefined' || typeof window === 'undefined') {
      return
    }
    const MIN_WIDTH = 900
    const overlay = document.createElement('div')
    overlay.style.cssText = [
      'position:fixed', 'inset:0', 'z-index:99999', 'display:none',
      'align-items:center', 'justify-content:center', 'flex-direction:column',
      'gap:16px', 'background:rgba(12,10,9,0.96)', 'color:#e2d6be',
      'font:16px/1.6 "Microsoft YaHei",sans-serif', 'text-align:center', 'padding:24px',
    ].join(';')
    const title = document.createElement('div')
    title.textContent = '窗口太窄，画面会小到看不清'
    title.style.cssText = 'font-size:20px;color:#b8860b'
    const detail = document.createElement('div')
    const button = document.createElement('button')
    button.textContent = '进入全屏'
    button.style.cssText = 'padding:10px 24px;font-size:16px;background:#b8860b;'
      + 'color:#1a1310;border:0;border-radius:6px;cursor:pointer'
    button.addEventListener('click', () => {
      const root = document.documentElement
      if (typeof root.requestFullscreen === 'function') {
        void root.requestFullscreen()
      }
    })
    overlay.appendChild(title)
    overlay.appendChild(detail)
    overlay.appendChild(button)
    document.body.appendChild(overlay)
    const sync = (): void => {
      overlay.style.display = window.innerWidth < MIN_WIDTH ? 'flex' : 'none'
      detail.textContent = `当前宽度 ${window.innerWidth}px；游戏按横屏设计，建议至少 ${MIN_WIDTH}px。`
        + '把窗口拉宽后这层提示会自动消失。'
    }
    sync()
    window.addEventListener('resize', sync)
  }

  /**
   * 埋点的秒级驱动 + 活动时间戳。
   *
   * <p>`TrackClient` 刻意不自带定时器（常驻定时器在 Cocos 里会跨场景继续跑），
   * 所以由本组件的 `update` 驱动。这里也不用 `dt` 累加：`sys.now()` 与事件时间戳同源，
   * 累 `dt` 会让"隔 10 秒 flush"在掉帧时比服务端预期慢一截。
   */
  override update(): void {
    const now = sys.now()
    if (now - this.lastTickAt < TRACK_TICK_INTERVAL_MS) {
      return
    }
    this.lastTickAt = now
    this.trackClient?.tick()
  }

  override onDestroy(): void {
    // 先卸载全局钩子：不卸的话场景都没了，异常仍然会回调到这个已释放的组件上，
    // 表现是"切场景之后偶发报错"，而没人知道是谁还在监听
    this.uninstallCrashHooks?.()
    this.uninstallCrashHooks = null
    this.crash = null
    const client = this.trackClient
    if (client !== null) {
      const idleSeconds = Math.floor((sys.now() - this.lastActionAt) / 1000)
      if (idleSeconds >= CHURN_IDLE_SECONDS) {
        client.track(TRACK_EVENTS.churn, { idleSec: String(idleSeconds), lastAction: 'none' })
      }
      // 进程马上就不在了：不冲刷的话这条会话的尾部事件会永远留在内存里
      client.onBackground()
      void client.settle()
    }
    this.unsubscribeNetworkEvents?.()
    this.unsubscribeNetworkEvents = null
    this.net?.disconnect()
    this.net = null
    this.root = null
    this.trackClient = null
  }

  /**
   * 起网络栈 → 登录 → 拉首屏 → 接面板。
   *
   * <p>面板**先接后登录**：View 的 `attach()` 在节点 `onLoad` 之前会先进 pending 缓冲，
   * 所以接得早不会丢数据；反过来先登录再接，就会有响应因为没人接而落空。
   */
  private async boot(): Promise<void> {
    if (this.booting) {
      return
    }
    this.booting = true
    const timeSync = new TimeSync({
      alphaFixed: TIME_SYNC_ALPHA_FIXED,
      jitterFactorFixed: TIME_SYNC_JITTER_FACTOR_FIXED,
      initialBestRttMs: TIME_SYNC_INITIAL_BEST_RTT_MS,
    })
    let seq = 0
    const deps: NetDeps = {
      http: isWxRuntime() ? new WxHttpTransport(REQUEST_TIMEOUT_MS)
        : new FetchHttpTransport(REQUEST_TIMEOUT_MS),
      socketFactory: (): SocketTransport => new WxSocketTransport(this.wsUrl),
      now: () => sys.now(),
      delay: (ms: number) => new Promise<void>(resolve => {
        setTimeout(resolve, ms)
      }),
      // 只用于退避抖动，不参与任何结算，所以种子可以来自时钟
      rng: Prng.of(Date.now()),
      newRequestId: () => `req-${Date.now()}-${++seq}`,
      newTraceId: () => `trace-${Date.now()}-${seq}`,
    }
    const config: NetConfig = {
      baseUrl: this.baseUrl,
      wsUrl: this.wsUrl,
      maxRetryAttempts: NET_RETRY_MAX_ATTEMPTS,
      retryBaseDelayMs: NET_RETRY_BASE_DELAY_MS,
      retryMaxDelayMs: NET_RETRY_MAX_DELAY_MS,
      offlineQueueMax: NET_OFFLINE_QUEUE_MAX,
      requestTimeoutMs: REQUEST_TIMEOUT_MS,
    }
    const net = new NetModule(config, deps)
    this.net = net
    const session = new GameSession({ net, store: gameStore, timeSync,
      now: () => sys.now(), newRequestId: deps.newRequestId })
    const apiDeps: GameApiDeps = {
      net, store: gameStore, timeSync, now: () => sys.now(), newRequestId: deps.newRequestId,
      worldLayout: { worldSize: WORLD_SIZE, chunkSize: WORLD_CHUNK_SIZE, maxChunks: VIEWPORT_CHUNK_COUNT },
    }
    const api = new GameApi(apiDeps)

    // 攒批策略由服务端下发（10 条或 10 秒那种），客户端不写死。appVersion 是公开端点，
    // 登录前就能调 —— 所以 startup 这个"登录前"的事件才有地方发。
    const tracker = await this.buildTracker(api)
    this.trackClient = tracker

    // 崩溃上报**不依赖埋点是否可用**：拿不到 TrackPolicy 时游戏照样要能收崩溃。
    // 有 tracker 就走它（`TrackClient.reportCrash` 不排队、不重投、失败只告警），
    // 没有就直接发端点 —— 两条路都是"尽力一次"，因为崩溃上报重投的意义是零（进程可能已退出）
    this.crash = new CrashReporter({
      sink: tracker === null
        ? {
          report: (crash) => {
            void api.reportCrash({ ...crash, ts: Date.now() }).then(
              (outcome) => {
                if (outcome.kind !== 'ok') {
                  console.warn('[crash] 崩溃上报未被收下', outcome.kind)
                }
              },
              (error: unknown) => console.warn('[crash] 崩溃上报失败', error),
            )
          },
        }
        : { report: (crash) => tracker.reportCrash(crash) },
      clientVersion: CLIENT_VERSION,
      sceneName: () => this.currentSceneName(),
      traceId: () => net.lastTraceId(),
      now: () => sys.now(),
    })
    this.uninstallCrashHooks = installGlobalHooks((error) => this.crash?.handle(error))

    this.unsubscribeNetworkEvents = session.bindNetworkEvents()
    // 每次动作都会经过根的 track，所以活动时间戳在这里刷一次就够 ——
    // 否则要在十四个回调里各写一遍"记得我还在"，而漏写的那个动作会变成"玩家没做它"
    const activity: Tracker | null = tracker === null ? null : {
      track: (name, params) => {
        this.lastActionAt = sys.now()
        tracker.track(name, params)
      },
    }
    this.root = new AppRoot({ api, session, store: gameStore, timeSync, targets: this.targets(),
      tracker: activity })
    this.lastActionAt = sys.now()
    tracker?.track(TRACK_EVENTS.startup, { clientVersion: CLIENT_VERSION })
    await this.root.start(this.resolveDeviceId(), this.nickName)
  }

  /**
   * 取 `TrackPolicy` 并建埋点客户端。
   *
   * <p>拿不到就返回 null 并说一声：埋点不是玩法功能，一次版本查询失败不该把玩家挡在游戏外；
   * 但也绝不静默 —— 静默的表现是"漏斗少了最前一环"，而没人会怀疑到上报上。
   */
  private async buildTracker(api: GameApi) {
    const outcome = await api.appVersion(CLIENT_VERSION, null)
    if (outcome.kind !== 'ok') {
      console.warn(`[track] 没拿到 TrackPolicy，本次会话不上报埋点：${outcome.kind === 'biz'
        ? outcome.msg : outcome.message}`)
      return null
    }
    const rules = {
      maxBatchSize: outcome.data.trackPolicy.maxBatchSize,
      flushIntervalMs: outcome.data.trackPolicy.flushSeconds * 1000,
      maxUnsentBatches: TRACK_MAX_UNSENT_BATCHES,
    }
    return new TrackClient(rules, new ApiTrackTransport(api, CLIENT_VERSION), () => sys.now())
  }

  /** 本节点上挂了哪些面板，就接哪些。没挂的面板不会被假装接上（根只会少发那份请求的落地）。 */
  private targets(): PanelTargets {
    const city = this.panel(CityPanelView, 'city')
    const army = this.panel(ArmyPanelView, 'army')
    const hero = this.panel(HeroPanelView, 'hero')
    const bag = this.panel(BagPanelView, 'bag')
    const stage = this.panel(StagePanelView, 'stage')
    const social = this.panel(SocialPanelView, 'social')
    const power = this.panel(PowerPanelView, 'power')
    const search = this.panel(TargetSearchView, 'targets')
    const quest = this.panel(QuestPanelView, 'quest')
    const world = this.panel(WorldMap, 'world')
    const out: PanelTargets = {
      error: (panel, message) => console.warn(`[${panel}] ${message}`),
    }
    if (city !== null) {
      out.city = (resp, offsetMs) => city.attach(resp, offsetMs)
      out.cityCollect = resp => city.attachCollect(resp)
      city.onUpgrade = configId => { void this.root?.upgradeBuilding(configId) }
      city.onSpeedUp = (buildingId, source) => { void this.root?.speedUpBuilding(buildingId, source) }
      city.onCollect = buildingId => { void this.root?.collect(buildingId) }
    }
    if (army !== null) {
      out.army = (resp, offsetMs) => army.attach(resp, offsetMs)
      army.onTrain = (unitId, count) => { void this.root?.train(unitId, count) }
      army.onTreat = () => { void this.root?.treatWounded() }
    }
    if (hero !== null) {
      out.hero = resp => hero.attach(resp)
      hero.onHeroAction = (heroId, action) => {
        console.warn(`[hero] "${action}" 需要额外的选择输入（道具/技能槽），编排层还没有对应动作`)
        void heroId
      }
    }
    if (bag !== null) {
      out.bag = resp => bag.attachBag(resp)
      out.resources = resp => bag.attachResources(resp)
      bag.onUseItem = (itemId, needsTarget) => { void this.root?.useItem(itemId, needsTarget) }
      bag.onSellItem = itemId => { this.root?.sellItem(itemId) }
    }
    if (stage !== null) {
      out.stage = resp => stage.attach(resp)
      stage.onChallenge = stageId => { this.root?.challenge(stageId) }
      stage.onSweep = (stageId, count) => { void this.root?.sweep(stageId, count) }
    }
    if (social !== null) {
      out.social = (resp, helps, members, offsetMs) => social.attach(resp, helps, members, offsetMs)
    }
    if (power !== null) {
      out.power = resp => power.render(resp)
    }
    if (search !== null) {
      out.targets = resp => search.attach(resp)
      search.onSearchRequested = radius => { void this.root?.searchTargets(radius) }
    }
    if (quest !== null) {
      out.quest = resp => quest.attach(resp)
      quest.onClaim = (questId, heroChoice) => { void this.root?.claimQuest(questId, heroChoice) }
    }
    if (world !== null) {
      // 「回城」按钮此前是个空函数，就是因为没人把家坐标交给它（WorldMap 里那条 TODO）
      out.home = (x, y) => world.focusHome(x, y)
    }
    return out
  }
}
