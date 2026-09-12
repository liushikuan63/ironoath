/**
 * 职责：客户端埋点攒批队列 —— 攒够 N 条或超过 T 秒才发一次上报请求（B16 §3，验收 3）。
 * 依赖：无（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * **B16 的禁止项把「不要逐条上报」写了两遍**，原因不是流量而是电量与丢包：
 * 一次战斗里可能产生十几个事件（发起、每回合、技能触发、结算），
 * 逐条上报就是十几个 HTTP 请求，微信小游戏在弱网下每个请求都可能超时重试，
 * 于是埋点本身成了弱网体验的主要负担 —— 而这恰恰是最需要埋点数据的场景。
 *
 * 实现上有四个必须处理的失败模式，它们都比「攒批」本身重要：
 *
 * 1. **低频玩家的事件永远发不出去**：只按条数触发的话，一个点两下就退出的玩家
 *    那两个事件会一直躺在队列里，随进程消失。而「进来就退」正是流失分析最需要的样本。
 *    所以有 flushIntervalMs 这一路兜底，且**时间窗从队首事件算起**而不是从上次发送算起 ——
 *    后者的话第一条事件最坏要等两个窗口。
 *
 * 2. **发送失败后事件消失**：弱网下这一批发不出去是常态。调用方拿到批次后若发送失败，
 *    必须能 requeue 退回。而退回就必须有上限，否则一次长时间断网会让内存被埋点撑爆 ——
 *    埋点撑爆内存导致游戏崩溃，丢的数据比攒着不发多得多。**这个上限是本类唯一真正需要
 *    丢弃逻辑的地方**：待发队列本身被 maxBatchSize 从构造上限死（攒够就交出去），
 *    只有「交出去但没发成功」的批次会累积。
 *
 * 3. **丢最旧还是丢最新**：丢最旧。最新的事件最可能解释玩家刚刚为什么退出，
 *    而最旧的那批往往是最不重要的启动事件。丢弃必须计数并暴露出来 ——
 *    静默丢弃的话，分析侧看到的漏斗会莫名其妙少一环，而没人知道是数据丢了还是玩家真没走到那一步。
 *
 * 4. **进程被杀**：切后台、退出登录、崩溃前必须 flushNow。崩溃前最后几个事件
 *    往往正是解释崩溃原因的那几个，它们若还攒在队列里就随进程一起消失了。
 *
 * **本类不发请求也不读时钟**：track/tick 的时刻由调用方给，返回的批次由调用方发。
 * 所以攒批策略能在单测里用可控时钟精确验证，而不需要 mock 网络或 setInterval。
 * 也**刻意不用 setInterval**：常驻定时器会让「什么时候该发」变成一件只能靠看日志才知道的事，
 * 而在 Cocos 里它还会在场景切换后继续跑，对着一个已经销毁的节点回调。
 */

/** 一个待上报的埋点事件。形状与 ops 协议的 TrackEvent 一致（服务端契约是唯一真源）。 */
export interface TrackEventDraft {
  readonly name: string
  readonly ts: number
  readonly params: Record<string, string>
}

/**
 * 攒批规则。
 *
 * maxBatchSize 与 flushIntervalMs 来自服务端下发的 TrackPolicy（版本检查响应里），
 * **不在客户端写死**：客户端没有配置表加载器，写死的策略会与服务端悄悄漂移，
 * 而漂移的症状（客户端发 50 条、服务端按 10 条攒批）谁都不会报错。
 *
 * maxUnsentBatches 是客户端自己的内存保护，服务端管不着也不该管：
 * 它取决于本机内存而不是运营口径，所以由组合根给值，本类不设默认值 ——
 * 有默认值的话，「忘了配」和「配成了默认值」就无法区分。
 */
export interface TrackQueueRules {
  readonly maxBatchSize: number
  readonly flushIntervalMs: number
  readonly maxUnsentBatches: number
}

/** 一批待发送的事件。reason 用于日志：出问题时「为什么这批被发出去了」是第一个要回答的问题。 */
export interface TrackBatch {
  readonly events: TrackEventDraft[]
  readonly reason: string
}

export class TrackQueue {
  private readonly rules: TrackQueueRules
  private readonly queue: TrackEventDraft[] = []
  private readonly unsent: TrackBatch[] = []
  /** 队首事件的入队时刻。攒批的时间窗从第一条算起，而不是从上一次发送后算起 */
  private oldestAt = 0
  private batchCount = 0
  private droppedBatches = 0

  public constructor(rules: TrackQueueRules) {
    if (!Number.isInteger(rules.maxBatchSize) || rules.maxBatchSize < 1) {
      throw new Error(`maxBatchSize 必须是 >= 1 的整数，实际=${rules.maxBatchSize}：否则永远攒不满也永远不发`)
    }
    if (!Number.isFinite(rules.flushIntervalMs) || rules.flushIntervalMs < 1) {
      // 为 0 等于每条都立刻发，那就是禁止项写了两遍的逐条上报
      throw new Error(`flushIntervalMs 必须 >= 1，实际=${rules.flushIntervalMs}：为 0 就是逐条上报`)
    }
    if (!Number.isInteger(rules.maxUnsentBatches) || rules.maxUnsentBatches < 1) {
      throw new Error(`maxUnsentBatches 必须是 >= 1 的整数，实际=${rules.maxUnsentBatches}：`
        + '为 0 等于发送失败就丢，弱网下埋点会全丢')
    }
    this.rules = rules
  }

