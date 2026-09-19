/**
 * 世界地图的**标签读数**：拍一张地图，并核对格子上印的是中文资源名而不是枚举原文。
 *
 * <p>为什么单独一个工具，而不是复用 `verify-art-runtime.mjs`：那条量具是"一趟走完八个面板"的串行流程，
 * 中间任何一格崩了它整体就退 1 —— 本轮 `ArmyPanelView` 的 `unitId` 崩溃（并行会话的线，已裁决不接）
 * 就把地图那一屏的验收一起拖住。验收自己的改动不该等别人的红，所以这里只走地图这一格。
 *
 * <p>判据（都能失败）：
 * ① 屏幕上**一条裸资源枚举都不能有**（`WOOD/STONE/IRON/GRAIN/GOLD/STAMINA`）——
 *    那正是 #268 修的东西，修前的截图上全是它；
 * ② 至少要读到**一个中文资源名**（期望值从 `contract/config/resource.json` 现读，不抄第二份）——
 *    没有这条正向断言，①会把"地图压根没渲染"也判成绿（同一族教训见 #265/#268）；
 * ③ 一个非 ASCII 文本都没有 ⇒ 面板没渲染，退 2 不算绿。
 *
 * 退出码：0 绿；1 判据失败；2 前置不满足（产物 / 后端 / 场景结构 / 页面报错）。
 */
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[world][前置] 产物不存在：${ROOT}（先跑 Cocos 构建）`)
  process.exit(2)
}

/** 客户端映射的对照真源：配置表里那六个中文名。 */
const RESOURCE_ROWS = (() => {
  const doc = JSON.parse(readFileSync('contract/config/resource.json', 'utf8'))
  return Array.isArray(doc) ? doc : doc.rows
})()
const CN_NAMES = RESOURCE_ROWS.map((row) => row.name)
const ENUM_NAMES = RESOURCE_ROWS.map((row) => row.id)

const OUT = process.env.WORLD_SHOT_OUT
  ?? path.resolve(process.cwd(), 'client/build/art-verify/world-label-check.png')
/** 放大档那张：默认档不画资源标签，正向文案判据与标签压叠都要看这一张。 */
const OUT_ZOOM = process.env.WORLD_SHOT_ZOOM_OUT
  ?? path.resolve(process.cwd(), 'client/build/art-verify/world-label-check-zoom1.png')
const PORT = Number(process.env.WORLD_SHOT_PORT ?? 8197)
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })

/**
 * 账号必须是**固定的夹具号**，不能每跑一个新号。
 *
 * <p>每跑一个新号 ⇒ 家坐标随机 ⇒ 落在地图边角的那些号，`computeViewportKeys` 会按世界边界
 * 把 3×3 裁成 2×3（实测连测三趟有两趟只到 6 块）。那不是缺陷，是**视野本来就被裁了**，
 * 而覆盖率判据要的是"完整窗口"。固定号让家坐标不变，读数才可复现。
 */
const DEVICE = process.env.WORLD_LABEL_DEVICE ?? 'world-label-fixture-1'
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, DEVICE)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'world')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)

/**
 * 等地图上有实体挂出文字。冷后端首连要注册 + 拉快照，固定 sleep 会把"还没画完"当成"画错了"。
 */
const sawCaption = await page.waitForFunction(() => {
  const scene = window.cc.director.getScene()
  let count = 0
  const visit = (n) => {
    const label = n.getComponent('cc.Label')
    if (n.active !== false && label !== null && label.enabled !== false && label.string !== '') count += 1
    for (const c of n.children) visit(c)
  }
  visit(scene)
  return count
}, { timeout: 25000, polling: 500 }).then((h) => h.jsonValue()).catch(() => 0)

/**
 * 等地图**画满该画的那些块**再拍，而不是"等它不动了"。
 *
 * <p>三次改错的经过（都记进台账，别再改回前两种）：
 * ① 等"有文字"—— 拍到的是分块还在流式下发的中间态，屏幕只有左上角一小块地形、其余全黑；
 * ② 数 `name === 'Art'` 的后代节点数判稳 —— 那个数把地形与实体混在一起，能在块没发全时
 * 连着三次不变（实测判稳在 55，那时只有 6 块），于是把中间态量成"短板 67% 铺不满"的假红；
 * ③ 数 `drawnTiles.size` 判稳 —— 对象对了，**"不动了"这个判据本身还是错的**：服务端分两批
 * 下发（先 6 块、再补 3 块），中间有超过一个采样窗口的停顿，2026-09-19 23:1x 实测又量到一次 6 块。
 *
 * <p>正确口径是等**期望值**：期望块数 = `CHUNKS_PER_SIDE_ON_SCREEN²`，那个常数从
 * `game/world/WorldZoom.ts` 现读（与视图同一真源，工具里不抄第二份）。
 * 到不了期望值就退 2 说清"只到 k 块"—— 那是前置没满足（家落在地图边角时视野本来就会被裁），
 * 不是判据失败，不许拿它当"地图铺不满"报缺陷。
 */
const EXPECTED_TILES = (() => {
  const src = readFileSync(
    path.resolve(process.cwd(), 'client/assets/scripts/game/world/WorldZoom.ts'), 'utf8')
  const hit = /CHUNKS_PER_SIDE_ON_SCREEN\s*=\s*(\d+)/.exec(src)
  const side = Number(hit?.[1] ?? 0)
  if (side < 1) {
    console.error('[world][前置] 从 WorldZoom.ts 里读不到 CHUNKS_PER_SIDE_ON_SCREEN —— 判据的期望值没有来源了')
    return 0
  }
  return side * side
})()

/** 板高也从真源现读（`WorldLabels.ts`），工具里不抄第二份数字。 */
const PLATE_HEIGHT = (() => {
  const src = readFileSync(
    path.resolve(process.cwd(), 'client/assets/scripts/game/world/WorldLabels.ts'), 'utf8')
  const hit = /CAPTION_PLATE_HEIGHT\s*=\s*(\d+)/.exec(src)
  return Number(hit?.[1] ?? 0)
})()

async function countTiles() {
  return page.evaluate(() => {
    let map = null
    const visit = (node) => {
      if (map !== null) return
      const comp = node.getComponent('WorldMap')
      if (comp !== null) { map = comp; return }
      for (const c of node.children) visit(c)
    }
    visit(window.cc.director.getScene())
    return map === null ? -1 : map.drawnTiles.size
  })
}
let last = -1
for (let attempt = 0; attempt < 40; attempt += 1) {
  await page.waitForTimeout(500)
  last = await countTiles()
  if (last >= EXPECTED_TILES) break
}
console.log(`[world] 地形块数 ${last} / 期望 ${EXPECTED_TILES}（期望值取自 WorldZoom 的每边块数）`)
if (EXPECTED_TILES === 0) {
  await browser.close()
  await preview.close()
  process.exit(2)
}
if (last < EXPECTED_TILES) {
  console.error(`[world][前置] 只画到 ${last} 块（期望 ${EXPECTED_TILES}）—— 分块没发全或家落在地图边角被裁，`
    + '覆盖率判据走不到，不要把这条读成"地图铺不满"')
  await browser.close()
  await preview.close()
  process.exit(2)
}
if (last <= 0) {
  console.error('[world][前置] 一个地形块都没有 —— 地图没画出来，判据走不到')
  await browser.close()
  await preview.close()
  process.exit(2)
}
await page.screenshot({ path: OUT })
console.log(`[world] 截图：${OUT}`)

/**
 * 视口覆盖率：把**地形块**在 UI 空间里的外接盒与可见尺寸比。
 *
 * <p>为什么量这个而不是量像素：WebGL 画布默认不保留绘制缓冲，`toDataURL` 拿到的可能是黑的，
 * 那种"量不出来"会被读成"覆盖率为 0"。外接盒是从**真正会画出来的节点**上读的，
 * 与 #269 那条"地图只占中间约 350×350"是同一件事，但它可以失败。
 *
 * <p>块节点从 `WorldMap.drawnTiles` 直接拿，**不再按节点名找**：这一格在同一个判据上错过三次，
 * 三次都是"用名字猜对象"——① 按 `name === 'Art'` 找，命中的是地形块的**子节点**和实体块
 * （两者同名都叫 `Art`），于是把 8 个 `100×100` 的默认盒子当成了"8 块没画出来的地图"；
 * ② 拿各节点自己的 UITransform 并外接盒，块节点没设尺寸时并出来的是 100 而不是 320；
 * ③ 用面积比，把"740×740 摆在 960×600 上"判成 102% 通过，而它左右其实有黑边。
 * 现在：对象由渲染器自己给（唯一真源），外接盒按**块中心跨度 + 一块的真实边长**算，按轴取短板。
 */
const coverage = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const visible = window.cc.view.getVisibleSize()
  let map = null
  const findMap = (n) => {
    if (map !== null) return
    const comp = n.getComponent('WorldMap')
    if (comp !== null) { map = comp; return }
    for (const c of n.children) findMap(c)
  }
  findMap(scene)
  if (map === null) return { missing: 'WorldMap' }
  const tiles = Array.from(map.drawnTiles.values())
  const sizes = {}
  let minCx = Infinity; let minCy = Infinity; let maxCx = -Infinity; let maxCy = -Infinity
  let chunkSide = 0
  for (const node of tiles) {
    const box = node.getComponent('cc.UITransform')
    if (box === null) continue
    const w = node.getWorldPosition(new window.cc.Vec3())
    minCx = Math.min(minCx, w.x); maxCx = Math.max(maxCx, w.x)
    minCy = Math.min(minCy, w.y); maxCy = Math.max(maxCy, w.y)
    const key = `${Math.round(box.width)}x${Math.round(box.height)}`
    sizes[key] = (sizes[key] ?? 0) + 1
    chunkSide = Math.max(chunkSide, box.width)
  }
  if (tiles.length === 0) return { tiles: 0, keys: [], widthRatio: 0, heightRatio: 0, sizes: {} }
  const boxW = (maxCx - minCx) + chunkSide
  const boxH = (maxCy - minCy) + chunkSide
  return {
    tiles: tiles.length,
    keys: Array.from(map.drawnTiles.keys()).sort(),
    chunkSide,
    boxW, boxH,
    viewW: visible.width, viewH: visible.height,
    widthRatio: boxW / visible.width,
    heightRatio: boxH / visible.height,
    sizes,
  }
})
if (coverage.missing !== undefined) {
  console.error(`[world] 判据失败：场景里找不到 ${coverage.missing} 组件 —— 量具与渲染器的接线断了，不是地图有问题`)
  await browser.close()
  await preview.close()
  process.exit(2)
}
const cover = Math.min(coverage.widthRatio, coverage.heightRatio)
console.log(`[world] 地形 ${coverage.tiles} 块，块中心跨度 + 一块边长 = 外接盒 ${Math.round(coverage.boxW ?? 0)}×${Math.round(coverage.boxH ?? 0)}`
  + ` vs 视口 ${Math.round(coverage.viewW ?? 0)}×${Math.round(coverage.viewH ?? 0)}`
  + ` ⇒ 宽 ${(coverage.widthRatio * 100).toFixed(0)}% / 高 ${(coverage.heightRatio * 100).toFixed(0)}%`
  + ` / 取短板 ${(cover * 100).toFixed(0)}%`)
console.log(`[world] 块节点 UITransform 按尺寸分堆：${JSON.stringify(coverage.sizes)}`)
// 归因读数：`drawnTiles` 的键就是 `cx:cy`，直接打出来才知道"少了一列"还是"少了一整行"。
console.log(`[world] 视野内的块键：${coverage.keys.join(' ')}`)
/**
 * 块节点的盒子必须跟上手绘地形的边长。原来 `renderTiles` 只 `setPosition` 不设尺寸，
 * 于是地形看得见 320×320、`UITransform` 却还是池默认的 100×100 ——
 * 触摸命中按节点盒子算的话，"点地块边缘点不中"就是可能的形状。
 * 这一条把"默认盒子"钉成缺陷：分堆里不该再出现 `100x100`。
 *
 * <p>对象取自 `drawnTiles`，所以这一条不再是"从一堆同名节点里挑出不是 100 的那些"那种
 * 自己选自己、恒真的写法。
 */
if (coverage.sizes['100x100'] !== undefined) {
  console.error(`[world] 判据失败：还有 ${coverage.sizes['100x100']} 个块节点停在池默认 100×100`
    + ' —— 手绘地形与节点盒子不一致，命中范围与可见范围就会分家')
  await browser.close()
  await preview.close()
  process.exit(1)
}
if (cover < 0.98) {
  console.error(`[world] 判据失败：短板方向只铺到 ${(cover * 100).toFixed(0)}%`
    + ' —— 屏幕会露出一圈纯黑，读起来像"地图到此为止"')
  await browser.close()
  await preview.close()
  process.exit(1)
}

/**
 * 用场景组件**本来就公开的入口**放大，不靠触摸。
 *
 * <p>#269/#272 里这道门永远走不到正向分支，根因是"headless 送不进触摸 + 默认档不画资源标签"
 * （`renderEntities` 里 `zoom > 0 ? entityCaption(entity) : ''`）。但 `WorldMap.zoomIn()` 是
 * 给平台适配层准备的公开方法（HUD 那颗「放大」按钮调的就是它），量具调它**不是开后门**，
 * 也不给 shipped 客户端新增任何能从外部拿到游戏态的面。
 *
 * <p>判据要能失败：调完必须看到"缩放 N"的读数真的变了，否则这条只是"我调了一个不存在的方法"。
 */
const zoomBefore = await page.evaluate(() => {
  let hit = null
  const visit = (n) => {
    const label = n.getComponent('cc.Label')
    if (label !== null && /缩放 \d/.test(label.string)) hit = label.string
    for (const c of n.children) visit(c)
  }
  visit(window.cc.director.getScene())
  return hit ?? '(读不到缩放读数)'
})
const zoomed = await page.evaluate(() => {
  let map = null
  const visit = (node) => {
    if (map !== null) return
    const comp = node.getComponent('WorldMap')
    if (comp !== null) { map = comp; return }
    for (const c of node.children) visit(c)
  }
  visit(window.cc.director.getScene())
  if (map === null || typeof map.zoomIn !== 'function') return false
  map.zoomIn()
  return true
})
if (!zoomed) {
  console.error('[world][前置] 场景里没有可调 zoomIn() 的 WorldMap 组件 —— 量具与视图的接线断了')
  await browser.close()
  await preview.close()
  process.exit(2)
}
// 放大之后要等块与标签重新落定，否则读到的是半屏中间态（#272 那条假红的同一个形状）。
for (let attempt = 0; attempt < 20; attempt += 1) {
  await page.waitForTimeout(500)
  if ((await countTiles()) >= EXPECTED_TILES) break
}
const zoomAfter = await page.evaluate(() => {
  let hit = null
  const visit = (n) => {
    const label = n.getComponent('cc.Label')
    if (label !== null && /缩放 \d/.test(label.string)) hit = label.string
    for (const c of n.children) visit(c)
  }
  visit(window.cc.director.getScene())
  return hit ?? '(读不到缩放读数)'
})
console.log(`[world] 调 zoomIn()：「${zoomBefore}」→「${zoomAfter}」`)
if (zoomAfter === zoomBefore) {
  console.error('[world] 判据失败：调了 zoomIn() 而缩放读数没变 —— 放大这条路径坏了（或读数不是那个 Label）')
  await browser.close()
  await preview.close()
  process.exit(1)
}
await page.screenshot({ path: OUT_ZOOM })
console.log(`[world] 截图（放大档）：${OUT_ZOOM}`)

/**
 * 读**所有**实体标签节点的文本，不管它当前可不可见。
 *
 * <p>为什么这里不按可见性过滤（与上面那段"可见文字"的口径相反）：本判据要回答的是
 * "#268 之后地图上写的到底是 `IRON` 还是 `铁矿`"，那是**文案内容**问题；
 * 而默认缩放下资源格的标签根本不画（截图实测：地图只占中间约 350×350，其余全黑，
 * 读数写着"缩放 0"，屏上 0 条资源标签）—— 那是**另一条更大的排版缺陷**，
 * 已单独记进 `.qoder-work-queue.md`，不许混进来把文案判据变成"因为没画所以通过/失败"。
 * 可见条数仍然打出来当读数，只是不当门。
 */
const captions = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const out = []
  const visit = (n, visible) => {
    const shown = visible && n.active !== false
    if (/Caption$/i.test(n.name)) {
      const label = n.getComponent('cc.Label')
      if (label !== null && label.string !== '') out.push([label.string, shown])
    }
    for (const c of n.children) visit(c, shown)
  }
  visit(scene, true)
  return out
})
const labels = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const out = []
  const visit = (n, visible) => {
    const shown = visible && n.active !== false
    const label = n.getComponent('cc.Label')
    if (shown && label !== null && label.enabled !== false && label.string !== '') out.push(label.string)
    for (const c of n.children) visit(c, shown)
  }
  visit(scene, true)
  return out
})
/**
 * 标签压叠**读数**（这一趟只打数字，不当门）。
 *
 * <p>#269 目视到的形状是"同一处两个 `石料` 叠在一起、`粮草` 压在 `无名君主` 上"。
 * 先把"到底几对、压了多少像素"量出来再决定改哪一侧 —— 这条判据链上刚连着错过三次
 * （面积比、`UITransform`、按节点名找对象，见 #270/#271/#272），**没有数字就先不立门**。
 * 对象仍从渲染器拿：`drawnEntities` 的 `Caption` 子节点，外接盒用 `getBoundingBoxToWorld()`。
 */
const overlap = await page.evaluate(() => {
  let map = null
  const visit = (node) => {
    if (map !== null) return
    const comp = node.getComponent('WorldMap')
    if (comp !== null) { map = comp; return }
    for (const c of node.children) visit(c)
  }
  visit(window.cc.director.getScene())
  if (map === null) return { boxes: 0, pairs: 0, examples: [], heights: {} }
  const boxes = []
  // 高度分堆：板子画的是 18 高，若这里冒出 50（文字盒）或 100（池默认）就说明量的又不是画出来的那块
  // —— #271/#272/#275 三次都栽在这条上，所以把分堆打在输出里当自检。
  const heights = {}
  // 两个池都要扫：藏牌口径现在是跨实体与行军一起判的，只扫实体就等于"门看不见行军牌"。
  const markers = [...map.drawnEntities.values(), ...map.drawnMarches.values()]
  for (const node of markers) {
    /**
     * 量 `CaptionPlate`（板子本体），不量 `Caption`：后者挂着 `Label`，它的 `UITransform`
     * 每帧被组件按文字尺寸重写（实测 24×50），量到的不是玩家看见的那块牌。
     */
    const plateNode = node.getChildByName('CaptionPlate')
    const box = plateNode !== null ? plateNode.getComponent('cc.UITransform') : null
    const caption = node.getChildByName('Caption')
    const label = caption !== null ? caption.getComponent('cc.Label') : null
    if (box === null || label === null || label.string === '') continue
    if (!node.activeInHierarchy || !box.node.activeInHierarchy) continue
    const r = box.getBoundingBoxToWorld()
    if (r.width <= 0 || r.height <= 0) continue
    boxes.push({ text: label.string, x: r.x, y: r.y, w: r.width, h: r.height })
    const key = `${Math.round(r.width)}x${Math.round(r.height)}`
    heights[key] = (heights[key] ?? 0) + 1
  }
  const hits = []
  let pairs = 0
  for (let i = 0; i < boxes.length; i += 1) {
    for (let j = i + 1; j < boxes.length; j += 1) {
      const a = boxes[i]; const b = boxes[j]
      const ox = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)
      const oy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y)
      if (ox > 1 && oy > 1) {
        pairs += 1
        if (hits.length < 5) hits.push(`${a.text} × ${b.text}（压 ${Math.round(ox)}×${Math.round(oy)}）`
          + ` 盒 ${Math.round(a.w)}×${Math.round(a.h)}@(${Math.round(a.x)},${Math.round(a.y)})`
          + ` / ${Math.round(b.w)}×${Math.round(b.h)}@(${Math.round(b.x)},${Math.round(b.y)})`)
      }
    }
  }
  return { boxes: boxes.length, pairs, examples: hits, heights }
})
await browser.close()
await preview.close()
console.log(`[world] 放大档在屏名牌（实体 + 行军）${overlap.boxes} 张，按盒子尺寸分堆 ${JSON.stringify(overlap.heights)}`)
console.log(`[world] 两两压叠 ${overlap.pairs} 对`
  + `${overlap.examples.length > 0 ? `：${overlap.examples.join('；')}` : ''}`)
/**
 * 分堆自检：牌盒只可能是"字数 × 板高"这几种，出现 50（文字盒）或 100（池默认）
 * 说明量的又不是画出来的那块 —— 这条判据已经错过三次（#271/#272/#275），所以钉成门。
 */
const badBoxes = PLATE_HEIGHT === 0 ? [] : Object.keys(overlap.heights).filter((key) => Number(key.split('x')[1]) !== PLATE_HEIGHT)
if (PLATE_HEIGHT === 0) {
  console.error('[world][前置] 从 WorldLabels.ts 里读不到 CAPTION_PLATE_HEIGHT —— 分堆自检没有基准，不判')
} else if (badBoxes.length > 0) {
  console.error(`[world] 判据失败：名牌盒分堆里出现非板高（${PLATE_HEIGHT}）的尺寸 ${badBoxes.join('、')} `
    + '—— 量的又不是画出来的那块了，压叠对数不可信')
  process.exit(1)
}
if (overlap.pairs > 0) {
  console.error(`[world] 判据失败：仍有 ${overlap.pairs} 对名牌压叠（藏牌口径 `
    + 'game/world/WorldLabels.ts#pickVisibleCaptions 未生效或被绕过）')
  process.exit(1)
}

const captionTexts = captions.map((entry) => entry[0])
const visibleCaptions = captions.filter((entry) => entry[1]).length
console.log(`[world] 等到 ${sawCaption} 条文字后拍摄；可见文字 ${labels.length} 条`)
if (errors.length > 0) {
  console.error(`[world][前置] 页面报错 ${errors.length} 条：${errors[0]}`)
  process.exit(2)
}
// 只数**实体**标签：按钮与页签那一些节点也叫 `*_Caption`，把它们算进来会得到
// "22 个标签、0 个资源名"这种看着像缺陷、其实是判据抓错对象的读数（实测首跑就这样）。
const entityCaptions = captionTexts.filter((s) => !/^(放大|缩小|回城|流亡迁城|行军|内城|军队|武将|背包|关卡|战报|任务|战令|邮件|社交|战力|商店|外观|搜索|地图|设置)$/.test(s))
console.log(`[world] 实体标签 ${entityCaptions.length} 个（屏上另有按钮/页签标签 ${captionTexts.length - entityCaptions.length} 个），其中当前可见 ${visibleCaptions} 个`)
if (entityCaptions.length === 0) {
  // 这是**本工具自己的能力边界**，不是产品缺陷：默认缩放（截图实测"缩放 0"，地图只占中间约 350×350）
  // 根本不画实体标签，而 headless 下点不动"放大"按钮（触摸送不进去，见项目队列）。
  // 所以这一屏的文案要看 `verify-art-runtime.mjs` 导出的 `art-world-zoom-runtime.png`（它会放大再看）。
  console.error('[world][前置] 默认缩放下地图不画任何实体标签，本工具又点不动"放大" —— '
    + '文案判据走不到。要看中文资源名请核 `art-world-zoom-runtime.png`，不要把这条读成产品缺陷')
  process.exit(2)
}

const bareEnums = entityCaptions.filter((s) => ENUM_NAMES.some((e) => new RegExp(`(^|[^A-Z])${e}([^A-Z]|$)`).test(s)))
const cnHits = entityCaptions.filter((s) => CN_NAMES.some((n) => s.includes(n)))
console.log(`[world] 标签文案：中文资源名 ${cnHits.length} 条 / 裸枚举 ${bareEnums.length} 条`
  + `${bareEnums.length > 0 ? `（${bareEnums.slice(0, 6).join('、')}）` : ''}`
  + ` / 其余 ${entityCaptions.length - bareEnums.length - cnHits.length} 条：${entityCaptions.filter((s) => !CN_NAMES.some((n) => s.includes(n))).slice(0, 4).join('、')}`)
// **负向判据永远有效**：这一屏只要冒出 `IRON`/`WOOD` 就是 #268 的缺陷复发，判红。
if (bareEnums.length > 0) {
  console.error(`[world] 判据失败：${bareEnums.length} 条标签仍是资源枚举原文（#268 的缺陷形态）`)
  process.exit(1)
}
// **正向判据要有对象才成立**：默认缩放这一屏只画得出玩家自己那座城（实测 1 条实体标签"无名君主"），
// 资源格的标签根本不画（`renderEntities` 只在 `zoom > 0` 时给文案），而 headless 点不动"放大"。
// 没有资源标签时判"必须有中文资源名"，等于拿工具的无能当产品的缺陷 —— 退 2 说清**谁在判**。
// 正向那一半已经有能自动跑的形态了：`entityCaption` 抽进引擎无关的 `game/world/WorldLabels.ts`，
// 由 `client/tests/WorldLabels.test.ts` 每次 `npm test` 判 —— 不需要浏览器、不需要缩放、不需要触摸。
if (cnHits.length === 0) {
  console.error('[world][前置] 这一屏没有资源格标签可核（默认缩放不画资源标签，本工具又点不动"放大"）。'
    + '负向判据已过（无枚举原文）；正向由 `client/tests/WorldLabels.test.ts` 判，人工图见 '
    + '`art-verify/art-world-zoom-runtime.png`')
  process.exit(2)
}
console.log(`[world] 全绿：地图实体标签写的都是中文资源名（${cnHits.length}/${entityCaptions.length} 条）`)
