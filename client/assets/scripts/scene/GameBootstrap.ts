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
 * <p><b>两个 baseUrl/wsUrl 是编辑器可改字段</b>：客户端的 generated 里
 * 只有配置表的类型没有值（`global.json` 不进包），所以地址必须由启动流程注入。
 * （`worldLayout` 曾同在这份名单里，2026-09-13 起改由 `MarchListResp` 随响应下发，
 * 客户端不再镜像 global.json 的 512/32/9。）
 */

// 这一行必须在所有 import 之前：模块按导入顺序深度优先求值，把它挪到后面（哪怕只挪一行）
// 就等于把锚点推迟，`bootMs` 会凭空少算一段 —— 而且少多少取决于前面那些模块谁先跑，
// 每次少得不一样多，所以退化不会以"变慢了"的形式出现，只会以"变快了"的形式出现。
import { bootElapsedMs } from '../game/session/BootClock'
import { _decorator, Color, Component, director, Graphics, Label, Node, Size, sys, UITransform, view } from 'cc'
import { FetchHttpTransport } from '../net/FetchTransport'
import { NetModule } from '../net/NetModule'
import type { NetConfig, NetDeps } from '../net/NetModule'
import { WxHttpTransport, WxSocketTransport } from '../net/WxTransport'
import { BrowserSocketTransport } from '../net/BrowserTransport'
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
import { decideUpdateGate } from '../game/release/UpdateGate'
import { applySystemUiFont } from './UiFont'
import { SettingsPanelView } from './SettingsPanelView'
import { GiftPopupView } from './GiftPopupView'
import type { SettingsAction } from '../game/settings/SettingsPanel'
import { planPrivacyPrompt } from '../game/privacy/PrivacyConsent'
import type { PrivacyPlan } from '../game/privacy/PrivacyConsent'
import { NetworkNotice } from '../game/network/NetworkNotice'
import type { UpdateGateDecision } from '../game/release/UpdateGate'
import type { AppVersionResp } from '../net/generated/OpsProtocol'
import type { NetOutcome, NetworkSignal } from '../net/NetModule'
import { TrackClient } from '../game/track/TrackClient'
import { CityPanelView } from './CityPanelView'
import { ArmyPanelView } from './ArmyPanelView'
import { HeroPanelView } from './HeroPanelView'
import { BagPanelView } from './BagPanelView'
import { StagePanelView } from './StagePanelView'
import { QuestPanelView } from './QuestPanelView'
import { MailPanelView } from './MailPanelView'
import { GuideView } from './GuideView'
import { BattleReportPanelView } from './BattleReportPanelView'
import { playbackOptionsOf } from '../game/battle/BattleReportPanel'
import { SocialPanelView } from './SocialPanelView'
import { PowerPanelView } from './PowerPanelView'
import { ShopPanelView } from './ShopPanelView'
import { AvatarFramePanelView } from './AvatarFramePanelView'
import { TargetSearchView } from './TargetSearchView'
import { MarchComposeOverlay } from './MarchComposeOverlay'
import { OfflineReportOverlay } from './OfflineReportOverlay'
import { WorldMap } from './WorldMap'
import { PanelNav } from './PanelNav'
import { installAudio, isMuted, playSfx, setMuted } from './AudioService'
import { preloadRuntimeArt } from './ArtCatalog'
import { claimReceiptText } from '../game/activity/ActivityPanel'

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
/**
 * 取平台的隐私设置。没有这个接口（浏览器/编辑器）时返回 null —— 由纯模块决定这意味着什么。
 *
 * <p><b>查询是异步的</b>，而它只影响"这次弹不弹"：所以回调里拿到结果就自己发起授权，
 * 不把它塞进启动序列等待 —— 为一个"没查到也不拦启动"的答案加一个等待点不划算。
 */
function readPrivacySetting(): { needAuthorization: boolean; privacyContractName: string | null } | null {
  const wxApi = (globalThis as Record<string, unknown>).wx as {
    getPrivacySetting?: (options: Record<string, unknown>) => void
  } | undefined
  if (wxApi?.getPrivacySetting === undefined) {
    return null
  }
  try {
    wxApi.getPrivacySetting({
      success: (res: { needAuthorization?: boolean; privacyContractName?: string }) => {
        if (res.needAuthorization === true) {
          requestPrivacyAuthorization()
        }
      },
      fail: (error: unknown) => console.warn('[privacy] getPrivacySetting 失败', error),
    })
  } catch (error) {
    console.warn('[privacy] getPrivacySetting 抛异常', error)
    return null
  }
  // 接口在（能查）但答案还没回来：这一次按"要问"处理由平台弹，
  // 平台自己知道用户同意过没有 —— 重复问一次不会造成多余的弹窗
  return { needAuthorization: true, privacyContractName: null }
}

/** 让平台弹它自己的隐私授权弹窗。失败只记日志：<b>不拦启动</b>（见 PrivacyConsent 里的取舍）。 */
function requestPrivacyAuthorization(): void {
  const wxApi = (globalThis as Record<string, unknown>).wx as {
    requirePrivacyAuthorize?: (options: Record<string, unknown>) => void
  } | undefined
  if (wxApi?.requirePrivacyAuthorize === undefined) {
    return
  }
  wxApi.requirePrivacyAuthorize({
    success: () => console.log('[privacy] 用户已同意隐私协议'),
    fail: (error: unknown) => console.warn('[privacy] 用户未同意隐私协议', error),
  })
}

