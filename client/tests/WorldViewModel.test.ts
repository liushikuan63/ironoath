/**
 * 职责：WorldViewModel 与 WorldContext 的单测 —— B07 §1（3×3 分块视野）、§3（迷雾）、
 * 验收 3（内存不随拖动增长）、验收 6（版本复用）、验收 10（收到推送立即纠偏）。
 * 依赖：node:test / node:assert。
 *
 * <p><b>为什么这些断言必须在逻辑层测</b>：场景（scene/WorldMap.ts）依赖真实 Cocos 运行时，
 * `tsconfig.test.json` 刻意把它排除在 node --test 之外。所以「哪些块该画成迷雾」
 * 「我的行军和块里的行军实体哪个优先」「服务端纠偏了没有」这些判断全部收在 WorldViewModel，
 * 场景只剩绘制与节点池。本文件覆盖的就是那部分可判定的逻辑。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { WorldViewModel, chunkIndexOf, chunkKeyOf, parseChunkKey } from '../assets/scripts/game/world/WorldViewModel'
import type { WorldLayout } from '../assets/scripts/game/world/WorldViewModel'
import {
  applyExileResult, bindWorldRequester, exileSnapshot, feedMarches, feedViewport, initializeWorld,
  resetWorld, worldModel, worldRequester,
} from '../assets/scripts/game/world/WorldContext'
import type { WorldRequester } from '../assets/scripts/game/world/WorldContext'
import type {
  ChunkData, MarchListResp, MarchView, ViewportResp, WorldEntity, WorldEntityType,
} from '../assets/scripts/net/generated/WorldProtocol'

/** 与 global.json 一致的世界尺寸。测试里写死是刻意的：夹具要能被读者一眼核对。 */
const LAYOUT: WorldLayout = { worldSize: 512, chunkSize: 32, maxChunks: 9 }
const CHUNKS_PER_SIDE = 16

function entity(id: string, type: WorldEntityType, x: number, y: number): WorldEntity {
  // 生成的 WorldEntity 把可空字段声明为「必填但值为 null」，夹具必须逐个给出来
  return {
    id, type, x, y,
    level: null, ownerName: null, allianceTag: null,
    marchStatus: null, resourceType: null, load: null,
  }
}

function chunkData(key: string, version: number, entities: WorldEntity[], truncated = false): ChunkData {
  return { key, version, entities, truncated }
}

function viewportResp(overrides: Partial<ViewportResp> = {}): ViewportResp {
  return { serverNow: 0, chunks: [], staleChunks: [], exploredChunks: [], fogChunks: [], ...overrides }
}

function marchView(overrides: Partial<MarchView> = {}): MarchView {
  return {
    marchId: 'm1',
    from: { x: 0, y: 0 },
    to: { x: 100, y: 0 },
    status: 'MARCHING',
    targetType: 'MONSTER',
    targetId: null,
    rallyId: null,
    action: 'ATTACK',
    startAt: 0,
    arriveAt: 1000,
    returnStartAt: null,
    returnArriveAt: null,
    units: [],
    heroes: [],
    load: 0,
    loadCap: 100,
    teamSpeed: 10,
    position: { x: 0, y: 0 },
    progressFixed: 0,
    gatherFinishAt: null,
    serverNow: 0,
    ...overrides,
  }
}

function marchList(marches: MarchView[], serverNow = 0,
                   exile: { peaceUntil?: number | null, nextExileAt?: number | null } = {}): MarchListResp {
  return {
    marches, home: { x: 0, y: 0 }, maxConcurrent: 3, serverNow,
    peaceUntil: exile.peaceUntil ?? null, nextExileAt: exile.nextExileAt ?? null,
  }
}

/** 标准起点：中心 (48,48) 落在块 (1,1)，视野正好是完整的 3×3。 */
function newModel(): WorldViewModel {
  return new WorldViewModel(LAYOUT, 0, { x: 48, y: 48 })
}

// ---------- 视野与坐标换算 ----------

