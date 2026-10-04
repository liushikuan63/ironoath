/**
 * 职责：**多类型建筑叠加**的运行期验收 —— 在一个新号上按真实流程升主城、建 4 类不同建筑，
 * 看"每一类都叠上了自己的正稿、名字/等级都在、未建格子仍然空着"是否成立（`CITY-ART-03` 的可辨那半）。
 * 依赖：`client/build/web-mobile` 产物 + 一台**本轮自己的** dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么需要它：`verify-city-build-many` 只建得起**一栋**（伐木场）—— 新号主城 1 级，其余 12 类
 * 按 `requireMainLevel` 全都点不动。于是"A18 那 15 张正稿里只见过 2 类"一直是审计里的欠账
 * （`内城界面审计` §8.5 第 4 条、§10.7 第 5 条）。本工具用**新号够用的那点资源**把路线走通：
 * 主城 →2（1000 木 1000 石）⇒ 农田 Lv2 门槛 + 仓库 Lv2；主城 →3 ⇒ 兵营 + 铁矿场。
 * 四类 + 主城 = 五栋同屏，正好覆盖"底图空位 + 各自正稿 + 名字与等级"三件事。
 *
 * <p>**15 类全见的验收不在这里**（那要主城 8 级 + 走运维补发发资源），见
 * `tools/verify-city-full-city.mjs`；本工具留在"新号零外部依赖也能跑"的位置上。
 *
 * <p><b>建造走 HTTP、只有截图走浏览器</b>：客户端那个建造选择器按服务端顺序列全部候选项，
 * 选中门槛不够的行会被服务端拒（`3000 主城等级不足`）—— 那是**产品行为**，但不是本工具要验的东西。
 * 本工具要验的是"建好之后画成什么样"，所以建造直接走 `/city/upgrade`，快且可重复。
 *
 * <p>只建不拆、不加速：建造会消耗这台 dev 后端的资源（内存存储，重启即清）。
 *
 * 退出码：0 全绿；1 判据失败（含"一栋都没建起来"）；2 前置不满足。
 *
 * <p>2026-09-22 新增。判据里"每类都必须有正稿"用的是**帧名前缀**（`building-*`），
 * 与 `verify-art-runtime` 同一个口径：退回图集小图标也算没画正稿。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.CITY_TYPES_PORT ?? 8294)
const OUT = 'client/build/art-verify'
const SHOT = path.join(OUT, '13-city-multi-types.png')
const WAIT_CAP_MS = Number(process.env.CITY_TYPES_WAIT_CAP_MS ?? 120_000)

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[multi-types][前置] 产物不存在（先跑 scripts/build-webmobile.sh）`)
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const deviceId = `multi-types-${Date.now()}`
const post = async (url, body, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  return response.json()
}

const init = await post('/player/init', {
  requestId: `multi-init-${Date.now()}`, deviceId, nickName: '多类型探针',
  clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[multi-types][前置] 建号失败（后端在跑吗？）：${JSON.stringify(init)}`)
  process.exit(2)
}
const playerId = init.data.playerId
const HEAD = { 'X-Player-Id': playerId }

/** 发起一次建造/升级，并把"要等多久"读出来（`serverNow` 在**信封**上，不在 data 里）。 */
const upgrade = async (configId, gridX, gridY) => {
  const body = { requestId: `multi-${configId}-${Date.now()}`, configId }
  if (gridX !== undefined) {
    body.gridX = gridX
    body.gridY = gridY
  }
  const resp = await post('/city/upgrade', body, HEAD)
  if (resp.code !== 0) {
    console.error(`[multi-types][前置] ${configId} 没建起来：${JSON.stringify(resp)}`)
    process.exit(2)
  }
  const waitMs = Math.max(0, resp.data.finishAt - resp.serverNow)
  return waitMs
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const MAX_QUEUE = 2 // 新手保护期两个免费队列（city_rule_newbie_free_queue_count=2）

/** 主城升到 level（串行：每次都要等上一次完成）。 */
const raiseMainCity = async (label) => {
  const ms = await upgrade('main_city')
  console.log(`[multi-types] 主城 → ${label}：耗时 ${ms}ms`)
  await sleep(Math.min(ms + 2000, WAIT_CAP_MS))
}

await raiseMainCity('2 级')
console.log('[multi-types] 建 农田 + 仓库（各需主城 2 级，两个队列并行）')
const farmMs = await upgrade('farm', 1, 1)
const warehouseMs = await upgrade('warehouse', 5, 1)
await sleep(Math.min(Math.max(farmMs, warehouseMs) + 2000, WAIT_CAP_MS))

await raiseMainCity('3 级')
console.log('[multi-types] 建 兵营 + 铁矿场（各需主城 3 级）')
const barracksMs = await upgrade('barracks', 1, 5)
const ironMs = await upgrade('iron_mine', 5, 5)
await sleep(Math.min(Math.max(barracksMs, ironMs) + 2000, WAIT_CAP_MS))

const city = await (await fetch(`${BACKEND}/city/list`, { headers: HEAD })).json()
if (city.code !== 0) {
  console.error(`[multi-types][前置] 读城内列表失败：${JSON.stringify(city)}`)
  process.exit(2)
}
const built = city.data.buildings
console.log(`[multi-types] 建完的：${built.map((b) => `${b.name} Lv${b.level}@(${b.gridX},${b.gridY})`).join('、')}`)

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'city')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => {
  if (window.cc === undefined || window.cc.director === undefined) return false
  const scene = window.cc.director.getScene()
  if (scene === null) return false
  const nav = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
  return nav !== null && nav !== undefined && nav.currentKey === 'city'
}, null, { timeout: 25_000 }).catch(() => {})
await page.waitForTimeout(2000)
/**
 * 收掉两样会挡住城景的东西，**都不改产品状态**：
 * ① 引导层（`GuideView`）—— 它压在画面正中央，别的内城探针也都这么摘；
 * ② 「落成贺礼」弹窗 —— 本工具建了 4 栋，必然触发一次付费弹窗（`GiftPayFlow`），
 *    它正好盖住城景中心。这里调它自己的 `hide()`（等价于点右上角 ×），不是伪造隐藏。
 */
