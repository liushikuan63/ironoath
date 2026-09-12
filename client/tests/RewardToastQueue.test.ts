/**
 * 职责：RewardToastQueue 单测 —— B04 §6「多个奖励按顺序播放，不可同时堆叠遮挡」（验收 8）。
 * 依赖：node:test / node:assert。
 *
 * 验收 8 原文的判定方式是「手工」，因为要看动效。但「不重叠、按序、卡住能自愈」
 * 这三件事是纯逻辑，可以自动化 —— 而它们恰恰是手工测试最容易漏的：
 * 手工只会点一次领奖看一条飘字，不会构造「第三条动效永不结束」这种场景。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { RewardToastQueue } from '../assets/scripts/game/reward/RewardToastQueue'
import type { RewardToast, ToastPresenter } from '../assets/scripts/game/reward/RewardToastQueue'

/** 一个可以手工控制「什么时候播完」的 presenter，并记录时间线以断言不重叠。 */
class ManualPresenter implements ToastPresenter {
  /** 每条飘字的开始顺序。 */
  readonly started: string[] = []
  /** 已经播完（resolve）的飘字 id。 */
  readonly finished: string[] = []
  /** 当前同时在播的条数峰值。大于 1 就说明发生了堆叠。 */
  concurrentPeak = 0
  private live = 0
  private readonly gates = new Map<string, () => void>()
  /** 设为 true 时 play 直接抛异常，用于验证异常隔离。 */
  throwOn: string | null = null
  /** 设为 true 时 play 返回永不 resolve 的 Promise，用于验证卡死兜底。 */
  hangOn: string | null = null

  play(toast: RewardToast): Promise<void> {
    this.started.push(toast.id)
    this.live++
    this.concurrentPeak = Math.max(this.concurrentPeak, this.live)
    if (this.throwOn === toast.id) {
      this.live--
      return Promise.reject(new Error(`动效 ${toast.id} 出错`))
    }
    if (this.hangOn === toast.id) {
      // 永不 resolve 也不 reject：这正是最危险的失败模式，日志里不会有任何异常
      return new Promise<void>(() => {})
    }
    return new Promise<void>((resolve) => {
      this.gates.set(toast.id, () => {
        this.live--
        this.finished.push(toast.id)
        resolve()
      })
    })
  }

  /** 让某条飘字播完。 */
  finish(id: string): void {
    const gate = this.gates.get(id)
    assert.ok(gate !== undefined, `飘字 ${id} 还没开始播或已经播完了`)
    this.gates.delete(id)
    gate()
  }
}

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0))
const wait = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

function toast(id: string, text = `+${id}`): RewardToast {
  return { id, text, kind: 'resource' }
}

function queue(presenter: ToastPresenter, overrides: Record<string, number> = {}) {
  return new RewardToastQueue(presenter, makeOptions(overrides))
}

function makeOptions(overrides: Record<string, number> = {}) {
  return {
    gapMs: 0,
    maxQueued: 12,
    stuckTimeoutMs: 5_000,
    ...overrides,
  }
}

// ---------- 验收 8：按序播放、不重叠 ----------

test('验收8：多条奖励严格按入队顺序播放，任何时刻只有一条在播', async () => {
  const presenter = new ManualPresenter()
  const q = queue(presenter)
  q.enqueue([toast('a'), toast('b'), toast('c')])

  await tick()
  assert.deepEqual(presenter.started, ['a'], '第一条应立刻开始')
  assert.equal(q.playing?.id, 'a')
  assert.equal(presenter.concurrentPeak, 1, '第一条还在播时不能起第二条')

  presenter.finish('a')
  await tick()
  assert.deepEqual(presenter.started, ['a', 'b'], '前一条播完才能播下一条')

  presenter.finish('b')
  await tick()
  presenter.finish('c')
  await tick()

  assert.deepEqual(presenter.finished, ['a', 'b', 'c'])
  assert.equal(presenter.concurrentPeak, 1, '全程不得出现两条同时播（B04 禁止项：不得堆叠遮挡）')
  assert.equal(q.pending, 0)
  assert.equal(q.idle, true)
})

test('连续 enqueue 不会并发起两个播放循环', async () => {
  const presenter = new ManualPresenter()
  const q = queue(presenter)
  q.enqueue([toast('a')])
  q.enqueue([toast('b')])
  q.enqueue([toast('c')])

  await tick()
  assert.deepEqual(presenter.started, ['a'], '三次 enqueue 之后仍然只有一条在播')
  assert.equal(presenter.concurrentPeak, 1)
  assert.equal(q.pending, 3)

  presenter.finish('a')
  await tick()
  presenter.finish('b')
  await tick()
  presenter.finish('c')
  await tick()
  assert.deepEqual(presenter.finished, ['a', 'b', 'c'])
  assert.equal(presenter.concurrentPeak, 1)
})

test('批量开箱的聚合结果按服务端给的顺序播放（顺序即飘字顺序）', async () => {
  const presenter = new ManualPresenter()
  const q = queue(presenter)
  // 服务端 openBatch 按掉落表顺序聚合，客户端不得重排
  q.enqueue([toast('SR碎片'), toast('木材'), toast('石料'), toast('金币')])
  await tick()
  presenter.finish('SR碎片')
  await tick()
  presenter.finish('木材')
  await tick()
  presenter.finish('石料')
  await tick()
  presenter.finish('金币')
  await tick()
  assert.deepEqual(presenter.finished, ['SR碎片', '木材', '石料', '金币'])
})

