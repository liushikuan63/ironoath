/**
 * 职责：「建造完成 → 落成贺礼」弹窗的**文案与几何**验收 —— 副标题必须印**服务端下发的显示名**
 * （如「落成贺礼」），屏上不许出现 `gift_building_celebration` 这类内部编号，
 * 且弹窗内的文字不许压在「立即购买」按钮上。
 * 依赖：`client/build/web-mobile` 产物 + 一台**本轮自己的** dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么不在 `verify-city-build-many` 里拍：弹窗由 `AppRoot.showGiftPopup()` 在**登录成功后问一次**
 * （GameBootstrap §B19 S3-iv），而那个工具会反复重载 —— 第一次问过之后就被全局冷却压住了
 * （10 分钟），后面的帧里也就不会再出现。所以这里走"先建好、再首次登录"的顺序。
 *
 * 用法：`BACKEND_ORIGIN=http://localhost:8163 node tools/verify-gift-popup.mjs`
 * 退出码：0 弹窗出现且显示名正确、无压字；1 判据失败；2 前置不满足。
 *
 * <p>2026-09-22 从 `tmp/probe-gift-popup.mjs` 迁进 `tools/`。它钉的缺陷是 #320 抓到的那一条：
 * 界面写着「商品 gift_building_celebration」，而 `pay_product` 那一行有中文名「落成贺礼」。
 * 几何那条判据自己先假红过一次（把按钮**自己的**「立即购买」也算成被压住）—— 现在按钮那棵子树整棵跳过。
 */
import { existsSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

// 默认仍吃 web-mobile；并行会话用 `outputName=` 建独立产物时用它指过去，不去覆盖别人的那一份。
const ROOT = process.env.GIFT_ARTIFACT_ROOT ?? 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.GIFT_PROBE_PORT ?? 8280)
const SHOT = 'client/build/art-verify/recheck-gift-popup.png'
const deviceId = process.env.GIFT_PROBE_DEVICE ?? `gift-probe-${Date.now()}`

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[gift-probe][前置] 产物不存在：${ROOT}`)
  process.exit(2)
}

const post = async (url, body, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  return response.json()
}

const init = await post('/player/init', {
  requestId: `gift-probe-init-${Date.now()}`, deviceId, nickName: '礼包探针',
  clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[gift-probe][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}
const playerId = init.data.playerId
const build = await post('/city/upgrade',
  { requestId: `gift-probe-build-${Date.now()}`, configId: 'lumber_camp', gridX: 1, gridY: 1 },
  { 'X-Player-Id': playerId })
if (build.code !== 0) {
  console.error(`[gift-probe][前置] 发起建造失败：${JSON.stringify(build)}`)
  process.exit(2)
}
console.log(`[gift-probe] 建号 ${playerId}，已在 Grid-1 发起伐木场（20 秒）；等它建完…`)
await new Promise((resolve) => setTimeout(resolve, 24_000))

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'city')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
// 登录 → 首屏 13 条 → showGiftPopup()：等弹窗真的出现在场景里
const appeared = await page.waitForFunction(() => {
  const scene = window.cc.director.getScene()
  let hit = false
  const visit = (node) => {
    if (hit) return
    if (node.name === 'giftPopup' && node.activeInHierarchy === true) hit = true
    for (const child of node.children) visit(child)
  }
  visit(scene)
  return hit
}, null, { timeout: 25_000 }).then(() => true).catch(() => false)
await page.waitForTimeout(600)

const texts = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const out = []
  const visit = (node, shown) => {
    const on = shown && node.activeInHierarchy === true
    const label = node.getComponent && node.getComponent('cc.Label')
    if (on && label !== null && label.string !== '') out.push(label.string)
    for (const child of node.children) visit(child, on)
  }
  visit(scene, true)
  return out
})

/**
 * 弹窗内部的**几何判据**：可见的每一行文字都不许压在「立即购买」按钮上。
 * 2026-09-21 复检抓到的形态就是「剩 59:59」藏在按钮后面（截图里只露出半行）——
 * 报价过期时间是这一屏唯一的时效信息，被盖掉等于看不见。
 */
const overlap = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let popup = null
  const visit = (node) => {
    if (popup !== null) return
    if (node.name === 'giftPopup') { popup = node; return }
    for (const child of node.children) visit(child)
  }
  visit(scene)
  if (popup === null) return { checked: 0, hits: [] }
  const rectOf = (node) => {
    const box = node.getComponent('cc.UITransform')
    if (box === null) return null
    const r = box.getBoundingBoxToWorld()
    return { left: r.x, right: r.x + r.width, bottom: r.y, top: r.y + r.height }
  }
  const rows = []
  const buttons = []
  const walk = (node, shown) => {
    const on = shown && node.activeInHierarchy === true
    // 按钮自己那一棵子树整棵跳过：它里面的「立即购买」当然落在按钮盒里，
    // 把它算进来就是假阳性 —— 这条判据问的是"**别的**文字有没有被按钮压住"。
    if (node.name === 'buy' && on) {
      const rect = rectOf(node)
      if (rect !== null) buttons.push(rect)
      return
    }
    const label = node.getComponent && node.getComponent('cc.Label')
    if (on && label !== null && label.string !== '') {
      const rect = rectOf(node)
      if (rect !== null) rows.push({ text: label.string, rect })
    }
    for (const child of node.children) walk(child, on)
  }
  walk(popup, true)
  const hits = []
  for (const row of rows) {
    for (const button of buttons) {
      const dx = Math.min(row.rect.right, button.right) - Math.max(row.rect.left, button.left)
      const dy = Math.min(row.rect.top, button.top) - Math.max(row.rect.bottom, button.bottom)
      if (dx > 1 && dy > 1) {
        hits.push(`「${row.text}」压在购买按钮上（重叠 ${Math.round(dx)}x${Math.round(dy)}px）`)
      }
    }
  }
  return { checked: rows.length, hits }
})
await page.screenshot({ path: SHOT })
await browser.close()
await preview.close()

console.log(`[gift-probe] 弹窗出现：${appeared ? '是' : '否'}`)
console.log(`[gift-probe] 屏上文本：${texts.join(' | ')}`)
console.log(`[gift-probe] 弹窗内文字 ${overlap.checked} 行，压按钮的 ${overlap.hits.length} 处`
  + `${overlap.hits.length ? '：' + overlap.hits.join('；') : ''}`)
console.log(`[gift-probe] 截图：${SHOT}`)
console.log(`[gift-probe] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

const failures = []
if (!appeared) {
  failures.push('弹窗没出现 —— 触发/频控/接线三者之一断了，先查 /gift/popup 的响应')
}
if (!texts.includes('落成贺礼')) {
  failures.push('屏上没有「落成贺礼」—— 显示名没下发，或界面没用 productName')
}
const leaked = texts.filter((text) => /gift_building_celebration|gift_/.test(text))
if (leaked.length > 0) {
  failures.push(`屏上出现了内部编号：${leaked.join('、')}`)
}
if (overlap.checked === 0) {
  failures.push('弹窗里一行可读文字都没有 —— 几何判据走不到，不许当绿')
}
if (overlap.hits.length > 0) {
  failures.push(`弹窗内文字被按钮压住：${overlap.hits.join('；')}`)
}
if (failures.length > 0) {
  console.error(`[gift-probe] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[gift-probe] 全绿：弹窗显示服务端下发的「落成贺礼」，屏上没有任何内部编号')


