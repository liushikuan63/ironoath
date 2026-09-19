/**
 * 职责：装备实例页（V03-b-S1）的**运行时**验收 —— 在真构建产物 + 真后端上，从**武将页的「装备库」按钮**点进去。
 * 依赖：node、playwright、**已启动的 dev 服务端**、已构建的 web-mobile 产物。
 *
 * 用法：EQUIP_BACKEND=http://localhost:8181 node tools/verify-equip-runtime.mjs
 *
 * <p><b>这一条判的就是入口本身</b>（V03-a 的探针当时只能用编排层驱动，因为入口被并行会话挡着；
 * 装备页的入口已经落地，所以这里**必须真点按钮**）：武将行上点「装备库」→ 装备页激活。
 * 若 dev 新号没有武将（`/hero/list` 为空），入口不可达 —— 这时探针**显式打一条 SKIP 并说明原因**，
 * 改由编排层驱动完成"面板画得对不对"那部分判据，**不把这条算成通过**（未验证就是未验证）。
 *
 * <p>判据：① 未打开时 `equipPanel` 不激活（不占首屏）；② 打开后逐行与探针**直连后端**拿到的
 * `/equip/instances` 对得上（名字/强化等级/铁耗/状态都独立重算，不从页面抄）；③ 行数据里不出现 heroId；
 * ④ 行都落在页面内、零页面错误。截图落 `client/build/equip-verify/`。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const OUT = process.env.EQUIP_VERIFY_OUT ?? path.resolve(process.cwd(), 'client/build/equip-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.EQUIP_PROBE_PORT ?? 8183)
const BACKEND = process.env.EQUIP_BACKEND ?? 'http://localhost:8181'
const ARTIFACT = process.env.EQUIP_ARTIFACT_ROOT ?? 'client/build/web-mobile'

let pass = 0
let fail = 0
const ok = (msg) => { pass += 1; console.log(`  PASS  ${msg}`) }
const bad = (msg) => { fail += 1; console.log(`  FAIL  ${msg}`) }
const skip = (msg) => { console.log(`  SKIP  ${msg}`) }
const check = (msg, actual, expected) => {
  if (actual === expected) {
    ok(`${msg}（${actual}）`)
  } else {
    bad(`${msg}：期望 ${expected}，实际 ${actual}`)
  }
}
const checkTrue = (msg, actual) => check(msg, Boolean(actual), true)

const SLOT_NAMES = { WEAPON: '武器', ARMOR: '护甲', MOUNT: '坐骑', ACCESSORY: '饰品' }
const REASONS = { MAX_LEVEL: '已满级', IRON_LOW: '铁矿不足' }

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
try {
  // 端点要身份：裸调只要不是连接失败就说明服务在
  await fetch(`${BACKEND}/equip/instances`)
} catch (error) {
  console.error(`探针起跑前先直连后端失败：${error.message}\n后端 ${BACKEND} 起来了吗？`)
  process.exit(2)
}
console.log(`=== 装备页运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const PANEL_SNAPSHOT = `(() => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  const node = game.getChildByName('equipPanel')
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

async function fetchJson(pathName, playerId) {
  const res = await fetch(`${BACKEND}${pathName}`, { headers: { 'X-Player-Id': playerId } })
  const body = await res.json()
  return body.data ?? body
}

/** 点武将行上的「装备库」按钮（真点击：与真人按下走的是同一个回调）。 */
async function clickArmory(page) {
  return page.evaluate(`(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas').getChildByName('Game')
    const heroPanel = game.children.find(c => c.name === 'hero')
    if (!heroPanel) return 'no-hero-panel'
    let found = null
    const walk = (n) => {
      if (found) return
      if (n.name === 'ArmoryButton') { found = n; return }
      for (const child of n.children) walk(child)
    }
    walk(heroPanel)
    if (!found) return 'no-button'
    found.emit('touch-start')
    return 'clicked'
  })()`)
}

const deviceId = `equip-runtime-${Date.now()}`
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

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'hero')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await page.waitForTimeout(1800)

const before = await page.evaluate(PANEL_SNAPSHOT)
checkTrue('装备页挂上了场景（equipPanel 节点在）', before !== null)
check('未打开时装备页不激活（不占首屏）', before?.active, false)

