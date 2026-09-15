/**
 * 职责：GameApi 的单测 —— 端点绑定、幂等键注入、时钟校准、Store 写入、世界模型接线。
 * 依赖：node:test / node:assert；传输层用 Fake 注入，不碰真实网络。
 *
 * <p>这一层是「场景」与「服务端」之间唯一的接线，所以测的重点不是某个端点通不通，
 * 而是三条一旦错了就很难发现的纪律：
 * <ol>
 *   <li><b>每个 ok 响应都必须变成一次时钟校准</b>。漏了的表现是倒计时慢慢漂移，
 *       而漂移要几小时才看得出来，届时没人会怀疑到某个端点没校准</li>
 *   <li><b>变更请求必须带 requestId，且 NetModule 用的就是这一个</b>。
 *       两处各生成一个的后果是重试时服务端去重失效 —— 那是刷奖励级别的漏洞</li>
 *   <li><b>业务失败绝不写 Store</b>。半写状态会让 UI 显示一份服务端并不承认的数据</li>
 * </ol>
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { GameApi } from '../assets/scripts/game/session/GameApi'
import type { GameApiDeps } from '../assets/scripts/game/session/GameApi'
import { NetModule } from '../assets/scripts/net/NetModule'
import type { ApiEnvelope, NetConfig, NetDeps } from '../assets/scripts/net/NetModule'
import type { HttpResponse, HttpTransport } from '../assets/scripts/net/NetTransport'
import { Store } from '../assets/scripts/game/store/Store'
import { TimeSync } from '../assets/scripts/core/TimeSync'
import { Prng } from '../assets/scripts/core/Prng'
import { exileSnapshot, resetWorld, worldModel, worldRequester } from '../assets/scripts/game/world/WorldContext'
import type { WorldLayout } from '../assets/scripts/game/world/WorldViewModel'

/** 服务端随 MarchListResp 下发的布局参数（夹具默认值）。改它不该影响客户端的任何行为 —— 值只从响应来。 */
const LAYOUT: WorldLayout = { worldSize: 512, chunkSize: 32, maxChunks: 9 }

class FakeHttp implements HttpTransport {
  readonly calls: Array<{ method: 'GET' | 'POST'; url: string; body: string }> = []
  script: Array<HttpResponse | Error> = []
  /** 模拟传输耗时。不推进时钟的话 rtt 恒为 0，TimeSync 的 rtt/2 补偿就永远测不到 */
  latencyMs = 0
  private readonly clock: { now: number }

  constructor(clock: { now: number }) {
    this.clock = clock
  }

  post(url: string, bodyText: string, headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    return this.record('POST', url, bodyText, headers)
  }

  get(url: string, headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    return this.record('GET', url, '', headers)
  }

  private async record(method: 'GET' | 'POST', url: string, bodyText: string,
                       _headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    this.calls.push({ method, url, body: bodyText })
    this.clock.now += this.latencyMs
    const index = Math.min(this.calls.length - 1, this.script.length - 1)
    const step = this.script[index]
    if (step === undefined) {
      throw new Error('FakeHttp 未配置 script')
    }
    if (step instanceof Error) {
      throw step
    }
    return step
  }

  lastUrl(): string {
    const last = this.calls[this.calls.length - 1]
    assert.ok(last !== undefined, '尚未发起任何请求')
    return last.url
  }

  lastBody(): Record<string, unknown> {
    const last = this.calls[this.calls.length - 1]
    assert.ok(last !== undefined, '尚未发起任何请求')
    return JSON.parse(last.body) as Record<string, unknown>
  }
}

function envelope<T>(data: T | null, serverNow = 5_000, code = 0, msg = '成功'): HttpResponse {
  const body: ApiEnvelope<T> = { code, msg, data, traceId: 'trace-server', serverNow, detail: null }
  return { status: 200, bodyText: JSON.stringify(body) }
}

