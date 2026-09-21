/**
 * 职责：证明像素法那一维在**页签相位**上不是瞎的 —— 运行时在互助页签的第一行上追加一块
 * "排在文字之后"的 `Graphics` 底板（#389 那处缺陷的形状），看这一维报不报得出来。
 * 依赖：node、playwright、已构建的 `client/build/web-mobile`、已启动的后端（互助数据来自 #396 的桩）。
 *
 * <p>为什么全程只动浏览器里的节点树：植入要生效只需要"有一块后画的板盖住行"，
 * 不需要改客户端源码 ⇒ 省掉一次 4 分钟的构建，也不会把工作树留在植入态。
 *
 * <p>判据三条，缺一不算过：植入前 0 处 / 植入后 > 0 处 / 撤掉后回到 0 处。
 * 用的是横扫同一份 `planPlateCoverage`（`tools/lib/plate-coverage.mjs`）——
 * 复制一份去验证，验证的就是另一个东西了。
 */
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { decodePng, diffRegion } from './lib/png-diff.mjs'
import { planPlateCoverage } from './lib/plate-coverage.mjs'

const BACKEND = process.env.LABELFIT_BACKEND ?? (() => {
  console.error('[plant] 缺 LABELFIT_BACKEND（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.PLANT_PORT ?? 8197)
const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `plant-${Date.now()}`)
const page = await context.newPage()
await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
  null, { timeout: 60_000 })
await page.waitForTimeout(2500)

const switched = await page.evaluate(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let hit = null
  const find = (n) => {
    if (hit !== null) return
    if (n.name === 'Tab_help' && n.activeInHierarchy) hit = n
    for (const c of n.children) find(c)
  }
  for (const p of game?.children ?? []) find(p)
  if (hit !== null) hit.emit('touch-start', null)
  return hit !== null
})
if (!switched) { console.error('[plant] 没找到 Tab_help 页签，无法验证'); await browser.close(); await preview.close(); process.exit(1) }
await page.waitForTimeout(1500)

async function measure(label) {
  const plan = await page.evaluate(planPlateCoverage, 'social')
  if (plan === null) { console.log(`  ${label}: 面板没读到`); return { hits: -1 } }
  if (plan.plates.length === 0) {
    console.log(`  ${label}: 候选 0 块、字形带 ${plan.bands.length} 条 ⇒ 报出 0 处`)
    return { hits: 0, bands: plan.bands.length }
  }
  const base = decodePng(await page.screenshot())
  let hits = 0
  for (const plate of plan.plates) {
    await page.evaluate((h) => { window.__plateNodes[h].getComponent('cc.Graphics').enabled = false }, plate.handle)
    await page.waitForTimeout(120)
    const after = decodePng(await page.screenshot())
    await page.evaluate((h) => { window.__plateNodes[h].getComponent('cc.Graphics').enabled = true }, plate.handle)
    await page.waitForTimeout(80)
    for (const bi of plate.bands) {
      const d = diffRegion(base, after, plan.bands[bi].rect, 24)
      if (d.changed > 0) {
        hits += 1
        console.log(`  ${label}: 「${plan.bands[bi].text}」被「${plate.name}」盖住，变了 ${d.changed}/${d.total} 像元`)
      }
    }
  }
  console.log(`  ${label}: 候选 ${plan.plates.length} 块、字形带 ${plan.bands.length} 条 ⇒ 报出 ${hits} 处`)
  return { hits, bands: plan.bands.length }
}

const before = await measure('植入前')
const planted = await page.evaluate(() => {
  const { Node, Color, UITransform } = window.cc
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.children.find((c) => c.name === 'social')
  let label = null
  const walk = (n) => {
    if (label !== null) return
    if ((n.getComponent('cc.Label')?.string ?? '').length > 0 && (n.parent?.name ?? '').startsWith('SocialRow')) label = n
    for (const c of n.children) walk(c)
  }
  walk(panel)
  if (label === null) return { ok: false, why: '没找到行标签（页签没切过去？）' }
  const row = label.parent
  const t = row.getComponent('cc.UITransform')
  const plate = new Node('probePlantPlate')
  // layer 跟着行节点走（`new Node()` 默认不是 UI_2D 层）
  plate.layer = row.layer
  row.addChild(plate)
  // **`window.cc.Graphics` 在这个构建里是 undefined**（Error 3804 = 传进去的组件类为空），
  // 但注册名可用 —— 量具自己也是靠 `getComponent('cc.Graphics')` 数到底板的，同一口径。
  const g = plate.addComponent('cc.Graphics')
  const w = t.width
  const h = t.height
  const ctor = row.getComponent('cc.Graphics')?.fillColor?.constructor ?? Color
  plate.getComponent('cc.UITransform').setContentSize(w, h)
  g.fillColor = new ctor(240, 40, 40, 255)
  g.rect(-w / 2, -h / 2, w, h)
  g.fill()
  window.__probePlant = plate
  return { ok: true, row: row.name, label: label.getComponent('cc.Label').string.slice(0, 8) }
})
console.log('  植入：', JSON.stringify(planted))
const after = await measure('植入后')
await page.evaluate(() => { window.__probePlant?.destroy(); window.__probePlant = null })
const reverted = await measure('撤掉后')

const verdict = before.hits === 0 && planted.ok === true && after.hits > 0 && reverted.hits === 0
console.log(`[plant] 结论：植入前 ${before.hits} / 植入后 ${after.hits} / 撤掉后 ${reverted.hits} ⇒ `
  + (verdict ? '页签相位上这一维看得见后画的底板，"0 处"不是瞎' : '判不出来 —— 这一维在相位上不可信'))
await browser.close()
await preview.close()
process.exit(verdict ? 0 : 1)
