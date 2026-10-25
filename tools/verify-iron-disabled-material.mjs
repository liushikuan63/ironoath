import { createServer } from 'node:http'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = path.resolve(process.env.IRON_DISABLED_BUILD ?? 'client/build/web-mobile')
const PORT = Number(process.env.IRON_DISABLED_PORT ?? 8304)
const OUT = path.resolve(process.env.IRON_DISABLED_OUT ?? 'tmp/ui-v25-iron-disabled')
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
const browser = await chromium.launch(process.env.IRON_DISABLED_BROWSER ? { executablePath: process.env.IRON_DISABLED_BROWSER } : {})
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
try {
  await page.goto(preview.origin)
  await page.waitForFunction(() => window.cc?.director?.getScene() && typeof window.System?.entries === 'function', null, { timeout: 60000 })
  const evidence = await page.evaluate(async () => {
    const modules = Array.from(window.System.entries()).map(([, m]) => m)
    const art = modules.find(m => typeof m?.applyIronButton === 'function')
    const Overlay = modules.find(m => typeof m?.StaminaDetailOverlay === 'function')?.StaminaDetailOverlay
    if (!art || !Overlay) throw new Error('Production modules missing')
    await art.preloadRuntimeArt()
    const canvas = window.cc.director.getScene().getChildByName('Canvas')
    const owner = new Overlay(canvas)
    let intents = 0; owner.onBuy = () => intents++
    const vm = { titleText: 'Iron disabled material', recoverText: 'Synthetic fixture', boughtText: '0', buyEnabled: false, buyLabel: '今日已达上限' }
    owner.render(vm)
    const buy = owner.node.getChildByName('Panel').getChildByName('BuyButton')
    const sprite = buy.getComponent('cc.Sprite')
    const read = () => ({ frame: sprite.spriteFrame?.name, tint: [sprite.color.r, sprite.color.g, sprite.color.b, sprite.color.a], active: buy.activeInHierarchy, caption: owner.buyCaption.string, captionColor: [owner.buyCaption.color.r, owner.buyCaption.color.g, owner.buyCaption.color.b, owner.buyCaption.color.a] })
    const disabled = read()
    buy.emit('touch-start'); buy.emit('touch-end')
    const afterDisabledTouch = read()
    art.applyIronButton(buy, 'normal', 220, 36)
    const negative = read()
    owner.render(vm); const restored = read()
    owner.render({ ...vm, buyEnabled: true, buyLabel: '购买' }); const enabled = read()
    owner.render(vm)
    const samples = []
    for (const [index, state] of ['normal', 'disabled', 'legacy-tint'].entries()) {
      const node = new buy.constructor('sample-' + state)
      canvas.addChild(node); node.layer = buy.layer
      node.addComponent(buy.getComponent('cc.UITransform').constructor)
      art.applyIronButton(node, state === 'disabled' ? 'disabled' : 'normal', 160, 32)
      node.setPosition(-190 + index * 190, 240, 0)
      const sp = node.getComponent('cc.Sprite')
      if (state === 'legacy-tint') { const c = sp.color.clone(); c.r = 104; c.g = 96; c.b = 88; sp.color = c }
      samples.push({ state, frame: sp.spriteFrame?.name, width: node.getComponent('cc.UITransform').width, height: node.getComponent('cc.UITransform').height })
    }
    return { disabled, afterDisabledTouch, negative, restored, enabled, intents, samples }
  })
  const validDisabled = r => r.frame === 'button-iron-disabled-v1' && r.tint.join(',') === '255,255,255,255' && r.active
  evidence.pass = validDisabled(evidence.disabled) && validDisabled(evidence.afterDisabledTouch)
    && !validDisabled(evidence.negative) && validDisabled(evidence.restored)
    && evidence.enabled.frame === 'button-iron-v1' && evidence.intents === 0
    && evidence.disabled.captionColor.join(',') === '70,62,52,255'
  mkdirSync(OUT, { recursive: true })
  await page.screenshot({ path: path.join(OUT, 'iron-disabled-32px-comparison.png') })
  writeFileSync(path.join(OUT, 'evidence.json'), JSON.stringify({ ...evidence, buildRoot: ROOT, pageErrors, blockedRequests, renderer: 'Cocos Creator 3.8.7 web-mobile; synthetic loopback only', samplesLeftToRight: ['normal', 'derived disabled', 'legacy tint'] }, null, 2))
  console.log(JSON.stringify({ ...evidence, pageErrors, blockedRequests }))
  if (!evidence.pass || pageErrors.length || blockedRequests.length) process.exitCode = 1
} finally {
  await context.close(); await browser.close(); await preview.close()
  await new Promise(resolve => mockBackend.close(resolve))
}