interface Harness {
  api: GameApi
  http: FakeHttp
  store: Store
  timeSync: TimeSync
  clock: { now: number }
}

function createHarness(): Harness {
  resetWorld()
  const store = new Store()
  const clock = { now: 1_000 }
  const http = new FakeHttp(clock)
  const timeSync = new TimeSync({ alphaFixed: 3000, jitterFactorFixed: 30000, initialBestRttMs: 200 })
  let seq = 0

  const config: NetConfig = {
    baseUrl: 'https://game.test',
    wsUrl: 'wss://game.test/ws',
    maxRetryAttempts: 2,
    retryBaseDelayMs: 10,
    retryMaxDelayMs: 40,
    offlineQueueMax: 5,
    requestTimeoutMs: 1000,
  }
  const deps: NetDeps = {
    http,
    socketFactory: () => {
      throw new Error('本测试不连 WebSocket')
    },
    now: () => clock.now,
    delay: async (ms) => {
      clock.now += ms
    },
    rng: Prng.of(1),
    newRequestId: () => `req-${++seq}`,
    newTraceId: () => `trace-${seq}`,
  }
  const apiDeps: GameApiDeps = {
    net: new NetModule(config, deps),
    store,
    timeSync,
    now: () => clock.now,
    newRequestId: () => `req-${++seq}`,
  }
  return { api: new GameApi(apiDeps), http, store, timeSync, clock }
}

// ---------- 时钟校准 ----------

test('每个 ok 响应都喂一次 TimeSync，rtt 与 rawOffset 都用本次请求的实测值', async () => {
  const { api, http, timeSync } = createHarness()
  assert.equal(timeSync.acceptedSamples(), 0)

  http.latencyMs = 60
  http.script = [envelope({ current: 80 }, 5_000)]
  const outcome = await api.staminaView()

  assert.equal(outcome.kind, 'ok')
  assert.equal(timeSync.acceptedSamples(), 1, '一次响应就是一次免费校准，漏掉的表现为倒计时慢慢漂移')
  // rawOffset = serverNow - sentAt = 5000 - 1000 = 4000；TimeSync 扣除 rtt/2 = 30 做单程延迟补偿。
  // 不补偿的话客户端时间会系统性超前，表现为「倒计时结束了但服务端说还没结束」
  assert.equal(timeSync.offsetMs(), 3_970)
})

test('业务失败与网络失败都不产生校准样本：那两种响应里的 serverNow 不可信或不存在', async () => {
  const { api, http, timeSync } = createHarness()
  http.script = [envelope(null, 0, 4002, '参数非法')]
  const biz = await api.staminaView()
  assert.equal(biz.kind, 'biz')
  assert.equal(timeSync.acceptedSamples(), 0)

  http.script = [new Error('连接被重置')]
  const network = await api.staminaView()
  assert.equal(network.kind, 'network')
  assert.equal(timeSync.acceptedSamples(), 0)
})

// ---------- 幂等键 ----------

test('变更请求自动注入 requestId，且 NetModule 用的就是同一个（两处各生成一个会让服务端去重失效）', async () => {
  const { api, http } = createHarness()
  http.script = [envelope({ reportId: 'r1' })]
  const outcome = await api.stageChallenge({ stageId: 'stage_01_01', units: [], heroes: null })
  assert.equal(outcome.kind, 'ok')
  const body = http.lastBody()
  assert.equal(typeof body.requestId, 'string')
  assert.ok((body.requestId as string).length > 0)
  assert.equal(body.stageId, 'stage_01_01')
  assert.equal(http.calls[0]?.method, 'POST')
})

test('读请求用 GET 且不带 body', async () => {
  const { api, http } = createHarness()
  http.script = [envelope({ stamina: 1, stages: [], serverNow: 0 })]
  await api.stageList()
  assert.equal(http.calls[0]?.method, 'GET')
  assert.equal(http.lastUrl(), 'https://game.test/stage/list')
})