await page.evaluate((source) => {
  const re = new RegExp(source)
  const scene = window.cc.director.getScene()
  const visit = (node) => {
    const label = node.getComponent && node.getComponent('cc.Label')
    if (/Guide/i.test(node.name) || (label !== null && label !== undefined && re.test(label.string ?? ''))) {
      const view = node.getComponent('GuideView')
      if (view !== null && view !== undefined) view.enabled = false
      node.removeFromParent()
      return
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
}, /第\s*\d+\s*\/\s*\d+\s*步|我完成了|升级主城：/.source)

const popupHidden = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let node = null
  const visit = (current) => {
    if (node !== null) return
    if (current.name === 'giftPopup') { node = current; return }
    for (const child of current.children) visit(child)
  }
  visit(scene)
  if (node === null || node.activeInHierarchy !== true) {
    return false
  }
  const view = node.getComponent('GiftPopupView')
  if (view === null || view === undefined) {
    return false
  }
  view.hide()
  return true
})
console.log(`[multi-types] 落成贺礼弹窗${popupHidden ? '已收起（它挡住了城景中心）' : '这一帧没出现'}`)
await page.waitForTimeout(400)

const frame = await page.evaluate(() => {
  const cc = window.cc
  const scene = cc.director.getScene()
  const tiles = []
  const visit = (node, shown) => {
    const on = shown && node.activeInHierarchy === true
    if (on && /^Grid-\d+$/.test(node.name)) {
      const icon = node.getChildByName('BuildingIcon')
      const sprite = icon === null ? null : icon.getComponent('cc.Sprite')
      const texts = []
      const collect = (child) => {
        const l = child.getComponent && child.getComponent('cc.Label')
        if (l !== null && l !== undefined && l.string !== '') texts.push(l.string)
        for (const grand of child.children) collect(grand)
      }
      collect(node)
      // 2026-10-04 诊断读数（先有读数再下结论）：这一格点不中，上一版只有"选择栏成了什么"，
      // 不足以分辨「命中区没盖住基座」与「坐标算错」。这里把**几何**也打出来：
      // 格子节点与 BuildingIcon 的世界坐标 / 尺寸 / 锚点 —— 前者决定点哪儿，后者决定画在哪儿。
      const ui = node.getComponent('cc.UITransform')
      const wp = ui === null ? null : ui.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0))
      const iconUi = icon === null ? null : icon.getComponent('cc.UITransform')
      const iwp = iconUi === null ? null : iconUi.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0))
      tiles.push({
        tile: node.name,
        texts,
        iconActive: icon === null ? null : icon.active,
        frameName: sprite === null || sprite.spriteFrame === null ? null : sprite.spriteFrame.name,
        geo: {
          wx: wp === null ? null : Math.round(wp.x), wy: wp === null ? null : Math.round(wp.y),
          w: ui === null ? null : Math.round(ui.width), h: ui === null ? null : Math.round(ui.height),
          ax: ui === null ? null : ui.anchorX, ay: ui === null ? null : ui.anchorY,
          iw: iconUi === null ? null : Math.round(iconUi.width), ih: iconUi === null ? null : Math.round(iconUi.height),
          iax: iconUi === null ? null : iconUi.anchorX, iay: iconUi === null ? null : iconUi.anchorY,
          iwx: iwp === null ? null : Math.round(iwp.x), iwy: iwp === null ? null : Math.round(iwp.y),
        },
      })
    }
    for (const child of node.children) visit(child, on)
  }
  visit(scene, true)
  return { tiles }
})

