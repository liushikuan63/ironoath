/**
 * 职责：客户端网络模块 —— HTTP 封装、指数退避重试、requestId 幂等、WebSocket 心跳与重连、离线队列。
 * 依赖：net/NetTransport（抽象）、core/EventBus、core/Prng、net/generated/Protocol（生成的契约类型）。
 *
 * B01 四项能力要求全部落在这里：
 * 1. HTTP：自动带 token、统一错误处理、指数退避重试（次数与退避上下界来自配置）
 * 2. WebSocket：心跳（间隔由服务端下发）、断线自动重连、重连期间消息走 HTTP 补拉
 * 3. 请求幂等：关键请求带 requestId，服务端去重
 * 4. 离线队列：断网期间操作入队，恢复后按序重放
 *
 * ⚠️ 铁律 3：本模块不做任何数值判定。它只负责把请求送到、把响应解析成类型安全的结构，
 *   服务端返回什么就是什么，客户端不猜测、不补算、不「乐观修正」业务数值。
 */

import { gameBus } from '../core/EventBus'
import { Prng } from '../core/Prng'
import type { SocketCallbacks, SocketFactory, SocketTransport, HttpTransport, HttpResponse } from './NetTransport'

/** 统一响应封装，与服务端 game-common 的 Result 及 Schema 的 ApiResult 对应。 */
export interface ApiEnvelope<T> {
  readonly code: number
  readonly msg: string
  readonly data: T | null
  readonly traceId: string | null
  readonly serverNow: number
  readonly detail: string | null
}

/** 请求结果。用可辨识联合而不是抛异常：业务失败与网络失败是完全不同的处理方式。 */
export type NetOutcome<T> =
  | { readonly kind: 'ok'; readonly data: T; readonly traceId: string; readonly serverNow: number }
  | { readonly kind: 'biz'; readonly code: number; readonly msg: string; readonly detail: string | null; readonly traceId: string }
  | { readonly kind: 'network'; readonly message: string; readonly queued: boolean; readonly requestId: string | null }

export interface NetConfig {
  /** HTTP 基址，如 https://game.example.com */
  readonly baseUrl: string
  /** WebSocket 地址，如 wss://game.example.com/ws */
  readonly wsUrl: string
  /** 最大重试次数。来源：global.json NET_RETRY_MAX_ATTEMPTS */
  readonly maxRetryAttempts: number
  /** 首次退避时长。来源：global.json NET_RETRY_BASE_DELAY_MS */
  readonly retryBaseDelayMs: number
  /** 退避上限。来源：global.json NET_RETRY_MAX_DELAY_MS */
  readonly retryMaxDelayMs: number
  /** 离线队列上限。来源：global.json NET_OFFLINE_QUEUE_MAX */
  readonly offlineQueueMax: number
  /** 单请求超时。超时视为网络失败，进入重试或离线队列。 */
  readonly requestTimeoutMs: number
}

/**
 * 弱网事件（传输层的事实，不是文案）。
 *
 * <p><b>为什么在 net 里定义而不是在显示层</b>：这三件事（将要重投 / 重投到上限 / 链路又通了）
 * 只有发请求的人知道，让它们从外面猜就等于猜错也没人报错。
 * 而"该对玩家说什么"刻意不在这里 —— 那是 {@code game/network/NetworkNotice} 的判断，
 * 两边各管一层，测试也各测各的。
 */
export interface NetworkRetrySignal {
  readonly kind: 'retry'
  /** 请求路径（如 `/player/init`）。只用于日志与排查，不进玩家可见文案。 */
  readonly path: string
  /** 第几次<b>重投</b>（1 起；首次发出不算）。 */
  readonly attempt: number
  /** 允许的重投上限，来源 `global.NET_RETRY_MAX_ATTEMPTS`。带出来是为了让上层不另存一份这个数。 */
  readonly maxAttempts: number
}

/** 重投到上限仍未接通。不带它就没有一句"别再等了"的话可说。 */
export interface NetworkGivenUpSignal {
  readonly kind: 'givenUp'
  readonly path: string
  readonly attempts: number
}

