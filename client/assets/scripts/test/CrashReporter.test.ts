/**
 * 职责：崩溃上报组装层的单测（B16 验收 9 的客户端半边）。
 * 依赖：node:test。
 *
 * <p>盯的是三件"崩了才会发现"的事：摘要不能吞下整个请求体、堆栈压完还得留下最深那一帧、
 * 以及**上报路径自己绝不能二次抛出**。第三件最容易被写错，而它的后果是
 * "玩家闪退且没有任何日志"—— 比不报还糟。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  CRASH_STACK_BUDGET_CHARS, CrashReporter, compressStack, firstLine, installGlobalHooks,
} from '../game/session/CrashReporter'
import type { CrashReporterDeps, CrashSink } from '../game/session/CrashReporter'
import type { CrashInput } from '../game/track/TrackClient'

function collector(): CrashSink & { sent: CrashInput[] } {
  const sent: CrashInput[] = []
  return { sent, report: (crash) => { sent.push(crash) } }
}

function makeReporter(overrides: Partial<CrashReporterDeps> = {}) {
  const sink = collector()
  const clock = { now: 1000 }
  const reporter = new CrashReporter({
    sink,
    clientVersion: '1.4.0',
    sceneName: () => 'MainCity',
    traceId: () => 'trace-42',
    now: () => clock.now,
    ...overrides,
  })
  return { reporter, sink, clock }
}

test('摘要只取第一行：某些异常的 message 里嵌着整个请求体', () => {
  assert.equal(firstLine('TypeError: bad\n  at a.js:1\n{"nickName":"很长的一段请求体"}'), 'TypeError: bad')
})

test('堆栈压进预算时保留头与尾，并标注省略了多少行', () => {
  const lines = ['Error: boom']
  for (let i = 0; i < 400; i++) {
    lines.push('    at frame' + String(i) + ' (bundle.js:1:1)')
  }
  const deepest = String(lines[lines.length - 1] ?? '')
  const big = lines.join('\n')
  assert.ok(big.length > CRASH_STACK_BUDGET_CHARS, '夹具本身必须真的超预算')

  const out = compressStack(big)
  assert.ok(out.length <= CRASH_STACK_BUDGET_CHARS, '压完还超预算就等于整条被丢弃')
  assert.ok(out.includes('Error: boom'), '丢掉抛出的那一行，日志就没法定性')
  assert.ok(out.includes(deepest), '协议写得很直接：丢掉的往往正是最深的那一帧')
  assert.match(out, /省略 \d+ 行/, '不标注省略，读日志的人会以为堆栈本来就这么短')
})

test('没超预算的堆栈原样返回（压缩本身不该改变内容）', () => {
  const small = 'Error: ok\n  at a.ts:1:1'
  assert.equal(compressStack(small), small)
})

test('抛出的不是 Error 也要能上报：JS 允许 throw 任何东西', () => {
  const { reporter, sink } = makeReporter()

  assert.equal(reporter.handle('就是一句字符串'), true)
  assert.equal(reporter.handle({ code: 500 }), true)
  // 循环引用到 String() 都会抛，这里必须兜住而不是把上报搞成二次崩溃
  const circular: Record<string, unknown> = {}
  circular.self = circular
  assert.equal(reporter.handle(circular), false,
    '它与上一条都 stringify 成 [object Object]，被按签名去重（去重语义另有用例）')
  assert.equal(reporter.handle(null), true)

  assert.equal(sink.sent.length, 3)
  assert.equal(sink.sent[0]?.message, '就是一句字符串')
  assert.equal(sink.sent[2]?.message, 'null')
})

test('非 Error 的抛出物只能按 stringify 结果去重：两个不同对象会同签名，这是没得区分而不是漏了', () => {
  const { reporter, sink } = makeReporter()

  assert.equal(reporter.handle({ a: 1 }), true)
  assert.equal(reporter.handle({ b: 2 }), false, '两者都 stringify 成 [object Object]')
  assert.equal(sink.sent.length, 1,
    '非 Error 的抛出物本来就没有可比对的签名；为了"看起来区分得开"去拼内存地址，'
    + '只会让同一个 bug 每帧报一条，把限流彻底废掉')
})

test('同一个错误在一帧里重复抛出只报一次（不然一个 bug 就能把带宽和日志一起打满）', () => {
  const { reporter, sink, clock } = makeReporter()
  const err = new Error('render 里越界')

  assert.equal(reporter.handle(err), true)
  assert.equal(reporter.handle(err), false)
  assert.equal(reporter.handle(err), false)
  assert.equal(sink.sent.length, 1)
  assert.equal(reporter.suppressedCount, 2)

  // 窗口过后同样的错误要能再报：它可能意味着问题还在
  clock.now += 61_000
  assert.equal(reporter.handle(err), true)
  assert.equal(sink.sent.length, 2)
})

test('上报出口自己抛异常时不得二次抛出', () => {
  const { reporter } = makeReporter({
    sink: {
      report: () => {
        throw new Error('上报路径上的二次崩溃')
      },
    },
  })

  assert.equal(reporter.handle(new Error('原始异常')), false)
  assert.equal(reporter.reportedCount, 0)
})

test('上报带齐 traceId、场景名与版本：灰度期间只有按版本分组才看得出 5% 批次在崩', () => {
  const { reporter, sink } = makeReporter()

  reporter.handle(new Error('boom'))

  const sent = sink.sent[0]
  assert.equal(sent?.traceId, 'trace-42')
  assert.equal(sent?.sceneName, 'MainCity')
  assert.equal(sent?.clientVersion, '1.4.0')
  assert.match(sent?.message ?? '', /^Error: boom$/)
})

test('全局钩子：wx 与浏览器两种入口都装，卸载后不再回调', () => {
  const g = globalThis as Record<string, any>
  const savedWx = g.wx
  const savedAdd = g.addEventListener
  const savedRemove = g.removeEventListener
  const handlers: Record<string, (e: unknown) => void> = {}
  const wxHandlers: Array<(e: unknown) => void> = []
  const removed: string[] = []

  g.wx = {
    onError: (h: (e: unknown) => void) => { wxHandlers.push(h) },
    offError: (h: (e: unknown) => void) => {
      removed.push('wx')
      const i = wxHandlers.indexOf(h)
      if (i >= 0) {
        wxHandlers.splice(i, 1)
      }
    },
  }
  g.addEventListener = (name: string, h: (e: unknown) => void) => {
    handlers[name] = h
  }
  g.removeEventListener = (name: string) => {
    removed.push(name)
    delete handlers[name]
  }

  const seen: unknown[] = []
  const uninstall = installGlobalHooks(e => { seen.push(e) })
  assert.equal(wxHandlers.length, 1)
  assert.equal(typeof handlers['error'], 'function')
  assert.equal(typeof handlers['unhandledrejection'], 'function',
    '漏掉 unhandledrejection 的表现是"异步里崩了但永远看不到"')

  wxHandlers[0]?.('小程序里的报错')
  handlers['unhandledrejection']?.({ reason: 'promise 里崩了' })
  assert.deepEqual(seen, ['小程序里的报错', 'promise 里崩了'])

  uninstall()
  assert.deepEqual(removed.sort(), ['error', 'unhandledrejection', 'wx'])

  g.wx = savedWx
  g.addEventListener = savedAdd
  g.removeEventListener = savedRemove
})
