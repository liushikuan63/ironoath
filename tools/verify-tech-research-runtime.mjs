#!/usr/bin/env node
/**
 * 职责：把「研究这一页玩家真的到得了、行上那颗「研究」真的发得出一次带幂等键的请求」变成能失败的判据。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 *   BACKEND_ORIGIN=http://localhost:8199 TECH_PORT=8189 node tools/verify-tech-research-runtime.mjs
 *
 * <p><b>为什么单独跑这一份</b>：#323 接了写侧（行上一颗「研究」键 + `AppRoot.researchTech`），
 * 但当时**整页没有玩家入口**（`openTech()` 零调用方），所以那一格的运行时目视是显式标注"未做"的 ——
 * 拿不到可失败证据的结论不许说成完成。#330 把入口补上（内城右上角「学院 · 研究」），这份量具才第一次
 * 能沿**真实玩家路径**走：内城 → 按那颗键 → 研究页出现 → 按行上的「研究」→ 看请求。
 *
 * <p><b>它盯的几件事</b>：① 入口按得动且真的把那一页显示出来（不是只点亮一个空壳）；
 * ② 页上把服务端给的"能不能研究"与那句原因原样摆出来；③ 可研究那行有一颗键，按下去**恰好一条**
 * `POST /tech/research` 且带幂等键；④ 被拒的那一行按不到键（灰着的行不给一颗必然失败的按钮）；
 * ⑤ 发完会重拉 `/tech/list`（队列那一行是服务端算的，不重拉就停在"没在研究"的旧世界上）。
 *
 * <p><b>夹具边界</b>：`/tech/list` 与 `/tech/research` 是读口/写口夹具（dev 新号没有学院等级，
 * 真数据到不了"可研究"那一行）；**入口与渲染走的是生产代码**。资源与金币是真后端读的。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.TECH_PORT ?? 8189)
const OUT = path.resolve(process.cwd(), 'client/build/tech-verify')
mkdirSync(OUT, { recursive: true })

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

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 研究页运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `tech-runtime-${Date.now()}`)
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
const passthrough = async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return true
  }
  return false
}

const NOW = Date.now()
const RESEARCH_CALLS = []
const CANCEL_CALLS = []
let techPulls = 0
/**
 * 研究状态会被 `/tech/research` 推进 —— 夹具不能是静态的：静态的话"发完重拉到了新状态"这条
 * 只能靠请求计数证明，两张截图长得一模一样（与 #322 的体力夹具同一条教训）。
 */
const TECH_STATE = { started: false }
/** 一行可研究、一行被学院等级挡着：两件事要在同一屏上都能看出来 */
const techBody = () => ({
  techs: [{
    techId: 'tech_agri_wood', name: '屯田令', school: 'AGRICULTURE', effectAttr: 'WOOD_OUTPUT',
    effectValuePerLevelFixed: 400, level: 3, maxLevel: 30, requireAcademyLevel: 2,
    nextTimeSec: 5, nextCost: [{ type: 'WOOD', amount: 600 }],
    researching: TECH_STATE.started,
    canResearch: !TECH_STATE.started, blockedReason: TECH_STATE.started ? 'QUEUE_BUSY' : 'NONE',
  }, {
    techId: 'tech_mil_attack', name: '锻兵令', school: 'MILITARY', effectAttr: 'UNIT_ATTACK',
    effectValuePerLevelFixed: 300, level: 0, maxLevel: 30, requireAcademyLevel: 6,
    nextTimeSec: 12, nextCost: [{ type: 'IRON', amount: 900 }],
    researching: false, canResearch: false, blockedReason: 'ACADEMY_LOW',
  }],
  queue: TECH_STATE.started
    ? {
      techId: 'tech_agri_wood', finishAt: Date.now() + 300_000, startedAt: Date.now(),
      totalSeconds: 300, remainingSeconds: 300,
    }
    : { techId: null, finishAt: null, startedAt: 0, totalSeconds: 0, remainingSeconds: 0 },
  academyLevel: 2,
  serverNow: NOW,
})

await context.route('**/tech/research*', async (route) => {
  if (await passthrough(route)) return
  RESEARCH_CALLS.push(JSON.parse(route.request().postData() ?? '{}'))
  TECH_STATE.started = true
  await reply(route, {
    techId: 'tech_agri_wood', level: 4, finishAt: Date.now() + 300_000,
    cost: [{ type: 'WOOD', amount: 600 }], timeSec: 300,
  })
})
await context.route('**/tech/cancel*', async (route) => {
  if (await passthrough(route)) return
  CANCEL_CALLS.push(JSON.parse(route.request().postData() ?? '{}'))
  TECH_STATE.started = false
  await reply(route, { techId: 'tech_agri_wood', refund: [{ type: 'WOOD', amount: 360 }] })
})
await context.route('**/tech/list*', async (route) => {
  if (await passthrough(route)) return
  techPulls += 1
  await reply(route, techBody())
})

const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'city')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)

