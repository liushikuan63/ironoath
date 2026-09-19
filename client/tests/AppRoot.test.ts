/**
 * 职责：组合根 AppRoot 的单测 —— 按钮的意图有没有变成请求、响应有没有回到面板、
 *      失败时有没有偷刷新。
 * 依赖：真实的 NetModule / GameApi / GameSession / Store / TimeSync，只有传输是假的。
 *
 * <p>这个文件是「面板按钮通不通」这件事唯一的可验证证据：场景层（`scene/`）在本仓库里
 * 只能过类型桩，跑不起来。所以根被刻意做成不 import 'cc'，才能在这里被真实驱动。
 *
 * <p>假传输用「路径 → 响应」的路由表而不是脚本队列：根一次启动就要发九个请求，
 * 按顺序排的脚本会让每条断言都依赖前面几条的执行次数（本项目踩过这种假绿）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { NetModule } from '../assets/scripts/net/NetModule'
import type { NetConfig, NetDeps } from '../assets/scripts/net/NetModule'
import type { HttpResponse, HttpTransport, SocketCallbacks, SocketTransport } from '../assets/scripts/net/NetTransport'
import { Prng } from '../assets/scripts/core/Prng'
import { TimeSync } from '../assets/scripts/core/TimeSync'
import { Store } from '../assets/scripts/game/store/Store'
import { GameApi } from '../assets/scripts/game/session/GameApi'
import type { GameApiDeps } from '../assets/scripts/game/session/GameApi'
import { GameSession } from '../assets/scripts/game/session/GameSession'
import { AppRoot } from '../assets/scripts/game/session/AppRoot'
import type { PanelTargets } from '../assets/scripts/game/session/AppRoot'
import type {
  ChatActionChoice, LineupChoice, ShareChannelChoice, SpeedupChoice,
} from '../assets/scripts/game/session/Choices'
import type { ChatPanelData } from '../assets/scripts/game/social/ChatPanel'
import type { RankBoardView } from '../assets/scripts/game/power/RankBoard'
import type { MarchComposeView } from '../assets/scripts/game/session/AppRoot'
import type { ClientReddotTree } from '../assets/scripts/game/reddot/ReddotTree'
import { resetWorld } from '../assets/scripts/game/world/WorldContext'

const SERVER_NOW = 1_788_000_000_000

/** 出征编成那三条用例的军队：两个兵种，其中一个未解锁（顺带钉住编排层也不放它出去）。 */
const ARMY_FOR_MARCH = {
  units: [
    { unitId: 'unit_infantry_t1', name: '重步', type: 'INFANTRY', tier: 1, count: 500, wounded: 0,
      training: 0, finishAt: null, remainingSeconds: null, unlocked: true, unlockHint: null,
      trainTimeSec: 10 },
    { unitId: 'unit_cavalry_t3', name: '铁骑', type: 'CAVALRY', tier: 3, count: 80, wounded: 0,
      training: 0, finishAt: null, remainingSeconds: null, unlocked: false,
      unlockHint: '需要马厩 10 级，当前 6 级', trainTimeSec: 30 },
  ],
  troopCap: 1000, troopsInUse: 0, trainingInUse: 0, queueSlots: 0, queueSlotsMax: 2,
  hospital: { capacity: 0, used: 0, treating: false, treatFinishAt: null, treatRemainingSeconds: 0,
    treatSecondsPerWounded: 0, treatCostRatio: 0 },
  serverNow: SERVER_NOW,
}

/** 每个端点的响应。只给「代码真的会读到的字段」，其余留空对象。 */
const ROUTES: Record<string, unknown> = {
  '/player/init': {
    playerId: 'P1', profile: { nickName: '君' }, cityLevel: 1, resources: {},
    power: { displayPower: 10, matchPower: 10, peakPower: 10 }, protectUntil: null,
    // 微信登录后服务端签发的会话票据；之后每条请求以 `Authorization: Bearer <票据>` 带上
    authToken: 'session-token-1', serverNow: SERVER_NOW, isNewPlayer: true,
  },
  '/time/sync': { sync: { offset: 0, syncAt: SERVER_NOW } },
  // 默认不弹：绝大多数用例不该被一个弹窗干扰；要弹的用例自己用 overrides 覆盖
  '/gift/popup': {
    popup: false, giftId: null, productId: null, offerExpireAt: null,
    cooldownSec: 0, serverNow: SERVER_NOW,
  },
  '/city/list': {
    buildings: [], buildOptions: [],
    queues: { used: 0, available: 2, max: 3 }, resources: {}, serverNow: SERVER_NOW,
  },
  '/army/list': {
    units: [], troopCap: 0, troopsInUse: 0, trainingInUse: 0,
    queueSlots: 0, queueSlotsMax: 0, hospital: {}, serverNow: SERVER_NOW,
  },
  '/hero/list': { heroes: [], lineups: [] },
  '/bag/list': { items: [] },
  '/resource/detail': { entries: [], serverNow: SERVER_NOW },
  '/stage/list': { chapters: [], serverNow: SERVER_NOW },
  '/social/summary': {
    squad: null,
    // 默认按「已在盟」给：摘要里的 alliance 是否为 null，是客户端决定「要不要拉成员」的
    // 唯一依据（未入盟时那一问必然回 10010）。留成 null 会让 diff 通道那几条用例
    // 悄悄走到"根本没发请求"的分支，而请求断言照旧通过。
    alliance: { id: 'A1', name: '铁盟', tag: '铁', memberCount: 2 },
    nationId: null, pendingInvites: 0, pendingHelps: 0,
    helpRemainingToday: 0, events: [], serverNow: SERVER_NOW,
  },
  '/social/reddot': {
    nodes: [
      { key: 'social', lit: true, children: [
        { key: 'social/help', lit: true, children: [] },
        { key: 'social/invite', lit: false, children: [] },
        { key: 'social/events', lit: false, children: [] },
      ] },
      { key: 'city', lit: false, children: [
        { key: 'city/building', lit: false, children: [] },
      ] },
    ],
    leafCount: 4, serverNow: SERVER_NOW,
  },
  '/rank/list': {
    type: 'POWER',
    entries: [
      { rank: 1, id: 'P9', name: '老王', value: 12345, tag: null },
      { rank: 2, id: 'P1', name: '君', value: 900, tag: null },
    ],
    myRank: 2, myValue: 900, page: 1, pageSize: 20, hasMore: false,
  },
  '/player/power': {
    power: { displayPower: 10, matchPower: 10, peakPower: 10 }, serverNow: SERVER_NOW, lines: [],
  },
  '/world/marches': {
    marches: [], home: { x: 48, y: 48 }, maxConcurrent: 3,
    worldSize: 512, chunkSize: 32, maxChunks: 9,
    peaceUntil: null, nextExileAt: null, serverNow: SERVER_NOW,
  },
  '/world/exile': {
    coord: { x: 300, y: 220 }, peaceUntil: SERVER_NOW + 3_600_000,
    nextExileAt: SERVER_NOW + 7_200_000, seed: 7, serverNow: SERVER_NOW,
  },
  '/city/upgrade': { accepted: true, serverNow: SERVER_NOW },
  '/city/speedUp': { remainingSeconds: 0, serverNow: SERVER_NOW },
  '/city/collect': { collected: {}, entries: [], serverNow: SERVER_NOW },
  '/army/train': { started: 1, serverNow: SERVER_NOW },
  '/army/treat': { treated: {}, serverNow: SERVER_NOW },
  '/item/use': { used: 1, remaining: 0, effects: [], serverNow: SERVER_NOW },
  '/stage/sweep': { results: {}, rewards: [], serverNow: SERVER_NOW },
  '/stage/challenge': {
    reportId: 'r1', stars: { cleared: true, noLoss: true, withinRounds: true, total: 3 },
    starsEarned: 3, newBest: true, rewards: [], losses: [], staminaCost: 6,
    staminaCharged: 6, progress: { stageId: 's1', stars: 3, bestRounds: 2, clearedAt: 1, sweepCount: 0 },
    serverNow: SERVER_NOW,
  },
  '/social/help': { helped: 1, skipped: 0, helpRemainingToday: 5, pendingHelps: 0, speedupGranted: 0, serverNow: SERVER_NOW },
  '/social/helpAll': { helped: 2, skipped: 0, helpRemainingToday: 4, pendingHelps: 0, speedupGranted: 0, serverNow: SERVER_NOW },
  '/social/ackEvents': { squad: null, alliance: null, nationId: null, pendingInvites: 0, pendingHelps: 0, helpRemainingToday: 0, events: [], serverNow: SERVER_NOW },
  '/squad/kick': { squad: null, alliance: null, nationId: null, pendingInvites: 0, pendingHelps: 0, helpRemainingToday: 0, events: [], serverNow: SERVER_NOW },
  '/alliance/kick': { squad: null, alliance: null, nationId: null, pendingInvites: 0, pendingHelps: 0, helpRemainingToday: 0, events: [], serverNow: SERVER_NOW },
  '/alliance/donate': { tier: 1, donated: {}, contribution: 0, fund: 0, serverNow: SERVER_NOW },
  '/world/searchTargets': { targets: [], selfMatchPower: 10, lowerBound: 5, upperBound: 20, serverNow: SERVER_NOW },
  '/world/march': {
    march: { marchId: 'm-1', from: { x: 48, y: 48 }, to: { x: 60, y: 60 }, status: 'MARCHING',
      targetType: 'CITY', targetId: 'P9', rallyId: null, action: 'ATTACK', startAt: SERVER_NOW,
      arriveAt: SERVER_NOW + 60000, returnStartAt: null, returnArriveAt: null, units: [], heroes: [],
      load: 0, loadCap: 0, teamSpeed: 0, position: { x: 48, y: 48 }, progressFixed: 0,
      gatherFinishAt: null, serverNow: SERVER_NOW },
    distance: 24, durationSec: 60, serverNow: SERVER_NOW,
  },
  '/activity/list': { activities: [], serverNow: SERVER_NOW, claimableCount: 0 },
  '/activity/claim': { claimed: true, state: 'RUNNING', rewards: [], serverNow: SERVER_NOW },
  '/quest/list': {
    quests: [
      { questId: 'quest_main_01', name: '筑起第一堵墙', type: 'MAIN',
        goalType: 'UPGRADE_BUILDING', goalTarget: 'main_city', goalValue: 2,
        current: 2, complete: true, claimed: false, claimable: true, locked: false,
        preQuestId: null, heroChoices: [
          { heroId: 'hero_sr_01', name: '卫无咎' },
          { heroId: 'hero_sr_02', name: '沈砚秋' },
          { heroId: 'hero_sr_03', name: '崔明烛' },
        ] },
    ],
    claimableCount: 1, serverNow: SERVER_NOW,
  },
  '/battle/reports': {
    reports: [
      { reportId: 'r-1', battleType: 'PVE', opponentId: 'mob_1', opponentName: '叛军斥候',
        winner: 'ATTACKER', won: true, totalRounds: 6, attackerLoss: 120, defenderLoss: 400,
        createdAt: SERVER_NOW - 60_000, expiresAt: SERVER_NOW + 7 * 86_400_000 },
    ],
    serverNow: SERVER_NOW,
  },
  '/social/report': { reportId: 'report_1', serverNow: SERVER_NOW },
  '/social/follows': { friends: [] },
  '/social/follow': {
    friends: [{ playerId: 'P2', name: '乙', online: false, lastSeenAt: SERVER_NOW - 3600_000 }],
  },
  '/social/unfollow': { friends: [] },
  '/social/blocks': { blockedPlayerIds: [] },
  '/social/block': { blockedPlayerIds: ['P2'] },
  '/social/unblock': { blockedPlayerIds: [] },
  // 分享落进频道是服务端的事，这里只回执「贴到哪了」
  '/battle/share': { reportId: 'r-1', channel: 'ALLIANCE', messageId: 'msg-share-1', serverNow: SERVER_NOW },
  '/battle/report': {
    reportId: 'r-1',
    result: { reportId: 'r-1', battleType: 'PVE', winner: 'ATTACKER', rounds: [],
      attacker: { units: [], power: 100, loss: 120 }, defender: { units: [], power: 90, loss: 400 },
      loot: [], seed: '1', skillTriggers: [] },
    createdAt: SERVER_NOW - 60_000, expiresAt: SERVER_NOW + 7 * 86_400_000,
    serverNow: SERVER_NOW,
    playback: { roundMs: 900, speeds: '1,2' },
  },
  '/mail/list': {
    mails: [
      { mailId: 'm-1', kind: 'OVERFLOW', title: '奖励放不下', text: '金币 ×500',
        rewards: [{ type: 'RESOURCE', id: 'GOLD', count: 500, name: '金币' }],
        claimed: false, read: false, createdAt: SERVER_NOW, expireAt: SERVER_NOW + 30 * 86_400_000,
        sourceRef: 'battle:r-1' },
    ],
    unreadCount: 1, claimedCount: 0,
  },
  '/mail/claimAll': {
    claimed: 1,
    rewards: [{ type: 'RESOURCE', id: 'GOLD', count: 500, name: '金币' }],
    failed: [],
  },
  '/mail/read': { mailId: 'm-1', unreadCount: 0 },
  '/guide/script': {
    steps: [
      { id: 'g1', name: '升主城', stepIndex: 1, trigger: 'PANEL_OPEN', panelKey: 'city',
        highlightPath: 'city', maskArea: 'full', text: '把主城升两级', skippable: false },
      { id: 'g2', name: '领奖', stepIndex: 2, trigger: 'PANEL_OPEN', panelKey: 'quest',
        highlightPath: 'quest', maskArea: 'full', text: '领那名赠送的武将', skippable: false },
    ],
    version: '7', nextStepIndex: 1, applies: true, serverNow: SERVER_NOW,
  },
  '/guide/progress': { advanced: true, finished: false, nextStepIndex: 2 },
  '/quest/claim': {
    questId: 'quest_main_01',
    rewards: [{ type: 'HERO', id: 'hero_sr_01', count: 1, name: '卫无咎' }],
    claimableCount: 0, serverNow: SERVER_NOW,
  },
  '/social/helpRequests': {
    requests: [
      { requestId: 'h1', fromPlayerId: 'P2', fromPlayerName: '乙', kind: 'BUILDING',
        targetDesc: '伐木场 Lv7→8', remainingSeconds: 600, helpedCount: 0, alreadyHelped: false },
      { requestId: 'h2', fromPlayerId: 'P3', fromPlayerName: '丙', kind: 'TRAINING',
        targetDesc: '训练步兵 100', remainingSeconds: 300, helpedCount: 1, alreadyHelped: true },
    ],
    pendingHelps: 1, helpRemainingToday: 5, serverNow: SERVER_NOW,
  },
  // 聊天（B22）：默认给一段空历史 —— 有内容的用例自己用 overrides 覆盖
  '/chat/list': { messages: [], hasMore: false, serverNow: SERVER_NOW },
  '/chat/send': {
    message: {
      messageId: 'msg-1', channel: 'WORLD', senderId: 'P1', senderName: '君', content: '大家好',
      sentAt: SERVER_NOW,
    },
    serverNow: SERVER_NOW,
  },
  '/alliance/sync': {
    version: 7, unchanged: false, removedMemberIds: [], fund: 100, level: 1, memberCount: 2,
    announcement: '', serverNow: SERVER_NOW,
    changedMembers: [
      { id: 'M1', name: '甲', power: 10, role: 'LEADER', contribution: 5, lastActiveAt: 1, squadId: null },
      { id: 'M2', name: '乙', power: 20, role: 'MEMBER', contribution: 3, lastActiveAt: 2, squadId: null },
    ],
  },
}