/**
 * **点击命中矩阵**（`CITY-ART-04`：每栋基座中心必须命中自身）。
 *
 * <p>为什么补在这一次里：多建几栋才能验"相邻/边缘/高塔"这几类 —— 单栋城市里点谁都是它。
 * 命中点取的是格子的**基座中心**（`Grid-N` 的 UITransform 原点，锚点是 (0.5, 0)），
 * 正是判据要求的那一点；读的是选择栏里的 `SelectedTitle`（与 `verify-devtools-panels` 同一条读数路径）。
 *
 * <p>同时带一条**负向对照**：点一个没建的格子，选择栏**不许**报出任何一栋已建建筑的名字 ——
 * 没有它，"点哪儿都能选中"这种假绿看不出来。
 */
const toPage = (nodeName) => page.evaluate((name) => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let target = null
  const visit = (node) => {
    if (node.name === name) target = node
    for (const child of node.children) visit(child)
  }
  visit(scene)
  if (target === null) return null
  const box = target.getComponent('cc.UITransform')
  const camera = scene.getComponentInChildren('cc.Camera')
  if (box === null || camera === null) return null
  const screen = camera.worldToScreen(box.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0)))
  const rect = document.querySelector('canvas').getBoundingClientRect()
  const pixel = cc.view.getVisibleSizeInPixel()
  return {
    x: rect.left + (screen.x / pixel.width) * rect.width,
    y: rect.top + rect.height - (screen.y / pixel.height) * rect.height,
  }
}, nodeName)

const selectedTitle = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let title = null
  const visit = (node) => {
    if (node.name === 'SelectedTitle') {
      const label = node.getComponent('cc.Label')
      if (label !== null && label !== undefined) title = label.string
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
  return title
})

const builtNames = built.map((b) => b.name)
const occupiedTiles = frame.tiles.filter((t) =>
  t.frameName !== null || t.texts.some((x) => builtNames.includes(x)))
