/**
 * 职责：`tools/lib/cocos-click.mjs` 的自检（`node --test tools/lib/cocos-click.test.mjs`）。
 * 依赖：node（`node:test`）。
 *
 * <p>**判据自带判据**：这里的 `hitTest` 假件是**独立的几何真值**（按已知的相机映射把引擎点还原成
 * 世界点，再按 contentSize 判框），与被测代码那条换算链没有任何共用代码。所以「点得中/点不中」
 * 不是被测实现自己说了算 —— 把 dpr 漏掉、把 y 翻反、把参数顺序搞错，都会立刻变红。
 *
 * <p>夹具按真实故障形状造：dpr=2、画布 rect 有偏移、而**设计空间**尺寸（960×540）与
 * 帧缓冲尺寸不同 —— 旧公式除的正是前者，这正是 #624 那 364px / 12px 偏移的来源。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveCocosClickPoint, clickNodeViaCocos } from './cocos-click.mjs'

// ── 夹具常量（手算得出来，断言里会逐个对上）──────────────────────────────
const NODE = { x: 100, y: 50, w: 60, h: 30 } // 节点世界中心与 contentSize
const CAM = { scale: 2, ox: 40, oy: 100 } // 世界 → 引擎：ex = x*2+40，ey = y*2+100
const RECT = { left: 12, top: 8, width: 800, height: 600 }
const DPR = 2
const DESIGN = { w: 960, h: 540 } // getVisibleSizeInPixel() 那类**设计空间**尺寸
const CENTER_ENGINE = { x: NODE.x * CAM.scale + CAM.ox, y: NODE.y * CAM.scale + CAM.oy } // 240,200

/** 被替换掉的那条公式（verify-stamina.mjs 原来用的），保留成对照不是保留成修法。 */
function legacyCss(enginePoint) {
  return {
    x: RECT.left + (enginePoint.x / DESIGN.w) * RECT.width,
    y: RECT.top + RECT.height - (enginePoint.y / DESIGN.h) * RECT.height,
  }
}

class V3 {
  constructor(x = 0, y = 0, z = 0) { this.x = x; this.y = y; this.z = z }
}
class V2 {
  constructor(x = 0, y = 0) { this.x = x; this.y = y }
}

