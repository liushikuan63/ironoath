#!/usr/bin/env node
/**
 * 职责：把「未搜索的目标搜索屏必须对玩家说一句下一步」钉成一条能失败的运行时判据（台账 #459）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199（不给就退 2 并点名这个变量：静默回落到别的后端，
 *   读数错得像产品缺陷 —— 台账 #371/#372）；端口 NOTICE_PORT（默认 8215）
 *   BACKEND_ORIGIN=http://localhost:8199 node tools/verify-targets-notice.mjs
 * 对照组：NOTICE_CONTROL=hero 时**期望读不到那句话**（用来证这条判据不是恒真）
 *   BACKEND_ORIGIN=http://localhost:8199 NOTICE_CONTROL=hero node tools/verify-targets-notice.mjs
 *
 * <p><b>为什么这一相只能运行时量</b>：`targetSearchNotice` 本身有免引擎单测（`client/tests/PowerPanel.test.ts`），
 * 但"未搜索那一相到底上没上屏"过的是另一条路：`render()` 在 `response === null` 时第一句就 return，
 * 所以那句必须在**建 Label 时**写一次（`TargetSearchView` 里 `addLabel` 之后那三行）。
 * 单测看不见这条 —— 把 `addLabel` 后那次写入删掉，941 项单测仍然全绿，屏上却是一片空白。
 *
 * <p><b>读的是场景图不是 DOM</b>：Cocos 把面板画在 canvas 上，DOM 里一个字都没有；
 * 组件只按注册名 `getComponent('cc.Label')` 取（release 产物会压缩类名，`constructor.name` 不可靠）。
 */
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import path from 'node:path'

const ROOT = path.resolve('client/build/web-mobile')
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-targets-notice] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.NOTICE_PORT ?? 8215)
const CONTROL = process.env.NOTICE_CONTROL ?? null
const WANT = '点搜索看看这一带有什么可打的'

/** 在页面里读那一格：`Overflow` 那颗 Label 的落地文本 + 面板内活动 Label 数。 */
function readNotice(panelKey) {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName(panelKey)
  if (!panel) return { missing: true }
  const texts = []
  let overflow = null
  const walk = (n) => {
    const lab = n.getComponent('cc.Label')
    if (lab && n.activeInHierarchy && String(lab.string ?? '').trim() !== '') {
      texts.push(lab.string)
      if (n.name === 'Overflow') overflow = lab.string
    }
    for (const c of n.children) walk(c)
  }
  walk(panel)
  return { overflow, labels: texts.length, texts }
}

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v),
  `notice-${CONTROL ?? 'targets'}-${Date.now()}`)
const page = await context.newPage()
const key = CONTROL ?? 'targets'
await page.goto(`${preview.origin}/?panel=${key}`, { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
  null, { timeout: 60_000 })
await page.waitForTimeout(4500)
await hideGuideOverlay(page)
const r = await page.evaluate(readNotice, key)
const shot = path.resolve(`client/build/notice-verify/targets-${key}.png`)
await page.screenshot({ path: shot })
await browser.close()
await preview.close()

const got = r.overflow ?? ''
console.log(`面板 ${key}：Overflow="${got}"（读不到=${r.overflow === null}）面板内活动 Label=${r.labels} 颗`)
console.log(`屏上文字：${(r.texts ?? []).join(' ｜ ')}`)
console.log(`SHOT  ${shot}`)
if (CONTROL !== null) {
  // 对照组：别的面板上不该有这句话 —— 没有这一条，"任何界面都算过"就是恒真
  if (got === WANT) {
    console.error(`[verify-targets-notice] 对照组失败：${key} 上出现了「${WANT}」，说明这条判据在任何界面都成立`)
    process.exit(1)
  }
  console.log(`[verify-targets-notice] 对照组正确：${key} 上没有那句话`)
  process.exit(0)
}
if (got !== WANT) {
  console.error(`[verify-targets-notice] 未搜索那一相没印那句引导：期望「${WANT}」，实际「${got}」`)
  process.exit(1)
}
console.log('[verify-targets-notice] 对：未搜索那一相给的是下一步动作，不是一片空白')
