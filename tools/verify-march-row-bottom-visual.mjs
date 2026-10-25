import { createServer } from 'node:http'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { assessDialogVerticalCapacity } from './lib/choice-row-bottom.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = path.resolve(process.env.MARCH_ROW_BUILD ?? 'client/build/web-mobile')
const PORT = Number(process.env.MARCH_ROW_PORT ?? 8301)
const OUT = path.resolve(process.env.MARCH_ROW_OUT ?? 'tmp/ui-v25-compose-row-1to1')
for (const file of ['index.html', 'application.js', 'assets/resources/config.json']) {
  if (!existsSync(path.join(ROOT, file))) throw new Error(`Missing isolated Cocos build artifact: ${file}`)
}

const requests = []
const mock = createServer((request, response) => {
  const chunks = []
  request.on('data', chunk => chunks.push(chunk))
  request.on('end', () => {
    const pathname = new URL(request.url ?? '/', 'http://localhost').pathname
    let body = {}
    try { body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {} } catch {}
    requests.push({ method: request.method, path: pathname, bodyKeys: Object.keys(body) })
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
    } : pathname === '/time/sync' ? { sync: { offset: 0, syncAt: Date.now() } } : {}
    const envelope = pathname === '/player/init'
      ? { code: 1000, msg: 'synthetic visual probe stops before player creation', data: {} }
      : { code: 0, data }
    response.end(request.method === 'OPTIONS' ? '' : JSON.stringify(envelope))
  })
})
mock.on('upgrade', (_request, socket) => socket.destroy())
await new Promise(resolve => mock.listen(0, '127.0.0.1', resolve))
const address = mock.address()
if (!address || typeof address === 'string') throw new Error('Could not bind synthetic API')
const backend = `http://127.0.0.1:${address.port}`
const preview = await startPreviewServer({ root: ROOT, backend, port: PORT, host: '127.0.0.1' })
const browser = await chromium.launch(process.env.MARCH_ROW_BROWSER
  ? { executablePath: process.env.MARCH_ROW_BROWSER } : {})
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1,
  serviceWorkers: 'block' })
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
page.on('pageerror', error => pageErrors.push(error.message))
try {
  await page.goto(preview.origin)
  await page.waitForFunction(() => window.cc?.director?.getScene() != null
    && typeof window.System?.entries === 'function', null, { timeout: 60000 })
  const mounted = await page.evaluate(async () => {
    const scene = window.cc.director.getScene()
    const canvas = scene.getChildByName('Canvas')
    const parent = canvas
    const entries = Array.from(window.System.entries())
    const artEntry = entries.find(([key, module]) =>
      /(?:^|\/)ArtCatalog\.ts(?:$|\?)/.test(key)
      && typeof module?.preloadRuntimeArt === 'function')
    const familyEntry = entries.find(([key, module]) =>
      /(?:^|\/)ArtFamilies\.ts(?:$|\?)/.test(key) && module?.PANEL_IRON_INSET)
    const layoutEntry = entries.find(([key, module]) =>
      /(?:^|\/)DialogLayout\.ts(?:$|\?)/.test(key)
      && typeof module?.dialogLayout === 'function' && module?.DIALOG_COMPACT_INSET)
    const styleEntry = entries.find(([key, module]) =>
      /(?:^|\/)DialogStyle\.ts(?:$|\?)/.test(key) && typeof module?.dialogContentRect === 'function')
    if (!artEntry || !familyEntry || !layoutEntry || !styleEntry) {
      return { found: false, scene: scene?.name ?? null, artCatalogFound: Boolean(artEntry),
        artFamiliesFound: Boolean(familyEntry), layoutFound: Boolean(layoutEntry), styleFound: Boolean(styleEntry) }
    }
    const artPreloaded = await artEntry[1].preloadRuntimeArt()
    const entry = entries.find(([key, module]) =>
      /(?:^|\/)MarchComposeOverlay\.ts(?:$|\?)/.test(key)
      && typeof module?.MarchComposeOverlay === 'function')
    if (!parent || !entry) return { found: false, scene: scene?.name ?? null, module: entry?.[0] ?? null }
    const overlay = new entry[1].MarchComposeOverlay(parent, 620)
    overlay.render({
      targetId: 'synthetic-target', targetName: '训练校场', coordText: '100,77', mode: 'MARCH',
      compose: { totalText: '700', options: [
        { unitId: 'probe-infantry', name: '重步兵', tier: 1, available: 500, selected: 300, unlocked: true, unlockHint: null },
        { unitId: 'probe-archers', name: '长弓兵', tier: 2, available: 240, selected: 100, unlocked: true, unlockHint: null },
        { unitId: 'probe-cavalry', name: '轻骑兵', tier: 3, available: 300, selected: 200, unlocked: true, unlockHint: null },
        { unitId: 'probe-crossbow', name: '弩手', tier: 4, available: 400, selected: 100, unlocked: true, unlockHint: null },
      ] },
      notice: null, rallyScopes: [], rallyNumbers: [], submitting: false, submitLabel: '出征',
    })
    const panel = overlay.node.getChildByName('plate')
    const frame = panel?.getComponent('cc.UITransform')
    const baseInsets = familyEntry[1].PANEL_IRON_INSET
    const area = styleEntry[1].dialogContentRect(overlay.node)
    const layout = layoutEntry[1].dialogLayout(area,
      frame.width - baseInsets.left - baseInsets.right,
      frame.height - baseInsets.top - baseInsets.bottom)
    const insets = layout.compact ? layoutEntry[1].DIALOG_COMPACT_INSET : baseInsets
    const collectRows = root => root.children.flatMap(child =>
      [...(/^composeRow\d+$/.test(child.name) ? [child] : []), ...collectRows(child)])
    const activeRows = collectRows(overlay.node).filter(node => node.activeInHierarchy)
    window.__marchRowBottomProbe = overlay
    return { found: true, module: entry[0], parent: parent.name, artPreloaded,
      activeRowCount: activeRows.length, title: overlay.titleLabel.string, insets }
  })
  if (!mounted.found) throw new Error(`Could not mount production MarchComposeOverlay: ${JSON.stringify(mounted)}`)
  await page.waitForTimeout(300)
  const measured = await page.evaluate(insets => {
    const overlay = window.__marchRowBottomProbe?.node
    const rect = node => {
      const box = node?.getComponent('cc.UITransform')
      if (!box || typeof box.convertToWorldSpaceAR !== 'function') return null
      const points = [[0, 0], [1, 0], [1, 1], [0, 1]].map(([x, y]) =>
        box.convertToWorldSpaceAR(new window.cc.Vec3((x - box.anchorX) * box.width, (y - box.anchorY) * box.height, 0)))
      const xs = points.map(p => p.x), ys = points.map(p => p.y)
      return { left: Math.min(...xs), right: Math.max(...xs), bottom: Math.min(...ys), top: Math.max(...ys),
        width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys) }
    }
    const find = (root, name) => {
      if (!root) return null
      if (root.name === name) return root
      for (const child of root.children) {
        const found = find(child, name)
        if (found) return found
      }
      return null
    }
    const plate = find(overlay, 'plate')
    const collectRows = root => (root?.children ?? []).flatMap(child =>
      [...(/^composeRow\d+$/.test(child.name) ? [child] : []), ...collectRows(child)])
    const rows = collectRows(overlay).filter(node => node.activeInHierarchy)
    const footer = ['编成取消', '编成侦察', '编成种类', '编成出征'].map(name => find(overlay, name))
    const plateRect = rect(plate)
    const rowRects = rows.map(rect).filter(Boolean)
    const footerRects = footer.map(rect).filter(Boolean)
    const footerBounds = footerRects.length ? {
      bottom: Math.min(...footerRects.map(item => item.bottom)),
      top: Math.max(...footerRects.map(item => item.top)),
    } : null
    return {
      overlayActive: overlay?.active === true,
      plate: plateRect,
      rows: rows.map((node, index) => ({ name: node?.name, active: node?.active === true,
        rect: rect(node), labels: node?.children?.map(child => child.getComponent('cc.Label')?.string ?? '') ?? [], index })),
      footer: footer.map((node, index) => ({ name: node?.name, rect: footerRects[index] ?? null })),
      verticalCapacityInput: {
        netContentHeight: plateRect ? plateRect.height - insets.top - insets.bottom : null,
        rows: rowRects.map(item => ({ bottom: item.bottom, top: item.top })),
        footer: footerBounds,
      },
      title: find(overlay, 'title')?.getComponent('cc.Label')?.string ?? null,
    }
  }, mounted.insets)
  const verticalCapacity = assessDialogVerticalCapacity(measured.verticalCapacityInput)
  if (!measured.overlayActive || measured.rows.length === 0 || measured.rows.length !== mounted.activeRowCount
      || measured.rows.some(row => !row.active || !row.rect) || !verticalCapacity.pass
      || verticalCapacity.visibleRowCount !== mounted.activeRowCount) {
    throw new Error(`MarchCompose did not render measurable production rows: ${JSON.stringify(measured)}`)
  }
  const margins = measured.rows.map(row => ({
    left: row.rect.left - measured.plate.left,
    right: measured.plate.right - row.rect.right,
  }))
  const tolerance = 0.75
  const rowBounded = measured.rows.every((row, i) =>
    row.rect.left >= measured.plate.left - tolerance && row.rect.right <= measured.plate.right + tolerance
      && Math.abs(margins[i].left - margins[i].right) <= tolerance)
  const rowVerticallyContained = measured.rows.every(row =>
    row.rect.top <= measured.plate.top + tolerance && row.rect.bottom >= measured.plate.bottom - tolerance)
  const clearance = measured.rows.map((row, i) => ({
    row: row.name,
    plateLeft: margins[i].left,
    plateRight: margins[i].right,
    footerTopGap: row.rect.bottom - Math.max(...measured.footer.map(item => item.rect.top)),
  }))
  const footerClearancePass = clearance.every(row => row.footerTopGap >= -tolerance)
  const screenshot = path.join(OUT, 'march-compose-row-bottom-cocos-webmobile-1to1.png')
  mkdirSync(OUT, { recursive: true })
  await page.screenshot({ path: screenshot })
  const evidence = {
    renderer: 'Cocos Creator 3.8.7 web-mobile in Edge; viewport 1440x900; deviceScaleFactor=1',
    productionModule: mounted,
    fixture: 'four synthetic unit rows mounted directly into the production MarchComposeOverlay; no player/session creation',
    measured, verticalCapacity, rowPlateClearance: clearance, rowBounded, rowVerticallyContained, footerClearancePass, mockRequests: requests, blockedRequests, pageErrors,
    previewOrigin: preview.origin, previewRewrites: preview.rewrites(), screenshot,
  }
  writeFileSync(path.join(OUT, 'march-compose-row-bottom-evidence.json'), JSON.stringify(evidence, null, 2))
  console.log(JSON.stringify(evidence, null, 2))
  if (!rowBounded || !rowVerticallyContained || !footerClearancePass || !verticalCapacity.pass
      || blockedRequests.length || pageErrors.length || preview.rewrites() < 1) process.exitCode = 1
} finally {
  await context.close()
  await browser.close()
  await preview.close()
  await new Promise(resolve => mock.close(resolve))
}