/** 打开平台配置的协议页（设置页那一行）。 */
function openPrivacyContract(): void {
  const wxApi = (globalThis as Record<string, unknown>).wx as {
    openPrivacyContract?: (options: Record<string, unknown>) => void
  } | undefined
  if (wxApi?.openPrivacyContract === undefined) {
    console.warn('[privacy] 当前平台没有 openPrivacyContract（浏览器/编辑器预览）')
    return
  }
  wxApi.openPrivacyContract({
    fail: (error: unknown) => console.warn('[privacy] 打开隐私协议失败', error),
  })
}

function isWxRuntime(): boolean {
  return typeof (globalThis as Record<string, unknown>).wx !== 'undefined'
}

@ccclass('GameBootstrap')
export class GameBootstrap extends Component {
  /** HTTP 基址，无尾斜杠。 */
  baseUrl = 'http://localhost:8080'
  wsUrl = 'ws://localhost:8080/ws'
  deviceId = ''
  /** 最近一次 /ops/app/version 的响应：设置页要显示版本与客服入口，拦更新时也要用。 */
  private appVersion: AppVersionResp | null = null
  /** 隐私授权计划：启动时问一次平台，设置页那行与 [boot] 自检行都要用它。 */
  private privacyPlan: PrivacyPlan = { request: false, contractName: null, apiAvailable: false }
  /** 弱网提示的判定（该说什么由 {@code NetworkNotice} 决定，场景只负责把它写进那一行）。 */
  private netNotice: NetworkNotice | null = null
  /** 顶部那一行（节点 + 文本）。没内容时节点不激活，所以它不长期占屏幕。 */
  private netNoticeRow: Node | null = null
  private netNoticeLabel: Label | null = null
  nickName = '无名君主'

  /** 编排本体。其它场景组件要调服务端就通过它，不要各自 new 一条网络栈。 */
  root: AppRoot | null = null

  private net: NetModule | null = null
  private nav: PanelNav | null = null
  /** 出征编成弹层（B25-S1）。它是弹层不是面板，所以不走 `panel()` 那张按名字查表的通道。 */
  private marchCompose: MarchComposeOverlay | null = null
  private offlineReport: OfflineReportOverlay | null = null
  /** 引导层（B18）：整屏遮罩 + 气泡，挂在所有面板与导航条之上。 */
  private guide: GuideView | null = null
  private unsubscribeNetworkEvents: (() => void) | null = null
  /** 推送订阅的退订句柄。`gameBus` 是模块级单例，不退订会让重登的第二个根也被调一次。 */
  private unsubscribePush: (() => void) | null = null
  private booting = false
  private destroyed = false
  private trackClient: TrackClient | null = null
  private crash: CrashReporter | null = null
  private uninstallCrashHooks: (() => void) | null = null
  private lastTickAt = 0
  private lastActionAt = 0
  /** 本次启动的面板视图装配账（targets() 里写）。找不到视图是静默的，所以账必须自己记。 */
  private panelViews: { attempted: number, missing: string[] } = { attempted: 0, missing: [] }

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

  /**
   * 取微信登录凭证（B15 §三）。
   *
   * <p>只有小游戏运行时才调 wx.login；浏览器/编辑器预览返回 null，
   * 服务端会退回 deviceId 建档（本地开发与既有单测的行为不变）。
   *
   * <p>登录失败不阻断启动：返回 null 走设备号路径，玩家仍能进游戏 ——
   * 微信侧偶发失败时"进不去"比"这次当作新设备"更糟（后者至少能玩，前者是黑屏）。
   * 服务端拿到 code 后换 openid，同一微信永远回到同一份存档。
   */
  private resolveWxCode(): Promise<string | null> {
    if (!isWxRuntime()) {
      return Promise.resolve(null)
    }
    return new Promise((resolve) => {
      wx.login({
        success: (res) => {
          if (typeof res.code === 'string' && res.code.length > 0) {
            resolve(res.code)
            return
          }
          console.warn('[session] wx.login 未返回 code，本次使用设备号建档')
          resolve(null)
        },
        fail: (err) => {
          console.warn('[session] wx.login 失败，本次使用设备号建档', err.errMsg)
          resolve(null)
        },
      })
    })
  }

  private currentSceneName(): string | null {
    const d = director as unknown as { getScene?: () => { name?: string } | null }
    const name = typeof d.getScene === 'function' ? d.getScene()?.name : undefined
    return typeof name === 'string' && name.length > 0 ? name : null
  }

  override onLoad(): void {
    this.installViewportGuard()
    void this.startup()
  }

  /** 先预加载美术，再建导航与面板；失败时加载器内部告警，UI 继续使用 Graphics 兜底。 */
  private async startup(): Promise<void> {
    await preloadRuntimeArt()
    if (this.destroyed) {
      return
    }
    // 导航层：由它建出各面板节点（初始未激活，因此不会九个面板一起画满屏背景），
    // 本组件只按 key 去找它们。放在 boot 之前：targets() 在登录成功后要立刻找得到这些组件。
    this.nav = this.node.addComponent(PanelNav)
    // 音效层挂在同一个 host 上：AudioSource 必须属于活跃场景，否则 playOneShot 一声不出且不报错
    installAudio(this.node)
    this.buildGuideLayer()
    // 邮件不占首屏：多一个并发请求会挤那 3 秒预算（首屏判据是「可交互」而不是「可见」），
    // 而邮箱不在可交互的必需项里 —— 玩家点开那一格才拉第一次。onShow 这个钩子此前挂着没人用。
    this.nav.onShow = key => {
      if (key === 'mail') {
        void this.root?.refresh('mail')
      }
      // 活动不占首屏（B17 §六：它是任务面板里的一个页签），玩家真点开任务那一格才拉第一次。
      // 每次打开都拉而不是"只拉一次"：读取路径在服务端会顺手同步窗口，缓存会让 EXPIRED 迟到
      if (key === 'quest') {
        void this.root?.refresh('activity')
      }
      // 商店不占首屏：多一个并发请求会挤那 3 秒预算，而货架不在可交互的必需项里
      if (key === 'shop') {
        void this.root?.refresh('shop')
      }
      // 外观同理（B24 块③），而且它更该每次打开都拉：框的「佩戴中」是这里唯一的状态来源，
      // 缓存会让"刚刚在商店买的那一枚"迟到（看着像买了没到账）
      if (key === 'avatarFrames') {
        void this.root?.refresh('avatarFrames')
      }
      // 引导的每一步都是"在某面板上弹"，所以换面板要重算一次该不该画（判定在驱动器里，这里只触发）
      this.guide?.repaint()
    }
    void this.boot()
  }

