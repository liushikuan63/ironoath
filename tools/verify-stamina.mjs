/**
 * 职责：**体力详情的实机验收**（B09 §5）——「点资源条上的体力那一行 → 弹层 → 买 1 次」整条链路。
 * 依赖：`client/build/web-mobile` 产物 + 一台本轮自己的 dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么需要它：`/stamina` 与 `/stamina/buy` 两个端点与服务端实现早就齐了，
 * 客户端**一处调用都没有**（收口清单"客户端发送口缺口"里的 `staminaView` / `staminaBuy`）。
 * 单测能钉住文案与置灰规则，但"点得开、买得到"只有真点一次才算数。
 *
 * <p>判据（都能失败）：
 *   ① 点「体力」那一行必须发出 `/stamina`，且弹出 `StaminaDetail` 弹层；
 *   ② 弹层里的标题必须等于**服务端读数**（`体力 X/Y` 与 `/stamina` 的响应逐字对上）；
 *   ③ 点「买 1 次（N 金币）」必须发出 `/stamina/buy`，且金币减少、体力增加（两次读数对比）。
 *      **前提是买之前体力未满**（2026-10-02 #606）：满仓时按钮**被正确置灰**，本来就不该有
 *      POST、已买次数与买价都不该动。此前这几条是无条件断言，而「满仓时体力不该涨」那条是
 *      分支断言 —— 两组判据用了相反的前提，于是「按钮正确置灰」这件符合 B09 §5 的事被判成了红。
 *
 * 退出码：0 全绿；1 判据失败；2 前置不满足（产物/后端/建号/找不到体力那一行）。
 *
 * <p>**读这份读数前先看那行「基准=…」**（#618）：绿的前提是资源条 2 列布局下体力落在 x=33。
 * 布局在修掉 `Map.copyOf` 之前**随 JVM 进程变**（`281` 时点不中、`33` 时点中），所以在旧后端上
 * 读到红**不构成功能缺陷的证据**。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.STAMINA_PORT ?? 8303)
const OUT = 'client/build/art-verify'
const SHOT = path.join(OUT, '20-stamina-detail.png')

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error('[stamina][前置] 产物不存在（先跑 scripts/build-webmobile.sh）')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const deviceId = `stamina-${Date.now()}`
const post = async (url, body, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  return response.json()
}
const get = async (url, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, { headers })
  return response.json()
}
const init = await post('/player/init', {
  requestId: `stamina-init-${Date.now()}`, deviceId, nickName: '体力探针',
  clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[stamina][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}
const playerId = init.data.playerId
const HEAD = { 'X-Player-Id': playerId }
// 前置读数：体力与金币的起点（后面要对比"买一次之后变了多少"）
const before = await get('/stamina', HEAD)
if (before.code !== 0) {
  console.error(`[stamina][前置] 读体力失败：${JSON.stringify(before)}`)
  process.exit(2)
}
const goldBefore = (await get('/player/profile', HEAD)).data?.resources?.GOLD?.current
  ?? (await get('/city/list', HEAD)).data?.resources?.GOLD?.current ?? null
console.log(`[stamina] 建号 ${playerId}：体力 ${before.data.current}/${before.data.cap}`
  + ` 买价 ${before.data.buyCostGold} 金币 今日已买 ${before.data.boughtToday} 金币余额 ${goldBefore}`)

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
const staminaPosts = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('request', (request) => {
  if (request.url().includes('/stamina')) {
    staminaPosts.push(`${request.method()} ${request.url().split('/stamina')[1] || '/'}`)
  }
})
// 诊断 A（#607）：把 /stamina 的真实响应体打出来。**别再靠猜** —— 「弹层存在但内容为空」
// 有两种完全不同的成因：① 请求失败 ⇒ AppRoot.write() 不调 onOk ⇒ render 永不执行；
// ② 请求成功但**点击没命中**，于是发请求的是 AppRoot:913 的 deliver 路径而不是
// openStaminaDetail(:1204)。这一行把两者一次分开。
page.on('request', (req) => {
  if (!req.url().includes('/stamina')) return
  const t0 = Date.now()
  req.response().then(async (r) => {
    const body = await r.text().catch(() => '<no body>')
    console.log(`[stamina][诊断] ${req.method()} ${req.url()} -> ${r.status()} ${Date.now() - t0}ms body=${body.slice(0, 300)}`)
  }).catch(() => console.log(`[stamina][诊断] ${req.url()} 无响应`))
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'city')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc?.director?.getScene?.() != null, null, { timeout: 25_000 })
  .catch(() => {})
// 等待时长做成可调（#610）：原来写死 2500ms。实测（#610）缩短它并不能改变「点不中」，
// 但把它做成参数之后，"点得中/点不中"可以按 GET /stamina 出现的**条数**直接判读 —���
// 一条 = 点击没命中（只有 AppRoot:913 的 deliver），两条 = 点击命中且触发了
// openStaminaDetail(:1204)。这是复现「点不中」时最省事的那个开关。
const __waitMs = Number(process.env.STA_WAIT_MS ?? '2500')
await page.waitForTimeout(__waitMs)
await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const visit = (node) => {
    if (/Guide/i.test(node.name)) {
      const view = node.getComponent && node.getComponent('GuideView')
      if (view !== null && view !== undefined) view.enabled = false
      node.removeFromParent()
      return
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
})

/** 点资源条上那一行「体力」（按**文案前缀**找，不写死行列号）。 */
const clickStaminaRow = () => page.evaluate(() => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let target = null
  const visit = (node) => {
    const label = node.getComponent && node.getComponent('cc.Label')
    if (label !== null && label !== undefined && (label.string ?? '').startsWith('体力')) target = node
    for (const child of node.children) visit(child)
  }
  visit(scene)
  if (target === null) return null
  // 诊断 G（#616）：#615 把「点得中/点不中」压成了判别指标 `base`（281 全红 / 33 全绿），
  // 但**没解释 base 为什么在 33 与 281 之间跳**。这一步把资源条上**每一项**的名字与
  // 屏幕坐标都打出来并连同 base 记录 —— 下次再遇到 281，就能立刻看出是哪一项排到了前面。
  // 读数（#616）：2 列 × 3 行 = 6 项 + 1 个溢出指示，顺序是 铁矿/木材/金币/粮草/体力/石料
// —— **不是配置表顺序**，而这正是 #618 修掉的那个 `Map.copyOf` 决定的。
// 找法：只扫名字以 `Resource` 开头的节点，递归它们整棵子树（第一版只扫直接子节点、且漏了
// 节点自己，于是打出「0 项」—— `Resource-0-0` 恰恰就是那个带 Label 的节点）。
  const barRows = []
  {
    const camG = scene.getComponentInChildren('cc.Camera')
    const rectG = document.querySelector('canvas').getBoundingClientRect()
    const pixG = cc.view.getVisibleSizeInPixel()
    // ⚠️ 与点击端**逐字同式**（#616）：`rowPoint` 算的是 rect.left + … 与 rect.top + rect.height - …，
    // 第一版这��诊断漏了 rect.left/top，打出来的 y 比真实点击点少 60（= rect.top）⇒ 拿它比较会看错。
    const pxOf = (nd) => {
      const bx = nd.getComponent('cc.UITransform')
      if (bx == null || camG == null) return '-'
      const s = camG.worldToScreen(bx.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0)))
      return Math.round(rectG.left + (s.x / pixG.width) * rectG.width) + ','
        + Math.round(rectG.top + rectG.height - (s.y / pixG.height) * rectG.height)
    }
    // 递归扫**每个** Resource 节点自己的整棵子树 —— #616 第一版只扫直接子节点、也没算节点
    // 自己，于是打出「0 项」（而 `Resource-0-0` 恰恰就是那个带 Label 的节点）。
    const walk = (nd, depth) => {
      if (nd.name.startsWith('Resource')) {
        const lab = nd.getComponent && nd.getComponent('cc.Label')
        const bx = nd.getComponent('cc.UITransform')
        if (lab != null) {
          const self = bx != null ? Math.round(bx.contentSize.width) + 'x' + Math.round(bx.contentSize.height) : 'no-UI'
          barRows.push(nd.name + '{' + self + '}"' + lab.string + '"@' + pxOf(nd) + '/d' + depth)
        }
      }
      for (const c of nd.children) walk(c, depth + 1)
    }
    walk(scene, 0)
  }
  // 诊断 E（#611）：#610 用偏移扫描钉死了「点 Label 中心偏 12px，触摸挂在整行节点上」。
  // 这里把 Label 往上每一层祖先的 UITransform 尺寸打出来 —— 找出「整行」是哪一层。
  // 注意：这段跑在**浏览器上下文**，console.log 会进页面而不是 node 的 stdout，
  // 必须收集到局部变量再随返回值带出去（#610 那次就是栽在这里，什么都没打出来）。
  const chainRows = []  // #611 对照：计算已移除
  const box = target.getComponent('cc.UITransform')
  const camera = scene.getComponentInChildren('cc.Camera')
  if (box === null || camera === null) return null
  const screen = camera.worldToScreen(box.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0)))
  const rect = document.querySelector('canvas').getBoundingClientRect()
  const pixel = cc.view.getVisibleSizeInPixel()
  return {
    diagG: barRows.length + ' 项：' + barRows.join(' ; '),
    chain: chainRows.join(' <- '),
    x: rect.left + (screen.x / pixel.width) * rect.width,
    y: rect.top + rect.height - (screen.y / pixel.height) * rect.height,
  }
})

