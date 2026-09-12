/**
 * 职责：全局异常捕获与崩溃上报的组装（B16 验收 9）。
 * 依赖：`TrackClient` 的 `CrashInput` 形状。**不 import `cc`**，所以整条链路能在 node:test 里跑。
 *
 * <p><b>为什么崩在上报路径上绝不能二次抛出</b>：这是异常处理路径，再抛一次就是二次崩溃 ——
 * 表现从"玩家闪退"变成"玩家闪退且没有任何日志"。所以 `handle()` 内部把所有失败咽成一行告警。
 *
 * <p><b>为什么要限流去重</b>：一帧里抛异常的 render 循环能在一秒里产出上千个相同错误，
 * 而崩溃上报是**直发不走攒批队列**的（它不能等攒批窗口 —— 进程可能马上就没了）。
 * 不去重就等于让一个 bug 把带宽和服务端日志一起打满，反而把同时间窗里其它人的崩溃挤掉。
 */

import type { CrashInput } from '../track/TrackClient'

/**
 * 堆栈压缩后的字符预算。
 *
 * <p>对应服务端的单次 payload 预算（`global.PERF_PAYLOAD_MAX_BYTES` = 20480）。这里取的是
 * **字符**数而不是字节数并留足余量：一条上报除堆栈外还有 message/traceId/版本等字段，
 * 而中文一字符可能占 3 字节。宁可压得比限制更狠，也不要让一条崩溃因为超预算被整条丢弃 ——
 * 被丢弃的崩溃比被截断的崩溃损失大得多。
 */
export const CRASH_STACK_BUDGET_CHARS = 6_000
/** 同一个错误摘要在这么长时间内只报一次。 */
export const CRASH_DEDUPE_WINDOW_MS = 60_000

/** 上报出口。`TrackClient.reportCrash` 与"埋点还没建好时的直发"都实现它。 */
export interface CrashSink {
  report(crash: CrashInput): void
}

/**
 * 取异常摘要的**第一行**。
 *
 * <p>协议注释点明了理由：某些异常的 message 里嵌着完整的请求体，
 * 整条塞进摘要会把这一条上报撑爆预算，而摘要要回答的只是"崩在哪一类错误上"。
 */
export function firstLine(text: string): string {
  const trimmed = text.split('\n')[0] ?? ''
  return trimmed.trim().slice(0, 500)
}

/**
 * 把堆栈压进预算：<b>保留头部与尾部，中间省略并标注省略了多少行</b>。
 *
 * <p>尾部给更大的份额：真正崩的那一帧在最深的位置，也就是尾部 —— 只留头部等于把答案丢掉。
 * 头尾都至少留一行：只剩一头的堆栈在排查时和没有差不多。
 */
export function compressStack(stack: string, budgetChars: number = CRASH_STACK_BUDGET_CHARS): string {
  if (stack.length <= budgetChars) {
    return stack
  }
  const lines = stack.split('\n')
  if (lines.length <= 2) {
    return stack.slice(0, budgetChars)
  }
  const headBudget = Math.floor(budgetChars * 0.35)
  const tailBudget = Math.floor(budgetChars * 0.55)

  const head: string[] = []
  let headSize = 0
  for (const line of lines) {
    if (headSize + line.length + 1 > headBudget) {
      break
    }
    head.push(line)
    headSize += line.length + 1
  }
  const tail: string[] = []
  let tailSize = 0
  for (let i = lines.length - 1; i >= head.length; i--) {
    const line = lines[i]
    if (line === undefined || tailSize + line.length + 1 > tailBudget) {
      break
    }
    tail.unshift(line)
    tailSize += line.length + 1
  }
  const elided = lines.length - head.length - tail.length
  if (elided <= 0) {
    return stack.slice(0, budgetChars)
  }
  return head.join('\n') + `\n…省略 ${elided} 行…\n` + tail.join('\n')
}

/** 从任意被抛出的东西里取出摘要与堆栈。JS 允许抛非 Error，所以这里必须兜住。 */
export function describeCrash(error: unknown, budgetChars: number = CRASH_STACK_BUDGET_CHARS):
  { message: string, stack: string } {
  if (error instanceof Error) {
    const raw = `${error.name}: ${error.message}`
    return {
      message: firstLine(raw),
      // 摘要与堆栈一起压：抛出深层嵌套时堆栈才是答案，而预算是全模块共享的
      stack: compressStack(`${raw}\n${error.stack ?? ''}`, budgetChars),
    }
  }
  const text = typeof error === 'string' ? error : safeString(error)
  return { message: firstLine(text), stack: compressStack(text, budgetChars) }
}

