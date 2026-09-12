/**
 * 职责：NetModule 单测 —— 覆盖 B01 的四项能力：重试退避、requestId 幂等、WebSocket 心跳/重连、离线队列。
 * 依赖：node:test / node:assert；传输层用 Fake 注入，不碰真实网络与真实定时器（重连除外，用 1ms 延迟）。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { NetModule } from '../assets/scripts/net/NetModule'
import type { ApiEnvelope, NetConfig, NetDeps } from '../assets/scripts/net/NetModule'
import type { HttpResponse, HttpTransport, SocketCallbacks, SocketTransport } from '../assets/scripts/net/NetTransport'
import { Prng } from '../assets/scripts/core/Prng'
import { gameBus } from '../assets/scripts/core/EventBus'

// ---------- Fakes ----------

class FakeHttp implements HttpTransport {
  readonly calls: Array<{ method: 'GET' | 'POST'; url: string; body: string; headers: Record<string, string> }> = []
  /** 每次调用依次返回的结果；元素为 HttpResponse 或 Error（表示网络失败）。用完则重复最后一项。 */
  script: Array<HttpResponse | Error> = []

  post(url: string, bodyText: string, headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    return this.record('POST', url, bodyText, headers)
  }

  get(url: string, headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    return this.record('GET', url, '', headers)
  }

  /**
   * GET 与 POST 共用同一条 script 索引：两者在 NetModule 里走的是同一个 sendWithRetry，
   * 分开记会让「第几次尝试」在两个计数器上各算一遍，重试次数的断言就测不准了。
   */
  private async record(method: 'GET' | 'POST', url: string, bodyText: string,
                       headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    this.calls.push({ method, url, body: bodyText, headers: { ...headers } })
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

  get callCount(): number {
    return this.calls.length
  }

  lastBody(): Record<string, unknown> {
    const last = this.calls[this.calls.length - 1]
    assert.ok(last !== undefined, '尚未发起任何请求')
    return JSON.parse(last.body) as Record<string, unknown>
  }

  lastUrl(): string {
    const last = this.calls[this.calls.length - 1]
    assert.ok(last !== undefined, '尚未发起任何请求')
    return last.url
  }
}

class FakeSocket implements SocketTransport {
  static instances: FakeSocket[] = []
  open = false
  readonly sent: string[] = []
  private callbacks: SocketCallbacks | null = null

  constructor(readonly url: string) {
    FakeSocket.instances.push(this)
  }

  get isOpen(): boolean {
    return this.open
  }

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

  close(): void {
    this.open = false
  }

  /** 测试驱动：模拟服务端完成握手。 */
  simulateOpen(): void {
    this.open = true
    this.callbacks?.onOpen()
  }

  simulateMessage(payload: object): void {
    this.callbacks?.onMessage(JSON.stringify(payload))
  }

  /** 直接投递原始文本，用于模拟服务端发来的非法 JSON。 */
  simulateRaw(text: string): void {
    this.callbacks?.onMessage(text)
  }

  simulateClose(reason = '测试关闭'): void {
    this.open = false
    this.callbacks?.onClose(reason)
  }
}

function envelope<T>(data: T | null, code = 0, msg = '成功'): HttpResponse {
  const body: ApiEnvelope<T> = {
    code,
    msg,
    data,
    traceId: 'trace-server',
    serverNow: 1_788_000_000_000,
    detail: null,
  }
  return { status: 200, bodyText: JSON.stringify(body) }
}

interface Harness {
  net: NetModule
  http: FakeHttp
  delays: number[]
  clock: { now: number }
}