/** 造一棵最小 Cocos 树 + 一台可扰动的相机。 */
function install(opts = {}) {
  const swapArgs = opts.swapArgs === true // 假装引擎把 (out, worldPos) 写反了
  const breakRoundTrip = opts.breakRoundTrip === true // 假装 screenToWorld 不是 worldToScreen 的逆
  const alwaysMiss = opts.alwaysMiss === true // 假装节点自己永远不接受命中
  const depth = opts.depth ?? 4

  const fwd = swapArgs
    // 引擎把 (worldPos, out) 写反了：调用方传进来的**第一个** Vec3 被当成世界点，
    // 于是拿 (0,0,0) 当世界点算，返回的是画布原点附近 —— 往返必然对不回去。
    ? (arg1, arg2) => {
        arg2.x = arg1.x * CAM.scale + CAM.ox
        arg2.y = arg1.y * CAM.scale + CAM.oy
        arg2.z = 0
        return arg2
      }
    : (out, worldPos) => { out.x = worldPos.x * CAM.scale + CAM.ox; out.y = worldPos.y * CAM.scale + CAM.oy; out.z = 0; return out }
  const inv = (out, sp) => {
    if (breakRoundTrip) { out.x = (sp.x - CAM.ox) / CAM.scale + 777; out.y = sp.y; out.z = 0; return out }
    out.x = (sp.x - CAM.ox) / CAM.scale; out.y = (sp.y - CAM.oy) / CAM.scale; out.z = 0; return out
  }

  const cameraNode = {
    name: 'MainCamera',
    children: [],
    getComponent: (type) => (type === 'cc.Camera' ? camera : null),
  }
  const camera = {
    node: cameraNode,
    systemWindowId: 0,
    worldToScreen: fwd,
    screenToWorld: inv,
  }
  // UITransform 假件：hitTest 是**独立真值** —— 把引擎点按已知相机映射还原成世界点再按 contentSize 判框。
  const makeBox = (nx, ny, w, h) => ({
    contentSize: { width: w, height: h },
    anchorPoint: { x: 0.5, y: 0.5 },
    convertToWorldSpaceAR: () => new V3(nx, ny, 0),
    hitTest(v2, windowId) {
      if (alwaysMiss) return false
      if (windowId !== 0) return false // 引擎 ui-transform.ts:471 的 windowId 门
      const wx = (v2.x - CAM.ox) / CAM.scale
      const wy = (v2.y - CAM.oy) / CAM.scale
      return Math.abs(wx - nx) <= w / 2 && Math.abs(wy - ny) <= h / 2
    },
  })
  const box = makeBox(NODE.x, NODE.y, NODE.w, NODE.h)
  const target = {
    name: 'Resource-2-1',
    activeInHierarchy: opts.inactive === true ? false : true,
    getComponent: (type) => {
      if (type === 'cc.UITransform') return opts.noUiTransform === true ? null : box
      if (type === 'cc.Label') return { string: '体力 30/30' }
      return null
    },
    children: [],
  }
  const plate = {
    name: 'ResourcePlate',
    activeInHierarchy: true,
    getComponent: () => null,
    children: opts.targetPlacement === 'deep' ? [] : [target],
  }
  const buyBox = makeBox(300, 20, 80, 24)
  const buy = {
    name: 'BuyButton',
    activeInHierarchy: true,
    getComponent: (type) => (type === 'cc.UITransform' ? buyBox : null),
    children: [],
  }
  const overlay = { name: 'StaminaDetail', activeInHierarchy: true, getComponent: () => null, children: [buy] }
  const canvas = { name: 'Canvas', activeInHierarchy: true, getComponent: () => null, children: [plate, overlay] }
  const root = { name: 'Scene', activeInHierarchy: true, getComponent: () => null, children: [cameraNode, canvas] }
  // 深度上限那条坑：造一条很深的链，看递归是被上限**挡住**而不是无限展开。
  let deep = canvas
  for (let i = 0; i < depth; i++) {
    const child = { name: `Deep-${i}`, activeInHierarchy: true, getComponent: () => null, children: [] }
    deep.children.push(child)
    deep = child
  }
  if (opts.targetPlacement === 'deep') deep.children.push(target)

  globalThis.window = {
    cc: { Vec2: V2, Vec3: V3, director: { getScene: () => root } },
    devicePixelRatio: DPR,
  }
  globalThis.document = {
    querySelector: (sel) => (sel === 'canvas' ? { getBoundingClientRect: () => ({ ...RECT }) } : null),
  }
  return { root, camera, box, target }
}

const resolveStamina = (extra = {}) =>
  resolveCocosClickPoint(Object.assign({ labelPrefix: '体力' }, extra))

// ── 正例 ────────────────────────────────────────────────────────────────
test('dpr=2 时：CSS 点 = rect.left + 引擎x/dpr、rect.top + 高 - 引擎y/dpr（逐字手算对上）', () => {
  install()
  const r = resolveStamina()
  assert.equal(r.verified, true, '应当通过引擎自己的命中：' + JSON.stringify(r))
  assert.equal(r.reason, null)
  assert.equal(r.x, 12 + 240 / 2, 'x 必须是 rect.left + worldToScreen.x/dpr')
  assert.equal(r.y, 8 + 600 - 200 / 2, 'y 必须从下往上翻，不能沿用 CSS 顶为原点')
  assert.equal(r.engine.x, CENTER_ENGINE.x)
  assert.equal(r.engine.y, CENTER_ENGINE.y)
  assert.equal(r.camera, 'MainCamera')
  assert.equal(r.dpr, DPR)
  assert.equal(r.attempts[0].roundTripOk, true)
  assert.equal(r.attempts[0].insideCanvas, true)
})

test('旧公式与新公式放在同一份读数下对打：旧点被引擎判负、新点被引擎判正', () => {
  install()
  const newCss = legacyCss === null ? null : { x: 12 + 240 / 2, y: 8 + 600 - 200 / 2 }
  const oldCss = legacyCss(CENTER_ENGINE)
  const r = resolveStamina({
    compare: [
      { label: 'legacy', x: oldCss.x, y: oldCss.y },
      { label: 'cocos', x: newCss.x, y: newCss.y },
    ],
  })
  const byLabel = Object.fromEntries(r.compare.map((row) => [row.label, row]))
  assert.equal(byLabel.legacy.MainCamera, false, '旧公式那个点必须打不中 —— 它不是引擎认的坐标')
  assert.equal(byLabel.cocos.MainCamera, true, '新公式那个点必须打中')
  // 旧点确实落在了别人的地盘上/框外：把它的引擎坐标摆出来，供人工核对分母错在哪
  assert.equal(byLabel.legacy.engine.x, (oldCss.x - RECT.left) * DPR)
  assert.notEqual(Math.round(byLabel.legacy.engine.x), CENTER_ENGINE.x)
})

