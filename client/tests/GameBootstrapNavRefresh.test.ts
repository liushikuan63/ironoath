/** 执行生产导航回调与真实网络适配链；不证明 Cocos 绘制或微信触摸。 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { runInNewContext } from 'node:vm'
import { AppRoot } from '../assets/scripts/game/session/AppRoot'
import type { PanelKey } from '../assets/scripts/game/session/AppRoot'
import { GameApi } from '../assets/scripts/game/session/GameApi'
import { GameSession } from '../assets/scripts/game/session/GameSession'
import { Store } from '../assets/scripts/game/store/Store'
import { NetModule } from '../assets/scripts/net/NetModule'
import type { HttpResponse } from '../assets/scripts/net/NetTransport'
import type { ArmyListResp } from '../assets/scripts/net/generated/ArmyProtocol'
import { TimeSync } from '../assets/scripts/core/TimeSync'
import { Prng } from '../assets/scripts/core/Prng'
import { buildArmyPanel } from '../assets/scripts/game/army/ArmyPanel'

const clientDir = existsSync(path.resolve(process.cwd(), 'client/assets'))
  ? path.resolve(process.cwd(), 'client') : process.cwd()
const source = readFileSync(path.join(clientDir, 'assets/scripts/scene/GameBootstrap.ts'), 'utf8')
const body = /this\.nav\.onShow = key => \{([\s\S]*?)\r?\n    \}\r?\n    void this\.boot\(\)/.exec(source)?.[1]
assert.ok(body !== undefined, '必须执行生产导航回调，不复制一套刷新逻辑')

function navigation(previous = 'city', appRoot?: AppRoot): {
  show: (key: string) => void, calls: string[][], settled: () => Promise<void>
} {
  const calls: string[][] = []
  const flights: Promise<void>[] = []
  const refresh = (...keys: PanelKey[]) => {
    calls.push(['refresh', ...keys])
    const flight = appRoot?.refresh(...keys) ?? Promise.resolve()
    flights.push(flight)
    return flight
  }
  const root = {
    refresh,
    leaveWorld: () => calls.push(['leaveWorld']),
    openGacha: () => calls.push(['openGacha']),
    loadSocialGates: () => calls.push(['loadSocialGates']),
    loadSocialDiscovery: () => calls.push(['loadSocialDiscovery']),
    reloadRankTab: () => calls.push(['reloadRankTab']),
  }
  const show = runInNewContext(`(function () {
    let previousKey = previous;
    return key => { ${body} };
  }).call(host)`, { previous, host: { root, guide: { repaint: () => undefined } } }) as (key: string) => void
  return { show, calls, settled: async () => { await Promise.all(flights) } }
}

test('升级收割后打开任务页，同时重拉任务进度与活动窗口', () => {
  const nav = navigation()
  nav.show('quest')
  assert.deepEqual(nav.calls, [['refresh', 'quest', 'activity']])
})

test('每次返回任务页都重新拉取，后续升级不能复用首次进入的进度', () => {
  const nav = navigation()
  nav.show('quest')
  nav.show('city')
  nav.show('quest')
  assert.deepEqual(nav.calls, [
    ['refresh', 'quest', 'activity'], ['refresh', 'quest', 'activity'],
  ])
})

test('内城不会因为任务刷新而增加首屏请求', () => {
  const nav = navigation()
  nav.show('city')
  assert.deepEqual(nav.calls, [])
})

test('离开地图进入任务时仍执行既有地图解绑', () => {
  const nav = navigation('world')
  nav.show('quest')
  assert.deepEqual(nav.calls, [['refresh', 'quest', 'activity'], ['leaveWorld']])
})

test('社交页既有权限和发现列表刷新保持', () => {
  const nav = navigation()
  nav.show('social')
  assert.deepEqual(nav.calls, [['loadSocialGates'], ['loadSocialDiscovery']])
})

/** Explicit server-response fixture, not production configuration or a real service. */
function armyResponse(unlocked = false): ArmyListResp {
  return {
    units: [{ unitId: 'unit_infantry_t1', name: '重步兵 T1', type: 'INFANTRY', tier: 1,
      count: 0, wounded: 0, training: 0, finishAt: null, remainingSeconds: null,
      unlocked, unlockHint: unlocked ? null : '需要兵营 1 级，当前 0 级',
      trainTimeSec: 60, trainCost: [{ type: 'IRON', amount: 30 }, { type: 'GRAIN', amount: 20 }] }],
    troopCap: unlocked ? 360 : 0, troopsInUse: 0, trainingInUse: 0,
    queueSlots: 0, queueSlotsMax: 20,
    hospital: { capacity: 0, used: 0, treating: false, treatFinishAt: null,
      treatRemainingSeconds: 0, treatSecondsPerWounded: 1, treatCostRatio: 1000 },
    autoTrain: { enabled: false, unitId: '', batchCount: 0, batchBudget: 0,
      targetCount: 0, stopReason: null }, serverNow: 1000,
  }
}