const emptyTiles = frame.tiles.filter((t) => !occupiedTiles.includes(t))
// 「点某一格」的唯一入口：先把镜头摆向那一格，再**重算**它的页面坐标，确认**在视口内**才点。
// ⚠️ 三条顺序上的硬要求（都是 2026-10-04 实测踩出来的）：
// ① **先移镜头再算坐标** —— 面板可缩放可平移（zoom 默认 1.8 / MIN 1 / MAX 2.4，滚轮 :552 /
//    捏合 :568 / 单指拖动 setFocus :583 / stage.setScale :646），镜头一动**所有**格子的页面坐标全变。
// ② **`setFocus` 会被夹** —— 夹取上限 `content*(zoom-1)/2`（:647-648）是"底图必须铺满视口"的硬约束
//    （`verify-city-zoom-runtime.mjs:170-176` 记着这个形状：本地 y≈219 ⇒ 期望 −394 被夹到 −240）。
//    ⇒ **"移了镜头就看得见"不成立**：夹完仍可能整格在视口外。
// ③ **落在视口外就不点** —— Playwright 会把视口外的坐标**夹进视口**，点空或落到主城头上都是这么来的
//    （那正是本轮查出来的「Grid-31/35 选中主城」）。宁可这一格报"没量到"，也不要量一个夹出来的假读数。
//    夹取后仍在视口外的，先缩到 CITY_ZOOM_MIN=1 再试一次（可平移范围最大），仍不行就老实记下。
const canvasRect = async () => page.evaluate(() => {
  const r = document.querySelector('canvas').getBoundingClientRect()
  return { left: r.left, top: r.top, width: r.width, height: r.height }
})
const inViewport = (point, rect) => point.x >= rect.left && point.x <= rect.left + rect.width
  && point.y >= rect.top && point.y <= rect.top + rect.height

const focusTile = async (name) => page.evaluate((n) => {
  let view = null
  let target = null
  // 2026-10-04：上一格读数自相矛盾（want[-355,0] 的 got 却是 [0,0]；
  // 前一格刚设成 [213,-126]，下一格读到的 before 却是 [0,0]）⇒ 先把"到底几个实例"数清楚。
  // 多个实例时 `getComponent` 只返回**第一个** ⇒ focusTile 很可能在给另一个实例设焦点。
  const all = []
  const insts = []
  const visit = (node) => {
    const v = node.getComponent('CityPanelView')
    if (v !== null && v !== undefined) {
      insts.push(v)
      all.push({ node: node.name, fx: v.focusX, fy: v.focusY, zoom: v.zoom })
    }
    if (target === null && node.name === n) target = node
    for (const c of node.children) visit(c)
  }
  visit(window.cc.director.getScene())
  // ⚠️ `insts` 才是组件本体；`all` 只是它的**快照**。拿快照调 setFocus 会报 not a function
  //（2026-10-04 实测踩到：`view = all[0]` ⇒ `view.setFocus is not a function`，探针直接崩）
  view = insts.length > 0 ? insts[0] : null
  const live = all.map((a) => ({ node: a.node, focus: [Math.round(a.fx * 100) / 100, Math.round(a.fy * 100) / 100], zoom: a.zoom }))
  globalThis.__panelViews = live
  if (view === null || target === null) return { ok: false, why: view === null ? 'no-view' : 'no-tile', views: live }
  const p = target.position
  const before = [view.focusX, view.focusY]
  view.setFocus(p.x, p.y)
  return {
    ok: true,
    viewCount: all.length,
    views: live,
    want: [Math.round(p.x), Math.round(p.y)],
    got: [Math.round(view.focusX), Math.round(view.focusY)],
    clamped: Math.abs(view.focusX - p.x) > 1 || Math.abs(view.focusY - p.y) > 1,
    before: [Math.round(before[0]), Math.round(before[1])],
  }
}, name)

const zoomToMin = async () => page.evaluate(() => {
  let view = null
  const visit = (n) => { if (view === null) view = n.getComponent('CityPanelView') ?? null; for (const c of n.children) visit(c) }
  visit(window.cc.director.getScene())
  if (view === null) return false
  view.zoomTo(1) // = CITY_ZOOM_MIN
  return true
})