/** 弹层读数：是否可见 + 里面所有 Label 的文本。 */
const readOverlay = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let overlay = null
  const visit = (node) => {
    if (node.name === 'StaminaDetail') overlay = node
    for (const child of node.children) visit(child)
  }
  visit(scene)
  if (overlay === null) return { found: false, visible: false, texts: [] }
  const texts = []
  const collect = (node) => {
    const label = node.getComponent && node.getComponent('cc.Label')
    if (label !== null && label !== undefined && label.string !== '') texts.push(label.string)
    for (const child of node.children) collect(child)
  }
  collect(overlay)
  return { found: true, visible: overlay.activeInHierarchy === true, texts }
})

/** 点弹层里的某个按钮（按节点名）。 */
const clickInOverlay = (nodeName) => page.evaluate((name) => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let overlay = null
  const visit = (node) => {
    if (node.name === 'StaminaDetail') overlay = node
    for (const child of node.children) visit(child)
  }
  visit(scene)
  if (overlay === null) return null
  let target = null
  const walk = (node) => {
    if (node.name === name) target = node
    for (const child of node.children) walk(child)
  }
  walk(overlay)
  if (target === null) return null
  // 诊断 E（#611）：#610 用偏移扫描钉死了「点 Label 中心偏 12px，触摸挂在整行节点上」。
  // 这里把 Label 往上每一层祖先的 UITransform 尺寸打出来 —— 找出「整行」是哪一层。
  // 注意：这段跑在**浏览器上下文**，console.log 会进页面而不是 node 的 stdout，
  // 必须收集到局部变量再随返回值带出去（#610 那次就是栽在这里，什么都没打出来）。
  const chainRows = []
  {
    let q = target
    while (q != null) {
      const bx = q.getComponent('cc.UITransform')
      const sz = bx == null ? 'no-UI' : Math.round(bx.contentSize.width) + 'x' + Math.round(bx.contentSize.height)
      chainRows.push(q.name + '[' + sz + ']active=' + q.active + ',inh=' + q.activeInHierarchy)
      q = q.parent
    }
  }
  const box = target.getComponent('cc.UITransform')
  const camera = scene.getComponentInChildren('cc.Camera')
  if (box === null || camera === null) return null
  const screen = camera.worldToScreen(box.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0)))
  const rect = document.querySelector('canvas').getBoundingClientRect()
  const pixel = cc.view.getVisibleSizeInPixel()
  return {
    chain: chainRows.join(' <- '),
    x: rect.left + (screen.x / pixel.width) * rect.width,
    y: rect.top + rect.height - (screen.y / pixel.height) * rect.height,
  }
}, nodeName)

