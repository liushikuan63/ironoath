#!/usr/bin/env node
/**
 * 职责：验战令面板"一行档位都没有"时那一格会说人话，而不是留一片空白压在三行摘要下面。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 BATTLEPASS_PROBE_PORT（默认 8192，同机并发时换一个）
 *   BACKEND_ORIGIN=http://localhost:8199 BATTLEPASS_PROBE_PORT=8192 node tools/verify-battlepass-empty-runtime.mjs
 *
 * <p><b>相位 A 钉的是"双向一致"而不是"必须看到某句话"</b>：赛季开没开、有没有档位，
 * 取决于后端当时的状态，量具不该假设它。所以那条不变量是 ——
 * **零行 ⇔ Notice 那一格写「暂无档位」**；有行时不许它冒出来（有档位还写"暂无"是自打嘴巴）。
 * 两个方向都能红，且不需要为它造夹具。
 *
 * <p><b>相位 A 只能验到"有档位"那一侧</b>（dev 赛季是开的，实测 `rows=3`），零档位那一侧从来没有
 * 被真实数据走过 —— 所以相位 B 把 `/battlePass/status` 的 `tiers` 换成空数组，其余字段
 * 一律用 `route.fetch()` 拿服务端原值（照 `shot-world-labels.mjs` 的做法：夹具不假装自己知道契约）。
 *
 * <p>`header 非空` 那两条是**反空转**：面板整块没画时行数也是 0，那时上面两条判据会假绿。
 * #347 的 `/hero/list` 夹具就是这么静默判绿过一次。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[battlepass-empty] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.BATTLEPASS_PROBE_PORT ?? 8192)
const OUT = path.resolve(process.cwd(), 'client/build/battlepass-verify')
mkdirSync(OUT, { recursive: true })

let pass = 0
let fail = 0
const check = (msg, actual, expected) => {
  if (actual === expected) { pass += 1; console.log(`  PASS  ${msg}（${String(actual)}）`) }
  else { fail += 1; console.log(`  FAIL  ${msg}：期望 ${String(expected)}，实际 ${JSON.stringify(actual)}`) }
}

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 战令面板空态验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const READ = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('battlePass')
  if (panel === undefined || panel === null) return null
  let rows = 0
  let header = ''
  let notice = ''
  const walk = (n) => {
    if (n.name === 'PassRow' && n.active === true) rows += 1
    if (n.name === 'Header') header = n.getComponent('cc.Label')?.string ?? ''
    if (n.name === 'Notice') notice = n.getComponent('cc.Label')?.string ?? ''
    for (const c of n.children) walk(c)
  }
  walk(panel)
  return { rows, header, notice }
})()`
const errors = []

/** 打开战令面板并读回那一屏；`until` 不满足时最多等 15 秒（冷后端首连要注册 + 拉快照）。 */
const openBattlePass = async (context) => {
  const page = await context.newPage()
  page.on('pageerror', (e) => errors.push(e.message))
  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', 'battlePass')
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  // 自检：产物里那两处写死的后端地址有没有真的被改写成本轮要打的那棵。
  // 漏了这一句，传错变量名就是"打到另一台机器上读数"，红得像是产品缺陷（台账 #371）。
  preview.assertRewritten()
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
  let read = null
  for (let i = 0; i < 30; i += 1) {
    await page.waitForTimeout(500)
    read = await page.evaluate(READ)
    if ((read?.header ?? '') !== '') break
  }
  return { page, read }
}

// ---------- 相位 A：真后端（dev 赛季开着 ⇒ 有档位），只钉那条双向不变量 ----------
const ctxA = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await ctxA.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `bp-empty-${Date.now()}`)
const { page: pageA, read: readA } = await openBattlePass(ctxA)
console.log(`  相位 A 读数：rows=${readA?.rows} header=${JSON.stringify(readA?.header)} notice=${JSON.stringify(readA?.notice)}`)