function syncResponse(overrides: Record<string, unknown>) {
  return Object.assign({}, ROUTES['/alliance/sync'], overrides)
}

function reddotResponse(socialLit: boolean, helpLit: boolean) {
  return {
    nodes: [
      { key: 'social', lit: socialLit, children: [
        { key: 'social/help', lit: helpLit, children: [] },
        { key: 'social/invite', lit: false, children: [] },
        { key: 'social/events', lit: false, children: [] },
      ] },
      { key: 'city', lit: false, children: [
        { key: 'city/building', lit: false, children: [] },
      ] },
    ],
    leafCount: 4,
    serverNow: SERVER_NOW,
  }
}

interface Call {
  readonly method: 'GET' | 'POST'
  readonly path: string
  /** 查询串。分页/类型这类参数只在 URL 上（GET 没有 body），不记下来就没法断言。 */
  readonly query: URLSearchParams
  readonly body: Record<string, unknown>
  /** 请求头。登录票据（X-Auth-Token）是 B15 之后每条请求都要带的身份凭证。 */
  readonly headers: Readonly<Record<string, string>>
}

/**
 * 长连接的假对象：只记「被谁建出来、连上之后发了什么」。
 *
 * <p>刻意不在 `connect` 里立刻回调 `onOpen`：真实握手的完成是在下一个事件循环里，
 * 假的一开口就"已连接"会让那种**把 bind 排在 connect 之后、以为顺序到了就发得出去**
 * 的实现写得过测试、跑在真机上却一条推送都收不到。要 open 必须由用例自己按一下。
 */
class RecordingSocket implements SocketTransport {
  readonly sent: string[] = []
  open = false
  private callbacks: SocketCallbacks | null = null

  constructor(readonly url: string) {}

  connect(callbacks: SocketCallbacks): void {
    this.callbacks = callbacks
  }

  send(text: string): boolean {
    if (!this.open) {
      return false
    }
    this.sent.push(text)
    return true
  }

  get isOpen(): boolean {
    return this.open
  }

  close(): void {
    this.open = false
  }

  /** 测试驱动：模拟服务端完成握手。 */
  simulateOpen(): void {
    this.open = true
    this.callbacks?.onOpen()
  }

  /** 测试驱动：模拟服务端推来一帧（业务推送就是从这里变成 `serverPush` 的）。 */
  simulateMessage(payload: unknown): void {
    this.callbacks?.onMessage(JSON.stringify(payload))
  }
}

class RoutingHttp implements HttpTransport {
  readonly calls: Call[] = []
  /** 下一条写请求返回这个业务错误。用来验「失败不许刷新」。 */
  bizFailNext: { code: number, msg: string, detail: string | null } | null = null
  /** 这些路径一律回业务错误（用来验"某个面板拉不到"时的降级，而不是整块白屏）。 */
  failPaths = new Set<string>()
  /**
   * 每个用例自己的响应覆盖表。刻意不去改模块级的 ROUTES：那会把上一个用例留下的
   * 响应带给下一个用例，症状是"单独跑是绿的、整文件跑就红"，而这类失败最难查。
   */
  overrides = new Map<string, unknown>()
  /** 被扣住的路径 → 放行前一直悬着的闸门。见 {@link hold}。 */
  private readonly held = new Map<string, Promise<void>>()

  /**
   * 扣住某个路径的响应，直到调用返回的 release 被调用。
   *
   * <p>用途是把「请求是不是并发发出的」变成可判定的事实：串行实现下，第一个面板没回来时
   * 后面的请求根本不会出现，所以「第一个还被扣着，其余十个已经在路上」这一条只可能并发成立。
   * **只扣第一次匹配**，否则 start 之后的重拉会一起被扣住、用例看起来像卡死。
   */
  hold(path: string): () => void {
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    this.held.set(path, gate)
    return () => {
      this.held.delete(path)
      release()
    }
  }

  post(url: string, bodyText: string,
       headers: Readonly<Record<string, string>> = {}): Promise<HttpResponse> {
    return this.reply('POST', url, bodyText, headers)
  }

  get(url: string, headers: Readonly<Record<string, string>> = {}): Promise<HttpResponse> {
    return this.reply('GET', url, '', headers)
  }

  private async reply(method: 'GET' | 'POST', url: string, bodyText: string,
                      headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    const parsed = new URL(url)
    const path = parsed.pathname
    this.calls.push({
      method,
      path,
      query: parsed.searchParams,
      body: bodyText === '' ? {} : JSON.parse(bodyText) as Record<string, unknown>,
      headers,
    })
    const gate = this.held.get(path)
    if (gate !== undefined) {
      this.held.delete(path)
      await gate
    }
    if (this.failPaths.has(path)) {
      return { status: 200, bodyText: JSON.stringify({ code: 9999, msg: '服务繁忙', detail: null, data: null, serverNow: SERVER_NOW }) }
    }
    if (!this.bizFailNext) {
      const data = this.overrides.has(path) ? this.overrides.get(path) : ROUTES[path]
      if (data === undefined) {
        throw new Error(`路由表里没有 ${path}：请补上，别让测试拿着空响应假装通过`)
      }
      return { status: 200, bodyText: JSON.stringify({ code: 0, msg: '成功', data, serverNow: SERVER_NOW }) }
    }
    const fail = this.bizFailNext
    this.bizFailNext = null
    return {
      status: 200,
      bodyText: JSON.stringify({ code: fail.code, msg: fail.msg, detail: fail.detail, data: null, serverNow: SERVER_NOW }),
    }
  }

  countOf(path: string): number {
    return this.calls.filter(c => c.path === path).length
  }
}

interface Harness {
  /** 最近一次落地给社交面板的成员 id 列表（断言 diff 合并结果用）。 */
  readonly lastSocialMembers: string[]
  /** 最近一次落地给社交面板的互助请求 id 列表。 */
  readonly lastSocialHelps: string[]
  /** 最近一次落地给聊天页签的数据（B22）—— 频道、会话、消息、提示行都看它。 */
  readonly lastChat: ChatPanelData | null
  /** 最近一次落地给榜单面板的整块视图。 */
  readonly lastRank: RankBoardView | null
  /** 最近一次推给出征编成面板的整块视图。 */
  readonly lastCompose: MarchComposeView | null
  /** 最近一次落地给活动页的服务端时刻（断言"剩余时间来自服务端"用）。 */
  readonly lastActivityNow: number
  /** 最近一次落地给活动页的行数。 */
  readonly lastActivityRows: number
  /** 最近一次下发到场景层的那棵红点树。 */
  readonly reddotTree: ClientReddotTree | null

