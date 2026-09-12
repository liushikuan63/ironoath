/**
 * 职责：埋点与崩溃上报的客户端入口 —— 攒批、发送、重投、切后台冲刷（B16 §3/§6，验收 3/9）。
 * 依赖：`TrackQueue`（攒批策略）、`OpsProtocol` 的形状。**引擎无关，可脱离 Cocos 跑单测**（B00 铁律 2）。
 *
 * <p>这一层解决的问题是「`TrackQueue` 有了但没人调用」：在它之前，客户端没有任何地方
 * 会产生埋点事件，于是服务端的攒批、落库、看板全部是空转 —— 上线后运营侧是瞎的
 * （`上线检查清单.md` §五 4 记的就是这一条）。
 *
 * <p><b>四条必须处理好的失败模式</b>：
 * <ol>
 *   <li><b>发送失败</b>：弱网下这是常态。失败的批次退回 `TrackQueue` 的重投缓冲区，
 *       缓冲区满了丢最旧的一批并计数 —— 丢的是批不是事件，因为一批里的事件同属一个时间窗，
 *       拆开会让漏斗出现半截</li>
 *   <li><b>进程被杀</b>：切后台时必须冲刷并立刻发出，因为崩溃前最后几个事件
 *       往往正是解释崩溃原因的那几个，它们若还攒在队列里就随进程一起消失了</li>
 *   <li><b>事件名写错</b>：`TrackQueue.track` 会当场抛（没有名字的事件在分析侧无法归类，
 *       却已经占了上报配额）。本类<b>不吞这个异常</b> —— 埋点调用点的 bug 应当在开发期就炸出来，
 *       而不是变成看板上一个永远为 0 的漏斗节点</li>
 *   <li><b>上报本身失败不能影响游戏</b>：所有网络调用都在 promise 链里处理错误，
 *       绝不让一次埋点失败冒泡成一次游戏崩溃。埋点是尽力而为的数据，不是账目</li>
 * </ol>
 *
 * <p><b>攒批策略来自服务端</b>（`/ops/app/version` 的 `TrackPolicy`），不在客户端写死：
 * 客户端没有配置表加载器，写死就是铁律 1 的硬编码，而且会与服务端悄悄漂移 ——
 * 漂移的症状是「客户端发 50 条，服务端按 10 条攒批」，谁都不报错。
 */

import { TrackQueue } from './TrackQueue'
import type { TrackBatch, TrackEventDraft, TrackQueueRules } from './TrackQueue'

/** 崩溃上报的内容。形状与 ops 协议的 CrashReportReq 一致（服务端契约是唯一真源）。 */
export interface CrashInput {
  readonly message: string
  readonly stack: string
  readonly clientVersion: string
  /** 崩溃时所在场景；崩在场景切换之间时为 null（那本身就是一种定位信息）。 */
  readonly sceneName: string | null
  /** 全链路 id，与服务端日志的 X-Trace-Id 同名同源。 */
  readonly traceId: string
}

/**
 * 传输层。<b>做成接口而不是直接依赖 GameApi</b>：埋点的发送必须在单测里可控
 * （成功 / 失败 / 抛异常三种都要能造出来），而真的发 HTTP 就没法测重投与丢弃了。
 */
export interface TrackTransport {
  /** 发一批埋点。resolve(true) 表示服务端已收下；false 或 reject 都会触发重投。 */
  sendBatch(events: TrackEventDraft[]): Promise<boolean>
  /** 上报一次崩溃。**不重投**：崩溃上报失败时重试也没有意义（进程可能已经要退出了）。 */
  reportCrash(crash: CrashInput): Promise<boolean>
}

export class TrackClient {
  private readonly queue: TrackQueue
  private readonly transport: TrackTransport
  private readonly now: () => number
  private inFlight = 0
  /**
   * 在途发送的 promise。`settle()` 等的是它们本身 ——
   * 只看 `inFlight` 计数就只能靠微任务自旋等它变 0，而真的网络请求是在 I/O 阶段回来的，
   * 微任务队列不腾空就永远轮不到它（表现是切后台时 JS 线程被钉死）。
   */
  private readonly inFlightSends: Promise<void>[] = []
  private deliveredBatches = 0
  private failedBatches = 0
  private crashReported = 0

  /**
   * @param rules    攒批规则，来自服务端下发的 TrackPolicy（条数 + 秒数换算成毫秒）
   *                 与客户端自己的重投缓冲上限
   * @param transport 传输层
   * @param now      当前毫秒时刻的取值函数。注入而不是直接读 Date.now，
   *                 这样「攒够 10 秒才发」可以在单测里被精确复现
   */
  public constructor(rules: TrackQueueRules, transport: TrackTransport, now: () => number) {
    this.queue = new TrackQueue(rules)
    this.transport = transport
    this.now = now
  }

