/**
 * 职责：内城镜头的运行时验收 —— 默认放大并对准主堡、缩小能看全城、放大键与夹取都真的生效、
 *       玩家动过镜头之后数据刷新不许把镜头抢回去。
 * 依赖：`client/build/web-mobile` 产物 + 一台本轮自己的 dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么需要它：这一格改的是"默认看得见什么"。默认 1.8 倍时屏上只剩主堡周围那几栋 ——
 * 这件事**只有运行时数得清**（36 格里几格落在视口内），源码里读不出来；
 * 而"缩到 1 倍能看全城"是它的对照组：两个读数必须一起变，只报一个等于没量。
 *
 * <p>缩放倍数与步长从 `CityPanelView.ts` 现读（不在量具里抄第二份）；
 * 点击走**真鼠标坐标**（节点世界坐标 × 画布缩放），不是 `emit` —— 这一格要验的正是"玩家点得到"。
 *
 * 退出码：0 全绿；1 判据失败；2 前置不满足。
 */
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { nodeScreenPos } from './lib/node-screen-pos.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.CITY_ZOOM_PORT ?? 8305)
const OUT = process.env.CITY_ZOOM_SHOT_OUT ?? 'client/build/city-zoom-verify'
const VIEW_SOURCE = 'client/assets/scripts/scene/CityPanelView.ts'
/** 主堡的显示名（`contract/config/building.json` 里 main_city 那一行）。认名字不认坐标：格子位置由服务端下发。 */
const KEEP_NAME = '主城'

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error('[city-zoom][前置] 产物不存在（先跑 scripts/build-webmobile.sh）')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const source = readFileSync(VIEW_SOURCE, 'utf8')
const constant = (name) => Number((source.match(new RegExp(`const ${name} = ([0-9.]+)`)) ?? [])[1])
const ZOOM_MIN = constant('CITY_ZOOM_MIN')
const ZOOM_MAX = constant('CITY_ZOOM_MAX')
const ZOOM_DEFAULT = constant('CITY_ZOOM_DEFAULT')
const ZOOM_STEP = constant('CITY_ZOOM_STEP')
if (![ZOOM_MIN, ZOOM_MAX, ZOOM_DEFAULT, ZOOM_STEP].every((value) => Number.isFinite(value))) {
  console.error(`[city-zoom][前置] 从 ${VIEW_SOURCE} 读不到缩放常量`)
  process.exit(2)
}

const deviceId = `city-zoom-${Date.now()}`
const init = await fetch(`${BACKEND}/player/init`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    requestId: `city-zoom-${Date.now()}`, deviceId, nickName: '镜头探针',
    clientTime: Date.now(), wxCode: '',
  }),
}).then((response) => response.json())
if (init.code !== 0) {
  console.error(`[city-zoom][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}

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
await page.waitForFunction(() => window.cc?.director?.getScene?.() != null, null, { timeout: 25_000 })
  .catch(() => {})
await page.waitForTimeout(3000)
await hideGuideOverlay(page)

/**
 * 读镜头与 36 格。
 *
 * <p>**几何全部在舞台本地坐标里算**，不换算屏幕像素：舞台本地点 p 落到面板坐标是
 * `stage.position + p * zoom`，而面板原点就是屏幕中心、面板尺寸就是 `view.getVisibleSize()`。
 * 这条路不依赖"世界原点在哪儿"（它在设计区左下角，不在屏幕中心 —— 量具里抄错过一次，
 * 见 `tools/lib/node-screen-pos.mjs` 的注释），也不受视口像素与设计分辨率的比例影响。
 */
const readView = () => page.evaluate((keepName) => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game') ?? null
  const city = game?.children.find((child) => child.name === 'city') ?? null
  const find = (root, name) => {
    let out = null
    const walk = (n) => {
      if (out !== null) return
      if (n.name === name) { out = n; return }
      for (const c of n.children) walk(c)
    }
    if (root !== null) walk(root)
    return out
  }
  const stage = find(city, 'CityStage')
  const grid = find(city, 'CityGrid')
  if (stage === null || grid === null) return { found: false, stage: stage !== null, grid: grid !== null }
  const visible = window.cc.view.getVisibleSize()
  const zoom = stage.scale.x
  const stageX = stage.position.x
  const stageY = stage.position.y
  const panelX = (lx) => stageX + lx * zoom
  const panelY = (ly) => stageY + ly * zoom
  const inside = (lx, ly) => Math.abs(panelX(lx)) <= visible.width / 2 + 2
    && Math.abs(panelY(ly)) <= visible.height / 2 + 2
  const tiles = []
  for (const tile of grid.children) {
    if (!tile.name.startsWith('Grid-')) continue
    const label = tile.getChildByName('Name')?.getComponent('cc.Label') ?? null
    tiles.push({
      name: label === null ? '' : String(label.string),
      inside: inside(tile.position.x, tile.position.y),
      panelX: panelX(tile.position.x),
      panelY: panelY(tile.position.y),
    })
  }
  const keep = tiles.find((tile) => tile.name === keepName) ?? null
  const keepNode = keep === null ? null : grid.children
    .find((tile) => tile.name.startsWith('Grid-')
      && String(tile.getChildByName('Name')?.getComponent('cc.Label')?.string ?? '') === keepName)
  return {
    found: true,
    zoom: Number(zoom.toFixed(4)),
    stageX: Number(stageX.toFixed(2)),
    stageY: Number(stageY.toFixed(2)),
    tileCount: tiles.length,
    insideCount: tiles.filter((tile) => tile.inside).length,
    keepPanel: keep === null ? null : { x: keep.panelX, y: keep.panelY },
    /** 主堡那一格的**舞台本地坐标**：夹取上限算不算得对，要靠它自己复算一遍。 */
    keepLocal: keepNode === null ? null
      : { x: Number(keepNode.position.x.toFixed(2)), y: Number(keepNode.position.y.toFixed(2)) },
    viewport: { width: visible.width, height: visible.height },
  }
}, KEEP_NAME)

/** 真鼠标点一颗 HUD 键（换算走共用的 `nodeScreenPos`）。点前再藏一次引导：它会随数据重新激活并吃掉点击。 */
const tapNode = async (name) => {
  await hideGuideOverlay(page, 120)
  const pos = await page.evaluate(nodeScreenPos, name)
  if (pos === null) return false
  await page.mouse.click(Math.round(pos.x), Math.round(pos.y))
  await page.waitForTimeout(220)
  return true
}

let failed = 0
const report = (label, ok, detail) => {
  if (!ok) failed += 1
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail === undefined ? '' : `：${detail}`}`)
}
const near = (actual, expected, slack = 0.02) => Math.abs(actual - expected) <= slack