/** 有请求真的拿到了回应（链路通了）。 */
export interface NetworkRecoveredSignal {
  readonly kind: 'recovered'
}

export type NetworkSignal = NetworkRetrySignal | NetworkGivenUpSignal | NetworkRecoveredSignal

export interface RequestOptions {
  /**
   * 是否携带 requestId 幂等键。
   *
   * 所有会产生副作用的请求（建造、训练、领奖、抽卡）<b>必须</b>为 true：
   * 有了幂等键，重试与离线重放才是安全的 —— 服务端会去重（B00 陷阱 3）。
   */
  readonly idempotent?: boolean
  /**
   * 断网时是否入离线队列。
   *
   * 只有幂等请求才允许入队：非幂等请求重放会产生重复副作用，
   * 那种情况下正确的行为是立刻失败并提示玩家，而不是「假装记下来稍后再发」。
   */
  readonly queueWhenOffline?: boolean
  /** 由调用方指定 requestId（用于跨重试保持同一个键）。缺省时自动生成。 */
  readonly requestId?: string
}

/** 可注入的运行时依赖，让 NetModule 能在 node 里跑单测（不用真定时器、不用真网络）。 */
export interface NetDeps {
  readonly http: HttpTransport
  readonly socketFactory: SocketFactory
  readonly now: () => number
  readonly delay: (ms: number) => Promise<void>
  /** 重试抖动用的随机源。用 Prng 而不是 Math.random，保持全项目随机可复现（铁律 4）。 */
  readonly rng: Prng
  readonly newRequestId: () => string
  readonly newTraceId: () => string
  /**
   * 弱网事件的观察者（可缺省）。缺省时本模块的行为与引入它之前逐字节一致 ——
   * 一个只用来"让人知道在等什么"的出口，不该有资格把请求链路弄坏。
   *
   * <p>存在的理由是 B16 验收 2 的后半句「有超时重试提示」：退避重试早就在跑，
   * 而玩家看不到任何东西，体感就是"卡死"，于是退出重进 —— 弱网下最坏的动作。
   */
  readonly notifyNetwork?: (signal: NetworkSignal) => void
}

/** WebSocket 服务端消息的公共形状。 */
interface SocketMessage {
  readonly type: string
  readonly serverNow?: number
  readonly heartbeatSeconds?: number
  readonly data?: unknown
}

interface QueuedRequest {
  readonly path: string
  readonly bodyText: string
  readonly requestId: string
  readonly enqueuedAt: number
}

const HEADER_TRACE_ID = 'X-Trace-Id'
const HEADER_AUTH = 'Authorization'
const HEADER_CONTENT_TYPE = 'Content-Type'
/**
 * 临时身份头。服务端各 Controller 用 `@RequestHeader` 读它，而 Spring 默认 `required = true` ——
 * 不带就是 400，客户端一个按钮都点不动。B15 换成真实登录后这个头会被 token 取代，
 * 取代的方式就是换掉这里，而不是在每个调用方各拼一次头。
 */
const HEADER_PLAYER = 'X-Player-Id'

export class NetModule {
  private readonly config: NetConfig
  private readonly deps: NetDeps
  private token: string | null = null
  /** HTTP 身份头。null = 尚未登录，此时认证端点会被服务端拒（这是应该的，不是 bug） */
  private playerId: string | null = null
  /** 最近一次请求发出的 traceId。崩溃上报要用它把"崩在哪条链路之后"对上服务端日志。 */
  private lastTraceIdValue = ''
  private socket: SocketTransport | null = null
  private socketOpen = false
  /**
   * 连通性标记。
   *
   * 刻意与 socketOpen 分开：还没调用 connectSocket 时 socketOpen 为 false，
   * 但那不代表断网 —— 若用它判断入队，开局所有请求都会被错误地塞进离线队列永不发出。
   * 本标记由三处驱动：HTTP 成功 → true；HTTP 重试全部失败 → false；WebSocket 开/关同步更新。
   */
  private online = true
  private heartbeatTimer: ReturnType<typeof setTimeout> | null = null
  private heartbeatSeconds = 0
  private reconnectAttempts = 0
  private closedByUser = false
  private disconnectedAt: number | null = null
  private readonly offlineQueue: QueuedRequest[] = []
  private replaying = false

