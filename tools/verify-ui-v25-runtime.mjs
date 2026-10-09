/**
 * 职责：V25 素材的运行时验收 —— 证明包内素材**能加载、九宫格几何与 .png.meta 自洽**，
 *       并且**沿真实玩家路径画到屏上**（点体力 → 详情弹层用的就是 `ui.panel.iron`），留一张肉眼可判的截图。
 * 依赖：playwright、`tools/lib/preview-server.mjs`、本轮构建的 `client/build/web-mobile`、一台活后端。
 *       独立构建产物可用 `SWEEP_ROOT` 显式指定；默认与批跑入口使用同一份 web-mobile。
 *
 * 为什么不只跑 shot-panel-sweep：那 17 页量的是导航条面板，而本批改的是三个弹窗底板
 * （礼包 / 选择弹层 / 体力详情）—— 它们不在导航条上，新号登录也不一定有可弹的礼包。
 * "构建 missing=0" 只证明资源进了包，不证明**渲染正确**，这一份补的就是那一截。
 *
 * 为什么不在页面里手工 new Node + addComponent(UITransform)：release 产物的 `window.cc` 里
 * 拿不到 `UITransform` 这个类，`addComponent(undefined)` 抛 Cocos 错误 3804（实测两次）
 * ⇒ 改成走真实调用链：找带 `onStamina` 的组件调一次，弹层由生产代码自己建。
 *
 * 跑法：
 *   BACKEND_ORIGIN=http://localhost:8311 \
 *   SWEEP_PORT=8298 node tools/verify-ui-v25-runtime.mjs
 *   独立产物：另设 SWEEP_ROOT=client/build/ui-v25（先构建 outputName=ui-v25）。
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { resolveCocosClickPoint } from './lib/cocos-click.mjs'

const ROOT = path.resolve(process.env.SWEEP_ROOT ?? 'client/build/web-mobile')
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.SWEEP_PORT ?? 8298)
const OUT = process.env.UI25_OUT ?? 'tmp/ui-v25-shots'

// 期望值来自 art-src/弹窗面板美术风格_VibeCoding规格.md §二 三档契约 + §4.7 实测尺寸。
// ⚠ w/h 填的是**交付尺寸（未裁边）**：Cocos 导入会 trim 掉 1~2px 透明边，meta 的 width/height 是裁后的，
//    而运行时 frame 报的是原图尺寸 ⇒ 照 meta 抄会每条差 1~2px 全红（本轮实测撞过一次）。
const EXPECT = [
  // 统一 v3 五种底板采用同一张 512×326 薄框母版；角帽约16–18px，四边24保角并留缓冲。
  // 本表与 .png.meta 不一致时，本探针报的正是"改了图没改布局常量"那一族。
  { key: 'ui.panel.kingdom', res: 'ui/generated/ui/panel-kingdom-v1', w: 512, h: 326, inset: [24, 24, 24, 24] },
  { key: 'ui.panel.iron', res: 'ui/generated/ui/panel-iron-v1', w: 512, h: 326, inset: [24, 24, 24, 24] },
  { key: 'ui.panel.parchment', res: 'ui/generated/ui/panel-parchment-v1', w: 512, h: 326, inset: [24, 24, 24, 24] },
  { key: 'ui.panel.warning', res: 'ui/generated/ui/panel-warning-v1', w: 512, h: 326, inset: [24, 24, 24, 24] },
  { key: 'ui.panel.gilt', res: 'ui/generated/ui/panel-gilt-v1', w: 512, h: 326, inset: [24, 24, 24, 24] },
  { key: 'ui.button.iron', res: 'ui/generated/ui/button-iron-v1', w: 256, h: 49, inset: [12, 12, 4, 4] },
  { key: 'ui.button.iron.hover', res: 'ui/generated/ui/button-iron-hover-v1', w: 256, h: 49, inset: [12, 12, 4, 4] },
  { key: 'ui.chip.close', res: 'ui/generated/ui/chip-close-v1', w: 52, h: 52, inset: [0, 0, 0, 0] },
  { key: 'ui.plate.band', res: 'ui/generated/ui/plate-band-v1', w: 512, h: 40, inset: [16, 16, 4, 4] },
  { key: 'ui.plate.tooltip', res: 'ui/generated/ui/plate-tooltip-v1', w: 256, h: 50, inset: [12, 12, 4, 4] },
  { key: 'ui.button.chip', res: 'ui/generated/ui/button-chip-v1', w: 256, h: 49, inset: [12, 12, 4, 4] },
  { key: 'ui.button.chip.hover', res: 'ui/generated/ui/button-chip-hover-v1', w: 256, h: 49, inset: [12, 12, 4, 4] },
  { key: 'ui.button.chip.disabled', res: 'ui/generated/ui/button-chip-disabled-v1', w: 256, h: 49, inset: [12, 12, 4, 4] },
  { key: 'ui.nav.tab', res: 'ui/generated/ui/nav-tab-v1', w: 256, h: 100, inset: [12, 12, 12, 12] },
  { key: 'ui.nav.tab.selected', res: 'ui/generated/ui/nav-tab-selected-v1', w: 256, h: 100, inset: [12, 12, 12, 12] },
  // 方形化装饰与关闭符号边框为0，消费端用 SIMPLE + trim=false 并按原 canvas contain。
  { key: 'ui.crest.league', res: 'ui/generated/ui/crest-league-v1', w: 256, h: 256, inset: [0, 0, 0, 0] },
  { key: 'ui.crest.nation', res: 'ui/generated/ui/crest-nation-v1', w: 256, h: 256, inset: [0, 0, 0, 0] },
  { key: 'ui.crest.battle', res: 'ui/generated/ui/crest-battle-v1', w: 256, h: 256, inset: [0, 0, 0, 0] },
  { key: 'ui.seal.wax', res: 'ui/generated/ui/seal-wax-v1', w: 256, h: 256, inset: [0, 0, 0, 0] },
]
// 零消费的礼包旗帜、奖章与绳线已退回草稿区；余下条目继续逐件核对加载与几何。

console.log(`[ui-v25] 产物根目录=${ROOT} / 后端=${BACKEND}`)
const missingArtifacts = ['index.html', 'application.js', 'assets/resources/config.json']
  .filter(file => !existsSync(path.join(ROOT, file)))
if (missingArtifacts.length > 0) {
  console.error(`[ui-v25][前置] 产物不完整：${ROOT}，缺少 ${missingArtifacts.join('、')}`
    + '（默认先跑 scripts/build-webmobile.sh；独立产物须先构建，再设 SWEEP_ROOT）')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const post = async (url, body) => (await fetch(`${BACKEND}${url}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
})).json()
const init = await post('/player/init', {
  requestId: `ui25-init-${Date.now()}`, deviceId: `ui25-${Date.now()}`,
  nickName: '美术验收', clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[ui-v25][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((v) => { localStorage.setItem('ironoath.deviceId', v) }, `ui25-${Date.now()}`)
const page = await context.newPage()
const errors = []
const staminaRequests = []
page.on('pageerror', (e) => errors.push(e.message))
page.on('request', request => {
  const pathname = new URL(request.url()).pathname
  if (/\/stamina(?:\/buy)?$/.test(pathname)) staminaRequests.push({ method: request.method(), pathname })
})
await page.goto(preview.origin)
await page.waitForFunction(() => window.cc?.director?.getScene() != null, null, { timeout: 60000 })
await page.waitForTimeout(3000)

const results = []
const push = (name, pass, detail) => results.push({ name, pass, detail })

// 一、逐件加载并回读运行时 inset —— 手写 .png.meta 最容易错的就是尺寸/顶点/border 三者不自洽。
const loaded = await page.evaluate((specs) => new Promise((resolve) => {
  const { resources, SpriteFrame } = window.cc
  const out = []
  let done = 0
  specs.forEach((s) => {
    resources.load(`${s.res}/spriteFrame`, SpriteFrame, (err, frame) => {
      if (err || !frame) {
        out.push({ res: s.res, ok: false, why: `加载失败：${err?.message || 'frame 为 null'}` })
      } else {
        out.push({
          res: s.res, ok: true,
          px: [frame.originalSize.width, frame.originalSize.height],
          inset: [frame.insetLeft, frame.insetRight, frame.insetTop, frame.insetBottom],
        })
      }
      if (++done === specs.length) resolve(out)
    })
  })
}), EXPECT)

for (const spec of EXPECT) {
  const got = loaded.find((l) => l.res === spec.res)
  if (!got || !got.ok) {
    push(`${spec.key} 可加载`, false, got?.why || '没有回读结果')
    continue
  }
  push(`${spec.key} 可加载`, true, `运行时像素 ${got.px.join('x')}`)
  push(`${spec.key} 尺寸与 meta 一致`, got.px[0] === spec.w && got.px[1] === spec.h,
    `运行时 ${got.px.join('x')} / 期望 ${spec.w}x${spec.h}`)
  push(`${spec.key} inset 与 meta 的 border 一致`, got.inset.join(',') === spec.inset.join(','),
    `运行时 L/R/T/B=${got.inset.join('/')} / meta=${spec.inset.join('/')}`)
}

// 二、真实玩家路径：点体力 → CityPanelView.onStamina → AppRoot.openStaminaDetail()
//     → 服务端 staminaView → StaminaDetailOverlay.render() —— 底板正是 ui.panel.iron。
const clicked = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let called = 0
  const names = []
  const visit = (n) => {
    for (const c of (n.components || [])) {
      if (typeof c.onStamina === 'function') {
        names.push(`${n.name}/${c.constructor?.name || '?'}`)
        try { c.onStamina(); called++ } catch (e) { /* 计数已加，异常留给 pageerror 兜 */ }
      }
    }
    ;(n.children || []).forEach(visit)
  }
  visit(scene)
  return { called, names }
})
push('找到并调用 onStamina（真实触发链）', clicked.called > 0,
  `命中 ${clicked.called} 处：${clicked.names.slice(0, 2).join(', ') || '无'}`)
