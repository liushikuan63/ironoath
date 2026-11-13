#!/usr/bin/env node
/**
 * 职责：验「弱网下不崩溃、且有超时兜底与重试提示」这一条（上线检查清单 §四-6）。
 *
 * 为什么单列一份（#2525）：§四-6 长期是 `[ ]`，判定写的是「3G 模拟下不崩溃，请求有超时兜底与重试提示」，
 * 但全仓没有任何一份量具真的把**接口链路**降速过 —— `tools/verify-offline-runtime.mjs` 验的是离线收益
 * 这个业务功能（`/offline/*`），不是链路质量；`tools/verify-perf-runtime.mjs` 的弱网相是把后端掐掉，
 * 那测的是「连不上」，而弱网的真实形态是**连得上但很慢**。
 *
 * 手法（见本轮三次返工）：**只延迟接口请求，不动静态资源**。
 *   - 若用 CDP `Network.emulateNetworkConditions` 全局限速，连 3MB 的 bundle 也一起慢下来，
 *     量到的是「页面打不开」而不是「接口慢」；本探针第一版就是这么错的（B/C 双双假红）。
 *   - 改为 `page.route`：命中 API 前缀的请求延迟 `WEAK_DELAY_MS`（默认 8000ms，超过客户端超时）后再放行，
 *     静态资源照常。超时由客户端自己判，于是重试与提示才会真的出现。
 *
 * 断言三组（都能失败）：
 *   A 不崩溃：接口超时期间没有未捕获页面异常；
 *   B 超时兜底可见：屏上出现重试/网络类提示（读 Cocos 场景图 `NetworkNotice` 的 Label，不读 DOM）；
 *   C 恢复后可用：去掉延迟后重开一屏，新请求拿到 ≥2 条 200。
 *
 * 负控（WEAK_NEGCTL=fast）：不注入延迟地跑一遍 —— B 必须变红（否则它只是「跑就绿」），
 *                                      而 C 仍应绿（证明 C 不依赖延迟）。
 *
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 * 用法（必填后端；不给立刻退 2）：
 *   BACKEND_ORIGIN=http://localhost:8199 WEAK_PROBE_PORT=8194 node tools/verify-weak-network-runtime.mjs
 *
 * 退码：0 全部通过 / 1 有失败 / 2 前提不足（缺后端或客户端产物）。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { clickNodeViaCocos } from './lib/cocos-click.mjs'

const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[weak] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.WEAK_PROBE_PORT ?? 8194)
const NEGCTL = process.env.WEAK_NEGCTL ?? ''
const DELAY_MS = Number(process.env.WEAK_DELAY_MS ?? 8000)
const ARTIFACT = path.resolve(process.cwd(), 'client/build/web-mobile/index.html')
if (!existsSync(ARTIFACT)) {
  console.error(`[weak] 前提不足：产物不存在 ${ARTIFACT}（先跑 env -u ELECTRON_RUN_AS_NODE bash scripts/build-webmobile.sh）`)
  process.exit(2)
}
const OUT = path.resolve(process.cwd(), 'client/build/weak-network-verify')
mkdirSync(OUT, { recursive: true })

let pass = 0
let fail = 0
const check = (msg, actual, expected) => {
  if (actual === expected) { pass += 1; console.log(`  PASS  ${msg}（${String(actual)}）`) }
  else { fail += 1; console.log(`  FAIL  ${msg}：期望 ${String(expected)}，实际 ${JSON.stringify(actual)}`) }
}

/** 屏上出现过的重试/网络类提示（客户端文案）。 */
const HINTS = ['网络不稳定', '正在重试', '网络异常', '连接失败', '请求超时', '超时']

/**
 * API 路径没有 `/api` 前缀（客户端直接打 `/player/init`、`/city/list` …），
 * 而且可能打的是**后端 origin**（跨源）也可能经 preview 代理 —— 所以只按路径认，不看端口。
 * 第一版写了 `url.port === PORT` 与 `/api/` 前缀，结果一条都记不到，C 组因此假红。
 */
