/**
 * 新号真实玩家链：两次主城升级 → 引导 HOLD/推进 → 任务领取 → 三选一送将 → 引导第3步。
 * 不写 Store/GuideState，不 emit，不调用业务方法；正控只通过真实鼠标操作生产节点。
 * 唯一植入是负控临时还原旧420宽右上气泡：同一领取判据必须红，原位还原后必须绿。
 * 依赖：本轮 web-mobile 产物、普通后端（新号主城1级）、Playwright；不需要 ops 权限。
 * 短比例 Web 使用1688×780 CSS视口，与原生844×390同宽高比，实际设计区960×443.60。
 * Web宽度保持≥900，生产窄窗guard照常保留并读display；844原生像素必须另走微信真实验收。
 * 退出码：0 全绿；1 判据/真实操作失败；2 产物、后端或点击量具前提不足。
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { clickNodeViaCocos, resolveCocosClickPoint } from './lib/cocos-click.mjs'

const ROOT = 'client/build/web-mobile'
// runner 按首次出现的 BACKEND/PORT 环境变量识别；这两项必须在其他环境声明之前。
const BACKEND = process.env.BACKEND_ORIGIN
const PORT = Number(process.env.GUIDE_PLAYER_PORT ?? 8312)
const OUT = process.env.GUIDE_PLAYER_OUT ?? 'client/build/guide-player-verify'
const SHORT_VIEWPORT = { width: 1688, height: 780 }
const HELD_TEXT = '尚未完成，请先完成当前操作'
const RUN = Date.now()
const deviceId = `guide-player-${RUN}`
const checks = [], evidence = [], requests = [], responses = [], errors = []
let browser, preview, page, playerId, exitCode = 0

class Prerequisite extends Error {}
const requireThat = (ok, message) => { if (!ok) throw new Prerequisite(message) }
const report = (name, pass, detail) => {
  checks.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'} ${name}${detail === undefined ? '' : `：${typeof detail === 'string' ? detail : JSON.stringify(detail)}`}`)
}
const must = (name, pass, detail) => {
  report(name, pass, detail)
  if (!pass) throw new Error(name)
}
const near = (a, b, tolerance = 0.6) => Number.isFinite(a) && Number.isFinite(b) && Math.abs(a - b) <= tolerance
const intersects = (a, b) => !!a && !!b && Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1
  && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1
const contains = (outer, inner) => !!outer && !!inner && inner.verified && inner.left >= outer.left - 1
  && inner.top >= outer.top - 1 && inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1

/** 3.8.7 SHRINK 在原始栅格字号上取整；wrap 二分的 right 是 (初始字号+1)|0。 */
function guideFontMetrics(label, target, maxFont) {
  const validScale = !!label && Number.isFinite(label.fontScale) && label.fontScale > 0
    && Number.isFinite(maxFont) && maxFont > 0 && Number.isFinite(target) && target > 0
  let effectiveScale = validScale ? label.fontScale : NaN
  if (effectiveScale * target > maxFont && target < maxFont) effectiveScale = maxFont / target
  effectiveScale = Math.max(1, effectiveScale)
  const rawMin = Math.floor(target * effectiveScale)
  const rawMax = rawMin + (label?.wrap === true ? 1 : 0)
  return { configured: label?.fontSize, target, fontScale: label?.fontScale, effectiveScale,
    rawActual: label?.actualFontSize, rawMin, rawMax, normalized: label?.actualFontSize / effectiveScale,
    validScale, pass: validScale && label.fontSize === target && label.overflow === 2
      && typeof label.wrap === 'boolean' && Number.isInteger(label.actualFontSize)
      && label.actualFontSize >= rawMin && label.actualFontSize <= rawMax }
}

/** 按真实自身盒判读；正文、表头与每颗文字都必须在气泡内，文字还必须在其按钮框内。 */
function guideTextBoxesIssues(guide) {
  const issues = []
  const buttons = [guide.next, guide.skip].filter(button => button?.active)
  const labels = [{ role: '步数/提示', box: guide.position?.box }, { role: '正文', box: guide.body?.box },
    ...buttons.map(button => ({ role: `${button.name}文字`, box: button.caption?.box }))]
  for (const label of labels) {
    if (!contains(guide.box, label.box)) issues.push(`${label.role}自身盒越出气泡`)
  }
  for (let index = 0; index < labels.length; index++) {
    for (const other of labels.slice(index + 1)) {
      if (intersects(labels[index].box, other.box)) issues.push(`${labels[index].role}与${other.role}自身盒重叠`)
    }
  }
  for (const button of buttons) {
    if (!contains(guide.box, button.box)) issues.push(`${button.name}按钮框越出气泡`)
    if (!contains(button.box, button.caption?.box)) issues.push(`${button.name}文字自身盒越出按钮框`)
    if (intersects(guide.body?.box, button.box)) issues.push(`正文与${button.name}按钮框重叠`)
    if (intersects(guide.position?.box, button.box)) issues.push(`步数/提示与${button.name}按钮框重叠`)
  }
  return issues
}

