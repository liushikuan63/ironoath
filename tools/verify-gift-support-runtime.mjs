#!/usr/bin/env node
/** B19 S3：真产物里的礼包处理态必须给可点的客服入口；用隔离后端运行。 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const BACKEND = process.env.BACKEND_ORIGIN
const OUT = path.resolve(process.env.GIFT_SUPPORT_OUT ?? 'tmp/gift-support')
const PORT = Number(process.env.GIFT_SUPPORT_PORT ?? 8252)
if (!BACKEND) { console.error('[gift-support][前置] 缺 BACKEND_ORIGIN'); process.exit(2) }
mkdirSync(OUT, { recursive: true })

let pass = 0
let fail = 0
const check = (name, condition) => {
  if (condition) { pass++; console.log(`  PASS ${name}`) }
  else { fail++; console.error(`  FAIL ${name}`) }
}
const read = () => {
  const game = window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
  const host = game?.getChildByName('giftPopup')
  const component = host?.getComponent('GiftPopupView')
  const support = host?.getChildByName('panel')?.getChildByName('support')
  const labels = []
  if (support) {
    const walk = node => {
      const label = node.getComponent('cc.Label')
      if (node.activeInHierarchy && label?.string) labels.push(label.string)
      node.children.forEach(walk)
    }
    walk(support)
  }
  return { host: !!host?.activeInHierarchy, bound: typeof component?.onSupport === 'function',
    support: !!support?.activeInHierarchy, labels, calls: window.__giftSupportCalls ?? 0 }
}

let preview
let browser
try {
  preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  await context.addInitScript(value => localStorage.setItem('ironoath.deviceId', value),
    `gift-support-${Date.now()}`)
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  await page.goto(`${preview.origin}/?panel=city`, { waitUntil: 'networkidle' })
  preview.assertRewritten()
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  await page.waitForTimeout(3200)
  await hideGuideOverlay(page)
  const prepared = await page.evaluate(() => {
    const game = window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
    const component = game?.getChildByName('giftPopup')?.getComponent('GiftPopupView')
    if (!component) return false
    window.__giftSupportWasBound = typeof component.onSupport === 'function'
    window.__giftSupportBound = component.onSupport
    window.__giftSupportCalls = 0
    component.onSupport = () => { window.__giftSupportCalls++ }
    const now = Date.now()
    component.attach({ popup: true, productId: 'fixture-gift', productName: '验收礼包',
      offerExpireAt: now + 60_000, serverNow: now })
    component.renderResult({ phase: 'processing', title: '支付状态待确认',
      detail: '若已扣款或奖励未到账，请联系客服', rewards: [], showSupport: true, minorNotice: null })
    return true
  })
  check('生产礼包组件已装配', prepared)
  await page.waitForTimeout(250)
  const shown = await page.evaluate(read)
  check('编排层把客服动作绑定给弹窗', await page.evaluate(() => window.__giftSupportWasBound === true))
  check('处理态有可见的联系客服按钮', shown.host && shown.support && shown.labels.includes('联系客服'))
  await page.screenshot({ path: path.join(OUT, 'support-visible.png') })
  const tapped = await page.evaluate(() => {
    const game = window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
    const button = game?.getChildByName('giftPopup')?.getChildByName('panel')?.getChildByName('support')
    if (!button?.activeInHierarchy) return false
    button.emit('touch-start')
    return true
  })
  check('联系客服按钮可触发一次动作', tapped && (await page.evaluate(read)).calls === 1)
  await page.evaluate(() => {
    const game = window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
    game.getChildByName('giftPopup').getComponent('GiftPopupView').renderResult({
      phase: 'delivered', title: '已到账', detail: null, rewards: [], showSupport: false, minorNotice: null,
    })
  })
  check('无需客服时按钮收起', !(await page.evaluate(read)).support)
  const fallback = await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const game = scene?.getChildByName('Canvas')?.getChildByName('Game')
    const host = game?.getChildByName('giftPopup')
    const component = host?.getComponent('GiftPopupView')
    if (!component || typeof window.__giftSupportBound !== 'function') return null
    component.onSupport = window.__giftSupportBound
    const now = Date.now()
    component.attach({ popup: true, productId: 'fixture-gift', productName: '验收礼包',
      offerExpireAt: now + 60_000, serverNow: now })
    component.renderResult({ phase: 'processing', title: '支付状态待确认',
      detail: '若已扣款或奖励未到账，请联系客服', rewards: [], showSupport: true, minorNotice: null })
    const button = host.getChildByName('panel')?.getChildByName('support')
    if (!button?.activeInHierarchy) return null
    button.emit('touch-start')
    const hints = []
    const walk = node => {
      if (node.name === 'SettingsHint') {
        node.children.forEach(child => {
          const label = child.getComponent('cc.Label')
          if (label?.string) hints.push(label.string)
        })
      }
      node.children.forEach(walk)
    }
    walk(scene)
    return { hidden: !host.activeInHierarchy, hints }
  })
  check('真实客服接线在浏览器给出说明且关闭礼包',
    fallback?.hidden === true && fallback.hints.some(text => text.includes('客服')))
  check('客服提示不向玩家泄露部署变量名',
    fallback !== null && fallback.hints.every(text => !/WECHAT_SUPPORT|_[A-Z]+/.test(text)))
  await page.screenshot({ path: path.join(OUT, 'support-browser-fallback.png') })
  check('页面没有异常', errors.length === 0)
  console.log(`[gift-support] ${pass} 通过 / ${fail} 失败；截图 ${OUT}`)
  process.exitCode = fail === 0 ? 0 : 1
} catch (error) {
  console.error(`[gift-support] ${error.message}`)
  process.exitCode = 1
} finally {
  if (browser) await browser.close()
  if (preview) await preview.close()
}