/**
 * 2026-10-04：**那个坐标上站着谁**（`Grid-35` 打空的最后一读）。
 *
 * <p>把落点反算回世界坐标，遍历所有 active + UITransform 节点，列出**世界矩形包含该点**的，
 * 按面积从大到小排：
 * - 只有 `Grid-35` 自己 ⇒ 命中区没接上（**产品缺陷**：`CityPanelView` 给该格算错了命中区）
 * - 还有更大的节点（面板边框/遮罩/别的按钮）⇒ 被它盖住（**产品缺陷**）
 *
 * <p>⚠️ 用 `convertToWorldSpaceAR` 换算角点，所以对任意锚点都对。
 */
const whoIsAt = async (tileName) => {
  const focusRes = await focusTile(tileName)
  if (focusRes.ok !== true) return { error: focusRes.why }
  await page.waitForTimeout(400)
  return page.evaluate((n) => {
    const cc = window.cc
    const scene = cc.director.getScene()
    const target = scene.getChildByName('Canvas')?.getChildByName('Game')
    void target
    let node = null
    const find = (x) => { if (node === null && x.name === n) node = x; for (const c of x.children) find(c) }
    find(scene)
    if (node === null) return { error: 'no-tile' }
    const ui = node.getComponent('cc.UITransform')
    const p = ui.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0))
    const hits = []
    const walk = (x) => {
      if (x.activeInHierarchy === true) {
        const t = x.getComponent('cc.UITransform')
        if (t !== null && t !== undefined) {
          // 用中心 + 半宽高近似该节点的世界矩形（锚点已由 convertToWorldSpaceAR 吸收）
          const c = t.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0))
          const hw = t.width / 2
          const hh = t.height / 2
          if (Math.abs(c.x - p.x) <= hw && Math.abs(c.y - p.y) <= hh) {
            hits.push({ name: x.name, area: Math.round(t.width * t.height), w: Math.round(t.width), h: Math.round(t.height) })
          }
        }
      }
      for (const k of x.children) walk(k)
    }
    walk(scene)
    // ⚠️ 升序：全屏容器（Game/Canvas/Background）必然覆盖任何点，**没有区分力**；
    // 有区分力的是**面积最小**的那个（最具体的那个）。
    hits.sort((a, b) => a.area - b.area)
    return { tile: n, world: [Math.round(p.x), Math.round(p.y)], hitCount: hits.length, hits: hits.slice(0, 6) }
  }, tileName)
}

const clickTile = async (name) => {
  const focused = await focusTile(name)
  if (focused.ok !== true) return { ok: false, why: focused.why }
  await page.waitForTimeout(400)
  const rect = await canvasRect()
  let point = await toPage(name)
  // 2026-10-04：`Grid-35` 打空（挪到点击第一位后读数是「点击建筑查看详情」⇒ 真的没打中，
  // 不是"停在上一次"）。新的头号嫌疑：**镜头在平滑移动**，而落点是在移动途中算的，
  // 到真正点击时又偏了。⇒ 量两次落点：间隔 600ms 还不同 ⇒ 镜头没停稳。
  let drift = null
  if (point !== null) {
    await page.waitForTimeout(600)
    const again = await toPage(name)
    if (again !== null) drift = Math.round(Math.hypot(point.x - again.x, point.y - again.y))
  }
  let zoomedOut = false
  if (point !== null && !inViewport(point, rect)) {
    // 夹取把这一格留在视口外 ⇒ 缩到最小再试一次（MIN=1 时可平移范围最大）
    zoomedOut = await zoomToMin()
    if (zoomedOut) {
      await page.waitForTimeout(400)
      point = await toPage(name)
    }
  }
  if (point === null) return { ok: false, why: 'no-point', focus: focused, zoomedOut }
  if (!inViewport(point, rect)) {
    return { ok: false, why: 'still-outside', point, focus: focused, zoomedOut }
  }
  await page.mouse.click(point.x, point.y)
  await page.waitForTimeout(600)
  return { ok: true, point, focus: focused, zoomedOut, drift }
}