const API_PREFIXES = ['/player/', '/city/', '/stage/', '/army/', '/quest/', '/ops/', '/resource/', '/guide/', '/nation/', '/social/']
const isApiPath = (pathname) => API_PREFIXES.some((prefix) => pathname.startsWith(prefix))

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 弱网：产物经 ${preview.origin}，后端 ${BACKEND}${NEGCTL ? `，负控=${NEGCTL}` : ''} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await context.addInitScript(() => {
  try { window.localStorage.setItem('ironoath.deviceId', `probe-weak-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`) } catch { /* 隐私模式下忽略 */ }
})

const page = await context.newPage()
const pageErrors = []
page.on('pageerror', (err) => pageErrors.push(String(err)))
const apiResponses = [] // { path, status }
page.on('response', (response) => {
  try {
    const url = new URL(response.url())
    if (isApiPath(url.pathname)) apiResponses.push({ path: url.pathname, status: response.status() })
  } catch { /* 忽略解析不了的 URL */ }
})

/** 延迟开关：延迟期间慢接口，恢复后立刻放行。 */
let delaying = NEGCTL !== 'fast'
// 真正被延迟过的接口请求数。C 组只在「确实造出过慢相」时才有意义 ——
// 负控（不注入延迟）里没有"恢复"这回事，那里应当 SKIP 而不是判红，
// 否则负控会同时打红 B 和 C，读的人分不清哪一条是"判据真的在看慢相"。
let delayedApiRequests = 0
await page.route('**/*', async (route) => {
  let pathname = ''
  try { pathname = new URL(route.request().url()).pathname } catch { pathname = '' }
  if (delaying && isApiPath(pathname)) {
    delayedApiRequests += 1
    await new Promise((resolve) => setTimeout(resolve, DELAY_MS))
  }
  await route.continue().catch(() => {})
})
if (delaying) console.log(`  已注入接口延迟 ${DELAY_MS}ms（只延迟接口，静态资源照常）`)
else console.log('  ⚠️ 负控模式：不注入延迟。B 组（超时兜底提示）应当变红')

/** 读「屏幕上那行提示」：走 Cocos 场景图，不读 DOM（这游戏的字全在 Canvas 里）。 */
async function readNotice(target) {
  return target.evaluate(() => {
    const scene = window.cc?.director?.getScene?.() ?? null
    if (scene === null) return null
    let found = null
    const visit = (node) => {
      if (found !== null) return
      if (node.name === 'NetworkNotice') {
        const textNode = node.children.find((c) => c.getComponent && c.getComponent('cc.Label') !== null)
        const label = textNode === undefined ? null : textNode.getComponent('cc.Label')
        const uit = node.getComponent ? node.getComponent('cc.UITransform') : null
        found = {
          active: node.active,
          text: label === null ? null : label.string,
          // 只读 text 不够：节点 active 但被透明掉时屏上什么都看不见（第一版截图里就是空的）
          opacity: typeof node.opacity === 'number' ? node.opacity : null,
          size: uit === null ? null : { w: Math.round(uit.width), h: Math.round(uit.height) },
        }
        return
      }
      for (const child of node.children) visit(child)
    }
    visit(scene)
    return found
  })
}

await page.goto(`${preview.origin}/?panel=city`, { waitUntil: 'domcontentloaded' })

// B 组：轮询屏上提示（最多 60s，够 8s 延迟 + 客户端超时 + 一次重试）。
const hitHints = new Set()
const noticeSamples = []
let noticeShotTaken = false
const deadline = Date.now() + 60_000
while (Date.now() < deadline) {
  const notice = await readNotice(page).catch(() => null)
  noticeSamples.push(notice)
  if (notice !== null && notice.active && notice.text) {
    for (const hint of HINTS) if (notice.text.includes(hint)) hitHints.add(hint)
    if (hitHints.size > 0) {
      // 提示可能过几百毫秒就淡出，所以**命中当下**就截图 —— 先截一次 `notice.png`，
      // 循环外那张 `throttled.png` 只用来记录"这一屏长什么样"，不能当提示在场的证据。
      if (!noticeShotTaken) {
        noticeShotTaken = true
        await page.screenshot({ path: path.join(OUT, 'notice.png') }).catch(() => {})
        console.log(`        命中当下已截图 notice.png（opacity=${notice.opacity} 尺寸=${JSON.stringify(notice.size)}）`)
      }
      break
    }
  }
  await page.waitForTimeout(500)
}
await page.screenshot({ path: path.join(OUT, 'throttled.png') }).catch(() => {})