test('坐标换算：块索引与块键互逆，且与服务端的位移口径一致', () => {
  assert.equal(chunkIndexOf(0, 32), 0)
  assert.equal(chunkIndexOf(31, 32), 0)
  assert.equal(chunkIndexOf(32, 32), 1)
  assert.equal(chunkKeyOf(48, 48, 32), '1:1')
  assert.equal(chunkKeyOf(511, 511, 32), '15:15')
  assert.deepEqual(parseChunkKey('15:2'), [15, 2])
  assert.throws(() => parseChunkKey('15'), /cx:cy/)
  assert.throws(() => parseChunkKey('a:b'), /整数/)
})

test('视野是 3×3；贴到世界角落时按边界裁剪，不画出世界之外的块', () => {
  assert.deepEqual(newModel().chunkKeys(), ['0:0', '1:0', '2:0', '0:1', '1:1', '2:1', '0:2', '1:2', '2:2'])

  const corner = new WorldViewModel(LAYOUT, 0, { x: 0, y: 0 })
  assert.deepEqual(corner.chunkKeys(), ['0:0', '1:0', '0:1', '1:1'])

  const farCorner = new WorldViewModel(LAYOUT, 0, { x: 511, y: 511 })
  assert.deepEqual(farCorner.chunkKeys(),
    ['14:14', '15:14', '14:15', '15:15'])
})

test('setCenter 把越界坐标夹回世界内；zoom 只认 0/1/2 三档', () => {
  const model = newModel()
  model.setCenter({ x: 9999, y: -5 })
  assert.deepEqual(model.center(), { x: 511, y: 0 })
  assert.throws(() => model.setZoom(3), /0\/1\/2/)
  assert.throws(() => model.setZoom(-1), /0\/1\/2/)
  model.setZoom(1)
  assert.equal(model.currentZoom(), 1)
})

test('家坐标随行军列表更新；迁城结果同时移动家与视野，回城不会返回旧址', () => {
  const model = newModel()
  assert.deepEqual(model.home(), { x: 48, y: 48 })

  model.applyMarches({ ...marchList([]), home: { x: 300, y: 220 } }, 0)
  assert.deepEqual(model.home(), { x: 300, y: 220 })
  assert.deepEqual(model.center(), { x: 48, y: 48 }, '刷新行军列表本身不该强制拉走正在看的视野')

  resetWorld()
  const initialized = initializeWorld(LAYOUT, 0, { x: 48, y: 48 })
  applyExileResult({ x: 440, y: 37 }, 1_000, 2_000, 500)
  assert.deepEqual(initialized.home(), { x: 440, y: 37 })
  assert.deepEqual(initialized.center(), { x: 440, y: 37 },
    '迁城成功后家与视野必须一起移动，否则回城会跳回旧址')
})

test('构造期校验布局：chunkSize 必须是 2 的幂，maxChunks 必须是奇数边的完全平方数', () => {
  const home = { x: 0, y: 0 }
  assert.throws(() => new WorldViewModel({ worldSize: 512, chunkSize: 31, maxChunks: 9 }, 0, home), /2 的幂/)
  assert.throws(() => new WorldViewModel({ worldSize: 500, chunkSize: 32, maxChunks: 9 }, 0, home), /整除/)
  assert.throws(() => new WorldViewModel({ worldSize: 512, chunkSize: 32, maxChunks: 8 }, 0, home), /完全平方数/)
  // 4 = 2×2 是完全平方数但边长为偶数 ⇒ 没有中心块，九宫格无从对齐
  assert.throws(() => new WorldViewModel({ worldSize: 512, chunkSize: 32, maxChunks: 4 }, 0, home), /奇数边/)
})

// ---------- 增量拉取（验收 6） ----------