// 2026-10-04：量「点过之后各格的实际落点」两两间距。
// Grid-35 期望铁矿场却选中兵营 ⇒ 要分清「两个格子的落点真的重叠（=产品缺陷：命中区重叠）」
// 与「落点分得开、只是点歪了（=量具还要再挪镜头）」。这两者只有量间距才分得开。
const hitPoints = []
const hitChecks = []
// 2026-10-04：`Grid-35` 一直是**最后一个**被点的，而它恰好是唯一不命中的那一格。
// 这既可能是「它真的点不到」，也可能只是「这一下没生效、选择栏停在上一次（兵营）」——
// **这两种只有把点击次序换掉才分得开**。默认把 Grid-35 提到**第一位**，
// `CITY_LAST_FIRST=0` 可切回原次序做对照。
const FIRST_TILE = process.env.CITY_LAST_FIRST === '0' ? null : 'Grid-35'
const ordered = FIRST_TILE === null
  ? occupiedTiles
  : [...occupiedTiles.filter((t) => t.tile === FIRST_TILE),
    ...occupiedTiles.filter((t) => t.tile !== FIRST_TILE)]
console.log(`[multi-types] 点击次序：${ordered.map((t) => t.tile).join(' → ')}`)
for (const tile of ordered) {
  const clicked = await clickTile(tile.tile)
  // 把「算出来的页面坐标」与「格子的几何」并排打出来：
  // 坐标出界就是"镜头没摆过去"，几何与坐标的差就是"命中区没盖住基座"——两者都打出来才分得开。
  console.log(`   [geo] ${tile.tile} 移镜头=${JSON.stringify(clicked.focus)}`
    + ` 节点世界=(${tile.geo?.wx},${tile.geo?.wy})`
    + ` 尺寸=${tile.geo?.w}×${tile.geo?.h} 锚点=(${tile.geo?.ax},${tile.geo?.ay})`
    + ` | 图标世界=(${tile.geo?.iwx},${tile.geo?.iwy}) 尺寸=${tile.geo?.iw}×${tile.geo?.ih}`
    + ` | 点下去时的页面坐标=${clicked.ok === true ? `(${Math.round(clicked.point.x)},${Math.round(clicked.point.y)})` : clicked.why}`
    + ` 实例数=${clicked.viewCount ?? "-"}` + ` 落点600ms漂移=${clicked.drift ?? '-'}px 图标active=${tile.iconActive} 帧名=${tile.frameName}`)
  if (clicked.ok !== true) {
    hitChecks.push({ tile: tile.tile, expected: null, title: null, ok: false })
    continue
  }
  if (clicked.ok === true) hitPoints.push({ tile: tile.tile, x: Math.round(clicked.point.x), y: Math.round(clicked.point.y), drift: clicked.drift })
  const title = await selectedTitle()
  const expectedName = tile.texts.find((x) => builtNames.includes(x)) ?? ''
  hitChecks.push({ tile: tile.tile, expected: expectedName, title, ok: title !== null && title.includes(expectedName) })
}
/**
 * 负向对照要能**分辨"点空了"与"串到邻格"**：两种情况下选择栏都不是空 —— 前者的标题**不变**
 * （空格没有交互，上一次的选中留着），后者会**变成另一栋**。
 *
 * <p>第一版写成"点空格后标题不许含任何已建建筑名"，结果因为上一次刚好选中的就是铁矿场而误报
 * （标题没变也算含建筑名）。现在：先明确选中主城当参照，再点**离所有建筑最远**的那个空格，
 * 判据是"标题要么不变、要么变成非建筑文案；**一旦变成别的建筑就是真串格**"。
 */
