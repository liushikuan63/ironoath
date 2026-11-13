#!/usr/bin/env node
/**
 * 职责：验客户端启动/进城主堡时**真的**会打 `/player/power` 与 `/city/harvest-learning` 这两条读接口，
 * 且两者回的都是 `code 0` 的正常形状。
 *
 * 为什么单列这一份（#2520）：`#2518` 把 147 条客户端绑定路径逐条比对探针源码，发现两条路径
 * **没有任何探针提到过**，可 `perf` 那一轮的真实抓包里它们却被打到了 —— 说明客户端自己在预取，
 * 而探针清单漏了它们。这两条正好是「探针清单 vs 真实客户端」对不上的样本，所以用一份最小量具钉住：
 * 断言的不是「面板画得对不对」，而是「这条请求真的发生过、而且回得来」。
 *
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 * 用法（必填后端；不给立刻退 2 —— 静默回落到 8080 会让读数错得像产品缺陷）：
 *   BACKEND_ORIGIN=http://localhost:8199 BASELINE_PROBE_PORT=8193 node tools/verify-player-baseline-runtime.mjs
 *
 * 退码：0 全部通过 / 1 有失败 / 2 前提不足（缺后端或客户端产物）。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[baseline] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.BASELINE_PROBE_PORT ?? 8193)
const ARTIFACT = path.resolve(process.cwd(), 'client/build/web-mobile/index.html')
if (!existsSync(ARTIFACT)) {
  console.error(`[baseline] 前提不足：产物不存在 ${ARTIFACT}（先跑 env -u ELECTRON_RUN_AS_NODE bash scripts/build-webmobile.sh）`)
  process.exit(2)
}
const OUT = path.resolve(process.cwd(), 'client/build/player-baseline-verify')
mkdirSync(OUT, { recursive: true })

let pass = 0
let fail = 0
const check = (msg, actual, expected) => {
  if (actual === expected) { pass += 1; console.log(`  PASS  ${msg}（${String(actual)}）`) }
  else { fail += 1; console.log(`  FAIL  ${msg}：期望 ${String(expected)}，实际 ${JSON.stringify(actual)}`) }
}
const checkTrue = (msg, actual) => check(msg, actual, true)

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 客户端预取的两条基线读接口：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
// 固定 deviceId ⇒ 每次都是全新账号（否则第二次跑会拿到上一轮的进度）
await context.addInitScript(() => {
  try { window.localStorage.setItem('ironoath.deviceId', `probe-baseline-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`) } catch { /* 隐私模式下忽略 */ }
})

const page = await context.newPage()
// 自检口（负数对照）：BASELINE_NEGCTL=bad-power 时把 /player/power 的回包换成一个 code!=0 的假包，
// 断言那几条必须变红 —— 否则「探针绿」只证明它跑了，不证明它会红。
if (process.env.BASELINE_NEGCTL === 'bad-power') {
  await context.route('**/player/power*', async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': 'GET,POST,OPTIONS' } })
      return
    }
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json', 'access-control-allow-origin': '*' },
      body: JSON.stringify({ code: 1, message: '负控注入：故意回一个错误包', data: null }),
    })
  })
  console.log('  ⚠️ 负数对照已开启：/player/power 被替换成 code=1 的假回包，本跑应当变红')
}
const seen = new Map() // path -> [ { status, body } ]
page.on('response', async (response) => {
  const url = response.url()
  for (const key of ['/player/power', '/city/harvest-learning']) {
    if (!url.includes(key)) continue
    let body = null
    try { body = await response.json() } catch { body = null }
    const list = seen.get(key) ?? []
    list.push({ status: response.status(), body })
    seen.set(key, list)
  }
})

await page.goto(`${preview.origin}/?panel=city`, { waitUntil: 'domcontentloaded' })
// 等场景起来 + 主城面板把读接口打完（面板要等回包才画全，见 #2508 的面板巡游同一经验）
await page.waitForFunction(() => Boolean(window.cc?.director?.getScene()?.getChildByName('Canvas')), { timeout: 60000 })
await page.waitForTimeout(6000)
await page.screenshot({ path: path.join(OUT, 'city-panel.png') }).catch(() => {})

const powerHits = seen.get('/player/power') ?? []
const harvestHits = seen.get('/city/harvest-learning') ?? []

console.log(`  抓包：/player/power ${powerHits.length} 次；/city/harvest-learning ${harvestHits.length} 次`)
checkTrue('/player/power 至少被打到一次（客户端真的在预取它）', powerHits.length > 0)
checkTrue('/city/harvest-learning 至少被打到一次（客户端真的在预取它）', harvestHits.length > 0)

const lastBody = (hits) => (hits.length === 0 ? null : hits[hits.length - 1].body)
const powerBody = lastBody(powerHits)
const harvestBody = lastBody(harvestHits)

// 断言只认契约里真的有字段，不认「看起来像」：字段名以生成的 PowerDetailResp / CityHarvestLearningResp 为准
check('/player/power 回 code 0', powerBody?.code, 0)
check('/player/power 带 power 快照对象', typeof powerBody?.data?.power, 'object')
check('/player/power 带 breakdown', typeof powerBody?.data?.breakdown, 'object')
check('/player/power 的 currentMatchPower 是数字', typeof powerBody?.data?.currentMatchPower, 'number')
check('/city/harvest-learning 回 code 0', harvestBody?.code, 0)
check('/city/harvest-learning 的 resources 是数组', Array.isArray(harvestBody?.data?.resources), true)
check('/city/harvest-learning 带 requiredMainLevel', typeof harvestBody?.data?.requiredMainLevel, 'number')
check('/city/harvest-learning 带 unlockReason 字符串', typeof harvestBody?.data?.unlockReason, 'string')

// 反空转：两次请求的 HTTP 状态必须是 200（不是 4xx 被前端兜住）
for (const hit of [...powerHits, ...harvestHits]) {
  if (hit.status !== 200) { fail += 1; console.log(`  FAIL  非 200 响应：status=${hit.status}`) }
}
if (powerHits.length === 0 && harvestHits.length === 0) {
  // 一条都没抓到时不装作通过：直接把「这次是前提问题还是客户端真没请求」写清
  console.log('  ⚠️ 两条都没抓到：先确认后端是 dev 档、产物是刚构建的，再看客户端是否改了预取时机')
}

await context.close()
await browser.close()
await preview.close()

console.log(`=== 基线读接口：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)