  /**
   * 引导层（B18）。节点在 PanelNav 之后挂上 ⇒ 同层兄弟里它排在最后，遮罩自然压住面板与导航条，
   * 不必给任何面板加"让位"的逻辑（那会在每个面板里各写一遍）。
   *
   * <p>它只装四根线：现在开着哪个面板、那块面板的可用区域、埋点出口、上报一步。
   * 一帧该长什么样、该不该有这一帧，全在 GuideDriver（那部分有 CI 用例）。
   */
  private buildGuideLayer(): void {
    const node = new Node('Guide')
    node.layer = this.node.layer
    this.node.addChild(node)
    const layer = node.addComponent(GuideView)
    this.guide = layer
    layer.openPanelKey = () => this.nav?.current() ?? null
    layer.contentRectFor = key => this.nav?.contentRectFor(key) ?? null
    layer.onTrack = (action, stepId, version) => this.root?.trackGuideStep(action, stepId, version)
    layer.onReport = (stepId, action) => {
      // 回执才能改位置：advanced=false 时服务端给回来的还是当前那一步，界面就留在原步等玩家
      void this.root?.guideProgress(stepId, action, resp => this.guide?.applyProgress(resp.nextStepIndex))
    }
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
    // 微信小游戏没有 DOM；即使适配层补出了 window/document，屏幕宽度也永远
    // 小于内城设计的 900px。不排除平台，玩家第一眼看到的就是一层"窗口太窄"。
    if (isWxRuntime() || typeof document === 'undefined' || typeof window === 'undefined') {
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
    this.destroyed = true
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
    this.unsubscribePush?.()
    this.unsubscribePush = null
    this.net?.disconnect()
    this.net = null
    this.nav = null
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
    // 弱网提示必须在**第一个请求之前**就建好：第一次请求（版本检查）本身就可能在弱网下重投，
    // 而 B16 验收 2 要的那句"重试提示"恰恰是给这一段等待看的
    this.netNotice = new NetworkNotice()
    this.createNetworkNoticeRow()
    const deps: NetDeps = {
      http: isWxRuntime() ? new WxHttpTransport(REQUEST_TIMEOUT_MS)
        : new FetchHttpTransport(REQUEST_TIMEOUT_MS),
      socketFactory: (url: string): SocketTransport =>
        isWxRuntime() ? new WxSocketTransport(url) : new BrowserSocketTransport(url),
      now: () => sys.now(),
      delay: (ms: number) => new Promise<void>(resolve => {
        setTimeout(resolve, ms)
      }),
      // 只用于退避抖动，不参与任何结算，所以种子可以来自时钟
      rng: Prng.of(Date.now()),
      newRequestId: () => `req-${Date.now()}-${++seq}`,
      newTraceId: () => `trace-${Date.now()}-${seq}`,
      notifyNetwork: (signal) => this.renderNetworkNotice(signal),
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
    }
    const api = new GameApi(apiDeps)

    // 版本闸门是**登录之前**的第一件事：协议写明 forceUpdate=true 时客户端必须停在提示页、
    // 不得进入游戏，而"进入游戏"的第一步就是登录与拉十个面板 —— 判定排在它们之后等于没拦。
    // 同一次响应后面还要用来建埋点（攒批策略在这份响应里），所以只发这一次请求。
    const version = await api.appVersion(CLIENT_VERSION, null)
    this.appVersion = version !== null && version.kind === 'ok' ? version.data : null
    // 隐私授权排在进入游戏之前：平台要求「使用隐私相关接口前先取得同意」，
    // 而登录就会带上设备与账号标识 —— 那正是隐私接口的范畴。
    // 弹窗与协议文本都由平台提供，本作不画、不存文案。
    this.privacyPlan = planPrivacyPrompt(readPrivacySetting())
    // 攒批策略由服务端下发（10 条或 10 秒那种），客户端不写死。appVersion 是公开端点，
    // 登录前就能调 —— 所以 startup 这个"登录前"的事件才有地方发。
    // 刻意建在闸门**之前**：被强制更新挡在提示页的那批玩家恰恰是最需要被数到的人
    // （他们"进不来"），没有 tracker 就读不到任何东西。update() 里的 trackClient.tick()
    // 与本方法的提前 return 无关，所以队列照样会冲出去。
    const tracker = await this.buildTracker(api, version)
    this.trackClient = tracker
    const gate = decideUpdateGate(version, CLIENT_VERSION)
    if (gate.blocked) {
      this.showUpdateNotice(gate)
      const blocked = {
        platform: isWxRuntime() ? 'wechat' : 'web',
        started: 'false',
        blocked: 'force-update',
        clientVersion: CLIENT_VERSION,
        latestVersion: gate.latest ?? '',
        bootMs: String(bootElapsedMs()),
      }
      console.log('[boot] ' + JSON.stringify({
        platform: blocked.platform,
        started: false,
        blocked: 'force-update',
        clientVersion: CLIENT_VERSION,
        latestVersion: gate.latest,
        notice: gate.notice,
        // 停在提示页也是一次首屏：玩家已经看到一个界面了。不带这个数的话，
        // "强制更新页出现得有多快"在小游戏运行时里永远是一格空白
        bootMs: bootElapsedMs(),
      }))
      // 自检行只有开发者工具的 Console 能看见，而那边不落文件 —— 同一个事实再发一份到服务端，
      // "被挡在更新页的人有多少"才是可查的。startup 刻意留到闸门之后：崩溃率的分母口径是
      // "这个版本有人跑起来"，把永久停在提示页的那批掺进去会稀释一个已有指标
      tracker?.track(TRACK_EVENTS.bootCheck, blocked)
      return
    }

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
    // 推送订阅在根构造之后立刻接上（B22 §一 1）：它把私聊「有人找你」的信标变成
    // 未读账 +1 与一次红点刷新。没接之前，服务端两路都发了、帧到了客户端被直接丢掉
    this.unsubscribePush = this.root.bindPush()
    this.lastActionAt = sys.now()
    tracker?.track(TRACK_EVENTS.startup, { clientVersion: CLIENT_VERSION })
    const started = await this.root.start(
      this.resolveDeviceId(), this.nickName, await this.resolveWxCode())
    if (started) {
      // 登录成功后问一次「这一屏弹不弹」（B19 S3-iv）。弹窗是加法：拉不到就这一屏不弹，
      // 所以这里不 await、也不接错误 —— 主流程不该被一个可选弹窗拖住
      void this.root.showGiftPopup()
    }
    // 回到前台：重校时 + 再问一次弹窗（挂后台期间错过的那次触发要补上）。
    // 这个回调只有小游戏运行时才有 —— 浏览器里没有 wx，静默跳过。
    // 根先取到局部变量：闭包里拿 this.root 会让类型收窄失效（它可能是 null）
    const foregroundRoot = this.root
    if (typeof wx !== 'undefined' && typeof wx.onShow === 'function' && foregroundRoot !== null) {
      wx.onShow(() => { void foregroundRoot.afterForeground() })
    }
    // 启动自检行：在微信开发者工具的 Console 里能一眼看出"到底跑起来没有"。
    // 小游戏没有可编程的自动化接口（miniprogram-automator 连上即断），
    // 所以这条日志就是 DevTools 内验证的入口 —— 它必须一行内给全判断依据：
    // 平台、登录结果、挂上的面板数、有没有拿到服务端会话票据。
    // 数的是 targets() 那一次装配里的**视图查找结果**，不是回调条数：一个面板会给 out 添两三个键
    // （city 与 cityCollect），按键数报"面板挂了几个"是一个会说谎的名字
    const missingPanels = this.panelViews.missing.join(',')
    if (missingPanels !== '') {
      // 缺一个视图 = 那个面板永远空白，而组件找不到时是静默返回 null 的：不在这里喊，
      // 就没有任何东西会知道（Console 之外还有一条 boot_check 能被读回来）
      console.warn(`[boot] 面板视图缺失：${missingPanels}`)
    }
    const state = gameStore.getState()
    console.log('[boot] ' + JSON.stringify({
      platform: isWxRuntime() ? 'wechat' : 'web',
      started,
      playerId: state.playerId,
      mountedPanels: this.panelViews.attempted - this.panelViews.missing.length,
      // 「找过几个」必须一起报：只有 mounted 与 missing 的话，
      // 一次都没跑过视图查找（装配整个被跳过）也是 mounted=0 + missing 为空，看起来全绿
      attemptedPanels: this.panelViews.attempted,
      missingPanels,
      hasAuthToken: this.net?.hasAuthToken() ?? false,
      clientVersion: CLIENT_VERSION,
      privacyApi: this.privacyPlan.apiAvailable,
      privacyAsked: this.privacyPlan.request,
      // 首屏耗时（B16 §一）。小游戏运行时里没有外部墙钟可读，这个自报数就是唯一的输入；
      // 但它是**下界** —— 从本包 JS 第一次被求值算起，不含包体下载与引擎初始化，
      // 那一段只能在真机上看。web-mobile 那边另有 verify-perf-runtime.mjs 的外部墙钟，两个数一起看
      bootMs: bootElapsedMs(),
    }))
    // 同一份事实再发一份到服务端：Console 里那行没人能读回来（IDE 不落文件），
    // 而"在开发者工具里跑通了"必须是一个可复核的结论而不是一句目击证词。
    tracker?.track(TRACK_EVENTS.bootCheck, {
      platform: isWxRuntime() ? 'wechat' : 'web',
      started: String(started),
      playerId: state.playerId ?? '',
      mountedPanels: String(this.panelViews.attempted - this.panelViews.missing.length),
      attemptedPanels: String(this.panelViews.attempted),
      missingPanels,
      hasAuthToken: String(this.net?.hasAuthToken() ?? false),
      clientVersion: CLIENT_VERSION,
      privacyApi: String(this.privacyPlan.apiAvailable),
      privacyAsked: String(this.privacyPlan.request),
      bootMs: String(bootElapsedMs()),
    })
    // 战报列表刻意不挤进首屏那一批：那边已经跑在 2.8 秒 / 3.0 秒的预算上，
    // 而「可交互」的判据里没有战报 —— 晚零点几秒到，玩家真点到那一格时通常已经拉完了。
    // 这一句就是它在小游戏里的唯一拉取路径（PanelNav.onShow 只管邮件那一格）。
    void this.root?.refresh('reports')
    // 登录后把**当前那一格**再通知一次。PanelNav 的初始 show 跑在它自己的 onLoad 里，
    // 那时上面那句 onShow 还没赋值、会话也还没建出来 —— 于是深链 ?panel=mail 进来的人
    // 看到的邮箱永远只有一副骨架（症状由 tools/verify-devtools-panels.mjs 抓到）。
    // 触发路径仍然只有 onShow 这一条：同 key 的 show() 只发通知、不重排激活，
    // 所以这里不需要再写一份「是邮件就拉邮件」的分支（那会变成第二个家）。
    // 登录没成功时不补：那一次拉取只会变成一条 panel_load_failed 噪声。
    // 引导只在登录后拉一次：老号会拿到 applies=false，于是这一层整个不画（服务端判，客户端不猜）
    if (started) {
      void this.root?.refresh('guide')
    }
    if (started && this.nav !== null) {
      this.nav.show(this.nav.current())
    }
  }

  /**
   * 设置页里点了「联系客服」或「申请退款」。
   *
   * <p><b>两个入口同一条路</b>：B15 §3 要求退款通道必须留，而那条通道就是客服 ——
   * 单开一套退款表单等于自建一个没人看的工单系统，那是另一件事。
   *
   * <p><b>打不开时必须说清是哪种打不开</b>：没配（服务端下发 null ⇒ 上游已经给了说明）、
   * 当前平台没有这个接口（浏览器/编辑器里跑）—— 这两种都不是故障，但都得让玩家看见，
   * 否则他的体感是「点了没反应」，而那会被当成 bug 报上来。
   */
  private handleSettingsAction(action: SettingsAction): void {
    if (action.kind === 'toggle-audio') {
      const next = !isMuted()
      setMuted(next)
      // 立刻重画那一行：文案不跟着翻，玩家会以为自己点错了地方。
      // 这里**不**补一声提示音 —— 刚静音就响，等于告诉玩家开关没生效。
      this.panel(SettingsPanelView, 'settings')
        ?.render(this.appVersion, CLIENT_VERSION, this.privacyPlan, next)
      return
    }
    if (action.kind === 'open-privacy-contract') {
      openPrivacyContract()
      return
    }
    if (action.kind === 'message') {
      this.showHint(action.text)
      return
    }
    const wxApi = (globalThis as Record<string, unknown>).wx as
      { openCustomerServiceChat?: (options: Record<string, unknown>) => void } | undefined
    if (wxApi?.openCustomerServiceChat === undefined) {
      console.warn('[settings] 当前平台没有 openCustomerServiceChat（浏览器/编辑器预览）'
        + '，corpId=' + action.corpId)
      this.showHint('请在微信小游戏内打开客服')
      return
    }
    // 参数形状照微信文档：corpId + extInfo.url；失败回调里把原始信息打出来，
    // 而不是让按钮静默失效 —— 客服打不开是提审与客诉都会撞到的事
    wxApi.openCustomerServiceChat({
      corpId: action.corpId,
      extInfo: { url: action.url },
      fail: (error: unknown) => {
        console.warn('[settings] 打开客服失败', error)
        this.showHint('打开客服失败，请稍后再试')
      },
    })
  }

  /**
   * 屏幕下方的一句提示，几秒后自己消失。
   *
   * <p>不引第三方 Toast：这里的用途只有"把刚刚那次点击的结果说清楚"，
   * 而一个挂在画布上的 Label 就是它的全部实现。
   */
  private showHint(text: string): void {
    // 有话要说就要有声音：这条是"结果提示"的唯一出口，音效挂在这里而不是挂在各视图的失败分支上
    playSfx('alert')
    const canvas = this.node.parent ?? this.node
    const hint = new Node('SettingsHint')
    canvas.addChild(hint)
    hint.layer = canvas.layer
    const visible = view.getVisibleSize()
    hint.addComponent(UITransform).setContentSize(new Size(visible.width - 80, 36))
    hint.setPosition(0, -visible.height / 2 + 90, 0)
    const label = applySystemUiFont(hint.addComponent(Label))
    label.string = text
    label.fontSize = 16
    label.lineHeight = 22
    label.color = new Color(226, 214, 190, 255)
    label.overflow = Label.Overflow.SHRINK
    hint.addComponent(Graphics)
    const background = hint.getComponent(Graphics)
    if (background !== null) {
      background.fillColor = new Color(24, 20, 18, 235)
      background.roundRect(-(visible.width - 80) / 2, -18, visible.width - 80, 36, 6)
      background.fill()
    }
    setTimeout(() => hint.destroy(), 3000)
  }

  /**
   * 强制更新的提示页（B16 §5）。
   *
   * <p>做法是**把画布上其余节点全部停用**，而不是只盖一层：盖一层的话导航条还在下面接着触摸，
   * 玩家点得动、面板却全是空的 —— 那比直接拦住更像"游戏坏了"。停用之后这一页就是全部，
   * 与协议里那句「必须停在提示页，不得进入游戏」是同一个意思。
   */
  private showUpdateNotice(gate: UpdateGateDecision): void {
    const canvas = this.node.parent ?? this.node
    for (const sibling of Array.from(canvas.children)) {
      sibling.active = false
    }
    const visible = view.getVisibleSize()
    const size = new Size(visible.width, visible.height)

    const layer = new Node('UpdateNotice')
    canvas.addChild(layer)
    layer.layer = canvas.layer
    layer.addComponent(UITransform).setContentSize(size)

    const bg = new Node('Bg')
    layer.addChild(bg)
    bg.layer = layer.layer
    bg.addComponent(UITransform).setContentSize(size)
    const graphics = bg.addComponent(Graphics)
    graphics.fillColor = new Color(18, 16, 14, 255)
    graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
    graphics.fill()

    const title = new Node('Title')
    layer.addChild(title)
    title.layer = layer.layer
    title.setPosition(0, size.height * 0.18, 0)
    title.addComponent(UITransform).setContentSize(new Size(size.width - 80, 34))
    const titleLabel = applySystemUiFont(title.addComponent(Label))
    titleLabel.string = '需要更新'
    titleLabel.fontSize = 26
    titleLabel.lineHeight = 32
    titleLabel.color = new Color(224, 190, 120, 255)

    const body = new Node('Body')
    layer.addChild(body)
    body.layer = layer.layer
    body.setPosition(0, 0, 0)
    body.addComponent(UITransform).setContentSize(new Size(size.width - 140, 160))
    const bodyLabel = applySystemUiFont(body.addComponent(Label))
    // 版本号必须一起显示：只说「请更新」的话，玩家分不清是自己版本旧还是服务器炸了
    bodyLabel.string = `${gate.notice ?? ''}\n\n当前版本 ${CLIENT_VERSION} ｜ 最新版本 ${gate.latest}`
    bodyLabel.fontSize = 16
    bodyLabel.lineHeight = 24
    bodyLabel.color = new Color(220, 214, 200, 255)
    bodyLabel.overflow = Label.Overflow.RESIZE_HEIGHT
    bodyLabel.horizontalAlign = Label.HorizontalAlign.CENTER
  }

  /**
   * 建顶部那行弱网提示。
   *
   * <p>位置在顶部而不是底部：底部横条被导航条占着，而一次弱网往往发生在玩家刚点完某个
   * 底部按钮之后 —— 提示压在按钮上会挡住他下一个动作。
   */
  private createNetworkNoticeRow(): void {
    const canvas = this.node.parent ?? this.node
    const visible = view.getVisibleSize()
    const width = visible.width
    const height = 30

    const row = new Node('NetworkNotice')
    canvas.addChild(row)
    row.layer = canvas.layer
    row.addComponent(UITransform).setContentSize(new Size(width, height))
    // 不用调层级：场景里的面板节点在 boot 之前就已经是 Canvas 的子节点，
    // 运行期 addChild 落在最后 = 画在它们上面（2D UI 的兄弟序就是绘制序）
    row.setPosition(0, visible.height / 2 - height, 0)

    const bg = new Node('Bg')
    row.addChild(bg)
    bg.layer = row.layer
    bg.addComponent(UITransform).setContentSize(new Size(width, height))
    const graphics = bg.addComponent(Graphics)
    graphics.fillColor = new Color(18, 16, 14, 200)
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()

    const text = new Node('Text')
    row.addChild(text)
    text.layer = row.layer
    text.addComponent(UITransform).setContentSize(new Size(width - 24, height))
    const label = applySystemUiFont(text.addComponent(Label))
    label.fontSize = 15
    label.lineHeight = 20
    label.color = new Color(232, 176, 96, 255)
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    this.netNoticeLabel = label
    this.netNoticeRow = row
    // 没有内容时不该占一条屏幕高度：初始就停用，等有话可说再打开
    row.active = false
  }

  /** 传输层的弱网事件 → 那一行的文字。判定全在 {@code NetworkNotice}，这里只做渲染。 */
  private renderNetworkNotice(signal: NetworkSignal): void {
    this.netNotice?.observe(signal)
    const text = this.netNotice?.current ?? null
    const label = this.netNoticeLabel
    if (label === null) {
      return
    }
    label.string = text ?? ''
    if (this.netNoticeRow !== null) {
      this.netNoticeRow.active = text !== null
    }
    if (signal.kind !== 'recovered') {
      // Console 那一行是给开发者工具/真机排查用的：路径与第几次重投都在那里，
      // 而屏幕上刻意不给路径（玩家不需要知道是 /stage/list 还是 /bag/list 没通）
      console.warn(`[net] ${signal.kind} ${'path' in signal ? signal.path : ''} `
        + `${'attempt' in signal ? signal.attempt : ''}${'attempts' in signal ? signal.attempts : ''}`)
    }
  }

  /**
   * 取 `TrackPolicy` 并建埋点客户端。
   *
   * <p>拿不到就返回 null 并说一声：埋点不是玩法功能，一次版本查询失败不该把玩家挡在游戏外；
   * 但也绝不静默 —— 静默的表现是"漏斗少了最前一环"，而没人会怀疑到上报上。
   */
  private async buildTracker(api: GameApi, outcome: NetOutcome<AppVersionResp>) {
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

  /**
   * 把礼包弹窗的节点挂上（B19 S3-iv 那一屏的宿主）。
   *
   * <p><b>为什么在代码里建</b>：本项目的场景文件里没有任何面板节点 —— 十五个面板全是
   * `PanelNav` 按清单建出来的，而礼包弹窗不在导航条上（它是模态弹窗，不该占一格），
   * 于是它成了唯一一个"只有视图类、没有宿主节点"的面板。`GiftPopupView` 的头注释写着
   * 「必须在编辑器里补 `giftPopup` 节点」，在这个工程里那句话的执行方式就是这里 ——
   * 缺了它的症状不是崩溃而是**那一屏永远不出现**（`/gift/popup` 的答案没有地方画），
   * 而启动自检行会如实报 `missing="giftPopup"`（探针把它当判据之后才有人看这一行）。
   *
   * <p>建出来先 `active = false`：显不显示由 `attach` 按服务端的答案决定，
   * 节点在登录成功之前就在场景里（与离线汇总那一层同一条纪律：别等要弹的时候才建）。
   */
  private mountGiftPopup(): void {
    if (this.node.getChildByName('giftPopup') !== null) {
      return
    }
    const size = view.getVisibleSize()
    const node = new Node('giftPopup')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(size.width, size.height))
    node.addComponent(GiftPopupView)
    node.active = false
  }

  /** 本节点上挂了哪些面板，就接哪些。没挂的面板不会被假装接上（根只会少发那份请求的落地）。 */
  private targets(): PanelTargets {
    this.mountGiftPopup()
    const city = this.panel(CityPanelView, 'city')
    const army = this.panel(ArmyPanelView, 'army')
    const hero = this.panel(HeroPanelView, 'hero')
    const bag = this.panel(BagPanelView, 'bag')
    const stage = this.panel(StagePanelView, 'stage')
    const social = this.panel(SocialPanelView, 'social')
    const power = this.panel(PowerPanelView, 'power')
    const search = this.panel(TargetSearchView, 'targets')
    const quest = this.panel(QuestPanelView, 'quest')
    const mail = this.panel(MailPanelView, 'mail')
    const reports = this.panel(BattleReportPanelView, 'reports')
    const world = this.panel(WorldMap, 'world')
    const settings = this.panel(SettingsPanelView, 'settings')
    const shop = this.panel(ShopPanelView, 'shop')
    const avatarFrames = this.panel(AvatarFramePanelView, 'avatarFrames')
    const giftPopup = this.panel(GiftPopupView, 'giftPopup')
    // 这一次装配的账：boot 自检行的 mountedPanels/missingPanels 从这里来。
    // 刻意在这里记而不是在别处再数一遍回调键名 —— 视图找没找到只在这儿知道
    const views = {
      city, army, hero, bag, stage, reports, social, power, search, quest, mail, world, settings,
      shop, avatarFrames, giftPopup,
    }
    this.panelViews = {
      attempted: Object.keys(views).length,
      missing: Object.entries(views).filter(([, view]) => view === null).map(([key]) => key),
    }
    const out: PanelTargets = {
      error: (panel, message) => console.warn(`[${panel}] ${message}`),
    }
    if (giftPopup !== null) {
      out.giftPopup = resp => giftPopup.attach(resp)
      out.payResult = view => giftPopup.renderResult(view)
      giftPopup.onBuy = productId => { void this.root?.buyGift(productId) }
      giftPopup.onClose = () => giftPopup.hide()
    }
    if (settings !== null) {
      settings.onSupport = (row) => this.handleSettingsAction(row.action)
      // 数据在 GameBootstrap 手里（版本响应是它拉的），所以这里直接推一次；
      // 面板没有 pending 通道可走 —— 那套是给 AppRoot 预拉的面板用的
      settings.render(this.appVersion, CLIENT_VERSION, this.privacyPlan, isMuted())
    }
    if (city !== null) {
      out.city = (resp, offsetMs) => city.attach(resp, offsetMs)
      out.cityCollect = resp => city.attachCollect(resp)
      city.onUpgrade = (configId: string, gridX?: number, gridY?: number) => {
        void this.root?.upgradeBuilding(configId, gridX ?? null, gridY ?? null)
      }
      city.onSpeedUp = (buildingId, source) => { void this.root?.speedUpBuilding(buildingId, source) }
      city.onCollect = buildingId => { void this.root?.collect(buildingId) }
    }
    if (army !== null) {
      out.army = (resp, offsetMs, trainMemory) => army.attach(resp, offsetMs, trainMemory)
      army.onTrain = (unitId, count) => { void this.root?.train(unitId, count) }
      army.onTreat = () => { void this.root?.treatWounded() }
      army.onToggleAutoTrain = () => { void this.root?.toggleAutoTrain() }
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
      out.speedupTargetChoice = (options, onPick) => bag.showTargetPicker(options, onPick)
    }
    if (stage !== null) {
      out.stage = resp => stage.attach(resp)
      stage.onChallenge = stageId => { this.root?.challenge(stageId) }
      stage.onSweep = (stageId, count) => { void this.root?.sweep(stageId, count) }
      out.lineupChoice = (options, onPick) => stage.showLineupPicker(options, onPick)
    }
    if (social !== null) {
      out.social = (resp, helps, members, offsetMs) => social.attach(resp, helps, members, offsetMs)
      out.chat = data => social.attachChat(data)
      social.onHelpAll = () => { void this.root?.helpAll() }
      social.onDonate = tier => { void this.root?.donate(tier) }
      social.onChatEnter = () => { void this.root?.openChat() }
      social.onChatChannel = channel => { void this.root?.selectChatChannel(channel) }
      social.onChatOpenPeer = peerId => { void this.root?.openConversation(peerId) }
      social.onChatSend = text => { void this.root?.sendChat(text) }
      // 聊天里点开一条「分享了战报」的消息：与战报列表点开同一路（拉详情 → 回放）
      social.onChatOpenReport = reportId => { void this.root?.openReport(reportId) }
      // 消息行上的动作（举报 / 拉黑）：目标与原因都由编排层决定，场景层只交出发信人与消息 id
      social.onChatAction = (senderId, messageId) => {
        void this.root?.openChatActions(senderId, messageId)
      }
      out.chatActionChoice = (options, onPick) => social.showChatActionPicker(options, onPick)
      social.onChatManageBlocks = () => { void this.root?.manageBlocks() }
      social.onRowAction = (kind, id, from) => {
        switch (kind) {
          case 'help':
            void this.root?.help(id)
            return
          case 'kick':
            void this.root?.kick(id, from)
            return
          case 'event':
            // 事件行当前只完成已读；坐标跳转还需要事件视图把数值坐标交给世界地图。
            void this.root?.ackEvents([id])
            return
          case 'none':
          case 'helpAll':
          case 'donate':
          // 聊天会话行由面板自己分流到 onChatOpenPeer（它带的是会话对象，不是社交动作）
          case 'chatPeer':
            return
        }
      }
    }
    // 红点树是共享实例：导航和面板各自只持引用，避免两处状态在多次刷新后漂移。
    out.reddot = (tree) => {
      this.nav?.attachReddot(tree)
      social?.attachReddot(tree)
    }
    if (power !== null) {
      out.power = resp => power.render(resp)
      // 榜单与明细共用「战力」这一页（B23 裁决④）：页签与翻页的意图交给编排层，
      // 面板自己不发请求也不判断能不能翻（能翻与否由服务端的 hasMore 决定）
      out.rank = view => power.renderRank(view)
      power.onRankTab = key => { void this.root?.openRankTab(key) }
      power.onRankPage = delta => {
        void (delta < 0 ? this.root?.rankPrevPage() : this.root?.rankNextPage())
      }
    }
    if (search !== null) {
      out.targets = resp => search.attach(resp)
      search.onSearchRequested = radius => { void this.root?.searchTargets(radius) }
      // 点一行就是把"打他"这个意图交出去：编成由编排层准备，这里不拼任何请求
      search.onTargetSelected = targetId => this.root?.beginMarchCompose(targetId)
    }
    // 出征编成弹层（B25-S1）：挂在最上层，编排层给什么画什么；它是弹层不是面板，所以不走 panel()
    // 「自上次登录以来」那一屏（B25-S3）：挂在导航之后 ⇒ 同层兄弟里它排在更后，遮罩压得住面板与导航条
    this.offlineReport = new OfflineReportOverlay(this.node)
    this.offlineReport.onJump = jump => this.root?.offlineReportJump(jump)
    this.marchCompose = new MarchComposeOverlay(this.node)
    this.marchCompose.onPick = (unitId, count) => this.root?.pickMarchUnit(unitId, count)
    this.marchCompose.onConfirm = () => { void this.root?.confirmMarch() }
    this.marchCompose.onCancel = () => this.root?.cancelMarchCompose()
    out.marchCompose = view => this.marchCompose?.render(view)
    out.offlineReport = view => this.offlineReport?.render(view)
    // 点汇总里的一条：跳页面这件事只有场景层知道怎么做（导航条在它手里）
    out.offlineJump = key => this.nav?.show(key)
    if (this.guide !== null) {
      // 步骤、文案、遮罩、能不能跳，一个字段都不在客户端（验收 1）
      out.guide = resp => this.guide?.attach(resp)
    }
    if (quest !== null) {
      out.quest = resp => quest.attach(resp)
      out.activity = (resp, serverNowMs) => quest.attachActivity(resp, serverNowMs)
      out.activityClaimed = resp => quest.showReceipt(claimReceiptText(resp))
      quest.onClaim = (questId, heroChoice) => { void this.root?.claimQuest(questId, heroChoice) }
      quest.onClaimActivity = activityId => { void this.root?.claimActivity(activityId) }
    }
    if (reports !== null) {
      out.reports = (resp, serverNowMs) => reports.attach(resp, serverNowMs)
      // 回放参数由服务端随战报下发（表里那两个数），这里只装配不写死。
      // 表里写了不支持的倍速时 playbackOptionsOf 会抛 —— 那是一条配置故障，
      // 让它响到崩溃上报里去，而不是让玩家点开一场看到一屏不动的画
      out.reportReplay = resp => reports.showReplay(resp.reportId, resp.result,
        playbackOptionsOf(resp.playback))
      reports.onReplayRequested = reportId => { void this.root?.openReport(reportId) }
      reports.onShareRequested = reportId => { this.root?.requestShare(reportId) }
      out.shareChannelChoice = (options, onPick) => reports.showSharePicker(options, onPick)
      out.reportShared = (text, warning) => reports.showShareOutcome(text, warning)
    }
    if (shop !== null) {
      out.shop = view => shop.attach(view)
      shop.onTab = currency => { void this.root?.openShopTab(currency) }
      shop.onBuy = rowId => { void this.root?.buyShopRow(rowId) }
    }
    if (avatarFrames !== null) {
      out.avatarFrames = view => avatarFrames.attach(view)
      // 卸下也走同一条：null 就是"卸下"，与协议的 WearFrameReq 同形
      avatarFrames.onWear = frameId => { void this.root?.wearFrame(frameId) }
    }
    if (mail !== null) {
      out.mail = (resp, serverNowMs) => mail.attach(resp, serverNowMs)
      out.mailClaimed = resp => mail.showOutcome(resp)
      mail.onClaimAll = () => { void this.root?.claimAllMail() }
      mail.onReadRequested = mailId => { void this.root?.readMail(mailId) }
    }
    if (world !== null) {
      // 「回城」按钮此前是个空函数，就是因为没人把家坐标交给它（WorldMap 里那条 TODO）
      out.home = (x, y) => world.focusHome(x, y)
      // 放大到城市档只切内城面板；不要再加载不存在的 MainCity.scene。
      world.onEnterCity = () => this.nav?.show('city')
      // 空态的「再次出征」：够不够、发不发由编排层判（表现层不碰这些）
      world.onRepeatLastMarch = () => { void this.root?.repeatLastMarch() }
    }
    return out
  }
}
