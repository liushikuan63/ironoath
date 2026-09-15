#!/usr/bin/env node
/**
 * 职责：在真实浏览器里量客户端运行期的四项性能指标，并按 `global.json` 的阈值判定。
 * 依赖：node、playwright、**已启动的 dev 服务端（默认 8080）**、已构建的 `client/build/web-mobile`。
 *
 * <p>对应 `CC开发全流程.md` 阶段 6 里能在这台机器上做的四项：
 * 首屏 ≤ PERF_FIRST_SCREEN_MAX_MS、单个接口响应 ≤ PERF_PAYLOAD_MAX_BYTES、
 * JS 堆峰值 ≤ PERF_MEMORY_PEAK_MAX_MB、帧率 ≥ PERF_MIN_FPS。
 *
 * <p><b>阈值一律从 `contract/config/global.json` 读</b>（铁律 1：一个数一个家）——
 * 工具里写死一个 4MB 或 50fps，就等于给同一个事实造了第二个家，改了表这里也不会跟着动。
 *
 * <p><b>三项必须说清的边界</b>：
 * ① 这是**无头桌面浏览器**，不是低端安卓。帧率与内存在这里量到的是「有没有明显退化」，
 *    真机数字仍归阶段 6 的人工项 —— 本工具的输出里字段名带 `DesktopHeadless` 就是为了不让人误读；
 * ② 首屏从 `goto` 算到客户端自己那行 `[boot]` 结构化自检，**含真实登录请求**，不是纯渲染耗时；
 * ③ payload 只统计打到后端的响应体，不含客户端静态资源（那是包体预算管的事）。
 *
 * <p><b>反空转下限</b>：一次首屏至少要观测到 N 个后端响应、拿到 `[boot]`、量到非零堆 ——
 * 少任何一项都按失败处理，而不是"没量到所以跳过"（本项目踩过"检查器自己空转还全绿"）。
 */
import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'

const ROOT = path.resolve('client/build/web-mobile')
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.PERF_PORT ?? 8092)
const SOAK_SECONDS = Number(process.env.PERF_SOAK_SECONDS ?? 600)
const FPS_SAMPLE_MS = Number(process.env.PERF_FPS_SAMPLE_MS ?? 5000)

/** 阈值：全部来自配置表，工具内不写死任何一个数。 */
function threshold(id) {
  const table = JSON.parse(readFileSync('contract/config/global.json', 'utf8'))
  const row = table.rows.find((r) => r.id === id)
  if (row === undefined) {
    throw new Error(`global.json 里没有 ${id}：阈值必须住在配置表里，别在工具里补一个`)
  }
  return Number(row.value)
}

const LIMITS = {
  firstScreenMs: threshold('PERF_FIRST_SCREEN_MAX_MS'),
  payloadBytes: threshold('PERF_PAYLOAD_MAX_BYTES'),
  memoryPeakMb: threshold('PERF_MEMORY_PEAK_MAX_MB'),
  minFps: threshold('PERF_MIN_FPS'),
}

// 客户端的后端地址是**构建期写死在 Boot.scene 里的两个编辑器字段**（baseUrl / wsUrl），
// 不是运行期配置。于是本工具原先有一个会骗人的地方：`BACKEND_ORIGIN` 只改变"统计哪个前缀"，
// 不改变"客户端实际把请求打到哪台"。共享开发机上 8080 常被别人的旧构建占着，
// 症状就是"我明明指了自己那台，量出来的却是别人那台的延迟"，而且看不出破绽。
// 现在：指了非默认后端就真的把产物里的写死值换掉；换不动即判失败。
// 不给（默认就是 8080）时一个字节都不改，与改动前行为一致。
const BAKED_HTTP = 'http://localhost:8080'
const BAKED_WS = 'ws://localhost:8080/ws'
const REWRITE = BACKEND !== BAKED_HTTP
const BACKEND_WS = BACKEND.replace(/^http/, 'ws') + '/ws'
let rewriteHits = 0

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.json': 'application/json', '.css': 'text/css', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.wasm': 'application/wasm', '.svg': 'image/svg+xml',
  '.mem': 'application/octet-stream', '.data': 'application/octet-stream',
  '.ttf': 'font/ttf', '.map': 'application/json',
}

