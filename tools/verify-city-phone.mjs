/**
 * 职责：**小屏（手机横屏）下的动作栏布局与命中**验收 —— 真机之前先在本机把这一层过掉。
 * 依赖：`client/build/web-mobile` 产物 + 一台本轮自己的 dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么需要它：内城选择栏的按钮是按**设计分辨率**摆的（`DetailBuildButton` x=0、
 * 加速 78/168、暂停/恢复 258、取消 348），而真机是 844×390 这种小屏横屏 ——
 * 设计坐标会被 `cc.view` 缩放/裁切，最右边的按钮完全可能落到屏幕外，或者被框、被导航条压住。
 * 本机无头浏览器一直是 1440×900，**这一层从来没量过**；而真机上触摸又比鼠标更容易暴露"差几个像素"。
 *
 * <p>判据（都能失败）：① 已激活的动作按钮，其中心点必须落在画布内并留 4px 余量；
 * ② 点「取消」（最右那只）必须真的发出 `/city/cancel` 请求 —— 只算坐标不算"点得到"是假绿；
 * ③ 按钮之间不许重叠到互相压住（同一时刻激活的两个按钮中心距 ≥ 各自宽度的一半）。
 *
 * 退出码：0 全绿；1 判据失败；2 前置不满足（产物/后端/建号）。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.SMALL_SCREEN_PORT ?? 8297)
const OUT = 'client/build/art-verify'
const SHOT = path.join(OUT, '16-city-small-window.png')
/** 手机横屏：iPhone 14 的 CSS 尺寸（真机上还要乘 DPR，这里看的是布局与命中）。 */
const PHONE = { width: 844, height: 390 }
/** 够宽的小窗：`GameBootstrap.installViewportGuard` 的阈值是 900，低于它 Web 上会弹「窗口太窄」。 */
const SMALL = { width: 960, height: 440 }

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error('[phone][前置] 产物不存在（先跑 scripts/build-webmobile.sh）')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const deviceId = `phone-${Date.now()}`
const post = async (url, body, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  return response.json()
}
const init = await post('/player/init', {
  requestId: `phone-init-${Date.now()}`, deviceId, nickName: '小屏探针',
  clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[phone][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}
const playerId = init.data.playerId
const build = await post('/city/upgrade',
  { requestId: `phone-build-${Date.now()}`, configId: 'lumber_camp', gridX: 1, gridY: 1 },
  { 'X-Player-Id': playerId })
if (build.code !== 0) {
  console.error(`[phone][前置] 发起建造失败：${JSON.stringify(build)}`)
  process.exit(2)
}

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
// 手机横屏 + 触摸：这一层与 1440×900 的鼠标探针不是一回事
const context = await browser.newContext({
  viewport: PHONE, hasTouch: true, isMobile: true, deviceScaleFactor: 2,
})
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
const cityPosts = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('request', (request) => {
  if (request.method() === 'POST' && request.url().includes('/city/')) {
    cityPosts.push(request.url().split('/city/')[1])
  }
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'city')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForTimeout(2500)

// ---------- 甲：窄视口（低于 900）必须**明确提示**，而不是把画面缩到看不清 ----------
//
// 这条是 2026-09-22 实测发现的：844×390 下客户端会盖一层「窗口太窄…建议至少 900px」并挡住所有输入
// —— 我第一版探针就是在这一层上"点不着按钮"，差点当成布局缺陷。
// 该守卫只在 Web 上装（`isWxRuntime()` 直接 return，注释明写"微信上屏幕宽度永远小于 900"），
// 所以真机不受影响；但 Web 侧这条行为本身要钉住。
const narrowHint = await page.evaluate(() => {
  const overlay = Array.from(document.querySelectorAll('div'))
    .find((node) => (node.textContent ?? '').includes('窗口太窄'))
  return overlay === null || overlay === undefined
    ? null : { visible: getComputedStyle(overlay).display !== 'none', text: overlay.textContent }
})
console.log(`[phone] 窄视口 ${PHONE.width}px：提示层=${narrowHint === null ? '(没有)' : narrowHint.visible}`
  + `${narrowHint === null ? '' : ` 文案=「${narrowHint.text.slice(0, 40)}…」`}`)

// ---------- 乙：够宽的小窗里，动作栏要真的点得到（触摸路径）----------
await page.setViewportSize(SMALL)
await page.waitForTimeout(1200)
const guardGone = await page.evaluate(() => {
  const overlay = Array.from(document.querySelectorAll('div'))
    .find((node) => (node.textContent ?? '').includes('窗口太窄'))
  return overlay === null || overlay === undefined || getComputedStyle(overlay).display === 'none'
})
console.log(`[phone] 拉到 ${SMALL.width}px 后提示层消失=${guardGone}`)
await page.waitForFunction(() => {
  if (window.cc === undefined || window.cc.director === undefined) return false
  const scene = window.cc.director.getScene()
  if (scene === null) return false
  const nav = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
  return nav !== null && nav !== undefined && nav.currentKey === 'city'
}, null, { timeout: 25_000 }).catch(() => {})
await page.waitForTimeout(2000)
// 摘引导层与落成贺礼弹窗（都会挡城景与动作栏）
await page.evaluate((source) => {
  const re = new RegExp(source)
  const scene = window.cc.director.getScene()
  const visit = (node) => {
    const label = node.getComponent && node.getComponent('cc.Label')
    if (/Guide/i.test(node.name) || (label !== null && label !== undefined && re.test(label.string ?? ''))) {
      const view = node.getComponent('GuideView')
      if (view !== null && view !== undefined) view.enabled = false
      node.removeFromParent()
      return
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
  const offline = (() => {
    let hit = null
    const walk = (n) => { if (hit !== null) return; if (n.name === 'OfflineReport') { hit = n; return } for (const c of n.children) walk(c) }
    walk(scene)
    return hit
  })()
  if (offline !== null) offline.active = false
}, /第\s*\d+\s*\/\s*\d+\s*步|我完成了|升级主城：/.source)

/** 选中伐木场那一格（按名字找格子，不写死 index）。 */
const clickNode = (nodeName) => page.evaluate((name) => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let target = null
  const visit = (node) => {
    if (node.name === name) target = node
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
}, nodeName)

const gridOf = (name) => page.evaluate((target) => {
  const scene = window.cc.director.getScene()
  let found = null
  const visit = (node) => {
    if (/^Grid-\d+$/.test(node.name)) {
      const collect = (child, out) => {
        const label = child.getComponent && child.getComponent('cc.Label')
        if (label !== null && label !== undefined && label.string.includes(target)) out.push(label.string)
        for (const grand of child.children) collect(grand, out)
      }
      const texts = []
      collect(node, texts)
      if (texts.length > 0) found = node.name
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
  return found
}, name)

const tileKey = await gridOf('伐木场')
if (tileKey === null) {
  console.error('[phone][前置] 画面上找不到伐木场那一格 —— 先确认建造真的起了')
  await browser.close()
  await preview.close()
  process.exit(2)
}
const tilePoint = await clickNode(tileKey)
if (tilePoint !== null) {
  // 必须用**触摸**：这个上下文是 hasTouch/isMobile，鼠标事件不会走 Cocos 的触摸分发，
  // 第一版用 page.mouse.click 点格子 ⇒ 什么都没选中（而 1440×900 那套探针一直是鼠标，所以没暴露）。
  await page.touchscreen.tap(tilePoint.x, tilePoint.y)
  await page.waitForTimeout(700)
}

/** 动作栏读数：每个按钮的激活态、屏幕点、尺寸；外加画布矩形。 */
const bar = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const camera = scene.getComponentInChildren('cc.Camera')
  const rect = document.querySelector('canvas').getBoundingClientRect()
  const pixel = window.cc.view.getVisibleSizeInPixel()
  const buttons = []
  const visit = (node) => {
    if (/^Detail.*Button$/.test(node.name) || node.name === 'CollectAllButton') {
      const box = node.getComponent('cc.UITransform')
      const screen = box === null || camera === null
        ? null : camera.worldToScreen(box.convertToWorldSpaceAR(new window.cc.Vec3(0, 0, 0)))
      const point = screen === null ? null : {
        x: rect.left + (screen.x / pixel.width) * rect.width,
        y: rect.top + rect.height - (screen.y / pixel.height) * rect.height,
      }
      buttons.push({
        name: node.name,
        active: node.activeInHierarchy === true,
        size: box === null ? null : [box.width, box.height],
        point: point === null ? null : [Math.round(point.x), Math.round(point.y)],
      })
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
  return {
    buttons,
    canvas: { left: rect.left, top: rect.top, width: rect.width, height: rect.height },
    designSize: [pixel.width, pixel.height],
  }
})

const postsBefore = cityPosts.length
const cancelPoint = await clickNode('DetailCancelButton')
if (cancelPoint !== null) {
  // 真机是**触摸**不是鼠标：用 touchscreen 点，走的是同一套命中测试但事件类型不同
  await page.touchscreen.tap(cancelPoint.x, cancelPoint.y)
  await page.waitForTimeout(1500)
}
await page.screenshot({ path: SHOT })
await browser.close()
await preview.close()

console.log(`[phone] 画布 ${JSON.stringify(bar.canvas)} 设计分辨率 ${JSON.stringify(bar.designSize)}`
  + ` 视口 ${PHONE.width}x${PHONE.height}`)
for (const button of bar.buttons) {
  console.log(`   ${button.name} active=${button.active} 尺寸=${JSON.stringify(button.size)}`
    + ` 屏幕点=${JSON.stringify(button.point)}`)
}
console.log(`[phone] 触摸点「取消」@${JSON.stringify(cancelPoint)} → /city/* 新增`
  + ` [${cityPosts.slice(postsBefore).join('、') || '(无)'}]`)
console.log(`[phone] 截图：${SHOT}`)
console.log(`[phone] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

const failures = []
// 窄视口必须有明确提示（而不是把 960 设计的画面硬缩到 844 上让人猜）
if (narrowHint === null) {
  failures.push(`窄视口 ${PHONE.width}px 下没有「窗口太窄」提示层 —— 玩家会面对一个缩小的画面不知怎么办`)
} else if (narrowHint.visible !== true) {
  failures.push(`窄视口 ${PHONE.width}px 下提示层存在但没显示`)
}
if (guardGone !== true) {
  failures.push(`拉到 ${SMALL.width}px 后「窗口太窄」提示层没消失 —— 够宽了还挡着就等于玩不了`)
}
const MARGIN = 4
const visible = bar.buttons.filter((button) => button.active === true)
if (visible.length === 0) {
  failures.push('一只激活的动作按钮都没有 —— 判据走不到，不许当绿')
}
for (const button of visible) {
  if (button.point === null) {
    failures.push(`${button.name} 激活了但算不出屏幕坐标`)
    continue
  }
  const [x, y] = button.point
  if (x < bar.canvas.left + MARGIN || x > bar.canvas.left + bar.canvas.width - MARGIN
      || y < bar.canvas.top + MARGIN || y > bar.canvas.top + bar.canvas.height - MARGIN) {
    failures.push(`${button.name} 的中心点 (${x},${y}) 落在画布外/贴边 —— 手机横屏上点不到它`)
  }
}
// 同时激活的按钮不许互相压住：中心距 ≥ 两者宽度一半的较大值
for (let i = 0; i < visible.length; i++) {
  for (let j = i + 1; j < visible.length; j++) {
    const a = visible[i]
    const b = visible[j]
    if (a.point === null || b.point === null || a.size === null || b.size === null) continue
    const gap = Math.hypot(a.point[0] - b.point[0], a.point[1] - b.point[1])
    const need = Math.max(a.size[0], b.size[0]) / 2
    if (gap < need) {
      failures.push(`${a.name} 与 ${b.name} 叠在一起（中心距 ${gap.toFixed(0)} < ${need.toFixed(0)}）—— 会互相抢点击`)
    }
  }
}
if (!cityPosts.includes('cancel')) {
  failures.push('在小屏上用**触摸**点「取消」没有发出 /city/cancel —— 按钮在这个尺寸下点不到')
}
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}
if (failures.length > 0) {
  console.error(`[phone] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[phone] 全绿：小屏横屏下动作栏都在画布内、互不重叠，且触摸能真的点到「取消」')
void url
