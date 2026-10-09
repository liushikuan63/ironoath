/**
 * 职责：内城镜头的运行时验收 —— 默认放大并对准主堡、缩小能看全城、放大键与夹取都真的生效、
 *       玩家动过镜头之后数据刷新不许把镜头抢回去；建筑薄框、名字与等级始终跟随真实脚面。
 * 依赖：`client/build/web-mobile` 产物 + 一台本轮自己的 dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么需要它：这一格改的是"默认看得见什么"。默认 1.8 倍时屏上只剩主堡周围那几栋 ——
 * 这件事**只有运行时数得清**（36 格里几格落在视口内），源码里读不出来；
 * 而"缩到 1 倍能看全城"是它的对照组：两个读数必须一起变，只报一个等于没量。
 *
 * <p>缩放倍数与步长从 `CityPanelView.ts` 现读（不在量具里抄第二份）；
 * 点击走**真鼠标坐标**（共用相机往返 + 引擎 hitTest），不是 `emit` —— 这一格要验的正是"玩家点得到"。
 *
 * 退出码：0 全绿；1 判据失败；2 前置不满足。
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { clickNodeViaCocos } from './lib/cocos-click.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.CITY_ZOOM_PORT ?? 8305)
const OUT = process.env.CITY_ZOOM_SHOT_OUT ?? 'client/build/city-zoom-verify'
const VIEW_SOURCE = 'client/assets/scripts/scene/CityPanelView.ts'
/** 可接续同一后端上 full-city 刚养好的真实账号，默认仍验新号主城；不修改账号城建。 */
const EXPECT_BUILDINGS = Number(process.env.CITY_ZOOM_EXPECT_BUILDINGS ?? 1)
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
if (!Number.isInteger(EXPECT_BUILDINGS) || EXPECT_BUILDINGS < 1) {
  console.error('[city-zoom][前置] CITY_ZOOM_EXPECT_BUILDINGS 必须是正整数')
  process.exit(2)
}

