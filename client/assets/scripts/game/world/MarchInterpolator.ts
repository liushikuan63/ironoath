/**
 * 职责：行军位置的客户端插值与纠偏（B07 §2、验收 10）。
 * 依赖：无（引擎无关，可脱离 Cocos 跑单测）。
 *
 * <p><b>客户端只插值，绝不判定到达</b>。「到达」是服务端延迟队列的事
 * （B07 禁止项：不要用客户端定时器决定到达）。客户端算出的位置只用于画动画，
 * 一旦与服务端下发的位置不一致，<b>以服务端为准并立即纠偏</b> ——
 * 不做平滑过渡，因为过渡意味着在一段时间内继续显示错误位置，
 * 而玩家盯着的正是自己的队伍（B07 验收 10：模拟时间偏差 5 分钟，收到推送立即纠偏）。
 *
 * <p><b>时钟偏差靠 offset 而不是靠本地时钟</b>：项目已有 TimeSync 模块
 * （B01 的时间校准契约）算出 {@code serverNow - localNow}。插值一律先把本地时刻
 * 换算成服务端时刻再算，否则玩家本地时钟快 5 分钟，他的队伍就会永远比别人的早到 5 分钟。
 *
 * <p><b>返程用 returnStartAt / returnFrom，不用 startAt / to</b>：
 * 返程的起点是「召回那一刻队伍所在的位置」，不是目的地。
 * 用错的表现是召回瞬间队伍瞬移到目的地再开始往回走 —— 服务端已经修过这个 bug，
 * 客户端如果按 startAt/to 插值，同一个 bug 会在表现层复现一次。
 */

/** 服务端下发的一支行军的时间轴（MarchView 的子集，只取插值需要的字段）。 */
export interface MarchTrack {
  readonly fromX: number
  readonly fromY: number
  readonly toX: number
  readonly toY: number
  readonly startAt: number
  readonly arriveAt: number
  readonly status: 'MARCHING' | 'STATIONED' | 'GATHERING' | 'RETURNING' | 'FIGHTING'
  /** 返程起点时刻；未在返程中为 null。 */
  readonly returnStartAt: number | null
  readonly returnArriveAt: number | null
  /** 返程起点坐标；未在返程中为 null。服务端按召回瞬间的实际位置算好下发。 */
  readonly returnFromX: number | null
  readonly returnFromY: number | null
}

export interface InterpolatedPosition {
  readonly x: number
  readonly y: number
  /** 0~1 的行程进度。 */
  readonly progress: number
  /** 距离本段结束还有多少毫秒；已结束为 0，<b>绝不为负</b>。 */
  readonly remainingMs: number
  /** 本段是否已结束。客户端据此停止动画，但<b>不据此判定到达</b> —— 那是服务端的事。 */
  readonly segmentDone: boolean
}

export class MarchInterpolator {
  private offsetMs: number

  /**
   * @param offsetMs 服务端时刻 - 本地时刻，来自 TimeSync。
   *                 本地时钟快 5 分钟时它是 -300000，插值会自动抵消这个偏差
   */
  constructor(offsetMs: number) {
    if (!Number.isFinite(offsetMs)) {
      throw new Error(`offsetMs 必须是有限数，实际=${offsetMs}`)
    }
    this.offsetMs = offsetMs
  }

  /** 收到新的时间校准（每个 HTTP 响应都带 serverNow，等于免费校准一次）后更新偏移。 */
  updateOffset(offsetMs: number): void {
    if (!Number.isFinite(offsetMs)) {
      throw new Error(`offsetMs 必须是有限数，实际=${offsetMs}`)
    }
    this.offsetMs = offsetMs
  }

  get offset(): number {
    return this.offsetMs
  }

  /**
   * 插值出某个本地时刻的位置。
   *
   * <p>全程整数运算：先乘后除，避免 {@code elapsed / span} 先变成 0 或 1 的小数截断。
   * 坐标向下取整 —— 格子是离散的，四舍五入会让队伍在两个格子之间来回跳。
   */
  positionAt(track: MarchTrack, localNow: number): InterpolatedPosition {
    if (track === undefined || track === null) {
      throw new Error('track 不得为空')
    }
    const serverNow = localNow + this.offsetMs
    if (track.status === 'RETURNING') {
      const t0 = track.returnStartAt ?? track.startAt
      const t1 = track.returnArriveAt ?? t0
      const fromX = track.returnFromX ?? track.toX
      const fromY = track.returnFromY ?? track.toY
      return interpolate(fromX, fromY, track.fromX, track.fromY, t0, t1, serverNow)
    }
    if (track.status === 'MARCHING') {
      return interpolate(track.fromX, track.fromY, track.toX, track.toY,
        track.startAt, track.arriveAt, serverNow)
    }
    // 驻扎 / 采集 / 交战都停在目标格
    return { x: track.toX, y: track.toY, progress: 1, remainingMs: 0, segmentDone: true }
  }

  /**
   * 收到服务端推送后的纠偏。
   *
   * <p><b>返回「应当立即跳到」的位置，而不是「平滑过渡到」的位置</b>：
   * 平滑过渡意味着在过渡期间继续显示错误位置，而验收 10 要的是「立即纠偏」。
   * 表现层可以在跳变时加一个短促的残影/闪光来提示玩家「位置被校正了」，
   * 但那是特效，不是插值。
   *
   * @param previous 纠偏前客户端正在用的时间轴
   * @param next     服务端推送的新时间轴
   * @param localNow 当前本地时刻
   */
  correction(previous: MarchTrack, next: MarchTrack,
             localNow: number): { jumped: boolean; from: InterpolatedPosition; to: InterpolatedPosition } {
    const from = this.positionAt(previous, localNow)
    const to = this.positionAt(next, localNow)
    return { jumped: from.x !== to.x || from.y !== to.y, from, to }
  }
}

function interpolate(fromX: number, fromY: number, toX: number, toY: number,
                     t0: number, t1: number, now: number): InterpolatedPosition {
  if (t1 <= t0) {
    return { x: toX, y: toY, progress: 1, remainingMs: 0, segmentDone: true }
  }
  if (now <= t0) {
    return { x: fromX, y: fromY, progress: 0, remainingMs: t1 - t0, segmentDone: false }
  }
  if (now >= t1) {
    return { x: toX, y: toY, progress: 1, remainingMs: 0, segmentDone: true }
  }
  const span = t1 - t0
  const elapsed = now - t0
  // 先乘后除：elapsed / span 直接算会先截断成 0
  const x = fromX + Math.floor((toX - fromX) * elapsed / span)
  const y = fromY + Math.floor((toY - fromY) * elapsed / span)
  return { x, y, progress: elapsed / span, remainingMs: t1 - now, segmentDone: false }
}
