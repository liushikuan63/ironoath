/**
 * 各界面排版横扫留帧：登录后逐页点底部导航，每页一张 1440×900 截图。
 *
 * <p>为什么按坐标点而不是找节点：导航是 Cocos 画布里的 17 颗按钮，无 DOM 可查；
 * 底部导航行在设计分辨率里是等距铺满的，实测首颗中心 (85,845)、末颗 (1355,845)，
 * 等距插值即可逐页命中（点偏了截图自己会说话，不需要机器判据假装命中）。
 *
 * 用法：BACKEND_ORIGIN=http://localhost:8171 node tools/shot-panel-sweep.mjs
 * 退出码：0 全部页都拍到且无 pageerror；2 前置不满足。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.SWEEP_PORT ?? 8297)
const OUT = process.env.SWEEP_OUT ?? 'tmp/layout-shots'
const PAGES = ['内城', '军队', '武将', '招募', '背包', '关卡', '战报', '任务',
  '战令', '邮件', '社交', '战力', '商店', '外观', '搜索', '地图', '设置']

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[sweep][前置] 产物不存在：${ROOT}`)
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
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const pos = await page.evaluate(() => {
      const scene = window.cc.director.getScene()
      let target = null
      const walk = (n) => {
        if (target === null && n.name === 'GuideNext' && n.active) target = n
        n.children.forEach(walk)
      }
      walk(scene)
      if (target === null) return null
      const w = target.getWorldPosition()
      const s = window.cc.view.getScaleX()
      return { x: window.innerWidth / 2 + w.x * s, y: window.innerHeight / 2 - w.y * s }
    })
    if (pos === null) return
    await page.mouse.click(Math.round(pos.x), Math.round(pos.y))
    await page.waitForTimeout(450)
  }
  // 引导的「我完成了」在第 1 步要等玩家真升级主城才推进（设计如此），
  // 横扫拍的是面板排版而不是引导流程，所以这里直接把 Guide 节点摘掉。
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

const first = 85
const step = (1355 - 85) / (PAGES.length - 1)
for (let i = 0; i < PAGES.length; i += 1) {
  await page.mouse.click(Math.round(first + step * i), 845)
  await page.waitForTimeout(900)
  await dismissGuide()
  const file = path.join(OUT, `panel-${String(i).padStart(2, '0')}-${PAGES[i]}.png`)
  await page.screenshot({ path: file })
  console.log(`[sweep] ${PAGES[i]} -> ${file}`)
}

await browser.close()
await preview.close()
if (errors.length > 0) {
  console.error(`[sweep][FAIL] 页面报错 ${errors.length} 条：${errors.slice(0, 3).join(' | ')}`)
  process.exit(1)
}
console.log(`[sweep] 全绿：${PAGES.length} 页截图完成，无 pageerror（playerId=${playerId}）`)
