/**
 * 各界面排版横扫留帧：登录后逐页点底部导航，每页一张 1440×900 截图。
 *
 * <p>为什么按**节点世界坐标**点而不是等距插值：导航格从前是 17 颗等距铺满，插值能命中；
 * 2026-09-26 起常驻条只剩核心几格 + 「更多」，其余入口在抽屉里 —— 等距插值会点到空格。
 * 现读格子自己的世界坐标换算屏幕像素，格子数怎么变都不用改这里；
 * 抽屉里的格子先点「更多」展开（`Nav-<key>` 不可见就是它在抽屉里）。
 *
 * <p>页清单从 `PanelNav.ts` 现读（key + 中文标签），不在工具里抄第二份 ——
 * 抄一份的失效方式是"新面板没进横扫，截图齐 17 张，看着像全过了"。
 *
 * 用法：BACKEND_ORIGIN=http://localhost:8171 node tools/shot-panel-sweep.mjs
 * 退出码：0 全部页都拍到且无 pageerror；1 有页点不到或有 pageerror；2 前置不满足。
 */
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { nodeScreenPos } from './lib/node-screen-pos.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.SWEEP_PORT ?? 8297)
const OUT = process.env.SWEEP_OUT ?? 'tmp/layout-shots'
const NAV_SOURCE = 'client/assets/scripts/scene/PanelNav.ts'
const PAGES = Array.from(readFileSync(NAV_SOURCE, 'utf8')
  .matchAll(/^\s*\{ key: '([^']+)', label: '([^']+)'/gm), (m) => ({ key: m[1], label: m[2] }))

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[sweep][前置] 产物不存在：${ROOT}`)
  process.exit(2)
}
if (PAGES.length === 0) {
  // 解析不到清单就退出：继续跑会拍 0 张图然后打印"全绿"
  console.error(`[sweep][前置] 从 ${NAV_SOURCE} 解析不到任何面板 key`)
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const post = async (url, body, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  return response.json()
}
const init = await post('/player/init', {
  requestId: `sweep-init-${Date.now()}`, deviceId: `sweep-${Date.now()}`,
  nickName: '排版横扫', clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[sweep][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}
const playerId = init.data.playerId

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `sweep-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
await page.goto(preview.origin)
await page.waitForFunction(() => window.cc?.director?.getScene() != null, null, { timeout: 60000 })
await page.waitForTimeout(2500)

// 引导是逐步弹出的：每页截图前把「我完成了」真点掉（按节点世界坐标换算屏幕像素），
// 否则引导气泡会盖在面板上，横扫拍到的就不是面板本身的排版。
const dismissGuide = async () => {
  // **藏，不点**：点「我完成了」会发一次推进引导的写请求，而新号这一步的前置没满足 ⇒
  // 写失败、屏上换成"网络不稳定，正在重试"，板子还在（口径见 tools/lib/guide-overlay.mjs）。
  // 从前这里是按坐标点，而那份换算把世界原点当成了屏幕中心（实际在设计区左下角）⇒
  // 点一直落在视口外，全靠下面那道"强行 active=false"兜底，所以谁也没发现它从来没点中过。
  await hideGuideOverlay(page, 200)
  // 引导的「我完成了」在第 1 步要等玩家真升级主城才推进（设计如此），
  // 横扫拍的是面板排版而不是引导流程，所以这里再把 Guide 节点整个摘掉。
  await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const walk = (n) => {
      if (n.name === 'Guide') n.active = false
      n.children.forEach(walk)
    }
    walk(scene)
  })
}
await dismissGuide()
await page.waitForTimeout(400)

/**
 * 导航格的屏幕像素坐标（换算收在 `tools/lib/node-screen-pos.mjs` 一份里）。
 * 格子不存在、或此刻不可见（在关着的抽屉里）⇒ null，由调用方决定要不要先展开。
 */
const navCellPos = (key) => page.evaluate(nodeScreenPos, `Nav-${key}`)

const missed = []
for (let i = 0; i < PAGES.length; i += 1) {
  const { key, label } = PAGES[i]
  let pos = await navCellPos(key)
  if (pos === null) {
    // 这一格在「更多」抽屉里：先真点「更多」展开，再取它自己的坐标
    const more = await navCellPos('more')
    if (more !== null) {
      await page.mouse.click(Math.round(more.x), Math.round(more.y))
      await page.waitForTimeout(400)
      pos = await navCellPos(key)
    }
  }
  if (pos === null) {
    missed.push(key)
    console.error(`[sweep][MISS] 点不到导航格：${key}（条上与抽屉里都找不到可见的那颗）`)
    continue
  }
  await page.mouse.click(Math.round(pos.x), Math.round(pos.y))
  await page.waitForTimeout(900)
  await dismissGuide()
  const file = path.join(OUT, `panel-${String(i).padStart(2, '0')}-${label}.png`)
  await page.screenshot({ path: file })
  console.log(`[sweep] ${label} -> ${file}`)
}

// 抽屉展开态自己也要留一帧：它是这一轮新增的一级界面，逐页横扫时每帧都已经被收起
const morePos = await navCellPos('more')
if (morePos !== null) {
  await page.mouse.click(Math.round(morePos.x), Math.round(morePos.y))
  await page.waitForTimeout(600)
  const file = path.join(OUT, 'panel-more-抽屉展开.png')
  await page.screenshot({ path: file })
  console.log(`[sweep] 更多（抽屉展开） -> ${file}`)
} else {
  missed.push('more')
  console.error('[sweep][MISS] 点不到「更多」那格')
}

await browser.close()
await preview.close()
if (missed.length > 0) {
  console.error(`[sweep][FAIL] ${missed.length} 格点不到：${missed.join(', ')}`)
  process.exit(1)
}
if (errors.length > 0) {
  console.error(`[sweep][FAIL] 页面报错 ${errors.length} 条：${errors.slice(0, 3).join(' | ')}`)
  process.exit(1)
}
console.log(`[sweep] 全绿：${PAGES.length} 页 + 抽屉展开 1 帧截图完成，无 pageerror（playerId=${playerId}）`)