// ---------- 端点路径与查询参数 ----------

test('端点路径与 Controller 一致（这几条最容易写错，错了就是 404）', async () => {
  const { api, http } = createHarness()
  http.script = [envelope({})]

  await api.staminaView()
  assert.equal(http.lastUrl(), 'https://game.test/stamina', 'StaminaController 的 view 是裸 @GetMapping')

  await api.bagList(null)
  assert.equal(http.lastUrl(), 'https://game.test/bag/list',
    'BagController 没有类级 @RequestMapping，路径是绝对的')

  await api.bagList('CHEST')
  assert.equal(http.lastUrl(), 'https://game.test/bag/list?type=CHEST')

  await api.battleReport('r-1')
  assert.equal(http.lastUrl(), 'https://game.test/battle/report?reportId=r-1')

  await api.gachaProbability('pool_hero_std')
  assert.equal(http.lastUrl(), 'https://game.test/gacha/probability?poolId=pool_hero_std')

  await api.resourceDetail()
  assert.equal(http.lastUrl(), 'https://game.test/resource/detail')

  await api.battleReports()
  assert.equal(http.lastUrl(), 'https://game.test/battle/reports')
})

test('加速类道具的目标 id 走 /item/use；开箱走 /item/openBatch', async () => {
  const { api, http } = createHarness()
  http.script = [envelope({})]
  await api.itemUse({ itemId: 'item_speed_60m', count: 1, targetId: 'b1' })
  assert.equal(http.lastUrl(), 'https://game.test/item/use')
  await api.itemOpenBatch({ itemId: 'chest_01', count: 10 })
  assert.equal(http.lastUrl(), 'https://game.test/item/openBatch')
})

// ---------- Store 写入 ----------

test('cityList 成功后整体替换 Store.resources；业务失败时一个字都不改', async () => {
  const { api, http, store } = createHarness()
  const resources = {
    WOOD: { current: 900, cap: 1200, protectedAmount: 300, perHour: 120, lastSettle: 4_000 },
  }
  http.script = [envelope({ buildings: [], queues: { used: 0, available: 2, max: 4 }, resources, serverNow: 5_000 })]
  const ok = await api.cityList()
  assert.equal(ok.kind, 'ok')
  assert.deepEqual(store.getState().resources, resources)
  assert.equal(store.getState().serverNow, 5_000)

  const before = store.getState().resources
  http.script = [envelope(null, 0, 5001, '城内数据不可用')]
  const failed = await api.cityList()
  assert.equal(failed.kind, 'biz')
  assert.equal(store.getState().resources, before,
    '半写状态会让 UI 显示一份服务端并不承认的数据')
})

test('playerPower 成功后写入 Store.power（战力明细面板与顶部横幅都读它）', async () => {
  const { api, http, store } = createHarness()
  const power = { displayPower: 12000, matchPower: 11000, peakPower: 13000 }
  http.script = [envelope({
    power, currentMatchPower: 11000, peakMemoryFloor: 10400,
    breakdown: { building: 1, troops: 1, heroes: 1, tech: 1, equipment: 1 },
    serverNow: 6_000,
  })]
  const outcome = await api.playerPower()
  assert.equal(outcome.kind, 'ok')
  assert.deepEqual(store.getState().power, power)
  assert.equal(store.getState().serverNow, 6_000)
})

// ---------- 世界模型接线（B07） ----------

test('enterWorld 用 MarchListResp.home 建世界模型并绑定 requester；leaveWorld 解绑', async () => {
  const { api, http } = createHarness()
  assert.equal(worldModel(), null)
  assert.equal(worldRequester(), null)

  http.script = [envelope({ marches: [], home: { x: 200, y: 160 }, maxConcurrent: 3, ...LAYOUT, serverNow: 5_000 })]
  const outcome = await api.enterWorld()
  assert.equal(outcome.kind, 'ok')

  const model = worldModel()
  assert.notEqual(model, null)
  assert.deepEqual(model?.center(), { x: 200, y: 160 }, '视野初始中心是家，不是 (0,0)')
  assert.notEqual(worldRequester(), null)

  api.leaveWorld()
  assert.equal(worldRequester(), null, '不解绑的话旧账号的响应会写进新账号的地图')
})