/** 场景只读：相机投影/往返、真实渲染登记、实际字号与引擎分行，不能用 string 存在代替画出来。 */
function readPlayerFrame() {
  const cc = window.cc, scene = cc?.director?.getScene?.()
  const game = scene?.getChildByName('Canvas')?.getChildByName('Game')
  if (!game) return { ready: false }
  const find = (root, name) => {
    if (!root) return null
    if (root.name === name) return root
    for (const child of root.children ?? []) { const hit = find(child, name); if (hit) return hit }
    return null
  }
  const nav = game.getComponent('PanelNav'), city = find(game, 'city'), quest = find(game, 'quest')
  const cityView = city?.getComponent('CityPanelView'), questView = quest?.getComponent('QuestPanelView')
  const guide = find(game, 'Guide'), bubble = find(guide, 'GuideBubble')
  const canvas = document.querySelector('canvas')?.getBoundingClientRect()
  const cameras = scene.getComponentsInChildren('cc.Camera')
  const cameraFor = node => cameras.find(cam => cam.enabled && cam.node.activeInHierarchy && (cam.visibility & node.layer) !== 0)
  const drawable = (node, component) => {
    if (!node?.activeInHierarchy || !component?.enabled || node._uiProps?.uiComp !== component || !cameraFor(node)) return false
    for (let ancestor = node; ancestor; ancestor = ancestor.parent) {
      if (ancestor.getComponent('cc.UIOpacity')?.opacity === 0) return false
    }
    return (component.color?.a ?? 255) > 0
  }
  const boxOf = node => {
    const ui = node?.getComponent('cc.UITransform'), cam = node && cameraFor(node)
    if (!ui || !cam || !canvas) return null
    const dpr = window.devicePixelRatio || 1
    const points = [[-ui.anchorX * ui.width, -ui.anchorY * ui.height],
      [(1 - ui.anchorX) * ui.width, -ui.anchorY * ui.height],
      [-ui.anchorX * ui.width, (1 - ui.anchorY) * ui.height],
      [(1 - ui.anchorX) * ui.width, (1 - ui.anchorY) * ui.height]].map(([x, y]) => {
      const world = ui.convertToWorldSpaceAR(new cc.Vec3(x, y, 0))
      const screen = cam.worldToScreen(new cc.Vec3(world.x, world.y, world.z))
      const back = cam.screenToWorld(new cc.Vec3(screen.x, screen.y, screen.z))
      return { x: canvas.left + screen.x / dpr, y: canvas.bottom - screen.y / dpr,
        drift: Math.max(Math.abs(back.x - world.x), Math.abs(back.y - world.y)) }
    })
    return { left: Math.min(...points.map(p => p.x)), right: Math.max(...points.map(p => p.x)),
      top: Math.min(...points.map(p => p.y)), bottom: Math.max(...points.map(p => p.y)),
      verified: points.every(p => Number.isFinite(p.x) && Number.isFinite(p.y) && p.drift <= 0.5) }
  }
  const labelOf = node => {
    const label = node?.getComponent('cc.Label'), ui = node?.getComponent('cc.UITransform')
    if (!label) return null
    const style = label.textStyle, layout = label.textLayoutData
    return { text: label.string, parsedString: [...(layout?.parsedString ?? [])],
      fontSize: label.fontSize, actualFontSize: label.actualFontSize, fontScale: style?.fontScale,
      lineHeight: label.lineHeight, width: ui?.width, height: ui?.height, overflow: label.overflow, wrap: label.enableWrapText,
      horizontalAlign: label.horizontalAlign, verticalAlign: label.verticalAlign,
      drawable: drawable(node, label), box: boxOf(node) }
  }
  const buttonOf = node => {
    if (!node) return null
    const sprite = node.getComponent('cc.Sprite'), graphics = node.getComponent('cc.Graphics')
    return { name: node.name, active: node.activeInHierarchy, box: boxOf(node),
      width: node.getComponent('cc.UITransform')?.width, height: node.getComponent('cc.UITransform')?.height,
      material: drawable(node, sprite) && !!sprite.spriteFrame,
      frameName: sprite?.spriteFrame?.name ?? null, graphicsOn: graphics?.enabled ?? false,
      caption: labelOf(node.getChildByName('caption') ?? node.getChildByName('Caption')) }
  }
  const main = cityView?.panel?.rows?.find(row => row.configId === 'main_city')
  const mainTile = (cityView?.gridTiles ?? []).find(tile => tile.plate.gridX === main?.gridX && tile.plate.gridY === main?.gridY)
  const rows = (questView?.drawnRows ?? []).filter(node => node.activeInHierarchy).map(node => ({
    questId: questView?.rowQuestIds?.get(node), title: labelOf(node.getChildByName('Title')),
    detail: labelOf(node.getChildByName('Detail')), status: labelOf(node.getChildByName('Status')),
    claim: buttonOf(node.getChildByName('ClaimButton')),
  }))
  const prompt = find(quest, 'HeroChoicePanel')
  const options = (prompt?.children ?? []).filter(node => node.name.startsWith('HeroOption_')).map(node => ({
    name: node.name, active: node.activeInHierarchy, box: boxOf(node), label: labelOf(node.getChildByName('Name')),
  }))
  const plate = find(bubble, 'GuideBubblePlate'), plateSprite = plate?.getComponent('cc.Sprite')
  const masks = (guide?.children ?? []).filter(node => node.name === 'GuideMask' && node.activeInHierarchy).map(boxOf)
  const navHeroBox = boxOf(find(game, 'Nav-hero'))
  const navPoint = navHeroBox ? { x: (navHeroBox.left + navHeroBox.right) / 2, y: (navHeroBox.top + navHeroBox.bottom) / 2 } : null
  const visible = cc.view.getVisibleSize()
  // installViewportGuard没有id；从其实际全屏按钮定位原生产DOM，不隐藏或改动它。
  const guardButton = [...document.querySelectorAll('button')].find(node => node.textContent === '进入全屏')
  const guard = guardButton?.parentElement
  const viewportGuard = { found: !!guard, display: guard ? getComputedStyle(guard).display : null,
    text: guard?.textContent ?? null, fullscreen: !!document.fullscreenElement }
  return { ready: !!nav, current: nav?.currentKey, viewport: { width: innerWidth, height: innerHeight },
    viewportGuard, sourceFields: { cityRows: Array.isArray(cityView?.panel?.rows), cityGridTiles: Array.isArray(cityView?.gridTiles),
      questDrawnRows: Array.isArray(questView?.drawnRows), questRowQuestIds: typeof questView?.rowQuestIds?.get === 'function' },
    visibleSize: { width: visible.width, height: visible.height }, scale: { x: cc.view.getScaleX(), y: cc.view.getScaleY() },
    canvas: canvas && { left: canvas.left, top: canvas.top, right: canvas.right, bottom: canvas.bottom }, dpr: window.devicePixelRatio,
    main: main && { id: main.id, name: main.name, level: main.level, upgrading: main.upgrading, collectable: main.collectable },
    mainTileName: mainTile?.node.name, selectedTitle: labelOf(find(city, 'SelectedTitle')),
    cityActions: ['DetailUpgradeButton', 'DetailCollectButton'].map(name => buttonOf(find(city, name))),
    guide: { active: guide?.activeInHierarchy === true, bubbleActive: bubble?.activeInHierarchy === true,
      width: bubble?.getComponent('cc.UITransform')?.width, height: bubble?.getComponent('cc.UITransform')?.height, box: boxOf(bubble),
      plateMaterial: drawable(plate, plateSprite) && !!plateSprite.spriteFrame,
      plateName: plateSprite?.spriteFrame?.name ?? null,
      body: labelOf(find(bubble, 'GuideStepText')), position: labelOf(find(bubble, 'GuideStepPosition')),
      next: buttonOf(find(bubble, 'GuideNext')), skip: buttonOf(find(bubble, 'GuideSkip')), masks,
      navBlocked: !!navPoint && masks.some(box => box && navPoint.x >= box.left && navPoint.x <= box.right
        && navPoint.y >= box.top && navPoint.y <= box.bottom) },
    rows, prompt: { active: prompt?.activeInHierarchy === true, box: boxOf(prompt),
      title: labelOf(find(prompt, 'PromptTitle')), options },
  }
}

