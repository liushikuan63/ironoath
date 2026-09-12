/**
 * 职责：把 `TrackClient` 的两个出口接到 `GameApi`（`/ops/track/batch` 与 `/ops/crash`）。
 * 依赖：`GameApi`（不碰 `cc`，所以可单测）。
 *
 * <p><b>为什么单独一个文件</b>：`TrackClient` 刻意只认 `TrackTransport` 接口，
 * 这样它的攒批、重投、丢弃计数都能在单测里用假传输验证。真传输必须有人实现，
 * 否则那套机制就永远只是一组漂亮的单测 —— 这正是 #10 记过的那类"有入口没有调用点"。
 *
 * <p><b>两个方法都不抛异常**：`TrackClient` 按 boolean 决定是重投还是丢弃，
 * 而埋点的一条网络错误如果冒泡出去，表现就是"玩家点升级被一个统计上报搞失败了"。
 * 失败一律回 false，交给上层的计数与日志。
 */

import type { CrashInput, TrackTransport } from './TrackClient'
import type { TrackEventDraft } from './TrackQueue'
import type { TrackEvent } from '../../net/generated/OpsProtocol'
import type { GameApi } from '../session/GameApi'

export class ApiTrackTransport implements TrackTransport {
  private readonly api: GameApi
  private readonly clientVersion: string
  private readonly traceIdOf: () => string

  constructor(api: GameApi, clientVersion: string, traceIdOf: () => string = () => '') {
    this.api = api
    this.clientVersion = clientVersion
    this.traceIdOf = traceIdOf
  }

  async sendBatch(events: TrackEventDraft[]): Promise<boolean> {
    const mapped: TrackEvent[] = events.map(e => ({ name: e.name, ts: e.ts, params: e.params }))
    const outcome = await this.api.trackBatch(mapped)
    if (outcome.kind !== 'ok') {
      return false
    }
    // 服务端回了 accepted/failed。全批被拒时按"失败"处理让它走重投，
    // 而不是让这一批在客户端凭空消失（它已经出队了，重投是最后一次机会）
    return outcome.data.accepted > 0 || outcome.data.failed === 0
  }

  async reportCrash(crash: CrashInput): Promise<boolean> {
    const outcome = await this.api.reportCrash({
      traceId: crash.traceId || this.traceIdOf(),
      message: crash.message,
      stack: crash.stack,
      clientVersion: crash.clientVersion || this.clientVersion,
      sceneName: crash.sceneName,
      ts: Date.now(),
    })
    return outcome.kind === 'ok' && outcome.data.accepted
  }
}
