#!/usr/bin/env node
/**
 * 职责：把「游戏内真的能看到第三方素材署名」钉成一条能失败的运行时判据（V14；CC BY 3.0 的授权条件）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199（不给就退 2 并点名这个变量：静默回落到别的后端会把读数
 *   错得像产品缺陷 —— 台账 #371/#372）；端口 CREDITS_PORT（默认 8219）
 *   BACKEND_ORIGIN=http://localhost:8199 node tools/verify-credits.mjs
 *
 * <p><b>期望串从哪来</b>：不硬编码在探针里 —— 从 `art-src/ATTRIBUTION.md` 现取那一行
 * （`Icons made by …`），因为**那份文件才是署名的事实源**；它没了就该判红，而不是探针自己记着。
 * 比对时去掉所有空白：折行会在断点处吃掉那个空格，逐字符比会假红。
 *
 * <p><b>它盯的四件事</b>：① 设置页有「开源许可与署名」这一行（授权条件的入口要在一级页面）；
 * ② 点它才出现覆盖层（对照组：点之前不出现）；③ 屏上能读到署名原文（去空白后与 ATTRIBUTION.md 一致）
 * 与许可名；④ 点「关闭」收得掉。截图落 `tmp/credits/`。
 */
import { mkdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const OUT = process.env.CREDITS_VERIFY_OUT ?? path.resolve(process.cwd(), 'tmp/credits')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.CREDITS_PORT ?? 8219)
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-credits] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const ARTIFACT = 'client/build/web-mobile'

let pass = 0
let fail = 0
const ok = (msg) => { pass += 1; console.log(`  PASS  ${msg}`) }
const bad = (msg) => { fail += 1; console.log(`  FAIL  ${msg}`) }
const check = (msg, actual, expected) => {
  if (actual === expected) {
    ok(`${msg}（${actual}）`)
  } else {
    bad(`${msg}：期望 ${expected}，实际 ${actual}`)
  }
}

/** 署名的事实源：ATTRIBUTION.md 里那段要求保留的原文。 */
const ATTRIBUTION = readFileSync('art-src/ATTRIBUTION.md', 'utf8')
const REQUIRED = ATTRIBUTION.split(/\r?\n/).find((line) => line.startsWith('Icons made by ')) ?? null
if (REQUIRED === null) {
  console.error('[verify-credits] art-src/ATTRIBUTION.md 里找不到那段署名原文（`Icons made by …`）—— 事实源没了，先查它')
  process.exit(2)
}
const strip = (text) => text.replace(/\s+/g, '')

/** 页面里读：设置页有没有那一行、覆盖层在不在、屏上写了什么。 */
function readCredits() {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  const settings = game.getChildByName('settings')
  const overlay = game.getChildByName('creditsOverlay')
  const texts = []
  let rowNode = false
  if (settings) {
    const walk = (n) => {
      if (n.name === 'row-credits') rowNode = true
      const label = n.getComponent('cc.Label')
      if (label && String(label.string ?? '').trim() !== '') texts.push(label.string)
      for (const child of n.children) walk(child)
    }
    walk(settings)
  }
  const overlayTexts = []
  let cardWidth = null
  let widestLabel = 0
  if (overlay) {
    const card = overlay.getChildByName('CreditsCard')
    if (card) {
      const transform = card.getComponent('cc.UITransform')
      cardWidth = transform ? transform.contentSize.width : null
    }
    const collect = (n) => {
      const label = n.getComponent('cc.Label')
      if (label && n.activeInHierarchy && String(label.string ?? '').trim() !== '') {
        overlayTexts.push(label.string)
        // Label 在 overflow=NONE 下的 contentSize 就是这段文字的实际宽度 —— 用它做"有没有一行顶出卡片"
        const t = n.getComponent('cc.UITransform')
        if (t) widestLabel = Math.max(widestLabel, t.contentSize.width)
      }
      for (const c of n.children) collect(c)
    }
    collect(overlay)
  }
  return {
    settingsMissing: !settings, rowNode, settingsTexts: texts,
    overlayMissing: !overlay, overlayActive: overlay ? overlay.activeInHierarchy : false, overlayTexts,
    cardWidth, widestLabel,
  }
}

/**
 * 按节点名点一下（`emit` 与真人按下走同一个回调）。
 *
 * <p>用**字符串形式**而不是 `page.evaluate(fn, ...)`：后者只接受一个参数，而且闭包里的助手函数
 * 序列化不过去（只有回调源码会被送进页面）—— 本仓其它探针也是这个写法。
 */
const clickByName = (name, event) => page.evaluate(`(() => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  let found = null
  const walk = (n) => {
    if (found) return
    if (n.name === '${name}' && n.activeInHierarchy) { found = n; return }
    for (const child of n.children) walk(child)
  }
  walk(game)
  if (!found) return false
  found.emit('${event}')
  return true
})()`)

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `credits-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()) })

await page.goto(`${preview.origin}/?panel=settings`, { waitUntil: 'networkidle' })
preview.assertRewritten()
await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
  null, { timeout: 60_000 })
await page.waitForTimeout(3500)
await hideGuideOverlay(page)
await page.waitForTimeout(500)

const before = await page.evaluate(readCredits)
check('设置页挂上了场景', before.settingsMissing, false)
check('设置页有「开源许可与署名」那一行', before.rowNode, true)
console.log(`  设置页文字：${(before.settingsTexts ?? []).join(' ｜ ')}`)
check('点之前覆盖层不出现（对照组）', before.overlayActive, false)

// 行的监听挂在 `touch-end` 上（`SettingsPanelView.drawActionRow`），不是 touch-start
const clicked = await clickByName('row-credits', 'touch-end')
await page.waitForTimeout(900)
const opened = await page.evaluate(readCredits)
check('点得动那一行', clicked, true)
check('点之后覆盖层出现', opened.overlayActive, true)
await page.screenshot({ path: path.join(OUT, 'credits.png') })

const shown = (opened.overlayTexts ?? []).join('')
console.log(`  覆盖层文字（${(opened.overlayTexts ?? []).length} 条）：${shown}`)
check('覆盖层有标题', shown.includes('开源许可与署名'), true)
check('署名原文（去空白后）与 art-src/ATTRIBUTION.md 一致', strip(shown).includes(strip(REQUIRED)), true)
check('许可名在屏上', shown.includes('Creative Commons Attribution 3.0'), true)
check('CC0 那一组也在（登记备查）', shown.includes('kenney.nl'), true)
check('屏上不出现内部路径与文件名', /art-src\/|\.ts\b|client\//.test(shown), false)

// 版式：没有一行顶出卡片内宽（首跑截图里"许可 · 备注"那行超出右沿约 8px，而当时探针全绿）
// ⚠ 下面那个 24 与 `CreditsOverlay.PADDING` 对应；改那边要同步这里
const innerWidth = (opened.cardWidth ?? 0) - 24 * 2
check('卡片内宽读得到（判据的分母）', innerWidth > 0, true)
check('没有一行超出卡片内宽', opened.widestLabel <= innerWidth, true)

const closed = await clickByName('CreditsCloseButton', 'touch-start')
await page.waitForTimeout(600)
const after = await page.evaluate(readCredits)
check('点「关闭」收得掉', after.overlayActive, false)
ok(`关闭键点得到（${closed}）`)

check('页面零错误', errors.join(' | ') || '无', '无')

await browser.close()
await preview.close()
console.log(` 截图：${path.join(OUT, 'credits.png')}`)
console.log(`\n=== 署名页运行时验收：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)
