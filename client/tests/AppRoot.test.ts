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
import type { HttpResponse, HttpTransport } from '../assets/scripts/net/NetTransport'
import { Prng } from '../assets/scripts/core/Prng'
import { TimeSync } from '../assets/scripts/core/TimeSync'
import { Store } from '../assets/scripts/game/store/Store'
import { GameApi } from '../assets/scripts/game/session/GameApi'
import type { GameApiDeps } from '../assets/scripts/game/session/GameApi'
import { GameSession } from '../assets/scripts/game/session/GameSession'
import { AppRoot } from '../assets/scripts/game/session/AppRoot'
import type { PanelTargets } from '../assets/scripts/game/session/AppRoot'
import type { LineupChoice, SpeedupChoice } from '../assets/scripts/game/session/Choices'
import type { ClientReddotTree } from '../assets/scripts/game/reddot/ReddotTree'
import { resetWorld } from '../assets/scripts/game/world/WorldContext'

const SERVER_NOW = 1_788_000_000_000

/** 每个端点的响应。只给「代码真的会读到的字段」，其余留空对象。 */
const ROUTES: Record<string, unknown> = {
  '/player/init': {
    playerId: 'P1', profile: { nickName: '君' }, cityLevel: 1, resources: {},
    power: { displayPower: 10, matchPower: 10, peakPower: 10 }, protectUntil: null,
    // 微信登录后服务端签发的会话票据；之后每条请求都要带 X-Auth-Token
    authToken: 'session-token-1', serverNow: SERVER_NOW, isNewPlayer: true,
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
  readonly body: Record<string, unknown>
  /** 请求头。登录票据（X-Auth-Token）是 B15 之后每条请求都要带的身份凭证。 */
  readonly headers: Readonly<Record<string, string>>
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
    const path = new URL(url).pathname
    this.calls.push({
      method,
      path,
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
  /** 最近一次下发到场景层的那棵红点树。 */
  readonly reddotTree: ClientReddotTree | null

  readonly root: AppRoot
  readonly http: RoutingHttp
  readonly store: Store
  readonly errors: Array<[string, string]>
  readonly attached: string[]
  readonly events: Array<{ name: string, params: Record<string, string> }>
  readonly speedupOptions: readonly SpeedupChoice[]
  readonly lineupOptions: readonly LineupChoice[]
  pickSpeedup(targetId: string): void
  pickLineup(index: number): void
}

function harness(options: { transportFails?: boolean } = {}): Harness {
  resetWorld()
  const http = new RoutingHttp()
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
    socketFactory: () => {
      throw new Error('本测试不连 WebSocket')
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
  let reddotTree: ClientReddotTree | null = null
  let speedupOptions: SpeedupChoice[] = []
  let lineupOptions: LineupChoice[] = []
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
    reddot: (tree) => {
      attached.push('reddot')
      reddotTree = tree
    },
    power: () => attached.push('power'),
    targets: () => attached.push('targets'),
    quest: () => attached.push('quest'),
    mail: () => attached.push('mail'),
    reports: () => attached.push('reports'),
    reportReplay: () => attached.push('reportReplay'),
    mailClaimed: () => attached.push('mailClaimed'),
    home: () => attached.push('home'),
    speedupTargetChoice: (options, onPick) => {
      speedupOptions = [...options]
      speedupPick = onPick
    },
    lineupChoice: (options, onPick) => {
      lineupOptions = [...options]
      lineupPick = onPick
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
    pickSpeedup(targetId) {
      speedupPick?.(targetId)
    },
    pickLineup(index) {
      const choice = lineupOptions[index]
      if (choice !== undefined) {
        lineupPick?.(choice)
      }
    },

    root, http, store, errors, attached, events,
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
    ['army', 'bag', 'city', 'hero', 'home', 'power', 'quest', 'reddot', 'resources', 'social',
      'stage'])
  assert.equal(h.errors.length, 0)
  assert.equal(h.root.playerId, 'P1')
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
  h.root.sellItem('item_wood')

  assert.equal(h.http.calls.length, total, '三个动作都不该发出请求')
  assert.equal(h.errors.length, 3, '三个动作各一条说明；首屏的降级提示是另一条用例的桩造的，不在这里')
// 按内容找而不是按下标：首屏可能再插进别面板的降级提示，下标不是契约
  const messages = h.errors.map(e => e[1]).join('\n')
  assert.match(messages, /阵容/)
  assert.match(messages, /目标/)
  assert.match(messages, /出售/)
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
  assert.deepEqual(h.attached.slice(-4), ['social', 'army', 'city', 'reddot'],
    '互助动作结束后，社交与红点都应收到权威结果')
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
  assert.deepEqual(h.attached.slice(-2), ['social', 'reddot'])
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

test('浏览器路径：没有 wxCode 时不带该值，登录链路与旧行为完全一致', async () => {
  const h = harness()
  assert.equal(await h.root.start('dev-web', '君'), true)

  const init = h.http.calls.find(c => c.path === '/player/init')
  assert.ok(init !== undefined)
  assert.equal(init.body.wxCode, '',
    '浏览器/编辑器预览必须发空串（服务端按空白判定），而不是让字段缺省成 undefined')
})