test('enterWorld 采用响应里的布局参数：响应给 256/16，模型就是 256/16（客户端不再镜像 512/32/9）', async () => {
  const { api, http } = createHarness()
  // 刻意给一组与「镜像默认值」不同的布局（256 % 16 === 0，校验通过）：
  // 若实现又回去读客户端常数，这条会红 —— 那是这份用例存在的唯一理由
  http.script = [envelope({
    marches: [], home: { x: 100, y: 100 }, maxConcurrent: 3,
    worldSize: 256, chunkSize: 16, maxChunks: 9, serverNow: 5_000,
  })]

  const outcome = await api.enterWorld()
  assert.equal(outcome.kind, 'ok')

  const model = worldModel()
  assert.equal(model?.worldSize, 256, 'worldSize 必须取自响应，而不是客户端镜像的 512')
  assert.equal(model?.chunkSize, 16, 'chunkSize 必须取自响应')
})

test('enterWorld 失败时不建世界模型：场景据此画「未连接」而不是一张假地图', async () => {
  const { api, http } = createHarness()
  http.script = [envelope(null, 0, 5001, '行军数据不可用')]
  const outcome = await api.enterWorld()
  assert.equal(outcome.kind, 'biz')
  assert.equal(worldModel(), null)
  assert.equal(worldRequester(), null)
})

test('doExile 成功：地图中心搬到新家、冷却与免战落地，请求打的是 /world/exile 且幂等键由适配层注入', async () => {
  const { api, http } = createHarness()
  // FakeHttp 按累计请求数取脚本，所以一个用例里的每一次请求都要提前排好
  http.script = [
    envelope({ marches: [], home: { x: 48, y: 48 }, maxConcurrent: 3, ...LAYOUT, serverNow: 5_000 }),
    envelope({
      coord: { x: 300, y: 220 }, peaceUntil: 90_000, nextExileAt: 80_000, seed: 7, serverNow: 10_000,
    }),
    // doExile 之后必然重拉一次列表：按钮的每一个输入都来自它
    envelope({
      marches: [], home: { x: 300, y: 220 }, maxConcurrent: 3, ...LAYOUT, serverNow: 11_000,
      peaceUntil: 90_000, nextExileAt: 80_000,
    }),
  ]
  await api.enterWorld()
  const outcome = await api.doExile()

  assert.equal(outcome.kind, 'ok')
  assert.deepEqual(worldModel()?.center(), { x: 300, y: 220 },
    '搬完家地图还停在旧址，玩家的第一反应是「没成功」再点一次')
  assert.equal(exileSnapshot().nextExileAt, 80_000)
  assert.equal(exileSnapshot().peaceUntil, 90_000)
  assert.equal(exileSnapshot().troopsAway, 0)

  const exileCall = http.calls.find(c => c.url.endsWith('/world/exile'))
  assert.notEqual(exileCall, undefined, '按钮必须真的打到一个写端点上')
  assert.match(JSON.parse(exileCall?.body ?? '{}').requestId, /^req-/,
    '迁城改的是世界坐标，重放等于白送一次逃生，幂等键必须由适配层注入')
})

