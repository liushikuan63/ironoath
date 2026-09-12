/**
 * 职责：EventBus 与 Store 单测 —— 验证事件隔离与「不可变快照 diff 刷新」。
 * 依赖：node:test / node:assert。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { EventBus } from '../core/EventBus'
import { INITIAL_STATE, Store } from '../game/store/Store'
import type { GameState } from '../game/store/Store'
import type { ResourceState } from '../net/generated/Protocol'

interface TestEvents extends Record<string, unknown> {
  hit: { value: number }
  bare: undefined
}

// ---------- EventBus ----------

test('EventBus：on 收到负载，返回的取消函数生效且幂等', () => {
  const bus = new EventBus<TestEvents>()
  const received: number[] = []
  const off = bus.on('hit', (payload) => received.push(payload.value))

  bus.emit('hit', { value: 1 })
  bus.emit('hit', { value: 2 })
  assert.deepEqual(received, [1, 2])

  off()
  off()
  bus.emit('hit', { value: 3 })
  assert.deepEqual(received, [1, 2])
  assert.equal(bus.listenerCount('hit'), 0)
})

test('EventBus：once 只触发一次并自动退订', () => {
  const bus = new EventBus<TestEvents>()
  let count = 0
  bus.once('hit', () => count++)
  bus.emit('hit', { value: 1 })
  bus.emit('hit', { value: 2 })
  assert.equal(count, 1)
  assert.equal(bus.listenerCount('hit'), 0)
})

test('EventBus：单个订阅者抛异常不影响其余订阅者（隔离）', () => {
  const bus = new EventBus<TestEvents>()
  const reached: string[] = []
  const originalError = console.error
  console.error = () => undefined
  try {
    bus.on('hit', () => {
      throw new Error('第一个订阅者坏了')
    })
    bus.on('hit', () => reached.push('second'))
    bus.on('hit', () => reached.push('third'))
    bus.emit('hit', { value: 1 })
  } finally {
    console.error = originalError
  }
  assert.deepEqual(reached, ['second', 'third'])
})

test('EventBus：订阅者在回调里退订不会导致漏发或重复发', () => {
  const bus = new EventBus<TestEvents>()
  const reached: string[] = []
  const offA = bus.on('hit', () => {
    reached.push('a')
    offA()
  })
  bus.on('hit', () => reached.push('b'))
  bus.emit('hit', { value: 1 })
  bus.emit('hit', { value: 2 })
  assert.deepEqual(reached, ['a', 'b', 'b'])
})

test('EventBus：off 不传 handler 时移除该事件全部订阅者；clear 清空全部', () => {
  const bus = new EventBus<TestEvents>()
  bus.on('hit', () => undefined)
  bus.on('hit', () => undefined)
  bus.on('bare', () => undefined)
  bus.off('hit')
  assert.equal(bus.listenerCount('hit'), 0)
  assert.equal(bus.listenerCount('bare'), 1)
  bus.clear()
  assert.equal(bus.listenerCount('bare'), 0)
})

// ---------- Store ----------

function resourceState(current: number, cap: number): ResourceState {
  return { current, cap, protectedAmount: 0, perHour: 100, lastSettle: 0 }
}

test('Store：patch 只通知真正变化的字段（diff 刷新）', () => {
  const store = new Store()
  const hits: string[] = []
  store.subscribe('cityLevel', () => hits.push('cityLevel'))
  store.subscribe('nickName', () => hits.push('nickName'))

  assert.deepEqual(store.patch({ cityLevel: 2 }), ['cityLevel'])
  assert.deepEqual(hits, ['cityLevel'])

  // 值没变 → 不通知，避免每次响应都重绘整个 UI
  assert.deepEqual(store.patch({ cityLevel: 2 }), [])
  assert.deepEqual(hits, ['cityLevel'])

  assert.deepEqual(store.patch({ nickName: '铁誓' }), ['nickName'])
  assert.deepEqual(hits, ['cityLevel', 'nickName'])
})

test('Store：快照不可变，patch 产生新对象', () => {
  const store = new Store()
  const before = store.getState()
  store.patch({ cityLevel: 5 })
  const after = store.getState()

  assert.notEqual(before, after)
  assert.equal(before.cityLevel, INITIAL_STATE.cityLevel)
  assert.equal(after.cityLevel, 5)
  assert.throws(() => {
    ;(after as { cityLevel: number }).cityLevel = 99
  }, TypeError)
})

test('Store：subscribeAll 收到变化字段列表；订阅者异常被隔离', () => {
  const store = new Store()
  const snapshots: Array<ReadonlyArray<keyof GameState>> = []
  store.subscribeAll((_state, keys) => snapshots.push(keys))

  const originalError = console.error
  console.error = () => undefined
  try {
    store.subscribeAll(() => {
      throw new Error('坏的订阅者')
    })
    store.patch({ cityLevel: 3, online: false })
  } finally {
    console.error = originalError
  }
  assert.deepEqual(snapshots[0], ['cityLevel', 'online'])
})

test('Store：整体替换 resources 会触发通知（引用不同即视为变化）', () => {
  const store = new Store()
  let notified = 0
  store.subscribe('resources', () => notified++)

  store.patch({ resources: { WOOD: resourceState(100, 200) } })
  store.patch({ resources: { WOOD: resourceState(100, 200) } })
  assert.equal(notified, 2, '内容相同但引用不同也应通知：服务端每次下发的都是新快照')

  const same = store.getState().resources
  store.patch({ resources: same })
  assert.equal(notified, 2, '同一引用不应通知')
})

test('Store：reset 回到初始快照并通知订阅者', () => {
  const store = new Store()
  store.patch({ playerId: 'P1', cityLevel: 8, online: false })
  let notified = 0
  store.subscribeAll(() => notified++)

  store.reset()
  assert.equal(store.getState().playerId, null)
  assert.equal(store.getState().cityLevel, 0)
  assert.equal(store.getState().online, true)
  assert.equal(notified, 1)
})

test('Store：退订函数幂等，重复调用不会误删其他订阅者', () => {
  const store = new Store()
  let a = 0
  let b = 0
  const offA = store.subscribe('cityLevel', () => a++)
  store.subscribe('cityLevel', () => b++)

  offA()
  offA()
  store.patch({ cityLevel: 2 })
  assert.equal(a, 0)
  assert.equal(b, 1)
})
