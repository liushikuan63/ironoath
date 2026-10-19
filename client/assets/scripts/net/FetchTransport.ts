/**
 * 职责：基于 fetch 的 HTTP 传输适配 —— 用于 Cocos 浏览器预览与本地联调。
 * 依赖：全局 fetch、net/NetTransport 抽象。
 *
 * 真机（微信小游戏）走 WxHttpTransport；浏览器预览走这里。
 * 两者对 NetModule 完全等价，所以「浏览器里跑通、真机上跑不通」的问题只会出在平台差异，
 * 不会出在重试/幂等逻辑上 —— 那些逻辑已经被 node 单测覆盖。
 */

import type { HttpTransport, HttpResponse } from './NetTransport'

export class FetchHttpTransport implements HttpTransport {
  /**
   * 单请求超时。**可被服务端下发的值覆盖**（见 `NetModule.applyNetworkTuning` 同一条理由：
   * 这个数住在 `global.json`，客户端没有配置表加载器 ⇒ 写死一份会与服务端悄悄漂移）。
   *
   * <p>之所以放在传输层而不是只用 `NetModule` 那一处：超时是**这一层**执行的
   * （`AbortController` 的 abort 定时器就在这里），`NetModule` 并不读它。
   */
  private timeoutMs: number

  constructor(timeoutMs: number) {
    if (timeoutMs <= 0) {
      throw new RangeError(`timeoutMs 必须为正数：${timeoutMs}`)
    }
    this.timeoutMs = timeoutMs
  }

  /**
   * 用服务端下发的超时覆盖当前值。
   *
   * <p>与 `NetModule.applyNetworkTuning` 同一时刻调用（版本检查响应到达后）。
   * 只影响**之后**发起的请求：已经发出的那个请求用的是旧值（第一个请求必然如此）。
   */
  applyTimeout(timeoutMs: number): void {
    if (timeoutMs <= 0) {
      throw new RangeError(`timeoutMs 必须为正数：${timeoutMs}`)
    }
    this.timeoutMs = timeoutMs
  }

  post(url: string, bodyText: string, headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    return this.send('POST', url, bodyText, headers)
  }

  get(url: string, headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    return this.send('GET', url, null, headers)
  }

  /** GET 与 POST 唯一的差别是有没有 body，超时与 abort 逻辑完全一致，所以共用一条路径。 */
  private async send(method: 'GET' | 'POST', url: string, bodyText: string | null,
                     headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), this.timeoutMs)
    try {
      const response = await fetch(url, {
        method,
        headers: { ...headers },
        body: bodyText,
        signal: controller.signal,
      })
      return { status: response.status, bodyText: await response.text() }
    } finally {
      clearTimeout(timer)
    }
  }
}