  constructor(config: NetConfig, deps: NetDeps) {
    if (config.maxRetryAttempts < 0) {
      throw new RangeError(`maxRetryAttempts 不得为负：${config.maxRetryAttempts}`)
    }
    if (config.retryBaseDelayMs <= 0 || config.retryMaxDelayMs < config.retryBaseDelayMs) {
      throw new RangeError('退避时长非法：base 必须为正且 max >= base')
    }
    if (config.offlineQueueMax <= 0) {
      throw new RangeError(`offlineQueueMax 必须为正：${config.offlineQueueMax}`)
    }
    this.config = config
    this.deps = deps
  }

  // ---------- 身份 ----------

  /** 设置后续请求自动携带的 token。 */
  setToken(token: string | null): void {
    this.token = token
  }

  /**
   * 设置后续 HTTP 请求自动携带的玩家身份。
   *
   * <p>与 {@link #bindPlayer} 是两件事，别合并：`bindPlayer` 是**往 socket 上申报身份**以便服务端
   * 定向推送，连接没开时它只会返回 false；而这里是本地状态，设上之后每个 HTTP 请求都带，
   * 与 socket 是否在线无关。登录成功时两个都要调，少调这一个的表现是「登录成功却满屏 400」。
   */
  setPlayer(playerId: string | null): void {
    this.playerId = playerId
  }

  /**
   * 设置会话票据（B15 §三）。登录成功后由 {@code GameSession} 调用，
   * 之后每个 HTTP 请求自动带 {@code X-Auth-Token}。
   *
   * <p>与 {@link #setPlayer} 分开是刻意的：票据由服务端签发，客户端只是搬运工；
   * 把两者塞进一个方法会让人以为票据是本地生成的。
   */
  setAuthToken(token: string | null): void {
    this.token = token
  }

  /** 是否已经持有会话票据（启动自检用；不暴露票据本身）。 */
  hasAuthToken(): boolean {
    return this.token !== null && this.token.length > 0
  }

  // ---------- HTTP ----------

  /**
   * 发起一次请求。
   *
   * 重试判定：只有「携带 requestId 的请求」才会在网络失败/5xx 时重试 ——
   * 服务端凭 requestId 去重，重试不会产生第二次副作用。
   * 无幂等键的写请求一旦发出就无法判断服务端是否已执行，重试可能造成重复扣资源，
   * 因此这类请求失败即失败，直接返回 network 结果。
   */
  async post<TReq extends object, TResp>(
    path: string,
    body: TReq,
    options: RequestOptions = {},
  ): Promise<NetOutcome<TResp>> {
    const idempotent = options.idempotent ?? false
    const requestId = idempotent ? options.requestId ?? this.deps.newRequestId() : options.requestId ?? null

    const payload: Record<string, unknown> = { ...(body as Record<string, unknown>) }
    if (requestId !== null) {
      // requestId 放进 body 而不是头：服务端的 PlayerInitReq 等 DTO 直接声明了该字段，
      // 放头里还得在网关层搬运一次，多一处出错的机会
      payload.requestId = requestId
    }
    const bodyText = JSON.stringify(payload)

    if (!this.online && (options.queueWhenOffline ?? false)) {
      if (requestId === null) {
        throw new Error(`queueWhenOffline 只对幂等请求开放：path=${path}`)
      }
      const queued = this.enqueue(path, bodyText, requestId)
      return { kind: 'network', message: '连接不可用，请求已进入离线队列', queued, requestId }
    }

    return this.sendWithRetry<TResp>(path, requestId, requestId !== null,
      (url, headers) => this.deps.http.post(url, bodyText, headers))
  }