function createHarness(overrides: Partial<NetConfig> = {}): Harness {
  FakeSocket.instances = []
  const http = new FakeHttp()
  const delays: number[] = []
  const clock = { now: 1_000 }
  let requestSeq = 0

  const config: NetConfig = {
    baseUrl: 'https://game.test',
    wsUrl: 'wss://game.test/ws',
    maxRetryAttempts: 3,
    retryBaseDelayMs: 500,
    retryMaxDelayMs: 8000,
    offlineQueueMax: 5,
    requestTimeoutMs: 5000,
    ...overrides,
  }
  const deps: NetDeps = {
    http,
    socketFactory: () => new FakeSocket(config.wsUrl),
    now: () => clock.now,
    // 记录而不真的等待：退避时长的正确性靠断言 delays 数组验证
    delay: async (ms) => {
      delays.push(ms)
      clock.now += ms
    },
    rng: Prng.of(20260906),
    newRequestId: () => `req-${++requestSeq}`,
    newTraceId: () => `trace-${++requestSeq}`,
  }
  return { net: new NetModule(config, deps), http, delays, clock }
}

// ---------- HTTP：幂等与重试 ----------

test('成功请求返回 ok，并带上服务端 traceId 与 serverNow', async () => {
  const { net, http } = createHarness()
  http.script = [envelope({ playerId: 'P1' })]

  const outcome = await net.post<{ a: number }, { playerId: string }>('/player/init', { a: 1 }, { idempotent: true })

  assert.equal(outcome.kind, 'ok')
  if (outcome.kind !== 'ok') return
  assert.equal(outcome.data.playerId, 'P1')
  assert.equal(outcome.traceId, 'trace-server')
  assert.equal(outcome.serverNow, 1_788_000_000_000)
})

test('幂等请求会把 requestId 写进 body，供服务端去重', async () => {
  const { net, http } = createHarness()
  http.script = [envelope({ ok: true })]

  await net.post('/x', { foo: 1 }, { idempotent: true })
  assert.equal(http.lastBody().requestId, 'req-1')

  await net.post('/x', { foo: 1 }, { idempotent: true, requestId: 'req-fixed' })
  assert.equal(http.lastBody().requestId, 'req-fixed')
})

test('非幂等请求不带 requestId，且失败后不重试（避免重复副作用）', async () => {
  const { net, http } = createHarness()
  http.script = [new Error('连接被重置')]

  const outcome = await net.post('/x', { foo: 1 }, { idempotent: false })

  assert.equal(outcome.kind, 'network')
  assert.equal(http.callCount, 1, '无幂等键的请求不得重试')
  assert.equal(http.lastBody().requestId, undefined)
})

test('幂等请求在 5xx 时按指数退避重试 max+1 次', async () => {
  const { net, http, delays } = createHarness({ maxRetryAttempts: 3, retryBaseDelayMs: 500, retryMaxDelayMs: 8000 })
  http.script = [{ status: 503, bodyText: '' }]

  const outcome = await net.post('/x', {}, { idempotent: true })

  assert.equal(outcome.kind, 'network')
  assert.equal(http.callCount, 4, '首次 + 3 次重试')
  assert.equal(delays.length, 3)
  // 退避序列约为 500 / 1000 / 2000，叠加 ±25% 抖动
  assert.ok((delays[0] ?? 0) >= 375 && (delays[0] ?? 0) <= 625, `首次退避 ${delays[0]}`)
  assert.ok((delays[1] ?? 0) >= 750 && (delays[1] ?? 0) <= 1250, `二次退避 ${delays[1]}`)
  assert.ok((delays[2] ?? 0) >= 1500 && (delays[2] ?? 0) <= 2500, `三次退避 ${delays[2]}`)
})

test('重试成功即停止，不会把剩余次数用完', async () => {
  const { net, http, delays } = createHarness()
  http.script = [new Error('超时'), envelope({ ok: 1 })]

  const outcome = await net.post<{ n: number }, { ok: number }>('/x', { n: 1 }, { idempotent: true })

  assert.equal(outcome.kind, 'ok')
  assert.equal(http.callCount, 2)
  assert.equal(delays.length, 1)
})

test('退避时长封顶在 retryMaxDelayMs', async () => {
  const { net, http, delays } = createHarness({ maxRetryAttempts: 6, retryBaseDelayMs: 1000, retryMaxDelayMs: 3000 })
  http.script = [new Error('持续失败')]

  await net.post('/x', {}, { idempotent: true })

  assert.equal(http.callCount, 7)
  for (const d of delays) {
    assert.ok(d <= 3000 * 1.25 + 1, `退避 ${d} 超出上限（含抖动）`)
  }
})