const first = await readView()
if (first.found !== true) {
  console.error(`[city-zoom][前置] 读不到城景舞台：${JSON.stringify(first)}`)
  await browser.close()
  await preview.close()
  process.exit(2)
}
console.log(`[city-zoom] 视口 ${first.viewport.width}x${first.viewport.height}，36 格落点读到 ${first.tileCount} 个`)

// ---------- 第 1 相：默认就是放大 + 对准主堡 ----------
report('36 格都建出来了', first.tileCount === 36, `实测 ${first.tileCount}`)
report(`默认缩放 = 源码常量 ${ZOOM_DEFAULT}`, near(first.zoom, ZOOM_DEFAULT), `实测 ${first.zoom}`)
/**
 * 镜头对准主堡 —— 判的是**夹取之后的精确落点**，不是"必须正中"。
 *
 * <p>主堡在底图上半部（本地 y 为正），1.8 倍下要把它摆到正中就得露出底图之外的深色底，
 * 而"底图必须铺满视口"是硬约束（`applyStageTransform` 的夹取上限 `content*(zoom-1)/2`）。
 * 所以这里按同一个公式复算期望偏移：焦点没生效（stage 停在 0,0）、或夹取写错，都会红。
 * 实测这一帧就是夹住的形状：本地 y≈219 ⇒ 期望 -394 被夹到 -240，主堡落在中心上方 ~154 设计px。
 */
const clamp = (value, limit) => Math.max(-limit, Math.min(limit, value))
const maxX = first.viewport.width * (first.zoom - 1) / 2
const maxY = first.viewport.height * (first.zoom - 1) / 2
const expectedX = first.keepLocal === null ? Number.NaN : clamp(-first.keepLocal.x * first.zoom, maxX)
const expectedY = first.keepLocal === null ? Number.NaN : clamp(-first.keepLocal.y * first.zoom, maxY)
report('镜头对准主堡（= 夹取公式算出的落点，±1px）',
  first.keepLocal !== null
  && Math.abs(first.stageX - expectedX) <= 1 && Math.abs(first.stageY - expectedY) <= 1,
  first.keepLocal === null ? '屏上找不到「主城」那一格'
    : `stage=(${first.stageX}, ${first.stageY}) 期望=(${expectedX.toFixed(1)}, ${expectedY.toFixed(1)})`)
report('主堡自己在默认这一屏里（"主城周围"必须包含主城）',
  first.keepPanel !== null
  && Math.abs(first.keepPanel.x) <= first.viewport.width / 2
  && Math.abs(first.keepPanel.y) <= first.viewport.height / 2,
  first.keepPanel === null ? '找不到主堡'
    : `panel=(${first.keepPanel.x.toFixed(0)}, ${first.keepPanel.y.toFixed(0)})，`
      + `离中心 ${(Math.abs(first.keepPanel.y) / first.viewport.height * 100).toFixed(1)}% 屏高`)
/**
 * "只显示主城周围建筑"的可计算口径：默认这一屏里落点可见的格子必须**少于全部**、且不止一格。
 * 只报"少于一半"是拍脑袋的阈值 —— 真正要钉的是"不是全城尽收"，所以用 < 36 与 ≥ 2 两条边。
 */