  /**
   * 发起一次 GET 请求。
   *
   * <p><b>GET 一律可安全重试</b>，所以不需要幂等键：读接口没有副作用。
   * 本项目里有几个 GET 是「带副作用的读」（GET /city/list 与 GET /army/list 会顺带收割
   * 到点的升级/训练 —— 惰性结算，服务端不跑定时器），但收割本身是幂等的：
   * 到点就收，重复调用第二次已经无可收之物。所以按普通 GET 处理是对的。
   *
   * <p><b>GET 不入离线队列</b>：队列存的是「玩家点了但没发出去」的写操作，
   * 而读操作重连后重新拉一次就行 —— 排队重放一个几小时前的列表毫无意义，
   * 还会把队列容量占掉，挤掉真正需要重放的那次抽卡。
   *
   * @param query 查询串参数。值为 null/undefined 的键会被跳过（而不是发一个空值出去）：
   *              服务端的 @RequestParam(required=false) 收到空串和收不到参数是两种语义
   */
  async get<TResp>(
    path: string,
    query?: Readonly<Record<string, string | number | boolean | null | undefined>>,
  ): Promise<NetOutcome<TResp>> {
    const fullPath = appendQuery(path, query)
    return this.sendWithRetry<TResp>(fullPath, null, true,
      (url, headers) => this.deps.http.get(url, headers))
  }

  /**
   * 发一次请求并按需重试。
   *
   * <p>重试判定由 {@code retryable} 显式给出，而不是从 requestId 推断：
   * 写请求只有在带幂等键时才能重试（服务端凭 requestId 去重，重试不会产生第二次副作用；
   * 无幂等键的写请求一旦发出就无法判断服务端是否已执行，重试可能造成重复扣资源，
   * 因此失败即失败），而 GET 没有 requestId 却天然可重试。
   * 用哨兵字符串冒充 requestId 会让它泄漏进 NetOutcome，调用方会以为那是个真的幂等键。
   *
   * @param send 怎么发。GET 与 POST 只有这一步不同，退避、信封解析、online 标记全部共用
   */
  private async sendWithRetry<TResp>(
    path: string,
    requestId: string | null,
    retryable: boolean,
    send: (url: string, headers: Readonly<Record<string, string>>) => Promise<HttpResponse>,
  ): Promise<NetOutcome<TResp>> {
    const url = this.config.baseUrl + path
    const attempts = retryable ? this.config.maxRetryAttempts + 1 : 1
    let lastError = '未知网络错误'

    for (let attempt = 0; attempt < attempts; attempt++) {
      if (attempt > 0) {
        this.emit({ kind: 'retry', path, attempt, maxAttempts: attempts - 1 })
        await this.deps.delay(this.backoffMs(attempt))
      }
      const traceId = this.deps.newTraceId()
      // 崩溃上报要带"最后一条链路"的 id（见 CrashReportReq 的说明）：真正崩掉的那一帧
      // 常常就在某个请求之后，而除了这里没谁知道那次请求用的是哪个 traceId
      this.lastTraceIdValue = traceId
      try {
        const response = await send(url, this.headers(traceId))
        // 拿到任何响应都说明链路是通的（哪怕业务失败），据此纠正 online 标记
        this.online = true
        if (response.status >= 500) {
          // 5xx 是服务端故障，可以重试（有幂等键时安全）
          lastError = `服务端故障 HTTP ${response.status}`
          continue
        }
        // 服务端答了（哪怕是一个业务拒绝）= 链路通了，那句"正在重试"该收回去。
        // 刻意不由超时器到点来清 —— 那会出现"提示自己消失了但还没通"
        this.emit({ kind: 'recovered' })
        return this.parseEnvelope<TResp>(response.status, response.bodyText, traceId)
      } catch (error) {
        lastError = describeError(error)
      }
    }
    this.online = false
    // 只给"确实重投过"的请求发这条：一次性失败的写请求报"重试 0 次仍未接通"是句胡话，
    // 它该由 AppRoot 按面板报具体原因（玩家要改的是操作，不是等网络）
    if (retryable && attempts > 1) {
      this.emit({ kind: 'givenUp', path, attempts: attempts - 1 })
    }
    return { kind: 'network', message: lastError, queued: false, requestId }
  }

