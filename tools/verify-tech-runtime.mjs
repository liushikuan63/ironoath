/**
 * 职责：研究页（V03-a-S1）的**运行时**验收 —— 在真构建产物 + 真后端上把内城「学院」那一页打开。
 * 依赖：node、playwright、**已启动的 dev 服务端**、已构建的 web-mobile 产物。
 *
 * 用法：TECH_BACKEND=http://localhost:8181 node tools/verify-tech-runtime.mjs
 * 必填：TECH_BACKEND=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 TECH_PROBE_PORT（默认 8182，同机并发时换一个）
 *
 * <p><b>入口那一格的临时驱动方式（写在这里，免得下一个人以为这是设计）</b>：
 * 入口方案 (a) 是"内城学院 → 动作栏研究"，而 `CityPanelView.ts` 当时被并行会话持有 ⇒ 本探针
 * 先用场景里的 `GameBootstrap.root.openTech()` 打开面板。**入口落地后要把这段换成真点击**
 * （判据也要跟着改成"点学院那一格出研究页"），否则本探针验的是"面板画得对"而不是"玩家点得到"。
 *
 * <p><b>判据盯三件事</b>：① 未打开时 `techPanel` 节点**不激活**（没占首屏）；
 * ② 打开后逐行与探针**直连后端**拿到的 `/tech/list` 对得上（名字/等级/成本/状态都独立重算一遍，
 * 不从页面抄）；③ 行都落在面板内、零页面错误。截图落 `client/build/tech-verify/`。
 *
 * 端口与产物根可换（TECH_PROBE_PORT / TECH_BACKEND / TECH_ARTIFACT_ROOT）：两个会话同时跑量具时，
 * 撞端口会表现为"读到别人的产物"。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const OUT = process.env.TECH_VERIFY_OUT ?? path.resolve(process.cwd(), 'client/build/tech-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.TECH_PROBE_PORT ?? 8182)
// 必须显式给后端：静默回落到 http://localhost:8181 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.TECH_BACKEND ?? (() => {
  console.error('[tech] 缺 TECH_BACKEND：不给就退回 http://localhost:8181，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const ARTIFACT = process.env.TECH_ARTIFACT_ROOT ?? 'client/build/web-mobile'

let pass = 0
let fail = 0
const ok = (msg) => { pass += 1; console.log(`  PASS  ${msg}`) }
const bad = (msg) => { fail += 1; console.log(`  FAIL  ${msg}`) }
const check = (msg, actual, expected) => {
  if (actual === expected) {
    ok(`${msg}（${actual}）`)
  } else {
    bad(`${msg}：期望 ${expected}，实际 ${actual}`)
  }
}
const checkTrue = (msg, actual) => check(msg, Boolean(actual), true)

const RESOURCE_NAMES = {
  WOOD: '木材', STONE: '石料', IRON: '铁矿', GRAIN: '粮草', GOLD: '金币', STAMINA: '体力',
}
const SCHOOLS = { AGRICULTURE: '农政', MILITARY: '军事', COMMERCE: '商贸', FORTIFICATION: '城防' }

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })

async function fetchTech(playerId) {
  const res = await fetch(`${BACKEND}/tech/list`, {
    headers: playerId ? { 'X-Player-Id': playerId } : {},
  })
  if (!res.ok) {
    throw new Error(`GET /tech/list 回 ${res.status}`)
  }
  const body = await res.json()
  return body.data ?? body
}

let expect
try {
  // 这个端点**要身份**：裸调会回 1001「缺少必需的请求头」—— 只要不是连接失败就说明服务在。
  // 真正的对照数据在后面拿到应用的身份之后再取（等级是"我研究到几级"，必须同一个人）。
  await fetch(`${BACKEND}/tech/list`)
} catch (error) {
  console.error(`探针起跑前先直连后端失败：${error.message}\n后端 ${BACKEND} 起来了吗？`)
  process.exit(2)
}
console.log(`=== 研究页运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const LABEL_SNAPSHOT = `(() => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  const node = game.getChildByName('techPanel')
  if (!node) return null
  const out = []
  const ys = []
  const walk = (n, dy) => {
    const y = dy + n.getPosition().y
    const label = n.components.find(x => x.constructor && x.constructor.name === 'Label')
    if (label && label.string) { out.push(label.string); ys.push(y) }
    for (const child of n.children) walk(child, y)
  }
  walk(node, 0)
  const transform = node.components.find(x => x.constructor && x.constructor.name === 'UITransform')
  return { active: node.active, labels: out, ys, height: transform ? transform.contentSize.height : -1 }
})()`

const has = (labels, text) => labels.some((l) => l.includes(text))

/** 打开研究页：入口尚未落地，先经场景里的编排层驱动（见文件头）。 */
async function openTechPanel(page) {
  const hit = await page.evaluate(`(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas').getChildByName('Game')
    const boot = game.components.find(c => c.constructor && c.constructor.name === 'GameBootstrap')
    if (!boot || !boot.root) return false
    void boot.root.openTech()
    return true
  })()`)
  if (!hit) {
    bad('拿不到场景里的编排层（GameBootstrap.root），打不开研究页')
  }
  await page.waitForTimeout(1200)
}

