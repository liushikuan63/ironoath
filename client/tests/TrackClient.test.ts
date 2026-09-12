/**
 * 职责：TrackClient 单测 —— 攒批发送、失败重投、切后台冲刷、崩溃上报（B16 §3/§6，验收 3/9）。
 * 依赖：node:test / node:assert。
 *
 * <p><b>传输层是假的，这是刻意的</b>：埋点发送的三种结果（服务端收下 / 明确拒绝 / 网络异常）
 * 都必须能被造出来，否则「失败之后批次有没有退回」这条根本没法定验证 ——
 * 而它恰恰是弱网下埋点还能不能用的关键。真的发 HTTP 就只能测到成功那一条路径。
 *
 * <p><b>时钟也是假的</b>：攒批窗口是「距队首事件 10 秒」，用真实时钟测超时
 * 就得真的等 10 秒（慢）或者把窗口调成 10 毫秒（那就不是被测的那条配置了）。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { TrackClient } from '../assets/scripts/game/track/TrackClient'
import type { CrashInput, TrackTransport } from '../assets/scripts/game/track/TrackClient'
import type { TrackEventDraft } from '../assets/scripts/game/track/TrackQueue'

const SECOND = 1000

type Mode = 'ok' | 'refuse' | 'throw'

class FakeTransport implements TrackTransport {
  /** 每次调用后的行为序列；用尽之后停在最后一项。 */
  modes: Mode[] = ['ok']
  readonly batches: TrackEventDraft[][] = []
  readonly crashes: CrashInput[] = []
  private calls = 0

  public async sendBatch(events: TrackEventDraft[]): Promise<boolean> {
    this.batches.push(events)
    const mode = this.modes[Math.min(this.calls, this.modes.length - 1)] ?? 'ok'
    this.calls++
    if (mode === 'throw') {
      throw new Error('模拟弱网：请求超时')
    }
    return mode === 'ok'
  }

  public async reportCrash(crash: CrashInput): Promise<boolean> {
    this.crashes.push(crash)
    return true
  }
}

/** 可控时钟：默认对应 global.TRACK_BATCH_MAX_SIZE=10 / TRACK_BATCH_FLUSH_SECONDS=10。 */
function makeClient(transport: TrackTransport, start = 0) {
  let current = start
  const client = new TrackClient(
    { maxBatchSize: 10, flushIntervalMs: 10 * SECOND, maxUnsentBatches: 2 },
    transport,
    () => current)
  return {
    client,
    advance(ms: number) {
      current += ms
    },
    at() {
      return current
    },
  }
}

function crashInput(): CrashInput {
  return {
    message: '空指针',
    stack: 'at WorldMap.update (WorldMap.ts:42)',
    clientVersion: '1.0.0',
    sceneName: 'world',
    traceId: 'trace-1',
  }
}

test('禁止项：攒满 10 条才发一次请求，前 9 条一个请求都不发', async () => {
  const transport = new FakeTransport()
  const { client } = makeClient(transport)

  for (let i = 0; i < 9; i++) {
    client.track('evt_' + String(i))
  }
  await client.settle()
  assert.equal(transport.batches.length, 0, '不足一批时不该有任何请求')
  assert.equal(client.pendingCount(), 9)

  client.track('evt_9')
  await client.settle()
  assert.equal(transport.batches.length, 1, '第 10 条触发一次请求')
  assert.equal(transport.batches[0]?.length, 10)
  assert.equal(client.pendingCount(), 0)
  assert.equal(client.deliveredBatchCount(), 1)
})

test('攒不满时超过 10 秒也要发：否则「点两下就退出」的玩家那两个事件永远发不出去', async () => {
  const transport = new FakeTransport()
  const { client, advance } = makeClient(transport)

  client.track('startup')
  client.track('login')
  advance(9 * SECOND)
  client.tick()
  await client.settle()
  assert.equal(transport.batches.length, 0, '距队首才 9 秒')

  advance(2 * SECOND)
  client.tick()
  await client.settle()
  assert.equal(transport.batches.length, 1, '距队首 11 秒 ⇒ 触发')
  assert.equal(transport.batches[0]?.length, 2)
})

test('服务端明确拒绝时退回重投，下一次 tick 重投成功', async () => {
  const transport = new FakeTransport()
  transport.modes = ['refuse', 'ok']
  const { client } = makeClient(transport)

  for (let i = 0; i < 10; i++) {
    client.track('evt_' + String(i))
  }
  await client.settle()
  assert.equal(transport.batches.length, 1)
  assert.equal(client.deliveredBatchCount(), 0, '被拒绝的批不算送达')
  assert.equal(client.failedBatchCount(), 1)

  client.tick()
  await client.settle()
  assert.equal(transport.batches.length, 2, '重投发出第二次请求')
  assert.equal(transport.batches[1]?.length, 10, '重投的是同一批，一条都不能少')
  assert.equal(client.deliveredBatchCount(), 1)
})