try {
  requireThat(BACKEND && /^https?:\/\//.test(BACKEND), '必须指定本轮 BACKEND_ORIGIN')
  requireThat(Number.isInteger(PORT) && PORT > 0 && PORT < 65536, 'GUIDE_PLAYER_PORT 必须是有效端口')
  requireThat(existsSync(path.join(ROOT, 'index.html')), '产物不存在，先跑 scripts/build-webmobile.sh')
  const table = JSON.parse(readFileSync('contract/config/guide.json', 'utf8'))
  const step = index => table.rows.find(row => row.stepIndex === index)
  requireThat(table.version === 2 && [1, 2, 3].every(index => step(index)), '本探针需要 contract guide@2 的前三步')
  const ccFile = path.join(ROOT, 'cocos-js', 'cc.js')
  requireThat(existsSync(ccFile), '缺 Cocos 引擎产物，不能校准字号')
  const engine = readFileSync(ccFile, 'utf8')
  const maxFont = Number(engine.match(/_maxFontSize\s*=\s*(\d+)/)?.[1])
  requireThat(Number.isFinite(maxFont) && engine.includes('_getStyleFontScale')
    && engine.includes('_calculateShrinkFont') && /\.actualFontSize\s*\+\s*1/.test(engine), '当前引擎字号规则不可校准')
  const fontMetrics = (label, target = label?.fontSize) => guideFontMetrics(label, target, maxFont)
  const copyIssues = (label, expected, frame) => {
    const issues = []
    if (!label?.drawable || !contains(frame.canvas, label.box)) issues.push('文字未真实绘制或超出画布')
    if (label?.text !== expected || label?.parsedString?.join('').replace(/\s/g, '') !== expected.replace(/\s/g, '')) issues.push('引擎分行未保留全文')
    const metrics = fontMetrics(label)
    if (!metrics.validScale || !Number.isFinite(label?.actualFontSize) || label.actualFontSize < metrics.rawMin) issues.push('原始栅格实际字号缩水')
    if (label?.parsedString?.length * label.lineHeight > label.height + 1) issues.push('实际分行超过文本盒高度')
    return issues
  }
  const http = async (route, body) => {
    const response = await fetch(`${BACKEND}${route}`, { method: body ? 'POST' : 'GET',
      headers: { 'Content-Type': 'application/json', ...(playerId ? { 'X-Player-Id': playerId } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(10_000) })
    requireThat(response.ok, `${route} HTTP ${response.status}`)
    const envelope = await response.json()
    requireThat(envelope.code === 0, `${route} 前提返回 ${envelope.code}：${envelope.message ?? ''}`)
    return envelope.data
  }
  const init = await http('/player/init', { requestId: `guide-player-init-${RUN}`, deviceId,
    nickName: '引导玩家探针', clientTime: RUN, wxCode: '' })
  playerId = init.playerId
  const freshCity = await http('/city/list')
  requireThat(freshCity.buildings?.find(row => row.configId === 'main_city')?.level === 1,
    '这份量具要普通新号主城1级，不能跑在 dev 城16级提速后端')
  const script = await http('/guide/script')
  requireThat(script.applies && script.nextStepIndex === 1, '新号必须从引导第1步开始')
  requireThat(script.steps?.every(remote => step(remote.stepIndex)?.text === remote.text), '后端引导正文与本轮 contract guide@2 不同')
  mkdirSync(OUT, { recursive: true })
  console.log(`[guide-player] 后端 ${BACKEND}；预览端口 ${PORT}；自有新号 ${playerId}；引导版本 ${script.version}`)
  preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
  preview.assertRewritten()
  browser = await chromium.launch({ headless: true, timeout: 20_000 })
  const context = await browser.newContext({ viewport: { width: 960, height: 640 } })
  await context.addInitScript(value => localStorage.setItem('ironoath.deviceId', value), deviceId)
  page = await context.newPage()
  page.setDefaultTimeout(12_000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    const url = new URL(request.url())
    if (url.origin !== new URL(BACKEND).origin || url.pathname === '/ops/track/batch') return
    requests.push({ route: url.pathname, method: request.method(), body: request.postDataJSON() })
  })
  page.on('response', response => {
    const url = new URL(response.url())
    if (url.origin === new URL(BACKEND).origin && /^\/(city|quest|guide|hero)\//.test(url.pathname)) {
      void response.json().then(body => responses.push({ route: url.pathname, body })).catch(() => {})
    }
  })
  const read = () => page.evaluate(readPlayerFrame)
  const wait = async (description, predicate, timeout = 12_000) => {
    const until = Date.now() + timeout
    let frame
    do {
      frame = await read()
      if (predicate(frame)) return frame
      await page.waitForTimeout(160)
    } while (Date.now() < until)
    throw new Error(`${description} 超时：${JSON.stringify(frame)}`)
  }
  const click = async opts => {
    const guard = (await read()).viewportGuard
    must('真实鼠标操作前生产窄窗guard未挡且未进入全屏', guard?.found && guard.display === 'none' && !guard.fullscreen, guard)
    const result = await clickNodeViaCocos(page, opts)
    requireThat(result.clicked, `点击量具不可信 ${opts.name ?? opts.labelPrefix}：${result.reason}`)
    evidence.push({ phase: 'real-mouse-click', opts, result })
    return result
  }
  const clickResponse = async (route, opts) => {
    const pending = page.waitForResponse(response => new URL(response.url()).origin === new URL(BACKEND).origin
      && new URL(response.url()).pathname === route && response.request().method() === 'POST', { timeout: 12_000 })
    // 先装监听再真点；量具失败时仍收掉有界响应等待，避免 unhandled rejection。
    pending.catch(() => {})
    await click(opts)
    const response = await pending, envelope = await response.json()
    must(`${route}真实点击获得成功回执`, response.ok() && envelope.code === 0, envelope)
    return { envelope, request: response.request().postDataJSON() }
  }
  const shot = async name => {
    const frame = await read()
    evidence.push({ phase: name, frame })
    await page.screenshot({ path: path.join(OUT, `${name}.png`), timeout: 12_000 })
    return frame
  }
  const guideLayout = async (name, viewport, index, paired = false) => {
    await page.setViewportSize(viewport)
    const targetHeight = 960 * viewport.height / viewport.width
    await wait(`${name}真实resize`, frame => near(frame.visibleSize?.width, 960, 0.2)
      && near(frame.visibleSize?.height, targetHeight, 0.2) && frame.guide.bubbleActive)
    // 等一帧渲染，actualFontSize/parsedString 是排版结果，不能读取 resize 前的残值。
    await page.waitForTimeout(180)
    const frame = await shot(name), guide = frame.guide
    must(`${name}实际CSS视口及生产窄窗guard未挡操作`, frame.viewport.width === viewport.width
      && frame.viewport.height === viewport.height && frame.viewportGuard.found && frame.viewportGuard.display === 'none'
      && !frame.viewportGuard.fullscreen, { cssViewport: frame.viewport, designVisibleSize: frame.visibleSize, guard: frame.viewportGuard })
    const compact = frame.current === 'city' && frame.visibleSize.height < 600
    const bodyIssues = copyIssues(guide.body, step(index).text, frame)
    must(`${name}实际视口与响应式150高气泡`, near(guide.width, compact ? 324 : 420) && near(guide.height, 150)
      && contains(frame.canvas, guide.box), { visibleSize: frame.visibleSize, width: guide.width, box: guide.box })
    must(`${name}引导正文全文保持20号字与62高/26行高`, bodyIssues.length === 0
      && fontMetrics(guide.body, 20).pass
      && near(guide.body?.height, 62) && near(guide.body?.lineHeight, 26),
      { issues: bodyIssues, label: guide.body, fontMetrics: fontMetrics(guide.body, 20) })
    const positionText = `第 ${index} / ${table.rows.length} 步`
    const positionIssues = copyIssues(guide.position, positionText, frame)
    must(`${name}步数全文保持单行20号字与28高/22行高`, positionIssues.length === 0
      && guide.position.parsedString.length === 1 && guide.position.wrap === false && guide.position.overflow === 2
      && fontMetrics(guide.position, 20).pass
      && near(guide.position.height, 28) && near(guide.position.lineHeight, 22),
    { issues: positionIssues, label: guide.position, fontMetrics: fontMetrics(guide.position, 20) })
    must(`${name}提示底板与完成键真实材质`, guide.plateMaterial && guide.next?.material
      && guide.next.frameName?.includes('button-chip') && !guide.next.graphicsOn, guide)
    const geometryIssues = guideTextBoxesIssues(guide)
    must(`${name}表头/正文/按钮文字互不交且自身盒全部容纳`, geometryIssues.length === 0,
      { issues: geometryIssues, position: guide.position, body: guide.body, next: guide.next, skip: guide.skip })
    must(`${name}跳过可见性符合服务端`, guide.skip?.active === paired, guide.skip)
    if (paired) {
      must(`${name}两颗真按钮等高并排且完整可见`, guide.skip.material && guide.skip.frameName?.includes('button-chip')
        && contains(guide.box, guide.skip.box) && !intersects(guide.next.box, guide.skip.box)
        && near(guide.next.box.top, guide.skip.box.top) && near(guide.next.box.bottom, guide.skip.box.bottom), guide)
    } else {
      must(`${name}唯一完成键居中`, near((guide.next.box.left + guide.next.box.right) / 2,
        (guide.box.left + guide.box.right) / 2, 1), guide.next)
    }
    for (const button of [guide.next, ...(paired ? [guide.skip] : [])]) {
      const issues = copyIssues(button.caption, button.caption.text, frame)
      must(`${name}/${button.name}按钮文字全文保持单行22号字与44高/28行高`, issues.length === 0
        && near(button.height, 44) && near(button.caption?.height, 44) && near(button.caption?.lineHeight, 28)
        && fontMetrics(button.caption, 22).pass
        && button.caption?.parsedString.length === 1 && button.caption.wrap === false && button.caption.overflow === 2
        && button.caption.horizontalAlign === 1 && button.caption.verticalAlign === 1,
      { issues, label: button.caption, fontMetrics: fontMetrics(button.caption, 22) })
    }
    if (frame.current === 'city') {
      must(`${name}引导避开真实升级/收割按钮`, frame.cityActions.filter(button => button?.active)
        .every(button => !intersects(guide.box, button.box)), frame.cityActions)
    } else {
      must(`${name}第一行领取键可见且不被提示覆盖`, frame.rows[0]?.claim?.active
        && contains(frame.canvas, frame.rows[0].claim.box) && !intersects(guide.box, frame.rows[0].claim.box), frame.rows[0])
    }
    return frame
  }
  const navigationBlocked = async phase => {
    const before = await read()
    must(`${phase}外部导航中心仍在强制遮罩里`, before.guide.active && before.guide.navBlocked, before.guide.masks)
    const count = requests.length
    await click({ name: 'Nav-hero' })
    await page.waitForTimeout(320)
    const after = await read()
    must(`${phase}真点外部导航仍留在原面板`, after.current === before.current,
      { before: before.current, after: after.current, newRequests: requests.slice(count) })
  }

  await page.goto(`${preview.origin}/?panel=city`, { waitUntil: 'domcontentloaded', timeout: 25_000 })
  const first = await wait('普通新号主城与引导首帧', frame => frame.ready && frame.main?.level === 1 && frame.guide.bubbleActive, 25_000)
  must('当前生产city字段与权威主城对应', first.sourceFields.cityRows && first.sourceFields.cityGridTiles
    && typeof first.main?.id === 'string' && first.main.id === freshCity.buildings.find(row => row.configId === 'main_city').id
    && typeof first.mainTileName === 'string', { fields: first.sourceFields, main: first.main, tile: first.mainTileName })
  await guideLayout('guide1-normal640', { width: 960, height: 640 }, 1)
  await guideLayout('guide1-short', SHORT_VIEWPORT, 1)
  await guideLayout('guide1-normal600', { width: 960, height: 600 }, 1)
  await navigationBlocked('Guide1')
  await click({ labelPrefix: freshCity.buildings.find(row => row.configId === 'main_city').name, within: 'CityNameplates' })
  await wait('真实点主城后显示升级键', frame => frame.cityActions.find(button => button?.name === 'DetailUpgradeButton')?.active)

  const upgrade = async targetLevel => {
    const { envelope, request } = await clickResponse('/city/upgrade', { name: 'DetailUpgradeButton', within: 'city' })
    must(`第${targetLevel - 1}次真实升级请求只操作主城`, request.configId === 'main_city', request)
    const remaining = envelope.data.finishAt - envelope.serverNow
    requireThat(Number.isFinite(remaining) && remaining <= 45_000, '普通新号主城升级超过45秒，量具等待前提不满足')
    const frame = await wait(`主城到点/升到${targetLevel}`, current => current.main?.level === targetLevel
      || current.cityActions.find(button => button?.name === 'DetailCollectButton')?.active, Math.max(12_000, remaining + 5_000))
    if (frame.main.level < targetLevel) await clickResponse('/city/collect', { name: 'DetailCollectButton', within: 'city' })
    const complete = await wait(`主城权威刷新到${targetLevel}级`, current => current.main?.level === targetLevel && !current.main.upgrading)
    evidence.push({ phase: `upgrade-${targetLevel}`, frame: complete, request, response: envelope })
    must(`真实主城升到${targetLevel}级`, complete.main.level === targetLevel, complete.main)
  }
  await upgrade(2)
  const held = await clickResponse('/guide/progress', { name: 'GuideNext', within: 'Guide' })
  must('只升一次后 COMPLETE 必须 HOLD在第1步', held.request.stepId === step(1).id && held.request.action === 'COMPLETE'
    && held.envelope.data.advanced === false && held.envelope.data.nextStepIndex === 1, held)
  await wait('HOLD给玩家未达成提示', frame => frame.guide.position?.drawable && frame.guide.position.text === HELD_TEXT)
  const heldFrame = await shot('guide1-hold-after-one-upgrade'), heldLabel = heldFrame.guide.position
  const heldIssues = copyIssues(heldLabel, HELD_TEXT, heldFrame)
  const heldGeometryIssues = guideTextBoxesIssues(heldFrame.guide)
  must('HOLD短提示保持单行28高/22行高与20号字且各自身盒不交', heldIssues.length === 0
    && heldLabel.parsedString.length === 1 && heldLabel.wrap === false && heldLabel.overflow === 2 && near(heldLabel.height, 28)
    && near(heldLabel.lineHeight, 22) && fontMetrics(heldLabel, 20).pass
    && heldGeometryIssues.length === 0,
  { issues: heldIssues, geometryIssues: heldGeometryIssues, label: heldLabel,
    fontMetrics: fontMetrics(heldLabel, 20), bodyBox: heldFrame.guide.body.box })
  // 真实提示的恢复计时器必须先走完；否则它稍后 repaint 会把负控几何悄悄还原。
  await wait('HOLD提示恢复当前步数', frame => frame.guide.position?.text.startsWith('第 '), 6_000)
  await wait('第二次升级键重新可用', frame => frame.cityActions.find(button => button?.name === 'DetailUpgradeButton')?.active)
  await upgrade(3)
  const advanced2 = await clickResponse('/guide/progress', { name: 'GuideNext', within: 'Guide' })
  must('两次真实升级后 COMPLETE 才推进第2步', advanced2.request.stepId === step(1).id
    && advanced2.envelope.data.advanced === true && advanced2.envelope.data.nextStepIndex === 2, advanced2)
  await wait('第2步未开任务时收起旧城建引导', frame => !frame.guide.active)

  const listWait = page.waitForResponse(response => new URL(response.url()).pathname === '/quest/list', { timeout: 12_000 })
  listWait.catch(() => {})
  await click({ name: 'Nav-quest' })
  const questEnvelope = await (await listWait).json()
  must('实际打开任务入口重新拉权威列表', questEnvelope.code === 0, questEnvelope)
  const firstQuest = questEnvelope.data.quests[0]
  must('权威第一行是新手三选一且已可领取', firstQuest.questId === step(2).judgeTarget && firstQuest.claimable
    && firstQuest.heroChoices.length === 3, firstQuest)
  const questFrame = await wait('任务第一行完成刷新且引导第2步出现', frame => frame.current === 'quest'
    && frame.rows[0]?.questId === firstQuest.questId && frame.rows[0]?.claim?.active && frame.guide.bubbleActive)
  must('当前生产任务绘制行与questId映射可读', questFrame.sourceFields.questDrawnRows
    && questFrame.sourceFields.questRowQuestIds && questFrame.rows[0].questId === firstQuest.questId, questFrame.sourceFields)
  await guideLayout('guide2-short', SHORT_VIEWPORT, 2)
  await guideLayout('guide2-normal600', { width: 960, height: 600 }, 2)
  await guideLayout('guide2-normal640', { width: 960, height: 640 }, 2)
  await navigationBlocked('Guide2')

  /** 仅负控修改几何，既不暂停驱动器也不隐藏任何引导节点；finally 原位还原。 */
  const oldGeometry = await page.evaluate(() => {
    const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
    const guide = game.getChildByName('Guide'), bubble = guide.getChildByName('GuideBubble')
    const area = game.getComponent('PanelNav').contentRectFor('quest')
    const ui = bubble.getComponent('cc.UITransform')
    const saved = { x: bubble.position.x, y: bubble.position.y, z: bubble.position.z, width: ui.width, height: ui.height }
    ui.setContentSize(420, 150)
    bubble.setPosition(area.x + area.width - 12 - 420 / 2, area.y + area.height - 150 / 2 - 12, 0)
    return saved
  })
  let negative
  try {
    const planted = await shot('negative-old420-topright')
    must('旧420右上负控确实覆盖第一行领取中心', intersects(planted.guide.box, planted.rows[0].claim.box), planted)
    const claimRequests = requests.filter(request => request.route === '/quest/claim').length
    await click({ name: 'ClaimButton', within: 'quest' })
    await page.waitForTimeout(450)
    negative = await read()
    const opensChoice = negative.prompt.active && negative.prompt.options.filter(option => option.active).length === 3
    must('旧布局负控令同一领取判据变红且没有发奖', !opensChoice
      && requests.filter(request => request.route === '/quest/claim').length === claimRequests, negative.prompt)
  } finally {
    await page.evaluate(saved => {
      const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
      const bubble = game.getChildByName('Guide').getChildByName('GuideBubble')
      bubble.getComponent('cc.UITransform').setContentSize(saved.width, saved.height)
      bubble.setPosition(saved.x, saved.y, saved.z)
    }, oldGeometry)
  }
  await page.waitForTimeout(160)
  const claimCount = requests.filter(request => request.route === '/quest/claim').length
  await click({ name: 'ClaimButton', within: 'quest' })
  await wait('恢复生产布局后同一领取按钮打开真实三选一', frame => frame.prompt.active
    && frame.prompt.options.filter(option => option.active).length === 3)
  must('只打开三选一时尚未发领取请求', requests.filter(request => request.route === '/quest/claim').length === claimCount)
  for (const [name, viewport] of [['choice-short', SHORT_VIEWPORT],
    ['choice-normal600', { width: 960, height: 600 }], ['choice-normal640', { width: 960, height: 640 }]]) {
    await page.setViewportSize(viewport)
    await wait(`${name}真实resize`, frame => near(frame.visibleSize?.height, 960 * viewport.height / viewport.width, 0.2))
    await page.waitForTimeout(180)
    const frame = await shot(name), options = frame.prompt.options.filter(option => option.active)
    must(`${name}实际CSS视口及生产窄窗guard未挡操作`, frame.viewport.width === viewport.width
      && frame.viewport.height === viewport.height && frame.viewportGuard.found && frame.viewportGuard.display === 'none'
      && !frame.viewportGuard.fullscreen, { cssViewport: frame.viewport, designVisibleSize: frame.visibleSize, guard: frame.viewportGuard })
    must(`${name}生产选择窗只收引导气泡且保留遮罩`, frame.prompt.active && !frame.guide.bubbleActive
      && frame.guide.active && frame.guide.navBlocked, frame.guide)
    must(`${name}三名中文候选完整可见`, options.length === 3 && options.every((option, index) => {
      const candidate = firstQuest.heroChoices[index]
      return option.name === `HeroOption_${candidate.heroId}` && candidate.name !== candidate.heroId
        && /[\u3400-\u9fff]/.test(candidate.name) && option.label?.text.includes(candidate.name)
        && !option.label.text.includes(candidate.heroId) && copyIssues(option.label, option.label.text, frame).length === 0
        && contains(frame.canvas, option.box) && contains(option.box, option.label.box)
    }) && options.every((option, index) => options.slice(index + 1).every(other => !intersects(option.box, other.box))), options)
    for (const option of options) {
      const point = await page.evaluate(resolveCocosClickPoint, { name: option.name, within: 'HeroChoicePanel' })
      requireThat(point.verified, `${name}候选点击中心量具不可信：${point.reason}`)
      evidence.push({ phase: `${name}-option-center`, option: option.name, point })
    }
    await navigationBlocked(`${name}选择窗打开时`)
  }
  const chosen = firstQuest.heroChoices[0]
  const claimed = await clickResponse('/quest/claim', { name: `HeroOption_${chosen.heroId}`, within: 'HeroChoicePanel' })
  must('真实点中文候选中心按当前QuestClaimReq送出选项与幂等键', claimed.request.questId === firstQuest.questId
    && claimed.request.heroChoice === chosen.heroId && typeof claimed.request.requestId === 'string'
    && claimed.request.requestId.length > 0, claimed.request)
  must('发奖回执确实给所选武将整卡及中文名', claimed.envelope.data.rewards.some(reward => reward.type === 'HERO'
    && reward.id === chosen.heroId && reward.name === chosen.name && reward.count === 1), claimed.envelope.data)
  await wait('领取后第一行已领取且气泡恢复', frame => !frame.prompt.active && frame.guide.bubbleActive
    && frame.rows[0]?.status?.text === '已领取' && !frame.rows[0]?.claim?.active)
  const advanced3 = await clickResponse('/guide/progress', { name: 'GuideNext', within: 'Guide' })
  must('真实领取后引导第2步推进第3步', advanced3.request.stepId === step(2).id && advanced3.request.action === 'COMPLETE'
    && advanced3.envelope.data.advanced && advanced3.envelope.data.nextStepIndex === 3, advanced3)
  await wait('第3步未开内城时旧任务引导收起', frame => !frame.guide.active)
  await click({ name: 'Nav-city' })
  await wait('真实打开内城显示第3步双按钮', frame => frame.current === 'city' && frame.guide.skip?.active)
  await guideLayout('guide3-short-paired', SHORT_VIEWPORT, 3, true)
  await guideLayout('guide3-normal600-paired', { width: 960, height: 600 }, 3, true)
  await guideLayout('guide3-normal640-paired', { width: 960, height: 640 }, 3, true)
  const skipped = await clickResponse('/guide/progress', { name: 'GuideSkip', within: 'Guide' })
  must('第3步右侧跳过键真实可点且推进第4步', skipped.request.stepId === step(3).id && skipped.request.action === 'SKIP'
    && skipped.envelope.data.advanced && skipped.envelope.data.nextStepIndex === 4, skipped)
  await wait('跳过后第4步不在内城显示旧引导', frame => !frame.guide.active)
  report('浏览器无未捕获页面错误', errors.length === 0, errors)
  if (checks.some(check => !check.pass)) exitCode = 1
} catch (error) {
  exitCode = error instanceof Prerequisite ? 2 : 1
  if (exitCode === 1 && !checks.some(check => !check.pass)) report('真实玩家链完整执行', false, error.message)
  console.error(`[guide-player][${exitCode === 2 ? '前置' : 'FAIL'}] ${error.stack ?? error.message}`)
  if (page && exitCode === 1) {
    try { evidence.push({ phase: 'failure', frame: await page.evaluate(readPlayerFrame) }); await page.screenshot({ path: path.join(OUT, 'failure.png'), timeout: 8_000 }) } catch {}
  }
} finally {
  if (existsSync(OUT)) writeFileSync(path.join(OUT, 'guide-player-results.json'), JSON.stringify({
    backend: BACKEND, playerId, deviceId, exitCode, passed: checks.filter(check => check.pass).length,
    failed: checks.filter(check => !check.pass).length, checks, evidence, requests, responses, errors,
    limits: 'Web短比例使用1688×780 CSS视口，仅同于原生844×390的宽高比与960×443.60设计可视区；生产窄窗guard原样保留并读取display。原生844×390像素及微信真机触摸需独立验收，也不验七步完整玩家闭环。',
  }, null, 2), 'utf8')
  if (browser) await browser.close()
  if (preview) await preview.close()
}
console.log(`[guide-player] EXIT=${exitCode}；${checks.filter(check => check.pass).length}过/${checks.filter(check => !check.pass).length}败；证据 ${OUT}`)
process.exit(exitCode)
