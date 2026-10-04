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
const hitChecks = []
for (const tile of occupiedTiles) {
  // 2026-10-04：**点之前先把镜头移到那一格**。
  // 起因是几何读数：算出来的页面坐标大面积出界（Grid-7 → x=-226、Grid-11 → x=1848、Grid-31 → y=1182，
  // 视口只有 1440×900），而 CityPanelView 本来就可缩放可平移（zoom 默认 1.8、MIN 1 / MAX 2.4，
  // 滚轮 :552 / 捏合 :568 / 单指拖动 + setFocus :583 / stage.setScale :646）⇒ 网格超出视口是设计如此。
  // 不移镜头就点，Playwright 会把视口外的坐标**夹进视口** ⇒ 点空或落到主城头上（实测正是如此）。
  // 由 applyStageTransform 反推：世界坐标 = zoom × (local − focus) ⇒ 把某格摆到屏幕中心 = setFocus(该格 local)。
  const focused = await page.evaluate((name) => {
    let view = null
    let target = null
    const visit = (n) => {
      if (view === null) view = n.getComponent('CityPanelView') ?? null
      if (target === null && n.name === name) target = n
      for (const c of n.children) visit(c)
    }
    visit(window.cc.director.getScene())
    if (view === null || target === null) return { ok: false, why: view === null ? 'no-view' : 'no-tile' }
    const p = target.position
    view.setFocus(p.x, p.y)
    return { ok: true, focus: [Math.round(p.x), Math.round(p.y)] }
  }, tile.tile)
  if (focused.ok !== true) {
    console.log(`   [geo] ${tile.tile} 移镜头失败：${focused.why}`)
  }
  await page.waitForTimeout(400)
  // ⚠️ 坐标必须在**移完镜头之后**重算：镜头一动，所有格子的页面坐标全变（上一版算完就缓存是错的）。
  const point = await toPage(tile.tile)
  // 把「算出来的页面坐标」与「格子的几何」并排打出来：
  // 两者的差就是这一格点不中的原因（坐标换算错 vs 命中区没盖住基座）——两者都打出来才分得开。
  console.log(`   [geo] ${tile.tile} 移镜头=${JSON.stringify(focused.focus ?? focused.why)}`
    + ` 节点世界=(${tile.geo?.wx},${tile.geo?.wy})`
    + ` 尺寸=${tile.geo?.w}×${tile.geo?.h} 锚点=(${tile.geo?.ax},${tile.geo?.ay})`
    + ` | 图标世界=(${tile.geo?.iwx},${tile.geo?.iwy}) 尺寸=${tile.geo?.iw}×${tile.geo?.ih}`
    + ` | 移镜头后的页面坐标=${point === null ? 'null' : `(${Math.round(point.x)},${Math.round(point.y)})`}`
    + ` 图标active=${tile.iconActive} 帧名=${tile.frameName}`)
  if (point === null) {
    hitChecks.push({ tile: tile.tile, expected: null, title: null, ok: false })
    continue
  }
  await page.mouse.click(point.x, point.y)
  await page.waitForTimeout(600)
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
  // 用屏幕坐标挑"离所有已建格子最远"的那个空格（比按索引挑稳）
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
  // 参照：先点主城那一格，让选择栏停在已知状态
  const reference = occupiedTiles.find((t) => t.texts.some((x) => x.includes('主城')))
  if (reference !== undefined) {
    const referencePoint = positions[reference.tile]
    if (referencePoint !== null && referencePoint !== undefined) {
      await page.mouse.click(referencePoint.x, referencePoint.y)
      await page.waitForTimeout(600)
    }
  }
  const before = await selectedTitle()
  const point = positions[far.name]
  if (point !== null && point !== undefined) {
    await page.mouse.click(point.x, point.y)
    await page.waitForTimeout(600)
    emptyControl = { tile: far.name, distance: Math.round(far.distance), before, title: await selectedTitle() }
  }
}
console.log('[multi-types] 点击命中：')
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