  /**
   * 发一条弱网事件。吞掉观察者抛出的异常。
   *
   * <p>这不是防御性装饰：观察者落在场景层，而场景节点会在切场景时被销毁，
   * 一次"更新一行提示"的失败绝不能把玩家的请求链路一起带走 ——
   * 尤其不能发生在弱网下（那时每一次成功的请求都比平时贵）。
   */
  private emit(signal: NetworkSignal): void {
    const notify = this.deps.notifyNetwork
    if (notify === undefined) {
      return
    }
    try {
      notify(signal)
    } catch (error) {
      console.warn('[net] 弱网提示的观察者抛异常（已忽略，不影响请求）', signal.kind, error)
    }
  }

  private headers(traceId: string): Record<string, string> {
    const headers: Record<string, string> = {
      [HEADER_CONTENT_TYPE]: 'application/json',
      [HEADER_TRACE_ID]: traceId,
    }
    if (this.token !== null) {
      headers[HEADER_AUTH] = `Bearer ${this.token}`
    }
    if (this.playerId !== null) {
      headers[HEADER_PLAYER] = this.playerId
    }
    return headers
  }

  /** 退避时长 = base × 2^(attempt-1)，封顶 max，并叠加 ±25% 抖动。 */
  private backoffMs(attempt: number): number {
    const exponential = this.config.retryBaseDelayMs * Math.pow(2, attempt - 1)
    const capped = Math.min(exponential, this.config.retryMaxDelayMs)
    // 抖动避免「服务端刚恢复就被全体客户端同时重连打垮」（惊群）
    const jitter = this.deps.rng.range(7500, 12500) / 10000
    return Math.max(1, Math.round(capped * jitter))
  }

  private parseEnvelope<TResp>(status: number, bodyText: string, fallbackTraceId: string): NetOutcome<TResp> {
    let envelope: ApiEnvelope<TResp>
    try {
      envelope = JSON.parse(bodyText) as ApiEnvelope<TResp>
    } catch {
      return {
        kind: 'network',
        message: `响应体不是合法 JSON（HTTP ${status}）`,
        queued: false,
        requestId: null,
      }
    }
    const traceId = envelope.traceId ?? fallbackTraceId
    if (envelope.code === 0) {
      if (envelope.data === null) {
        return { kind: 'network', message: '成功响应缺少 data', queued: false, requestId: null }
      }
      this.absorbServerNow(envelope.serverNow)
      return { kind: 'ok', data: envelope.data, traceId, serverNow: envelope.serverNow }
    }
    return { kind: 'biz', code: envelope.code, msg: envelope.msg, detail: envelope.detail, traceId }
  }

  /** 每次响应都带 serverNow，顺手喂给时间同步（由 Store 层订阅处理）。 */
  private absorbServerNow(serverNow: number): void {
    if (typeof serverNow === 'number' && serverNow > 0) {
      gameBus.emit('serverTimeHint', { serverNow, localNow: this.deps.now() })
    }
  }

  // ---------- 离线队列 ----------

  private enqueue(path: string, bodyText: string, requestId: string): boolean {
    if (this.offlineQueue.length >= this.config.offlineQueueMax) {
      // 队满丢最旧：最旧的请求最可能已经过期（比如「加速建造」在几小时后重放已无意义），
      // 而最新的请求最可能是玩家刚刚点的那一下
      const dropped = this.offlineQueue.shift()
      console.warn(
        `[NetModule] 离线队列已满（上限 ${this.config.offlineQueueMax}），丢弃最旧请求 path=${dropped?.path} 入队于=${dropped?.enqueuedAt}`,
      )
    }
    this.offlineQueue.push({ path, bodyText, requestId, enqueuedAt: this.deps.now() })
    return true
  }

  /** 队列中待重放的请求数。 */
  pendingCount(): number {
    return this.offlineQueue.length
  }