const deviceId = process.env.CITY_ZOOM_DEVICE ?? `city-zoom-${Date.now()}`
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
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(`console: ${message.text()}`)
})

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
  // 负控把 Name 暂移出建筑后仍读同一颗真实节点：不能用“父子关系不符”代替几何脱离。
  const nameNode = (tile) => tile.getChildByName('Name')
    ?? (globalThis.__cityZoomDetachedName?.tileUuid === tile.uuid
      ? globalThis.__cityZoomDetachedName.node : null)
  for (const tile of grid.children) {
    if (!tile.name.startsWith('Grid-')) continue
    const label = nameNode(tile)?.getComponent('cc.Label') ?? null
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
      && String(nameNode(tile)?.getComponent('cc.Label')?.string ?? '') === keepName)
  const keepIcon = keepNode?.getChildByName('BuildingIcon') ?? null
  const keepSprite = keepIcon?.getComponent('cc.Sprite') ?? null
  const keepBox = keepIcon?.getComponent('cc.UITransform') ?? null
  const keepCenterLocal = keepNode === null || keepIcon === null || keepBox === null ? null : {
    x: keepNode.position.x + keepIcon.position.x + (0.5 - keepBox.anchorX) * keepBox.width,
    y: keepNode.position.y + keepIcon.position.y + (0.5 - keepBox.anchorY) * keepBox.height,
  }
  const keepBounds = keepNode === null || keepIcon === null || keepBox === null ? null : {
    left: panelX(keepNode.position.x + keepIcon.position.x - keepBox.anchorX * keepBox.width),
    right: panelX(keepNode.position.x + keepIcon.position.x + (1 - keepBox.anchorX) * keepBox.width),
    bottom: panelY(keepNode.position.y + keepIcon.position.y - keepBox.anchorY * keepBox.height),
    top: panelY(keepNode.position.y + keepIcon.position.y + (1 - keepBox.anchorY) * keepBox.height),
  }
  let ancestorOpacity = true
  for (let ancestor = keepIcon; ancestor; ancestor = ancestor.parent) {
    if (ancestor.getComponent('cc.UIOpacity')?.opacity === 0) ancestorOpacity = false
  }
  const keepDrawable = keepIcon !== null && keepIcon.activeInHierarchy && keepSprite?.enabled === true
    && keepSprite.spriteFrame?.name === 'building-main-city-v1' && keepIcon._uiProps?.uiComp === keepSprite
    && keepSprite.color.a > 0 && ancestorOpacity && scene.getComponentsInChildren('cc.Camera')
      .some(camera => camera.enabled && camera.node.activeInHierarchy && (camera.visibility & keepIcon.layer) !== 0)
  const cameras = scene.getComponentsInChildren('cc.Camera')
  const canvas = document.querySelector('canvas')?.getBoundingClientRect()
  const dpr = window.devicePixelRatio || 1
  // 脚面比必须取正在运行的生产模块；不把十五张图的 alpha 几何再抄进量具。
  const artFamilyEntry = typeof window.System?.entries === 'function'
    ? Array.from(window.System.entries()).find(([key, module]) => /(?:^|\/)ArtFamilies\.ts(?:$|\?)/.test(key)
      && typeof module?.buildingArtFootRatio === 'function') : undefined
  const artFamilies = artFamilyEntry?.[1] ?? null
  const cityView = scene.getComponentInChildren('CityPanelView')
  const platesByTile = new Map((cityView?.gridTiles ?? []).map(tile => [tile.node.name, tile.plate]))
  const rowsByPlate = new Map((cityView?.panel?.rows ?? []).map(row => [`${row.gridX}:${row.gridY}`, row]))
  const expectedBuildings = (cityView?.panel?.rows ?? [])
    .filter(row => row.level > 0 || row.upgrading || row.paused || row.collectable).map(row => {
      const tile = (cityView?.gridTiles ?? []).find(tile => tile.plate.gridX === row.gridX && tile.plate.gridY === row.gridY)
      const name = typeof row.name === 'string' ? row.name.trim() : ''
      return { tile: tile?.node.name ?? null, configId: row.configId, gridX: row.gridX, gridY: row.gridY,
        name: name !== '' && !/^[A-Za-z][A-Za-z0-9_-]*$/.test(name) ? name : '未知建筑', level: `${row.level}级` }
    })
  const cameraFor = (node) => cameras.find(camera => camera.enabled && camera.node.activeInHierarchy
    && (camera.visibility & node.layer) !== 0) ?? null
  const drawable = (node, component) => {
    if (!node?.activeInHierarchy || !component?.enabled || node._uiProps?.uiComp !== component
      || cameraFor(node) === null) return false
    for (let ancestor = node; ancestor; ancestor = ancestor.parent) {
      if (ancestor.getComponent('cc.UIOpacity')?.opacity === 0) return false
    }
    return true
  }
  const screenPoint = (node, x = 0, y = 0) => {
    const box = node?.getComponent('cc.UITransform') ?? null
    const camera = node == null ? null : cameraFor(node)
    if (box === null || camera === null || canvas === undefined || canvas.width <= 0 || canvas.height <= 0) return null
    const world = box.convertToWorldSpaceAR(new window.cc.Vec3(x, y, 0))
    const p = camera.worldToScreen(new window.cc.Vec3(world.x, world.y, world.z))
    const back = camera.screenToWorld(new window.cc.Vec3(p.x, p.y, p.z))
    const roundTripDrift = Math.max(Math.abs(back.x - world.x), Math.abs(back.y - world.y))
    // 与仓库 cocos-click 同口径：引擎点按 dpr 回到 CSS 像素，不除设计空间尺寸。
    return { x: canvas.left + p.x / dpr, y: canvas.top + canvas.height - p.y / dpr, roundTripDrift }
  }
  // UITransform 的四角经实际相机投影到浏览器像素，名字与框是否跟随由读数判定。
  const screenBox = (node) => {
    const box = node?.getComponent('cc.UITransform') ?? null
    if (box === null) return null
    const project = (x, y) => screenPoint(node, x, y)
    const points = [project(-box.anchorX * box.width, -box.anchorY * box.height),
      project((1 - box.anchorX) * box.width, -box.anchorY * box.height),
      project(-box.anchorX * box.width, (1 - box.anchorY) * box.height),
      project((1 - box.anchorX) * box.width, (1 - box.anchorY) * box.height)]
    if (points.some(point => point === null)) return null
    const xs = points.map(p => p.x)
    const ys = points.map(p => p.y)
    const left = Math.min(...xs), right = Math.max(...xs)
    const top = Math.min(...ys), bottom = Math.max(...ys)
    const origin = project(0, 0)
    return { x: (left + right) / 2, y: (top + bottom) / 2, width: right - left, height: bottom - top,
      origin, points, verified: [...points, origin].every(point => Number.isFinite(point.roundTripDrift)
        && point.roundTripDrift <= 0.5) }
  }
  const buildings = grid.children.filter(tile => /^Grid-\d+$/.test(tile.name)).flatMap(tile => {
    const icon = tile.getChildByName('BuildingIcon')
    const sprite = icon?.getComponent('cc.Sprite') ?? null
    if (!drawable(icon, sprite) || !sprite.spriteFrame || sprite.color.a <= 0) return []
    const frame = tile.getChildByName('BuildingNameplate')
    const name = nameNode(tile)
    const level = tile.getChildByName('Level')
    const nameLabel = name?.getComponent('cc.Label') ?? null
    const levelLabel = level?.getComponent('cc.Label') ?? null
    const plate = platesByTile.get(tile.name)
    const row = plate ? rowsByPlate.get(`${plate.gridX}:${plate.gridY}`) : undefined
    const iconBox = icon.getComponent('cc.UITransform')
    const footRatio = row && artFamilies ? artFamilies.buildingArtFootRatio(row.configId) : Number.NaN
    const expectedFrame = row && artFamilies ? artFamilies.FAMILY_ASSETS?.building?.[row.configId]?.split('/').at(-1) : null
    const entityFootLocalY = iconBox && Number.isFinite(footRatio) ? iconBox.height * (footRatio - iconBox.anchorY) : Number.NaN
    const foot = Number.isFinite(entityFootLocalY) ? screenPoint(icon, 0, entityFootLocalY) : null
    const base = screenPoint(tile)
    const footDistance = foot && base ? Math.hypot(foot.x - base.x, foot.y - base.y) : null
    const footGeometryValid = !!row && !!artFamilies && Number.isFinite(footRatio) && footRatio >= 0 && footRatio <= 1
      && sprite.trim === false && expectedFrame === sprite.spriteFrame.name
      && footDistance !== null && footDistance <= 0.5
    return [{ tile: tile.name, configId: row?.configId ?? null, name: String(nameLabel?.string ?? ''),
      level: String(levelLabel?.string ?? ''), art: sprite.spriteFrame.name,
      foot, base, footGeometryValid, footVerified: foot !== null && base !== null
        && Number.isFinite(foot.roundTripDrift) && foot.roundTripDrift <= 0.5
        && Number.isFinite(base.roundTripDrift) && base.roundTripDrift <= 0.5,
      footMetadata: { configId: row?.configId ?? null, expectedFrame, footRatio: Number.isFinite(footRatio) ? footRatio : null,
        trim: sprite.trim, anchorY: iconBox?.anchorY ?? null, entityFootLocalY: Number.isFinite(entityFootLocalY) ? entityFootLocalY : null,
        footDistance },
      frame: screenBox(frame), nameBox: screenBox(name), levelBox: screenBox(level),
      frameDrawable: drawable(frame, frame?.getComponent('cc.Graphics')),
      nameDrawable: drawable(name, nameLabel) && nameLabel.color.a > 0 && nameLabel.string !== '',
      levelDrawable: drawable(level, levelLabel) && levelLabel.color.a > 0 && levelLabel.string !== '' }]
  })
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
    keepCenterLocal,
    keepBounds,
    keepDrawable,
    buildings,
    expectedBuildings,
    buildingDataReady: Array.isArray(cityView?.panel?.rows) && (cityView?.gridTiles?.length ?? 0) > 0,
    buildingFootSource: artFamilyEntry?.[0] ?? null,
    viewport: { width: visible.width, height: visible.height },
  }
}, KEEP_NAME)