// ── 反例：三条自检各自能被卡住 ───────────────────────────────────────────
test('往返不一致时报 roundtrip-failed，而不是含糊地说"点不中"', () => {
  install({ breakRoundTrip: true })
  const r = resolveStamina()
  assert.equal(r.verified, false)
  assert.equal(r.reason, 'roundtrip-failed', 'reason 必须指向真正卡住的那一条')
  assert.equal(r.attempts[0].roundTripOk, false)
  assert.equal(r.x, null, '不可信就不许给出坐标 —— 给了就会被拿去当偏移量的基准')
})

test('引擎把 worldToScreen 的 (out, worldPos) 写反时，同样被判不可信（口径不靠猜）', () => {
  install({ swapArgs: true })
  const r = resolveStamina()
  assert.equal(r.verified, false)
  assert.equal(r.reason, 'roundtrip-failed')
})

test('节点自己不收命中时报 hittest-rejected', () => {
  install({ alwaysMiss: true })
  const r = resolveStamina()
  assert.equal(r.verified, false)
  assert.equal(r.reason, 'hittest-rejected')
  assert.equal(r.attempts[0].hitTest, false)
})

test('点落在画布外时报 outside-canvas（事件到不了 canvas 的监听器）', () => {
  // 命中与往返都成立，但引擎点换算出来的 CSS 点掉到了画布下沿之外（把画布压扁到 50px 高）。
  install()
  const savedHeight = RECT.height
  RECT.height = 50
  try {
    const r = resolveStamina()
    assert.equal(r.verified, false)
    assert.equal(r.reason, 'outside-canvas')
    assert.equal(r.attempts[0].hitTest, true, '这一条只该由"出画布"卡住，命中本身是通过的')
    assert.equal(r.attempts[0].roundTripOk, true, '往返也不能被牵连')
  } finally {
    RECT.height = savedHeight
  }
})

// ── 反例：定位与遍历 ────────────────────────────────────────────────────
test('找不到节点 / 范围内没有 / 不活跃 / 没有 UITransform：四种都给出各自的 reason', () => {
  install()
  assert.equal(resolveCocosClickPoint({ name: 'NoSuchNode' }).reason, 'node-not-found')
  assert.equal(resolveCocosClickPoint({ name: 'BuyButton', within: 'NoSuchScope' }).reason, 'within-not-found')
  assert.equal(resolveCocosClickPoint({ name: 'BuyButton', within: 'StaminaDetail' }).verified, true)

  install({ inactive: true })
  assert.equal(resolveStamina().reason, 'node-inactive')
  install({ noUiTransform: true })
  assert.equal(resolveStamina().reason, 'no-uitransform')
  assert.equal(resolveCocosClickPoint({}).reason, 'no-criterion')
})

test('递归带深度上限：节点埋在 80 层之下时是"找不到"，不是爆栈', () => {
  install({ depth: 80, targetPlacement: 'deep' })
  const r = resolveCocosClickPoint({ name: 'Resource-2-1' })
  assert.equal(r.verified, false)
  assert.equal(r.reason, 'node-not-found', '上限挡住了遍历，这必须是一条可判定的结局而不是挂死')
  // 对照：同一条链浅一点就找得到 —— 否则上面那条可能是"树里本来就没有"
  install({ depth: 5, targetPlacement: 'deep' })
  assert.equal(resolveCocosClickPoint({ name: 'Resource-2-1' }).verified, true)
})

// ── Node 侧门禁：不可信时一个像素都不点 ─────────────────────────────────
test('verified=false 时 clickNodeViaCocos 一次都不点；true 时点且坐标就是引擎给的那个', async () => {
  install({ alwaysMiss: true })
  const calls = []
  const page = {
    evaluate: async (fn, arg) => { assert.equal(typeof fn, 'function'); return fn(arg) },
    mouse: { click: (x, y) => calls.push([x, y]) },
  }
  const missed = await clickNodeViaCocos(page, { labelPrefix: '体力' })
  assert.equal(missed.clicked, false)
  assert.equal(calls.length, 0, '坐标不可信却点了 —— 这正是 #624 之后红绿不能当判据的原因')

  install()
  const hit = await clickNodeViaCocos(page, { labelPrefix: '体力' })
  assert.equal(hit.clicked, true)
  assert.equal(calls.length, 1)
  assert.deepEqual(calls[0], [132, 508])
})