// content-type 必须按**解析后的文件路径**取：拿 path.extname('/') 会得到 ''
// → application/octet-stream → 浏览器把 index.html 当下载，表现为 page.goto 直接抛
// "Download is starting"。
const server = createServer(async (req, res) => {
  const url = decodeURIComponent((req.url ?? '/').split('?')[0])
  const target = path.join(ROOT, url === '/' ? 'index.html' : url)
  try {
    let buf = await readFile(target)
    if (REWRITE && ['.js', '.mjs', '.json'].includes(path.extname(target))) {
      const text = buf.toString('utf8')
      const after = text.split(BAKED_HTTP).join(BACKEND).split(BAKED_WS).join(BACKEND_WS)
      if (after !== text) {
        rewriteHits += text.split(BAKED_HTTP).length - 1 + text.split(BAKED_WS).length - 1
        buf = Buffer.from(after, 'utf8')
      }
    }
    res.writeHead(200, { 'content-type': MIME[path.extname(target)] ?? 'application/octet-stream' })
    res.end(buf)
    // SPA fallback 只对非文件路径生效，且不能被当成"资源存在"的证据
  } catch {
    const buf = await readFile(path.join(ROOT, 'index.html'))
    res.writeHead(200, { 'content-type': 'text/html' }).end(buf)
  }
})
await new Promise((r) => server.listen(PORT, r))

const browser = await chromium.launch({ headless: true, args: ['--enable-precise-memory-info'] })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `perf-${Date.now()}`)
const page = await context.newPage()

const errors = []
const backendResponses = []
let bootAtMs = null
let bootLine = null
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))
page.on('console', (m) => {
  if (m.type() === 'error') errors.push('console.error: ' + m.text())
  if (m.text().includes('[boot]') && bootAtMs === null) {
    bootAtMs = Date.now()
    bootLine = m.text()
  }
})
page.on('response', async (resp) => {
  if (!resp.url().startsWith(BACKEND)) return
  let bytes = 0
  try {
    bytes = (await resp.body()).length
  } catch {
    bytes = Number(resp.headers()['content-length'] ?? 0)
  }
  backendResponses.push({ path: new URL(resp.url()).pathname, status: resp.status(), bytes })
})