/** 真鼠标点 HUD 键，坐标由仓库共用的相机往返/引擎 hitTest 验证。 */
const tapNode = async (name) => {
  await hideGuideOverlay(page, 120)
  const point = await clickNodeViaCocos(page, { name, within: 'city' })
  if (point.clicked !== true) return false
  await page.waitForTimeout(220)
  return true
}

let failed = 0
const report = (label, ok, detail) => {
  if (!ok) failed += 1
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail === undefined ? '' : `：${detail}`}`)
}
const near = (actual, expected, slack = 0.02) => Math.abs(actual - expected) <= slack

/** 以本轮面板生产行与实际 plate 配对，校验每一栋楼的名字/等级，不用“至少 N 栋”替代覆盖。 */
const buildingSourceFailures = (snapshot) => {
  if (!snapshot.buildingDataReady || snapshot.expectedBuildings.length === 0) return ['生产城建行或格位元数据缺失']
  const failures = []
  const expected = snapshot.expectedBuildings
  const live = new Map(snapshot.buildings.map(building => [building.tile, building]))
  if (expected.length !== snapshot.buildings.length || live.size !== snapshot.buildings.length
    || new Set(expected.map(building => building.tile)).size !== expected.length) failures.push('生产城建集合与真实建筑格位未一一覆盖')
  for (const row of expected) {
    const building = live.get(row.tile)
    if (!row.tile || !building) { failures.push(`${row.configId}: 生产建筑缺少真实绘制格位`); continue }
    if (building.configId !== row.configId || building.name !== row.name || building.level !== row.level) {
      failures.push(`${row.tile}: 名字/等级未对应生产行（${building.name} ${building.level}，应为${row.name} ${row.level}）`)
    }
  }
  return failures
}

/**
 * 同一栋楼前后两次实测的框/文字中心与盒子尺寸，都必须服从它自己的脚面与实测 zoom。
 * 不检查 parent；负控移到固定面板后仍送进这一条，必须由真正的投影偏差判红。
 */
const attachmentFailures = (before, after, slack = 1.5) => {
  const failures = [...buildingSourceFailures(before), ...buildingSourceFailures(after)]
  if (before.buildings.length === 0) return ['没有可测的真实建筑']
  const ratio = after.zoom / before.zoom
  if (!Number.isFinite(ratio) || ratio <= 0) return ['缩放读数无效']
  const live = new Map(after.buildings.map(building => [building.tile, building]))
  for (const old of before.buildings) {
    const current = live.get(old.tile)
    if (!current?.foot || !old.foot) { failures.push(`${old.tile}: 真实脚面缺失`); continue }
    if (old.footVerified !== true || current.footVerified !== true) {
      failures.push(`${old.tile}: 脚面相机投影往返校验失败`); continue
    }
    if (old.footGeometryValid !== true || current.footGeometryValid !== true) {
      failures.push(`${old.tile}: 实体脚面未贴合基座、trim或生产素材脚面元数据错误`); continue
    }
    if (![old, current].every(building => building.frameDrawable && building.nameDrawable && building.levelDrawable)) {
      failures.push(`${old.tile}: 框/名字/等级未登记绘制`); continue
    }
    for (const key of ['frame', 'nameBox', 'levelBox']) {
      const a = old[key], b = current[key]
      if (!a || !b) { failures.push(`${old.tile}.${key}: 投影盒缺失`); continue }
      if (a.verified !== true || b.verified !== true) {
        failures.push(`${old.tile}.${key}: 相机投影往返校验失败`); continue
      }
      const drift = Math.hypot((b.x - current.foot.x) - (a.x - old.foot.x) * ratio,
        (b.y - current.foot.y) - (a.y - old.foot.y) * ratio)
      const sizeDrift = Math.max(Math.abs(b.width - a.width * ratio), Math.abs(b.height - a.height * ratio))
      if (!Number.isFinite(drift) || !Number.isFinite(sizeDrift) || drift > slack || sizeDrift > slack) {
        failures.push(`${old.tile}.${key}: 脚面相对偏差=${drift.toFixed(2)}px，盒尺寸偏差=${sizeDrift.toFixed(2)}px`)
      }
    }
  }
  return failures
}
const followEvidence = []
const reportFollow = (label, before, after) => {
  const failures = attachmentFailures(before, after)
  followEvidence.push({ phase: label, before, after, failures })
  report(label, failures.length === 0,
    `${before.buildings.length} 栋，zoom ${before.zoom} → ${after.zoom}${failures.length ? `；${failures.slice(0, 3).join('；')}` : '；框/名字/等级均跟随脚面'}`)
  return failures
}
const drag = async (dx, dy) => {
  await hideGuideOverlay(page, 120)
  await page.mouse.move(700, 450)
  await page.mouse.down()
  await page.mouse.move(700 + dx, 450 + dy, { steps: 6 })
  await page.mouse.up()
  await page.waitForTimeout(300)
}

const first = await readView()
if (first.found !== true) {
  console.error(`[city-zoom][前置] 读不到城景舞台：${JSON.stringify(first)}`)
  await browser.close()
  await preview.close()
  process.exit(2)
}
console.log(`[city-zoom] 视口 ${first.viewport.width}x${first.viewport.height}，36 格落点读到 ${first.tileCount} 个`)
console.log(`[city-zoom] 后端 ${BACKEND}，设备号=${deviceId}，期望真实建筑至少 ${EXPECT_BUILDINGS} 栋`)

// ---------- 第 1 相：默认就是放大 + 对准主堡 ----------
report('36 格都建出来了', first.tileCount === 36, `实测 ${first.tileCount}`)
report('真实建筑数量满足本轮验收前提', first.buildings.length >= EXPECT_BUILDINGS,
  `${first.buildings.length}/${EXPECT_BUILDINGS} 栋（满城复用 CITY_ZOOM_DEVICE 与 CITY_ZOOM_EXPECT_BUILDINGS=15）`)
const sourceFailures = buildingSourceFailures(first)
report('每一栋生产建筑的真实格位、名字与中文等级一一对应', sourceFailures.length === 0,
  sourceFailures.length ? sourceFailures.join('；') : `${first.buildings.length} 栋与生产城建集合完整对应`)
report('生产素材的真实实体脚面贴合每栋建筑基座（≤0.5屏幕px，trim=false）',
  first.buildingFootSource !== null && first.buildings.length > 0 && first.buildings.every(building => building.footGeometryValid),
  `脚面源=${first.buildingFootSource}；${first.buildings.map(building => `${building.name}:${JSON.stringify(building.footMetadata)}`).join('；')}`)
report('每栋真实建筑的薄框、名字与等级都登记绘制', first.buildings.length > 0
  && first.buildings.every(building => building.frameDrawable && building.nameDrawable && building.levelDrawable),
  first.buildings.map(building => `${building.name}:框=${building.frameDrawable}/名=${building.nameDrawable}/级=${building.levelDrawable}`).join('；'))
report('薄框与文字的投影坐标通过相机往返校验', first.buildings.length > 0
  && first.buildings.every(building => building.footVerified
    && ['frame', 'nameBox', 'levelBox'].every(key => building[key]?.verified === true)),
  `${first.buildings.length} 栋，框/名字/等级各四角与中心往返偏差≤0.5世界单位`)
report(`默认缩放 = 源码常量 ${ZOOM_DEFAULT}`, near(first.zoom, ZOOM_DEFAULT), `实测 ${first.zoom}`)
/**
 * 镜头对准主堡 —— 判的是**夹取之后的精确落点**，不是"必须正中"。
 *
 * <p>镜头目标来自主堡实际 Sprite 的主体中心，按铺满视口的夹取公式复算；
 * 用旧塔楼热区或只对准基座，都会导致这一条或下面的完整主体边界判红。
 */
const clamp = (value, limit) => Math.max(-limit, Math.min(limit, value))
const maxX = first.viewport.width * (first.zoom - 1) / 2
const maxY = first.viewport.height * (first.zoom - 1) / 2
const expectedX = first.keepCenterLocal === null ? Number.NaN : clamp(-first.keepCenterLocal.x * first.zoom, maxX)
const expectedY = first.keepCenterLocal === null ? Number.NaN : clamp(-first.keepCenterLocal.y * first.zoom, maxY)
report('镜头对准主堡主体中心（= 夹取公式算出的落点，±1px）',
  first.keepCenterLocal !== null
  && Math.abs(first.stageX - expectedX) <= 1 && Math.abs(first.stageY - expectedY) <= 1,
  first.keepLocal === null ? '屏上找不到「主城」那一格'
    : `stage=(${first.stageX}, ${first.stageY}) 期望=(${expectedX.toFixed(1)}, ${expectedY.toFixed(1)})`)
report('真实主堡正稿登记在可见相机层', first.keepDrawable, JSON.stringify(first.keepBounds))
report('默认镜头中的主堡主体完整进入视口', first.keepBounds !== null
  && first.keepBounds.left >= -first.viewport.width / 2 - 1
  && first.keepBounds.right <= first.viewport.width / 2 + 1
  && first.keepBounds.bottom >= -first.viewport.height / 2 - 1
  && first.keepBounds.top <= first.viewport.height / 2 + 1, JSON.stringify(first.keepBounds))
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
reportFollow('缩小键之后薄框与文字随建筑同比例移动/缩小', first, zoomedOut)
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
reportFollow('放大键之后薄框与文字随建筑同比例移动/放大', zoomedOut, beforeDrag)
// 真拖一段。拖动本身必须真的移动了舞台 —— 否则后面"没回中"量的是一个没动过的东西
await drag(130, 30)
const dragged = await readView()
const movedByDrag = Math.hypot(dragged.stageX - beforeDrag.stageX, dragged.stageY - beforeDrag.stageY)
report('单指拖动真的平移了镜头（> 20 设计px）', movedByDrag > 20,
  `位移 ${movedByDrag.toFixed(1)}（(${beforeDrag.stageX}, ${beforeDrag.stageY}) → (${dragged.stageX}, ${dragged.stageY})）`)
reportFollow('真鼠标拖动之后薄框与文字仍贴着各自建筑脚面', beforeDrag, dragged)
await page.screenshot({ path: path.join(OUT, 'city-zoom-drag.png') })
// 面板每秒重画一次（`update` 里的秒级重绘），等两秒就是两次 renderGrid：镜头不许被抢回主堡
await page.waitForTimeout(2200)
const afterTick = await readView()
reportFollow('秒级数据刷新之后薄框与文字仍贴着各自建筑脚面', dragged, afterTick)
report('数据刷新后镜头仍在玩家停的地方（没有自动回中）',
  Math.abs(afterTick.stageX - dragged.stageX) <= 1 && Math.abs(afterTick.stageY - dragged.stageY) <= 1,
  `拖后 (${dragged.stageX}, ${dragged.stageY}) → 刷新后 (${afterTick.stageX}, ${afterTick.stageY})`)

// 同页负控：临时把一个名字移到固定面板、保持当前世界变换，再真拖镜头。
// 不能以“名字不在父节点里”为失败理由；readView 继续投影它的真实 reference。
const detached = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let grid = null, card = null
  const walk = node => {
    if (node.name === 'CityGrid') grid = node
    if (node.name === 'city') card = node.getChildByName('Card')
    for (const child of node.children) walk(child)
  }
  walk(scene)
  const tile = grid?.children.find(node => node.getChildByName('BuildingIcon')?.activeInHierarchy
    && node.getChildByName('Name')?.getComponent('cc.Label')?.string)
  const name = tile?.getChildByName('Name') ?? null
  if (!name || !card) return false
  globalThis.__cityZoomDetachedName = { node: name, tileUuid: tile.uuid, parent: name.parent,
    position: name.position.clone(), scale: name.scale.clone(), rotation: name.rotation.clone(), sibling: name.getSiblingIndex() }
  name.setParent(card, true)
  return true
})
report('负控前置：真实名字保世界变换移到固定面板', detached)
if (detached) {
  const frozen = await readView()
  const moved = attachmentFailures(afterTick, frozen)
  report('负控前置：移父节点当下没有改变文字屏幕几何', moved.length === 0, moved.slice(0, 3).join('；'))
  await drag(-90, -30)
  const broken = await readView()
  const deviations = attachmentFailures(frozen, broken)
  const footMoved = frozen.buildings.some(old => {
    const live = broken.buildings.find(building => building.tile === old.tile)
    return old.foot && live?.foot && Math.hypot(live.foot.x - old.foot.x, live.foot.y - old.foot.y) > 20
  })
  followEvidence.push({ phase: '负控：固定面板名字必须被同一几何门抓住', before: frozen, after: broken, failures: deviations })
  report('负控：真拖后建筑脚面确实移动', footMoved)
  report('负控：同一跟随判据抓到名字脱离（必须红）',
    deviations.some(failure => failure.includes('.nameBox: 脚面相对偏差=')), deviations.slice(0, 3).join('；'))
  await page.screenshot({ path: path.join(OUT, 'city-zoom-negative-fixed-name.png') })
  await page.evaluate(() => {
    const saved = globalThis.__cityZoomDetachedName
    saved.node.setParent(saved.parent)
    saved.node.setPosition(saved.position)
    saved.node.setScale(saved.scale)
    saved.node.setRotation(saved.rotation)
    saved.node.setSiblingIndex(saved.sibling)
    delete globalThis.__cityZoomDetachedName
  })
  const restored = await readView()
  await drag(90, 30)
  const restoredDrag = await readView()
  const restoredMovement = Math.max(0, ...restored.buildings.map(old => {
    const live = restoredDrag.buildings.find(building => building.tile === old.tile)
    return old.foot && live?.foot ? Math.hypot(live.foot.x - old.foot.x, live.foot.y - old.foot.y) : 0
  }))
  report('还原负控后再次真拖，实体脚面确实移动（>20屏幕px）', restoredMovement > 20,
    `实体脚面位移 ${restoredMovement.toFixed(2)}px`)
  reportFollow('还原负控后再次真拖，薄框与文字跟随判据恢复绿', restored, restoredDrag)
  await page.screenshot({ path: path.join(OUT, 'city-zoom-restored-follow.png') })
}

// ---------- 第 4 相：放大键与上限夹取 ----------
for (let i = 0; i < 12; i += 1) {
  await tapNode('ZoomInButton')
}
const zoomedIn = await readView()
reportFollow('放大到上限之后薄框与文字保持脚面相对比例', afterTick, zoomedIn)
report(`放大到上限 ${ZOOM_MAX}（夹取生效）`, near(zoomedIn.zoom, ZOOM_MAX), `实测 ${zoomedIn.zoom}`)
await tapNode('ZoomOutButton')
const oneStep = await readView()
reportFollow('上限缩小一步之后薄框与文字保持脚面相对比例', zoomedIn, oneStep)
report(`放大键与缩小键都按步长 ${ZOOM_STEP} 走`, near(oneStep.zoom, ZOOM_MAX - ZOOM_STEP, 0.03),
  `实测 ${oneStep.zoom}（期望 ${(ZOOM_MAX - ZOOM_STEP).toFixed(2)}）`)
await page.screenshot({ path: path.join(OUT, 'city-zoom-max.png') })

// ---------- 第 5 相：真实滚轮缩放也走同一条跟随判据 ----------
await hideGuideOverlay(page, 120)
await page.mouse.move(700, 450)
await page.mouse.wheel(0, -120)
await page.waitForTimeout(350)
const wheeled = await readView()
report('真鼠标滚轮改变缩放倍数', !near(wheeled.zoom, oneStep.zoom), `${oneStep.zoom} → ${wheeled.zoom}`)
reportFollow('真滚轮缩放之后薄框与文字保持脚面相对比例', oneStep, wheeled)
await page.screenshot({ path: path.join(OUT, 'city-zoom-wheel.png') })
writeFileSync(path.join(OUT, 'city-nameplate-follow.json'), JSON.stringify({
  backend: BACKEND, deviceId, expectedBuildings: EXPECT_BUILDINGS, initial: first, followEvidence, errors,
}, null, 2))

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
