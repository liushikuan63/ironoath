/**
 * 职责：客户端的网络层与对时参数由**服务端下发**，不是客户端抄一份 global 表。
 * 依赖：node:test / node:assert + 引擎无关的 net/core 模块。
 *
 * <p><b>为什么要有这条</b>：这些数住在 `global.json`（`NET_RETRY_*` / `NET_OFFLINE_QUEUE_MAX` /
 * `TIME_SYNC_*`），而**客户端没有配置表加载器** —— `global.json` 不进包，
 * 客户端只有生成的类型、没有表数据。写死一份的代价是**与服务端悄悄漂移**，
 * 症状是「谁都没报错」的偏差（与 `TrackPolicy` 随 `/ops/app/version` 下发是同一条理由）。
 *
 * <p><b>为什么是「先发后覆盖」而不是「构造时就用服务端的值」</b>：版本检查**本身就是一个请求**，
 * 而它要用这些参数发出去 ⇒ 鸡生蛋。所以：先用内置默认值把第一个请求发出去，
 * 拿到响应后用服务端的值覆盖，**之后的请求**一律用服务端那份。
 *
 * <p><b>判据只观测真实行为</b>（重试信号里报的 maxAttempts、离线队列满的信号、
 * 对时参数对样本取舍的影响），**不加「只给测试看」的取值器** ——
 * 那种取值器本身就是一个「只有测试在读」的第二份真相。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { NetModule } from '../assets/scripts/net/NetModule'
import { FetchHttpTransport } from '../assets/scripts/net/FetchTransport'
import { TimeSync } from '../assets/scripts/core/TimeSync'
import type { NetDeps, NetworkSignal } from '../assets/scripts/net/NetModule'
import type { HttpTransport, HttpResponse } from '../assets/scripts/net/NetTransport'

/** 总是失败的传输层：逼 NetModule 走满重试、进离线队列。 */
class FailingTransport implements HttpTransport {
  post(_url: string, _bodyText: string, _headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    return Promise.reject(new Error('网络失败（测试用）'))
  }

  get(_url: string, _headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    return Promise.reject(new Error('网络失败（测试用）'))
  }
}

function deps(transport: HttpTransport, signals: NetworkSignal[]): NetDeps {
  return {
    http: transport,
    socketFactory: () => { throw new Error('本用例不连 WebSocket') },
    now: () => 1_000,
    delay: () => Promise.resolve(),
    rng: { nextInt: () => 0, range: () => 10_000 },
    newRequestId: () => 'req-1',
    newTraceId: () => 'trace-1',
    notifyNetwork: (signal: NetworkSignal) => signals.push(signal),
  } as unknown as NetDeps
}

const DEFAULTS = {
  maxRetryAttempts: 3,
  retryBaseDelayMs: 500,
  retryMaxDelayMs: 8_000,
  offlineQueueMax: 50,
  requestTimeoutMs: 5_000,
}

function newNet(signals: NetworkSignal[]): NetModule {
  return new NetModule({
    baseUrl: 'http://localhost:0',
    wsUrl: 'ws://localhost:0',
    ...DEFAULTS,
  }, deps(new FailingTransport(), signals))
}

test('服务端下发的重试次数覆盖客户端的默认值：重试信号里的 maxAttempts 跟着变', async () => {
  const signals: NetworkSignal[] = []
  const net = newNet(signals)

  // 服务端说：最多重试 7 次（客户端默认 3 次）。
  net.applyNetworkTuning({
    maxRetryAttempts: 7,
    retryBaseDelayMs: 250,
    retryMaxDelayMs: 4_000,
    offlineQueueMax: 20,
    requestTimeoutMs: 9_000,
  })

  await net.post('/probe', {}, { idempotent: true }).catch(() => undefined)

  const retry = signals.find(signal => signal.kind === 'retry')
  assert.ok(retry !== undefined, '应当发出过重试信号')
  assert.equal(retry !== undefined && retry.kind === 'retry' ? retry.maxAttempts : -1, 7,
    '重试次数必须用服务端下发的 7，而不是客户端写死的 3')
})

