#!/usr/bin/env node
/**
 * 职责：把「战报面板的『侦察情报』页签真的画得出来、切得过去、过期的那份说得不一样」
 * 变成能失败的判据（B26 S19：`GET /world/reports` 第一次有了读者）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 INTEL_PORT（默认 8197，同机并发时换一个）
 *   BACKEND_ORIGIN=http://localhost:8199 INTEL_PORT=8197 node tools/verify-scout-intel-runtime.mjs
 *
 * <p><b>为什么单独一个量具而不塞进 march 探针</b>：march 那份吃的是搜索/编成那一屏的节点树，
 * 这一份要读的是战报面板的行池。两者混在一起时"整棵树找同名节点"会把别的页签里同样叫
 * `Header` / `Title` 的文字一起读进来（第一版就是这么假红的）—— 一屏一个量具才读得准。
 *
 * <p><b>它盯的五件事</b>：① 两颗页签都在，默认显示战报那一份；② 点「侦察情报」后标题跟着换，
 * 并写明"几份、几份还有效"；③ 行的三段（坐标+等级 / 观测值+误差 / 有效或过期）各就各位，
 * **误差与数字同屏**（B07 验收 9：分开写玩家就会把带误差的数当精确值用）；
 * ④ 过期的那条不藏，但右侧写「过期」且行底色压暗；⑤ 空列表那格说的是"怎么弄出一份情报"，
 * 不是「暂无数据」。
 *
 * <p><b>不验的</b>：侦察队真的打到目标、情报真的按误差生成 —— 那是服务端 `WorldAppService`
 * 与 `WorldEndpointTest` 那一头的事；这里的敌情数据是**读接口夹具**（dev 新号一支侦察队都没派过）。
 */
import path from 'node:path'
import { mkdirSync } from 'node:fs'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[scout-intel] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.INTEL_PORT ?? 8197)
const OUT = path.resolve(process.cwd(), 'client/build/scout-intel-verify')
mkdirSync(OUT, { recursive: true })
const HOUR = 3_600_000

let pass = 0
let fail = 0
const check = (msg, actual, expected) => {
  if (actual === expected) {
    pass += 1
    console.log(`  PASS  ${msg}（${String(actual)}）`)
  } else {
    fail += 1
    console.log(`  FAIL  ${msg}：期望 ${String(expected)}，实际 ${JSON.stringify(actual)}`)
  }
}
const checkTrue = (msg, actual) => check(msg, actual, true)

/** 两份敌情：一份还有效、一份已过期（两份都要画出来，右侧那格要分得开）。 */
const SCOUT_FIXTURE = {
  reports: [
    {
      reportId: 'intel-live', target: { x: 100, y: 77 }, targetId: 'fixture-target-1',
      targetLevel: 7, createdAt: Date.now(), expiresAt: Date.now() + 3 * HOUR,
      expired: false, remainingMs: 3 * HOUR, errorFixed: 1200, seed: null,
      metrics: [{ name: 'power', value: 12_400 }, { name: 'infantry', value: 8000 },
        { name: 'grain', value: 4200 }, { name: 'gold', value: 900 }],
      serverNow: Date.now(),
    },
    {
      reportId: 'intel-old', target: { x: 51, y: 52 }, targetId: 'fixture-target-2',
      targetLevel: 3, createdAt: Date.now(), expiresAt: Date.now() - 1000,
      expired: true, remainingMs: 0, errorFixed: 2500, seed: null,
      metrics: [{ name: 'power', value: 900 }], serverNow: Date.now(),
    },
  ],
  serverNow: Date.now(),
}

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 侦察情报页签运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `intel-runtime-${Date.now()}`)
const cors = (request) => ({
  'access-control-allow-origin': request.headers()['origin'] ?? '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
})
const reply = async (route, data) => route.fulfill({
  status: 200,
  headers: { ...cors(route.request()), 'content-type': 'application/json' },
  body: JSON.stringify({ code: 0, msg: '成功', data, serverNow: Date.now() }),
})
const scoutReads = []
await context.route('**/world/reports*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  scoutReads.push(1)
  await reply(route, SCOUT_FIXTURE)
})

const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'reports')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
// 自检：产物里那两处写死的后端地址有没有真的被改写成本轮要打的那棵。
// 漏了这一句，传错变量名就是"打到另一台机器上读数"，红得像是产品缺陷（台账 #371）。
preview.assertRewritten()
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await hideGuideOverlay(page)

/**
 * 只读**战报面板子树里、当前激活的那些节点**的文字。
 * 之前整棵树乱找会把别的页签里同名的 `Header` / `Title` 一起读进来，症状是"读数全空但界面明明有"。
 */