  /**
   * 记一个事件。
   *
   * **事件名与时间戳在这里校验**（服务端的核心攒批策略刻意不校验内容）：
   * 校验属于边界，而客户端就是这一侧的边界。没有名字的事件在分析侧无法归类，
   * 却已经占了上报配额，所以当场拒绝而不是发到服务端再被计入 failed。
   *
   * @returns 若这次入队触发了一批，返回该批；否则返回 null
   */
  public track(event: TrackEventDraft): TrackBatch | null {
    if (event === null || event === undefined) {
      throw new Error('event 不得为空')
    }
    if (typeof event.name !== 'string' || event.name.trim().length === 0) {
      throw new Error('事件名不得为空：没有名字的事件在分析侧无法归类，却已经占了上报配额')
    }
    if (!Number.isFinite(event.ts)) {
      throw new Error(`事件 ${event.name} 的时间戳必须是有限数，实际=${String(event.ts)}：`
        + '非有限值会让攒批窗口的比较结果永远是 false，于是这一批永远不发')
    }
    if (this.queue.length === 0) {
      this.oldestAt = event.ts
    }
    this.queue.push({ name: event.name, ts: event.ts, params: { ...event.params } })
    if (this.queue.length >= this.rules.maxBatchSize) {
      return this.flush(`攒满 ${this.rules.maxBatchSize} 条`)
    }
    return null
  }

  /**
   * 按时间触发一次检查。由调用方在每帧（或每秒）驱动。
   *
   * @param now 当前毫秒时刻，由调用方给 —— 本类不读时钟，所以攒批可被精确复现
   * @returns 到期则返回该批，否则 null
   */
  public tick(now: number): TrackBatch | null {
    if (this.queue.length === 0) {
      return null
    }
    const waited = now - this.oldestAt
    if (waited < this.rules.flushIntervalMs) {
      return null
    }
    return this.flush(`距首条事件已过 ${Math.floor(waited / 1000)} 秒`)
  }

  /**
   * 立即交出全部（切后台、退出登录、崩溃前）。
   *
   * 崩溃前调用尤其重要：验收 9 要求崩溃上报带完整堆栈与 traceId，
   * 而崩溃前最后几个事件往往正是解释崩溃原因的那几个。
   */
  public flushNow(reason: string): TrackBatch | null {
    if (this.queue.length === 0) {
      return null
    }
    return this.flush(reason)
  }

  /**
   * 发送失败后把这一批退回，等下一次触发时重投。
   *
   * 退回的批次排在队尾（不是队首）：重投顺序不重要，重要的是别让一次失败堵住后面的批。
   * 超过 maxUnsentBatches 时丢最旧的一批并计数 —— 丢的是批不是事件，
   * 因为一批里的事件同属一个时间窗，要丢就一起丢，拆开会让漏斗出现半截。
   */
  public requeue(batch: TrackBatch): void {
    if (batch === null || batch === undefined || batch.events.length === 0) {
      return
    }
    this.unsent.push(batch)
    while (this.unsent.length > this.rules.maxUnsentBatches) {
      const dropped = this.unsent.shift()
      this.droppedBatches++
      if (dropped !== undefined) {
        // 必须留下日志：静默丢弃的话，分析侧看到的漏斗会莫名其妙少一环，
        // 而没人知道是数据丢了还是玩家真的没走到那一步
        console.warn(`[track] 重试缓冲区已满 ${this.rules.maxUnsentBatches} 批，丢弃最旧的一批`
          + `（${dropped.events.length} 条，原因=${dropped.reason}）。累计已丢 ${this.droppedBatches} 批`)
      }
    }
  }

  /** 取出一批待重投的旧批次。没有则返回 null。优先于新攒的批：旧批的数据更接近丢失。 */
  public takeUnsent(): TrackBatch | null {
    const batch = this.unsent.shift()
    return batch === undefined ? null : batch
  }

  private flush(reason: string): TrackBatch {
    const batch: TrackBatch = { events: this.queue.slice(), reason }
    this.queue.length = 0
    this.oldestAt = 0
    this.batchCount++
    return batch
  }

  /** 当前攒了多少条（未交出）。恒小于 maxBatchSize：攒够即交出去。 */
  public pendingCount(): number {
    return this.queue.length
  }

  /** 已交出多少批。批数远小于事件数说明攒批在生效。 */
  public issuedBatchCount(): number {
    return this.batchCount
  }

  /** 等待重投的批次数。持续非零说明网络一直不通。 */
  public unsentBatchCount(): number {
    return this.unsent.length
  }

  /** 因重试缓冲区满而丢弃的批次数。**这个数必须被监控看见**，它非零说明发生了长时间断网。 */
  public droppedBatchCount(): number {
    return this.droppedBatches
  }

  /** 队首事件已攒了多久（毫秒）。用于判断上报是不是卡住了。 */
  public oldestAge(now: number): number {
    return this.queue.length === 0 ? 0 : Math.max(0, now - this.oldestAt)
  }
}