function safeString(value: unknown): string {
  try {
    return String(value)
  } catch {
    // toString 自己抛错的异常对象是真实存在的（循环引用的宿主对象等）
    return typeof value
  }
}

export interface CrashReporterDeps {
  readonly sink: CrashSink
  readonly clientVersion: string
  readonly sceneName: () => string | null
  readonly traceId: () => string
  readonly now: () => number
  readonly stackBudgetChars?: number
  readonly dedupeWindowMs?: number
}

export class CrashReporter {
  private readonly sink: CrashSink
  private readonly clientVersion: string
  private readonly sceneName: () => string | null
  private readonly traceId: () => string
  private readonly now: () => number
  private readonly stackBudget: number
  private readonly dedupeWindow: number
  /** 上一条崩溃摘要 → 它上次被上报的时刻。 */
  private readonly recent = new Map<string, number>()
  private reported = 0
  private suppressed = 0

  constructor(deps: CrashReporterDeps) {
    this.sink = deps.sink
    this.clientVersion = deps.clientVersion
    this.sceneName = deps.sceneName
    this.traceId = deps.traceId
    this.now = deps.now
    this.stackBudget = deps.stackBudgetChars ?? CRASH_STACK_BUDGET_CHARS
    this.dedupeWindow = deps.dedupeWindowMs ?? CRASH_DEDUPE_WINDOW_MS
  }

  /**
   * 处理一次未捕获异常。绝不抛出。
   *
   * @returns true 表示这一次真的交给了出口；false 表示被去重吞掉或组装失败。
   */
  handle(error: unknown): boolean {
    try {
      const { message, stack } = describeCrash(error, this.stackBudget)
      const at = this.now()
      const last = this.recent.get(message)
      if (last !== undefined && at - last < this.dedupeWindow) {
        this.suppressed++
        return false
      }
      // 先记时间再上报：上报本身也可能崩（比如 sink 里读坏了的 scene 名），
      // 不先记就会在同一个错误上反复重入
      this.recent.set(message, at)
      this.sink.report({
        traceId: this.traceId(),
        message,
        stack,
        clientVersion: this.clientVersion,
        sceneName: this.sceneName(),
      })
      this.reported++
      return true
    } catch (inner) {
      console.warn('[crash] 崩溃上报本身失败了', inner)
      return false
    }
  }

  get reportedCount(): number {
    return this.reported
  }

  get suppressedCount(): number {
    return this.suppressed
  }
}

/**
 * 安装平台的全局异常钩子，返回卸载函数。
 *
 * <p>微信小游戏与浏览器/编辑器预览的入口不同（`wx.onError` 与 `error`/`unhandledrejection` 事件），
 * 而两边都可能在同一环境里同时存在，所以**两个都装**而不是挑一个：漏掉 `unhandledrejection`
 * 的表现是"异步里崩了但永远看不到"，而那正是本项目最容易出问题的地方（弱网重试、Promise 链）。
 *
 * <p>没装成功（宿主既没有 wx 也没有 addEventListener）时返回一个空卸载函数并告警一次 ——
 * 静默不装等于把"线上没有任何崩溃日志"这件事藏起来。
 */
export function installGlobalHooks(report: (error: unknown) => void): () => void {
  const g = globalThis as Record<string, any>
  const cleanups: Array<() => void> = []

  const wx = g.wx
  if (wx && typeof wx.onError === 'function') {
    const handler = (res: any) => report(res && res.message ? res.message : res)
    wx.onError(handler)
    cleanups.push(() => {
      if (typeof wx.offError === 'function') {
        wx.offError(handler)
      }
    })
  }
  if (typeof g.addEventListener === 'function') {
    const onError = (event: any) => report(event?.error ?? event?.message ?? event)
    const onRejection = (event: any) => report(event?.reason ?? event)
    g.addEventListener('error', onError)
    g.addEventListener('unhandledrejection', onRejection)
    cleanups.push(() => {
      g.removeEventListener('error', onError)
      g.removeEventListener('unhandledrejection', onRejection)
    })
  }
  if (cleanups.length === 0) {
    console.warn('[crash] 宿主没有可用的全局异常钩子，线上将收不到任何崩溃上报')
  }
  return () => {
    for (const off of cleanups) {
      try {
        off()
      } catch (error) {
        console.warn('[crash] 卸载全局钩子失败', error)
      }
    }
  }
}