test('同一块内拖动不发请求；跨块才发，并带上手里各块的版本号', () => {
  const model = newModel()
  const first = model.nextRequest()
  assert.notEqual(first, null)
  assert.deepEqual(first?.chunkVersions, [])   // 首次请求：手里什么都没有
  model.markRequested()
  assert.equal(model.nextRequest(), null)

  // 还在块 (1,1) 里，只是挪了几格 ⇒ 3×3 的块集合没变，不该再烧一次流量
  model.setCenter({ x: 60, y: 40 })
  assert.equal(model.nextRequest(), null)

  // 跨到块 (2,1) ⇒ 必须重新拉
  model.setCenter({ x: 70, y: 40 })
  model.applyViewport(viewportResp({
    chunks: [chunkData('2:1', 7, [entity('a', 'MONSTER', 70, 40)])],
    exploredChunks: [...model.chunkKeys()],
  }))
  const second = model.nextRequest()
  assert.notEqual(second, null)
  assert.deepEqual(second?.chunkVersions, [{ key: '2:1', version: 7 }])
})

test('缩放档位变化会触发一次重新拉取（服务端可能按档位给不同粒度的实体）', () => {
  const model = newModel()
  model.markRequested()
  assert.equal(model.nextRequest(), null)

  model.setZoom(1)
  assert.notEqual(model.nextRequest(), null)
  model.markRequested()
  assert.equal(model.nextRequest(), null)

  // 同档位重复设置不算变化：双指缩放时档位会在同一档里反复抖，每次都重拉就是白烧流量
  model.setZoom(1)
  assert.equal(model.nextRequest(), null)

  model.setZoom(0)
  assert.notEqual(model.nextRequest(), null)
})

test('响应在路上时玩家拖走了地图：用「请求时的视野」做驱逐判据，刚收到的块不会被立刻丢掉', () => {
  const model = newModel()
  const req = model.nextRequest()
  assert.notEqual(req, null)
  model.markRequested()
  const requested = [...model.chunkKeys()]

  // 请求还没回来，玩家已经把视野拖到地图另一头
  model.setCenter({ x: 400, y: 400 })

  model.applyViewport(viewportResp({
    chunks: [chunkData('1:1', 3, [entity('keep', 'MONSTER', 48, 48)])],
    exploredChunks: requested,
  }))

  // 关键断言：块还在缓存里。若按「当前视野」驱逐，这里会是 0，表现为快速拖动时地图一片空白
  assert.equal(model.chunkCount(), 1)
  assert.equal(model.entityCount(), 1)
  // 并且立刻要求补发一次，否则会拿旧视野的块去画新视野的地图
  assert.notEqual(model.nextRequest(), null)
})

// ---------- 迷雾（B07 §3） ----------

test('迷雾块与「还没加载」的块必须可区分，且迷雾块一律不给实体', () => {
  const model = newModel()
  model.markRequested()
  model.applyViewport(viewportResp({
    // 1:1 已探索并有实体；2:2 是迷雾（服务端不下发实体）；其余块这次没回
    chunks: [
      chunkData('1:1', 1, [entity('m', 'MONSTER', 48, 48)]),
      chunkData('2:2', 1, [entity('leak', 'MONSTER', 80, 80)]),
    ],
    exploredChunks: ['1:1'],
    fogChunks: ['2:2'],
  }))

  const tiles = model.frame(0).tiles
  const byKey = new Map(tiles.map((tile) => [tile.key, tile]))

  const explored = byKey.get('1:1')
  assert.equal(explored?.fogged, false)
  assert.equal(explored?.loaded, true)
  assert.equal(explored?.entities.length, 1)

  const fogged = byKey.get('2:2')
  assert.equal(fogged?.fogged, true)
  // 服务端不该下发迷雾块的实体，但万一缓存里有，逻辑层也必须挡掉 ——
  // 否则迷雾就退化成纯客户端遮罩，改一行代码就能透视全图
  assert.deepEqual(fogged?.entities, [])

  const pending = byKey.get('0:0')
  assert.equal(pending?.loaded, false)
  assert.equal(pending?.fogged, false, '没加载不等于迷雾：一个是网络慢，一个是没探索过')
})