const startedAt = Date.now()
await page.goto(`http://localhost:${PORT}/`, { waitUntil: 'domcontentloaded' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null, { timeout: 30_000 })
const readyAt = Date.now()
const sceneReadyMs = readyAt - startedAt
// 等客户端自己那行 [boot]。它是"真的跑起来了"的权威信号（登录与十个面板预拉都完成才打），
// 因此首屏口径要用它 —— 但它比"场景就绪"晚几秒，等太短会把正常启动误判成"没起来"（本轮踩过）。
const bootDeadline = Date.now() + 20_000
while (bootAtMs === null && Date.now() < bootDeadline) {
  await page.waitForTimeout(200)
}
const firstScreenMs = (bootAtMs ?? readyAt) - startedAt
const bootLineSeen = bootAtMs !== null

/**
 * 客户端自报的首屏耗时（`[boot]` 里的 `bootMs`）。
 *
 * <p>本工具量的是<b>外部墙钟</b>（从 goto 到看见那行），而小游戏运行时里没有这样一个外部时钟
 * —— 那一头唯一的输入就是这个自报数。所以这里顺手核对它真的在：缺了它，
 * 「首屏耗时的小游戏运行时版本」这条仍然不是难量，而是没有东西可量。
 */
const clientBootMs = (() => {
  if (bootLine === null) {
    return null
  }
  const hit = /"bootMs":([0-9]+)/.exec(bootLine)
  return hit === null ? null : Number(hit[1])
})()
/** 两个时钟之差 = 本工具能看到、而自报数看不到的那一段（HTML/JS 下载与解析、引擎初始化）。 */
const preJsLoadMs = clientBootMs === null ? null : firstScreenMs - clientBootMs

const cdp = await context.newCDPSession(page)
await cdp.send('Performance.enable')
async function heapUsedMb() {
  const { metrics } = await cdp.send('Performance.getMetrics')
  const row = metrics.find((m) => m.name === 'JSHeapUsedSize')
  return row ? Number((row.value / (1024 * 1024)).toFixed(1)) : 0
}

const fps = await page.evaluate(async (sampleMs) => {
  let frames = 0
  const start = performance.now()
  await new Promise((resolve) => {
    const tick = () => {
      frames += 1
      if (performance.now() - start < sampleMs) requestAnimationFrame(tick)
      else resolve()
    }
    requestAnimationFrame(tick)
  })
  const seconds = (performance.now() - start) / 1000
  return Number((frames / seconds).toFixed(1))
}, FPS_SAMPLE_MS)

const heapStartMb = await heapUsedMb()
console.log(`[perf] 首屏 ${firstScreenMs}ms（[boot] ${bootLineSeen ? '已捕获' : '未捕获，退到场景就绪'}），` +
  `客户端自报 ${clientBootMs ?? '无'}ms（差 ${preJsLoadMs ?? '无'}ms = 本包 JS 被求值之前的下载与解析），` +
  `帧率 ${fps}（无头桌面 5s 采样），堆起始 ${heapStartMb}MB；开始 ${SOAK_SECONDS}s 挂机采样…`)
let heapPeakMb = heapStartMb
for (let elapsed = 0; elapsed < SOAK_SECONDS; elapsed += 30) {
  await page.waitForTimeout(Math.min(30, SOAK_SECONDS - elapsed) * 1000)
  const now = await heapUsedMb()
  heapPeakMb = Math.max(heapPeakMb, now)
  if ((elapsed / 30) % 4 === 0) console.log(`[perf]   +${Math.min(elapsed + 30, SOAK_SECONDS)}s 堆 ${now}MB（峰值 ${heapPeakMb}MB）`)
}
const heapEndMb = await heapUsedMb()

const biggest = Array.from(backendResponses)
  .sort((a, b) => b.bytes - a.bytes)
  .slice(0, 8)
  .map((r) => `${r.path} ${r.bytes}B`)
const maxPayload = backendResponses.reduce((max, r) => Math.max(max, r.bytes), 0)

const failures = []
if (!bootLineSeen) failures.push('没拿到 [boot] 自检行：首屏口径退化了，这次测量不算数')
// 自报数是小游戏运行时里唯一可读的首屏耗时（那边没有本工具这样的外部墙钟），所以它必须在
// 本工具跑得通的路径上先被核对存在 —— 否则"能在 DevTools 里读那一行"仍然只是一句愿望
if (bootLineSeen && clientBootMs === null) {
  failures.push('[boot] 里没有可解析的 bootMs：小游戏运行时那一头只能读这个自报数，缺它等于那条路又是空的')
}
if (clientBootMs !== null && clientBootMs > firstScreenMs) {
  failures.push(`自报首屏 ${clientBootMs}ms > 外部墙钟 ${firstScreenMs}ms：两个数在同一台机器的同一个时钟上，`
    + '客户端那段严格被包在里面 —— 只能是锚点被推迟（BootClock 的 import 被挪到了后面）或时钟倒拨被夹过')
}
if (backendResponses.length < 8) failures.push(`后端响应只观测到 ${backendResponses.length} 条（少于 8）：测量空转嫌疑`)
if (REWRITE && rewriteHits === 0) {
  failures.push(`指定了后端 ${BACKEND} 却在产物里一处都没换到：客户端实际打的仍是 ${BAKED_HTTP}，`
    + '这台量出来的延迟不是那台打出来的请求')
}
if (heapPeakMb <= 0) failures.push('没量到 JS 堆：内存这项等于没测')
if (firstScreenMs > LIMITS.firstScreenMs) failures.push(`首屏 ${firstScreenMs}ms > ${LIMITS.firstScreenMs}ms`)
if (maxPayload > LIMITS.payloadBytes) failures.push(`最大响应 ${maxPayload}B > ${LIMITS.payloadBytes}B（${biggest[0]}）`)
if (heapPeakMb > LIMITS.memoryPeakMb) failures.push(`堆峰值 ${heapPeakMb}MB > ${LIMITS.memoryPeakMb}MB`)
// 帧率**刻意不进 failures**：PERF_MIN_FPS 是给低端安卓真机定的数，而无头桌面浏览器在这台机器上
// 量到 ~38 —— 拿它判红等于造一个永远红、然后被所有人忽略的卡口。真机 FPS 仍归 CC 文档阶段 6 的人工项，
// 这里只把数字记下来，低于参数时额外说一句。
if (fps < LIMITS.minFps) {
  console.log(`[perf] 注意：无头桌面帧率 ${fps} 低于 PERF_MIN_FPS=${LIMITS.minFps}，` +
    '这一项只能用真机判定，本工具不据此判失败')
}
if (errors.length > 0) failures.push(`控制台报错 ${errors.length} 条`)

// ---------- 弱网相位 ----------
//
// 做法是**只掐后端**、不掐整条链路：静态资源照常加载（否则连游戏都起不来，量到的是"页面打不开"），
// 所有打到 :8080 的请求先失败一段时间，再放行。这模拟的是"接口不可用但客户端已经跑起来"，
// 正是 B16 弱网那一条要看的形态。
//
// 两条判据都只能从可观测量取：① 没有未捕获异常（不崩）；② 重试真的发生（同一条路径被打了不止一次）。
// **超时/重试的界面文案住在 Cocos 场景里，DOM 读不到**，所以这里不把它当判据 ——
// 真机上那半句要用眼睛看，不能用一个读不到的东西冒充证据。
const WEAK_KILL_MS = Number(process.env.PERF_WEAK_KILL_MS ?? 6000)
const weakErrors = []
const attempts = new Map()
let killing = true
const weakPage = await context.newPage()
weakPage.on('pageerror', (e) => weakErrors.push('pageerror: ' + e.message))
await weakPage.route(`${BACKEND}/**`, (route) => {
  const path = new URL(route.request().url()).pathname
  attempts.set(path, (attempts.get(path) ?? 0) + 1)
  if (killing) setTimeout(() => route.abort('failed'), 150)
  else route.continue()
})
await weakPage.goto(`http://localhost:${PORT}/`, { waitUntil: 'domcontentloaded' })
await weakPage.waitForTimeout(WEAK_KILL_MS)
killing = false
await weakPage.waitForTimeout(3000)
const weakAlive = await weakPage.evaluate(() =>
  window.cc !== undefined && window.cc.director.getScene() !== null)
const retriedPaths = Array.from(attempts.entries()).filter(([, n]) => n > 1).map(([p, n]) => `${p}×${n}`)
const weakNetwork = {
  note: '超时/重试的界面提示在 Cocos 场景里，DOM 读不到，本相位不据此判定',
  killedMs: WEAK_KILL_MS,
  attempts: Object.fromEntries(attempts),
  retriedPaths,
  aliveAfterRecovery: weakAlive,
  errors: weakErrors,
}
console.log(`[perf] 弱网：掐后端 ${WEAK_KILL_MS}ms，重试过的路径 ${retriedPaths.length} 条（${retriedPaths.join('、')}），` +
  `恢复后场景存活=${weakAlive}`)
await weakPage.close()

if (weakErrors.length > 0) failures.push(`弱网下出现未捕获异常 ${weakErrors.length} 条`)
if (retriedPaths.length === 0) failures.push('弱网下没有任何路径被重试：重试链路等于没生效')
if (!weakAlive) failures.push('弱网恢复后场景没有存活')

console.log(JSON.stringify({
  note: '无头桌面浏览器测量，真机数字仍归 CC 文档阶段 6 的人工项；阈值全部读自 global.json',
  limits: LIMITS,
  firstScreenMs,
  backend: { origin: BACKEND, rewrittenFromBaked: REWRITE, rewriteHits },
  clientReportedBootMs: clientBootMs,
  preJsLoadMs,
  sceneReadyMs,
  bootLineSeen,
  fpsDesktopHeadless: fps,
  soakSeconds: SOAK_SECONDS,
  heapStartMb,
  heapPeakMb,
  heapEndMb,
  backendResponseCount: backendResponses.length,
  maxPayloadBytes: maxPayload,
  biggestResponses: biggest,
  weakNetwork,
  errors,
  failures,
}, null, 2))

await browser.close()
server.close()
if (failures.length > 0) process.exitCode = 1