test('网络异常与服务端拒绝同样处理：都退回重投（两种情况下数据都还没到服务端）', async () => {
  const transport = new FakeTransport()
  transport.modes = ['throw', 'ok']
  const { client } = makeClient(transport)

  for (let i = 0; i < 10; i++) {
    client.track('evt_' + String(i))
  }
  await client.settle()
  assert.equal(client.failedBatchCount(), 1)

  client.tick()
  await client.settle()
  assert.equal(client.deliveredBatchCount(), 1, '异常之后重投成功')
})

test('切后台冲刷：进程随时可能被系统回收，攒着的事件必须在切后台那一刻发出去', async () => {
  const transport = new FakeTransport()
  const { client } = makeClient(transport)

  client.track('startup')
  client.track('churn')
  assert.equal(transport.batches.length, 0, '还没攒满，也没到时间')

  client.onBackground()
  await client.settle()
  assert.equal(transport.batches.length, 1)
  assert.deepEqual(transport.batches[0]?.map((e) => e.name), ['startup', 'churn'])
  assert.equal(client.pendingCount(), 0)
})

test('崩溃上报不进攒批队列、不等窗口、立刻发出（它是进程退出前唯一的机会）', async () => {
  const transport = new FakeTransport()
  const { client } = makeClient(transport)

  client.reportCrash(crashInput())
  await client.settle()

  assert.equal(transport.crashes.length, 1)
  assert.equal(transport.batches.length, 0, '崩溃不走埋点批次')
  assert.equal(transport.crashes[0]?.traceId, 'trace-1')
  assert.equal(transport.crashes[0]?.stack, 'at WorldMap.update (WorldMap.ts:42)')
  assert.equal(client.crashReportCount(), 1)
  assert.equal(client.pendingCount(), 0)
})

test('事件名非法当场抛：埋点调用点的 bug 要在开发期炸出来，而不是变成看板上一个永远为 0 的漏斗节点', async () => {
  const transport = new FakeTransport()
  const { client } = makeClient(transport)

  assert.throws(() => client.track('  '), /事件名不得为空/)
  client.track('ok_event')
  await client.settle()
  assert.equal(client.pendingCount(), 1, '被拒的事件不该进队列，合法的照常攒着')
  assert.equal(transport.batches.length, 0)
})

test('重投缓冲区满时丢最旧的一批并计数：静默丢弃会让漏斗莫名其妙少一环', async () => {
  const transport = new FakeTransport()
  transport.modes = ['refuse']
  const { client } = makeClient(transport)

  // 造 4 批全部被拒 ⇒ 缓冲区上限 2，应当丢掉最旧的 2 批
  for (let round = 0; round < 4; round++) {
    for (let i = 0; i < 10; i++) {
      client.track('r' + String(round) + '_e' + String(i))
    }
    await client.settle()
  }
  assert.equal(transport.batches.length, 4)
  assert.equal(client.deliveredBatchCount(), 0)
  assert.equal(client.droppedBatchCount(), 2, '4 批退回、上限 2 ⇒ 丢最旧的 2 批')
})

test('settle 要真的等 I/O 落定：传输在宏任务里回来时不能自旋卡死（真机就是这一种）', async () => {
  // FakeTransport 是在微任务里 resolve 的，所以它永远暴露不出这个缺陷：
  // 真的网络请求在 I/O 阶段回来，而微任务队列不腾空，事件循环就轮不到它。
  // 旧实现是 `while (inFlight > 0) await Promise.resolve()`，在这里会永不结束。
  const sent: number[] = []
  const slow = {
    sendBatch: (events: TrackEventDraft[]) => new Promise<boolean>((resolve) => {
      sent.push(events.length)
      setTimeout(() => resolve(true), 2)
    }),
    reportCrash: async () => true,
  }
  const { client } = makeClient(slow)

  for (let i = 0; i < 10; i++) {
    client.track('slow_' + String(i))
  }
  const finishedInTime = await Promise.race([
    client.settle().then(() => true),
    new Promise<boolean>(resolve => setTimeout(() => resolve(false), 2_000)),
  ])

  assert.equal(finishedInTime, true,
    'settle 自旋等计数：切后台时它会把 JS 线程钉死，而这正是最需要发完最后一批的时刻')
  assert.deepEqual(sent, [10])
  assert.equal(client.deliveredBatchCount(), 1)
})
