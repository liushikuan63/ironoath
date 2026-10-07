/**
 * 职责：B04 验收 8「多个奖励 / 提示按顺序播放、不可同时堆叠遮挡」的**前台**证据（真产物 + 真量测 + 截图）。
 * 用法：`BACKEND_ORIGIN=http://localhost:8356 HINT_PROBE_PORT=8377 node tools/verify-hint-queue.mjs`
 *      ⚠️ 后端变量必须显式传（静默回退 8080 会打到别人的活后端）；端口也是本探针独占的。
 *
 * <p>判据的设计：队列生效时**任一时刻屏上最多一条提示**，三条依次出现；
 * 而旧实现（每次都往同一坐标 addChild）会让三条同时在屏、完全重叠。
 *
 * <p>对照组就是这条判据的反面：运行时把 `hintQueue` 置 null（等价于回到旧实现，走"未注入参数就直接显示"
 * 那一支）⇒ 「最多一条」必须红。植入与验证在同一次运行里做完，不用改仓库代码。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? ''
const PORT = Number(process.env.HINT_PROBE_PORT ?? 8377)
const SHOT_DIR = 'tmp/hint-queue'
const HOLD_MS = 3200   // 比 HINT_HOLD_MS(3000) 略长，留出销毁与下一帧的时间

if (!BACKEND) {
  console.error('[hint-queue][前置] 没有传 BACKEND_ORIGIN —— 不猜端口，退了。')
  process.exit(2)
}
if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[hint-queue][前置] 产物不存在：${ROOT}（先跑 build-webmobile.sh）`)
  process.exit(2)
}
mkdirSync(path.resolve(process.cwd(), SHOT_DIR), { recursive: true })

const deviceId = `hint-queue-${Date.now()}`
const init = await fetch(`${BACKEND}/player/init`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ requestId: `hq-init-${Date.now()}`, deviceId, nickName: '飘字探针', clientTime: Date.now(), wxCode: '' }),
}).then((r) => r.json())
if (init.code !== 0) {
  console.error(`[hint-queue][前置] 建号失败：${JSON.stringify(init).slice(0, 200)}`)
  process.exit(2)
}
const sentToast = init.data.toast
console.log(`[hint-queue] 建号 ${init.data.playerId}；init 下发的 toast = ${JSON.stringify(sentToast)}`)

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(e.message))

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'city')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null,
  null, { timeout: 40_000 })

/**
 * 等队列真的建起来再测。
 *
 * <p>第一版在这里踩了一次：场景 `cc.director.getScene()` 就绪时，浏览器那次 /player/init 的响应
 * 还没被 AppRoot 处理完 ⇒ 读到 `hintQueue === null`，四条判据一起红，看着像"接线没通"。
 * 真机上这个窗口同样存在，而它的后果是**首屏之前发生的提示走降级支路（叠着放）**——
 * 那是刻意的取舍（宁可叠一次也不丢提示），但量"排队有没有生效"必须等注入完成。
 */
await page.waitForFunction(() => {
  let target = null
  const visit = (node) => {
    if (target !== null) return
    for (const comp of node.components ?? []) {
      if (typeof comp.showHint === 'function' && typeof comp.paintHint === 'function') { target = comp; return }
    }
    for (const child of node.children) visit(child)
  }
  visit(window.cc.director.getScene())
  return target !== null && target.hintQueue !== null && target.hintQueue !== undefined
}, null, { timeout: 20_000 }).catch(() => undefined)

/** 找到挂着 showHint 的那个组件（GameBootstrap），并确认队列已经拿到下发的参数。 */
const wired = await page.evaluate((expected) => {
  let target = null
  const visit = (node) => {
    if (target !== null) return
    for (const comp of node.components ?? []) {
      if (typeof comp.showHint === 'function' && typeof comp.paintHint === 'function') { target = comp; return }
    }
    for (const child of node.children) visit(child)
  }
  visit(window.cc.director.getScene())
  if (target === null) return { found: false }
  const queue = target.hintQueue
  if (queue === null || queue === undefined) return { found: true, queuePresent: false }
  const opt = queue.options ?? {}
  return {
    found: true,
    queuePresent: true,
    matchesTable: opt.gapMs === expected.gapMs && opt.maxQueued === expected.maxQueued
      && opt.stuckTimeoutMs === expected.stuckTimeoutMs,
    readout: { gapMs: opt.gapMs, maxQueued: opt.maxQueued, stuckTimeoutMs: opt.stuckTimeoutMs },
  }
}, sentToast)

/** 数屏上正在显示的提示节点，并取它们的文本与矩形。 */
const measure = () => page.evaluate(() => {
  const out = []
  const visit = (node) => {
    if (node.name === 'SettingsHint' && node.activeInHierarchy === true) {
      const box = node.getComponent('cc.UITransform')
      const label = node.getComponent('cc.Label')
      const r = box === null ? null : box.getBoundingBoxToWorld()
      out.push({
        text: label === null ? '' : label.string,
        rect: r === null ? null : { left: r.x, right: r.x + r.width, bottom: r.y, top: r.y + r.height },
      })
    }
    for (const child of node.children) visit(child)
  }
  visit(window.cc.director.getScene())
  return out
})