await page.waitForTimeout(2500)

// 三、回读屏上是否真有一块 SLICED 的图在画着，并核它两条：没退化、边框也没吃掉可读区
//     （与 verify-art-runtime 同一条判据；V25-e 把它同时接进这一份，因为**只有这一份会画到 panel-iron**）。
const rendered = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const found = []
  const visit = (n) => {
    const sp = (n.components || []).find((c) => c.constructor?.name === 'cc.Sprite' || c.spriteFrame !== undefined)
    if (sp && sp.spriteFrame) {
      const f = sp.spriteFrame
      // release 产物会压缩类名 ⇒ 组件必须按**注册名**取（本仓既有配方）；
      // 直接读 n.uiTransform 在压缩后拿不到，会把盒子读成 0x0 并伪装成"退化"（实测踩过）。
      const ut = n.getComponent('cc.UITransform')
      const name = String(f.name || '')
      if (/panel-iron|plate-band|button-iron/.test(name)) {
        found.push({
          node: n.name, frame: name, type: sp.type,
          box: ut ? [ut.width, ut.height] : null,
          inset: [f.insetLeft, f.insetRight, f.insetTop, f.insetBottom],
          degenerate: !!ut && (ut.width < f.insetLeft + f.insetRight || ut.height < f.insetTop + f.insetBottom),
        })
      }
    }
    ;(n.children || []).forEach(visit)
  }
  visit(scene)
  return found
})
push('屏上真有 V25 素材在画（玩家路径渲染）', rendered.length > 0,
  rendered.length ? rendered.map((r) => `${r.node}:${r.frame}@${r.box ? r.box.join('x') : '?'}`).join(' | ') : '一张都没找到')
