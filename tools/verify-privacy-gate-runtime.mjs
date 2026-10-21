/**
 * V19 隐私启动闸门的浏览器回调桩：运行真 web-mobile 产物，模拟微信同意/拒绝回调。
 * 只验客户端是否等待回调、拒绝后零业务请求及可读遮罩；微信原生弹窗仍须真机验。
 * 用法：node tools/verify-privacy-gate-runtime.mjs（先 build-webmobile.sh）。
 */
import { existsSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const root = path.resolve('client/build/web-mobile')
if (!existsSync(path.join(root, 'index.html'))) {
  console.error('[privacy-gate] 缺 web-mobile 产物，先构建')
  process.exit(2)
}

const preview = await startPreviewServer({ root, backend: 'http://127.0.0.1:9',
  port: Number(process.env.PRIVACY_PROBE_PORT ?? 8488) })
let browser
try {
  browser = await chromium.launch({ headless: true })
  const results = []
  for (const phase of ['denied', 'accepted']) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
    await context.addInitScript((mode) => {
      window.__privacyProbe = { phase: mode, events: [], requests: [] }
      window.wx = {
        getPrivacySetting(options) {
          window.__privacyProbe.events.push('queried')
          setTimeout(() => options.success({ needAuthorization: true, privacyContractName: '隐私保护指引' }), 40)
        },
        requirePrivacyAuthorize(options) {
          window.__privacyProbe.events.push('prompted')
          setTimeout(() => {
            window.__privacyProbe.events.push(mode)
            if (mode === 'denied') options.fail({ errMsg: 'user denied' })
            else options.success()
          }, 120)
        },
        request(options) {
          window.__privacyProbe.requests.push(String(options.url))
          setTimeout(() => options.fail?.({ errMsg: 'probe backend intentionally absent' }), 0)
          return { abort() {} }
        },
        login(options) {
          options.fail?.({ errMsg: 'probe has no WeChat account' })
        },
      }
    }, phase)
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.goto(preview.origin, { waitUntil: 'domcontentloaded' })
    await page.waitForFunction(() => window.__privacyProbe.events.includes('prompted'), null, { timeout: 25_000 })
    const pending = await page.evaluate(() => ({ ...window.__privacyProbe }))
    await page.waitForFunction(() => window.__privacyProbe.events.includes(window.__privacyProbe.phase), null,
      { timeout: 25_000 })
    if (phase === 'denied') {
      await page.waitForFunction(() => {
        const scene = window.cc?.director?.getScene?.()
        const visit = node => node.name === 'PrivacyNotice' || node.children.some(visit)
        return scene ? visit(scene) : false
      }, null, { timeout: 25_000 })
    } else {
      await page.waitForFunction(() => window.__privacyProbe.requests.some(url => url.includes('/ops/app/version')),
        null, { timeout: 25_000 })
    }
    const state = await page.evaluate(() => {
      const scene = window.cc?.director?.getScene?.()
      const labels = []
      const walk = node => {
        const label = node.getComponent?.('cc.Label')
        if (label?.string) labels.push(label.string)
        node.children.forEach(walk)
      }
      if (scene) walk(scene)
      return { requests: window.__privacyProbe.requests, labels }
    })
    if (phase === 'denied') {
      await page.screenshot({ path: 'D:/tmp/ironoath-privacy-denied-20261022.png' })
    }
    results.push({ phase, pendingRequests: pending.requests.length, versionRequests:
      state.requests.filter(url => url.includes('/ops/app/version')).length,
    noticeVisible: state.labels.some(label => label.includes('隐私授权未完成')),
    errors })
    await context.close()
  }
  const denied = results[0]
  const accepted = results[1]
  const passed = denied.pendingRequests === 0 && denied.versionRequests === 0 && denied.noticeVisible
    && accepted.pendingRequests === 0 && accepted.versionRequests > 0
    && denied.errors.length === 0 && accepted.errors.length === 0
  console.log(JSON.stringify({ passed, results, screenshot: 'D:/tmp/ironoath-privacy-denied-20261022.png' }))
  if (!passed) process.exitCode = 1
} finally {
  await browser?.close()
  await preview.close()
}