/** 内城那一页的节点 + 研究页的节点，一次读回来 */
const READ = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const find = (name) => {
    let hit = null
    const walk = (n) => { if (n.name === name) hit = n; for (const c of n.children) walk(c) }
    walk(game)
    return hit
  }
  const tech = find('techPanel')
  const texts = []
  const buttons = []
  if (tech !== null && tech.active) {
    const walk = (n) => {
      const t = n.getComponent('cc.Label')?.string ?? ''
      if (t.length > 0) texts.push(t)
      if (n.name.startsWith('research-') && n.active) buttons.push(n.name)
      for (const c of n.children) walk(c)
    }
    walk(tech)
  }
  return {
    entryExists: find('TechOpenButton') !== null,
    entryActive: find('TechOpenButton') !== null && find('TechOpenButton').active,
    pageActive: tech !== null && tech.active,
    texts, buttons,
  }
})()`

let read = null
for (let i = 0; i < 40; i += 1) {
  await page.waitForTimeout(500)
  read = await page.evaluate(READ)
  if (read?.entryActive === true) break
}
check('内城右上角有那颗「学院 · 研究」且看得见', read?.entryActive, true)
check('研究页此刻还没打开（不是一开始就盖在内城上）', read?.pageActive, false)

await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let hit = null
  const walk = (n) => { if (n.name === 'TechOpenButton') hit = n; for (const c of n.children) walk(c) }
  walk(game)
  if (hit !== null) hit.emit('touch-start')
  return hit !== null
})()`)
await page.waitForTimeout(1_200)
read = await page.evaluate(READ)
checkTrue('按那颗键真的把研究页显示出来了', read?.pageActive === true)
checkTrue('页上把两行科技与学院等级都摆出来了：' + JSON.stringify(read?.texts ?? []).slice(0, 160),
  read !== null && read.texts.some((t) => t.includes('屯田令'))
    && read.texts.some((t) => t.includes('锻兵令')) && read.texts.some((t) => t.includes('学院 2 级')))
checkTrue('被学院等级挡住那一行写的是服务端给的那句原因',
  read !== null && read.texts.some((t) => t.includes('学院等级不足')))
check('只有可研究那一行有键（灰着的行不给一颗必然失败的按钮）',
  (read?.buttons ?? []).join(','), 'research-tech_agri_wood')
check('打开研究页拉了一次列表', techPulls, 1)
await page.screenshot({ path: path.join(OUT, 'tech-page-opened.png') })
console.log(`  截图：${path.join(OUT, 'tech-page-opened.png')}`)

const tapped = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let hit = null
  const walk = (n) => { if (n.name === 'research-tech_agri_wood' && n.active) hit = n; for (const c of n.children) walk(c) }
  walk(game)
  if (hit !== null) hit.emit('touch-start')
  return hit !== null
})()`)
checkTrue('按得到可研究那一行的「研究」', tapped)
await page.waitForTimeout(1_500)
check('恰好发出一条 POST /tech/research', RESEARCH_CALLS.length, 1)
check('请求体里是那一行的 techId', RESEARCH_CALLS[0]?.techId, 'tech_agri_wood')
checkTrue('带幂等键（开始研究要扣资源，弱网重投不该扣两次）',
  typeof RESEARCH_CALLS[0]?.requestId === 'string' && RESEARCH_CALLS[0].requestId.length > 0)
checkTrue('发完重拉了列表（队列那一行是服务端算的，不重拉就停在"没在研究"的旧世界上）', techPulls >= 2)
read = await page.evaluate(READ)
checkTrue('重拉到的状态画出来了：队列那一行写「正在研究 屯田令」：'
  + JSON.stringify(read?.texts ?? []).slice(0, 140),
  read !== null && read.texts.some((t) => t.includes('正在研究 屯田令')))
check('开始研究之后那一行的键收掉了（一次一队列，再点必然被服务端拒）',
  (read?.buttons ?? []).join(','), '')
await page.screenshot({ path: path.join(OUT, 'tech-research-sent.png') })
console.log(`  截图：${path.join(OUT, 'tech-research-sent.png')}`)

// ---------- 队列那一行的「取消研究」：现在能进来了，就得能反悔 ----------
const cancelTapped = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let hit = null
  const walk = (n) => { if (n.name === 'CancelResearchButton' && n.active) hit = n; for (const c of n.children) if (c.active) walk(c) }
  walk(game)
  if (hit === null) return false
  hit.emit('touch-start')
  return true
})()`)
checkTrue('在研那一行旁边有颗「取消研究」且按得到', cancelTapped)
await page.waitForTimeout(1_500)
check('恰好发出一条 POST /tech/cancel', CANCEL_CALLS.length, 1)
check('请求不带 techId（一次一队列，取消哪一行由服务端按队列定）',
  CANCEL_CALLS[0]?.techId, undefined)
checkTrue('取消也带幂等键（退资源是写操作，重放会退两次）',
  typeof CANCEL_CALLS[0]?.requestId === 'string' && CANCEL_CALLS[0].requestId.length > 0)
read = await page.evaluate(READ)
checkTrue('取消完那句回执照服务端给的数念（返还比例与城建同一份配置，客户端不重算）：'
  + JSON.stringify(read?.texts ?? []).slice(0, 140),
  read !== null && read.texts.some((t) => t.includes('已取消「屯田令」')
    && t.includes('退回 木材 360')))
check('取消完那一行的键又回来了（队列空出来了）', (read?.buttons ?? []).join(','), 'research-tech_agri_wood')
await page.screenshot({ path: path.join(OUT, 'tech-cancelled.png') })
console.log(`  截图：${path.join(OUT, 'tech-cancelled.png')}`)

check('运行期零 error（页面级报错）', errors.length, 0)
if (errors.length > 0) {
  for (const message of errors.slice(0, 3)) console.log(`    error: ${message.slice(0, 160)}`)
}

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
