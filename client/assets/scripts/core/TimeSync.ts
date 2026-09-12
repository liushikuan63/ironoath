/**
 * 职责：客户端时间偏移跟踪 —— 服务端 game-common `TimeOffsetTracker` 的 TypeScript 镜像。
 * 依赖：core/FixedPoint。
 *
 * B00 铁律 5：客户端不得用本地 Date 做产出结算。本类的作用是让客户端<b>显示</b>的倒计时
 * 与服务端一致，而不是让客户端有能力自己算产出。所有结算仍以服务端下发为准，收到后强制纠偏。
 *
 * 为什么不能直接用服务端返回的 offset：单次 /time/sync 采样含一个单程网络延迟，
 * 直接采用会让客户端时间系统性超前（RTT 200ms 时超前 100ms），
 * 表现为「倒计时结束了但服务端说还没结束」——玩家会以为游戏卡了。
 * 做法是：扣除 rtt/2 补偿 + 加权移动平均 + 剔除抖动极值。
 */

import * as Fixed from './FixedPoint'

/** 时间源抽象，便于单测注入可控时钟（与 Cocos 的 sys.now / Date.now 解耦）。 */
export type Clock = () => number

export interface TimeSyncOptions {
  /** 平滑系数（定点，0~10000）。来源：global.json TIME_SYNC_ALPHA。 */
  readonly alphaFixed: number
  /** 抖动剔除倍数（定点）。来源：global.json TIME_SYNC_JITTER_FACTOR。 */
  readonly jitterFactorFixed: number
  /** 首次采样前的最佳 RTT 兜底估计（毫秒）。来源：global.json TIME_SYNC_INITIAL_BEST_RTT_MS。 */
  readonly initialBestRttMs: number
}

export class TimeSync {
  private readonly alphaFixed: number
  private readonly jitterFactorFixed: number
  // 字段名与同名访问器方法刻意错开（bestRtt / bestRttMs()），否则 TS 报重复标识符
  private bestRtt: number
  private offset = 0
  private calibrated = false
  private accepted = 0
  private rejected = 0
  private readonly clock: Clock

  constructor(options: TimeSyncOptions, clock: Clock = Date.now) {
    if (options.alphaFixed <= 0 || options.alphaFixed > Fixed.ONE) {
      throw new RangeError(`alpha 必须落在 (0, 1.0] 的定点区间，实际=${options.alphaFixed}`)
    }
    if (options.jitterFactorFixed < Fixed.ONE) {
      throw new RangeError(`jitterFactor 不得小于 1.0，否则所有样本都会被丢弃，实际=${options.jitterFactorFixed}`)
    }
    if (options.initialBestRttMs <= 0) {
      throw new RangeError(`initialBestRttMs 必须为正数，实际=${options.initialBestRttMs}`)
    }
    this.alphaFixed = options.alphaFixed
    this.jitterFactorFixed = options.jitterFactorFixed
    this.bestRtt = options.initialBestRttMs
    this.clock = clock
  }

  /**
   * 提交一次采样。
   *
   * @param rttMs     往返时延（收到响应时刻 - 发出请求时刻）
   * @param rawOffset 服务端返回的 offset 原值（= 服务端收到请求时刻 - 客户端发出请求时刻）
   */
  offer(rttMs: number, rawOffset: number): void {
    if (rttMs < 0) {
      throw new RangeError(`rttMs 不得为负：${rttMs}`)
    }
    const threshold = Fixed.round(Fixed.mul(Fixed.of(this.bestRtt), this.jitterFactorFixed))
    if (this.calibrated && rttMs > threshold) {
      // 抖动极值：这次采样大概率被一次卡顿污染，丢弃而不是拉偏整条平均线
      this.rejected++
      return
    }
    const compensated = rawOffset - Math.trunc(rttMs / 2)
    if (rttMs > 0 && rttMs < this.bestRtt) {
      this.bestRtt = rttMs
    }
    if (!this.calibrated) {
      this.offset = compensated
      this.calibrated = true
    } else {
      this.offset += Fixed.mul(compensated - this.offset, this.alphaFixed)
    }
    this.accepted++
  }

  /** 当前平滑后的偏移（毫秒）。 */
  offsetMs(): number {
    return this.offset
  }

  /** 是否已完成至少一次有效采样。 */
  isCalibrated(): boolean {
    return this.calibrated
  }

  /**
   * 当前服务端时间的本地估计。
   *
   * 未校准时返回本地时间原值 —— 宁可用一个明确「未校准」的值，
   * 也不要用错误的 offset 去误导表现层（那会导致倒计时跳变且无从排查）。
   */
  serverNow(clientNowMs: number = this.clock()): number {
    return this.calibrated ? clientNowMs + this.offset : clientNowMs
  }

  /** 距某个服务端时间点还剩多少毫秒（本地估计）。用于倒计时显示。 */
  remainingMs(serverDeadline: number, clientNowMs: number = this.clock()): number {
    return Math.max(0, serverDeadline - this.serverNow(clientNowMs))
  }

  acceptedSamples(): number {
    return this.accepted
  }

  rejectedSamples(): number {
    return this.rejected
  }

  bestRttMs(): number {
    return this.bestRtt
  }
}