test('业务失败返回 biz 且不重试（资源不足不是网络问题，重试无意义）', async () => {
  const { net, http } = createHarness()
  http.script = [envelope(null, 4000, '资源不足')]

  const outcome = await net.post('/x', {}, { idempotent: true })

  assert.equal(outcome.kind, 'biz')
  if (outcome.kind !== 'biz') return
  assert.equal(outcome.code, 4000)
  assert.equal(outcome.msg, '资源不足')
  assert.equal(http.callCount, 1)
})

test('自动携带 token 与 X-Trace-Id 请求头', async () => {
  const { net, http } = createHarness()
  http.script = [envelope({})]
  net.setToken('token-abc')

  await net.post('/x', {}, { idempotent: true })

  const headers = http.calls[0]?.headers ?? {}
  assert.equal(headers.Authorization, 'Bearer token-abc')
  assert.ok((headers['X-Trace-Id'] ?? '').startsWith('trace-'))
  assert.equal(headers['Content-Type'], 'application/json')
})

test('身份头只在绑定玩家之后携带，且与 socket 的 bindPlayer 各管一条通道', async () => {
  const { net, http } = createHarness()
  http.script = [envelope({}), envelope({}), envelope({})]

  await net.get('/x')
  assert.equal(http.calls[0]?.headers['X-Player-Id'], undefined,
    '未登录时不该带身份头 —— 服务端会拒，而拒得对：一个不认识的身份比没有身份更危险')

  net.setPlayer('P1')
  await net.get('/x')
  assert.equal(http.calls[1]?.headers['X-Player-Id'], 'P1')

  // 没有 socket 时 bindPlayer 只会返回 false，但 HTTP 身份依然有效。
  // 两者若合并成一个动作，表现就是「没连上 WebSocket ⇒ 之后每个请求都 400」
  assert.equal(net.bindPlayer('P1'), false)
  await net.get('/x')
  assert.equal(http.calls[2]?.headers['X-Player-Id'], 'P1')
})

test('lastTraceId 反映最近一次请求真正带出去的 id：编一个假 id 比空串更坏', async () => {
  const { net, http } = createHarness()
  http.script = [envelope({}), envelope({})]
  assert.equal(net.lastTraceId(), '', '还没发过请求就是空串——"崩在启动阶段"本身就是信息')

  await net.get('/x')
  const first = http.calls[0]?.headers['X-Trace-Id']
  assert.equal(net.lastTraceId(), first)

  await net.get('/x')
  assert.equal(net.lastTraceId(), http.calls[1]?.headers['X-Trace-Id'])
  assert.notEqual(net.lastTraceId(), first, '拿到的必须是"最后一条"而不是第一条')
})

test('响应体不是合法 JSON 时返回 network 失败，不抛异常', async () => {
  const { net, http } = createHarness()
  http.script = [{ status: 200, bodyText: '<html>网关错误页</html>' }]

  const outcome = await net.post('/x', {}, { idempotent: true })
  assert.equal(outcome.kind, 'network')
  if (outcome.kind === 'network') {
    assert.match(outcome.message, /不是合法 JSON/)
  }
})

test('每次成功响应都发出 serverTimeHint，等于免费获得一次时钟校准', async () => {
  const { net, http } = createHarness()
  http.script = [envelope({})]
  const hints: number[] = []
  const off = gameBus.on('serverTimeHint', (h) => hints.push(h.serverNow))

  await net.post('/x', {}, { idempotent: true })
  off()
  assert.deepEqual(hints, [1_788_000_000_000])
})

// ---------- 离线队列 ----------