let emptyControl = null
if (emptyTiles.length > 0) {
  // ⚠️ 2026-10-04：这一相原先把**所有格子**的页面坐标一次算完就缓存，然后照着缓存去点 ——
  // 两个毛病：① 空格多半**不在当前视口里**（面板可平移缩放），不先移镜头就会被 Playwright 夹坐标，
  // 点到哪一栋全看夹完落在哪；② 一旦中途移了镜头，缓存的坐标全部作废。
  // 现在两处点击都走 `clickTile`（先移镜头 → 重算坐标 → 再点）。
  // 「离所有建筑最远」仍按页面坐标算距离 —— 那只是**挑谁**用的启发式，不参与判据。
  const positions = {}
  for (const tile of frame.tiles) {
    positions[tile.tile] = await toPage(tile.tile)
  }
  const occupiedNames = occupiedTiles.map((t) => t.tile)
  const far = emptyTiles
    .map((tile) => {
      const point = positions[tile.tile]
      if (point === null || point === undefined) return { name: tile.tile, distance: -1 }
      const distance = Math.min(...occupiedNames.map((name) => {
        const other = positions[name]
        return other === null || other === undefined ? 0 : Math.hypot(point.x - other.x, point.y - other.y)
      }))
      return { name: tile.tile, distance }
    })
    .sort((a, b) => b.distance - a.distance)[0]
  // 参照：先点主城那一格，让选择栏停在已知状态（同样先移镜头）
  const reference = occupiedTiles.find((t) => t.texts.some((x) => x.includes('主城')))
  if (reference !== undefined) {
    await clickTile(reference.tile)
  }
  const before = await selectedTitle()
  const farClick = await clickTile(far.name)
  console.log(`   [geo] 空格 ${far.name} 距已建格 ${Math.round(far.distance)}px`
    + ` 移镜头=${JSON.stringify(farClick.focus)}`
    + ` 点下去时的页面坐标=${farClick.ok === true ? `(${Math.round(farClick.point.x)},${Math.round(farClick.point.y)})` : farClick.why}`)
  if (farClick.ok === true) {
    emptyControl = { tile: far.name, distance: Math.round(far.distance), before, title: await selectedTitle() }
  }
}
// 2026-10-04：漂移值**单独打一行** —— 上一格混在 [geo] 长行里被截断，一直没读到。
for (const hp of hitPoints) console.log(`   [drift] ${hp.tile} 落点(${hp.x},${hp.y}) 600ms 漂移=${hp.drift}px`)
// 2026-10-04：Grid-35 打空的最后一读 —— 那个坐标上站着谁
const hud = await page.evaluate(() => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let n = null
  const find = (x) => { if (n === null && x.name === 'ZoomOutButton') n = x; for (const c of x.children) find(c) }
  find(scene)
  if (n === null) return { found: false }
  const chain = []
  let cur = n
  while (cur !== null && cur !== undefined) {
    const ui = cur.getComponent('cc.UITransform')
    chain.push({ name: cur.name, active: cur.activeInHierarchy === true,
      pos: [Math.round(cur.position.x), Math.round(cur.position.y)],
      size: ui === null ? null : [Math.round(ui.width), Math.round(ui.height)],
      anchor: ui === null ? null : [ui.anchorX, ui.anchorY] })
    cur = cur.parent
  }
  return { found: true, chain }
})
globalThis.__hudChain = hud
const who = await whoIsAt('Grid-35')
console.log('[hud] ZoomOutButton 父链=' + JSON.stringify(globalThis.__hudChain))
console.log('[who] Grid-35 世界坐标=' + JSON.stringify(who.world) + ' 覆盖该点的节点数=' + (who.hitCount ?? '-') + ' => ' + JSON.stringify(who.hits ?? who.error))
console.log('[multi-types] 点击命中：')
// 2026-10-04：落点两两间距。重叠 ⇒ 命中区真的叠在一起（产品缺陷）；分得开 ⇒ 量具还要再挪镜头。
for (const a of hitPoints) {
  const near = hitPoints.filter((b) => b !== a)
    .map((b) => ({ tile: b.tile, d: Math.round(Math.hypot(a.x - b.x, a.y - b.y)) }))
    .sort((x, y) => x.d - y.d)
  console.log(`   [geo] ${a.tile} 落点(${a.x},${a.y}) 最近邻=${near[0]?.tile ?? '-'} 距离=${near[0]?.d ?? '-'}px`)
}
for (const check of hitChecks) {
  console.log(`   ${check.tile} 期望「${check.expected}」实际「${check.title}」${check.ok ? '' : '  ← 未命中'}`)
}
console.log(`[multi-types] 负向对照（最远的空格 ${emptyControl?.tile ?? '(没找到)'}，距最近建筑 ${emptyControl?.distance ?? '?'}px）：`
  + `点击前「${emptyControl?.before ?? '(没读到)'}」→ 点击后「${emptyControl?.title ?? '(没读到)'}」`)