test('迷雾与探索以本次响应为准整体替换，不累加', () => {
  const model = newModel()
  model.markRequested()
  model.applyViewport(viewportResp({
    chunks: [chunkData('1:1', 1, [entity('m', 'MONSTER', 48, 48)])],
    exploredChunks: ['1:1'],
    fogChunks: ['0:0'],
  }))
  assert.equal(model.frame(0).tiles.find((t) => t.key === '0:0')?.fogged, true)

  // 下一次响应里 0:0 已经探索过了
  model.markRequested()
  model.applyViewport(viewportResp({
    chunks: [chunkData('0:0', 1, [entity('n', 'MONSTER', 10, 10)])],
    exploredChunks: ['0:0', '1:1'],
    fogChunks: [],
  }))
  const tile = model.frame(0).tiles.find((t) => t.key === '0:0')
  assert.equal(tile?.fogged, false)
  assert.equal(tile?.entities.length, 1)
})

// ---------- 实体筛选 ----------

test('渲染帧剔除 EMPTY，并剔除已由本地插值接管的己方行军（否则队伍会分裂成两支）', () => {
  const model = newModel()
  model.applyMarches(marchList([marchView({ marchId: 'mine' })]), 0)
  model.markRequested()
  model.applyViewport(viewportResp({
    chunks: [chunkData('1:1', 1, [
      entity('blank', 'EMPTY', 40, 40),
      entity('mine', 'MARCH', 41, 41),
      entity('theirs', 'MARCH', 42, 42),
      entity('city', 'CITY', 43, 43),
    ])],
    exploredChunks: [...model.chunkKeys()],
  }))

  const drawn = model.frame(0).tiles.flatMap((tile) => tile.entities.map((e) => e.id))
  assert.deepEqual(drawn.sort(), ['city', 'theirs'])
})

// ---------- 行军插值与纠偏（验收 10） ----------

test('行军位置随时间推进，剩余时间绝不为负，结束后停在目的地', () => {
  const model = newModel()
  model.applyMarches(marchList([marchView({
    from: { x: 0, y: 0 }, to: { x: 100, y: 0 }, startAt: 1000, arriveAt: 3000,
  })]), 0)

  const mid = model.frame(2000).marches[0]
  assert.notEqual(mid, undefined)
  assert.equal(mid?.x, 50)
  assert.equal(mid?.y, 0)
  assert.equal(mid?.remainingMs, 1000)
  assert.ok(Math.abs(mid.progress - 0.5) < 1e-9)

  const done = model.frame(999999).marches[0]
  assert.equal(done?.x, 100)
  assert.equal(done?.remainingMs, 0, '已结束必须是 0，绝不能是负数')
  assert.equal(done?.progress, 1)
})

test('返程用服务端权威位置重新锚定，不会先瞬移到目的地再往回走', () => {
  const model = newModel()
  // 召回那一刻：队伍在 (50,0)，服务端 serverNow=1000，预计 3000 到家
  model.applyMarches(marchList([marchView({
    marchId: 'r1',
    from: { x: 0, y: 0 }, to: { x: 100, y: 0 },
    status: 'RETURNING', startAt: 0, arriveAt: 1000,
    returnStartAt: 1000, returnArriveAt: 3000,
    position: { x: 50, y: 0 },
  })], 1000), 1000)

  // MarchTrack 的默认回退是「返程起点 = 目的地 to」，那样这一帧会画在 x=100：
  // 玩家看到自己的队伍瞬间跳到目的地再开始往回走 —— 服务端修过的 bug 在表现层复现一次
  const atRecall = model.frame(1000).marches[0]
  assert.equal(atRecall?.x, 50, '召回瞬间必须停在服务端给的位置')

  const halfway = model.frame(2000).marches[0]
  assert.equal(halfway?.x, 25, '返程按 (50,0)→(0,0) 匀速推进')

  const home = model.frame(3000).marches[0]
  assert.equal(home?.x, 0)
  assert.equal(home?.remainingMs, 0)
})

