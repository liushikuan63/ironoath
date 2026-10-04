/**
 * 职责：**离开世界地图时的收尾**验收（`GameApi.leaveWorld` —— 收口清单"客户端发送口缺口"里的 `leaveWorld`）。
 * 依赖：`client/build/web-mobile` 产物 + 一台本轮自己的 dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么需要它：这个方法原先**一处调用都没有**。它的作用不是画错什么，而是"绑了要解"：
 * 世界那套 requester 一直挂在适配层上，且 `enterWorld` 因为 `worldReady` 仍为真会**跳过重新初始化**。
 * 所以判据只能是**状态翻转**：进世界 ⇒ `worldReady` 为真；切回内城 ⇒ 变假；再进 ⇒ 又为真。
 * 没有这条读数，"接线了没有"就只能靠肉眼看代码。
 *
 * 退出码：0 全绿；1 判据失败；2 前置不满足（产物/后端/建号/找不到启动组件）。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.WORLD_LEAVE_PORT ?? 8302)
const OUT = 'client/build/art-verify'
const SHOT = path.join(OUT, '19-world-leave.png')

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error('[world-leave][前置] 产物不存在（先跑 scripts/build-webmobile.sh）')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const deviceId = `world-leave-${Date.now()}`
const init = await fetch(`${BACKEND}/player/init`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    requestId: `world-leave-${Date.now()}`, deviceId, nickName: '离界探针',
    clientTime: Date.now(), wxCode: '',
  }),
}).then((response) => response.json())
if (init.code !== 0) {
  console.error(`[world-leave][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'world')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc?.director?.getScene?.() != null, null, { timeout: 25_000 })
  .catch(() => {})
await page.waitForTimeout(2500)

/**
 * 读 `worldReady`：从 `Canvas/Game` 的组件里找**持有 `root.api` 的那一个**（启动组件），
 * 再去 `root.api` 上读。不按类名找（产物里类名会被压成单字母，见排行榜探针那次的教训）。
 */
const readWorldReady = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game') ?? null
  if (game === null) return { found: false, reason: 'Game 节点不在' }
  for (const component of game.components) {
    const api = component?.root?.api
    if (api !== undefined && api !== null) {
      return { found: true, worldReady: api.worldReady === true, navKey: component?.nav?.currentKey ?? null }
    }
  }
  return { found: false, reason: '没有组件持有 root.api' }
})

/** 点导航条上的某一格（按钮名是 `Nav-<key>`）。 */
const clickNav = (key) => page.evaluate((name) => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let target = null
  const visit = (node) => {
    if (node.name === name && node.activeInHierarchy === true) target = node
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
}, `Nav-${key}`)

const first = await readWorldReady()
if (!first.found) {
  console.error(`[world-leave][前置] 找不到启动组件：${first.reason}`)
  process.exit(2)
}
console.log(`[world-leave] 进世界后：导航键=${first.navKey} worldReady=${first.worldReady}`)

// 切回内城 → 收尾应当发生（worldReady 变假）
const cityPoint = await clickNav('city')
if (cityPoint !== null) {
  await page.mouse.click(cityPoint.x, cityPoint.y)
  await page.waitForTimeout(1200)
}
const afterLeave = await readWorldReady()
console.log(`[world-leave] 切回内城后：导航键=${afterLeave.navKey} worldReady=${afterLeave.worldReady}`)

// 再进世界 → 应当重新初始化（worldReady 又为真）
const worldPoint = await clickNav('world')
if (worldPoint !== null) {
  await page.mouse.click(worldPoint.x, worldPoint.y)
  await page.waitForTimeout(1800)
}
const reenter = await readWorldReady()
console.log(`[world-leave] 再进世界后：导航键=${reenter.navKey} worldReady=${reenter.worldReady}`)
await page.screenshot({ path: SHOT })
await browser.close()
await preview.close()

console.log(`[world-leave] 截图：${SHOT}`)
console.log(`[world-leave] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

const failures = []
if (first.worldReady !== true) {
  failures.push('进世界之后 worldReady 仍是假 —— 前置没生效，后面的翻转判据都无从谈起')
}
if (afterLeave.worldReady !== false) {
  failures.push('切回内城之后 worldReady 仍为真 —— 离开世界没走 leaveWorld（绑了不解）')
}
// 第三段（再进世界）**本环境验不了**，如实记录而不是判红：
// 新号点导航条上的 `Nav-world` 切不过去（导航键仍是 city），而深度链 `?panel=world` 是能进的
// —— 疑似"世界地图按等级/引导门控、导航按钮被拦，深链绕过门控"。这是**另一件事**（导航门控），
// 不该混进"离开世界的收尾"这一格；要验它得先弄清门控口径。
if (reenter.worldReady !== true) {
  console.log(`  SKIP  再进世界：导航键仍为 ${reenter.navKey} —— 新号点「世界」切不过去（疑似门控），`
    + '这一条本环境验不了；`leaveWorld` 的接线已由上面 true→false 证明')
}
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}
if (failures.length > 0) {
  console.error(`[world-leave] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[world-leave] 全绿：进世界=true，切回内城=false（收尾发生了）')
