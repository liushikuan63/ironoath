/**
 * #774 提示净区：真 web-mobile 产物上量提示与可点控件/状态文字的世界矩形。
 * 用法：BACKEND_ORIGIN=http://127.0.0.1:<自有端口> node tools/verify-hint-clearance.mjs
 * 先起隔离内存后端并 build-webmobile.sh；本探针仅建隔离玩家，不触碰原账号。
 */
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const backend = process.env.BACKEND_ORIGIN
if (!backend) {
  console.error('[hint-clearance] 缺 BACKEND_ORIGIN，不猜共享后端')
  process.exit(2)
}
const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend,
  port: Number(process.env.HINT_CLEARANCE_PORT ?? 8494) })
preview.assertRewritten()
let browser
let failed = false
try {
  browser = await chromium.launch({ headless: true })
  for (const [width, height] of [[1440, 900], [854, 480], [375, 812]]) {
    const deviceId = `hint-clearance-${width}-${Date.now()}`
    const init = await fetch(`${backend}/player/init`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ requestId: `hint-clear-${Date.now()}`, deviceId, nickName: '提示净区', clientTime: Date.now(), wxCode: '' }) }).then(res => res.json())
    if (init.code !== 0) throw new Error(`init ${width}: ${JSON.stringify(init).slice(0, 120)}`)
    const context = await browser.newContext({ viewport: { width, height } })
    await context.addInitScript(value => localStorage.setItem('ironoath.deviceId', value), deviceId)
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    const url = new URL(preview.origin)
    url.searchParams.set('panel', 'city')
    await page.goto(url.toString(), { waitUntil: 'networkidle' })
    await page.waitForFunction(() => {
      const scene = window.cc?.director?.getScene?.()
      if (!scene) return false
      const stack = [scene]
      while (stack.length) {
        const node = stack.pop()
        for (const comp of node.components ?? []) if (typeof comp.showHint === 'function' && comp.hintQueue) return true
        stack.push(...node.children)
      }
      return false
    }, null, { timeout: 30000 })
    await page.evaluate(() => {
      const scene = window.cc.director.getScene()
      const stack = [scene]
      let bootstrap
      while (stack.length) {
        const node = stack.pop()
        for (const comp of node.components ?? []) if (typeof comp.paintHint === 'function') bootstrap = comp
        stack.push(...node.children)
      }
      void bootstrap.paintHint('提示净区测量')
    })
    await page.waitForFunction(() => {
      const stack = [window.cc.director.getScene()]
      while (stack.length) {
        const node = stack.pop()
        if (node.name === 'SettingsHint' && node.activeInHierarchy) return true
        stack.push(...node.children)
      }
      return false
    }, null, { timeout: 3000 })
    const result = await page.evaluate(() => {
      const scene = window.cc.director.getScene()
      const boxes = []
      const visit = (node) => {
        if (node.activeInHierarchy && (/Button|SettingsHint|Message|SelectedTitle|SelectedStatus|ActionBar|DetailBar/.test(node.name))) {
          const box = node.getComponent('cc.UITransform')
          if (box) {
            const r = box.getBoundingBoxToWorld()
            boxes.push({ name: node.name, rect: { left: r.x, right: r.x + r.width, bottom: r.y, top: r.y + r.height } })
          }
        }
        for (const child of node.children) visit(child)
      }
      visit(scene)
      const hint = boxes.find(item => item.name === 'SettingsHint')
      const overlap = boxes.filter(item => item !== hint && hint && item.rect.right > hint.rect.left + 1
        && item.rect.left < hint.rect.right - 1 && item.rect.top > hint.rect.bottom + 1
        && item.rect.bottom < hint.rect.top - 1)
      return { hint, overlap, boxes: boxes.filter(item => /DetailBuildButton|DetailActionBar|Message|SelectedStatus|ZoomInButton|ZoomOutButton|TechOpenButton/.test(item.name)) }
    })
    await page.screenshot({ path: `D:/tmp/ironoath-hint-clearance-${width}x${height}.png` })
    if (result.overlap.length > 0 || !result.hint || errors.length > 0) failed = true
    console.log(JSON.stringify({ passed: result.overlap.length === 0 && !!result.hint && errors.length === 0,
      width, height, ...result, errors }))
    await context.close()
  }
} finally {
  await browser?.close()
  await preview.close()
}
if (failed) process.exitCode = 1
