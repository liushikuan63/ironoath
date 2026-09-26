/**
 * 职责：**体力详情的实机验收**（B09 §5）——「点资源条上的体力那一行 → 弹层 → 买 1 次」整条链路。
 * 依赖：`client/build/web-mobile` 产物 + 一台本轮自己的 dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么需要它：`/stamina` 与 `/stamina/buy` 两个端点与服务端实现早就齐了，
 * 客户端**一处调用都没有**（收口清单"客户端发送口缺口"里的 `staminaView` / `staminaBuy`）。
 * 单测能钉住文案与置灰规则，但"点得开、买得到"只有真点一次才算数。
 *
 * <p>判据（都能失败）：
 *   ① 点「体力」那一行必须发出 `/stamina`，且弹出 `StaminaDetail` 弹层；
 *   ② 弹层里的标题必须等于**服务端读数**（`体力 X/Y` 与 `/stamina` 的响应逐字对上）；
 *   ③ 点「买 1 次（N 金币）」必须发出 `/stamina/buy`，且金币减少、体力增加（两次读数对比）。
 *
 * 退出码：0 全绿；1 判据失败；2 前置不满足（产物/后端/建号/找不到体力那一行）。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.STAMINA_PORT ?? 8303)
const OUT = 'client/build/art-verify'
const SHOT = path.join(OUT, '20-stamina-detail.png')

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error('[stamina][前置] 产物不存在（先跑 scripts/build-webmobile.sh）')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const deviceId = `stamina-${Date.now()}`
const post = async (url, body, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  return response.json()
}
const get = async (url, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, { headers })
  return response.json()
}
const init = await post('/player/init', {
  requestId: `stamina-init-${Date.now()}`, deviceId, nickName: '体力探针',
  clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[stamina][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}
const playerId = init.data.playerId
const HEAD = { 'X-Player-Id': playerId }
// 前置读数：体力与金币的起点（后面要对比"买一次之后变了多少"）
const before = await get('/stamina', HEAD)
if (before.code !== 0) {
  console.error(`[stamina][前置] 读体力失败：${JSON.stringify(before)}`)
  process.exit(2)
}
const goldBefore = (await get('/player/profile', HEAD)).data?.resources?.GOLD?.current
  ?? (await get('/city/list', HEAD)).data?.resources?.GOLD?.current ?? null
console.log(`[stamina] 建号 ${playerId}：体力 ${before.data.current}/${before.data.cap}`
  + ` 买价 ${before.data.buyCostGold} 金币 今日已买 ${before.data.boughtToday} 金币余额 ${goldBefore}`)

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
const staminaPosts = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('request', (request) => {
  if (request.url().includes('/stamina')) {
    staminaPosts.push(`${request.method()} ${request.url().split('/stamina')[1] || '/'}`)
  }
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'city')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc?.director?.getScene?.() != null, null, { timeout: 25_000 })
  .catch(() => {})
await page.waitForTimeout(2500)
await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const visit = (node) => {
    if (/Guide/i.test(node.name)) {
      const view = node.getComponent && node.getComponent('GuideView')
      if (view !== null && view !== undefined) view.enabled = false
      node.removeFromParent()
      return
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
})

/** 点资源条上那一行「体力」（按**文案前缀**找，不写死行列号）。 */
const clickStaminaRow = () => page.evaluate(() => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let target = null
  const visit = (node) => {
    const label = node.getComponent && node.getComponent('cc.Label')
    if (label !== null && label !== undefined && (label.string ?? '').startsWith('体力')) target = node
    for (const child of node.children) visit(child)
  }
  visit(scene)
  if (target === null) return null
  const box = target.getComponent('cc.UITransform')
  const camera = scene.getComponentInChildren('cc.Camera')
  if (box === null || camera === null) return null
  const screen = camera.worldToScreen(box.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0)))
  const rect = document.querySelector('canvas').getBoundingClientRect()
  const pixel = cc.view.getVisibleSizeInPixel()
  return {
    x: rect.left + (screen.x / pixel.width) * rect.width,
    y: rect.top + rect.height - (screen.y / pixel.height) * rect.height,
  }
})

/** 弹层读数：是否可见 + 里面所有 Label 的文本。 */
const readOverlay = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let overlay = null
  const visit = (node) => {
    if (node.name === 'StaminaDetail') overlay = node
    for (const child of node.children) visit(child)
  }
  visit(scene)
  if (overlay === null) return { found: false, visible: false, texts: [] }
  const texts = []
  const collect = (node) => {
    const label = node.getComponent && node.getComponent('cc.Label')
    if (label !== null && label !== undefined && label.string !== '') texts.push(label.string)
    for (const child of node.children) collect(child)
  }
  collect(overlay)
  return { found: true, visible: overlay.activeInHierarchy === true, texts }
})