for (const r of rendered) {
  push(`未退化：${r.node} (${r.frame})`, !!r.box && !r.degenerate,
    r.box ? `消费 ${r.box.join('x')} vs inset 和 L+R=${r.inset[0] + r.inset[1]} T+B=${r.inset[2] + r.inset[3]}，type=${r.type}`
      : '读不到 cc.UITransform —— 量具问题，不是退化')
  // V25-e：不退化只是"引擎没把整张图缩小"，还要"边框没吃掉可读区"（规格 §二 的 0.6）。
  // 这条在这一份里比在 verify-art-runtime 里更值钱：只有这一份沿真实链路画出 panel-iron/button-iron。
  const ratio = r.box && r.box[0] > 0 && r.box[1] > 0
    ? Math.max((r.inset[0] + r.inset[1]) / r.box[0], (r.inset[2] + r.inset[3]) / r.box[1])
    : null
  push(`边厚不吃可读区：${r.node} (${r.frame})`, ratio !== null && ratio <= 0.6,
    ratio === null ? '读不到消费尺寸 —— 量具问题，不是通过'
      : `边框占可读区 ${ratio.toFixed(2)}（阈 0.6），消费 ${r.box.join('x')}`
        + ` / 边框和 ${r.inset[0] + r.inset[1]}x${r.inset[2] + r.inset[3]}`)
}