await page.screenshot({ path: SHOT })
await browser.close()
await preview.close()

const withArt = frame.tiles.filter((t) => t.iconActive === true && t.frameName !== null)
console.log('[multi-types] 有正稿的格子：')
for (const tile of withArt) {
  console.log(`   ${tile.tile}  ${tile.texts.join(' ')}  帧名=${tile.frameName}`)
}
console.log(`[multi-types] 截图：${SHOT}`)
console.log(`[multi-types] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

const failures = []
const expected = built.filter((b) => b.configId !== 'main_city')
if (expected.length < 4) {
  failures.push(`只建起 ${expected.length} 栋非主城建筑（要 4 栋才覆盖 4 类）—— 前置流程没走通`)
}
const byName = new Map(withArt.map((t) => [t.texts.find((x) => !/^Lv\d+$/.test(x)) ?? t.tile, t]))
for (const building of expected) {
  const tile = byName.get(building.name)
  if (tile === undefined) {
    failures.push(`${building.name} 在画面上没有正稿（应有 building-* 帧名的可见图标）`)
  } else if (!/^building-/.test(tile.frameName ?? '')) {
    failures.push(`${building.name} 画的是 ${tile.frameName}，不是 building-* 正稿（退回图集小图标了）`)
  }
}
const mainCityArt = withArt.find((t) => t.texts.some((x) => x.includes('主城')))
if (mainCityArt !== undefined) {
  failures.push(`主城不该叠正稿（底图已有城堡）：${mainCityArt.frameName}`)
}
const artWithoutBuilding = withArt.filter((t) => !expected.some((b) => t.texts.some((x) => x === b.name)))
if (artWithoutBuilding.length > 0) {
  failures.push(`有正稿出现在没建的格子上：${artWithoutBuilding.map((t) => t.tile).join('、')}`)
}
// CITY-ART-04：每栋基座中心命中自身 + 空格不误命中
if (hitChecks.length === 0) {
  failures.push('点击命中矩阵一条都没跑（没找到有建筑的格子）—— 判据走不到，不许当绿')
}
for (const check of hitChecks.filter((c) => !c.ok)) {
  failures.push(`${check.tile} 基座中心点击后选择栏是「${check.title}」，不是「${check.expected}」`)
}
// 负向对照：点最远的空格。**标题不变 = 点空了**（空格没有交互）；变成另一栋 = 命中区串格（真缺陷）
if (emptyControl !== null && emptyControl.title !== null
    && emptyControl.title !== emptyControl.before
    && builtNames.some((name) => emptyControl.title.includes(name))) {
  failures.push(`点空格子（${emptyControl.tile}，距最近建筑 ${emptyControl.distance}px）后选择栏从`
    + `「${emptyControl.before}」变成「${emptyControl.title}」—— 命中区串到邻格了`)
}
if (emptyControl !== null && emptyControl.title === null) {
  failures.push('负向对照没读到选择栏标题 —— 判据走不到，不许当绿')
}
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}
if (failures.length > 0) {
  console.error(`[multi-types] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log(`[multi-types] 全绿：${expected.length} 类建筑各自叠着正稿、名字与等级都在，未建格子仍空着`)