test('断网时幂等请求入队，恢复后按入队顺序重放', async () => {
  const { net, http } = createHarness()
  // 先让一次请求彻底失败，把 online 打成 false
  http.script = [new Error('断网')]
  await net.post('/warmup', {}, { idempotent: true })
  assert.equal(net.isOnline(), false)

  const urls: string[] = []
  http.post = async (url) => {
    urls.push(url)
    return envelope({})
  }

  const first = await net.post('/a', { i: 1 }, { idempotent: true, queueWhenOffline: true })
  const second = await net.post('/b', { i: 2 }, { idempotent: true, queueWhenOffline: true })

  assert.equal(first.kind, 'network')
  if (first.kind === 'network') assert.equal(first.queued, true)
  assert.equal(second.kind, 'network')
  assert.equal(net.pendingCount(), 2)
  assert.equal(urls.length, 0, '离线期间不应真的发出请求')

  const replayed = await net.replayOfflineQueue()
  assert.equal(replayed, 2)
  assert.deepEqual(urls, ['https://game.test/a', 'https://game.test/b'], '必须按入队顺序重放')
  assert.equal(net.pendingCount(), 0)
})

test('非幂等请求不允许入队：重放会产生重复副作用', async () => {
  const { net, http } = createHarness()
  http.script = [new Error('断网')]
  await net.post('/warmup', {}, { idempotent: true })

  await assert.rejects(
    () => net.post('/draw', {}, { idempotent: false, queueWhenOffline: true }),
    /queueWhenOffline 只对幂等请求开放/,
  )
  assert.equal(net.pendingCount(), 0)
})

test('队列超上限时丢最旧的一条，保留玩家刚刚点的那一下', async () => {
  const { net, http } = createHarness({ offlineQueueMax: 2 })
  http.script = [new Error('断网')]
  await net.post('/warmup', {}, { idempotent: true })

  const originalWarn = console.warn
  console.warn = () => undefined
  try {
    await net.post('/a', {}, { idempotent: true, queueWhenOffline: true })
    await net.post('/b', {}, { idempotent: true, queueWhenOffline: true })
    await net.post('/c', {}, { idempotent: true, queueWhenOffline: true })
  } finally {
    console.warn = originalWarn
  }
  assert.equal(net.pendingCount(), 2)

  const urls: string[] = []
  http.post = async (url) => {
    urls.push(url)
    return envelope({})
  }
  await net.replayOfflineQueue()
  assert.deepEqual(urls, ['https://game.test/b', 'https://game.test/c'], '最旧的 /a 应被丢弃')
})

test('重放途中再次断网：请求被塞回队首，不丢失', async () => {
  const { net, http } = createHarness()
  http.script = [new Error('断网')]
  await net.post('/warmup', {}, { idempotent: true })
  await net.post('/a', {}, { idempotent: true, queueWhenOffline: true })

  http.script = [new Error('仍然断网')]
  const replayed = await net.replayOfflineQueue()
  assert.equal(replayed, 0)
  assert.equal(net.pendingCount(), 1)
})

// ---------- WebSocket ----------

test('连接建立时服务端下发心跳间隔，客户端据此启动心跳（不硬编码 30s）', () => {
  const { net } = createHarness()
  const received: number[] = []
  const off = gameBus.on('netConnected', (e) => received.push(e.heartbeatSeconds))

  net.connectSocket()
  const socket = FakeSocket.instances[0]
  assert.ok(socket !== undefined)
  socket.simulateOpen()
  socket.simulateMessage({ type: 'connected', serverNow: 1_788_000_000_000, heartbeatSeconds: 30 })
  off()

  assert.deepEqual(received, [30])
  assert.equal(net.connectionOpen(), true)
  // 必须断开：connected 帧会启动 setInterval 心跳，不清掉会让测试进程无法退出
  net.disconnect()
})