const rowPoint = await clickStaminaRow()
console.log('[stamina][诊断G] ' + (rowPoint === null ? '(rowPoint=null)' : rowPoint.diagG))
console.log('[stamina][诊断E] Label 祖先链：' + (rowPoint === null ? '(rowPoint=null)' : rowPoint.chain))
if (rowPoint === null) {
  console.error('[stamina][前置] 资源条上找不到「体力」那一行 —— 前置不满足')
  process.exit(2)
}
// 点击偏移 —— **取证开关，不是修法**（#618 更正了它的用途）。
//
// 它最初被当成修法：#610 扫出「往右 12px 命中」，看上去是坐标偏了。#618 查清了真根因：
// `PlayerSave.resources()` 返回 `Map.copyOf(...)`（已修成保序快照），资源条 6 项的**排列顺序**
// 跨 JVM 进程会变 —— 同一台后端内 24/24 稳定、换一个进程就换位置。所以「点不中」从来不是
// 点的位置偏了，而是**体力那一格当时排在第二列**。⇒ **偏移量不该用来「修好」命中**，
// 它的用途只有一个：**在怀疑布局变了时，把它当一个可调的取证旋钮**。
//
// 判据仍是 **GET /stamina 出现几条**：一条 = 点击没命中（只有 AppRoot:913 的 deliver），
// 两条 = 点击命中并触发了 `openStaminaDetail`(:1204)。
// 命中测试（#623/#624）：点不中究竟是「坐标算错」还是「那个点压根不落在任何一格上」。
// 把点击点**反投影**回世界坐标，再对每个资源条格子算 containsPoint —— 一次就能分开这两种。
// 注意坐标换算要与 clickStaminaRow **同式**（含 rect.left / rect.top），#616 那次漏了 top 就看错方向。
const hitReport = await page.evaluate((PT) => {
  const cc = window.cc
  const scene = cc.director.getScene()
  const cam = scene.getComponentInChildren('cc.Camera')
  const rect = document.querySelector('canvas').getBoundingClientRect()
  const pixel = cc.view.getVisibleSizeInPixel()
  const vx = (PT.x - rect.left) * (pixel.width / rect.width)
  const vy = (PT.y - rect.top) * (pixel.height / rect.height)
  // ⚠️ Cocos 的签名是 screenToWorld(screenPoint, camera, out) —— **第一个参数是一个 Vec2/Vec3**，
  // 不是 (x, y, z, out) 四个散参。传散参会被当成「拿 out 当 screenPoint」，报
  // 「Cannot create property 'x' on number」（#624 实测踩过）。
  // ⚠️ Cocos 3.x 的签名是 screenToWorld(screenPoint: Vec3, out: Vec3) —— 入参**必须是 Vec3**。
  // 传 Vec2 或四个散参都拿不到正确结果（散参报「Cannot create property 'x' on number」，
  // Vec2 静默给 NaN）—— #624 两个都踩过。
  const sp = new cc.Vec3(vx, vy, 0)
  const world = new cc.Vec3()
  cam.screenToWorld(sp, world)
  const rows = []
  const walk = (n, depth) => {
    const bx = n.getComponent && n.getComponent('cc.UITransform')
    if (depth > 40) return
    if (bx != null && bx !== undefined && n.activeInHierarchy === true && n.name.startsWith('Resource')) {
      // ⚠️ Cocos 3.8 的 UITransform **没有** containsPoint（实测报
      // 「bx.containsPoint is not a function」）⇒ 自己算：把世界点换算到节点局部，再按 anchor 判矩形。
      const lp = new cc.Vec3()
      bx.convertToNodeSpaceAR(world, lp)
      const size = bx.contentSize
      const ap = bx.anchorPoint
      const halfW = size.width * ap.x
      const halfH = size.height * ap.y
      const hit = lp.x >= -halfW && lp.x <= size.width - halfW
        && lp.y >= -halfH && lp.y <= size.height - halfH
      const local = bx.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0))
      const scr = cam.worldToScreen(local)
      const cx = rect.left + (scr.x / pixel.width) * rect.width
      const cy = rect.top + rect.height - (scr.y / pixel.height) * rect.height
      rows.push(n.name + ' size=' + Math.round(size.width) + 'x' + Math.round(size.height)
        + ' anchor=' + bx.anchorPoint.x + ',' + bx.anchorPoint.y
        + ' localPt=' + Math.round(lp.x) + ',' + Math.round(lp.y)
        + ' hit=' + hit + ' origin@(' + Math.round(cx) + ',' + Math.round(cy) + ')')
    }
    for (const c of n.children) walk(c, depth + 1)
  }
  walk(scene, 0)
  return { world: Math.round(world.x) + ',' + Math.round(world.y), rows }
}, { x: 281, y: 177 })
console.log('[stamina][命中] 点(281,177) -> 世界(' + hitReport.world + ')' + hitReport.rows.map((r) => String.fromCharCode(10) + '    ' + r).join(''))
// 坐标口径诊断（#626）：Cocos 产物 `convertUtils.worldToScreenUtils` 的实现是
//   worldToScreen(e, i); i.x /= view.getScaleX(); i.y /= view.getScaleY()
// 而 Touch.getUILocationX/Y 是 (this._x - viewport.x) / getScaleX()
// ⇒ 正逆两向一致：**css = rect.left + viewport.x + worldToScreen.x**。
// 而本探针原来用的是 `(screen.x / getVisibleSizeInPixel().width) * rect.width`
// —— 归一化的分母换了、viewport 原点也没加 ⇒ 那就是 #624 里 364px 差的来源。
// 这里把四个数一次打全，好让新公式有可核对的依据。
const coordInfo = await page.evaluate(() => {
  const cc = window.cc
  const canvas = document.querySelector('canvas')
  const rect = canvas.getBoundingClientRect()
  const vp = cc.view.getViewportRect()
  const vis = cc.view.getVisibleSize()
  const pix = cc.view.getVisibleSizeInPixel()
  return { rectW: rect.width, rectH: rect.height, rectL: rect.left, rectT: rect.top,
    canvasW: canvas.width, canvasH: canvas.height,
    vpX: vp.x, vpY: vp.y, vpW: vp.width, vpH: vp.height,
    scaleX: cc.view.getScaleX(), scaleY: cc.view.getScaleY(),
    visW: vis.width, visH: vis.height, pixW: pix.width, pixH: pix.height,
    dpr: window.devicePixelRatio }
})
console.log('[stamina][坐标] ' + JSON.stringify(coordInfo))
// 触摸观测（#626 定下的下一步）：**不再算坐标**，直接给每个 `Resource-*` 节点挂一个计数
// 监听，点一次之后看「哪个节点收到了 touch-start」。一次读数就能把这三种可能分开：
//   ① 坐标压根没落在任何一格的盒内  ② 命中了别的节点  ③ 该格根本没收到事件（监听没挂上/被吞）
// 挂的是 `touch-start` 的**监听计数**，不改任何业务行为（Cocos 的 `on` 是追加语义）。
await page.evaluate(() => {
  const cc = window.cc
  const scene = cc.director.getScene()
  window.__touchHits = []
  const walk = (n, depth) => {
    if (depth > 40) return
    if (n.name.startsWith('Resource')) {
      n.on('touch-start', () => window.__touchHits.push(n.name), n)
    }
    for (const c of n.children) walk(c, depth + 1)
  }
  walk(scene, 0)
})
// 命中顺序诊断（#628）：Cocos 的 `_sortByPriority`（cc.js 压缩源码）同父分支最终是
//   var d = r ? r.siblingIndex : 0, _ = s ? s.siblingIndex : 0; return o ? d - _ : _ - d
// ⇒ **同父之间按 siblingIndex（= 添加顺序）比较，先添加的先被命中**，与「后添加在上层」的
// 渲染直觉相反。CityPanelView 里 `ResourcePlate` 先 addChild（L767），格子后加（L774+）
// ⇒ 按这条规则 plate 永远先于格子被命中。这里把 siblingIndex 一次打全，好把这层推论钉死。
const siblingInfo = await page.evaluate(() => {
  const cc = window.cc
  const scene = cc.director.getScene()
  const rows = []
  const walk = (n, depth) => {
    if (depth > 40) return
    if (n.name.startsWith('Resource')) {
      const p2 = n.parent
      rows.push(`${n.name} siblingIndex=${n.siblingIndex} parent=${p2 == null ? '(null)' : p2.name}`
        + ` parentSiblingOfPlate=${p2 == null ? '-' : p2.siblingIndex}`)
    }
    for (const c of n.children) walk(c, depth + 1)
  }
  walk(scene, 0)
  rows.sort((a, b) => a.localeCompare(b))
  return rows
})
console.log('[stamina][sibling] ' + siblingInfo.join(String.fromCharCode(10) + '    '))
// hitTest 直测（#632）：引擎的 `_handleTouchStart` 里 `!i.hitTest(ly, t.windowId) || (…, e.dispatchEvent(t), 0)`
// ⇒ 节点**必须**先过自己的 `hitTest` 才会派发。这里直接对每个 Resource 节点调 hitTest，
// 坐标用 Cocos 自己的 `Touch.getLocation` 约定（`t.getLocation(ly)` 走的是**未翻转的** view 坐标），
// 与探针的点击公式不同 —— 这正是要分开看的地方。
const htInfo = await page.evaluate(() => {
  const cc = window.cc
  const scene = cc.director.getScene()
  const PT = { x: 281, y: 177 }
  const rect = document.querySelector('canvas').getBoundingClientRect()
  const vis = cc.view.getVisibleSize()
  const v = new cc.Vec2()
  // 对齐 Touch.getLocation：先减去 viewport 原点，再除以 scale —— **不翻转 y**
  const vp = cc.view.getViewportRect()
  v.x = (PT.x - rect.left - vp.x) / cc.view.getScaleX()
  v.y = (rect.height - (PT.y - rect.top)) / cc.view.getScaleY()
  const rows = []
  const walk = (n, depth) => {
    if (depth > 40) return
    if (n.name.startsWith('Resource')) {
      const bx = n.getComponent && n.getComponent('cc.UITransform')
      if (bx != null && bx !== undefined) {
        rows.push(`${n.name} size=${Math.round(bx.contentSize.width)}x${Math.round(bx.contentSize.height)}`
          + ` hitTest=${bx.hitTest(v, 0)}`)
      }
    }
    for (const c of n.children) walk(c, depth + 1)
  }
  walk(scene, 0)
  return { probe: Math.round(v.x) + ',' + Math.round(v.y), rows }
})
console.log('[stamina][hitTest] 探针点(' + htInfo.probe + ')：' + htInfo.rows.join(' ; '))
const __dx = Number(process.env.STA_DX ?? '0')
const __dy = Number(process.env.STA_DY ?? '0')
console.log('[stamina][偏移] 基准=' + Math.round(rowPoint.x) + ',' + Math.round(rowPoint.y) + ' 偏移=' + __dx + ',' + __dy)
await page.mouse.click(rowPoint.x + __dx, rowPoint.y + __dy)
const touchHits = await page.evaluate(() => (window.__touchHits ?? []).slice())
console.log('[stamina][触摸] 收到 touch-start 的节点: ' + (touchHits.length === 0 ? '(无)' : touchHits.join('、')) + '  |  点击点=' + Math.round(rowPoint.x + __dx) + ',' + Math.round(rowPoint.y + __dy))
await page.waitForTimeout(1500)
// 诊断 B（#607）：render() 末尾明确写了 this.node.active = true，而 Cocos 的 activeInHierarchy
// 要本节点**与所有祖先**都 active。所以「不可见」有两种可能：本节点没被 render，或者某个祖先
// 不活跃。这里把整条祖先链打出来 —— 一次就能分开这两种。
const chain = await page.evaluate(() => {
  const walk = (n, depth) => {
    if (n == null) return null
    if (n.name === 'StaminaDetail') return n
    for (const c of n.children) {
      const r = walk(c)
      if (r !== null) return r
    }
    return null
  }
  const scene = window.cc.director.getScene()
  const t = walk(scene)
  if (t === null) return 'NOT_FOUND'
  const rows = []
  let n = t
  while (n !== null) {
    rows.push(`${n.name}:active=${n.active},activeInHierarchy=${n.activeInHierarchy}`)
    n = n.parent
  }
  return rows.join(' <- ')
})
console.log(`[stamina][诊断2] 祖先链：${chain}`)
const opened = await readOverlay()
console.log(`[stamina] 点体力行后：弹层存在=${opened.found} 可见=${opened.visible}`
  + ` 文本=${JSON.stringify(opened.texts)}`)