check('A 慢接口期间没有未捕获的页面异常', pageErrors.length, 0)
if (pageErrors.length > 0) console.log(`        异常样本：${pageErrors.slice(0, 3).join(' | ')}`)

const hinted = hitHints.size > 0
check('B 慢接口期间屏上出现了重试/网络类提示', hinted, true)
if (hinted) console.log(`        命中文案：${[...hitHints].join(' / ')}`)
else {
  const seen = noticeSamples.filter((s) => s !== null).slice(-3).map((s) => `${s.active}:${s.text}`)
  console.log(`        最近三次 NetworkNotice 读数为：${seen.join(' | ') || '（场景里没找到这个节点）'}`)
}

// C 组：去掉延迟，**不重开页面**，点一下底部「邮件」让客户端自己发一条请求。
// 这条判据是 #2525 的真正闭环：`NetworkNotice.observe(recovered)` 只有在**请求成功**时才会清空那一行，
// 所以"提示能不能收回"等价于"恢复后客户端还会不会发请求、并且成功"。
// perf 探针当年只观察到"提示一直挂着"，但没法区分是提示没接恢复信号、还是客户端干脆不再发请求。
delaying = false
await page.unrouteAll({ behavior: 'ignoreErrors' }).catch(() => {})
const before = apiResponses.length
const clicked = await clickNodeViaCocos(page, { labelPrefix: '邮件' }).catch((err) => ({ verified: false, reason: String(err) }))
console.log(`  恢复后点击底部「邮件」：verified=${clicked.verified} reason=${clicked.reason ?? '—'}`)
const recoverDeadline = Date.now() + 45_000
let cleared = false
while (Date.now() < recoverDeadline) {
  const notice = await readNotice(page).catch(() => null)
  if (notice !== null && !notice.active) { cleared = true; break }
  await page.waitForTimeout(500)
}
const freshCount = apiResponses.slice(before).filter((r) => r.status === 200).length
const countedPaths = [...new Set(apiResponses.map((r) => r.path))].slice(0, 8)
console.log(`  诊断：点击后新增 200 回包 ${freshCount} 条；API 回包累计 ${apiResponses.length} 条；样本路径=${countedPaths.join(', ') || '（一条都没记到）'}`)
if (delayedApiRequests === 0) {
  // 没有造出慢相，就没有"恢复"这件事：C 组在这里不适用。
  // 负控（WEAK_NEGCTL=fast）的判据是"B 必须变红"，不是"每一组都绿"。
  console.log(`  SKIP  C 恢复后拿到新的 200 回包 —— 本相没有注入任何延迟（慢相未发生），恢复这件事不存在`)
} else {
  check('C 恢复后不重开页面也能拿到新的 200 回包（≥1 条）', freshCount >= 1, true)
}
check('D 恢复后提示自动收回（NetworkNotice 变回 inactive）', cleared, true)

const sceneUp = await page
  .waitForFunction(() => Boolean(window.cc?.director?.getScene()?.getChildByName('Canvas')), { timeout: 60_000 })
  .then(() => true)
  .catch(() => false)
check('A 场景最终起来（Canvas 存在）', sceneUp, true)
check('A 全程累计未捕获异常仍为 0', pageErrors.length, 0)
await page.screenshot({ path: path.join(OUT, 'recovered.png') }).catch(() => {})

console.log(`=== 弱网：${pass} 通过 / ${fail} 失败 ===`)
console.log(`截图：${OUT}${path.sep}throttled.png、recovered.png`)
await context.close().catch(() => {})
await browser.close().catch(() => {})
await preview.close()
process.exit(fail === 0 ? 0 : 1)