report('默认放大时屏上只有主堡周围那几栋（不是全城尽收）',
  first.insideCount < first.tileCount && first.insideCount >= 2,
  `可见 ${first.insideCount}/${first.tileCount} 格`)
await page.screenshot({ path: path.join(OUT, 'city-zoom-default.png') })

// ---------- 第 2 相：缩小到下限 ⇒ 全城尽收 ----------
const stepsDown = Math.ceil((ZOOM_DEFAULT - ZOOM_MIN) / ZOOM_STEP) + 1
let tapped = 0
for (let i = 0; i < stepsDown; i += 1) {
  if (await tapNode('ZoomOutButton')) tapped += 1
}
report('缩小键点得到（真鼠标坐标命中）', tapped === stepsDown, `${tapped}/${stepsDown} 次命中`)
const zoomedOut = await readView()
report(`缩小到下限 ${ZOOM_MIN}（夹取生效，不多缩）`, near(zoomedOut.zoom, ZOOM_MIN), `实测 ${zoomedOut.zoom}`)
report('缩小后 36 格全在视口里（对照组：这才是"全城尽收"）',
  zoomedOut.insideCount === zoomedOut.tileCount, `${zoomedOut.insideCount}/${zoomedOut.tileCount} 格可见`)
report('缩到 1 倍时舞台回到居中（不露底图外的深色底）',
  Math.abs(zoomedOut.stageX) <= 1 && Math.abs(zoomedOut.stageY) <= 1,
  `stage=(${zoomedOut.stageX}, ${zoomedOut.stageY})`)
await page.screenshot({ path: path.join(OUT, 'city-zoom-full.png') })

// ---------- 第 3 相：玩家动过镜头之后，数据刷新不许抢回去 ----------
// 先放大一步（1 → 1.3）：1 倍时夹取上限为 0、舞台恒居中，"没回中"这条会恒真（假绿）。
await tapNode('ZoomInButton')
const beforeDrag = await readView()
// 真拖一段。拖动本身必须真的移动了舞台 —— 否则后面"没回中"量的是一个没动过的东西
await page.mouse.move(700, 450)
await page.mouse.down()
await page.mouse.move(830, 480, { steps: 6 })
await page.mouse.up()
await page.waitForTimeout(300)
const dragged = await readView()
const movedByDrag = Math.hypot(dragged.stageX - beforeDrag.stageX, dragged.stageY - beforeDrag.stageY)
report('单指拖动真的平移了镜头（> 20 设计px）', movedByDrag > 20,
  `位移 ${movedByDrag.toFixed(1)}（(${beforeDrag.stageX}, ${beforeDrag.stageY}) → (${dragged.stageX}, ${dragged.stageY})）`)
// 面板每秒重画一次（`update` 里的秒级重绘），等两秒就是两次 renderGrid：镜头不许被抢回主堡
await page.waitForTimeout(2200)
const afterTick = await readView()
report('数据刷新后镜头仍在玩家停的地方（没有自动回中）',
  Math.abs(afterTick.stageX - dragged.stageX) <= 1 && Math.abs(afterTick.stageY - dragged.stageY) <= 1,
  `拖后 (${dragged.stageX}, ${dragged.stageY}) → 刷新后 (${afterTick.stageX}, ${afterTick.stageY})`)

// ---------- 第 4 相：放大键与上限夹取 ----------
for (let i = 0; i < 12; i += 1) {
  await tapNode('ZoomInButton')
}
const zoomedIn = await readView()
report(`放大到上限 ${ZOOM_MAX}（夹取生效）`, near(zoomedIn.zoom, ZOOM_MAX), `实测 ${zoomedIn.zoom}`)
await tapNode('ZoomOutButton')
const oneStep = await readView()
report(`放大键与缩小键都按步长 ${ZOOM_STEP} 走`, near(oneStep.zoom, ZOOM_MAX - ZOOM_STEP, 0.03),
  `实测 ${oneStep.zoom}（期望 ${(ZOOM_MAX - ZOOM_STEP).toFixed(2)}）`)
await page.screenshot({ path: path.join(OUT, 'city-zoom-max.png') })

await browser.close()
await preview.close()
if (errors.length > 0) {
  console.error(`[city-zoom][FAIL] 页面报错 ${errors.length} 条：${errors.slice(0, 3).join(' | ')}`)
  process.exit(1)
}
if (failed > 0) {
  console.error(`[city-zoom][FAIL] ${failed} 条判据未过`)
  process.exit(1)
}
console.log(`[city-zoom] 全绿：默认 ${ZOOM_DEFAULT} 倍对准主堡、屏上 ${first.insideCount}/${first.tileCount} 格；`
  + `缩到 ${ZOOM_MIN} 倍全城 ${zoomedOut.insideCount} 格尽收；截图在 ${OUT}`)
process.exit(0)
