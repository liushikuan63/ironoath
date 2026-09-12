/**
 * 职责：TrackQueue 单测 —— B16 §3「客户端批量上报（10 条或 10 秒触发）」，验收 3。
 * 依赖：node:test / node:assert。
 *
 * **B16 的禁止项把「不要逐条上报」写了两遍**，而它在功能测试里永远测不出来：
 * 逐条上报的实现同样能把事件送到服务端，验收 3「所有关键节点均有事件」照样全绿。
 * 它会死在弱网真机上 —— 十几个事件就是十几个 HTTP 请求，每个都可能超时重试。
 * 所以本类的断言盯的是**请求数**而不是**事件数**：不足一批时必须一个请求都不发。
 *
 * 另一条容易被漏掉的是重试缓冲区的上限：待发队列被 maxBatchSize 从构造上限死，
 * 但「交出去却发不出去」的批次会累积，而一次长时间断网足以让内存被埋点撑爆 ——
 * 埋点撑爆内存导致游戏崩溃，丢的数据比攒着不发多得多。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { TrackQueue } from '../assets/scripts/game/track/TrackQueue'
import type { TrackBatch, TrackEventDraft, TrackQueueRules } from '../assets/scripts/game/track/TrackQueue'

const SECOND = 1000

/** 默认规则对应 global.TRACK_BATCH_MAX_SIZE=10 / TRACK_BATCH_FLUSH_SECONDS=10。 */
function makeQueue(overrides: Partial<TrackQueueRules> = {}): TrackQueue {
  return new TrackQueue({ maxBatchSize: 10, flushIntervalMs: 10 * SECOND, maxUnsentBatches: 3, ...overrides })
}

function event(name: string, ts: number): TrackEventDraft {
  return { name, ts, params: { playerId: 'p1' } }
}

/** 灌 count 条事件，收集期间交出的批次。 */
function fill(queue: TrackQueue, count: number, fromTs = 1000): TrackBatch[] {
  const issued: TrackBatch[] = []
  for (let i = 0; i < count; i++) {
    const batch = queue.track(event(`evt_${i}`, fromTs + i))
    if (batch !== null) {
      issued.push(batch)
    }
  }
  return issued
}

test('禁止项：攒满 10 条才交出一批，前 9 条一个请求都不发', () => {
  const queue = makeQueue()
  const issued = fill(queue, 9)
  assert.deepEqual(issued, [], '不足一批时不该产生任何待发送的批次')
  assert.equal(queue.pendingCount(), 9)
  assert.equal(queue.issuedBatchCount(), 0)

  const tenth = queue.track(event('evt_9', 1009))
  assert.notEqual(tenth, null, '第 10 条应当触发')
  assert.equal(tenth?.events.length, 10)
  assert.match(tenth?.reason ?? '', /攒满/)
  assert.equal(queue.pendingCount(), 0)
  assert.equal(queue.issuedBatchCount(), 1)
})

test('攒不满时超过 10 秒也要发：否则「点两下就退出」的玩家那两个事件永远发不出去', () => {
  const queue = makeQueue()
  fill(queue, 2)

  assert.equal(queue.tick(1000 + 9 * SECOND), null, '距首条事件才 9 秒')
  const batch = queue.tick(1000 + 10 * SECOND)
  assert.notEqual(batch, null, '距首条事件 10 秒 ⇒ 触发')
  assert.equal(batch?.events.length, 2)
  assert.match(batch?.reason ?? '', /秒/)
  assert.equal(queue.pendingCount(), 0)
})

test('时间窗从队首事件算起，而不是从上一次发送后算起', () => {
  const queue = makeQueue({ maxBatchSize: 100 })
  queue.track(event('a', 0))
  queue.track(event('b', 9 * SECOND))
  // 距队首已过 10 秒 ⇒ 该发，即使第二条才进来 1 秒
  assert.notEqual(queue.tick(10 * SECOND), null)
})

test('切后台/崩溃前可强制冲刷；空队列不产生空批', () => {
  const queue = makeQueue()
  queue.track(event('a', 1000))
  assert.equal(queue.flushNow('切后台')?.events.length, 1)
  assert.equal(queue.flushNow('再切一次'), null, '空队列不该交出一个空批次')
  assert.equal(queue.pendingCount(), 0)
})

test('内存不变量：无论灌多少条，待发队列恒小于 maxBatchSize', () => {
  const queue = makeQueue({ maxBatchSize: 10 })
  for (let i = 0; i < 50; i++) {
    queue.track(event(`evt_${i}`, i))
    assert.ok(queue.pendingCount() < 10, `灌到第 ${i + 1} 条时待发队列不该达到上限`)
  }
  assert.equal(queue.pendingCount(), 0, '50 条正好五批，全部交出')
  assert.equal(queue.issuedBatchCount(), 5)
})

