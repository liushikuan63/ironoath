/**
 * 职责：出征编成弹层的**运行时**验收（B25-S1 的 S1d）—— 在真构建产物 + 真服务端上确认
 * 「弹层真的存在、默认是收起的、搜索面板正常画出来」。
 * 依赖：node、playwright、**已启动的 dev 服务端**、已构建的 `client/build/web-mobile`。
 * 用法：node tools/verify-march-runtime.mjs
 *
 * <p><b>本探针不验「点一行 → 编成 → 出征成功」那条完整链路</b>，这是刻意的：
 * 目标搜索的候选来自匹配池，而 dev 服上**没有任何对手**（空服没有真人，Bot 也没进池），
 * 新号自己又有新手护盾、不进候选池 —— 本探针实测 `searchTargets` 回 `targets: []`。
 * 而"给探针造一个对手"要么开作弊端点、要么改服务端配置，两条都是这个仓库明文禁止的。
 * 所以那条链路的证据分两段：**编排与纯逻辑**在 `client/tests/` 里逐条断言（含"只带选中的行"
 * "被拒不提收起"），**画没画出来**由本探针管。真机/真服上有人之后再人工复验一次。
 *
 * <p>端口与后端可用环境变量换（MARCH_PROBE_PORT / MARCH_BACKEND）：两个会话同时跑量具时，
 * 撞端口会表现为"读到别人的产物"，而那看起来像产物坏了。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from 'file:///D:/Java/GitHub/tieshi/tools/lib/preview-server.mjs'

const OUT = process.env.MARCH_VERIFY_OUT ?? path.resolve(process.cwd(), 'client/build/march-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.MARCH_PROBE_PORT ?? 8193)
const BACKEND = process.env.MARCH_BACKEND ?? 'http://localhost:8080'

let pass = 0
let fail = 0
const ok = (msg) => { pass += 1; console.log(`  PASS  ${msg}`) }
const bad = (msg) => { fail += 1; console.log(`  FAIL  ${msg}`) }
const check = (msg, actual, expected) => {
  if (actual === expected) {
    ok(`${msg}（${String(actual)}）`)
  } else {
    bad(`${msg}：期望 ${String(expected)}，实际 ${String(actual)}`)
  }
}

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 出征编成弹层运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `march-runtime-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'targets')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await page.waitForTimeout(1800)

// 弹层挂在 Game 节点下（不是面板，走的是自定义类而不是 panel() 那张按名字查表的通道）
const probe = await page.evaluate(`(() => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  if (!game) return { gameFound: false }
  const overlay = game.getChildByName('MarchCompose')
  const search = game.getChildByName('targets')
  return {
    gameFound: true,
    overlayFound: overlay !== null,
    overlayActive: overlay === null ? null : overlay.active,
    searchActive: search === null ? null : search.active,
  }
})()`)

check('场景装配出来了', probe.gameFound, true)
check('编成弹层节点存在（MarchCompose）', probe.overlayFound, true)
check('弹层默认是收起的（没点目标就不该弹）', probe.overlayActive, false)
check('搜索面板是打开的（探针进的这一页）', probe.searchActive, true)

await page.screenshot({ path: path.join(OUT, 'march-search-panel.png') })
console.log(`  截图：${path.join(OUT, 'march-search-panel.png')}`)
check('运行期零 error（页面级报错）', errors.length, 0)
if (errors.length > 0) {
  for (const message of errors.slice(0, 3)) console.log(`    error: ${message.slice(0, 160)}`)
}

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
