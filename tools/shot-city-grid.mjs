/**
 * 内城格子的**干净视图**：把引导层关掉再拍 6×6 地皮。
 *
 * <p>为什么单独一个工具，而不是往 `verify-art-runtime.mjs` 里塞：那条量具每次都
 * `page.goto` 重载并只留最后一张截图，而这里要的是"遮罩已经消失的那一帧"。
 * 引导层（`Canvas/Game/Guide`）压在内城卡片上，正好遮住第 6 行 —— 那一行最能看出
 * 地皮是"按区分成四块"还是"还在按奇偶交替"。
 *
 * <p>颜色对不对的**机器判定不在这里**，在 `tests/CityGroundTint.test.ts`：
 * "同区同色、不成棋盘"是数据属性，不是渲染属性，用单测判它才会真的失败。
 * 本工具只负责"玩家真的看得见"这一半。
 *
 * 退出码：0 成功；2 前置不满足（产物 / 遮罩节点 / 场景结构没找到）。
 */
import { existsSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[city-grid][前置] 产物不存在：${ROOT}（先跑 Cocos 构建）`)
  process.exit(2)
}

const OUT = process.env.CITY_SHOT_OUT
  ?? path.resolve(process.cwd(), 'client/build/art-verify/art-city-grid.png')
const PORT = Number(process.env.CITY_SHOT_PORT ?? 8192)
const preview = await startPreviewServer({ root: ROOT, backend: 'http://localhost:8080', port: PORT })

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `city-grid-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'city')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await page.waitForTimeout(2000)

const probe = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game') ?? null
  if (game === null) return { ok: false, why: 'Canvas/Game 不在场景里' }
  const guide = game.getChildByName('Guide')
  if (guide === null) return { ok: false, why: 'Canvas/Game/Guide 不在场景里 —— 遮罩换了位置，截图仍会被挡住' }
  guide.active = false
  let ground = null
  let tiles = 0
  const visit = (n) => {
    if (n.name === 'Ground') ground = n
    if (/^Grid-\d+$/.test(n.name)) tiles += 1
    for (const c of n.children) visit(c)
  }
  visit(scene)
  return { ok: true, groundFound: ground !== null, tiles }
})
if (!probe.ok) {
  console.error(`[city-grid][前置] ${probe.why}`)
  await browser.close()
  await preview.close()
  process.exit(2)
}
await page.waitForTimeout(400)
await page.screenshot({ path: OUT })
await browser.close()
await preview.close()

if (errors.length > 0) {
  console.error(`[city-grid][前置] 页面报错 ${errors.length} 条：${errors[0]}`)
  process.exit(2)
}
if (!probe.groundFound) {
  console.error('[city-grid][前置] 场景里没有 Ground 容器 —— 地皮那一层没画出来')
  process.exit(2)
}
console.log(`[city-grid] 截图：${OUT}`)
console.log(`[city-grid] 格子节点 ${probe.tiles} 个，引导层已摘除`)
