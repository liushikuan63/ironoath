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
  const outFirst = opts.outFirst === true // 假装引擎是 .d.ts 那种 (out, worldPos) 且**没有**默认值
  const breakRoundTrip = opts.breakRoundTrip === true // 假装 screenToWorld 不是 worldToScreen 的逆
  const alwaysMiss = opts.alwaysMiss === true // 假装节点自己永远不接受命中
  const depth = opts.depth ?? 4

  // ⚠️ 这里照抄**本仓产物**的实参序（#639），不是 .d.ts 的：
  // 引擎自己的包装是 `this._camera.worldToScreen(e, t)`，`e`（世界点）在**前**、`t`（输出）在后，
  // 且输出省略时默认复用传入的那个向量。实测一致：`worldToScreen(v)` 就地改 v 得到 (150,75) 是对的；
  // `worldToScreen(new V3(), world)` 会把 `world` 写成 (0,0)。
  // 所以**两参形式写错顺序会静默退化**，单参形式对两种顺序都成立 —— 自检要能抓住有人改回两参。
  const fwd = (worldPos, out) => {
    if (outFirst) {
      // .d.ts 那种顺序：out 在前且**没有默认值** ⇒ 单参调用直接拿不到 worldPos。
      if (worldPos === undefined) throw new TypeError('worldPos is undefined')
      out.x = worldPos.x * CAM.scale + CAM.ox; out.y = worldPos.y * CAM.scale + CAM.oy; out.z = 0
      return out
    }
    const dst = out === undefined ? worldPos : out
    dst.x = worldPos.x * CAM.scale + CAM.ox
    dst.y = worldPos.y * CAM.scale + CAM.oy
    dst.z = 0
    return dst
  }
  const inv = (sp, out) => {
    const dst = out === undefined ? sp : out
    if (breakRoundTrip) { dst.x = (sp.x - CAM.ox) / CAM.scale + 777; dst.y = sp.y; dst.z = 0; return dst }
    dst.x = (sp.x - CAM.ox) / CAM.scale; dst.y = (sp.y - CAM.oy) / CAM.scale; dst.z = 0; return dst
  }

  const cameraNode = {
    name: 'MainCamera',
    children: [],
    getComponent: (type) => (type === 'cc.Camera' ? camera : null),
  }
  const camera = {
    node: cameraNode,
    // 实测：组件 cc.Camera 上的 systemWindowId 是 **undefined**（hitTest 比的是渲染场景里那台）。
    systemWindowId: undefined,
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
      if (windowId !== 0) return false // 产物：`void 0===e&&(e=0); … h.systemWindowId===e`
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

test('夹具照抄产物：实参序是 (worldPos, out)，两参按 .d.ts 顺序传会**静默**退化成 (0,0)', () => {
  // #639 实测（1440×900、dpr=1、相机正交）：`worldToScreen(v)` 就地改 v → (150,75) 正确；
  // `worldToScreen(new Vec3(), world)` → world 变成 (0,0)，**不报错**。这就是 #638 首次实跑
  // 拿到的 (0,0) 与 roundtrip-failed。没有这条，夹具就成了"照着自己编的世界把关"。
  const { camera } = install()
  const one = camera.worldToScreen(new V3(100, 50, 0))
  assert.deepEqual([one.x, one.y], [CENTER_ENGINE.x, CENTER_ENGINE.y], '单参形式是产物真实行为')
  const out = new V3()
  const two = camera.worldToScreen(new V3(), out)
  // 第一个参数被当成世界点（这里是零向量）⇒ 输出是**相机原点那个角**，不是节点所在处。
  // 本仓真机上那个角恰好是 (0,0) —— 这就是 #638 首次实跑读到的 (0,0) 与 roundtrip-failed。
  assert.deepEqual([two.x, two.y], [CAM.ox, CAM.oy], '输出退化成"世界原点投影到屏幕"，且不抛异常')
  assert.notDeepEqual([two.x, two.y], [CENTER_ENGINE.x, CENTER_ENGINE.y], '绝不能等于节点自己的屏幕点')
})

test('解析器走单参形式，所以拿到的引擎点与实参序无关（不赌签名）', () => {
  install()
  const r = resolveStamina()
  assert.equal(r.verified, true, '单参形式对两种实参序都成立：' + JSON.stringify(r.attempts))
  assert.deepEqual([r.engine.x, r.engine.y], [CENTER_ENGINE.x, CENTER_ENGINE.y])
})

test('windowId 必须传 0：组件上的 systemWindowId 实测是 undefined，照抄它会恒判不命中', () => {
  // #639 实测：`hitTest` 比的是渲染场景里那台相机的 systemWindowId，组件 cc.Camera 上读到的
  // 是 undefined。传它 ⇒ `undefined !== 0` ⇒ 那台被 continue 掉 ⇒ 恒 false，
  // 症状长得跟"点不中"一模一样（#638 首次实跑就撞在这）。
  install()
  const r = resolveStamina()
  assert.equal(r.attempts[0].componentSystemWindowId, undefined, '夹具要照抄实况：组件上确实读不到')
  assert.equal(r.verified, true, '传 0 才过得了引擎那道 windowId 门')
  assert.equal(r.attempts[0].hitTest, true)
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
