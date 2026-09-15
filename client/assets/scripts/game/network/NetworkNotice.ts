/**
 * 职责：弱网时该对玩家说什么（B16 验收 2「不崩、请求有超时兜底与重试提示」的后半句，
 *       也是 `上线检查清单.md` §五 6 那条）。
 * 依赖：只有 {@code net/NetModule} 的信号<b>类型</b>（type-only，编译期擦除），
 *       不碰 `cc`、不碰传输实现，所以能在 node 里跑单测。
 *
 * <p><b>缺的不是机制而是这一句话</b>：退避重试早就实现了（`NetModule#sendWithRetry`，
 * 实测一次登录会重投 3 次），而全仓没有任何一处把"正在重试"告诉玩家 ——
 * `PanelTargets.error` 的落点是 `console.warn`（只有开发者看得见）。
 * 玩家的体感是"界面卡死了"，于是他会退出重进，而那恰好是弱网下最坏的动作。
 * 这条属于本仓库反复出现的那一族：<b>判定与机制都在，唯独没接到人能看见的地方</b>。
 *
 * <p><b>三句话各自的诚实性</b>（改文案前先读这段）：
 * <ul>
 *   <li>{@code retry} ⇒ 「正在重试（第 N 次）」。<b>不写"约 x 秒后"</b>：一次弱网里会有十几个
 *       面板各自重试，把某个请求的退避时长写成全局的"下一次"是假精确（它描述的其实不是任何一次）。</li>
 *   <li>{@code givenUp} ⇒ 「重试 N 次仍未接通」。<b>不再说"正在"</b>：最后一次已经失败了，
 *       继续说"正在重试"就是在撒谎，而玩家会据此一直等下去。</li>
 *   <li>{@code recovered} ⇒ 清空。驱动它的是"服务端真的答了"（HTTP 非 5xx，含业务拒绝），
 *       不是超时器到点 —— 所以不会出现"提示自己消失了但其实还没通"。</li>
 * </ul>
 *
 * <p><b>一次抖动内只显示一条，且只朝"更深"走</b>：并发的十几个请求各报各的会把一句提示
 * 刷成滚动字幕；而先看到"第 3 次"再被另一个请求的"第 1 次"覆盖，读起来像是网络在退步。
 * 所以计数取本次抖动里见过的最大值，直到 {@code recovered} 才归零。
 *
 * <p><b>这里刻意不管失败原因</b>：具体原因（业务码、服务端给的提示）由 `AppRoot` 走面板自己的
 * 那条路显示。把两者的文案合到一处，就等于让"操作被拒绝"和"网络不通"共用一句话，
 * 而玩家对这两件事的正确动作完全相反（改操作 vs 等网络）。
 */
import type { NetworkSignal } from '../../net/NetModule'

export type { NetworkSignal }

/**
 * 弱网提示的那一句话。
 *
 * <p>不是"事件总线"：它只保有一条文案与一个计数，没有任何订阅者列表 ——
 * 一条提示需要一个状态机的时候，多半是把它写成常量的时候了。
 */
export class NetworkNotice {
  private text: string | null = null
  /** 本次抖动里见过的最深一次重投。归零只发生在 {@code recovered}。 */
  private deepestAttempt = 0

  /** 当前该显示的话；null 表示这一行不该占屏幕。 */
  get current(): string | null {
    return this.text
  }

  observe(signal: NetworkSignal): void {
    if (signal.kind === 'recovered') {
      this.text = null
      this.deepestAttempt = 0
      return
    }
    if (signal.kind === 'givenUp') {
      this.text = `网络不稳定，重试 ${signal.attempts} 次仍未接通，请检查网络`
      return
    }
    // 只朝更深走：并发的另一条请求报出更小的次数时，不把已经显示的那句往回改
    if (signal.attempt > this.deepestAttempt) {
      this.deepestAttempt = signal.attempt
    }
    this.text = `网络不稳定，正在重试（第 ${this.deepestAttempt} 次）`
  }
}