  readonly root: AppRoot
  readonly http: RoutingHttp
  /** 长连接实例。用例要靠它按下"服务端完成了握手"（见 {@link RecordingSocket}）。 */
  readonly sockets: RecordingSocket[]
  readonly store: Store
  readonly errors: Array<[string, string]>
  readonly attached: string[]
  readonly events: Array<{ name: string, params: Record<string, string> }>
  readonly speedupOptions: readonly SpeedupChoice[]
  readonly lineupOptions: readonly LineupChoice[]
  /** 最近一次弹出的分享频道候选（没点分享时为空） */
  readonly shareChannelOptions: readonly ShareChannelChoice[]
  /** 最近一次弹出的聊天动作候选（举报原因 / 拉黑） */
  readonly chatActionOptions: readonly ChatActionChoice[]
  /** 最近一次分享结果（文本 + 是否告警色） */
  readonly lastShareOutcome: readonly [string, boolean]
  pickSpeedup(targetId: string): void
  pickLineup(index: number): void
  pickChatAction(id: string): void
  pickShareChannel(channel: string): void
}

function harness(options: { transportFails?: boolean } = {}): Harness {
  resetWorld()
  const http = new RoutingHttp()
  const sockets: RecordingSocket[] = []
  const transport: HttpTransport = options.transportFails
    ? {
      post: async (): Promise<HttpResponse> => {
        throw new Error('fetch failed')
      },
      get: async (): Promise<HttpResponse> => {
        throw new Error('fetch failed')
      },
    }
    : http
  const store = new Store()
  const timeSync = new TimeSync({ alphaFixed: 3000, jitterFactorFixed: 30000, initialBestRttMs: 200 })
  const clock = { now: 1_000 }
  let seq = 0
  const config: NetConfig = {
    baseUrl: 'https://game.test', wsUrl: 'wss://game.test/ws', maxRetryAttempts: 2,
    retryBaseDelayMs: 10, retryMaxDelayMs: 40, offlineQueueMax: 5, requestTimeoutMs: 1000,
  }
  const deps: NetDeps = {
    http: transport,
    socketFactory: (url: string) => {
      const socket = new RecordingSocket(url)
      sockets.push(socket)
      return socket
    },
    now: () => clock.now,
    delay: async (ms: number) => {
      clock.now += ms
    },
    rng: Prng.of(1),
    newRequestId: () => `req-${++seq}`,
    newTraceId: () => `trace-${seq}`,
  }
  const net = new NetModule(config, deps)
  const session = new GameSession({ net, store, timeSync, now: () => clock.now, newRequestId: deps.newRequestId })
  const apiDeps: GameApiDeps = {
    net, store, timeSync, now: () => clock.now, newRequestId: deps.newRequestId,
  }
  const api = new GameApi(apiDeps)
  const errors: Array<[string, string]> = []
  const attached: string[] = []
  let socialMembers: string[] = []
  let socialHelps: string[] = []
  let lastChat: ChatPanelData | null = null
  let lastRank: RankBoardView | null = null
  let lastCompose: MarchComposeView | null = null
  let reddotTree: ClientReddotTree | null = null
  let activityNow = -1
  let activityRows = -1
  let speedupOptions: SpeedupChoice[] = []
  let lineupOptions: LineupChoice[] = []
  let shareChannelOptions: ShareChannelChoice[] = []
  let chatActionOptions: ChatActionChoice[] = []
  let chatActionPick: ((choice: ChatActionChoice) => void) | null = null
  let lastShareOutcome: [string, boolean] = ['', false]
  let shareChannelPick: ((choice: ShareChannelChoice) => void) | null = null
  let speedupPick: ((targetId: string) => void) | null = null
  let lineupPick: ((choice: LineupChoice) => void) | null = null

  const targets: PanelTargets = {
    city: () => attached.push('city'),
    cityCollect: () => attached.push('cityCollect'),
    army: () => attached.push('army'),
    hero: () => attached.push('hero'),
    resources: () => attached.push('resources'),
    bag: () => attached.push('bag'),
    stage: () => attached.push('stage'),
    social: (_resp, helps, members) => {
      attached.push('social')
      socialMembers = members.map(m => m.id)
      socialHelps = helps.map(h => h.requestId)
    },
    chat: (data) => {
      attached.push('chat')
      lastChat = data
    },
    reddot: (tree) => {
      attached.push('reddot')
      reddotTree = tree
    },
    power: () => attached.push('power'),
    rank: (view) => {
      attached.push('rank')
      lastRank = view
    },
    targets: () => attached.push('targets'),
    marchCompose: (view) => {
      attached.push('marchCompose')
      lastCompose = view
    },
    quest: () => attached.push('quest'),
    mail: () => attached.push('mail'),
    activity: (resp, serverNowMs) => {
      attached.push('activity')
      activityNow = serverNowMs
      activityRows = resp.activities.length
    },
    activityClaimed: () => attached.push('activityClaimed'),
    reports: () => attached.push('reports'),
    reportReplay: () => attached.push('reportReplay'),
    mailClaimed: () => attached.push('mailClaimed'),
    home: () => attached.push('home'),
    guide: () => attached.push('guide'),
    speedupTargetChoice: (options, onPick) => {
      speedupOptions = [...options]
      speedupPick = onPick
    },
    lineupChoice: (options, onPick) => {
      lineupOptions = [...options]
      lineupPick = onPick
    },
    shareChannelChoice: (options, onPick) => {
      shareChannelOptions = [...options]
      shareChannelPick = onPick
    },
    chatActionChoice: (options, onPick) => {
      chatActionOptions = [...options]
      chatActionPick = onPick
    },
    reportShared: (text, warning) => {
      lastShareOutcome = [text, warning]
    },
    error: (panel, message) => errors.push([panel, message]),
  }
  const events: Array<{ name: string, params: Record<string, string> }> = []
  const root = new AppRoot({
    api, session, store, timeSync, targets,
    tracker: { track: (name, params) => events.push({ name, params: params ?? {} }) },
  })
  return {
    get lastSocialMembers() {
      return socialMembers
    },
    get lastChat() {
      return lastChat
    },
    get lastRank() {
      return lastRank
    },
    get lastCompose() {
      return lastCompose
    },
    get lastActivityNow() {
      return activityNow
    },
    get lastActivityRows() {
      return activityRows
    },
    get lastSocialHelps() {
      return socialHelps
    },
    get reddotTree() {
      return reddotTree
    },
    get speedupOptions() {
      return speedupOptions
    },
    get lineupOptions() {
      return lineupOptions
    },
    get shareChannelOptions() {
      return shareChannelOptions
    },
    get chatActionOptions() {
      return chatActionOptions
    },
    get lastShareOutcome() {
      return lastShareOutcome
    },
    pickSpeedup(targetId) {
      speedupPick?.(targetId)
    },
    pickLineup(index) {
      const choice = lineupOptions[index]
      if (choice !== undefined) {
        lineupPick?.(choice)
      }
    },
    pickChatAction(id) {
      const choice = chatActionOptions.find(option => option.id === id)
      if (choice !== undefined) {
        chatActionPick?.(choice)
      }
    },
    pickShareChannel(channel) {
      const choice = shareChannelOptions.find(option => option.channel === channel)
      if (choice !== undefined) {
        shareChannelPick?.(choice)
      }
    },

    root, http, sockets, store, errors, attached, events,
  }
}

// 登录 1 条 + 首屏面板请求。社交面板是三条（摘要 + 成员 diff + 互助列表），
// 2026-09-12 加任务面板（B12 §1 + 收口清单 #98 的三选一送将）后再 +1，
// 阶段 3.4 再把红点树作为独立首屏拉取项 +1 ——
// 这个数被断言写死正是为了让每一次新增都要被看见并解释
const PANEL_PULLS = 13

test('start：先登录，再把十个面板各拉一次，并把家坐标交出去', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)

  assert.equal(h.http.calls[0]?.path, '/player/init', '登录必须是第一个请求')
  // 首屏预拉改成并发之后，**落地先后不再是代码的性质**（见 #127：串行那一串实测吃掉 1.8 秒，
  // 把首屏可交互推到 3.9 秒越过预算）。所以这里按集合比：新增或删掉一次拉取仍然会被看见，
  // 但谁先谁后不再断言 —— 那个顺序没有任何调用方在读，钉住它只会让并发化变成一次假红。
  assert.deepEqual(Array.from(h.attached).sort(),
    ['army', 'bag', 'chat', 'city', 'hero', 'home', 'power', 'quest', 'rank', 'reddot',
      'resources', 'social', 'stage'])
  assert.equal(h.errors.length, 0)
  assert.equal(h.root.playerId, 'P1')
})

test('start 之后长连接真的被打开：只调 bindPlayer 而不 connect，推送通道一条消息都收不到', async () => {
  // 这条用例盯的是一个"全绿但功能是死的"缺陷：GameSession 登录成功后只做了 bindPlayer，
  // 而 bindPlayer 在没有连接时静默返回 false —— 于是服务端到客户端的推送整条链从未成立，
  // 而所有 HTTP 用例照旧全过（客户端各面板都是拉出来的，看不出推送断了）。
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)

  assert.equal(h.sockets.length, 1, '登录成功后应当开且只开一条长连接')
  const socket = h.sockets[0]
  assert.ok(socket !== undefined)
  assert.ok(socket.url.includes('playerId=P1'), `握手 URL 要带 playerId，实际 ${socket.url}`)
  assert.ok(socket.url.includes('token=session-token-1'), `握手 URL 要带票据，实际 ${socket.url}`)
})

test('握手完成后立刻申报身份：bind 不能由调用方按顺序排在 connect 之后', async () => {
  // 握手的 onOpen 是异步到的（默认 harness 里的假连接在 start 返回时还没 open）。
  // 若谁在 connect 之后顺手 bind 一下就算完，真机上那一发永远发不出去。
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)
  const socket = h.sockets[0]
  assert.ok(socket !== undefined)
  assert.deepEqual(socket.sent, [], '还没握手成功时不该有任何已发出的帧')

  socket.simulateOpen()
  assert.deepEqual(JSON.parse(socket.sent[0] ?? '{}'),
    { type: 'bind', playerId: 'P1', token: 'session-token-1' })
})

test('回到前台：重校时并再问一次弹窗（挂后台期间触发的那一次不能丢）', async () => {
  // 为什么必须"再问一次"：弹窗的时机由服务端的触发与频控决定，而挂后台期间玩家看不到任何东西 ——
  // 回前台不问，那一次触发就白过了（而它可能正是"卡关补给"这种有时效的机会）
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)
  const syncsBefore = h.http.countOf('/time/sync')
  const popupsBefore = h.http.countOf('/gift/popup')

  await h.root.afterForeground()

  assert.equal(h.http.countOf('/time/sync'), syncsBefore + 1,
    '回前台要重校时：休眠期间本地时钟可能漂移，而产出倒计时全靠它')
  assert.equal(h.http.countOf('/gift/popup'), popupsBefore + 1,
    '回前台要再问一次礼包弹窗，否则挂后台错过的那次触发永远看不到')
})