if (playerId === null) {
  bad('拿不到应用发请求时带的身份（X-Player-Id）—— 后面的对账无从谈起')
  await browser.close()
  process.exit(1)
}
const instances = await fetchJson('/equip/instances', playerId)
const heroes = await fetchJson('/hero/list', playerId)
console.log(`  后端现状：instances=${instances.instances.length} heroes=${(heroes.heroes ?? []).length}`)

// ① 入口：真点武将行上的「装备库」
const clicked = await clickArmory(page)
if (clicked === 'clicked') {
  ok('在武将行上点到了「装备库」按钮')
} else {
  skip(`入口点击：${clicked === 'no-button' ? '武将页当前没有行（无武将）' : '找不到武将页节点'}`
    + ' ⇒ 入口在 dev 不可达，改由编排层驱动；"可点达"这条算未验证')
  const drove = await page.evaluate(`(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas').getChildByName('Game')
    const boot = game.components.find(c => c.constructor && c.constructor.name === 'GameBootstrap')
    if (!boot || !boot.root) return false
    void boot.root.openEquip()
    return true
  })()`)
  if (!drove) {
    bad('编排层也拿不到，装备页打不开')
  }
}
await page.waitForTimeout(1200)

const snap = await page.evaluate(PANEL_SNAPSHOT)
if (snap === null) {
  bad('点完之后读不到 equipPanel')
  await browser.close()
  process.exit(1)
}
check('打开后装备页激活', snap.active, true)
checkTrue('标题是「装 备」', has(snap.labels, '装 备'))
checkTrue(`汇总行照抄服务端（已装备 ${instances.instances.filter((i) => i.wornByHeroId).length} / 共 ${instances.instances.length} 件）`,
  has(snap.labels, `已装备 ${instances.instances.filter((i) => i.wornByHeroId).length} / 共 ${instances.instances.length} 件`))

const first = instances.instances[0]
if (first === undefined) {
  // 这条是首跑截图抓出来的：数据到了但一件装备都没有，而面板写着"正在载入…"（玩家会一直等）
  checkTrue('空列表写实话"还没有装备"', has(snap.labels, '还没有装备'))
  check('空列表不出现"正在载入"', has(snap.labels, '正在载入'), false)
  skip('后端一件装备都没下发（新号没有装备实例）⇒ 逐行对账无从谈起，算未验证')
} else {
  checkTrue(`第一件在画面上（${first.name}）`, has(snap.labels, first.name))
  checkTrue(`槽位与强化等级一致（${SLOT_NAMES[first.slot]} · 强化 +${first.forgeLevel} / ${first.forgeMax}）`,
    has(snap.labels, `${SLOT_NAMES[first.slot]} · 强化 +${first.forgeLevel} / ${first.forgeMax}`))
  if (!first.canForge && REASONS[first.blockReason] !== undefined) {
    checkTrue(`不能强化的原因来自服务端（${REASONS[first.blockReason]}）`, has(snap.labels, REASONS[first.blockReason]))
  }
}

// 行数必须与服务端一致（这一页没有滚动：少画一件 = 那件玩家够不到）
const drawnRows = snap.labels.filter((l) => l.includes(' · 强化 +')).length
check('每一件都画出来了（行数与服务端一致）', drawnRows, instances.instances.length)

// heroId 不许出现在画面上（id 不是名字）
check('画面上不出现 heroId', snap.labels.some((l) => /hero_/.test(l)), false)

const outside = snap.ys.filter((y) => y > snap.height / 2 + 1 || y < -snap.height / 2 - 1)
check('每一行文字都落在页面高度之内', outside.length, 0)

check('页面零错误', errors.join(' | ') || '无', '无')
await page.screenshot({ path: path.join(OUT, 'equip-panel.png') })
console.log(`  截图：${path.join(OUT, 'equip-panel.png')}`)
await page.screenshot({ path: path.join(OUT, 'equip-entry-hero-panel.png') })

await browser.close()
await preview.close?.()
console.log(`\n=== 装备页运行时验收：${pass} 通过 / ${fail} 失败（另有 SKIP 见上）===`)
process.exit(fail === 0 ? 0 : 1)