test('服务端下发的离线队列上限覆盖客户端的默认值：队列到上限就丢最旧', async () => {
  const signals: NetworkSignal[] = []
  const net = newNet(signals)

  net.applyNetworkTuning({
    maxRetryAttempts: 3,
    retryBaseDelayMs: 500,
    retryMaxDelayMs: 8_000,
    offlineQueueMax: 1,
    requestTimeoutMs: 5_000,
  })

  // 队列满时 NetModule 会打一行 warn（丢最旧的那个）。这行 warn 就是「上限生效」的可观测证据：
  // 上限 50 时两个请求都进队列、**不会**有这行；上限 1 时第二个请求进来就满了 ⇒ 有这行。
  const warnings: string[] = []
  const originalWarn = console.warn
  console.warn = (message: unknown): void => { warnings.push(String(message)) }
  try {
    // ① 第一个请求真的发出去并失败（重试耗尽）⇒ NetModule 认定「不在线」；
  // ② 之后的请求因为不在线且允许入队 ⇒ 进离线队列（上限 1 ⇒ 第二个就满了、丢最旧）。
  await net.post('/a', {}, { idempotent: true }).catch(() => undefined)
  await net.post('/b', {}, { idempotent: true, queueWhenOffline: true }).catch(() => undefined)
  await net.post('/c', {}, { idempotent: true, queueWhenOffline: true }).catch(() => undefined)
  } finally {
    console.warn = originalWarn
  }

  assert.ok(warnings.some(line => line.includes('离线队列已满')),
    '队列上限必须用服务端下发的 1：上限 50 时两个请求都进队列、不会打这行；上限 1 时第二个就满了')
})

test('服务端下发的坏值被当场拒绝，不默默用坏值继续发请求', () => {
  const signals: NetworkSignal[] = []
  const net = newNet(signals)

  assert.throws(() => net.applyNetworkTuning({
    maxRetryAttempts: 3,
    retryBaseDelayMs: 500,
    retryMaxDelayMs: 8_000,
    offlineQueueMax: 0,
    requestTimeoutMs: 5_000,
  }), /offlineQueueMax/, '队列上限为 0 必须当场抛：默默用坏值的症状是「离线请求永远丢」')
  assert.throws(() => net.applyNetworkTuning({
    maxRetryAttempts: 3,
    retryBaseDelayMs: 500,
    retryMaxDelayMs: 400,
    offlineQueueMax: 50,
    requestTimeoutMs: 5_000,
  }), /退避/, 'max < base 是非法退避，必须当场抛')
  assert.throws(() => net.applyNetworkTuning({
    maxRetryAttempts: 3,
    retryBaseDelayMs: 500,
    retryMaxDelayMs: 8_000,
    offlineQueueMax: 50,
    requestTimeoutMs: 0,
  }), /requestTimeoutMs/, '超时为 0 必须当场抛')
})

test('传输层的超时可被下发覆盖（超时的执行点在这一层，不在 NetModule）', () => {
  const transport = new FetchHttpTransport(5_000)
  // 超时住在传输层（AbortController / wx.request 的 timeout），所以覆盖也必须落在这一层。
  // 判据用「非法值当场抛」这种可观测行为，不加只给测试看的取值器。
  assert.throws(() => new FetchHttpTransport(0), /timeoutMs/)
  assert.doesNotThrow(() => transport.applyTimeout(9_000), '覆盖成合法值必须不抛')
  assert.throws(() => transport.applyTimeout(0), /timeoutMs/, '覆盖成非法值必须当场抛')
})

test('对时参数可被下发覆盖：抖动的剔除倍数变了，样本的取舍跟着变', () => {
  // 抖动剔除倍数决定「RTT 超过历史最佳值的几倍就丢弃这个样本」。
  // 用同一个样本序列跑两个倍数，观测「被接受了几次」—— 这是对时参数**真实**起作用的样子。
  const samples: Array<{ rtt: number; offset: number }> = [
    { rtt: 100, offset: 1_000 },
    { rtt: 400, offset: 1_000 },
    { rtt: 100, offset: 1_000 },
  ]

  const strict = new TimeSync({ alphaFixed: 2_000, jitterFactorFixed: 30_000, initialBestRttMs: 150 })
  strict.applyTimeSyncTuning(2_000, 30_000)
  for (const sample of samples) strict.offer(sample.rtt, sample.offset)

  const loose = new TimeSync({ alphaFixed: 2_000, jitterFactorFixed: 30_000, initialBestRttMs: 150 })
  // 服务端说：抖动 4 倍才丢（客户端默认 3 倍）⇒ 那个 RTT 400 的样本不再被丢。
  loose.applyTimeSyncTuning(2_000, 40_000)
  for (const sample of samples) loose.offer(sample.rtt, sample.offset)

  assert.equal(strict.acceptedSamples(), 2, '3 倍抖动：RTT 400 那个样本被丢 ⇒ 只接受 2 个')
  assert.equal(loose.acceptedSamples(), 3, '4 倍抖动：RTT 400 那个样本被接受 ⇒ 接受 3 个')
  assert.throws(() => loose.applyTimeSyncTuning(0, 40_000), /alpha/)
  assert.throws(() => loose.applyTimeSyncTuning(2_000, 0), /jitterFactor/)
})
