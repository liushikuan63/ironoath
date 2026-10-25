/** Existing Cocos row probe; MARCH_ROW_CASE=labels also verifies queued #817.
 * Run: MARCH_ROW_CASE=labels MARCH_ROW_OUT=tmp/ui-v25-817-label-final node tools/verify-march-row-bottom-visual.mjs
 * Header/locked rows are measured at scroll top; the full long summary is measured at scroll bottom.
 * Synthetic loopback-only responses; this is web-mobile evidence, not native/WeChat verification.
 */
import { createServer } from 'node:http'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { assessDialogVerticalCapacity, assessComposeLabelGeometry } from './lib/choice-row-bottom.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const LABEL_CASE = process.env.MARCH_ROW_CASE === 'labels'
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
  const mounted = await page.evaluate(async labelCase => {
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
    const fixture = {
      targetId: 'synthetic-target', targetName: '训练校场', coordText: '100,77', mode: 'MARCH',
      compose: { totalText: '700', options: [
        { unitId: 'probe-infantry', name: '重步兵', tier: 1, available: 500, selected: 300, unlocked: true, unlockHint: null },
        { unitId: 'probe-archers', name: '长弓兵', tier: 2, available: 240, selected: 100, unlocked: true, unlockHint: null },
        { unitId: 'probe-cavalry', name: '轻骑兵', tier: 3, available: 300, selected: 200, unlocked: true, unlockHint: null },
        { unitId: 'probe-crossbow', name: '弩手', tier: 4, available: 400, selected: 100, unlocked: true, unlockHint: null },
      ] },
      notice: null, rallyScopes: [], rallyNumbers: [], submitting: false, submitLabel: '出征',
    }
    if (labelCase) {
      fixture.mode = 'RALLY'
      fixture.compose.options = Array.from({ length: 20 }, (_, index) => ({
        unitId: `probe-unit-${index}`, name: `训练兵种T${index + 1}`, tier: index + 1,
        available: index === 0 ? 500 : 0, selected: index === 0 ? 300 : 0,
        unlocked: index === 0, unlockHint: index === 0 ? null : '需要兵营 2 级，当前 1 级',
      }))
      fixture.rallyScope = 'NATION'
      fixture.rallyScopes = [{ scope: 'SQUAD', label: '小队' }, { scope: 'ALLIANCE', label: '联盟' }, { scope: 'NATION', label: '国家' }]
    }
    overlay.render(fixture)
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
      activeRowCount: activeRows.length, fixtureUnitCount: fixture.compose.options.length, title: overlay.titleLabel.string, insets }
  }, LABEL_CASE)
  if (!mounted.found) throw new Error(`Could not mount production MarchComposeOverlay: ${JSON.stringify(mounted)}`)
  await page.waitForTimeout(300)
  const readGeometry = insets => {
    const owner = window.__marchRowBottomProbe
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
    const clips = node => {
      const result = []
      for (let parent = node.parent; parent; parent = parent.parent) {
        const mask = parent.getComponent('cc.Mask')
        if (parent.activeInHierarchy && mask) result.push({ ...rect(parent), enabled: mask.enabled })
      }
      return result
    }
    const cameras = window.cc.director.getScene().getComponentsInChildren('cc.Camera')
    const readLabel = label => {
      const bounds = rect(label.node), maskBounds = clips(label.node)
      let opaque = true
      for (let node = label.node; node; node = node.parent) {
        const opacity = node.getComponent('cc.UIOpacity')
        if (opacity && opacity.enabled !== false && opacity.opacity <= 0) opaque = false
      }
      const visible = label.node.activeInHierarchy && label.enabled && label.color.a > 0
        && label.string.trim().length > 0 && label.node._uiProps?.uiComp === label && opaque
        && cameras.some(camera => camera.enabled && camera.node.activeInHierarchy && (camera.visibility & label.node.layer) !== 0)
        && maskBounds.filter(mask => mask.enabled).every(mask => bounds
          && bounds.left >= mask.left - 0.5 && bounds.right <= mask.right + 0.5
          && bounds.bottom >= mask.bottom - 0.5 && bounds.top <= mask.top + 0.5)
      return { text: label.string, rect: bounds, visible, clips: maskBounds }
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
      labelGeometry: {
        title: readLabel(owner.titleLabel),
        coord: readLabel(owner.coordLabel),
        total: readLabel(owner.totalLabel),
        crest: { active: owner.crestNode.activeInHierarchy, rect: rect(owner.crestNode) },
        rows: rows.map(node => ({ name: node.name, labels: node.children.filter(child => child.getComponent('cc.Label')).map(child => readLabel(child.getComponent('cc.Label'))) })),
      },
      title: find(overlay, 'title')?.getComponent('cc.Label')?.string ?? null,
    }
  }
  const measured = await page.evaluate(readGeometry, mounted.insets)
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
  // Header/row checks use the initial viewport; the long summary must be reachable at scroll bottom.
  const labelInput = (top, bottom = top) => ({ plate: bottom.plate, insets: mounted.insets,
    labels: { ...top.labelGeometry, total: bottom.labelGeometry.total } })
  const scrollLabels = async direction => {
    await page.evaluate(direction => {
      let node = window.__marchRowBottomProbe.totalLabel.node.parent
      while (node && !node.getComponent('cc.ScrollView')) node = node.parent
      const scroll = node?.getComponent('cc.ScrollView')
      if (!scroll) throw new Error('Actual production summary ScrollView missing')
      if (direction === 'bottom') scroll.scrollToBottom(0); else scroll.scrollToTop(0)
    }, direction)
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)))
  }
  let labelChecks = null
  if (LABEL_CASE) {
    mkdirSync(OUT, { recursive: true })
    const topScreenshot = path.join(OUT, 'march-labels-top.png')
    await page.screenshot({ path: topScreenshot })
    await scrollLabels('bottom')
    const bottom = await page.evaluate(readGeometry, mounted.insets)
    labelChecks = { positive: assessComposeLabelGeometry(labelInput(measured, bottom)),
      topScreenshot, summaryAtBottom: bottom.labelGeometry.total, negatives: [] }
    for (const issue of ['header', 'locked-row', 'summary', 'hidden-title', 'disabled-row', 'disabled-mask']) {
      await scrollLabels(issue === 'summary' || issue === 'disabled-mask' ? 'bottom' : 'top')
      try {
        await page.evaluate(issue => {
          const owner = window.__marchRowBottomProbe
          let node, destination
          if (issue === 'hidden-title') {
            node = owner.titleLabel.node
            window.__composeLabelMutation = { kind: 'active', node, value: node.active }
            node.active = false; return
          }
          if (issue === 'disabled-row') {
            const label = owner.rowNameLabels[1]
            window.__composeLabelMutation = { kind: 'enabled', component: label, value: label.enabled }
            label.enabled = false; return
          }
          if (issue === 'disabled-mask') {
            node = owner.totalLabel.node.parent
            while (node && !node.getComponent('cc.Mask')) node = node.parent
            const mask = node?.getComponent('cc.Mask')
            if (!mask) throw new Error('Actual ancestor Mask missing')
            window.__composeLabelMutation = { kind: 'enabled', component: mask, value: mask.enabled }
            mask.enabled = false; return
          }
          if (issue === 'header') { node = owner.titleLabel.node; destination = owner.crestNode.worldPosition.clone() }
          if (issue === 'locked-row') {
            const index = owner.rowCountLabels.findIndex(label => label.string === '不可出征')
            if (index < 0) throw new Error('Locked row fixture was not rendered')
            node = owner.rowNameLabels[index].node; destination = owner.rowCountLabels[index].node.worldPosition.clone()
            // Align the right edges: real overlap while both labels remain inside the row viewport.
            destination.x -= node.getComponent('cc.UITransform').width * node.worldScale.x
          }
          if (issue === 'summary') {
            node = owner.totalLabel.node
            const plate = owner.node.getChildByName('plate').getComponent('cc.UITransform')
            destination = plate.convertToWorldSpaceAR(new window.cc.Vec3(0, -plate.height / 2, 0))
          }
          window.__composeLabelMutation = { node, position: node.position.clone() }
          node.setWorldPosition(destination)
        }, issue)
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)))
        const mutant = await page.evaluate(readGeometry, mounted.insets)
        const assessment = assessComposeLabelGeometry(issue === 'summary' || issue === 'disabled-mask'
          ? labelInput(measured, mutant) : labelInput(mutant, bottom))
        const key = issue === 'header' || issue === 'hidden-title' ? 'headerClear'
          : issue === 'locked-row' || issue === 'disabled-row' ? 'rowsClear' : 'summaryContained'
        const rejected = !assessment.pass && assessment[key] === false
          && ['headerClear', 'rowsClear', 'summaryContained'].filter(k => k !== key).every(k => assessment[k] === true)
          && (issue !== 'locked-row' || mutant.labelGeometry.rows.some(row => row.labels[0].visible && row.labels[1].visible
            && Math.min(row.labels[0].rect.right, row.labels[1].rect.right) - Math.max(row.labels[0].rect.left, row.labels[1].rect.left) > 0.5))
        const screenshot = path.join(OUT, `negative-${issue}.png`)
        await page.screenshot({ path: screenshot })
        labelChecks.negatives.push({ issue, rejected, assessment, screenshot, measured: mutant.labelGeometry })
      } finally {
        await page.evaluate(() => {
          const saved = window.__composeLabelMutation
          if (saved?.kind === 'active') saved.node.active = saved.value
          else if (saved?.kind === 'enabled') saved.component.enabled = saved.value
          else saved?.node.setPosition(saved.position)
          delete window.__composeLabelMutation
        })
      }
    }
    await scrollLabels('top')
    const restoredTop = await page.evaluate(readGeometry, mounted.insets)
    await scrollLabels('bottom')
    labelChecks.restored = assessComposeLabelGeometry(labelInput(restoredTop, await page.evaluate(readGeometry, mounted.insets)))
    labelChecks.hiddenUnitNotice = measured.labelGeometry.total.text.includes(`另有 ${mounted.fixtureUnitCount - mounted.activeRowCount} 种兵`)
  }
  const screenshot = path.join(OUT, 'march-compose-row-bottom-cocos-webmobile-1to1.png')
  mkdirSync(OUT, { recursive: true })
  await page.screenshot({ path: screenshot })
  const evidence = {
    renderer: 'Cocos Creator 3.8.7 web-mobile in Edge; viewport 1440x900; deviceScaleFactor=1',
    productionModule: mounted,
    fixture: LABEL_CASE ? '20 synthetic unit options; real NATION rally band; locked rows and long summary' : 'four synthetic unit rows mounted directly into the production MarchComposeOverlay; no player/session creation',
    measured, verticalCapacity, labelChecks, rowPlateClearance: clearance, rowBounded, rowVerticallyContained, footerClearancePass, mockRequests: requests, blockedRequests, pageErrors,
    previewOrigin: preview.origin, previewRewrites: preview.rewrites(), screenshot,
  }
  writeFileSync(path.join(OUT, 'march-compose-row-bottom-evidence.json'), JSON.stringify(evidence, null, 2))
  console.log(JSON.stringify(evidence, null, 2))
  if (!rowBounded || !rowVerticallyContained || !footerClearancePass || !verticalCapacity.pass
      || (LABEL_CASE && (!labelChecks.positive.pass || !labelChecks.restored.pass || !labelChecks.hiddenUnitNotice || labelChecks.negatives.some(row => !row.rejected)))
      || blockedRequests.length || pageErrors.length || preview.rewrites() < 1) process.exitCode = 1
} finally {
  await context.close()
  await browser.close()
  await preview.close()
  await new Promise(resolve => mock.close(resolve))
}