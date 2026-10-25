import { createServer } from 'node:http'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { assessChoiceRowBottom, assessDialogVerticalCapacity } from './lib/choice-row-bottom.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = path.resolve(process.env.CHOICE_BUILD ?? 'client/build/web-mobile')
const PORT = Number(process.env.CHOICE_PORT ?? 8299)
const OUT = path.resolve(process.env.CHOICE_OUT ?? 'tmp/ui-v25-choice-row')
const required = ['index.html', 'application.js', 'assets/resources/config.json']
const missing = required.filter(file => !existsSync(path.join(ROOT, file)))
if (missing.length) throw new Error(`Missing isolated Cocos build artifacts: ${missing.join(', ')}`)

const requests = []
const mockBackend = createServer((request, response) => {
  const chunks = []
  request.on('data', chunk => chunks.push(chunk))
  request.on('end', () => {
    const pathname = new URL(request.url ?? '/', 'http://localhost').pathname
    let body = {}
    try { body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {} } catch {}
    const clientTime = Number.isFinite(body.clientTime) ? body.clientTime : Date.now()
    requests.push({ method: request.method, path: pathname,
      bodyKeys: Object.keys(body), clientTimeType: typeof body.clientTime })
    response.writeHead(200, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': '*',
      'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
      'Content-Type': 'application/json',
    })
    const data = pathname === '/ops/app/version' ? {
      latest: 'probe', forceUpdate: false, grayEnabled: false, notice: null, support: null,
      trackPolicy: { maxBatchSize: 16, flushSeconds: 10 },
      networkTuning: { maxRetryAttempts: 0, retryBaseDelayMs: 100, retryMaxDelayMs: 500,
        offlineQueueMax: 16, requestTimeoutMs: 1000, timeSyncAlphaFixed: 10000,
        timeSyncJitterFactorFixed: 20000, timeSyncInitialBestRttMs: 50 },
    } : pathname === '/time/sync' ? { sync: { offset: 0, syncAt: clientTime } } : {}
    const envelope = pathname === '/player/init' ? { code: 1000, msg: 'synthetic geometry probe stops before player creation', data: {} } : { code: 0, data }
    response.end(request.method === 'OPTIONS' ? '' : JSON.stringify(envelope))
  })
})
mockBackend.on('upgrade', (_request, socket) => socket.destroy())
await new Promise(resolve => mockBackend.listen(0, '127.0.0.1', resolve))
const address = mockBackend.address()
if (!address || typeof address === 'string') throw new Error('Could not bind synthetic backend')
const backend = `http://127.0.0.1:${address.port}`
const preview = await startPreviewServer({ root: ROOT, backend, port: PORT, host: '127.0.0.1' })
const browser = await chromium.launch(process.env.CHOICE_BROWSER ? { executablePath: process.env.CHOICE_BROWSER } : {})
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, serviceWorkers: 'block' })
const blockedRequests = []
await context.route('**/*', async route => {
  const url = new URL(route.request().url())
  if (url.origin === preview.origin || url.origin === backend) return route.continue()
  blockedRequests.push({ origin: url.origin, resourceType: route.request().resourceType() })
  return route.abort('blockedbyclient')
})
await context.routeWebSocket('**/*', websocket => {
  const url = new URL(websocket.url())
  const allowedOrigins = [preview.origin.replace(/^http/, 'ws'), backend.replace(/^http/, 'ws')]
  if (allowedOrigins.includes(url.origin)) return websocket.connect()
  blockedRequests.push({ origin: url.origin, resourceType: 'websocket' })
  return websocket.close({ code: 1008, reason: 'Probe permits loopback only' })
})
const page = await context.newPage()
const pageErrors = []
page.on('pageerror', error => pageErrors.push({ message: error.message, stack: error.stack }))
let overlay = null
try {
  await page.goto(preview.origin)
  await page.waitForFunction(() => window.cc?.director?.getScene() != null
    && typeof window.System?.entries === 'function', null, { timeout: 60000 })
  const mounted = await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const canvas = scene.getChildByName('Canvas')
    const entries = Array.from(window.System.entries())
    const entry = entries.find(([key, module]) =>
      /(?:^|\/)ChoiceOverlay\.ts(?:$|\?)/.test(key)
      && typeof module?.ChoiceOverlay === 'function')
    const artEntry = entries.find(([key, module]) =>
      /(?:^|\/)ArtFamilies\.ts(?:$|\?)/.test(key) && module?.PANEL_IRON_INSET)
    const layoutEntry = entries.find(([key, module]) =>
      /(?:^|\/)DialogLayout\.ts(?:$|\?)/.test(key)
      && typeof module?.dialogLayout === 'function' && module?.DIALOG_COMPACT_INSET)
    const styleEntry = entries.find(([key, module]) =>
      /(?:^|\/)DialogStyle\.ts(?:$|\?)/.test(key) && typeof module?.dialogContentRect === 'function')
    if (!canvas || !entry || !artEntry || !layoutEntry || !styleEntry) {
      return { found: false, scene: scene?.name ?? null, module: entry?.[0] ?? null,
        artFamiliesFound: Boolean(artEntry), layoutFound: Boolean(layoutEntry), styleFound: Boolean(styleEntry) }
    }
    const ChoiceOverlay = entry[1].ChoiceOverlay
    const owner = new ChoiceOverlay(canvas, 'Choice row geometry', 620)
    owner.show(Array.from({ length: 4 }, (_, index) => ({
      id: `choice-geometry-${index + 1}`,
      label: `Geometry row ${index + 1}`,
      detail: 'Synthetic option for layout verification',
    })), () => {})
    const viewport = owner.node.getChildByName('DialogContent')
    const scroll = viewport?.getComponent(window.cc.ScrollView)
    if (!viewport || !scroll) {
      owner.hide()
      owner.node.destroy()
      return { found: false, scene: scene?.name ?? null, error: 'Choice DialogContent ScrollView was not created' }
    }
    const content = viewport.getChildByName('DialogContentContent')
    const activeRows = content?.children.filter(node =>
      /^Choice-\d+$/.test(node.name) && node.activeInHierarchy) ?? []
    const frame = owner.node.getComponent('cc.UITransform')
    const baseInsets = artEntry[1].PANEL_IRON_INSET
    const area = styleEntry[1].dialogContentRect(owner.node)
    const layout = layoutEntry[1].dialogLayout(area,
      frame.width - baseInsets.left - baseInsets.right,
      frame.height - baseInsets.top - baseInsets.bottom)
    const insets = layout.compact ? layoutEntry[1].DIALOG_COMPACT_INSET : baseInsets
    scroll.scrollToBottom(0)
    window.__choiceRowBottomProbe = owner
    return { found: true, module: entry[0], activeRowCount: activeRows.length,
      viewport: viewport.name, insets }
  })
  if (!mounted.found) throw new Error(`Could not mount actual ChoiceOverlay: ${JSON.stringify(mounted)}`)
  await page.waitForTimeout(250)
  const measured = await page.evaluate(insets => {
    const owner = window.__choiceRowBottomProbe
    const popup = owner?.node
    const viewport = popup?.getChildByName('DialogContent')
    const content = viewport?.getChildByName('DialogContentContent')
    const row = content?.getChildByName('Choice-3')
    const footer = popup?.getChildByName('ChoiceCancel')
    const rect = node => {
      const box = node?.getComponent('cc.UITransform')
      if (!box || typeof box.convertToWorldSpaceAR !== 'function') return null
      const x0 = -box.width * box.anchorX, x1 = box.width * (1 - box.anchorX)
      const y0 = -box.height * box.anchorY, y1 = box.height * (1 - box.anchorY)
      const points = [[x0, y0], [x0, y1], [x1, y0], [x1, y1]]
        .map(([x, y]) => box.convertToWorldSpaceAR(new window.cc.Vec3(x, y, 0)))
      const xs = points.map(point => point.x), ys = points.map(point => point.y)
      return { x: Math.min(...xs), y: Math.min(...ys),
        width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys) }
    }
    const rowNodes = content?.children.filter(node =>
      /^Choice-\d+$/.test(node.name) && node.activeInHierarchy) ?? []
    const rowRects = rowNodes.map(rect).filter(Boolean)
    const footerNodes = popup?.children.filter(node =>
      ['ChoicePrev', 'ChoiceNext', 'ChoiceCancel'].includes(node.name)) ?? []
    const footerRects = footerNodes.map(rect).filter(Boolean)
    const footerBounds = footerRects.length ? {
      bottom: Math.min(...footerRects.map(item => item.y)),
      top: Math.max(...footerRects.map(item => item.y + item.height)),
    } : null
    const panel = rect(popup)
    return {
      row: rect(row), viewport: rect(viewport), footer: rect(footer),
      rowActive: row?.activeInHierarchy === true,
      capacityInput: {
        netContentHeight: panel ? panel.height - insets.top - insets.bottom : null,
        rows: rowRects.map(item => ({ bottom: item.y, top: item.y + item.height })),
        footer: footerBounds,
      },
    }
  }, mounted.insets)
  const assessment = assessChoiceRowBottom(measured)
  const verticalCapacity = assessDialogVerticalCapacity(measured.capacityInput)
  const screenshot = path.join(OUT, 'choice-row-bottom-cocos-webmobile-final.png')
  mkdirSync(OUT, { recursive: true })
  await page.screenshot({ path: screenshot })
  const evidence = {
    buildRoot: ROOT,
    renderer: 'Cocos Creator 3.8.7 web-mobile runtime; synthetic API responses only',
    mounted,
    measured,
    assessment,
    verticalCapacity,
    mockRequests: requests,
    pageErrors,
    blockedRequests,
    screenshot,
    previewRewrites: preview.rewrites(),
  }
  writeFileSync(path.join(OUT, 'choice-row-bottom-evidence-final.json'), JSON.stringify(evidence, null, 2))
  console.log(JSON.stringify(evidence, null, 2))
  if (!measured.rowActive || !assessment.pass || !verticalCapacity.pass
      || verticalCapacity.visibleRowCount !== mounted.activeRowCount
      || pageErrors.length > 0 || blockedRequests.length > 0 || preview.rewrites() < 1) process.exitCode = 1
} finally {
  if (overlay) overlay.hide?.()
  await context.close()
  await browser.close()
  await preview.close()
  await new Promise(resolve => mockBackend.close(resolve))
}