test('首屏预拉是并发发出的：第一个面板还扣着时，其余十个已经在路上', async () => {
  // 「首屏可交互」是这批面板全部到齐的时刻，所以它们必须是并发而不是逐个 await 的和。
  // 这条断言只可能对并发实现成立：串行时第一个面板没回来，后面的请求根本不会发出。
  const h = harness()
  const release = h.http.hold('/city/list')

  const started = h.root.start('dev-1', '君')
  for (let i = 0; i < 50 && h.http.calls.length < 12; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 1))
  }
  const paths = h.http.calls.map(c => c.path)
  assert.equal(paths.includes('/city/list'), true, '第一个面板得先发出去（并且被扣着）')
  for (const path of ['/army/list', '/hero/list', '/bag/list', '/resource/detail', '/stage/list',
    '/social/summary', '/player/power', '/world/marches', '/quest/list', '/social/reddot']) {
    assert.equal(paths.includes(path), true,
      `第一个面板还卡着时 ${path} 就该已经发出：串行会把这些请求排成一串，`
      + '首屏可交互时间就是它们的和')
  }

  release()
  assert.equal(await started, true, '放行之后启动要正常收尾')
})

test('登录失败就停手：一个面板请求都不发（否则一进游戏被十个 400 糊脸）', async () => {
  const h = harness()
  h.http.bizFailNext = { code: 2002, msg: '设备标识非法', detail: null }

  assert.equal(await h.root.start('bad-device', '君'), false)
  assert.deepEqual(h.http.calls.map(c => c.path), ['/player/init'],
    '登录之外一条都不发')
  assert.equal(h.attached.length, 0)
  assert.deepEqual(h.errors[0], ['session', '设备标识非法'])
})

test('升级成功：请求带 configId 与幂等键，之后城建列表被重新拉一次', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  const before = h.http.countOf('/city/list')

  await h.root.upgradeBuilding('barracks')

  const call = h.http.calls.find(c => c.path === '/city/upgrade')
  assert.notEqual(call, undefined)
  assert.equal(call?.body.configId, 'barracks')
  assert.match(String(call?.body.requestId), /^req-/, '幂等键必须由编排层注入，不给面板漏填的机会')
  assert.equal(h.http.countOf('/city/list'), before + 1, '成功必须重拉列表：客户端不自己改数字')
})

test('首次建造把玩家选中的 gridX/gridY 原样送进 /city/upgrade', async () => {
  const h = harness()
  await h.root.start('dev-1', '测试')
  h.events.length = 0

  await h.root.upgradeBuilding('lumber_camp', 3, 4)

  const call = h.http.calls.find(c => c.path === '/city/upgrade')
  assert.equal(call?.body.gridX, 3)
  assert.equal(call?.body.gridY, 4)
  assert.deepEqual(h.events[0]?.params,
    { buildingId: 'lumber_camp', gridX: '3', gridY: '4' })
})

test('业务失败：只报服务端给的原因，一次都不多拉（刷新会盖掉玩家正在看的提示）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  const total = h.http.calls.length
  h.http.bizFailNext = { code: 3003, msg: '资源不足', detail: '木材还差 1200' }

  await h.root.upgradeBuilding('barracks')

  assert.equal(h.http.calls.length, total + 1, '只多发这一条写请求，不许顺手重拉列表')
  assert.deepEqual(h.errors.at(-1), ['city', '木材还差 1200'],
    'detail 优先于 msg：玩家要知道差多少，不是听一句「资源不足」')
})

test('业务失败没有 detail 时退回 msg：detail 是「差多少」，msg 只是「哪一类」', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.http.bizFailNext = { code: 3003, msg: '资源不足', detail: null }

  await h.root.upgradeBuilding('barracks')

  assert.equal(h.errors.at(-1)?.[1], '资源不足')
})

test('传输抛错时说的是「网络不通」：玩家看到这句会等，看到「操作失败」只会去重试同一件事', async () => {
  const h = harness({ transportFails: true })

  assert.equal(await h.root.start('dev-1', '君'), false)
  assert.equal(h.errors[0]?.[0], 'session')
  assert.match(h.errors[0]?.[1] ?? '', /网络不通/)
})

test('面板读取失败同时是一条埋点：只发给 Console 的话，「某个面板一直是空的」永远查不回来', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.events.length = 0
  h.http.bizFailNext = { code: 2001, msg: '背包还没开', detail: null }

  await h.root.refresh('bag')

  const failed = h.events.find(e => e.name === 'panel_load_failed')
  assert.ok(failed, `必须发一条 panel_load_failed，实际事件=${h.events.map(e => e.name).join('、') || '（一条没有）'}`)
  assert.deepEqual(failed?.params, { panel: 'bag', kind: 'biz', reason: '背包还没开' },
    'panel 与 kind 是分得开的两格：哪个面板、是哪一类失败')
  assert.equal(h.errors.length, 1,
    '埋点不取代给玩家的那句提示 —— 它们是同一个收口点的两半，少一半就是「玩家看不到」或「后台查不到」')
})

test('邮件不在首屏预拉里：点开那一格才拉第一次（多一个并发请求会挤首屏预算）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')

  assert.equal(h.http.calls.some(c => c.path === '/mail/list'), false,
    '首屏发过 /mail/list 就等于把 3 秒预算分一格给一个玩家未必会打开的面板')

  await h.root.refresh('mail')

  assert.equal(h.http.calls.filter(c => c.path === '/mail/list').length, 1)
  assert.equal(h.attached.includes('mail'), true, '列表必须落到邮件面板')
  assert.equal(h.errors.length, 0)
})

test('活动列表：刷新打 /activity/list，递下去的是响应 + 服务端时刻（剩余时间靠这两个数相减）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.attached.length = 0

  assert.equal(h.http.calls.some(c => c.path === '/activity/list'), false,
    '活动不占首屏：没点开任务那一格之前一次都不许拉（B17 §六）')

  await h.root.refresh('activity')

  assert.equal(h.http.calls.filter(c => c.path === '/activity/list').length, 1)
  assert.equal(h.attached.includes('activity'), true, '列表必须落到面板的活动页')
  assert.equal(h.lastActivityNow, SERVER_NOW,
    '递下去的时刻必须是响应里的 serverNow —— 用本地时钟算剩余时间就是铁律 5 的违规')
  assert.equal(h.errors.length, 0)
})

test('领活动奖励：带 activityId 发一次写请求（含幂等键），成功后重拉列表并把回执单独递一次', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.attached.length = 0

  await h.root.claimActivity('activity_monster_hunt')

  const claims = h.http.calls.filter(c => c.path === '/activity/claim')
  assert.equal(claims.length, 1, '领一次只发一次请求')
  assert.equal(claims[0]?.body.activityId, 'activity_monster_hunt')
  assert.equal(claims[0]?.body.requestId !== undefined, true, '领取是写操作，必须带幂等键')
  assert.equal(h.http.calls.filter(c => c.path === '/activity/list').length, 1,
    '领完重拉一次列表，那一行才会变成「本轮已领取」')
  assert.deepEqual(h.attached, ['activityClaimed', 'activity'],
    '先递回执（玩家要看到领到了什么），再落重拉后的列表')
  assert.equal(h.events.some(e => e.name === 'activity_claim' && e.params.activityId === 'activity_monster_hunt'),
    true, '领奖要埋点：看板上要能看出哪条活动在发奖')
})

test('一键领取：只发 1 次请求（B12 禁止项），成功后重拉列表并把回执单独递一次', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.attached.length = 0

  await h.root.claimAllMail()

  assert.equal(h.http.calls.filter(c => c.path === '/mail/claimAll').length, 1,
    '50 封邮件发 50 次请求就是那条禁止项')
  assert.equal(h.http.calls.filter(c => c.path === '/mail/list').length, 1,
    '领完重拉一次列表，那几封才会变成「已领取」')
  assert.deepEqual(h.attached, ['mailClaimed', 'mail'],
    '先递回执（玩家要看到领到了什么），再落重拉后的列表')
  const claim = h.http.calls.find(c => c.path === '/mail/claimAll')
  assert.equal(claim?.body.requestId !== undefined, true, '领取是写操作，必须带幂等键')
  assert.equal(claim?.body.mailIds, undefined,
    '请求体里不该出现邮件 id 列表：哪几封可领是服务端状态')
})

test('领取失败与所有面板一样走同一个收口点：给玩家一句话，并发一条 panel_load_failed', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.events.length = 0
  h.http.bizFailNext = { code: 1002, msg: '请求重复提交', detail: null }

  await h.root.claimAllMail()

  assert.equal(h.errors.some(e => e[0] === 'mail'), true, '面板名必须是 mail，玩家才知道是哪一格出错')
  const failed = h.events.find(e => e.name === 'panel_load_failed')
  assert.equal(failed?.params.panel, 'mail')
  assert.equal(failed?.params.reason, '请求重复提交')
  assert.equal(h.http.calls.filter(c => c.path === '/mail/list').length, 0,
    '失败就不重拉列表：列表还没变，多拉一次只是把失败藏起来')
})

test('战报列表：refresh 只发一次 GET，回执落到面板（GameApi 那两个方法第一次有了调用方）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.attached.length = 0

  await h.root.refresh('reports')

  assert.equal(h.http.calls.filter(c => c.path === '/battle/reports').length, 1)
  assert.equal(h.attached.includes('reports'), true)
  assert.equal(h.errors.length, 0)
})

test('点开一场：只拉详情，不重拉列表（列表刚拉过，重拉会把玩家正看着的那一屏换掉）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  await h.root.refresh('reports')
  h.http.calls.length = 0
  h.attached.length = 0

  await h.root.openReport('r-1')

  assert.equal(h.http.calls.filter(c => c.path === '/battle/report').length, 1)
  assert.equal(h.http.calls.some(c => c.path === '/battle/reports'), false)
  assert.deepEqual(h.attached, ['reportReplay'], '详情落到回放，而不是又落一次列表')
})

test('详情拉不到：走所有面板同一个收口点（给玩家一句话 + 一条 panel_load_failed）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.events.length = 0
  h.http.bizFailNext = { code: 11003, msg: '战报已过期', detail: null }

  await h.root.openReport('r-gone')

  assert.equal(h.errors.some(e => e[0] === 'reports'), true)
  const failed = h.events.find(e => e.name === 'panel_load_failed')
  assert.equal(failed?.params.panel, 'reports')
  assert.equal(failed?.params.reason, '战报已过期')
})

test('一键收割发的是 buildingId=null，且收割结果先落地再刷新列表', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.attached.length = 0

  await h.root.collect(null)

  const call = h.http.calls.find(c => c.path === '/city/collect')
  assert.equal(call?.body.buildingId, null,
    '「一键收割」必须是显式的 null，而不是缺字段 —— 缺字段在服务端那是另一种语义')
  assert.deepEqual(h.attached.slice(0, 2), ['cityCollect', 'city'],
    '飘字必须赶在列表重画之前，否则玩家看不到收了多少')
})

