/**
 * 职责：轻量事件总线 —— 系统间解耦的唯一通道（B00 铁律 7）。
 * 依赖：无（引擎无关，可脱离 Cocos 跑单测）。
 *
 * 铁律 7 要求：系统间禁止直接互相 import 实例。BUILDING_UPGRADED 之类的领域事件发出后，
 * 任务、红点、成就各自订阅，彼此不知道对方存在。这样加一个新系统不需要改任何已有系统。
 *
 * 刻意不引入第三方事件库（B01 禁止项：不引入 Redux/MobX 等）：
 * 需要的能力只有 on/once/off/emit 四个，20 行代码比一个依赖更安全。
 */

/** 事件处理函数。 */
export type Handler<T> = (payload: T) => void

/** 取消订阅函数。 */
export type Unsubscribe = () => void

/**
 * 类型安全的事件总线。
 *
 * Events 是一个「事件名 → 负载类型」的映射。用它做泛型约束后，
 * `emit('x', 错误的负载)` 会在编译期报错，而不是在运行时静默传 undefined。
 */
export class EventBus<Events extends Record<string, unknown>> {
  private readonly listeners = new Map<keyof Events, Array<Handler<never>>>()

  /** 订阅事件，返回取消订阅函数。 */
  on<K extends keyof Events>(type: K, handler: Handler<Events[K]>): Unsubscribe {
    const list = this.listeners.get(type)
    if (list === undefined) {
      this.listeners.set(type, [handler as Handler<never>])
    } else {
      list.push(handler as Handler<never>)
    }
    let cancelled = false
    return () => {
      if (cancelled) {
        return
      }
      cancelled = true
      this.off(type, handler)
    }
  }

  /** 订阅一次，触发后自动移除。 */
  once<K extends keyof Events>(type: K, handler: Handler<Events[K]>): Unsubscribe {
    const unsubscribe = this.on(type, (payload) => {
      unsubscribe()
      handler(payload)
    })
    return unsubscribe
  }

  /** 取消订阅。handler 未提供时移除该事件的全部订阅者。 */
  off<K extends keyof Events>(type: K, handler?: Handler<Events[K]>): void {
    const list = this.listeners.get(type)
    if (list === undefined) {
      return
    }
    if (handler === undefined) {
      this.listeners.delete(type)
      return
    }
    const index = list.indexOf(handler as Handler<never>)
    if (index >= 0) {
      list.splice(index, 1)
    }
    if (list.length === 0) {
      this.listeners.delete(type)
    }
  }

  /**
   * 发布事件。
   *
   * 单个订阅者抛异常不会中断其余订阅者：否则一个红点系统的 bug 会让任务系统也收不到事件，
   * 而排查时看到的是「任务不刷新」，完全指不到真正的故障点。
   */
  emit<K extends keyof Events>(type: K, payload: Events[K]): void {
    const list = this.listeners.get(type)
    if (list === undefined || list.length === 0) {
      return
    }
    // 复制一份再遍历：订阅者可能在回调里 on/off，直接遍历原数组会漏发或重复发
    for (const handler of [...list]) {
      try {
        ;(handler as Handler<Events[K]>)(payload)
      } catch (error) {
        console.error(`[EventBus] 事件「${String(type)}」的订阅者抛出异常，已隔离，不影响其余订阅者`, error)
      }
    }
  }

  /** 某事件的订阅者数量，用于单测与排查「事件没人订阅」。 */
  listenerCount<K extends keyof Events>(type: K): number {
    return this.listeners.get(type)?.length ?? 0
  }

  /** 清空全部订阅。切换场景或重登录时调用，防止旧场景的订阅者被新数据唤醒。 */
  clear(): void {
    this.listeners.clear()
  }
}

/**
 * 全项目事件目录。
 *
 * 新增事件必须在这里登记负载类型 —— 这是「事件契约」，
 * 让订阅方与发布方对负载结构达成一致，而不是靠 emit 处随手写的对象字面量。
 *
 * B01 只登记骨架期需要的事件；B03 起按批次追加
 * （buildingUpgraded / resourceSettled / battleResult / marchArrived / allianceChat ...）。
 */
export interface GameEventMap extends Record<string, unknown> {
  /** 与服务端的连接建立（含首帧下发的服务端时间与心跳间隔）。 */
  netConnected: { serverNow: number; heartbeatSeconds: number }
  /** 连接断开。reason 用于决定是静默重连还是提示玩家。 */
  netDisconnected: { reason: string }
  /** 重连成功。订阅方应据此补拉离线期间的数据。 */
  netReconnected: { downtimeMs: number }
  /** 收到服务端主动推送。type 为服务端消息类型，data 为业务负载。 */
  serverPush: { type: string; data: unknown }
  /** 玩家存档初始化完成（登录或建号）。 */
  playerReady: { playerId: string; isNewPlayer: boolean }
  /** Store 快照发生变化，keys 为本次变化的字段名。 */
  storeChanged: { keys: string[] }
  /** 离线队列中的请求被重放。 */
  offlineQueueReplayed: { count: number }
  /**
   * 从任意响应/心跳中拿到的服务端时间提示。
   * 每个 HTTP 响应都带 serverNow，等于免费获得一次时钟校准机会，不需要单独的心跳同步请求。
   */
  serverTimeHint: { serverNow: number; localNow: number }
}

/** 全局事件总线。系统间只通过它通信，不互相 import 实例（铁律 7）。 */
export const gameBus = new EventBus<GameEventMap>()