// 三之二、置灰：生产弹层是 targets() 里的普通类局部实例，不是节点上的 Component。
// 先沿真实 GET 链捕获正在 render 的 this，再驱动同一实例；不 new 假节点，也不静默跳过。
// 素材与 Graphics 兜底的静态判据继续保留，防止任一分支在换图时丢掉置灰语义。
const overlaySrc = readFileSync(path.resolve(process.cwd(),
  'client/assets/scripts/scene/StaminaDetailOverlay.ts'), 'utf8')
push('置灰：贴图路径在（灰态压暗 tint）',
  /buySprite\.color\s*=\s*[\s\S]{0,80}COLOR_TINT_ON[\s\S]{0,40}COLOR_TINT_OFF/.test(overlaySrc),
  'render() 里必须有 `buySprite.color = 可点 ? TINT_ON : TINT_OFF`')
push('置灰：兜底路径未被删（Graphics 换色）',
  /buyBackground\.fillColor\s*=\s*[\s\S]{0,60}COLOR_BUY\s*:\s*COLOR_BUY_OFF/.test(overlaySrc),
  '素材加载失败那条路仍要能置灰，否则贴图缺失时灰态消失')
push('置灰：协议要求"画着但不响应"（active 不被置 false）',
  !/buy\.active\s*=\s*false/.test(overlaySrc) && /buyEnabled/.test(overlaySrc),
  '协议明写置灰而不是隐藏；这里断言没有把按钮 active 关掉')
const captureRequestsBefore = staminaRequests.length
const captured = await page.evaluate(async () => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const bootstrap = game?.getComponent('GameBootstrap')
  const entry = typeof window.System?.entries === 'function'
    ? Array.from(window.System.entries()).find(([key, module]) =>
      /(?:^|\/)StaminaDetailOverlay\.ts(?:$|\?)/.test(key)
      && typeof module?.StaminaDetailOverlay?.prototype?.render === 'function') : null
  if (!bootstrap?.root || typeof bootstrap.root.openStaminaDetail !== 'function' || !entry) {
    return { found: false, error: '缺 GameBootstrap.root 或实际 StaminaDetailOverlay 模块 export' }
  }
  const prototype = entry[1].StaminaDetailOverlay.prototype
  const original = prototype.render
  let owner = null, vm = null
  prototype.render = function (view) {
    const rendered = original.call(this, view)
    if (this.node?.name === 'StaminaDetail' && this.node.parent === game) { owner = this; vm = view }
    return rendered
  }
  try {
    await bootstrap.root.openStaminaDetail()
  } catch (error) {
    return { found: false, error: `真实体力 GET 链失败：${error.message}` }
  } finally {
    prototype.render = original
  }
  if (!owner || !vm || typeof owner.render !== 'function' || owner.node.activeInHierarchy !== true) {
    return { found: false, error: '真实 GET 链未 render 当前可见的体力弹层实例' }
  }
  const buy = owner.node.getChildByName('Panel')?.getChildByName('BuyButton')
  if (!buy) return { found: false, error: '生产实例没有 BuyButton，不能以零意图冒充点击通过' }
  const saved = { owner, view: owner.currentView ?? vm, onBuy: owner.onBuy, intents: 0, buy, touches: 0 }
  saved.touchProbe = () => { saved.touches += 1 }
  buy.on('touch-start', saved.touchProbe)
  window.__ui25StaminaProbe = saved
  owner.onBuy = () => { window.__ui25StaminaProbe.intents += 1 }
  return { found: true, module: entry[0], prototypeRestored: prototype.render === original }
})
push('灰态运行时前置：真实 GET 捕获生产弹层实例并还原 prototype',
  captured.found === true && captured.prototypeRestored === true, JSON.stringify(captured))
const captureGets = staminaRequests.slice(captureRequestsBefore)
  .filter(request => request.method === 'GET' && /\/stamina$/.test(request.pathname))
push('灰态运行时前置：捕获实例经过真实体力 GET', captureGets.length > 0, JSON.stringify(captureGets))
if (captured.found !== true) {
  console.error(`[ui-v25][前置] ${captured.error}；灰态验收不能跳过`)
  await browser.close()
  await preview.close()
  process.exit(2)
}

