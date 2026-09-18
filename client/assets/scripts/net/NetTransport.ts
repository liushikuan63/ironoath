/**
 * 职责：网络传输层抽象 —— 把「怎么发字节」与「怎么组织请求」分开。
 * 依赖：无（纯类型定义）。
 *
 * 为什么要抽象：NetModule 的重试、幂等、离线队列逻辑必须能在 node 环境跑单测
 * （B00 铁律 2：客户端玩法逻辑不 import 任何 cc 渲染模块，同理也不应绑死平台 API）。
 * 真机上注入 WxTransport，单测里注入 Fake，两者对 NetModule 完全等价。
 */

/** HTTP 响应。只保留决策需要的字段。 */
export interface HttpResponse {
  readonly status: number
  readonly bodyText: string
}

/** HTTP 传输实现。 */
export interface HttpTransport {
  /**
   * 发起 POST 请求。
   *
   * 实现<b>不应</b>对网络错误做重试 —— 重试策略属于 NetModule（需要配合幂等键判断能否安全重试）。
   * 网络层失败请 reject，业务状态码请照常 resolve。
   */
  post(url: string, bodyText: string, headers: Readonly<Record<string, string>>): Promise<HttpResponse>

  /**
   * 发起 GET 请求。约束与 {@link HttpTransport#post} 相同：不自己重试，网络失败 reject。
   *
   * <p>服务端 12 个 Controller 里有 11 个读端点是 @GetMapping（列表、详情、战报），
   * 没有 get 就等于这些接口一个都调不通。查询串由 NetModule 拼好后放进 url，
   * 传输层只负责把这个 url 发出去 —— 编码规则只有一份，不该在两个传输实现里各写一遍。
   */
  get(url: string, headers: Readonly<Record<string, string>>): Promise<HttpResponse>
}

/** WebSocket 事件回调。 */
export interface SocketCallbacks {
  onOpen(): void
  onMessage(text: string): void
  onClose(reason: string): void
  onError(message: string): void
}

/** WebSocket 传输实现。 */
export interface SocketTransport {
  connect(callbacks: SocketCallbacks): void
  /** 发送文本帧。连接不可用时返回 false，不抛异常。 */
  send(text: string): boolean
  close(): void
  readonly isOpen: boolean
}

/**
 * 创建 SocketTransport 的工厂。重连时需要一个全新的实例。
 *
 * <p>**参数是"这一次要连的完整 URL"**，而不是让实现自己记住配置里的 `wsUrl`：
 * 握手 URL 要带 `playerId` 与票据（服务端握手阶段校验用；小游戏 WebSocket 设不了自定义头），
 * 而票据会换 —— 工厂若被烤成无参，重连就一直带着第一次打开时的那枚旧票。
 */
export type SocketFactory = (url: string) => SocketTransport