const INTEL = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('reports')
  if (!panel || !panel.active) return { found: false }
  const byName = {}
  let tabColor = null
  let tabActive = null
  let tabText = null
  const walk = (node) => {
    const label = node.getComponent('cc.Label')
    if (label !== null && (label.string ?? '').length > 0) {
      if (node.name === 'TabScout') {
        tabColor = label.color.r + ',' + label.color.g + ',' + label.color.b
        tabActive = node.active
        tabText = label.string
      } else {
        byName[node.name] = (byName[node.name] ?? []).concat([label.string])
      }
    }
    for (const child of node.children) if (child.active) walk(child)
  }
  walk(panel)
  return { found: true, byName, tabColor, tabActive, tabText,
    battleTab: (() => {
      const t = panel.getChildByName('List')?.getChildByName('TabBattle')
      return t?.getComponent('cc.Label')?.string ?? null
    })() }
})()`

let intel = null
for (let i = 0; i < 40; i += 1) {
  await page.waitForTimeout(500)
  intel = await page.evaluate(INTEL)
  if (intel?.found && intel.tabActive === true && scoutReads.length > 0) break
}
check('战报面板是激活的那一页（深链 ?panel=reports 生效）', intel?.found, true)
check('两颗页签都画出来了', JSON.stringify([intel?.battleTab, intel?.tabText]),
  JSON.stringify(['战报', '侦察情报']))
check('敌情读口被打了一次（不多发）', scoutReads.length, 1)
checkTrue('默认停在战报那一份：标题里不写敌情那份的数量',
  !(intel?.byName?.Header ?? []).some((t) => t.includes('侦察情报')))

const tapped = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let tab = null
  const walk = (node) => {
    if (node.name === 'TabScout') tab = node
    for (const child of node.children) if (child.active) walk(child)
  }
  walk(game)
  if (tab === null) return false
  tab.emit('touch-start')
  return true
})()`)
checkTrue('按得到「侦察情报」那颗页签', tapped)
await page.waitForTimeout(600)
intel = await page.evaluate(INTEL)
const names = intel?.byName ?? {}
check('切过去后标题写明几份、几份还有效', (names.Header ?? [])[0], '侦察情报 2 份（1 份还有效）')
check('两行敌情都画出来了（过期的那份不藏）', (names.Title ?? []).length, 2)
check('第一行是「侦察：坐标 · 等级」（等级是报告里唯一无误差的字段）',
  (names.Title ?? [])[0], '侦察：100, 77 · 7 级')
const detail0 = (names.Detail ?? [])[0] ?? ''
checkTrue('观测值与误差同屏、一万以上折成万、还写着多久过期',
  detail0.includes('战力 1.2 万') && detail0.includes('±12%') && /小时后过期/.test(detail0))
check('右侧那一格两份分别写「有效」「过期」', JSON.stringify(names.Outcome ?? []),
  JSON.stringify(['有效', '过期']))
checkTrue('有两行情报时「还没有敌情」那行不出现（第一版两行下面还挂着它，画面自相矛盾）',
  (names.Empty ?? []).length === 0)
checkTrue('观测值那一行完整可读（左半边没被裁掉：盒子按行宽给过）',
  detail0.startsWith('战力 '))
checkTrue('选中那颗页签变成金色字（未选中是灰字：靠颜色不靠改字面）',
  intel?.tabColor === '184,134,11')
await page.screenshot({ path: path.join(OUT, 'scout-intel-tab.png') })
console.log(`  截图：${path.join(OUT, 'scout-intel-tab.png')}`)

// 空列表那一格：把读口换成空数组，重开一页验文案（"怎么弄出一份"而不是「暂无数据」）
await context.unroute('**/world/reports*')
await context.route('**/world/reports*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, { reports: [], serverNow: Date.now() })
})
const TAP_SCOUT = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let tab = null
  const walk = (node) => {
    if (node.name === 'TabScout') tab = node
    for (const child of node.children) if (child.active) walk(child)
  }
  walk(game)
  if (tab !== null) tab.emit('touch-start')
  return tab !== null
})()`
const page2 = await context.newPage()
page2.on('pageerror', (error) => errors.push(error.message))
const url2 = new URL(`${preview.origin}/`)
url2.searchParams.set('panel', 'reports')
await page2.goto(url2.toString(), { waitUntil: 'networkidle' })
await page2.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await page2.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let tab = null
  const walk = (node) => {
    if (node.name === 'TabScout') tab = node
    for (const child of node.children) if (child.active) walk(child)
  }
  walk(game)
  if (tab !== null) tab.emit('touch-start')
  return tab !== null
})()`)
let empty = null
for (let i = 0; i < 30; i += 1) {
  await page2.waitForTimeout(500)
  empty = await page2.evaluate(INTEL)
  if ((empty?.byName?.Header ?? []).some((t) => t.includes('侦察情报'))) {
    break
  }
  // 面板还没建好时点了等于没点，所以每一轮都补一次点击
  await page2.evaluate(TAP_SCOUT)
}
check('没有敌情时那一格说的是怎么弄出一份', (empty?.byName?.Empty ?? [])[0],
  '还没有敌情：在出征编成里把命令切成「侦察」，派一队去看')
await page2.screenshot({ path: path.join(OUT, 'scout-intel-empty.png') })
console.log(`  截图：${path.join(OUT, 'scout-intel-empty.png')}`)

check('运行期零 error（页面级报错）', errors.length, 0)
if (errors.length > 0) {
  for (const message of errors.slice(0, 3)) console.log(`    error: ${message.slice(0, 160)}`)
}

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