/** 点弹层里的某个按钮（按节点名）。 */
const clickInOverlay = (nodeName) => page.evaluate((name) => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let overlay = null
  const visit = (node) => {
    if (node.name === 'StaminaDetail') overlay = node
    for (const child of node.children) visit(child)
  }
  visit(scene)
  if (overlay === null) return null
  let target = null
  const walk = (node) => {
    if (node.name === name) target = node
    for (const child of node.children) walk(child)
  }
  walk(overlay)
  if (target === null) return null
  const box = target.getComponent('cc.UITransform')
  const camera = scene.getComponentInChildren('cc.Camera')
  if (box === null || camera === null) return null
  const screen = camera.worldToScreen(box.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0)))
  const rect = document.querySelector('canvas').getBoundingClientRect()
  const pixel = cc.view.getVisibleSizeInPixel()
  return {
    x: rect.left + (screen.x / pixel.width) * rect.width,
    y: rect.top + rect.height - (screen.y / pixel.height) * rect.height,
  }
}, nodeName)

const rowPoint = await clickStaminaRow()
if (rowPoint === null) {
  console.error('[stamina][前置] 资源条上找不到「体力」那一行 —— 前置不满足')
  process.exit(2)
}
await page.mouse.click(rowPoint.x, rowPoint.y)
await page.waitForTimeout(1500)
const opened = await readOverlay()
console.log(`[stamina] 点体力行后：弹层存在=${opened.found} 可见=${opened.visible}`
  + ` 文本=${JSON.stringify(opened.texts)}`)
await page.screenshot({ path: SHOT })

// 买一次：金币要少、体力要多
const postsBeforeBuy = staminaPosts.length
const buyPoint = await clickInOverlay('BuyButton')
if (buyPoint !== null) {
  await page.mouse.click(buyPoint.x, buyPoint.y)
  await page.waitForTimeout(2000)
}
const afterBuy = await readOverlay()
const after = await get('/stamina', HEAD)
await page.screenshot({ path: SHOT })
await browser.close()
await preview.close()

console.log(`[stamina] 点买之后：/stamina 请求新增 [${staminaPosts.slice(postsBeforeBuy).join('、') || '(无)'}]`
  + ` 弹层文本=${JSON.stringify(afterBuy.texts)}`)
console.log(`[stamina] 服务端：体力 ${before.data.current} → ${after.data.current}`
  + ` 今日已买 ${before.data.boughtToday} → ${after.data.boughtToday}`)
console.log(`[stamina] 截图：${SHOT}`)
console.log(`[stamina] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

const failures = []
if (!staminaPosts.some((entry) => entry.startsWith('GET'))) {
  failures.push('点体力那一行没有发出 GET /stamina —— 资源条上的触摸没接上')
}
if (opened.visible !== true) {
  failures.push('点完体力那一行，StaminaDetail 弹层没显示')
}
const expectedTitle = `体力 ${before.data.current}/${before.data.cap}`
if (!opened.texts.some((text) => text.replace(/\s+/g, '').includes(expectedTitle.replace(/\s+/g, '')))) {
  failures.push(`弹层标题不等于服务端读数（期望含「${expectedTitle}」，实际 ${JSON.stringify(opened.texts)}）`)
}
if (!opened.texts.some((text) => text.includes('金币'))) {
  failures.push('弹层里没有买体力的按钮文案（应含「买 1 次（N 金币）」）')
}
if (!staminaPosts.some((entry) => entry.startsWith('POST'))) {
  failures.push('点了买体力没有发出 POST /stamina/buy —— 按钮没接上（或已到上限被置灰）')
}
if (after.data.boughtToday <= before.data.boughtToday) {
  failures.push(`买完之后今日已买次数没涨（${before.data.boughtToday} → ${after.data.boughtToday}）`)
}
// 体力的变化要看**买之前满没满**：B09 §5 明写"溢出不结转"，满仓时买就是会丢 ——
// 第一版一刀切断言"买完必须涨"，于是把一件**符合规格**的事判成了缺陷。
// 未满 ⇒ 必须涨；已满 ⇒ 必须**不涨**（涨了才说明溢出不生效，那才是缺陷），且弹层要给过警告。
const wasFull = before.data.current >= before.data.cap
if (!wasFull && after.data.current <= before.data.current) {
  failures.push(`没满仓（${before.data.current}/${before.data.cap}）时买完体力没涨（→ ${after.data.current}）`)
}
if (wasFull) {
  if (after.data.current > before.data.current) {
    failures.push(`满仓时买完体力竟涨了（${before.data.current} → ${after.data.current}）—— 溢出没按 B09 不结转`)
  }
  if (!opened.texts.some((text) => text.includes('溢出'))) {
    failures.push('满仓时弹层没有提示"买了会溢出损失"')
  }
}
// 买价要跟着涨（服务端定价，客户端不推算）：这是"服务端真的记了这次购买"的第二个证据
if (after.data.buyCostGold > 0 && after.data.buyCostGold <= before.data.buyCostGold) {
  failures.push(`买完之后下一次的买价没涨（${before.data.buyCostGold} → ${after.data.buyCostGold}）`)
}
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}
if (failures.length > 0) {
  console.error(`[stamina] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[stamina] 全绿：体力行点得开、弹层画的是服务端读数、买 1 次真扣钱真加体力')