function readStaminaBuyPaint() {
  const saved = window.__ui25StaminaProbe
  const owner = saved?.owner
  const scene = window.cc.director.getScene()
  const buy = owner?.node?.getChildByName('Panel')?.getChildByName('BuyButton')
  const art = buy?.getChildByName('DialogButtonArt') ?? buy
  const sprite = art?.getComponent('cc.Sprite')
  const caption = buy?.getChildByName('Caption')
  const label = caption?.getComponent('cc.Label')
  if (!buy || !art || !sprite?.spriteFrame || !label) return { found: false, error: '缺真实 BuyButton 首 Sprite 或 Caption' }
  const cameras = scene.getComponentsInChildren('cc.Camera')
    .filter(camera => camera.enabled && camera.node.activeInHierarchy)
  const visible = node => cameras.some(camera => (camera.visibility & node.layer) !== 0)
  const opaqueAncestors = node => {
    for (let ancestor = node; ancestor; ancestor = ancestor.parent) {
      if (ancestor.getComponent('cc.UIOpacity')?.opacity === 0) return false
    }
    return true
  }
  return { found: true, active: buy.activeInHierarchy && art.activeInHierarchy,
    enabled: sprite.enabled, firstRenderer: art._uiProps?.uiComp === sprite,
    layer: art.layer, parentLayer: art.parent?.layer, camera: visible(art), opaqueAncestors: opaqueAncestors(art),
    frame: sprite.spriteFrame.name, type: sprite.type, tint: [sprite.color.r, sprite.color.g, sprite.color.b, sprite.color.a],
    caption: label.string, captionActive: caption.activeInHierarchy, captionEnabled: label.enabled,
    captionFirstRenderer: caption._uiProps?.uiComp === label, captionCamera: visible(caption),
    captionLayer: caption.layer, captionParentLayer: caption.parent?.layer,
    captionOpaqueAncestors: opaqueAncestors(caption), captionColor: [label.color.r, label.color.g, label.color.b, label.color.a],
    buyEnabled: owner.currentView?.buyEnabled ?? owner.view?.buyEnabled,
    intents: saved.intents, touches: saved.touches }
}
const staminaPaintConsumed = row => row.found === true && row.active && row.enabled && row.firstRenderer
  && row.camera && row.opaqueAncestors && row.layer === row.parentLayer && row.type === 1
  && row.frame === 'button-iron-v1' && row.tint?.[3] > 0
  && row.captionActive && row.captionEnabled && row.captionFirstRenderer && row.captionCamera
  && row.captionOpaqueAncestors && row.captionLayer === row.captionParentLayer && row.captionColor?.[3] > 0