test('服务端纠偏只标记一次，读过即清；1 格以内的取整误差不算纠偏', () => {
  const model = newModel()
  model.applyMarches(marchList([marchView({
    marchId: 'c1', from: { x: 0, y: 0 }, to: { x: 100, y: 0 }, startAt: 0, arriveAt: 100000,
  })]), 0)
  assert.equal(model.frame(50000).marches[0]?.x, 50)

  // 模拟验收 10：本地时钟比服务端快 5 分钟，服务端推送把行程拉长了 4 倍
  model.applyMarches(marchList([marchView({
    marchId: 'c1', from: { x: 0, y: 0 }, to: { x: 100, y: 0 }, startAt: 0, arriveAt: 400000,
  })]), 50000)

  const corrected = model.frame(50000).marches[0]
  assert.equal(corrected?.corrected, true, '位置被校正必须让表现层知道，好闪一下残影')
  assert.equal(corrected?.x, 12, '立即纠偏，不做平滑过渡（平滑等于继续显示错误位置）')

  assert.equal(model.frame(50000).marches[0]?.corrected, false, '一次性信号，读过即清')
})

test('重新锚定返程不产生假纠偏（同一条直线的两种参数化只差取整误差）', () => {
  const model = newModel()
  const base = {
    marchId: 'r2', from: { x: 0, y: 0 }, to: { x: 100, y: 0 },
    status: 'RETURNING' as const, startAt: 0, arriveAt: 1000, returnArriveAt: 3000,
  }
  model.applyMarches(marchList([marchView({ ...base, position: { x: 100, y: 0 } })], 1000), 1000)
  assert.equal(model.frame(1000).marches[0]?.corrected, false)

  // 第二次拉取：服务端说 t=2000 时队伍在 (50,0)，与前一条直线完全一致
  model.applyMarches(marchList([marchView({ ...base, position: { x: 50, y: 0 } })], 2000), 2000)
  assert.equal(model.frame(2000).marches[0]?.corrected, false, '一致的数据不该让残影闪个不停')
})

test('响应里消失的行军立刻从渲染帧摘掉，不在地图上留幽灵队伍', () => {
  const model = newModel()
  model.applyMarches(marchList([marchView({ marchId: 'gone' }), marchView({ marchId: 'stay' })]), 0)
  assert.equal(model.frame(0).marches.length, 2)
  model.applyMarches(marchList([marchView({ marchId: 'stay' })]), 0)
  const remaining = model.frame(0).marches
  assert.equal(remaining.length, 1)
  assert.equal(remaining[0]?.marchId, 'stay')
})

// ---------- 内存上界（验收 3） ----------

test('连续跨块拖动 60 次，持有的块数与实体数始终被上限封住', () => {
  const model = newModel()
  const perChunk = 5
  for (let i = 0; i < 60; i++) {
    const cx = i % CHUNKS_PER_SIDE
    const cy = Math.floor(i / CHUNKS_PER_SIDE) % CHUNKS_PER_SIDE
    model.setCenter({ x: cx * 32 + 16, y: cy * 32 + 16 })
    const req = model.nextRequest()
    assert.notEqual(req, null, `第 ${i} 次跨块拖动后必须要求重新拉取`)
    model.markRequested()
    const keys = [...model.chunkKeys()]
    model.applyViewport(viewportResp({
      chunks: keys.map((key) => chunkData(key, i + 1,
        Array.from({ length: perChunk }, (_, n) => entity(`${key}#${n}`, 'MONSTER', n, n)))),
      exploredChunks: keys,
    }))
    // 不变量必须在每一步都成立，而不是只在最后检查一次
    assert.ok(model.chunkCount() <= LAYOUT.maxChunks, `第 ${i} 次后持有 ${model.chunkCount()} 块`)
  }
  assert.equal(model.chunkCount(), LAYOUT.maxChunks)
  assert.equal(model.entityCount(), LAYOUT.maxChunks * perChunk,
    '实体数被 maxChunks × 单块实体数封顶 —— 这就是验收 3「内存增长 < 10MB」的上界来源')
})