test('业务推送转成 serverPush 事件，未知类型也不会被吞掉', () => {
  const { net } = createHarness()
  const pushes: Array<{ type: string; data: unknown }> = []
  const off = gameBus.on('serverPush', (p) => pushes.push(p))

  net.connectSocket()
  const socket = FakeSocket.instances[0]
  assert.ok(socket !== undefined)
  socket.simulateOpen()
  socket.simulateMessage({ type: 'march_arrived', data: { marchId: 'm1' } })
  socket.simulateMessage({ type: 'city_attacked', data: { from: 'P2' } })
  off()

  assert.deepEqual(pushes, [
    { type: 'march_arrived', data: { marchId: 'm1' } },
    { type: 'city_attacked', data: { from: 'P2' } },
  ])
})

test('非法 JSON 推送被忽略，不断开连接', () => {
  const { net } = createHarness()
  const originalWarn = console.warn
  console.warn = () => undefined
  try {
    net.connectSocket()
    const socket = FakeSocket.instances[0]
    assert.ok(socket !== undefined)
    socket.simulateOpen()
    socket.simulateRaw('这不是 JSON')
    assert.equal(net.connectionOpen(), true)
  } finally {
    console.warn = originalWarn
    net.disconnect()
  }
})

test('bindPlayer 把 playerId 发给服务端，未连接时返回 false', () => {
  const { net } = createHarness()
  assert.equal(net.bindPlayer('P1'), false, '未连接时不应假装发送成功')

  net.connectSocket()
  const socket = FakeSocket.instances[0]
  assert.ok(socket !== undefined)
  socket.simulateOpen()
  assert.equal(net.bindPlayer('P1'), true)
  assert.deepEqual(JSON.parse(socket.sent[0] ?? '{}'), { type: 'bind', playerId: 'P1' })
})

test('断开后发出 netDisconnected 并自动重连，重连成功发出 netReconnected', async () => {
  const { net } = createHarness({ retryBaseDelayMs: 1, retryMaxDelayMs: 2 })
  const events: string[] = []
  const offA = gameBus.on('netDisconnected', () => events.push('disconnected'))
  const offB = gameBus.on('netReconnected', () => events.push('reconnected'))

  net.connectSocket()
  const first = FakeSocket.instances[0]
  assert.ok(first !== undefined)
  first.simulateOpen()
  first.simulateClose('网络中断')

  assert.equal(net.connectionOpen(), false)
  assert.equal(net.isOnline(), false)

  // 重连走真实 setTimeout（延迟 1~2ms），等一小会儿
  await new Promise((resolve) => setTimeout(resolve, 30))
  const second = FakeSocket.instances[1]
  assert.ok(second !== undefined, '应已创建新的连接实例')
  second.simulateOpen()

  offA()
  offB()
  assert.deepEqual(events, ['disconnected', 'reconnected'])
  assert.equal(net.connectionOpen(), true)
})

test('主动 disconnect 不触发自动重连', async () => {
  const { net } = createHarness({ retryBaseDelayMs: 1, retryMaxDelayMs: 2 })
  net.connectSocket()
  const socket = FakeSocket.instances[0]
  assert.ok(socket !== undefined)
  socket.simulateOpen()

  net.disconnect()
  await new Promise((resolve) => setTimeout(resolve, 20))

  assert.equal(FakeSocket.instances.length, 1, '主动断开后不应新建连接')
  assert.equal(net.connectionOpen(), false)
})

test('构造参数校验：退避与队列上限非法时立刻报错', () => {
  const { http } = createHarness()
  const base: NetConfig = {
    baseUrl: '', wsUrl: '', maxRetryAttempts: 3, retryBaseDelayMs: 500,
    retryMaxDelayMs: 8000, offlineQueueMax: 5, requestTimeoutMs: 5000,
  }
  const deps: NetDeps = {
    http,
    socketFactory: () => new FakeSocket(''),
    now: () => 0,
    delay: async () => undefined,
    rng: Prng.of(1),
    newRequestId: () => 'r',
    newTraceId: () => 't',
  }
  assert.throws(() => new NetModule({ ...base, maxRetryAttempts: -1 }, deps), RangeError)
  assert.throws(() => new NetModule({ ...base, retryBaseDelayMs: 0 }, deps), RangeError)
  assert.throws(() => new NetModule({ ...base, retryMaxDelayMs: 100 }, deps), RangeError)
  assert.throws(() => new NetModule({ ...base, offlineQueueMax: 0 }, deps), RangeError)
})