test('领奖：三选一的 heroChoice 原样进请求体，埋点区分「选完再领」与「直接领」', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')

  await h.root.claimQuest('quest_main_01', 'hero_sr_02')

  const claims = h.http.calls.filter(c => c.path === '/quest/claim')
  assert.equal(claims.length, 1)
  assert.equal(claims[0]?.body.questId, 'quest_main_01')
  assert.equal(claims[0]?.body.heroChoice, 'hero_sr_02',
    '选中的武将必须原样进请求体 —— 服务端靠它决定发哪一名')
  assert.deepEqual(h.events.find(e => e.name === 'quest_claim')?.params,
    { questId: 'quest_main_01', needsChoice: 'true', heroId: 'hero_sr_02' })
  assert.deepEqual(h.attached.slice(-2), ['quest', 'hero'],
    '领完要重拉任务面板（已领取状态）与武将面板（整卡进册的权威读法）')
})

test('领奖：没有候选的任务必须带 null 而不是空串（多传或漏传都会被服务端拒）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')

  await h.root.claimQuest('quest_side_01', null)

  const claim = h.http.calls.find(c => c.path === '/quest/claim')
  assert.equal(claim?.body.heroChoice, null,
    '无候选就是 null：空串会让服务端把「传了一个空 id」当成选择结果')
  assert.equal(h.events.find(e => e.name === 'quest_claim')?.params.needsChoice, 'false')
})

test('×10 扫荡只发一个 count=10 的请求（拆成十个请求，弱网下会只成一半而红点剩一格）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')

  await h.root.sweep('s1', 10)

  const sweeps = h.http.calls.filter(c => c.path === '/stage/sweep')
  assert.equal(sweeps.length, 1)
  assert.equal(sweeps[0]?.body.count, 10)
})

test('信息不足的动作不发请求，只说清缺什么（替玩家挑阵容消耗的是他的兵和体力，且不会报错）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  const total = h.http.calls.length

  h.root.challenge('s1')
  await h.root.useItem('item_speedup', true)

  assert.equal(h.http.calls.length, total, '两个动作都不该发出请求')
  assert.equal(h.errors.length, 2, '两个动作各一条说明；首屏的降级提示是另一条用例的桩造的，不在这里')
// 按内容找而不是按下标：首屏可能再插进别面板的降级提示，下标不是契约
  const messages = h.errors.map(e => e[1]).join('\n')
  assert.match(messages, /阵容/)
  assert.match(messages, /目标/)
  // 「出售」那条断言随 B24 裁决④ 撤掉：出售整条撤下（连表列都删了），不再有点了被拒这条通路
})

test('加速道具先展示真实队列，选中建筑后才带 targetId 发请求', async () => {
  const h = harness()
  h.http.overrides.set('/city/list', {
    buildings: [
      { id: 'b1', configId: 'main_city', level: 2, gridX: 3, gridY: 3,
        status: 'UPGRADING', finishAt: SERVER_NOW + 60_000, remainingSeconds: 60,
        progress: 5000, startedAt: SERVER_NOW - 60_000, totalSeconds: 120, helpCount: 0 },
    ],
    buildOptions: [],
    queues: { used: 1, available: 2, max: 3 },
    resources: {},
    serverNow: SERVER_NOW,
  })
  await h.root.start('dev-1', '测试')

  await h.root.useItem('item_speedup', true)
  assert.equal(h.http.countOf('/item/use'), 0, '选目标之前不能先扣道具')
  assert.deepEqual(h.speedupOptions.map(option => option.targetId), ['b1'])

  h.pickSpeedup('b1')
  await new Promise(resolve => setTimeout(resolve, 0))
  const call = h.http.calls.find(c => c.path === '/item/use')
  assert.equal(call?.body.targetId, 'b1')
  assert.deepEqual(h.attached.slice(-4), ['bag', 'city', 'army', 'reddot'])
})

test('挑战先展示已编成阵容，选中后才把英雄与全部可用兵力送进请求', async () => {
  const h = harness()
  h.http.overrides.set('/hero/list', {
    heroes: [{ heroId: 'h1', name: '卫无咎' }, { heroId: 'h2', name: '沈砚秋' }],
    lineups: [{
      presetIndex: 0, main: 'h1', sub1: 'h2', sub2: null,
      bonus: { commandValue: 120 }, activeBonds: [],
    }],
    fragments: [], troopCap: 360, troopsInUse: 125, serverNow: SERVER_NOW,
  })
  h.http.overrides.set('/army/list', {
    units: [
      { unitId: 'unit_infantry_t1', name: '步兵', count: 100 },
      { unitId: 'unit_archer_t1', name: '弓兵', count: 25 },
    ],
    troopCap: 360, troopsInUse: 125, trainingInUse: 0, queueSlots: 0, queueSlotsMax: 2,
    hospital: {}, serverNow: SERVER_NOW,
  })
  await h.root.start('dev-1', '测试')

  h.root.challenge('s1')
  assert.equal(h.http.countOf('/stage/challenge'), 0, '选阵容之前不能消耗体力与兵力')
  assert.equal(h.lineupOptions.length, 1)

  h.pickLineup(0)
  await new Promise(resolve => setTimeout(resolve, 0))
  const call = h.http.calls.find(c => c.path === '/stage/challenge')
  assert.deepEqual(call?.body.heroes, ['h1', 'h2'])
  assert.deepEqual(call?.body.units, [
    { unitId: 'unit_infantry_t1', count: 100 },
    { unitId: 'unit_archer_t1', count: 25 },
  ])
  assert.deepEqual(h.attached.slice(-3), ['stage', 'army', 'hero'])
})

test('踢人按页签分流到不同端点：View 的回调不带组织，根必须带上', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')

  await h.root.kick('P2', 'squad')
  await h.root.kick('P3', 'alliance')

  const paths = h.http.calls.filter(c => c.path.includes('kick')).map(c => c.path)
  assert.deepEqual(paths, ['/squad/kick', '/alliance/kick'])
})

test('流亡迁城：exile 一次 + 它内部重拉列表一次，不重复刷世界面板', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  const marchesBefore = h.http.countOf('/world/marches')

  await h.root.exile()

  assert.equal(h.http.countOf('/world/exile'), 1)
  assert.equal(h.http.countOf('/world/marches'), marchesBefore + 1,
    'doExile 内部已经重拉过，根再拉一次就是双倍请求')
})

test('搜索：半径由面板给、maxCount 由根定，响应回到目标列表', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.attached.length = 0

  await h.root.searchTargets(64)

  const call = h.http.calls.find(c => c.path === '/world/searchTargets')
  assert.equal(call?.body.radius, 64)
  assert.equal(typeof call?.body.maxCount, 'number')
  assert.deepEqual(h.attached, ['targets'])
})

test('每个面板动作都要留下一个事件（B16 验收 3 的客户端半边，卡口比对的就是这件事）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.events.length = 0

  await h.root.upgradeBuilding('barracks')
  await h.root.train('unit_infantry_t1', 10)

  assert.deepEqual(h.events.map(e => e.name), ['building_upgrade_start', 'army_train'],
    '动作的事件必须在请求之前落，否则请求失败时这一环就彻底消失了')
  const [first, second] = h.events
  assert.deepEqual(first?.params, { buildingId: 'barracks' })
  assert.deepEqual(second?.params, { unitId: 'unit_infantry_t1', count: '10' },
    '参数统一是字符串：埋点字段类型化会让每加一种类型都要改双端契约')
})

test('被挡下的点击同样要留事件，并带上"被什么挡下"：这是产品该不该补选择器的依据', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.events.length = 0

  await h.root.useItem('item_speedup', true)

  assert.deepEqual(h.events, [{ name: 'item_use', params: { itemId: 'item_speedup', blocked: 'picker' } }])
  assert.equal(h.http.calls.filter(c => c.path === '/item/use').length, 0,
    '挡下就不发请求，但事件必须发')
})

test('一次启动只发 PANEL_PULLS 条请求（多出来的每一个都是玩家在等的白屏时长）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  assert.equal(h.http.calls.length, PANEL_PULLS + 1)
})

test('互助列表真拉得到：面板拿到的是一批完整请求，不再用空数组假装', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')

  assert.deepEqual(h.lastSocialHelps, ['h1', 'h2'],
    '列表包含已经帮过的行（alreadyHelped 标出来），玩家要看得见「我帮过谁」')
  const helpCall = h.http.calls.find(c => c.path === '/social/helpRequests')
  assert.notEqual(helpCall, undefined)
})

test('红点树进入首屏组合根，导航与面板消费的是同一棵服务端权威树', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')

  assert.equal(h.http.countOf('/social/reddot'), 1)
  assert.equal(h.reddotTree?.isLit('social'), true,
    '父链聚合必须由客户端树完成，导航不应自己看 pendingHelps')
  assert.equal(h.reddotTree?.isLit('social/help'), true)
  assert.equal(h.reddotTree?.isLit('social/invite'), false, '无假红点')
  assert.equal(h.reddotTree?.isLit('city/building'), false)
})

test('一键帮助成功后重拉红点树：已消失的父链和叶子同轮熄灭', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  assert.equal(h.reddotTree?.isLit('social/help'), true)

  h.http.overrides.set('/social/reddot', reddotResponse(false, false))
  await h.root.helpAll()

  assert.equal(h.http.countOf('/social/reddot'), 2,
    '处理完不重拉，导航会一直亮到玩家刷新页面')
  assert.equal(h.reddotTree?.isLit('social/help'), false)
  assert.equal(h.reddotTree?.isLit('social'), false)
  // 聊天页签与社交面板共用同一份摘要（未读账），所以它紧跟在 social 之后落地
  assert.deepEqual(h.attached.slice(-5), ['social', 'chat', 'army', 'city', 'reddot'],
    '互助动作结束后，社交、聊天与红点都应收到权威结果')
})

test('单条帮助也重拉红点树：处理一行后不用刷新页面才熄灭', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.http.overrides.set('/social/reddot', reddotResponse(false, false))

  await h.root.help('h1')

  assert.equal(h.http.countOf('/social/reddot'), 2)
  assert.equal(h.reddotTree?.isLit('social/help'), false)
  assert.equal(h.reddotTree?.isLit('social'), false)
})

test('事件标记已读后重拉红点树：未读事件叶子与社交父链同轮熄灭', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.http.overrides.set('/social/reddot', {
    nodes: [
      { key: 'social', lit: true, children: [
        { key: 'social/help', lit: false, children: [] },
        { key: 'social/invite', lit: false, children: [] },
        { key: 'social/events', lit: true, children: [] },
      ] },
    ],
    leafCount: 4,
    serverNow: SERVER_NOW,
  })
  await h.root.refresh('reddot')
  assert.equal(h.reddotTree?.isLit('social/events'), true)

  h.http.overrides.set('/social/reddot', reddotResponse(false, false))
  await h.root.ackEvents(['event-1'])

  assert.equal(h.http.countOf('/social/reddot'), 3,
    '启动、制造未读事件、标记已读各拉一次')
  assert.equal(h.reddotTree?.isLit('social/events'), false)
  assert.equal(h.reddotTree?.isLit('social'), false)
  assert.deepEqual(h.attached.slice(-3), ['social', 'chat', 'reddot'])
})