function armyNavigation() {
  const reads: Array<{ path: string; headers: Readonly<Record<string, string>> }> = []
  const posts: string[] = [], delivered: ArmyListResp[] = [], errors: string[][] = []
  let response: HttpResponse = { status: 200, bodyText: '' }
  const store = new Store(), timeSync = new TimeSync({ alphaFixed: 3000, jitterFactorFixed: 30000, initialBestRttMs: 200 })
  let sequence = 0
  const now = () => 1000, newRequestId = () => `army-nav-${++sequence}`
  const net = new NetModule({ baseUrl: 'https://army-navigation.test', wsUrl: 'wss://army-navigation.test/ws',
    maxRetryAttempts: 0, retryBaseDelayMs: 1, retryMaxDelayMs: 1, offlineQueueMax: 1, requestTimeoutMs: 1000 }, {
    http: {
      get: async (url, headers) => { reads.push({ path: new URL(url).pathname, headers }); return response },
      post: async url => { posts.push(new URL(url).pathname); throw new Error('navigation must not POST') },
    },
    socketFactory: () => { throw new Error('navigation must not open a socket') },
    now, delay: async () => undefined, rng: Prng.of(1), newRequestId,
    newTraceId: () => `army-nav-trace-${sequence}`,
  })
  net.setPlayer('army-navigation-fixture-player')
  net.setAuthToken('fixture-session')
  const api = new GameApi({ net, store, timeSync, now, newRequestId })
  const session = new GameSession({ net, store, timeSync, now, newRequestId })
  const root = new AppRoot({ api, session, store, timeSync,
    targets: { army: value => delivered.push(value), error: (panel, message) => errors.push([panel, message]) },
    tracker: { track: () => undefined },
  })
  return { root, nav: navigation('city', root), reads, posts, delivered, errors,
    respond(value: ArmyListResp) {
      response = { status: 200, bodyText: JSON.stringify({ code: 0, msg: 'OK', data: value, traceId: 'fixture', serverNow: value.serverNow }) }
    },
    fail() {
      response = { status: 200, bodyText: JSON.stringify({ code: 50001, msg: '暂时无法读取兵营', data: null, traceId: 'fixture', serverNow: 1000 }) }
    },
  }
}

test('正常进入兵营执行生产 onShow→AppRoot→GameApi→GET，升级与编队后的真实响应取代旧缓存', async () => {
  const h = armyNavigation(), old = armyResponse()
  h.respond(old); await h.root.refresh('army')
  assert.equal(buildArmyPanel(h.delivered.at(-1)!, 0, 1000).troopCapText, '带兵 0/0')
  const updated = armyResponse(true); h.respond(updated)
  h.nav.show('army'); await h.nav.settled()
  assert.equal(h.reads.length, 2, '兵营进入必须在预取之外真正重拉一次')
  assert.deepEqual(h.reads.map(read => read.path), ['/army/list', '/army/list'])
  assert.equal(h.reads[1]?.headers.Authorization, 'Bearer fixture-session')
  assert.deepEqual(h.delivered.at(-1), updated)
  const panel = buildArmyPanel(h.delivered.at(-1)!, 0, 1000)
  assert.equal(panel.troopCapText, '带兵 0/360'); assert.equal(panel.rows[0]?.unlocked, true)
  assert.equal(panel.rows[0]?.unlockHint, null); assert.equal(panel.queueText, '训练队列 0/20')
  assert.deepEqual(h.posts, []); assert.deepEqual(h.errors, [])
})

test('离开再回兵营及同页再次打开都重拉，训练到期后的兵力与队列只取新服务端响应', async () => {
  const h = armyNavigation(), training = armyResponse(true)
  training.units[0]!.training = 1; training.units[0]!.finishAt = 61000
  training.units[0]!.remainingSeconds = 60; training.trainingInUse = 1; training.queueSlots = 1
  h.respond(training); h.nav.show('army'); await h.nav.settled()
  assert.equal(h.delivered.at(-1)?.trainingInUse, 1)
  const settled = armyResponse(true); settled.units[0]!.count = 1; settled.troopsInUse = 1
  h.respond(settled); h.nav.show('city'); h.nav.show('army'); await h.nav.settled()
  assert.deepEqual(h.delivered.at(-1), settled)
  h.nav.show('army'); await h.nav.settled()
  assert.equal(h.reads.length, 3, '不得将首次兵营请求或已完成 promise 当缓存')
  assert.deepEqual(h.posts, [])
})

test('兵营刷新失败保留旧数据并给出既有错误；下一次正常进入仍重新请求并恢复', async () => {
  const h = armyNavigation(), old = armyResponse()
  h.respond(old); await h.root.refresh('army')
  h.fail(); h.nav.show('army'); await h.nav.settled()
  assert.equal(h.reads.length, 2); assert.equal(h.delivered.length, 1)
  assert.deepEqual(h.delivered[0], old); assert.deepEqual(h.errors, [['army', '暂时无法读取兵营']])
  const updated = armyResponse(true); h.respond(updated)
  h.nav.show('army'); await h.nav.settled()
  assert.equal(h.reads.length, 3); assert.deepEqual(h.delivered.at(-1), updated)
  assert.deepEqual(h.posts, [])
})

test('离开地图进入兵营仍解绑地图，其他导航不会偷偷增加兵营请求', async () => {
  const nav = navigation('world'); nav.show('army'); await nav.settled()
  assert.deepEqual(nav.calls, [['refresh', 'army'], ['leaveWorld']])
  const h = armyNavigation(); h.nav.show('city'); await h.nav.settled()
  assert.deepEqual(h.reads, []); assert.deepEqual(h.posts, [])
})
