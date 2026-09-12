/**
 * 职责：单一状态 Store —— 不可变快照 + 按键 diff 刷新（B00 技术栈：客户端状态自研轻量 Store）。
 * 依赖：net/generated/Protocol（生成的契约类型）、core/EventBus。
 *
 * B01 禁止项：不引入 Redux / MobX。需要的能力只有「一份权威快照 + 变化时通知关心它的 UI」，
 * 100 行代码即可，而引入第三方状态库会把服务端权威的数值流向搞混（谁能改 store？）。
 *
 * 铁律 2、3 的落地方式：Store 里的每一个数值都来自服务端响应。
 * UI 层只<b>读</b> Store，唯一的写入方是收到服务端结果之后的适配层。
 * 客户端预测（如产出倒计时）在 UI 层用 serverNow 现算，不写回 Store ——
 * 这样「服务端结果到达 → 覆盖 Store → UI 自动纠偏」是一条单行道，不存在预测值污染权威值的可能。
 */

import type { PowerSnapshot, ResourceState } from '../../net/generated/Protocol'
import { gameBus } from '../../core/EventBus'
import type { Unsubscribe } from '../../core/EventBus'

/** 全局唯一的游戏状态快照。字段全部对应服务端下发的数据，客户端不自造字段。 */
export interface GameState {
  /** 玩家 id，未登录时为 null。 */
  readonly playerId: string | null
  readonly nickName: string
  readonly cityLevel: number
  /** 资源快照，键为 ResourceType。整体替换，不做原地修改。 */
  readonly resources: Readonly<Record<string, ResourceState>>
  readonly power: PowerSnapshot | null
  /** 新手保护到期时间（服务端时间戳）；null 表示无保护。 */
  readonly protectUntil: number | null
  /** 最近一次从服务端拿到的时间戳，配合 core/TimeSync 使用。 */
  readonly serverNow: number
  /** 网络是否可用。UI 据此显示断网提示条。 */
  readonly online: boolean
}

/** 单个字段的订阅回调。 */
export type FieldListener<K extends keyof GameState> = (value: GameState[K], state: GameState) => void

/** 整份快照的订阅回调，附带本次真正变化的字段名。 */
export type SnapshotListener = (state: GameState, changedKeys: ReadonlyArray<keyof GameState>) => void

/** 初始快照。所有数值都是「空」而不是「0」——0 会被误当成服务端给的真实值。 */
export const INITIAL_STATE: GameState = {
  playerId: null,
  nickName: '',
  cityLevel: 0,
  resources: {},
  power: null,
  protectUntil: null,
  serverNow: 0,
  online: true,
}

export class Store {
  private state: GameState
  private readonly fieldListeners = new Map<keyof GameState, Set<FieldListener<never>>>()
  private readonly snapshotListeners = new Set<SnapshotListener>()

  constructor(initial: GameState = INITIAL_STATE) {
    this.state = initial
  }

  /** 取当前快照。返回值不可变，调用方改它不会影响 Store。 */
  getState(): GameState {
    return this.state
  }

  /**
   * 合并一次更新，返回<b>真正发生变化</b>的字段名。
   *
   * 用 Object.is 做浅比较：值没变就不通知，避免「每次响应都刷一遍全屏 UI」造成的掉帧。
   * 这也是 diff 刷新的意义 —— 资源条只在资源真的变了时才重绘。
   */
  patch(partial: Partial<GameState>): ReadonlyArray<keyof GameState> {
    const previous = this.state
    const changed: Array<keyof GameState> = []
    const next: Record<string, unknown> = { ...previous }

    for (const key of Object.keys(partial) as Array<keyof GameState>) {
      const value = partial[key]
      if (value === undefined) {
        continue
      }
      if (!Object.is(previous[key], value)) {
        next[key as string] = value
        changed.push(key)
      }
    }

    if (changed.length === 0) {
      return []
    }

    this.state = Object.freeze(next) as unknown as GameState
    for (const key of changed) {
      const listeners = this.fieldListeners.get(key)
      if (listeners === undefined) {
        continue
      }
      // 复制一份再遍历：订阅者可能在回调里退订
            // 复制一份再遍历：订阅者可能在回调里退订。
      // 用 Array.from 而不是 [...listeners]：Cocos 的转译会把 iterable 的 spread 编成
      // `[].concat(set)`，运行时拿到的是「装着 Set 的数组」而不是元素 —— 表现为
      // 「订阅者抛出异常 TypeError: x is not a function」。数组的 spread 没有这个问题，
      // 但这里两种都统一成 Array.from，避免下次有人看到别的写法又改回来。
      for (const listener of Array.from(listeners)) {
        try {
          ;(listener as FieldListener<typeof key>)(this.state[key], this.state)
        } catch (error) {
          console.error(`[Store] 字段「${String(key)}」的订阅者抛出异常，已隔离`, error)
        }
      }
    }
        for (const listener of Array.from(this.snapshotListeners)) {
      try {
        listener(this.state, changed)
      } catch (error) {
        console.error('[Store] 快照订阅者抛出异常，已隔离', error)
      }
    }
    // 铁律 7：其它系统（任务、红点）通过事件总线感知变化，不直接 import Store 实例
    gameBus.emit('storeChanged', { keys: changed.map((key) => String(key)) })
    return changed
  }

  /** 订阅单个字段的变化。返回取消订阅函数。 */
  subscribe<K extends keyof GameState>(key: K, listener: FieldListener<K>): Unsubscribe {
    let set = this.fieldListeners.get(key)
    if (set === undefined) {
      set = new Set()
      this.fieldListeners.set(key, set)
    }
    set.add(listener as FieldListener<never>)
    let cancelled = false
    return () => {
      if (cancelled) {
        return
      }
      cancelled = true
      const current = this.fieldListeners.get(key)
      current?.delete(listener as FieldListener<never>)
      if (current !== undefined && current.size === 0) {
        this.fieldListeners.delete(key)
      }
    }
  }

  /** 订阅整份快照变化（用于需要跨字段联动的场景，如红点汇总）。 */
  subscribeAll(listener: SnapshotListener): Unsubscribe {
    this.snapshotListeners.add(listener)
    let cancelled = false
    return () => {
      if (cancelled) {
        return
      }
      cancelled = true
      this.snapshotListeners.delete(listener)
    }
  }

  /** 重置为初始快照（退出登录、切换账号）。会通知所有订阅者。 */
  reset(): void {
    this.patch({ ...INITIAL_STATE })
  }
}

/** 全局唯一 Store 实例。UI 只读它，写入集中在收到服务端响应的适配层。 */
export const gameStore = new Store()
