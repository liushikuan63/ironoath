/**
 * 职责：奖励飘字与领取动效的串行播放队列（B04 §6，验收 8）。
 * 依赖：无（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * B04 的要求是「多个奖励按顺序播放，不可同时堆叠遮挡」。
 * 堆叠遮挡不只是难看：两条飘字叠在一起时玩家一条都读不清，
 * 等于这两份奖励的反馈都白给了 —— 而奖励反馈是 SLG 里最便宜的正反馈来源。
 *
 * 实现上有三个必须处理的失败模式，它们都比「排队」本身重要：
 *
 * 1. **卡死**：队列是串行的，前一条播完才播下一条。所以只要有一条动效永远不 resolve
 *    （节点被提前销毁、tween 被外部 stop、异步资源加载失败但没 reject），
 *    整个队列就会永久冻结 —— 玩家此后再也看不到任何奖励飘字，
 *    而日志里一点异常都没有（Promise 没 reject，只是没 resolve）。
 *    因此每条都有 stuckTimeoutMs 兜底，超时强制推进。
 *
 * 2. **调用方抛异常**：一条动效出错不该让后面全部消失。异常被隔离并记录。
 *
 * 3. **队列被灌爆**：一次开 100 个宝箱如果按「每次抽取一条」入队，就是 100 条飘字、
 *    播两分钟，期间任何新奖励都看不见。服务端已经做了聚合（openBatch 按掉落表聚合，
 *    100 抽最多 6 行），所以正常路径碰不到上限；maxQueued 是给调用方 bug 兜底的。
 *    超出的部分丢弃并打警告 —— 丢的只是飘字，奖励本身已经在存档里，
 *    玩家从资源条与背包仍然看得到，所以这是可接受的降级，但必须留下日志。
 */

/** 飘字类别。决定用哪套动效与音效，不参与排序（顺序由入队顺序决定）。 */
export type ToastKind = 'resource' | 'item' | 'power' | 'levelup'

/** 一条飘字。text 由调用方组装好（服务端下发的中文名 + 数量），队列不做任何本地化。 */
export interface RewardToast {
  /** 唯一标识，用于日志排查「哪一条卡住了」。 */
  readonly id: string
  /** 已组装好的展示文案，如「+1,200 木材」。 */
  readonly text: string
  readonly kind: ToastKind
}

/**
 * 真正播放动效的一方（Cocos 侧的节点动画、音效、飘字预制体）。
 *
 * 返回的 Promise 必须在动效<b>真正播完</b>时才 resolve —— 这是队列串行的唯一依据。
 * 提前 resolve 会让下一条叠上来（正是 B04 要禁止的），
 * 永不 resolve 会冻结整个队列（由 stuckTimeoutMs 兜底，但那已经是降级路径）。
 */
export interface ToastPresenter {
  play(toast: RewardToast): Promise<void>
}

export interface RewardToastOptions {
  /** 两条飘字之间的间隔毫秒。来源：global.json TOAST_GAP_MS */
  readonly gapMs: number
  /** 队列长度上限，超出丢弃并告警。来源：global.json TOAST_MAX_QUEUE */
  readonly maxQueued: number
  /** 单条飘字的最长播放时间，超时强制推进。来源：global.json TOAST_STUCK_TIMEOUT_MS */
  readonly stuckTimeoutMs: number
  /** 延时实现，注入以便单测确定性驱动；缺省用 setTimeout。 */
  readonly delay?: (ms: number) => Promise<void>
}

export class RewardToastQueue {
  private readonly presenter: ToastPresenter
  private readonly options: Required<Pick<RewardToastOptions, 'gapMs' | 'maxQueued' | 'stuckTimeoutMs'>>
  private readonly delay: (ms: number) => Promise<void>
  private readonly queue: RewardToast[] = []
  private draining = false
  private current: RewardToast | null = null
  private droppedTotal = 0

  constructor(presenter: ToastPresenter, options: RewardToastOptions) {
    if (presenter === undefined || presenter === null) {
      throw new Error('presenter 不得为空')
    }
    if (!(options.gapMs >= 0)) {
      throw new Error(`gapMs 不得为负：${options.gapMs}`)
    }
    if (!(options.maxQueued > 0)) {
      throw new Error(`maxQueued 必须为正，否则任何飘字都播不出来：${options.maxQueued}`)
    }
    if (!(options.stuckTimeoutMs > 0)) {
      throw new Error(`stuckTimeoutMs 必须为正：${options.stuckTimeoutMs}`)
    }
    this.presenter = presenter
    this.options = {
      gapMs: options.gapMs,
      maxQueued: options.maxQueued,
      stuckTimeoutMs: options.stuckTimeoutMs,
    }
    this.delay = options.delay ?? ((ms) => new Promise<void>((resolve) => setTimeout(resolve, ms)))
  }