test('社交面板的成员走 diff 通道真拉得到：首次 version=0，之后带上服务端给的版本号', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')

  const social = h.attached.filter(a => a === 'social').length
  assert.equal(social, 1, 'start 之后社交面板要落地一次')
  const syncCall = h.http.calls.find(c => c.path === '/alliance/sync')
  assert.equal(syncCall?.body.version, 0, '首次必须是 0，也就是"给我全量"')

  h.http.calls.length = 0
  await h.root.refresh('social')
  const again = h.http.calls.find(c => c.path === '/alliance/sync')
  assert.equal(again?.body.version, 7,
    '游标必须用服务端返回的那个：本地自增会与对端错位，表现是有人早就退盟了还挂在列表里')
})

test('未入盟不再拉成员列表：摘要说没有联盟就不发那一问，也不报「暂时拉不到」', async () => {
  // 微信开发者工具实测抓到的：新号（无联盟）每次刷新社交面板都打一次 /alliance/sync，
  // 服务端回 10010「联盟不存在或已解散」，客户端把它拼成「成员列表暂时拉不到，稍后会自动重试」。
  // 那句话永远不会兑现，而这一次请求每轮刷新都白发。
  const h = harness()
  h.http.overrides.set('/social/summary', Object.assign({}, ROUTES['/social/summary'], { alliance: null }))

  await h.root.start('dev-1', '君')

  assert.equal(h.http.countOf('/alliance/sync'), 0,
    '未入盟时那一问必然失败：发出去就是每次刷新白发一次请求')
  assert.equal(h.errors.some(e => e[0] === 'social'), false,
    '「本来就没有」不是「暂时拉不到」，报成后者等于给玩家一句永远等不到结果的话')
  assert.deepEqual(h.lastSocialMembers, [])

  // 之后真的入盟了：游标必须仍是"从零要全量"，不能带着上一个状态留下的版本号去要增量
  h.http.overrides.delete('/social/summary')
  h.http.calls.length = 0
  await h.root.refresh('social')
  const sync = h.http.calls.find(c => c.path === '/alliance/sync')
  assert.equal(sync?.body.version, 0, '入盟后的第一次同步必须是全量')
  assert.deepEqual(h.lastSocialMembers, ['M1', 'M2'], '入盟后成员列表要真的能出来')
})

test('成员 diff 的合并：变更覆盖、移除摘掉，面板拿到的永远是合并后的全量', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  let members = h.lastSocialMembers
  assert.deepEqual(members, ['M1', 'M2'])

  h.http.overrides.set('/alliance/sync', syncResponse({
    version: 9,
    changedMembers: [{ id: 'M3', name: '丙', power: 30, role: 'MEMBER', contribution: 0, lastActiveAt: 3, squadId: null }],
    removedMemberIds: ['M1'],
  }))
  await h.root.refresh('social')
  members = h.lastSocialMembers
  assert.deepEqual(members, ['M2', 'M3'], '先删后加，顺序按合并结果断言')
})

test('某一小块拉不到不连带废掉整个社交面板，且两块都坏时原因一起说清', async () => {
  const h = harness()
  h.http.failPaths.add('/alliance/sync')
  h.http.failPaths.add('/social/helpRequests')

  await h.root.start('dev-1', '君')

  assert.equal(h.attached.includes('social'), true, '摘要本身是好的，整块不显示是过度反应')
  assert.deepEqual(h.lastSocialMembers, [])
  assert.deepEqual(h.lastSocialHelps, [])
  const reason = h.errors.find(e => e[0] === 'social')?.[1] ?? ''
  assert.ok(reason.includes('成员') && reason.includes('互助'),
    '两条支路各坏各的要一起说，只报第一条会让玩家以为另一块是正常的：' + reason)

  h.http.failPaths.clear()
  await h.root.refresh('social')
  assert.equal(h.lastSocialMembers.length, 2, '恢复之后不需要重启就能自动补上')
  assert.equal(h.lastSocialHelps.length, 2)
})

test('微信登录：wx.login 的 code 原样进 /player/init，服务端票据之后每条请求都带', async () => {
  const h = harness()

  // 第三个参数是 wxCode：小游戏里由 wx.login 取，浏览器传 null
  assert.equal(await h.root.start('dev-wx', '君', 'wx-code-xyz'), true)

  const init = h.http.calls.find(c => c.path === '/player/init')
  assert.ok(init !== undefined, '登录请求必须发出去')
  assert.equal(init.body.wxCode, 'wx-code-xyz',
    'code 必须原样交给服务端换 openid；客户端不解析、不缓存、不复用')

  const after = h.http.calls.filter(c => c.path !== '/player/init')
  assert.ok(after.length > 0, '登录后至少要拉首屏')
  // 断言消息里带上实际头，失败时不用再跑一次才知道是哪条请求漏了票
  const missingToken = after.filter(c => c.headers.Authorization !== 'Bearer session-token-1')
    .map(c => `${c.path}:${JSON.stringify(c.headers)}`)
  assert.equal(missingToken.length, 0,
    `登录拿到的会话票据必须自动附到之后每条请求上，漏带的是 ${missingToken.join(', ')}`)
})

/**
 * 引导在 AppRoot 这一层的三件事：脚本走得通路由、位置只跟着回执走、埋点三件参数齐。
 * （一帧该画什么的判定在 GuideDriver.test.ts 里，这里只管"到浏览器之外的边界"。）
 */
test('引导：登录后拉一次脚本、上报之后位置跟着回执走、埋点带 stepId+action+guideVersion', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-guide', '君'), true)

  await h.root.refresh('guide')
  assert.ok(h.attached.includes('guide'), '脚本必须投递给引导层（客户端自己没有第二份脚本）')
  const scriptCall = h.http.calls.find(c => c.path === '/guide/script')
  assert.ok(scriptCall !== undefined, 'refresh("guide") 必须真的打一次 GET /guide/script')
  assert.equal(scriptCall.body.requestId, undefined, '读操作不该带幂等键')

  const acks: Array<number | null> = []
  await h.root.guideProgress('g1', 'COMPLETE', resp => acks.push(resp.nextStepIndex))
  assert.deepEqual(acks, [2], '界面只按回执里的 nextStepIndex 挪位置，不自己加一')
  const progressCall = h.http.calls.find(c => c.path === '/guide/progress')
  assert.equal(progressCall?.body.stepId, 'g1')
  assert.equal(progressCall?.body.action, 'COMPLETE')
  assert.equal(String(progressCall?.body.requestId ?? '').length > 0, true, 'mutate 必须补上幂等键')

  h.root.trackGuideStep('skip', 'g2', '7')
  const last = h.events.filter(e => e.name === 'guide_step').pop()
  assert.deepEqual(last?.params, { action: 'skip', stepId: 'g2', guideVersion: '7' },
    '没有 guideVersion，一次热更在看板上会长得像一次流失')
})

test('分享战报：先问目标频道再发请求；失败也走面板那行提示，而不是只进 console', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)

  // 点了「分享」才弹选择器；没选之前一个请求都不该发出去
  h.root.requestShare('r-1')
  assert.deepEqual(h.shareChannelOptions.map(option => option.channel), ['ALLIANCE', 'SQUAD'])
  assert.equal(h.http.countOf('/battle/share'), 0, '还没选频道就不该发请求')

  h.pickShareChannel('ALLIANCE')
  await settle()
  const call = h.http.calls.find(c => c.path === '/battle/share')
  assert.equal(call?.body.reportId, 'r-1')
  assert.equal(call?.body.channel, 'ALLIANCE')
  assert.deepEqual([...h.lastShareOutcome], ['已分享到联盟频道', false])

  // 失败（未入盟发小队）也要让玩家看见：只说进 console 等于分享没成功而没人知道
  h.http.bizFailNext = { code: 10046, msg: '你没有在该频道发言的资格', detail: null }
  await h.root.shareReport('r-2', 'SQUAD')
  assert.deepEqual([...h.lastShareOutcome], ['你没有在该频道发言的资格', true])
})

test('举报与拉黑：一层选择器里给出原因与拉黑；拉黑后本地缓存要丢掉重拉（否则旧消息还挂着）', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)
  h.http.overrides.set('/chat/list', {
    messages: [
      { messageId: 'm1', channel: 'WORLD', senderId: 'P2', senderName: '乙', content: '卖号加我', sentAt: SERVER_NOW - 1000 },
    ],
    hasMore: false, serverNow: SERVER_NOW,
  })
  await h.root.openChat()
  assert.equal(h.lastChat?.messages.length, 1)

  // 点别人那条消息上的按钮：先懒取名单（首屏不为它多打一轮），再给出选项
  await h.root.openChatActions('P2', 'm1')
  assert.equal(h.http.countOf('/social/blocks'), 1, '第一次点才拉名单')
  // 一页放 4 项，所以顺序是：4 种举报原因 → 私聊他 → 关注/取关 → 拉黑/取消拉黑
  assert.deepEqual(h.chatActionOptions.map(option => option.kind),
    ['REPORT', 'REPORT', 'REPORT', 'REPORT', 'PRIVATE', 'FOLLOW', 'BLOCK'])

  // 举报：只记不留（回执说"运营会看到"，不说会不会封）
  h.pickChatAction('REPORT_SPAM')
  await settle()
  const reportCall = h.http.calls.find(c => c.path === '/social/report')
  assert.equal(reportCall?.body.targetPlayerId, 'P2')
  assert.equal(reportCall?.body.messageId, 'm1')
  assert.equal(reportCall?.body.reason, 'SPAM')
  assert.equal(h.lastChat?.hintText, '举报已受理，运营会看到这条记录')

  // 拉黑：名单跟着服务端回执更新，且**本地历史整份丢掉重拉** ——
  // 不丢的话，被拉黑的人刚才那几句还挂在聊天记录里，玩家会以为拉黑没生效
  h.http.overrides.set('/chat/list', { messages: [], hasMore: false, serverNow: SERVER_NOW })
  await h.root.blockPlayer('P2')
  assert.equal(h.http.calls.filter(c => c.path === '/chat/list').length, 2, '拉黑后要重拉当前频道')
  assert.equal(h.lastChat?.messages.length, 0, '被拉黑的人的消息立刻从视图里消失')

  // 再点一次：菜单里变成「取消拉黑」
  await h.root.openChatActions('P2', 'm1')
  assert.equal(h.chatActionOptions.some(option => option.kind === 'UNBLOCK'), true)
  h.pickChatAction('UNBLOCK')
  await settle()
  assert.equal(h.lastChat?.hintText, '已取消拉黑')
})