  /**
   * 按入队顺序重放离线请求。
   *
   * 顺序必须保持：先「建造」后「升级」这类有因果依赖的操作乱序重放会得到服务端校验失败，
   * 玩家看到的是「我明明点了却没生效」。
   */
  async replayOfflineQueue(): Promise<number> {
    if (this.replaying || this.offlineQueue.length === 0) {
      return 0
    }
    this.replaying = true
    let replayed = 0
    try {
      while (this.offlineQueue.length > 0) {
        const item = this.offlineQueue[0]
        if (item === undefined) {
          break
        }
        // 队列里只可能有幂等写请求（enqueue 前已经校验过 requestId 非空），所以恒可重试；
        // GET 从不入队，重放路径固定是 POST
        const outcome = await this.sendWithRetry<unknown>(item.path, item.requestId, true,
          (url, headers) => this.deps.http.post(url, item.bodyText, headers))
        this.offlineQueue.shift()
        if (outcome.kind === 'network') {
          // 又断了：把这条塞回队首，等下次恢复再继续，不丢请求
          this.offlineQueue.unshift(item)
          break
        }
        replayed++
      }
    } finally {
      this.replaying = false
    }
    if (replayed > 0) {
      gameBus.emit('offlineQueueReplayed', { count: replayed })
    }
    return replayed
  }

  // ---------- WebSocket ----------

  /** 建立长连接。心跳间隔由服务端在 connected 帧里下发，客户端不硬编码。 */
  connectSocket(): void {
    this.closedByUser = false
    this.openSocket()
  }

  private openSocket(): void {
    const callbacks: SocketCallbacks = {
      onOpen: () => this.onSocketOpen(),
      onMessage: (text) => this.onSocketMessage(text),
      onClose: (reason) => this.onSocketClose(reason),
      onError: (message) => console.warn(`[NetModule] WebSocket 错误：${message}`),
    }
    this.socket = this.deps.socketFactory()
    this.socket.connect(callbacks)
  }

  private onSocketOpen(): void {
    this.socketOpen = true
    this.online = true
    const wasDisconnected = this.disconnectedAt !== null
    const downtime = this.disconnectedAt === null ? 0 : this.deps.now() - this.disconnectedAt
    this.disconnectedAt = null
    this.reconnectAttempts = 0
    if (wasDisconnected) {
      // 重连期间推送会丢，必须让上层走 HTTP 补拉（B01 要求）
      gameBus.emit('netReconnected', { downtimeMs: downtime })
      void this.replayOfflineQueue()
    }
  }

  private onSocketMessage(text: string): void {
    let message: SocketMessage
    try {
      message = JSON.parse(text) as SocketMessage
    } catch {
      console.warn('[NetModule] 收到非法 JSON 推送，已忽略')
      return
    }
    switch (message.type) {
      case 'connected': {
        const heartbeat = message.heartbeatSeconds ?? 0
        if (heartbeat > 0) {
          this.heartbeatSeconds = heartbeat
          this.restartHeartbeat()
        }
        gameBus.emit('netConnected', {
          serverNow: message.serverNow ?? this.deps.now(),
          heartbeatSeconds: heartbeat,
        })
        break
      }
      case 'pong':
        if (message.serverNow !== undefined) {
          this.absorbServerNow(message.serverNow)
        }
        break
      default:
        gameBus.emit('serverPush', { type: message.type, data: message.data })
        break
    }
  }