// ---------- 截断与重试 ----------

test('被 payload 上限截断的块会重试，但重试次数封顶，不会变成吃满带宽的死循环', () => {
  const model = newModel()
  const warned: string[] = []
  const originalWarn = console.warn
  console.warn = (message: unknown) => {
    warned.push(String(message))
  }
  try {
    // 首次请求
    assert.notEqual(model.nextRequest(), null)
    model.markRequested()

    // 服务端连续四次都说 1:1 没发完。前三次继续重试，第四次放弃并留下警告
    for (let round = 1; round <= 4; round++) {
      model.applyViewport(viewportResp({
        chunks: [chunkData('1:1', round, [entity('x', 'MONSTER', 48, 48)], true)],
        exploredChunks: [...model.chunkKeys()],
        staleChunks: ['1:1'],
      }))
      if (round <= 3) {
        assert.notEqual(model.nextRequest(), null, `第 ${round} 次截断后应当继续重试`)
        model.markRequested()
      } else {
        assert.equal(model.nextRequest(), null, '超过重试上限必须停下，否则请求会永远打下去')
      }
    }
    assert.equal(warned.length, 1)
    assert.match(warned[0] ?? '', /停止重试/)
  } finally {
    console.warn = originalWarn
  }
})

test('truncated=true 的块即使没被列进 staleChunks 也会被要求重取', () => {
  const model = newModel()
  assert.notEqual(model.nextRequest(), null)
  model.markRequested()
  model.applyViewport(viewportResp({
    chunks: [chunkData('1:1', 1, [entity('x', 'MONSTER', 48, 48)], true)],
    exploredChunks: [...model.chunkKeys()],
    staleChunks: [],
  }))
  assert.notEqual(model.nextRequest(), null,
    '实体没发完的块必须再取一次，否则玩家看到「明明有怪却显示为空地」而无从知道为什么')
})

// ---------- WorldContext ----------

test('未初始化时 worldModel() 为 null：场景据此画「未连接」而不是一张假地图', () => {
  resetWorld()
  assert.equal(worldModel(), null)
  assert.equal(worldRequester(), null)
})

test('initializeWorld 之后可用；resetWorld 同时解绑传输层，避免旧账号的响应写进新账号的地图', () => {
  resetWorld()
  const requester: WorldRequester = {
    viewport: () => undefined, marches: () => undefined, exile: () => undefined,
  }
  const model = initializeWorld(LAYOUT, 0, { x: 48, y: 48 })
  bindWorldRequester(requester)
  assert.equal(worldModel(), model)
  assert.equal(worldRequester(), requester)

  feedViewport(viewportResp({
    chunks: [chunkData('1:1', 2, [entity('m', 'MONSTER', 48, 48)])],
    exploredChunks: [...model.chunkKeys()],
  }))
  assert.equal(model.entityCount(), 1)

  feedMarches(marchList([marchView({ marchId: 'ctx' })]), 0)
  assert.equal(model.frame(0).marches.length, 1)

  // 冷却与免战由同一份列表下发，客户端不自己算第二份窗口
  feedMarches(marchList([marchView({ marchId: 'ctx' })], 10_000,
      { peaceUntil: 20_000, nextExileAt: 30_000 }), 0)
  assert.deepEqual(exileSnapshot(),
      { nextExileAt: 30_000, peaceUntil: 20_000, troopsAway: 1, serverNow: 10_000 })

  resetWorld()
  assert.equal(worldModel(), null)
  assert.equal(worldRequester(), null)
  assert.equal(exileSnapshot().nextExileAt, null)
  assert.equal(exileSnapshot().troopsAway, 0)
  // 重置之后仍然可能有在途响应到达：喂数据必须静默忽略而不是抛错，
  // 否则「退出登录的瞬间收到一个响应」就会变成一个崩溃
  feedViewport(viewportResp())
  feedMarches(marchList([]), 0)
})