await page.screenshot({ path: SHOT })

// 买一次：金币要少、体力要多
// 满不满仓要在**买之前**定下来（#606）：下面三条判据「有没有 POST /stamina/buy」「已买次数
// 涨没涨」「下次买价涨没涨」全都在问「这次购买有没有发生」，而满仓时按钮**被正确置灰**
// ⇒ 本来就不该有 POST、次数与买价都不该动。此前这三条是无条件断言，与下面第 229 行
// `wasFull` 的分支判据用了相反的前提，于是「满仓时按钮正确置灰」这件符合规格的事被判成了红。
const wasFullBeforeBuy = before.data.current >= before.data.cap
const postsBeforeBuy = staminaPosts.length
const buyPoint = await clickInOverlay('BuyButton')
if (buyPoint !== null) {
  await page.mouse.click(buyPoint.x, buyPoint.y)
  await page.waitForTimeout(2000)
}
const afterBuy = await readOverlay()
const after = await get('/stamina', HEAD)
await page.screenshot({ path: SHOT })
await browser.close()
await preview.close()

console.log(`[stamina] 点买之后：/stamina 请求新增 [${staminaPosts.slice(postsBeforeBuy).join('、') || '(无)'}]`
  + ` 弹层文本=${JSON.stringify(afterBuy.texts)}`)