  private restartHeartbeat(): void {
    this.stopHeartbeat()
    const intervalMs = this.heartbeatSeconds * 1000
    this.heartbeatTimer = setInterval(() => {
      if (!this.sendRaw(JSON.stringify({ type: 'ping' }))) {
        // 心跳发不出去说明连接已死，但 onClose 可能还没触发（半开连接），主动断开走重连
        this.onSocketClose('心跳发送失败')
      }
    }, intervalMs)
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  /** 绑定玩家身份，之后服务端才能定向推送。 */
  bindPlayer(playerId: string): boolean {
    // 票据跟着 bind 一起发：严格身份模式下服务端会校验它（见 GameWebSocketHandler#bind）。
    // 漏了这一段的表现是"HTTP 全通、但订阅推送一条都收不到"，而本地宽松实现又不会报错。
    const message: Record<string, string> = { type: 'bind', playerId }
    if (this.token !== null) {
      message.token = this.token
    }
    return this.sendRaw(JSON.stringify(message))
  }

  private sendRaw(text: string): boolean {
    const socket = this.socket
    if (socket === null || !this.socketOpen) {
      return false
    }
    return socket.send(text)
  }

  private onSocketClose(reason: string): void {
    if (!this.socketOpen) {
      return
    }
    this.socketOpen = false
    this.online = false
    this.disconnectedAt = this.deps.now()
    this.stopHeartbeat()
    gameBus.emit('netDisconnected', { reason })
    if (!this.closedByUser) {
      this.scheduleReconnect()
    }
  }

  /** 断线重连，同样用带上限的指数退避，避免服务端重启时被全体客户端同时冲击。 */
  private scheduleReconnect(): void {
    this.reconnectAttempts++
    const delayMs = Math.min(
      this.config.retryBaseDelayMs * Math.pow(2, Math.min(this.reconnectAttempts - 1, 6)),
      this.config.retryMaxDelayMs,
    )
    setTimeout(() => {
      if (!this.closedByUser && !this.socketOpen) {
        console.info(`[NetModule] 第 ${this.reconnectAttempts} 次重连，延迟 ${delayMs}ms`)
        this.openSocket()
      }
    }, delayMs)
  }

  /** 主动断开（退出登录、切后台省电）。主动断开不触发自动重连。 */
  disconnect(): void {
    this.closedByUser = true
    this.stopHeartbeat()
    this.socketOpen = false
    this.socket?.close()
    this.socket = null
  }

  /** 当前连接状态。 */
  connectionOpen(): boolean {
    return this.socketOpen
  }

  /**
   * 最近一次请求发出的 traceId；还没发过任何请求时为空串。
   *
   * <p>崩溃上报靠它把"崩在哪条链路之后"对到服务端日志上。没发过请求就崩了也是有效信息
   * （说明崩在启动阶段），所以这里给空串而不是编一个假 id —— 编出来的 id 在服务端永远查不到，
   * 会把排查的人引向一条不存在日志。
   */
  lastTraceId(): string {
    return this.lastTraceIdValue
  }

  /** 当前是否认为网络可用。UI 据此显示「网络异常」提示条。 */
  isOnline(): boolean {
    return this.online
  }
}

function describeError(error: unknown): string {
  if (error instanceof Error) {
    return error.message
  }
  return String(error)
}

/**
 * 把查询参数拼进 path。
 *
 * <p>三条规矩：
 * <ul>
 *   <li><b>值为 null/undefined 的键跳过</b>，而不是发一个空值出去。服务端的
 *       {@code @RequestParam(required = false)} 收到空串和收不到参数是两种语义
 *       （例如 /bag/list 的 type 过滤：空串会被当成一个真实的类型名去匹配，结果永远为空）</li>
 *   <li><b>必须编码</b>。战报 id、昵称这类值里一旦出现 &amp; 或 =，
 *       不编码就会把查询串切坏，而症状是服务端收到一个莫名其妙的参数值</li>
 *   <li><b>键按字典序排序</b>。同一个请求的 url 因此是稳定的，抓包对比与日志检索才有意义；
 *       依赖对象字面量的插入序会让「同样的调用」在不同调用点产生不同的 url</li>
 * </ul>
 */
function appendQuery(path: string,
                     query?: Readonly<Record<string, string | number | boolean | null | undefined>>): string {
  if (query === undefined) {
    return path
  }
  const parts: string[] = []
  for (const key of Object.keys(query).sort()) {
    const value = query[key]
    if (value === null || value === undefined) {
      continue
    }
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  if (parts.length === 0) {
    return path
  }
  // path 本身可能已经带 ? （目前没有，但拼接规则不该依赖这个假设）
  return path + (path.includes('?') ? '&' : '?') + parts.join('&')
}