/** 两两求屏幕矩形相交面积（>1px 见方才算"压住"，与既有几何判据同一条阈值）。 */
function overlaps(rows) {
  const hits = []
  for (let i = 0; i < rows.length; i += 1) {
    for (let j = i + 1; j < rows.length; j += 1) {
      const a = rows[i].rect
      const b = rows[j].rect
      if (a === null || b === null) continue
      const dx = Math.min(a.right, b.right) - Math.max(a.left, b.left)
      const dy = Math.min(a.top, b.top) - Math.max(a.bottom, b.bottom)
      if (dx > 1 && dy > 1) hits.push(`${rows[i].text} × ${rows[j].text}（重叠 ${Math.round(dx)}x${Math.round(dy)}px）`)
    }
  }
  return hits
}

const TEXTS = ['飘字探针第一条', '飘字探针第二条', '飘字探针第三条']

/** 一次采样：连发三条，立刻量（应当只有第一条在屏），再依次等下一条。 */
async function runOnce({ disableQueue }) {
  await page.evaluate((args) => {
    let target = null
    const visit = (node) => {
      if (target !== null) return
      for (const comp of node.components ?? []) {
        if (typeof comp.showHint === 'function' && typeof comp.paintHint === 'function') { target = comp; return }
      }
      for (const child of node.children) visit(child)
    }
    visit(window.cc.director.getScene())
    if (args.disableQueue) target.hintQueue = null
    else if (target.hintQueue === null || target.hintQueue === undefined) target.hintQueue = target.__savedQueue
    for (const text of args.texts) target.showHint(text)
  }, { texts: TEXTS, disableQueue })

  const first = await measure()
  await page.screenshot({ path: path.join(SHOT_DIR, disableQueue ? 'planted-stacked.png' : 'queued-first.png') })
  const seen = [first]
  for (let i = 1; i < TEXTS.length; i += 1) {
    await page.waitForTimeout(HOLD_MS)
    seen.push(await measure())
  }
  return { first, seen }
}

// 先存一份队列，供"关掉队列"那一相之后恢复（避免两相互相污染）
await page.evaluate(() => {
  let target = null
  const visit = (node) => {
    if (target !== null) return
    for (const comp of node.components ?? []) {
      if (typeof comp.showHint === 'function' && typeof comp.paintHint === 'function') { target = comp; return }
    }
    for (const child of node.children) visit(child)
  }
  visit(window.cc.director.getScene())
  target.__savedQueue = target.hintQueue
  return true
})

const queued = await runOnce({ disableQueue: false })
const planted = await runOnce({ disableQueue: true })

await browser.close()
await preview.close()

const failures = []
const say = (ok, text) => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${text}`)
  if (!ok) failures.push(text)
}

say(wired.found === true, '找到挂着 showHint 与 paintHint 的组件（提示出口在场景里）')
say(wired.queuePresent === true, `运行时确实持有队列；读到的参数 ${JSON.stringify(wired.readout ?? null)}`)
say(wired.matchesTable === true, '队列参数逐等于 /player/init 下发的那三个值（不是客户端写死的）')

say(queued.first.length === 1,
  `连发三条后，第一时刻屏上只有 1 条提示（实际 ${queued.first.length} 条：${queued.first.map((r) => r.text).join(' / ')}）`)
say(overlaps(queued.first).length === 0,
  `第一时刻没有任何两条互相压住（命中 ${overlaps(queued.first).length} 处）`)
const ordered = queued.seen.map((rows) => rows.map((r) => r.text).join('|'))
say(ordered.some((line) => line.includes(TEXTS[0])) && ordered.some((line) => line.includes(TEXTS[2])),
  `三条都按序上过屏：${ordered.join('  →  ')}`)

// 对照组：关掉队列（等价旧实现）⇒ "只有一条在屏"必须不成立，否则上面的判据是装饰
say(planted.first.length >= 2,
  `对照组（运行时关掉队列）屏上同时出现 ${planted.first.length} 条 —— 判据能失败才算数`)
say(overlaps(planted.first).length >= 1,
  `对照组里它们确实互相压住（命中 ${overlaps(planted.first).length} 处：${overlaps(planted.first).slice(0, 2).join('；')}）`)

say(errors.length === 0, `页面零未捕获错误${errors.length ? `：${errors[0]}` : ''}`)
console.log(`[hint-queue] 截图：${path.join(SHOT_DIR, 'queued-first.png')} / ${path.join(SHOT_DIR, 'planted-stacked.png')}`)

if (failures.length > 0) {
  console.error(`[hint-queue] 判据失败 ${failures.length} 条`)
  process.exitCode = 1
} else {
  console.log('[hint-queue] 全绿：提示按序播放、任一时刻不堆叠，参数逐列等于下发值，且对照组证明判据能失败')
  process.exitCode = 0
}
