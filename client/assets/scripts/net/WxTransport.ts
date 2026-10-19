/**
 * 职责：微信小游戏平台的传输适配（wx.request / wx.connectSocket）。
 * 依赖：全局 wx 对象（类型声明见 client/types/cc.d.ts）、net/NetTransport 抽象。
 *
 * 这是 B00 指定的目标平台（WeChat Mini Game）的真实实现。
 * NetModule 只认抽象接口，因此这里的平台细节不会渗进重试/幂等/离线队列逻辑。
 */

import type { HttpTransport, HttpResponse, SocketCallbacks, SocketTransport } from './NetTransport'

/** wx.request 的 Promise 包装。 */
export class WxHttpTransport implements HttpTransport {
  /**
   * 单请求超时。**可被服务端下发的值覆盖**（见 `NetModule.applyNetworkTuning` 同一条理由：
   * 这个数住在 `global.json`，客户端没有配置表加载器 ⇒ 写死一份会与服务端悄悄漂移）。
   *
   * <p>之所以放在传输层而不是只用 `NetModule` 那一处：超时是**这一层**执行的
   * （`wx.request` 的 timeout 参数就在这里），`NetModule` 并不读它。
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
    return this.send('GET', url, undefined, headers)
  }

  private send(method: 'GET' | 'POST', url: string, data: string | undefined,
               headers: Readonly<Record<string, string>>): Promise<HttpResponse> {
    return new Promise<HttpResponse>((resolve, reject) => {
      const task = wx.request({
        url,
        method,
        // wx.request 在 header 为 application/json 时会自行序列化对象，
        // 这里传字符串并要求按原文发送，避免二次序列化把请求体变成带引号的字符串
        data,
        header: { ...headers },
        timeout: this.timeoutMs,
        success: (res) => {
          const text = typeof res.data === 'string' ? res.data : JSON.stringify(res.data)
          resolve({ status: res.statusCode, bodyText: text })
        },
        fail: (err) => reject(new Error(err.errMsg)),
      })
      // 微信的 timeout 在部分基础库版本上不生效，这里补一道主动超时，
      // 否则弱网下请求会永久挂着，重试逻辑永远等不到结果
      setTimeout(() => {
        task.abort()
        reject(new Error(`请求超时（${this.timeoutMs}ms）：${url}`))
      }, this.timeoutMs)
    })
  }
}

/** wx.connectSocket 的适配。一个实例对应一条连接，重连时必须新建。 */
export class WxSocketTransport implements SocketTransport {
  private readonly url: string
  private task: WxSocketTask | null = null
  private open = false

  constructor(url: string) {
    this.url = url
  }

  get isOpen(): boolean {
    return this.open
  }

  connect(callbacks: SocketCallbacks): void {
    this.task = wx.connectSocket({ url: this.url })
    this.task.onOpen(() => {
      this.open = true
      callbacks.onOpen()
    })
    this.task.onMessage((res) => callbacks.onMessage(res.data))
    this.task.onClose(() => {
      this.open = false
      callbacks.onClose('服务端关闭或网络中断')
    })
    this.task.onError((res) => {
      this.open = false
      callbacks.onError(res.errMsg)
    })
  }

  send(text: string): boolean {
    const task = this.task
    if (task === null || !this.open) {
      return false
    }
    let ok = true
    task.send({
      data: text,
      fail: () => {
        ok = false
      },
    })
    return ok
  }

  close(): void {
    this.open = false
    this.task?.close()
    this.task = null
  }
}