// ---------- GET ----------

test('GET 解析同一个信封，并且不需要幂等键就能重试（读接口无副作用）', async () => {
  const { net, http, delays } = createHarness()
  http.script = [
    new Error('连接被重置'),
    new Error('连接被重置'),
    envelope({ list: [1, 2, 3] }),
  ]
  const outcome = await net.get<{ list: number[] }>('/city/list')
  assert.equal(outcome.kind, 'ok')
  assert.deepEqual(outcome.kind === 'ok' ? outcome.data : null, { list: [1, 2, 3] })
  assert.equal(http.callCount, 3)
  assert.equal(delays.length, 2, '两次失败 ⇒ 两次退避')
  assert.ok(http.calls.every((call) => call.method === 'GET'))
})

test('GET 失败时 outcome.requestId 为 null：内部的可重试标记绝不能泄漏成幂等键', async () => {
  const { net, http } = createHarness({ maxRetryAttempts: 1 })
  http.script = [new Error('连接被重置')]
  const outcome = await net.get('/city/list')
  assert.equal(outcome.kind, 'network')
  assert.equal(outcome.requestId, null,
    '调用方看到非 null 的 requestId 会以为这是个可去重的写请求，从而做出错误的重试决策')
})

test('GET 遇 5xx 会重试，遇 4xx 直接按业务失败返回', async () => {
  const { net, http } = createHarness()
  http.script = [{ status: 503, bodyText: '' }, envelope(null, 4002, '参数非法')]
  const outcome = await net.get('/battle/report', { reportId: 'r1' })
  assert.equal(outcome.kind, 'biz')
  assert.equal(outcome.kind === 'biz' ? outcome.code : -1, 4002)
  assert.equal(http.callCount, 2, '5xx 重试一次后拿到 4xx，不再继续重试')
})

test('GET 不入离线队列：断网时直接失败，重连后重新拉一次就行', async () => {
  const { net, http } = createHarness()
  http.script = [new Error('连接被重置')]

  // 第一步：一个不入队的写请求把 online 打成 false（4 次尝试全失败）
  await net.post('/warmup', {}, { idempotent: true })
  assert.equal(net.isOnline(), false)
  assert.equal(net.pendingCount(), 0)

  // 第二步：断网后的写请求才会真正入队
  await net.post('/gacha/draw', {}, { idempotent: true, queueWhenOffline: true })
  assert.equal(net.pendingCount(), 1)

  // 第三步：GET 照样尝试、照样失败，但绝不入队
  const outcome = await net.get('/city/list')
  assert.equal(outcome.kind, 'network')
  assert.equal(outcome.queued, false, '排队重放一个几小时前的列表毫无意义，还会挤掉真正要重放的写操作')
  assert.equal(net.pendingCount(), 1, '队列里仍然只有那一次抽卡')
})

test('查询串：跳过 null/undefined、编码特殊字符、键按字典序稳定', async () => {
  const { net, http } = createHarness()
  http.script = [envelope({ ok: 1 })]
  await net.get('/bag/list', {
    type: null,
    keyword: 'a&b=c 中文',
    page: 2,
    all: undefined,
  })
  // 键按字典序 ⇒ keyword 在 page 前；null 与 undefined 的键整个消失，
  // 因为服务端 @RequestParam(required=false) 收到空串和收不到参数是两种语义
  assert.equal(http.lastUrl(),
    'https://game.test/bag/list?keyword=a%26b%3Dc%20%E4%B8%AD%E6%96%87&page=2')
})

test('没有查询参数时不追加问号', async () => {
  const { net, http } = createHarness()
  http.script = [envelope({ ok: 1 })]
  await net.get('/army/list')
  assert.equal(http.lastUrl(), 'https://game.test/army/list')
  await net.get('/army/list', { type: null })
  assert.equal(http.lastUrl(), 'https://game.test/army/list', '全是 null 等于没有参数')
})