// 反空转：面板没画起来的话，下面那条双向不变量毫无意义（#347 就是这么把假绿抓出来的）
check('相位 A：战令面板真的画起来了（header 非空）', (readA?.header ?? '') !== '', true)
// 反向那一半：修悬空分隔符不能修成"永远不拼"。有档位时区间那串必须还在表头里。
check('相位 A：有档位时表头仍带区间那串（不能把分隔符一律去掉）',
  readA?.header?.startsWith('赛季战令 · ') ?? false, true)
check('相位 A：零档位时那一格写「暂无档位」；有档位时不许写（双向一致，两个方向都能红）',
  (readA?.rows ?? -1) === 0 ? readA?.notice === '暂无档位' : readA?.notice !== '暂无档位', true)
await pageA.screenshot({ path: path.join(OUT, 'battlepass-empty-or-rows.png') })
console.log(`  截图：${path.join(OUT, 'battlepass-empty-or-rows.png')}`)
await ctxA.close()

// ---------- 相位 B：把档位数组钉成空 —— 零档位那一侧的唯一运行时证据 ----------
let stubbed = 0
const ctxB = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await ctxB.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `bp-zero-${Date.now()}`)
await ctxB.route('**/battlePass/status*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.continue()
    return
  }
  const response = await route.fetch()
  const envelope = await response.json()
  // 信封是 { code, msg, data, serverNow }，战令负载在 data 里（首版写在顶层 ⇒ 客户端读到的仍是原值）
  const payload = envelope?.data ?? null
  if (payload === null || typeof payload !== 'object' || !Array.isArray(payload.tiers)) {
    console.log(`  [夹具] /battlePass/status 不是预期的 code/data.tiers 信封（code=${envelope?.code}），不注入`)
    await route.fulfill({ response })
    return
  }
  stubbed += 1
  const before = payload.tiers.length
  payload.tiers = []
  await route.fulfill({ response, body: JSON.stringify(envelope) })
  console.log(`  [夹具] /battlePass/status 档位 ${before} → 0（seasonId/points/seasonEndAt 等保持服务端原值）`)
})
const { page: pageB, read: readB } = await openBattlePass(ctxB)
console.log(`  相位 B 读数：rows=${readB?.rows} header=${JSON.stringify(readB?.header)} notice=${JSON.stringify(readB?.notice)}`)

// 顺序就是纪律：先证明面板画过，再让"零行 ⇒ 空态"这一族判据计分。
check('相位 B：夹具真的改写过读口（命中数 > 0；为 0 说明下面的零行是别的原因）', stubbed > 0, true)
check('相位 B：面板真的画起来了（header 非空 —— 没画过则下面两条会静默判绿）',
  (readB?.header ?? '') !== '', true)
// #349 目视抓到的缺陷：`BattlePassPanelView` 无条件拼 `赛季战令 · ${rangeText}`，而零档位时
// rangeText 是空串 ⇒ 表头尾巴挂着一个没有内容的分隔符。两条一起钉：一条盯精确文案，
// 一条盯"任何以分隔符结尾"的写法（换成别的空段也不会放过）。
check('相位 B：零档位时表头就是「赛季战令」，不带悬空分隔符', readB?.header, '赛季战令')
check('相位 B：表头不以分隔符结尾（盯的是形状，不只是这一句文案）',
  /[·•]\s*$/.test(readB?.header ?? ''), false)
check('相位 B：零档位时行数是 0', readB?.rows, 0)
check('相位 B：那一格写「暂无档位」而不是一片空白', readB?.notice, '暂无档位')
await pageB.screenshot({ path: path.join(OUT, 'battlepass-zero-tiers.png') })
console.log(`  截图：${path.join(OUT, 'battlepass-zero-tiers.png')}`)

check('两相跑完零页面级 error', errors.length, 0)
if (errors.length > 0) for (const e of errors.slice(0, 3)) console.log(`    error: ${e.slice(0, 160)}`)

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