// ---------- 失败模式 1：动效抛异常 ----------

test('一条动效抛异常不影响后续：异常被隔离，队列继续消费', async () => {
  const presenter = new ManualPresenter()
  presenter.throwOn = 'b'
  const q = queue(presenter)
  q.enqueue([toast('a'), toast('b'), toast('c')])

  await tick()
  presenter.finish('a')
  await tick()
  // b 立刻 reject，队列应自动推进到 c
  await tick()
  assert.deepEqual(presenter.started, ['a', 'b', 'c'], '出错的那条之后的奖励必须继续播')
  presenter.finish('c')
  await tick()
  assert.equal(q.idle, true)
  assert.equal(q.pending, 0)
})

// ---------- 失败模式 2：动效永不结束 ----------

test('卡住的动效被 stuckTimeoutMs 强制推进，队列不会永久冻结', async () => {
  const presenter = new ManualPresenter()
  presenter.hangOn = 'b'
  const q = queue(presenter, { stuckTimeoutMs: 30 })
  q.enqueue([toast('a'), toast('b'), toast('c')])

  await tick()
  presenter.finish('a')
  await tick()
  assert.equal(q.playing?.id, 'b', 'b 开始播放后就会卡住')

  // 卡住的这条永不 resolve；若无超时兜底，c 永远不会播，玩家此后再也看不到任何飘字
  await wait(80)
  assert.deepEqual(presenter.started, ['a', 'b', 'c'], '超时后必须推进到 c')
  presenter.finish('c')
  await tick()
  assert.equal(q.idle, true, '卡死过一条之后队列必须能回到空闲')
})

// ---------- 失败模式 3：队列被灌爆 ----------

test('超过 maxQueued 的部分被丢弃并计数，已入队的照常播完', async () => {
  const presenter = new ManualPresenter()
  const q = queue(presenter, { maxQueued: 3 })
  const batch = [toast('a'), toast('b'), toast('c'), toast('d'), toast('e')]

  const accepted = q.enqueue(batch)
  assert.equal(accepted, 3, '只接受前 3 条')
  assert.equal(q.droppedCount, 2)

  await tick()
  presenter.finish('a')
  await tick()
  presenter.finish('b')
  await tick()
  presenter.finish('c')
  await tick()
  assert.deepEqual(presenter.finished, ['a', 'b', 'c'])
  assert.equal(q.idle, true)
})

test('队列有空位时后续 enqueue 仍然能被接受（丢弃不是永久状态）', async () => {
  const presenter = new ManualPresenter()
  const q = queue(presenter, { maxQueued: 2 })
  assert.equal(q.enqueue([toast('a'), toast('b'), toast('c')]), 2)
  await tick()
  presenter.finish('a')
  await tick()
  assert.equal(q.enqueue([toast('d')]), 1, 'a 播完腾出位置后应能继续入队')
  presenter.finish('b')
  await tick()
  presenter.finish('d')
  await tick()
  assert.deepEqual(presenter.finished, ['a', 'b', 'd'])
  assert.equal(q.droppedCount, 1)
})

// ---------- 间隔与清空 ----------

test('gapMs 让两条飘字之间留出可分辨的间隔', async () => {
  const presenter = new ManualPresenter()
  const q = queue(presenter, { gapMs: 40 })
  q.enqueue([toast('a'), toast('b')])

  await tick()
  presenter.finish('a')
  await tick()
  assert.deepEqual(presenter.started, ['a'], '间隔期内不能起下一条')
  await wait(70)
  assert.deepEqual(presenter.started, ['a', 'b'], '间隔结束后应起下一条')
  presenter.finish('b')
  await tick()
  assert.equal(q.idle, true)
})

test('clear 丢弃尚未播放的飘字，正在播的那条播完后队列归于空闲', async () => {
  const presenter = new ManualPresenter()
  const q = queue(presenter)
  q.enqueue([toast('a'), toast('b'), toast('c')])

  await tick()
  assert.equal(q.playing?.id, 'a')
  q.clear()
  assert.equal(q.pending, 1, '只剩正在播的 a')
  assert.equal(q.droppedCount, 2)

  presenter.finish('a')
  await tick()
  assert.equal(q.idle, true)
  assert.deepEqual(presenter.started, ['a'], '被清掉的 b、c 不应再播')
})

test('空数组入队是合法的：没有奖励就不该有飘字', async () => {
  const presenter = new ManualPresenter()
  const q = queue(presenter)
  assert.equal(q.enqueue([]), 0)
  await tick()
  assert.equal(q.idle, true)
  assert.deepEqual(presenter.started, [])
})

// ---------- 参数校验 ----------

test('非法参数在构造期就拒绝，而不是等到播放时才发现', () => {
  const presenter = new ManualPresenter()
  assert.throws(() => queue(presenter, { maxQueued: 0 }), /maxQueued/)
  assert.throws(() => queue(presenter, { gapMs: -1 }), /gapMs/)
  assert.throws(() => queue(presenter, { stuckTimeoutMs: 0 }), /stuckTimeoutMs/)
})
