#!/usr/bin/env node
/**
 * 职责：验战令面板"一行档位都没有"时那一格会说人话，而不是留一片空白压在三行摘要下面。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 *   BACKEND_ORIGIN=http://localhost:8199 BATTLEPASS_PROBE_PORT=8192 node tools/verify-battlepass-empty-runtime.mjs
 *
 * <p><b>这一条判据是"双向一致"而不是"必须看到某句话"</b>：赛季开没开、有没有档位，
 * 取决于后端当时的状态，量具不该假设它。所以钉的是这条不变量 ——
 * **零行 ⇔ Notice 那一格写「暂无档位」**；有行时不许它冒出来（有档位还写"暂无"是自打嘴巴）。
 * 两个方向都能红，且不需要为它造夹具（#347 那份 `/hero/list` 夹具的教训：夹具没让面板画起来时，
 * "空态被藏起来"会静默通过）。
 *
 * <p>`header 非空` 那条是**反空转**：面板整块没画时行数也是 0，那时上面这条会假绿。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
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
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `bp-empty-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(e.message))

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

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'battlePass')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
let read = null
for (let i = 0; i < 30; i += 1) {
  await page.waitForTimeout(500)
  read = await page.evaluate(READ)
  if ((read?.header ?? '') !== '') break
}
console.log(`  读数：rows=${read?.rows} header=${JSON.stringify(read?.header)} notice=${JSON.stringify(read?.notice)}`)

// 反空转：面板没画起来的话，下面两条都毫无意义（#347 就是这么把假绿抓出来的）
check('战令面板真的画起来了（header 非空）', (read?.header ?? '') !== '', true)
check('零档位时那一格写「暂无档位」；有档位时不许写（双向一致，两个方向都能红）',
  (read?.rows ?? -1) === 0 ? read?.notice === '暂无档位' : read?.notice !== '暂无档位', true)

await page.screenshot({ path: path.join(OUT, 'battlepass-empty-or-rows.png') })
console.log(`  截图：${path.join(OUT, 'battlepass-empty-or-rows.png')}`)
check('跑完零页面级 error', errors.length, 0)
if (errors.length > 0) for (const e of errors.slice(0, 3)) console.log(`    error: ${e.slice(0, 160)}`)

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