test('doExile 被服务端否决：不许偷偷搬家，但必须重拉列表把按钮纠正过来', async () => {
  const { api, http } = createHarness()
  http.script = [
    envelope({ marches: [], home: { x: 48, y: 48 }, maxConcurrent: 3, ...LAYOUT, serverNow: 5_000 }),
    envelope(null, 0, 6011, '流亡迁城冷却中'),
    envelope({
      marches: [], home: { x: 48, y: 48 }, maxConcurrent: 3, ...LAYOUT, serverNow: 12_000,
      nextExileAt: 200_000, peaceUntil: null,
    }),
  ]
  await api.enterWorld()
  const outcome = await api.doExile()

  assert.equal(outcome.kind, 'biz')
  assert.deepEqual(worldModel()?.center(), { x: 48, y: 48 }, '被拒绝的那一次什么都不许改')
  assert.equal(exileSnapshot().nextExileAt, 200_000,
    '按钮的输入全来自列表下发；失败后不刷新就会一直显示「可以迁」而每次都回同样的错误')
})

test('worldViewport 的响应喂进世界模型：块进缓存、迷雾与探索落地', async () => {
  const { api, http } = createHarness()
  http.script = [envelope({ marches: [], home: { x: 48, y: 48 }, maxConcurrent: 3, ...LAYOUT, serverNow: 5_000 })]
  await api.enterWorld()
  const model = worldModel()
  assert.notEqual(model, null)

  const req = model?.nextRequest()
  assert.ok(req !== undefined && req !== null, '进入世界后首次必须要求拉取视野')
  const keys = [...(model?.chunkKeys() ?? [])]
  http.script = [envelope({
    serverNow: 6_000,
    chunks: [{
      key: '1:1', version: 3, truncated: false,
      entities: [{
        id: 'm1', type: 'MONSTER', x: 48, y: 48, level: 2, ownerName: null, allianceTag: null,
        marchStatus: null, resourceType: null, load: null,
      }],
    }],
    staleChunks: [],
    exploredChunks: keys,
    fogChunks: [],
  })]
  const outcome = await api.worldViewport(req)
  assert.equal(outcome.kind, 'ok')
  assert.equal(http.lastUrl(), 'https://game.test/world/viewport')
  assert.equal(model?.entityCount(), 1)
  assert.equal(model?.frame(6_000).tiles.find((tile) => tile.key === '1:1')?.entities.length, 1)
})

test('marches 的响应喂进世界模型，行军出现在渲染帧里', async () => {
  const { api, http } = createHarness()
  http.script = [envelope({ marches: [], home: { x: 48, y: 48 }, maxConcurrent: 3, ...LAYOUT, serverNow: 5_000 })]
  await api.enterWorld()

  http.script = [envelope({
    marches: [{
      marchId: 'x1', from: { x: 48, y: 48 }, to: { x: 148, y: 48 }, status: 'MARCHING',
      targetType: 'MONSTER', targetId: null, action: 'ATTACK',
      startAt: 0, arriveAt: 10_000, returnStartAt: null, returnArriveAt: null,
      units: [], heroes: [], load: 0, loadCap: 100, teamSpeed: 10,
      position: { x: 48, y: 48 }, progressFixed: 0, gatherFinishAt: null, serverNow: 5_000,
    }],
    home: { x: 48, y: 48 }, maxConcurrent: 3, ...LAYOUT, serverNow: 5_000,
  })]
  const outcome = await api.marches()
  assert.equal(outcome.kind, 'ok')
  const marches = worldModel()?.frame(5_000).marches ?? []
  assert.equal(marches.length, 1)
  assert.equal(marches[0]?.marchId, 'x1')
})