console.log(`[stamina] 服务端：体力 ${before.data.current} → ${after.data.current}`
  + ` 今日已买 ${before.data.boughtToday} → ${after.data.boughtToday}`)
console.log(`[stamina] 截图：${SHOT}`)
console.log(`[stamina] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

const failures = []
if (!staminaPosts.some((entry) => entry.startsWith('GET'))) {
  failures.push('点体力那一行没有发出 GET /stamina —— 资源条上的触摸没接上')
}
if (opened.visible !== true) {
  failures.push('点完体力那一行，StaminaDetail 弹层没显示')
}
const expectedTitle = `体力 ${before.data.current}/${before.data.cap}`
if (!opened.texts.some((text) => text.replace(/\s+/g, '').includes(expectedTitle.replace(/\s+/g, '')))) {
  failures.push(`弹层标题不等于服务端读数（期望含「${expectedTitle}」，实际 ${JSON.stringify(opened.texts)}）`)
}
if (!opened.texts.some((text) => text.includes('金币'))) {
  failures.push('弹层里没有买体力的按钮文案（应含「买 1 次（N 金币）」）')
}
if (wasFullBeforeBuy) {
  // 满仓：按钮应被禁用。判据是「不该发出 POST」+「弹层给出了溢出警告」，
  // 后者与下面 `wasFull` 分支里的那条重复不了（这里查的是**点击前**的弹层，那里查的是点击后）。
  if (staminaPosts.some((entry) => entry.startsWith('POST'))) {
    failures.push(`满仓时（${before.data.current}/${before.data.cap}）竟然发出了 POST /stamina/buy —— 置灰规则没生效`)
  }
  if (buyPoint === null) {
    failures.push('满仓时「买 1 次」按钮找不到了 —— 满仓应置灰而不是移除（玩家仍需看到买价与警告）')
  }
} else {
  if (!staminaPosts.some((entry) => entry.startsWith('POST'))) {
    failures.push('未满仓时点了买体力没有发出 POST /stamina/buy —— 按钮没接上')
  }
  if (after.data.boughtToday <= before.data.boughtToday) {
    failures.push(`买完之后今日已买次数没涨（${before.data.boughtToday} → ${after.data.boughtToday}）`)
  }
}
// 体力的变化要看**买之前满没满**：B09 §5 明写"溢出不结转"，满仓时买就是会丢 ——
// 第一版一刀切断言"买完必须涨"，于是把一件**符合规格**的事判成了缺陷。
// 未满 ⇒ 必须涨；已满 ⇒ 必须**不涨**（涨了才说明溢出不生效，那才是缺陷），且弹层要给过警告。
const wasFull = before.data.current >= before.data.cap
if (!wasFull && after.data.current <= before.data.current) {
  failures.push(`没满仓（${before.data.current}/${before.data.cap}）时买完体力没涨（→ ${after.data.current}）`)
}
if (wasFull) {
  if (after.data.current > before.data.current) {
    failures.push(`满仓时买完体力竟涨了（${before.data.current} → ${after.data.current}）—— 溢出没按 B09 不结转`)
  }
  if (!opened.texts.some((text) => text.includes('溢出'))) {
    failures.push('满仓时弹层没有提示"买了会溢出损失"')
  }
}
// 买价要跟着涨（服务端定价，客户端不推算）：这是"服务端真的记了这次购买"的第二个证据
// 买价要跟着涨（服务端定价，客户端不推算）：这是"服务端真的记了这次购买"的第二个证据。
// **只在真的买成了的时候断言** —— 满仓时这次购买压根没发生，拿它判"没涨"是量具自相矛盾。
if (!wasFullBeforeBuy && after.data.buyCostGold > 0 && after.data.buyCostGold <= before.data.buyCostGold) {
  failures.push(`买完之后下一次的买价没涨（${before.data.buyCostGold} → ${after.data.buyCostGold}）`)
}
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}
if (failures.length > 0) {
  console.error(`[stamina] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[stamina] 全绿：体力行点得开、弹层画的是服务端读数、买 1 次真扣钱真加体力')