const deviceId = `tech-runtime-${Date.now()}`
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, deviceId)
const page = await context.newPage()
const errors = []
let playerId = null
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
page.on('request', (request) => {
  const header = request.headers()['x-player-id']
  if (header) {
    playerId = header
  }
})

await page.goto(`${preview.origin}/`, { waitUntil: 'networkidle' })
// 自检：产物里那两处写死的后端地址有没有真的被改写成本轮要打的那棵。
// 漏了这一句，传错变量名就是"打到另一台机器上读数"，红得像是产品缺陷（台账 #371）。
preview.assertRewritten()
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await hideGuideOverlay(page)
await page.waitForTimeout(1800)

const before = await page.evaluate(LABEL_SNAPSHOT)
checkTrue('研究页挂上了场景（techPanel 节点在）', before !== null)
check('未打开时研究页不激活（不占首屏）', before?.active, false)

await openTechPanel(page)
const snap = await page.evaluate(LABEL_SNAPSHOT)
if (snap === null) {
  bad('打完 openTech 后读不到 techPanel')
  await browser.close()
  process.exit(1)
}
check('打开后研究页激活', snap.active, true)

// 逐行与探针自己直连后端拿到的那份对账（带身份：等级是"我研究到几级"）
const mine = playerId === null ? null : await fetchTech(playerId)
if (mine === null) {
  bad('拿不到应用发请求时带的身份（X-Player-Id），逐行对账无从谈起')
  await browser.close()
  process.exit(1)
}
console.log(`  后端现状（带身份）：techs=${mine.techs.length} academyLevel=${mine.academyLevel}`)
checkTrue('标题是「研 究」', has(snap.labels, '研 究'))
checkTrue(`学院等级照抄服务端（学院 ${mine.academyLevel} 级）`, has(snap.labels, `学院 ${mine.academyLevel} 级`))

const first = mine.techs[0]
if (first === undefined) {
  bad('后端一个科技都没下发，后面的逐行对账无从谈起')
} else {
  checkTrue(`第一行有科技名（${first.name}）`, has(snap.labels, first.name))
  checkTrue(`等级文本一致（${first.level} / ${first.maxLevel} 级）`, has(snap.labels, `${first.level} / ${first.maxLevel} 级`))
  const cost = first.nextCost.filter((c) => c.amount > 0)
    .map((c) => `${RESOURCE_NAMES[c.type] ?? c.type} ${c.amount}`).join(' · ')
  checkTrue(`成本文本一致（${cost}）`, has(snap.labels, cost))
  if (first.canResearch) {
    checkTrue('可研究的那行写「可研究」', has(snap.labels, '可研究'))
  } else {
    checkTrue('被拒的那行不给"可研究"', has(snap.labels, first.name) && !has(snap.labels, '可研究'))
  }
  checkTrue(`每一行都画了学派标题（${SCHOOLS[first.school] ?? first.school}）`,
    has(snap.labels, SCHOOLS[first.school] ?? first.school))
}

// 没有行的文字跑到面板外：研究行是**逐行 setPosition** 的，漏一个就会叠在 y=0
const outside = snap.ys.filter((y) => y > snap.height / 2 + 1 || y < -snap.height / 2 - 1)
check('每一行文字都落在页面高度之内', outside.length, 0)

// 行数必须与服务端一致（这一页没有滚动：少画一行 = 那一项玩家永远够不到）
const drawnRows = snap.labels.filter((l) => l.includes(' / ') && l.endsWith('级')).length
check('每一行都画出来了（行数与服务端一致）', drawnRows, mine.techs.length)
check('没有"未显示"这类截断提示', has(snap.labels, '未显示'), false)

check('页面零错误', errors.join(' | ') || '无', '无')
await page.screenshot({ path: path.join(OUT, 'tech-panel.png') })
console.log(`  截图：${path.join(OUT, 'tech-panel.png')}`)

await browser.close()
await preview.close?.()

console.log(`\n=== 研究页运行时验收：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)
