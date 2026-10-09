/**
 * 连续建造两栋的实机验收：真实点击建造、空地和选择器，核对成功回执与权威城池，再验正稿。
 * 依赖 client/build/web-mobile、本轮自己的 BACKEND_ORIGIN 后端与 Playwright。
 * 当前契约的 lumber_camp、quarry 均允许主城 1 级首次建造；名称、选项、坐标与完成时刻现读服务端/场景。
 * 不直接发建造请求，不以初始队列 0/N 或隐藏 ChoiceOverlay 的默认 label 当成功。
 * 判据：两次 HTTP 200/code 0、对应实例和队列落库、两栋 Lv1/队列归零、真实 Sprite 登记与可见层，
 * 主城和已建建筑都画真实正稿、未建格不画。只建不拆、不加速。退出码：0 全绿；1 判据失败；2 缺产物。
 */
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { clickNodeViaCocos, resolveCocosClickPoint } from './lib/cocos-click.mjs'

const ROOT = 'client/build/web-mobile'
const OUT = 'client/build/art-verify/cc-audit'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.CITY_MANY_PORT ?? 8212)
const TARGETS = ['lumber_camp', 'quarry']
console.log(`[build-many] 后端 ${BACKEND} 预览端口 ${PORT}`)
if (!existsSync(path.resolve(ROOT, 'index.html'))) {
  console.error('[build-many][前置] 产物不存在（先跑 scripts/build-webmobile.sh）')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

let preview, browser, page, session, initialCity, finalCity
let queueZero = false
const errors = [], receipts = [], failures = []
let tiles = [], finalTexts = []
const assert = (ok, message) => { if (!ok) throw new Error(message) }
const isPost = (response, endpoint) =>
  new URL(response.url()).pathname === endpoint && response.request().method() === 'POST'

async function readCity() {
  const response = await fetch(`${BACKEND}/city/list`, {
    headers: { 'X-Player-Id': session.playerId,
      ...(session.authToken ? { Authorization: `Bearer ${session.authToken}` } : {}) },
    signal: AbortSignal.timeout(15_000),
  })
  const body = await response.json()
  assert(response.status === 200 && body.code === 0, `权威 city/list 失败：HTTP ${response.status} ${JSON.stringify(body)}`)
  return body.data
}

const clearGuide = () => page.evaluate(() => {
  const visit = node => {
    const label = node.getComponent('cc.Label')
    if (/Guide/i.test(node.name) || /第\s*\d+\s*\/\s*\d+\s*步|我完成了|升级主城：/.test(label?.string ?? '')) {
      const view = node.getComponent('GuideView')
      if (view) view.enabled = false
      node.removeFromParent()
      return 1
    }
    return node.children.reduce((sum, child) => sum + visit(child), 0)
  }
  return visit(window.cc.director.getScene())
})

async function click(name, within) {
  const point = await clickNodeViaCocos(page, { name, within })
  console.log(`[build-many] 真点击 ${within}/${name}：${JSON.stringify(point)}`)
  assert(point.clicked === true, `点击量具未通过：${within}/${name} ${point.reason}`)
  return point
}

async function loadCity(reload = false) {
  const login = page.waitForResponse(response => isPost(response, '/player/init'), { timeout: 25_000 })
    .then(response => ({ response }), error => ({ error }))
  if (reload) await page.reload({ waitUntil: 'networkidle' })
  else await page.goto(`${preview.origin}/?panel=city`, { waitUntil: 'networkidle' })
  const result = await login
  assert(result.response, `没有登录回执：${result.error?.message}`)
  const body = await result.response.json()
  assert(result.response.status() === 200 && body.code === 0 && body.data?.playerId, `登录失败：${JSON.stringify(body)}`)
  assert(!session || session.playerId === body.data.playerId, '重载后玩家身份改变')
  session = body.data
  await page.waitForFunction(() => {
    const game = window.cc?.director?.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
    return game?.getComponent('PanelNav')?.currentKey === 'city'
      && game.getChildByName('city')?.getComponent('CityPanelView')?.resp?.buildings?.length > 0
  }, null, { timeout: 25_000 })
  console.log('[build-many] 清引导层：', await clearGuide())
  // 登录刷新可能出现升级礼包；只能走真实关闭入口，不能隐藏渲染器绕过模态输入。
  const giftActive = await page.evaluate(() => window.cc.director.getScene()
    ?.getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('giftPopup')?.activeInHierarchy === true)
  if (giftActive) {
    await click('close', 'giftPopup')
    await page.waitForFunction(() => window.cc.director.getScene()
      ?.getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('giftPopup')?.activeInHierarchy === false,
    null, { timeout: 5000 })
  }
  await page.waitForTimeout(500)
}

async function buildOne(configId) {
  const before = await readCity()
  const option = before.buildOptions.find(row => row.configId === configId)
  assert(option?.name, `服务端没有本轮合法可建项：${configId}`)
  assert(!before.buildings.some(row => row.configId === configId), `首次建造前已有实例：${configId}`)
  await page.waitForFunction(id => {
    const city = window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
      ?.getChildByName('city')?.getComponent('CityPanelView')
    return city?.resp?.buildOptions?.some(row => row.configId === id)
  }, configId, { timeout: 5000 })
  await click('DetailBuildButton', 'city')

  // 取实际 gridTiles 的空地与投影坐标，不按旧数组下标猜地块；只选引擎自命中成立的可视空地。
  const empty = await page.evaluate(() => {
    const city = window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
      ?.getChildByName('city')?.getComponent('CityPanelView')
    return (city?.gridTiles ?? []).filter(tile => tile.node.activeInHierarchy && tile.levelLabel.string === '')
      .map(tile => ({ tile: tile.node.name, gridX: tile.plate.gridX, gridY: tile.plate.gridY }))
  })
  const rect = await page.locator('canvas').evaluate(canvas => {
    const r = canvas.getBoundingClientRect()
    return { x: r.left, y: r.top, width: r.width, height: r.height }
  })
  const candidates = []
  for (const tile of empty) {
    const point = await page.evaluate(resolveCocosClickPoint, { name: tile.tile, within: 'CityGrid' })
    // HUD 在上下边缘，缩放按钮在角落；候选落点留在城景中部，避免把 UI 遮挡当网格点击。
    if (point.verified && point.x > rect.x + rect.width * 0.2 && point.x < rect.x + rect.width * 0.8
        && point.y > rect.y + rect.height * 0.28 && point.y < rect.y + rect.height * 0.72) {
      candidates.push({ ...tile, distance: Math.hypot(point.x - rect.x - rect.width / 2, point.y - rect.y - rect.height / 2) })
    }
  }
  const tile = candidates.sort((a, b) => a.distance - b.distance)[0]
  assert(tile, '没有可自命中且位于城景中部的空地')
  await click(tile.tile, 'CityGrid')
  await page.waitForFunction(() => window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('city')?.getComponent('CityPanelView')?.buildPicker?.node?.activeInHierarchy === true,
  null, { timeout: 5000 })

  // 多个 ChoiceOverlay 同名。保存真实 buildPicker 节点引用再临时命名，finally 恢复即使已离开场景的旧节点。
  const scope = `CityBuildManyPicker-${receipts.length + 1}`
  const pageCount = await page.evaluate(scope => {
    const picker = window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
      ?.getChildByName('city')?.getComponent('CityPanelView')?.buildPicker
    if (!picker?.node?.activeInHierarchy || !picker.optionNodes?.length) return 0
    window.cityBuildManyPickerScope = { picker, node: picker.node, originalName: picker.node.name, scope }
    picker.node.name = scope
    return Math.ceil(picker.options.length / picker.optionNodes.length)
  }, scope)
  let result, selected
  try {
    assert(pageCount > 0, '实际建造选择器没有活跃选项')
    for (let i = 0; i < pageCount; i++) {
      selected = await page.evaluate(({ scope, configId }) => {
        const saved = window.cityBuildManyPickerScope
        if (saved?.scope !== scope || !saved.node.activeInHierarchy) return null
        const picker = saved.picker
        const index = picker.options.findIndex(row => row.id === configId)
        const visible = index - picker.page * picker.optionNodes.length
        const row = picker.optionNodes[visible]
        if (index < 0 || visible < 0 || visible >= picker.optionNodes.length || !row?.activeInHierarchy) return null
        return { node: row.name, label: picker.optionTitleLabels[visible]?.string, configId: picker.options[index].id }
      }, { scope, configId })
      if (selected) break
      if (i + 1 < pageCount) {
        await click('ChoiceNext', scope)
        await page.waitForTimeout(150)
      }
    }
    assert(selected?.label === option.name, `实际选择器没有该服务端选项：${JSON.stringify({ option, selected })}`)
    const response = page.waitForResponse(response => {
      if (!isPost(response, '/city/upgrade')) return false
      try { return response.request().postDataJSON()?.configId === configId } catch { return false }
    }, { timeout: 10_000 }).then(response => ({ response }), error => ({ error }))
    await click(selected.node, scope)
    result = await response
  } finally {
    await page.evaluate(scope => {
      const saved = window.cityBuildManyPickerScope
      if (saved?.scope === scope) {
        saved.node.name = saved.originalName
        delete window.cityBuildManyPickerScope
      }
    }, scope)
  }
  assert(result?.response, `真实选择后没有 city/upgrade 回执：${result?.error?.message}`)
  const response = result.response
  const body = await response.json(), request = response.request().postDataJSON()
  const after = await readCity()
  const building = after.buildings.find(row => row.configId === configId)
  const receipt = { configId, name: option.name, tile, selected, request, httpStatus: response.status(),
    response: body, building, queues: after.queues, serverNow: after.serverNow }
  receipts.push(receipt)
  console.log(`[build-many] 真实建造回执与权威实例：${JSON.stringify(receipt)}`)
  assert(response.status() === 200 && body.code === 0, `建造业务失败：${JSON.stringify(body)}`)
  assert(request.gridX === tile.gridX && request.gridY === tile.gridY, '实际请求与被点击地块不一致')
  assert(building?.id === body.data.buildingId && building.gridX === tile.gridX && building.gridY === tile.gridY,
    '成功回执没有对应权威实例与放置坐标')
  assert(after.queues.used === after.buildings.filter(row => row.status === 'UPGRADING').length, '权威队列与升级中实例数不一致')
  assert(building.status === 'UPGRADING' ? building.finishAt === body.data.finishAt && after.queues.used > 0
    : building.level === 1 && building.status === 'IDLE', '首次建造未进入队列或完成态')
  await page.waitForFunction(id => window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('city')?.getComponent('CityPanelView')?.resp?.buildings?.some(row => row.id === id),
  building.id, { timeout: 5000 })
}

const texts = () => page.evaluate(() => {
  const out = []
  const visit = node => {
    if (!node.activeInHierarchy) return
    const label = node.getComponent('cc.Label')
    if (label?.string) out.push(label.string)
    node.children.forEach(visit)
  }
  visit(window.cc.director.getScene().getChildByName('Canvas'))
  return out
})

async function run() {
  try {
    preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
    browser = await chromium.launch({ headless: true })
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 })
    await context.addInitScript(deviceId => localStorage.setItem('ironoath.deviceId', deviceId), `buildmany-${Date.now()}`)
    page = await context.newPage()
    page.on('pageerror', error => errors.push(error.message))
    await loadCity()
    initialCity = await readCity()
    assert(initialCity.buildings.length === 1 && initialCity.buildings[0].configId === 'main_city', '不是仅有主城的本轮新号')
    for (const configId of TARGETS) await buildOne(configId)
    await page.screenshot({ path: path.join(OUT, '08-two-buildings-queued.png') })
    // 必须先有两次成功放置。用权威 city/list 惰性收割到期建筑，初始的 0/N 永远不能通过本判据。
    for (let i = 0; i <= 12; i++) {
      finalCity = await readCity()
      const completed = TARGETS.every(id => finalCity.buildings.some(row => row.configId === id && row.level === 1 && row.status === 'IDLE'))
      console.log(`[build-many] 第 ${i + 1} 次权威检查：queue=${finalCity.queues.used}/${finalCity.queues.available} ${JSON.stringify(finalCity.buildings)}`)
      if (receipts.length === 2 && completed && finalCity.queues.used === 0) { queueZero = true; break }
      if (i < 12) await page.waitForTimeout(5000)
    }
    assert(queueZero, '两次成功建造后未在 60 秒内到达两栋 Lv1/队列归零')
    await loadCity(true)
    await page.waitForFunction(ids => {
      const city = window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
        ?.getChildByName('city')?.getComponent('CityPanelView')
      return ids.every(id => city?.resp?.buildings?.some(row => row.configId === id && row.level === 1))
        && (city.gridTiles ?? []).filter(tile => tile.nameLabel.string && tile.icon.activeInHierarchy)
          .every(tile => tile.icon.getComponent('cc.Sprite')?.spriteFrame)
    }, TARGETS, { timeout: 10_000 }).catch(() => {})
    finalCity = await readCity()
    finalTexts = await texts()
    tiles = await page.evaluate(() => {
      const scene = window.cc.director.getScene()
      const city = scene.getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('city')?.getComponent('CityPanelView')
      const cameras = scene.getComponentsInChildren('cc.Camera')
      return (city?.gridTiles ?? []).map(tile => {
        const icon = tile.icon, sprite = icon.getComponent('cc.Sprite')
        const sameLayer = icon.layer === tile.node.layer && icon.layer === city.node.layer
        const cameraVisible = cameras.some(camera => camera.enabled && camera.node.activeInHierarchy && (camera.visibility & icon.layer) !== 0)
        const rendererRegistered = !!sprite && icon._uiProps?.uiComp === sprite
        const opaque = !sprite || sprite.color.a > 0
        let ancestorOpacity = true
        for (let node = icon; node; node = node.parent) {
          if (node.getComponent('cc.UIOpacity')?.opacity === 0) ancestorOpacity = false
        }
        const iconActive = icon.activeInHierarchy === true
        const hasSprite = !!sprite?.spriteFrame
        const drawable = iconActive && hasSprite && sprite.enabled && rendererRegistered && sameLayer && cameraVisible && opaque && ancestorOpacity
        return { tile: tile.node.name, gridX: tile.plate.gridX, gridY: tile.plate.gridY,
          level: tile.levelLabel.string, name: tile.nameLabel.string, iconActive, hasSprite,
          spriteFrame: sprite?.spriteFrame?.name ?? null, rendererRegistered, spriteEnabled: sprite?.enabled ?? false,
          layer: icon.layer, sameLayer, cameraVisible, opaque, ancestorOpacity, drawable }
      })
    })
    await page.screenshot({ path: path.join(OUT, '09-two-buildings-done.png') })
    console.log('[build-many] 最终屏上文本：', finalTexts.join(' | '))
    for (const tile of tiles.filter(tile => tile.level || tile.iconActive)) console.log(`[build-many] 建筑表现：${JSON.stringify(tile)}`)
    assert(finalCity.buildings.length === 3 && finalCity.queues.used === 0, '权威最终城池不是主城加两栋/队列归零')
    for (const configId of ['main_city', ...TARGETS]) {
      const building = finalCity.buildings.find(row => row.configId === configId)
      const tile = tiles.find(row => row.gridX === building?.gridX && row.gridY === building?.gridY)
      assert(tile?.name === building?.name && tile.level === `Lv${building.level}`, `权威实例与显示格不一致：${configId} ${JSON.stringify(tile)}`)
      assert(tile.drawable && tile.spriteFrame === `building-${configId.replaceAll('_', '-')}-v1`,
        `真实建筑的正稿未登记/未在可见层绘制：${configId} ${JSON.stringify(tile)}`)
      if (configId !== 'main_city') assert(building.level === 1, `首次建筑不是 Lv1：${configId}`)
    }
    const empty = tiles.filter(tile => !finalCity.buildings.some(row => row.gridX === tile.gridX && row.gridY === tile.gridY))
    assert(empty.length > 0 && empty.every(tile => !tile.iconActive && !tile.drawable && !tile.level && !tile.name), '未建格出现建筑表现或空地判据走不到')
    assert(errors.length === 0, `页面报错 ${errors.length} 条：${errors[0]}`)
    console.log('[build-many] 全绿：两次真实建造业务成功、两栋 Lv1 与主城正稿均登记绘制、未建不画、权威队列归零')
  } catch (error) {
    failures.push(error.stack ?? String(error))
    console.error(`[build-many] 判据失败：${error.message}`)
    process.exitCode = 1
    if (page && !page.isClosed()) await page.screenshot({ path: path.join(OUT, 'multi-building-failure.png') }).catch(() => {})
  } finally {
    try {
      writeFileSync(path.join(OUT, 'multi-building-report.json'), JSON.stringify({ backend: BACKEND,
        playerId: session?.playerId, initialCity, receipts, finalCity, queueZero, tiles, texts: finalTexts,
        pageErrors: errors, failures }, null, 2))
    } finally {
      try { await browser?.close() } finally { await preview?.close() }
    }
  }
}

await run()