test('事件名与时间戳在客户端这一侧就被拒（边界校验，不发到服务端再被计入 failed）', () => {
  const queue = makeQueue()
  assert.throws(() => queue.track({ name: ' ', ts: 1, params: {} }), /事件名不得为空/)
  assert.throws(() => queue.track({ name: 'x', ts: Number.NaN, params: {} }), /有限数/)
  // 非有限时间戳会让攒批窗口的比较永远为 false，于是这一批永远不发 —— 所以必须当场拒绝
  assert.throws(() => queue.track({ name: 'x', ts: Number.POSITIVE_INFINITY, params: {} }), /有限数/)
  assert.equal(queue.pendingCount(), 0, '被拒的事件不该进队列')
})

test('入队的事件被复制：调用方之后改自己的 params 不会影响已攒下的那一条', () => {
  const queue = makeQueue({ maxBatchSize: 1 })
  const params: Record<string, string> = { step: '1' }
  const batch = queue.track({ name: 'guide_step', ts: 1, params })
  params.step = '被改了'
  assert.equal(batch?.events[0]?.params.step, '1')
})

test('发送失败可退回重投，且退回的批优先于新攒的批（旧批更接近丢失）', () => {
  const queue = makeQueue({ maxBatchSize: 2 })
  queue.track(event('a', 1))
  const first = queue.track(event('b', 2))
  assert.notEqual(first, null, '攒满 2 条应当交出一批')
  queue.requeue(first as TrackBatch)
  assert.equal(queue.unsentBatchCount(), 1)

  const retry = queue.takeUnsent()
  assert.deepEqual(retry?.events.map((e) => e.name), ['a', 'b'])
  assert.equal(queue.unsentBatchCount(), 0)
  assert.equal(queue.takeUnsent(), null, '没有待重投的批时返回 null')
})

test('重试缓冲区满时丢最旧的一批并计数：静默丢弃会让漏斗莫名其妙少一环', () => {
  const queue = makeQueue({ maxBatchSize: 1, maxUnsentBatches: 2 })
  const batches: TrackBatch[] = []
  for (let i = 0; i < 4; i++) {
    const batch = queue.track(event(`evt_${i}`, i))
    if (batch !== null) {
      batches.push(batch)
    }
  }
  for (const batch of batches) {
    queue.requeue(batch)
  }
  assert.equal(queue.unsentBatchCount(), 2, '上限 2 批')
  assert.equal(queue.droppedBatchCount(), 2, '4 批退回、只留 2 批 ⇒ 丢了 2 批')
  // 丢的是最旧的：留下的应当是 evt_2 与 evt_3
  assert.equal(queue.takeUnsent()?.events[0]?.name, 'evt_2')
  assert.equal(queue.takeUnsent()?.events[0]?.name, 'evt_3')
})

test('规则构造期校验：flushIntervalMs 为 0 等于逐条上报，maxUnsentBatches 为 0 等于失败就丢', () => {
  assert.throws(() => makeQueue({ maxBatchSize: 0 }), /maxBatchSize/)
  assert.throws(() => makeQueue({ flushIntervalMs: 0 }), /逐条上报/)
  assert.throws(() => makeQueue({ maxUnsentBatches: 0 }), /弱网下埋点会全丢/)
})

test('端到端守恒：每条事件都有下落（已送达 + 仍在重试缓冲 + 已丢），一条都不能凭空消失', () => {
  const maxBatch = 3
  const queue = makeQueue({ maxBatchSize: maxBatch, maxUnsentBatches: 2 })
  const total = 20
  let delivered = 0
  let issued = 0
  for (let i = 0; i < total; i++) {
    const batch = queue.track(event(`evt_${i}`, i * 10))
    if (batch !== null) {
      issued++
      // 模拟弱网：奇数批发不出去，退回等重投
      if (issued % 2 === 1) {
        queue.requeue(batch)
      } else {
        delivered += batch.events.length
      }
    }
  }
  const rest = queue.flushNow('测试结束')
  if (rest !== null) {
    delivered += rest.events.length
  }

  let waiting = 0
  for (let batch = queue.takeUnsent(); batch !== null; batch = queue.takeUnsent()) {
    waiting += batch.events.length
  }
  const dropped = queue.droppedBatchCount() * maxBatch

  assert.equal(queue.pendingCount(), 0, '结束时待发队列应当已清空')
  assert.ok(delivered > 0, '弱网下也必须有一部分送达')
  assert.ok(dropped > 0, '退回的批超过缓冲区上限时确实发生了丢弃')
  assert.equal(delivered + waiting + dropped, total,
    `已送达 ${delivered} + 待重投 ${waiting} + 已丢 ${dropped} 必须等于总数 ${total}`)
})
