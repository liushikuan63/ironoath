/**
 * 职责：浏览器/编辑器预览环境的 WebSocket 传输适配（全局 WebSocket）。
 * 依赖：浏览器标准 WebSocket（模块内声明最小结构类型，不依赖 DOM lib —— 与 WxTransport
 *       用 `declare const wx` 同一条路子）；net/NetTransport 抽象。
 *
 * <p>为什么单独一个文件：`socketFactory` 曾无条件返回 WxSocketTransport，
 * 浏览器里 WS 一连接就抛 `wx is not defined`（运行期量具抓到并钉了前后差值判据）。
 * 平台选择只发生在装配点一处，这里不做判定。
 */

import type { SocketCallbacks, SocketTransport } from './NetTransport'

/** 只声明本文件用到的那几样成员；DOM lib 缺席时也能编译。 */
interface BrowserWebSocketLike {
  onopen: ((event: unknown) => void) | null
  onmessage: ((event: { data: unknown }) => void) | null
  onerror: ((event: unknown) => void) | null
  onclose: ((event: { code: number; reason: string }) => void) | null
  send(data: string): void
  close(): void
}

declare const WebSocket: new (url: string) => BrowserWebSocketLike

export class BrowserSocketTransport implements SocketTransport {
  private readonly url: string
  private socket: BrowserWebSocketLike | null = null
  private open = false

  constructor(url: string) {
    this.url = url
  }

  get isOpen(): boolean {
    return this.open
  }

  connect(callbacks: SocketCallbacks): void {
    const socket = new WebSocket(this.url)
    this.socket = socket
    socket.onopen = () => {
      this.open = true
      callbacks.onOpen()
    }
    socket.onmessage = (event) => {
      // NetModule 的协议帧全是文本；二进制帧不静默丢，转成字符串让它到断言里可见
      callbacks.onMessage(typeof event.data === 'string' ? event.data : String(event.data))
    }
    socket.onerror = () => callbacks.onError('浏览器 WebSocket 错误')
    socket.onclose = (event) => {
      this.open = false
      callbacks.onClose(event.reason === '' ? `连接关闭（code=${event.code}）` : event.reason)
    }
  }

  send(text: string): boolean {
    if (this.socket === null || !this.open) {
      return false
    }
    this.socket.send(text)
    return true
  }

  close(): void {
    this.socket?.close()
    this.socket = null
    this.open = false
  }
}
