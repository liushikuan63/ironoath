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
  private readonly timeoutMs: number

  constructor(timeoutMs: number) {
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