test('关注与私聊发起：菜单里关注他 → 私聊页出现他 → 点开就是一段空会话（名字来自关注列表）', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)

  await h.root.openChatActions('P2', 'm1')
  assert.equal(h.chatActionOptions.some(option => option.kind === 'FOLLOW'), true,
    '菜单里要有「关注他」（第 2 页，与拉黑并列）')
  assert.equal(h.chatActionOptions.some(option => option.kind === 'PRIVATE'), true,
    '以及「私聊他」——这是 S1 里刻意留给关注那块的私聊发起入口')

  h.pickChatAction('FOLLOW')
  await settle()
  const followCall = h.http.calls.find(c => c.path === '/social/follow')
  assert.equal(followCall?.body.targetPlayerId, 'P2')
  assert.equal(h.lastChat?.friends.length, 1)
  assert.equal(h.lastChat?.friends[0]?.name, '乙')
  assert.equal(h.lastChat?.hintText, '已关注，打开私聊页就能找到他')

  // 从关注列表发起私聊：切到私聊频道 + 拉一段（这次是空的）历史，名字由关注列表喂进昵称表
  h.http.overrides.set('/chat/list', { messages: [], hasMore: false, serverNow: SERVER_NOW })
  await h.root.openConversation('P2')
  assert.equal(h.lastChat?.channel, 'PRIVATE')
  assert.equal(h.lastChat?.peerLabel, '乙', '没有历史消息时名字只能来自关注列表')
  assert.equal(h.lastChat?.messages.length, 0, '新会话是空的 —— 这正是"发起"与"回复"的区别')
})

test('浏览器路径：没有 wxCode 时不带该值，登录链路与旧行为完全一致', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-web', '君'), true)

  const init = h.http.calls.find(c => c.path === '/player/init')
  assert.ok(init !== undefined)
  assert.equal(init.body.wxCode, '',
    '浏览器/编辑器预览必须发空串（服务端按空白判定），而不是让字段缺省成 undefined')
})

// ---------- 聊天（B22 §一 1） ----------

/** 私信事件。字段与 `/social/summary` 和推送两路一致（形状由 SocialPushShapeTest 钉住）。 */
function privateEvent(eventId: string, peerId: string, occurredAt: number): Record<string, unknown> {
  return {
    eventId, type: 'PRIVATE_MESSAGE', title: `${peerId} 给你发来一条私信`, body: null,
    coord: null, relatedId: peerId, occurredAt, expired: false,
  }
}

function summaryWithEvents(events: Array<Record<string, unknown>>): Record<string, unknown> {
  return { ...(ROUTES['/social/summary'] as Record<string, unknown>), events }
}

/** 等异步的推送处理跑完（推送 → 刷新红点 → 落地数据这条链是异步的）。 */
async function settle(): Promise<void> {
  for (let i = 0; i < 12; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 1))
  }
}

test('聊天：进页签拉当前频道历史，画成消息行；切到私聊未选会话时改画会话列表且不发请求', async () => {
  const h = harness()
  h.http.overrides.set('/chat/list', {
    messages: [
      { messageId: 'm1', channel: 'WORLD', senderId: 'P2', senderName: '乙', content: '有人吗', sentAt: SERVER_NOW - 60_000 },
      { messageId: 'm2', channel: 'WORLD', senderId: 'P1', senderName: '君', content: '在', sentAt: SERVER_NOW - 30_000 },
    ],
    hasMore: false, serverNow: SERVER_NOW,
  })
  assert.equal(await h.root.start('dev-1', '君'), true)

  await h.root.openChat()
  assert.equal(h.lastChat?.mode, 'messages')
  assert.deepEqual(h.lastChat?.messages.map(m => m.author), ['乙', '我'],
    '自己的消息写「我」，别人的用服务端昵称')
  const listCall = h.http.calls.filter(c => c.path === '/chat/list').pop()
  assert.equal(listCall?.body.channel, 'WORLD')
  assert.equal(listCall?.body.toPlayerId, null, '非私聊频道不带对象')

  const before = h.http.countOf('/chat/list')
  await h.root.selectChatChannel('PRIVATE')
  assert.equal(h.lastChat?.mode, 'conversations', '私聊未选对象 = 画会话列表')
  assert.equal(h.http.countOf('/chat/list'), before,
    '没有对象就没有会话键，这一问必然被服务端拒绝 —— 不发这一次注定失败的请求')
  assert.equal(h.lastChat?.canSend, false, '没选对象时结构上发不出去')
})

test('聊天：打开私聊会话拉那一段历史，并把该发信人的未读消账（别人的未读不动）', async () => {
  const h = harness()
  h.http.overrides.set('/social/summary',
    summaryWithEvents([privateEvent('e-p2', 'P2', SERVER_NOW - 10_000), privateEvent('e-p3', 'P3', SERVER_NOW - 9_000)]))
  assert.equal(await h.root.start('dev-1', '君'), true)
  assert.equal(h.lastChat?.channelTabs.find(t => t.key === 'PRIVATE')?.unread, 2)

  await h.root.selectChatChannel('PRIVATE')
  assert.equal(h.lastChat?.mode, 'conversations')
  assert.deepEqual((h.lastChat?.conversations ?? []).map(c => c.peerId), ['P3', 'P2'],
    '最近说话的排在前面')

  h.http.overrides.set('/chat/list', {
    messages: [
      { messageId: 'm9', channel: 'PRIVATE', senderId: 'P2', senderName: '乙', content: '来帮我', sentAt: SERVER_NOW - 10_000 },
    ],
    hasMore: false, serverNow: SERVER_NOW,
  })
  await h.root.openConversation('P2')

  const listCall = h.http.calls.filter(c => c.path === '/chat/list').pop()
  assert.equal(listCall?.body.channel, 'PRIVATE')
  assert.equal(listCall?.body.toPlayerId, 'P2')
  assert.equal(h.lastChat?.mode, 'messages')
  assert.equal(h.lastChat?.peerLabel, '乙', '打开一次就从消息里学到了昵称')
  const ack = h.http.calls.find(c => c.path === '/social/ackEvents')
  assert.deepEqual(ack?.body.eventIds, ['e-p2'],
    '只消这一个发信人的未读：把别人的一起清了会让对方那条永远消失')
})

test('聊天：私聊推送到达 ⇒ 未读账 +1 且红点按服务端结论刷新（此前这条链整条是死的）', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)
  const socket = h.sockets[0]
  assert.ok(socket !== undefined)
  socket.simulateOpen()

  const reddotsBefore = h.http.countOf('/social/reddot')
  const off = h.root.bindPush()
  try {
    socket.simulateMessage({
      type: 'PRIVATE_MESSAGE', serverNow: SERVER_NOW,
      data: privateEvent('e-1', 'P2', SERVER_NOW),
    })
    await settle()

    assert.equal(h.http.countOf('/social/reddot'), reddotsBefore + 1,
      '红点只读服务端权威树：推送到了要问一次树，而不是客户端自己点亮')
    assert.equal(h.lastChat?.channelTabs.find(t => t.key === 'PRIVATE')?.unread, 1)
    assert.equal(h.lastChat?.conversations.length, 1)

    // 同一条事件重复到达（重连补拉 + 推送）只算一次：数成两条就永远清不掉
    socket.simulateMessage({
      type: 'PRIVATE_MESSAGE', serverNow: SERVER_NOW,
      data: privateEvent('e-1', 'P2', SERVER_NOW),
    })
    await settle()
    assert.equal(h.lastChat?.channelTabs.find(t => t.key === 'PRIVATE')?.unread, 1)

    // 别的推送类型不掺和聊天
    socket.simulateMessage({ type: 'MEMBER_ATTACKED', serverNow: SERVER_NOW, data: {} })
    await settle()
    assert.equal(h.lastChat?.channelTabs.find(t => t.key === 'PRIVATE')?.unread, 1)
  } finally {
    off()
  }

  // 退订之后再来一条：不再进账（gameBus 是单例，不退订会让重登的第二个根被调一次）
  socket.simulateMessage({
    type: 'PRIVATE_MESSAGE', serverNow: SERVER_NOW,
    data: privateEvent('e-2', 'P2', SERVER_NOW),
  })
  await settle()
  assert.equal(h.lastChat?.channelTabs.find(t => t.key === 'PRIVATE')?.unread, 1)
})

test('聊天：形状不对的推送当没收到（数得出来又读不出的未读比少一条更难查）', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)
  const socket = h.sockets[0]
  assert.ok(socket !== undefined)
  socket.simulateOpen()

  const originalWarn = console.warn
  console.warn = () => undefined
  try {
    const off = h.root.bindPush()
    // 存储记录形状（coordX/expireAt、没有 title/relatedId 的推送曾经真的发过）
    socket.simulateMessage({ type: 'PRIVATE_MESSAGE', serverNow: SERVER_NOW, data: { eventId: 'e-1', coordX: 1 } })
    await settle()
    off()
  } finally {
    console.warn = originalWarn
  }
  assert.equal(h.lastChat?.channelTabs.find(t => t.key === 'PRIVATE')?.unread, 0,
    '字段不全就整条丢掉：记进去会让这条未读永远清不掉')
})

test('聊天：发送成功用服务端回执落地并让面板清输入框；限流失败给可读提示且不清', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)

  await h.root.sendChat('  大家好  ')
  const sendCall = h.http.calls.find(c => c.path === '/chat/send')
  assert.equal(sendCall?.body.channel, 'WORLD')
  assert.equal(sendCall?.body.toPlayerId, null)
  assert.equal(sendCall?.body.content, '大家好', '两头的空白要去掉，否则服务端的"同内容"限流判两句话')
  assert.equal(h.lastChat?.messages.length, 1, '落地的是服务端回执那条（有权威 id 与时刻）')
  assert.equal(h.lastChat?.messages[0]?.author, '我')
  assert.equal(h.lastChat?.sentSeq, 1, 'sentSeq 变了 ⇒ 面板清输入框')

  h.http.bizFailNext = { code: 10044, msg: '同一句话发得太快，请稍后再试', detail: '同一句话 8 秒后才能再发' }
  await h.root.sendChat('大家好')
  assert.equal(h.lastChat?.sentSeq, 1, '失败时 sentSeq 不动：玩家打的字不能被清掉')
  assert.equal(h.lastChat?.hintIsWarning, true)
  assert.equal(h.lastChat?.hintText, '慢一点：同一句话 8 秒后才能再发',
    '限流要翻成"慢一点"，不是把服务端的错误原文甩给玩家')
})

test('聊天：未入盟看联盟频道，服务端的拒绝理由原样进提示行（客户端不预判资格）', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)
  h.http.failPaths.add('/chat/list')

  await h.root.selectChatChannel('ALLIANCE')
  assert.equal(h.lastChat?.hintIsWarning, true)
  assert.equal(h.lastChat?.hintText, '服务繁忙',
    'detail 为空时退回 msg；有 detail 就用 detail（服务端已经把下一步写清楚了）')
  assert.equal(h.http.countOf('/chat/list') >= 1, true, '客户端要先敢发出去，才谈得上被服务端裁决')
})