  /**
   * 记一个事件。参数可选（无参数的事件传空 map，不允许传 null —— 下游遍历 null 会炸）。
   *
   * <p>事件名非法时<b>当场抛</b>，见类注释第 3 条。
   */
  public track(name: string, params: Record<string, string> = {}): void {
    const batch = this.queue.track({ name, ts: this.now(), params })
    if (batch !== null) {
      this.send(batch)
    }
  }

  /**
   * 驱动一次检查：到点的批次发出去，并重投一批旧的。
   *
   * <p><b>由调用方在每帧或每秒调一次</b>（Cocos 的 update 里）。本类不用 setInterval ——
   * 常驻定时器会让「什么时候该发」变成只能靠看日志才知道的事，
   * 而在 Cocos 里它还会在场景切换后继续跑，对着一个已经销毁的节点回调。
   *
   * <p>重投优先于新批：旧批的数据更接近丢失。
   */
  public tick(): void {
    const retry = this.queue.takeUnsent()
    if (retry !== null) {
      this.send(retry)
      return
    }
    const due = this.queue.tick(this.now())
    if (due !== null) {
      this.send(due)
    }
  }

  /**
   * 切后台 / 退出登录 / 崩溃前调用：把队列里剩的全部冲刷并发出去。
   *
   * <p>这是「进程被杀」那条失败模式的唯一防线。微信小游戏切后台后随时可能被系统回收，
   * 没有这一步的话，玩家最后那几个事件（往往正是解释他为什么走的那几个）永远不会到达服务端。
   */
  public onBackground(): void {
    const batch = this.queue.flushNow('切后台')
    if (batch !== null) {
      this.send(batch)
    }
  }

  /**
   * 上报一次崩溃。**不进攒批队列、不重投**：崩溃是低频事件，
   * 而它是线上唯一能解释「玩家为什么再也进不来」的证据，
   * 让它等一个攒批窗口等于在进程即将退出时把它丢掉。
   */
  public reportCrash(crash: CrashInput): void {
    this.crashReported++
    this.transport.reportCrash(crash).then(
      (ok) => {
        if (!ok) {
          console.warn('[track] 崩溃上报未被服务端收下 traceId=' + crash.traceId)
        }
      },
      (error: unknown) => {
        // 上报失败也不能影响游戏：这里已经在异常处理路径上了，再抛一次就是二次崩溃
        console.warn('[track] 崩溃上报失败 traceId=' + crash.traceId, error)
      },
    )
  }

  /**
   * 等所有在途请求落定。<b>给单测与切后台用</b>：
   * 没有它就没法断言「发送失败之后批次被退回了」，因为那发生在 promise 回调里。
   *
   * <p>等的是 promise 本身而不是计数：计数要用微任务自旋去看，而微任务队列不腾空，
   * 事件循环就不会去处理回来的 socket I/O —— 真网络请求下那是一个永不结束、
   * 还把渲染线程钉死的循环。切后台正是最需要它跑完的时刻。
   *
   * <p>落定不等于送达：失败的批次会被退回队列等下一次 `tick()` 重投，
   * 所以本方法返回后 `pendingCount()` 仍可能非 0，那是设计而不是没等完。
   */
  public async settle(): Promise<void> {
    while (this.inFlightSends.length > 0) {
      const pending = [...this.inFlightSends]
      await Promise.all(pending)
    }
  }

  private send(batch: TrackBatch): void {
    this.inFlight++
    const settled = this.transport.sendBatch(batch.events).then(
      (ok) => {
        this.inFlight--
        if (ok) {
          this.deliveredBatches++
        } else {
          this.failedBatches++
          this.queue.requeue(batch)
        }
      },
      (error: unknown) => {
        this.inFlight--
        this.failedBatches++
        // 网络异常与「服务端明确拒绝」同样处理：都退回重投。
        // 区分它们没有意义 —— 两种情况下这批数据都还没到服务端
        console.warn('[track] 上报失败，退回重投（' + batch.events.length + ' 条，原因=' + batch.reason + '）', error)
        this.queue.requeue(batch)
      },
    )
    const tracked = settled.then(() => {
      const index = this.inFlightSends.indexOf(tracked)
      if (index >= 0) {
        this.inFlightSends.splice(index, 1)
      }
    })
    this.inFlightSends.push(tracked)
  }

  /** 已成功送达的批数。 */
  public deliveredBatchCount(): number {
    return this.deliveredBatches
  }

  /** 发送失败（含网络异常）的批次数。持续非零说明网络或服务端有问题。 */
  public failedBatchCount(): number {
    return this.failedBatches
  }

  /** 因重投缓冲区满而被丢弃的批次数。**这个数必须被监控看见**。 */
  public droppedBatchCount(): number {
    return this.queue.droppedBatchCount()
  }

  /** 还攒着没发的事件数。 */
  public pendingCount(): number {
    return this.queue.pendingCount()
  }

  /** 已发起过的崩溃上报次数（不含失败）。 */
  public crashReportCount(): number {
    return this.crashReported
  }
}