  /**
   * 入队一批飘字，保持传入顺序（B04 §6：奖励列表的顺序就是播放顺序，
   * 而服务端的 RewardService 契约明确写了「顺序即客户端飘字播放顺序」）。
   *
   * @returns 实际被接受的条数；小于入参长度说明触发了 maxQueued 上限
   */
  enqueue(toasts: readonly RewardToast[]): number {
    if (toasts === undefined || toasts === null) {
      throw new Error('toasts 不得为空（没有奖励请传空数组）')
    }
    let accepted = 0
    for (const toast of toasts) {
      if (this.queue.length >= this.options.maxQueued) {
        const dropped = toasts.length - accepted
        this.droppedTotal += dropped
        console.warn(
          `[RewardToastQueue] 队列已满（上限 ${this.options.maxQueued}），丢弃 ${dropped} 条飘字。`
          + '奖励本身已入账，丢的只是表现层；正常路径不该触发本上限 —— '
          + '服务端已按掉落表聚合，触发通常说明调用方把聚合前的明细逐条入队了。',
        )
        break
      }
      this.queue.push(toast)
      accepted++
    }
    void this.drain()
    return accepted
  }

  /** 等待播放（含正在播的那条）的条数。 */
  get pending(): number {
    return this.queue.length + (this.current === null ? 0 : 1)
  }

  /** 正在播放的那一条；空闲时为 null。 */
  get playing(): RewardToast | null {
    return this.current
  }

  /** 累计因队列满而被丢弃的条数，用于埋点与排查。 */
  get droppedCount(): number {
    return this.droppedTotal
  }

  /** 队列是否空闲（没有正在播的，也没有排队的）。 */
  get idle(): boolean {
    return !this.draining && this.queue.length === 0 && this.current === null
  }

  /**
   * 清空尚未开始播放的飘字。切换场景或重登录时调用。
   *
   * <b>正在播放的那一条无法取消</b>：presenter 没有 cancel 接口，
   * 强行中断需要它自己支持。它会正常播完（或触发 stuckTimeoutMs 兜底）后队列归于空闲。
   */
  clear(): void {
    const dropped = this.queue.length
    this.queue.length = 0
    if (dropped > 0) {
      this.droppedTotal += dropped
      console.warn(`[RewardToastQueue] 清空队列，丢弃 ${dropped} 条未播放的飘字`)
    }
  }

  /**
   * 串行播放循环。
   *
   * draining 标志保证同一时刻只有一个循环在跑：
   * enqueue 可能被连续调用多次，若每次都起一个循环，就会出现两条飘字同时播 ——
   * 那正是 B04 要禁止的「同时堆叠遮挡」。
   */
  private async drain(): Promise<void> {
    if (this.draining) {
      return
    }
    this.draining = true
    try {
      while (this.queue.length > 0) {
        const toast = this.queue.shift() as RewardToast
        this.current = toast
        await this.playOne(toast)
        this.current = null
        if (this.queue.length > 0 && this.options.gapMs > 0) {
          await this.delay(this.options.gapMs)
        }
      }
    } finally {
      // 无论如何都要复位：否则一次异常会让队列永久停止消费，后续奖励再也播不出来
      this.draining = false
    }
  }

  /**
   * 播一条，带超时兜底与异常隔离。永远不向外抛异常。
   *
   * race 的两个分支都返回 boolean：{@code true} 表示超时兜底生效（动效没在限定时间内播完），
   * {@code false} 表示动效正常结束。用 boolean 而不是 symbol 标记是因为
   * symbol 在 race 的联合类型里会被 widen，判别反而要写类型断言。
   */
  private async playOne(toast: RewardToast): Promise<void> {
    let stuck: boolean
    try {
      stuck = await Promise.race([
        // 用 async 包一层：presenter.play 同步抛异常时也能被 catch 到，
        // 否则异常会逃出 drain 循环，把整个队列带走
        (async () => {
          await this.presenter.play(toast)
          return false
        })(),
        this.delay(this.options.stuckTimeoutMs).then(() => true),
      ])
    } catch (error) {
      console.error(
        `[RewardToastQueue] 飘字「${toast.id}」播放出错，已隔离，继续播放后续奖励`, error,
      )
      return
    }
    if (stuck) {
      console.error(
        `[RewardToastQueue] 飘字「${toast.id}」播放超过 ${this.options.stuckTimeoutMs}ms 未结束，`
        + '强制推进到下一条。这通常意味着动效的 Promise 永远不会 resolve'
        + '（节点被提前销毁 / tween 被外部停止），队列本身没有卡住但这一条的表现丢了。',
      )
    }
  }
}