const staminaGrayIssues = row => {
  const issues = []
  if (!staminaPaintConsumed(row)) issues.push('真实按钮首 Sprite/字/相机/层未消费')
  if (row.buyEnabled !== false || row.caption !== '今日已达上限') issues.push('未 render 实际禁用帧')
  if (row.tint?.join(',') !== '104,96,88,255') issues.push(`首 Sprite 不是灰 tint：${row.tint}`)
  if (row.captionColor?.join(',') !== '70,62,52,255') issues.push(`禁用字色错误：${row.captionColor}`)
  return issues
}
const staminaGrayEvidence = { captured, captureGets }
try {
  await page.evaluate(() => {
    const saved = window.__ui25StaminaProbe
    saved.owner.render({ ...saved.view, buyEnabled: false, buyLabel: '今日已达上限' })
  })
  await page.waitForTimeout(80)
  const gray = await page.evaluate(readStaminaBuyPaint)
  staminaGrayEvidence.gray = gray
  push('运行时灰态：真实首 Sprite 压暗、字色同步变灰、active 与可见层保留',
    staminaGrayIssues(gray).length === 0, JSON.stringify({ paint: gray, issues: staminaGrayIssues(gray) }))
  await hideGuideOverlay(page)
  const purchasesBefore = staminaRequests.filter(request => /\/stamina\/buy$/.test(request.pathname)).length
  const point = await page.evaluate(resolveCocosClickPoint, { name: 'BuyButton', within: 'StaminaDetail' })
  push('运行时灰态：真实鼠标坐标通过相机往返与引擎命中', point.verified === true, JSON.stringify(point))
  if (point.verified === true) await page.mouse.click(point.x, point.y)
  await page.waitForTimeout(350)
  const afterGrayClick = await page.evaluate(readStaminaBuyPaint)
  const purchases = staminaRequests.filter(request => /\/stamina\/buy$/.test(request.pathname))
  staminaGrayEvidence.disabledClick = { point, after: afterGrayClick, purchases }
  push('运行时灰态：真实鼠标触摸抵达 BuyButton（避免被别的弹层吞掉而假绿）',
    point.verified === true && afterGrayClick.touches === 1,
    JSON.stringify({ point: { x: point.x, y: point.y }, touches: afterGrayClick.touches }))
  push('运行时灰态：真点击零购买意图且零购买请求', point.verified === true
    && afterGrayClick.found === true && afterGrayClick.touches === 1
    && afterGrayClick.intents === 0 && purchases.length === purchasesBefore,
    JSON.stringify({ touches: afterGrayClick.touches, intents: afterGrayClick.intents, requests: purchases.slice(purchasesBefore) }))
  push('运行时灰态：真点击后首 Sprite 仍保持灰态', staminaGrayIssues(afterGrayClick).length === 0,
    JSON.stringify({ paint: afterGrayClick, issues: staminaGrayIssues(afterGrayClick) }))
  await page.screenshot({ path: path.join(OUT, 'ui-v25-stamina-disabled.png') })

  // 改实际首 Sprite 的色，不改 readback 或 VM；同一灰态门必须因 tint 变白而红。
  try {
    const changed = await page.evaluate(() => {
      const saved = window.__ui25StaminaProbe
      const buy = saved.owner.node.getChildByName('Panel').getChildByName('BuyButton')
      const art = buy.getChildByName('DialogButtonArt') ?? buy
      const sprite = art.getComponent('cc.Sprite')
      if (!sprite || art._uiProps?.uiComp !== sprite) return false
      saved.spriteRestore = { sprite, color: sprite.color.clone() }
      const color = sprite.color.clone()
      color.r = 255; color.g = 255; color.b = 255
      sprite.color = color
      return true
    })
    await page.waitForTimeout(60)
    const negative = await page.evaluate(readStaminaBuyPaint)
    const issues = staminaGrayIssues(negative)
    staminaGrayEvidence.negative = { changed, paint: negative, issues }
    push('灰态负控：真实首 Sprite 改白使同一灰态门翻红', changed
      && staminaPaintConsumed(negative) && issues.some(issue => issue.startsWith('首 Sprite 不是灰 tint：')),
      JSON.stringify({ paint: negative, issues }))
    await page.screenshot({ path: path.join(OUT, 'ui-v25-stamina-disabled-white-negative.png') })
  } finally {
    await page.evaluate(() => {
      const saved = window.__ui25StaminaProbe
      if (saved.spriteRestore) saved.spriteRestore.sprite.color = saved.spriteRestore.color
      delete saved.spriteRestore
    })
  }
  await page.waitForTimeout(60)
  const restoredGray = await page.evaluate(readStaminaBuyPaint)
  staminaGrayEvidence.restoredGray = restoredGray
  push('灰态负控还原：同一灰态门恢复绿', staminaGrayIssues(restoredGray).length === 0,
    JSON.stringify({ paint: restoredGray, issues: staminaGrayIssues(restoredGray) }))
  await page.evaluate(() => {
    const saved = window.__ui25StaminaProbe
    saved.owner.render({ ...saved.view, buyEnabled: true })
  })
  await page.waitForTimeout(60)
  const enabled = await page.evaluate(readStaminaBuyPaint)
  staminaGrayEvidence.enabled = enabled
  push('启用对照：生产 render 恢复白 tint 和原字色（不购买）', staminaPaintConsumed(enabled)
    && enabled.buyEnabled === true && enabled.tint.join(',') === '255,255,255,255'
    && enabled.captionColor.join(',') === '226,214,190,255' && enabled.intents === 0,
    JSON.stringify(enabled))
  const requestsAfterEnabled = staminaRequests.filter(request => /\/stamina\/buy$/.test(request.pathname))
  push('灰态、负控和启用对照全过程零购买请求', requestsAfterEnabled.length === purchasesBefore,
    JSON.stringify(requestsAfterEnabled.slice(purchasesBefore)))
} finally {
  await page.evaluate(() => {
    const saved = window.__ui25StaminaProbe
    if (!saved) return
    try { saved.owner.render(saved.view) } finally {
      saved.buy.off('touch-start', saved.touchProbe)
      saved.owner.onBuy = saved.onBuy
      delete window.__ui25StaminaProbe
    }
  })
}
writeFileSync(path.join(OUT, 'ui-v25-stamina-gray-evidence.json'), JSON.stringify(staminaGrayEvidence, null, 2))

