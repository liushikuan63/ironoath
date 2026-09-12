/**
 * 职责：ChunkCache 与 MarchInterpolator 的单测 —— B07 §1（分块加载/离开视野卸载）、
 * 验收 3（内存不随拖动增长）、验收 6（无变化时复用缓存）、验收 10（收到推送立即纠偏）。
 * 依赖：node:test / node:assert。
 *
 * Cocos 侧的节点池与合批渲染在这里测不到（需要真实引擎），
 * 但「什么时候该把节点还回池子」由 ChunkCache 的 evicted 列表决定 ——
 * 那部分逻辑是本文件覆盖的重点，因为内存泄漏的根因几乎总是「该驱逐的没驱逐」。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { ChunkCache } from '../assets/scripts/game/world/ChunkCache'
import { MarchInterpolator } from '../assets/scripts/game/world/MarchInterpolator'
import type { MarchTrack } from '../assets/scripts/game/world/MarchInterpolator'
import type { ChunkData, WorldEntity } from '../assets/scripts/net/generated/WorldProtocol'

function entity(id: string, x: number, y: number): WorldEntity {
  // 生成的 WorldEntity 把可空字段声明为「必填但值为 null」（协议里是 required + type:[X,"null"]），
  // 所以夹具必须逐个给出来，不能省略
  return {
    id, type: 'MONSTER', x, y,
    level: null, ownerName: null, allianceTag: null,
    marchStatus: null, resourceType: null, load: null,
  }
}

function chunk(key: string, version: number, count: number): ChunkData {
  const entities: WorldEntity[] = []
  for (let i = 0; i < count; i++) {
    entities.push(entity(`${key}#${i}`, i, i))
  }
  return { key, version, entities, truncated: false }
}

const VIEWPORT = ['0:0', '1:0', '2:0', '0:1', '1:1', '2:1', '0:2', '1:2', '2:2']

// ---------- ChunkCache ----------

test('验收6：版本相同的块不再写入，被记为 unchanged（二次请求实体数为 0）', () => {
  const cache = new ChunkCache({ maxChunks: 9 })
  const first = cache.apply([chunk('1:1', 5, 3)], VIEWPORT)
  assert.deepEqual(first.updated, ['1:1'])
  assert.equal(cache.versionOf('1:1'), 5)
  assert.equal(cache.get('1:1')?.length, 3)

  // 服务端对未变化的块回「同版本 + 空实体」
  const second = cache.apply([chunk('1:1', 5, 0)], VIEWPORT)
  assert.deepEqual(second.updated, [])
  assert.deepEqual(second.unchanged, ['1:1'])
  assert.equal(cache.get('1:1')?.length, 3, '缓存内容必须保留，不能因为服务端没下发就清空')
})

test('版本前进时覆盖旧内容，版本倒退时丢弃并告警（响应乱序到达）', () => {
  const cache = new ChunkCache({ maxChunks: 9 })
  cache.apply([chunk('1:1', 5, 3)], VIEWPORT)

  const forward = cache.apply([chunk('1:1', 6, 1)], VIEWPORT)
  assert.deepEqual(forward.updated, ['1:1'])
  assert.equal(cache.versionOf('1:1'), 6)
  assert.equal(cache.get('1:1')?.length, 1)

  const backward = cache.apply([chunk('1:1', 4, 9)], VIEWPORT)
  assert.deepEqual(backward.updated, [], '版本倒退的块必须被丢弃，否则刚打掉的野怪会重新出现')
  assert.equal(cache.versionOf('1:1'), 6)
  assert.equal(cache.get('1:1')?.length, 1)
})

test('验收3：离开视野的块立即驱逐，持有数永远不超过 maxChunks', () => {
  const cache = new ChunkCache({ maxChunks: 9 })
  cache.apply([chunk('1:1', 1, 10)], VIEWPORT)
  assert.equal(cache.size, 1)

  // 视野整体右移一格：原来的 0:x 列离开视野
  const shifted = ['1:0', '2:0', '3:0', '1:1', '2:1', '3:1', '1:2', '2:2', '3:2']
  cache.apply([chunk('3:1', 1, 10)], shifted)
  const diff = cache.apply([], shifted)
  assert.ok(diff.evicted.includes('0:0') === false, '0:0 上一轮就该被驱逐')
  assert.equal(cache.has('0:0'), false)
  assert.ok(cache.size <= 9, `持有数不得超过 maxChunks，实际 ${cache.size}`)
})

test('验收3：连续拖动 60 次（模拟 60 秒），缓存大小与实体总数都有上界，不随拖动增长', () => {
  const cache = new ChunkCache({ maxChunks: 9 })
  let maxSize = 0
  let maxEntities = 0
  let totalEvicted = 0
  for (let step = 0; step < 60; step++) {
    // 视野沿 x 轴滚动，每步进入一个新块、离开一个旧块
    const base = step
    const viewport: string[] = []
    const incoming: ChunkData[] = []
    for (let dy = 0; dy < 3; dy++) {
      for (let dx = 0; dx < 3; dx++) {
        const key = `${base + dx}:${dy}`
        viewport.push(key)
        if (!cache.has(key)) {
          incoming.push(chunk(key, step + 1, 50))
        }
      }
    }
    const diff = cache.apply(incoming, viewport)
    totalEvicted += diff.evicted.length
    maxSize = Math.max(maxSize, cache.size)
    maxEntities = Math.max(maxEntities, cache.entityCount())
  }
  assert.equal(maxSize, 9, '无论拖多久，持有的块数都必须被 maxChunks 封顶')
  assert.equal(maxEntities, 9 * 50, '实体总数必须有上界 —— 这是验收 3「内存增长 < 10MB」的前提')
  assert.ok(totalEvicted > 100, `离开视野的块必须真的被驱逐，实际驱逐 ${totalEvicted} 次`)
})

test('stale 块保留旧缓存：stale 的含义是「这次没更新」，不是「你的数据作废了」', () => {
  const cache = new ChunkCache({ maxChunks: 9 })
  cache.apply([chunk('1:1', 3, 7)], VIEWPORT)

  const diff = cache.apply([], VIEWPORT, ['1:1'])
  assert.deepEqual(diff.evicted, [], 'stale 块仍在视野内，不该被驱逐')
  assert.equal(cache.get('1:1')?.length, 7, '旧内容必须保留，否则玩家会看到一块空白地图')
  // 下次请求要继续带上它的版本号
  assert.deepEqual(cache.versionsFor(VIEWPORT), [{ key: '1:1', version: 3 }])
})

test('versionsFor 只上报视野内的块：把全缓存都发上去会让请求体白白变大', () => {
  const cache = new ChunkCache({ maxChunks: 20 })
  cache.apply([chunk('1:1', 3, 1), chunk('9:9', 8, 1)], [...VIEWPORT, '9:9'])
  const reported = cache.versionsFor(VIEWPORT)
  assert.deepEqual(reported, [{ key: '1:1', version: 3 }])
  assert.equal(cache.versionOf('未缓存的块'), -1, '没缓存的块要报 -1，服务端据此全量下发')
})

test('构造参数校验', () => {
  assert.throws(() => new ChunkCache({ maxChunks: 0 }), /maxChunks/)
  assert.throws(() => new ChunkCache({ maxChunks: -1 }), /maxChunks/)
})

// ---------- MarchInterpolator ----------

const TRACK: MarchTrack = {
  fromX: 10, fromY: 20, toX: 110, toY: 20,
  startAt: 1_000_000, arriveAt: 1_010_000,
  status: 'MARCHING',
  returnStartAt: null, returnArriveAt: null, returnFromX: null, returnFromY: null,
}

test('去程插值：一半时间走一半路程，端点精确，全程单调', () => {
  const interpolator = new MarchInterpolator(0)
  assert.deepEqual(interpolator.positionAt(TRACK, 1_000_000), 
    { x: 10, y: 20, progress: 0, remainingMs: 10_000, segmentDone: false })
  const half = interpolator.positionAt(TRACK, 1_005_000)
  assert.equal(half.x, 60, '一半时间 ⇒ 一半路程')
  assert.equal(half.progress, 0.5)
  const end = interpolator.positionAt(TRACK, 1_010_000)
  assert.equal(end.x, 110)
  assert.equal(end.remainingMs, 0)
  assert.equal(end.segmentDone, true)
  // 超过到达时刻不越界
  assert.equal(interpolator.positionAt(TRACK, 9_999_999).x, 110)

  let previous = 10
  for (let t = 1_000_000; t <= 1_010_000; t += 100) {
    const x = interpolator.positionAt(TRACK, t).x
    assert.ok(x >= previous, `去程位置必须单调不减，t=${t}`)
    previous = x
  }
})

test('时钟偏差被 offset 抵消：本地时钟快 5 分钟时，队伍位置与服务端一致', () => {
  const drifted = new MarchInterpolator(-300_000)   // 本地比服务端快 5 分钟
  const correct = new MarchInterpolator(0)
  // 本地时刻快 5 分钟 ⇒ 换算成服务端时刻后应当与「本地准确」时算出的位置相同
  const a = drifted.positionAt(TRACK, 1_005_000 + 300_000)
  const b = correct.positionAt(TRACK, 1_005_000)
  assert.equal(a.x, b.x)
  assert.equal(a.y, b.y)
  // 不校时的话会直接跑到终点：这正是「玩家本地时钟快 5 分钟，队伍就早到 5 分钟」的 bug
  const noCorrection = new MarchInterpolator(0).positionAt(TRACK, 1_005_000 + 300_000)
  assert.equal(noCorrection.x, 110)
  assert.notEqual(a.x, noCorrection.x)
})

test('验收10：返程用 returnStartAt/returnFrom 插值，召回瞬间不瞬移', () => {
  const interpolator = new MarchInterpolator(0)
  const recalled: MarchTrack = {
    ...TRACK,
    status: 'RETURNING',
    returnStartAt: 1_004_000,      // 走到 x=50 时召回
    returnArriveAt: 1_008_000,
    returnFromX: 50, returnFromY: 20,
  }
  const atRecall = interpolator.positionAt(recalled, 1_004_000)
  assert.equal(atRecall.x, 50, '召回瞬间必须停在召回时的位置，不能跳到目的地 110')
  assert.notEqual(atRecall.x, TRACK.toX)

  const halfwayHome = interpolator.positionAt(recalled, 1_006_000)
  assert.equal(halfwayHome.x, 30, '返程一半 ⇒ 从 50 往 10 走了一半')
  assert.ok(halfwayHome.x < atRecall.x, '返程必须往家的方向走')

  const home = interpolator.positionAt(recalled, 1_008_000)
  assert.equal(home.x, 10)
  assert.equal(home.segmentDone, true)
})

test('返程字段缺失时退回 to/startAt，但绝不返回负坐标或越界坐标', () => {
  const interpolator = new MarchInterpolator(0)
  const degraded: MarchTrack = {
    ...TRACK, status: 'RETURNING',
    returnStartAt: null, returnArriveAt: null, returnFromX: null, returnFromY: null,
  }
  const pos = interpolator.positionAt(degraded, 1_000_000)
  assert.equal(pos.segmentDone, true, '时间轴退化（t1 <= t0）时应直接判定本段结束')
  assert.equal(pos.x, TRACK.fromX)
})

test('驻扎/采集/交战都停在目标格，不再插值', () => {
  const interpolator = new MarchInterpolator(0)
  for (const status of ['STATIONED', 'GATHERING', 'FIGHTING'] as const) {
    const pos = interpolator.positionAt({ ...TRACK, status }, 1_002_000)
    assert.equal(pos.x, TRACK.toX)
    assert.equal(pos.y, TRACK.toY)
    assert.equal(pos.segmentDone, true)
  }
})

test('收到推送立即纠偏：位置跳变而不是平滑过渡（过渡意味着继续显示错误位置）', () => {
  const interpolator = new MarchInterpolator(0)
  const localNow = 1_005_000
  // 客户端手里的旧时间轴：以为 10 秒到达
  const stale = TRACK
  // 服务端推送的新时间轴：被加速过，5 秒就到达
  const pushed: MarchTrack = { ...TRACK, arriveAt: 1_005_000 }

  const result = interpolator.correction(stale, pushed, localNow)
  assert.equal(result.jumped, true)
  assert.equal(result.from.x, 60, '纠偏前客户端显示的位置')
  assert.equal(result.to.x, 110, '纠偏后必须立即采用服务端位置')
})

test('推送内容与本地一致时不跳变（不该无谓地打断动画）', () => {
  const interpolator = new MarchInterpolator(0)
  const result = interpolator.correction(TRACK, { ...TRACK }, 1_005_000)
  assert.equal(result.jumped, false)
  assert.equal(result.from.x, result.to.x)
})

test('offset 可以随每个响应更新（每个 HTTP 响应都带 serverNow，等于免费校准一次）', () => {
  const interpolator = new MarchInterpolator(0)
  assert.equal(interpolator.offset, 0)
  interpolator.updateOffset(-1500)
  assert.equal(interpolator.offset, -1500)
  assert.throws(() => interpolator.updateOffset(Number.NaN), /有限数/)
  assert.throws(() => new MarchInterpolator(Number.POSITIVE_INFINITY), /有限数/)
})

test('remainingMs 永不为负（倒计时归零后停在 0）', () => {
  const interpolator = new MarchInterpolator(0)
  assert.equal(interpolator.positionAt(TRACK, 1_010_000).remainingMs, 0)
  assert.equal(interpolator.positionAt(TRACK, 9_999_999).remainingMs, 0)
  assert.equal(interpolator.positionAt(TRACK, 999_999).remainingMs, 10_000)
})