test('聊天：断线重连补拉未读（推送在断线期间全丢了，B01 要求走 HTTP 补拉）', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-1', '君'), true)
  const reddotsBefore = h.http.countOf('/social/reddot')

  const off = h.root.bindPush()
  try {
    // 重连事件由 NetModule 在重连成功时发出；这里直接驱动同一根线
    const { gameBus } = await import('../assets/scripts/core/EventBus')
    gameBus.emit('netReconnected', { downtimeMs: 5_000 })
    await settle()
  } finally {
    off()
  }
  assert.equal(h.http.countOf('/social/summary') >= 2, true, '重连要补拉一次摘要（未读账的家）')
  assert.equal(h.http.countOf('/social/reddot'), reddotsBefore + 1, '顺带把红点树问一次')
})

test('榜单面板：明细页签不发请求，切到榜才拉，且页码回到第 1 页', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  // 打开战力页：默认停在「明细」，那一页由 /player/power 供数 —— 榜单请求一次都不该发
  await h.root.refresh('power')
  assert.equal(h.http.countOf('/rank/list'), 0, '明细页签不该请求榜单')
  assert.equal(h.lastRank?.activeKey, 'DETAIL')
  assert.equal(h.lastRank?.rows.length, 0, '明细页签一行榜行都不画')

  await h.root.openRankTab('POWER')
  assert.equal(h.http.countOf('/rank/list'), 1)
  const call = h.http.calls.filter(c => c.path === '/rank/list').at(-1)
  assert.equal(call?.query.get('type'), 'POWER')
  assert.equal(call?.query.get('page'), '1', '第一次进来必须是第 1 页')
  assert.equal(h.lastRank?.rows.length, 2)
  assert.equal(h.lastRank?.mine?.rankText, '第 2 名', '我的名次来自服务端下发的 myRank')
  assert.deepEqual(h.lastRank?.rows.map(r => r.mine), [false, true], '按 id 标出我自己那一行')
})

test('分页：下一页按服务端回显的 page 推进；hasMore=false 时再点也不发请求', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.http.overrides.set('/rank/list', {
    type: 'KILL', entries: [{ rank: 21, id: 'P7', name: '丙', value: 5, tag: null }],
    myRank: 21, myValue: 5, page: 1, pageSize: 20, hasMore: true,
  })
  await h.root.openRankTab('KILL')
  assert.equal(h.lastRank?.canNext, true, '服务端说还有下一页')

  // 第二页：服务端把这页回成 2，并且没有更多了
  h.http.overrides.set('/rank/list', {
    type: 'KILL', entries: [{ rank: 41, id: 'P8', name: '丁', value: 4, tag: null }],
    myRank: 21, myValue: 5, page: 2, pageSize: 20, hasMore: false,
  })
  await h.root.rankNextPage()
  const second = h.http.calls.filter(c => c.path === '/rank/list').at(-1)
  assert.equal(second?.query.get('page'), '2')
  assert.equal(h.lastRank?.pageText, '第 2 页')
  assert.equal(h.lastRank?.canNext, false)

  // 到底了再点：不发请求（客户端只是照 hasMore 办事，不猜最后一页在哪）
  const before = h.http.countOf('/rank/list')
  await h.root.rankNextPage()
  assert.equal(h.http.countOf('/rank/list'), before, 'hasMore=false 时翻页是空操作')

  // 换一张榜：页码必须回到第 1 页，否则玩家会看到某张榜的"第 2 页"却说不出为什么
  await h.root.openRankTab('ALLIANCE')
  const switched = h.http.calls.filter(c => c.path === '/rank/list').at(-1)
  assert.equal(switched?.query.get('page'), '1')
  assert.equal(switched?.query.get('type'), 'ALLIANCE')
})

test('榜拉不到时：把服务端的理由放到提示行，且不把上一份榜清空（清空会被读成"榜没了"）', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  await h.root.openRankTab('POWER')
  assert.equal(h.lastRank?.noticeText, null)

  h.http.failPaths.add('/rank/list')
  await h.root.openRankTab('POWER')
  assert.equal(h.lastRank?.noticeText, '服务繁忙', '业务拒绝的理由原样进提示行')
  assert.equal(h.lastRank?.rows.length, 2, '拉不到不等于榜没了：上一次的行留在原地')
  assert.equal(h.errors.some(e => e[0] === 'rank'), true, '面板读取失败也要进统一的上报口')
})

test('点搜索到的目标 → 拉起编成（带坐标与可选项），且一个请求都不发', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.http.overrides.set('/world/searchTargets', {
    targets: [{ id: 'P9', name: '邻居', coord: { x: 60, y: 60 }, matchPower: 12,
      powerRatio: 12000, distanceBand: 'NEAR', resourceHint: 'NORMAL', isShielded: false,
      tyrannyLevel: null }],
    selfMatchPower: 10, lowerBound: 5, upperBound: 20, serverNow: SERVER_NOW,
  })
  h.http.overrides.set('/army/list', ARMY_FOR_MARCH)
  await h.root.refresh('army')
  await h.root.searchTargets(64)
  const before = h.http.calls.length

  h.root.beginMarchCompose('P9')

  assert.equal(h.http.calls.length, before, '编成只是准备数据：确认之前不发请求、不扣兵')
  assert.equal(h.lastCompose?.targetName, '邻居')
  assert.equal(h.lastCompose?.coordText, '60, 60')
  assert.equal(h.lastCompose?.compose.options.length, 2, '可选项来自军队列表')
  assert.deepEqual(h.lastCompose?.compose.options.map(o => o.selected), [0, 0],
    '不自动勾选全军（裁决④(b)）')
  assert.equal(h.lastCompose?.compose.canSubmit, false)
})

test('勾选后确认 → POST /world/march 只带选中的行、坐标是目标的、requestId 是新生成的', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.http.overrides.set('/world/searchTargets', {
    targets: [{ id: 'P9', name: '邻居', coord: { x: 60, y: 60 }, matchPower: 12,
      powerRatio: 12000, distanceBand: 'NEAR', resourceHint: 'NORMAL', isShielded: false,
      tyrannyLevel: null }],
    selfMatchPower: 10, lowerBound: 5, upperBound: 20, serverNow: SERVER_NOW,
  })
  h.http.overrides.set('/army/list', ARMY_FOR_MARCH)
  await h.root.refresh('army')
  await h.root.searchTargets(64)
  h.root.beginMarchCompose('P9')
  h.root.pickMarchUnit('unit_infantry_t1', 30)
  const marchesBefore = h.http.countOf('/world/marches')

  await h.root.confirmMarch()

  const sent = h.http.calls.filter(c => c.path === '/world/march').at(-1)
  assert.ok(sent, '确认必须真的发出出征请求')
  assert.equal(sent?.body.toX, 60)
  assert.equal(sent?.body.toY, 60)
  assert.equal(sent?.body.action, 'ATTACK', '搜到的目标都是玩家城，行动是 ATTACK')
  assert.deepEqual(sent?.body.units, [{ unitId: 'unit_infantry_t1', count: 30 }],
    '只带选中的行（带 0 会被服务端回「数量必须为正」）')
  assert.match(String(sent?.body.requestId), /^req-\d+$/, '键由 GameApi 每次新生成')
  assert.equal(h.lastCompose?.targetId, '', '成功之后编成收起')
  assert.equal(h.http.countOf('/world/marches'), marchesBefore + 1, '出征后要刷一次行军列表')
})

test('服务端拒绝时：提示原文、编成不收起（玩家能改完再发），且不发第二枪', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.http.overrides.set('/world/searchTargets', {
    targets: [{ id: 'P9', name: '邻居', coord: { x: 60, y: 60 }, matchPower: 12,
      powerRatio: 12000, distanceBand: 'NEAR', resourceHint: 'NORMAL', isShielded: false,
      tyrannyLevel: null }],
    selfMatchPower: 10, lowerBound: 5, upperBound: 20, serverNow: SERVER_NOW,
  })
  h.http.overrides.set('/army/list', ARMY_FOR_MARCH)
  await h.root.refresh('army')
  await h.root.searchTargets(64)
  h.root.beginMarchCompose('P9')
  h.root.pickMarchUnit('unit_infantry_t1', 10)

  h.http.bizFailNext = { code: 6004, msg: '战力圈层校验未通过', detail: '对方实力远弱于你' }
  await h.root.confirmMarch()

  assert.equal(h.lastCompose?.notice, '对方实力远弱于你', '服务端的理由原样进提示行')
  assert.equal(h.lastCompose?.targetId, 'P9', '被拒之后编成不收起：玩家要能改完再发')
  assert.equal(h.lastCompose?.submitting, false, '提交态要复位，否则按钮永远灰着')
  assert.equal(h.http.countOf('/world/march'), 1, '一次确认只发一枪')
})

test('再次出征：把上一次成功的队伍原样重发，且兵力不足时明确拒绝而不是改小', async () => {
  const h = harness()
  await h.root.start('dev-1', '君')
  h.http.overrides.set('/world/searchTargets', {
    targets: [{ id: 'P9', name: '邻居', coord: { x: 60, y: 60 }, matchPower: 12,
      powerRatio: 12000, distanceBand: 'NEAR', resourceHint: 'NORMAL', isShielded: false,
      tyrannyLevel: null }],
    selfMatchPower: 10, lowerBound: 5, upperBound: 20, serverNow: SERVER_NOW,
  })
  h.http.overrides.set('/army/list', ARMY_FOR_MARCH)
  await h.root.refresh('army')
  await h.root.searchTargets(64)

  // 还没出征过：再次出征给的是"没有可以重复的队伍"，且一个请求都不发
  const before = h.http.countOf('/world/march')
  await h.root.repeatLastMarch()
  assert.equal(h.http.countOf('/world/march'), before, '没有上一次就不该发请求')
  assert.match(h.lastCompose?.notice ?? '', /还没有成功出征过/)

  // 先成功出征一次，再重复
  h.root.beginMarchCompose('P9')
  h.root.pickMarchUnit('unit_infantry_t1', 30)
  await h.root.confirmMarch()
  await h.root.repeatLastMarch()

  const repeats = h.http.calls.filter(c => c.path === '/world/march')
  assert.equal(repeats.length, 2, '确认一次 + 重复一次 = 两枪')
  const first = repeats[0]!.body
  const second = repeats[1]!.body
  assert.deepEqual(second.units, first.units, '重复的是同一支队伍（业务字段照搬）')
  assert.equal(second.toX, first.toX)
  assert.equal(second.toY, first.toY)
  assert.equal(second.action, first.action)
  assert.notEqual(second.requestId, first.requestId, '每次点都是新键：复用旧键会被 REQUEST_DUPLICATED 拒')

  // 兵力不够了：说清差多少，且不发第三枪
  const shrunk = { ...ARMY_FOR_MARCH, units: [{ ...ARMY_FOR_MARCH.units[0]!, count: 5 },
    ARMY_FOR_MARCH.units[1]!] }
  h.http.overrides.set('/army/list', shrunk)
  await h.root.refresh('army')
  const beforeShrunk = h.http.countOf('/world/march')
  await h.root.repeatLastMarch()
  assert.equal(h.http.countOf('/world/march'), beforeShrunk, '凑不齐就不发')
  assert.match(h.lastCompose?.notice ?? '', /30/)
  assert.match(h.lastCompose?.notice ?? '', /5/)
})