// 三之三、MarchComposeOverlay 的遮罩不能被材质换图顺手删掉。
// 风险形状：该弹层原先把「整屏遮罩 rect」与「底板 roundRect」画在**同一个 Graphics** 上，
// 而 applySlicedSprite 会清空所挂节点的整张画布 ⇒ 若把底板换回原节点，遮罩会一起消失，
// 底下搜索面板的字与标题叠在一起（该文件注释里记着 `compose-mode-*.png` 那次两张都拍到的事故）。
// 打开这个弹层要造部队 + 目标（成本高、且会占集结名额），所以这里钉**结构**而不是钉像素：
// 遮罩必须还画在 background 上，而贴图/兜底底板必须在**另一个节点**里。
const composeSrc = readFileSync(path.resolve(process.cwd(),
  'client/assets/scripts/scene/MarchComposeOverlay.ts'), 'utf8')
const scrimStillOnBackground = /background\.rect\(\s*-screen\.width/.test(composeSrc)
const plateOnOwnNode = /new Node\('plate'\)[\s\S]{0,400}applySlicedSprite\(plate,\s*'ui\.panel\.iron'/.test(composeSrc)
const plateNotDrawnOnBackground = !/background\.roundRect\(\s*-width\s*\/\s*2/.test(composeSrc)
push('集结弹层：整屏遮罩仍画在 background 上', scrimStillOnBackground,
  '断言 `background.rect(-screen.width…)` 还在 —— 少了它弹层打开时底下面板照常亮')
push('集结弹层：底板在独立 plate 节点（贴图路径）', plateOnOwnNode,
  "断言有 `new Node('plate')` 且对它调 applySlicedSprite('ui.panel.iron')")
push('集结弹层：底板不再画回 background（否则遮罩会被一起清空）', plateNotDrawnOnBackground,
  '兜底 roundRect 必须画在 plate 自己的 Graphics 上，不能回到 background')

const shot = path.join(OUT, 'ui-v25-stamina-on-screen.png')
await page.screenshot({ path: shot })

// 四、走真实鼠标入口读取导航两态和 chip 三态。私聊未选对象只切换显示，不点击发送。
// 除加载之外，Sprite 必须成为首个 UI renderer，并被有效相机看到；enabled+frame 不能代替消费。
function readSmallUi() {
  const scene = window.cc.director.getScene()
  const cameras = [], rows = []
  const collect = node => {
    const camera = node.getComponent('cc.Camera')
    if (camera && camera.enabled && node.activeInHierarchy) cameras.push(camera)
    for (const child of node.children ?? []) collect(child)
  }
  collect(scene)
  const visit = node => {
    if (!node.activeInHierarchy) return
    const sprite = node.getComponent('cc.Sprite'), box = node.getComponent('cc.UITransform')
    const frame = sprite?.spriteFrame, name = String(frame?.name ?? '')
    if (frame && /button-chip|nav-tab/.test(name)) {
      const border = [frame.insetLeft, frame.insetRight, frame.insetTop, frame.insetBottom]
      rows.push({ node: node.name, frame: name, type: sprite.type, enabled: sprite.enabled,
        active: node.activeInHierarchy, renderer: node._uiProps?.uiComp === sprite,
        alpha: sprite.color?.a ?? 0, layer: node.layer, parentLayer: node.parent?.layer,
        visible: cameras.some(camera => (camera.visibility & node.layer) !== 0),
        box: box ? [box.width, box.height] : null, border,
        ratio: box && box.width > 0 && box.height > 0
          ? Math.max((border[0] + border[1]) / box.width, (border[2] + border[3]) / box.height) : null,
        degenerate: !box || box.width <= 0 || box.height <= 0
          || box.width < border[0] + border[1] || box.height < border[2] + border[3] })
    }
    for (const child of node.children ?? []) visit(child)
  }
  visit(scene)
  return rows
}
const smallUiConsumed = row => row.active && row.enabled && row.renderer && row.visible
  && row.alpha > 0 && row.layer === row.parentLayer && row.type === 1

await hideGuideOverlay(page)
let controlsReady = true
for (const target of [
  { name: 'CloseButton', within: 'StaminaDetail' },
  { name: 'Nav-more' }, { name: 'Nav-social' },
  { name: 'Tab_chat', within: 'social' },
  { name: 'Channel_PRIVATE', within: 'social' },
]) {
  const point = await page.evaluate(resolveCocosClickPoint, target)
  push(`真实小件入口：${target.name} 经引擎自命中`, point.verified, JSON.stringify(point))
  if (!point.verified) { controlsReady = false; break }
  await page.mouse.click(point.x, point.y)
  await page.waitForTimeout(400)
}
const smallRows = await page.evaluate(readSmallUi)
for (const spec of EXPECT.filter(spec => /button-chip|nav-tab/.test(spec.res))) {
  const matches = smallRows.filter(row => row.frame === path.basename(spec.res))
  push(`${spec.key} 在实际切页后可见`, controlsReady && matches.length > 0, JSON.stringify(matches))
  push(`${spec.key} 真实首 renderer 消费`, matches.length > 0 && matches.every(smallUiConsumed),
    JSON.stringify(matches))
  push(`${spec.key} 九宫格有效且边占比≤0.6`, matches.length > 0
    && matches.every(row => !row.degenerate && row.ratio !== null && row.ratio <= 0.6), JSON.stringify(matches))
}
await page.screenshot({ path: path.join(OUT, 'ui-v25-nav-chip-three-states.png') })

// 禁用当前可见的真实选中导航 Sprite，必须使同一消费判据变红；finally 恢复实际材质。
const negativeBaseline = smallRows.filter(row => /nav-tab-selected-v1/.test(row.frame))
const canDisable = negativeBaseline.length > 0 && negativeBaseline.every(smallUiConsumed)
push('导航消费负控前提：真实选中材质有效', canDisable, JSON.stringify(negativeBaseline))
if (canDisable) {
  try {
    const changed = await page.evaluate(() => {
      const find = node => {
        if (!node.activeInHierarchy) return null
        const sprite = node.getComponent('cc.Sprite')
        if (/nav-tab-selected-v1/.test(String(sprite?.spriteFrame?.name ?? ''))) return sprite
        for (const child of node.children ?? []) { const hit = find(child); if (hit) return hit }
        return null
      }
      const sprite = find(window.cc.director.getScene())
      if (!sprite) return false
      window.__ui25DisabledSprite = { sprite, enabled: sprite.enabled }
      sprite.enabled = false
      return true
    })
    await page.waitForTimeout(60)
    const negative = (await page.evaluate(readSmallUi)).filter(row => /nav-tab-selected-v1/.test(row.frame))
    push('禁用真实导航材质使消费判据翻红', changed && negative.length > 0
      && negative.some(row => !smallUiConsumed(row) && row.enabled === false), JSON.stringify(negative))
    await page.screenshot({ path: path.join(OUT, 'ui-v25-nav-disabled-negative.png') })
  } finally {
    await page.evaluate(() => {
      const saved = window.__ui25DisabledSprite
      if (saved) saved.sprite.enabled = saved.enabled
      delete window.__ui25DisabledSprite
    })
  }
  await page.waitForTimeout(60)
  const restored = (await page.evaluate(readSmallUi)).filter(row => /nav-tab-selected-v1/.test(row.frame))
  push('导航材质还原后同一消费判据恢复绿', restored.length > 0 && restored.every(smallUiConsumed),
    JSON.stringify(restored))
}

const failed = results.filter((r) => !r.pass)
results.forEach((r) => console.log(`  ${r.pass ? 'PASS' : 'FAIL'}  ${r.name}  —  ${r.detail}`))
console.log(`\n[ui-v25] ${results.length} 条判据 / 失败 ${failed.length}`)
console.log(`[ui-v25] 截图：${shot}`)
if (errors.length) console.log(`[ui-v25] pageerror ${errors.length} 条：${errors.slice(0, 2).join(' | ')}`)
await browser.close()
await preview.close()
process.exitCode = failed.length || errors.length ? 1 : 0