test('doRecall 调 /world/recall、刷新列表，并把返程倒计时交给场景', async () => {
  const { api, http } = createHarness()
  http.script = [envelope({ marches: [], home: { x: 48, y: 48 }, maxConcurrent: 3, ...LAYOUT, serverNow: 5_000 })]
  await api.enterWorld()
  http.calls.length = 0
  const returning = {
    marchId: 'x1', from: { x: 48, y: 48 }, to: { x: 148, y: 48 }, status: 'RETURNING',
    targetType: 'MONSTER', targetId: null, action: 'ATTACK',
    startAt: 0, arriveAt: 10_000, returnStartAt: 6_000, returnArriveAt: 18_000,
    units: [], heroes: [], load: 0, loadCap: 100, teamSpeed: 10,
    position: { x: 98, y: 48 }, progressFixed: 5_000, gatherFinishAt: null, serverNow: 6_000,
  }
  http.script = [
    envelope({ march: returning, returnArriveAt: 18_000, returnSeconds: 12, serverNow: 6_000 }),
    envelope({
      marches: [returning], home: { x: 48, y: 48 }, maxConcurrent: 3, ...LAYOUT, serverNow: 6_000,
    }),
  ]

  const result = await api.doRecall('x1')

  assert.equal(result.ok, true)
  assert.match(result.message, /12 秒后到家/)
  const call = http.calls.find(entry => entry.url.endsWith('/world/recall'))
  assert.equal(JSON.parse(call?.body ?? '{}').marchId, 'x1')
  assert.equal(worldModel()?.frame(6_000).marches[0]?.status, 'RETURNING')
})

test('doCollectGather 调 /world/collectGather，并把结算资源显示成可读文案', async () => {
  const { api, http } = createHarness()
  http.script = [envelope({ marches: [], home: { x: 48, y: 48 }, maxConcurrent: 3, ...LAYOUT, serverNow: 5_000 })]
  await api.enterWorld()
  http.calls.length = 0
  const returning = {
    marchId: 'g1', from: { x: 48, y: 48 }, to: { x: 68, y: 48 }, status: 'RETURNING',
    targetType: 'RESOURCE', targetId: null, action: 'GATHER',
    startAt: 0, arriveAt: 2_000, returnStartAt: 6_000, returnArriveAt: 16_000,
    units: [], heroes: [], load: 300, loadCap: 300, teamSpeed: 10,
    position: { x: 68, y: 48 }, progressFixed: 0, gatherFinishAt: null, serverNow: 6_000,
  }
  http.script = [
    envelope({
      collected: [{ resourceType: 'WOOD', amount: 300 }],
      returnArriveAt: 16_000, march: returning, serverNow: 6_000,
    }),
    envelope({
      marches: [returning], home: { x: 48, y: 48 }, maxConcurrent: 3, ...LAYOUT, serverNow: 6_000,
    }),
  ]

  const result = await api.doCollectGather('g1')

  assert.equal(result.ok, true)
  assert.match(result.message, /WOOD ×300/)
  const call = http.calls.find(entry => entry.url.endsWith('/world/collectGather'))
  assert.equal(JSON.parse(call?.body ?? '{}').marchId, 'g1')
})

test('socialReddot 绑定 /social/reddot：整棵树原样返回给组合根消费', async () => {
  const { api, http } = createHarness()
  http.script = [envelope({
    nodes: [
      { key: 'social', lit: true, children: [
        { key: 'social/help', lit: true, children: [] },
      ] },
    ],
    leafCount: 4,
    serverNow: 5_000,
  })]

  const outcome = await api.socialReddot()

  assert.equal(outcome.kind, 'ok')
  assert.equal(http.lastUrl(), 'https://game.test/social/reddot')
  if (outcome.kind === 'ok') {
    assert.equal(outcome.data.nodes[0]?.key, 'social')
    assert.equal(outcome.data.nodes[0]?.children[0]?.lit, true)
  }
})

// ---------- 离线队列策略 ----------

test('变更请求默认不入离线队列：断网时如实失败，而不是几小时后悄悄执行', async () => {
  const { api, http } = createHarness()
  // 先用一个读请求把 online 打成 false（GET 会重试到上限后判定断网）
  http.script = [new Error('连接被重置')]
  await api.staminaView()

  const outcome = await api.gachaDraw({ poolId: 'pool_hero_std', count: 1 })
  assert.equal(outcome.kind, 'network')
  assert.equal(outcome.queued, false,
    '抽卡会扣资源：三小时前点的那一次在网络恢复后悄悄执行，玩家看到的是「钻石少了但不记得抽过」')
})
