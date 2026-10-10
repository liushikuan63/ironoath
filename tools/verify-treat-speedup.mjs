#!/usr/bin/env node
/**
 * 职责：把「加速治疗点得动」钉成一条能失败的运行时判据（V12；`/army/treatSpeedUp`，
 * 军队四格里最后一个"有端点、玩家点不到"的）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199（不给就退 2 并点名这个变量：静默回落到别的后端会把读数
 *   错得像产品缺陷 —— 台账 #371/#372）；端口 TREAT_PORT（默认 8218）
 *   BACKEND_ORIGIN=http://localhost:8199 node tools/verify-treat-speedup.mjs
 *
 * <p><b>为什么用夹具</b>：dev 新号既没有伤兵在治疗，背包里也没有训练令，`ArmyPanelView` 那颗键
 * 只在 `hospital.treating` 为真时出现（到点可收那一档由「收取伤兵」承担）—— 真后端上这一格
 * **永远量不到**。夹具把两样都注入，判据就能失败；`/army/treatSpeedUp` 的请求也由桩接住，
 * 所以"点下去到底发了什么"是可读的。
 *
 * <p><b>它盯的四件事</b>：① 治疗中那颗键可见（idle 时不可见 —— 第二相是对照组）；
 * ② 点下去先弹"用哪一张加速"，不是直接吃掉道具；③ 候选里**只有训练令**（建造令与研究令走到这个端点
 * 会被服务端按 `ITEM_CANNOT_USE` 拒）；④ 选中之后真的发出 `POST /army/treatSpeedUp`，
 * 请求体带的是那一张的 `itemId` 与幂等键。
 *
 * <p><b>不验的</b>：真的少多少秒、道具真的被扣 —— 那是服务端 `ArmyAppService.treatSpeedUp`
 * 与 `ArmyEndpointTest` 那一头（这里两个都是读接口夹具）。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { makeStubRead } from './lib/route-stub.mjs'

const OUT = process.env.TREAT_VERIFY_OUT ?? path.resolve(process.cwd(), 'tmp/treat-speedup')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.TREAT_PORT ?? 8218)
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-treat-speedup] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const ARTIFACT = 'client/build/web-mobile'
const TRAIN_ITEM = 'e_probe_train_1h'

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

const now = Date.now()
/** 医院那一格的开关：第二相把它翻成 false 当对照组（夹具是可变对象，stub 每次请求现取）。 */
const armyFixture = {
  units: [],
  troopCap: 1000, troopsInUse: 0, trainingInUse: 0, queueSlots: 0, queueSlotsMax: 2,
  hospital: {
    capacity: 200, used: 40, treating: true, treatFinishAt: now + 300_000,
    treatRemainingSeconds: 300, treatSecondsPerWounded: 30, treatCostRatio: 0,
  },
  autoTrain: { enabled: false, unitId: 'none', batchCount: 1, batchBudget: 0, targetCount: 0, stopReason: null },
  serverNow: now,
}
// 三种令都在背包里：只有训练令能用来加速治疗（服务端按 effectKind 校验），另两种必须不出现
const bagFixture = {
  items: [
    { itemId: TRAIN_ITEM, name: '一小时训练令', type: 'SPEEDUP', rarity: 'RARE', obtainFrom: null,
      count: 4, stackMax: 99, sortKey: 10, effectKind: 'REDUCE_TRAIN_SECONDS', effectTarget: null,
      effectValue: 3600, description: null, expiresAt: null, usableIn: ['ARMY'], artKey: null, opened: 0 },
    { itemId: 'e_probe_build_1h', name: '一小时建造令', type: 'SPEEDUP', rarity: 'RARE', obtainFrom: null,
      count: 9, stackMax: 99, sortKey: 11, effectKind: 'REDUCE_BUILD_SECONDS', effectTarget: null,
      effectValue: 3600, description: null, expiresAt: null, usableIn: ['CITY'], artKey: null, opened: 0 },
    { itemId: 'e_probe_research_1h', name: '一小时研究令', type: 'SPEEDUP', rarity: 'RARE', obtainFrom: null,
      count: 2, stackMax: 99, sortKey: 12, effectKind: 'REDUCE_RESEARCH_SECONDS', effectTarget: null,
      effectValue: 3600, description: null, expiresAt: null, usableIn: ['TECH'], artKey: null, opened: 0 },
  ],
  capacityUsed: 3, capacityMax: 100, serverNow: now,
}

/** 页面里读：那颗键在不在、亮没亮；以及选择器当前画了什么。 */
function readArmy() {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  const panel = game.getChildByName('army')
  if (!panel) return { missing: true }
  let buttonActive = null
  let buttonNode = false
  const texts = []
  const walk = (n) => {
    if (n.name === 'TreatSpeedUpButton') { buttonNode = true; buttonActive = n.activeInHierarchy }
    const label = n.getComponent('cc.Label')
    if (label && n.activeInHierarchy && String(label.string ?? '').trim() !== '') texts.push(label.string)
    for (const child of n.children) walk(child)
  }
  walk(panel)
  // 选择器是挂在 Game 节点下的独立 ChoiceOverlay，不在 army 面板里
  const pickerTexts = []
  for (const child of game.children) {
    if (child.name !== 'ChoiceOverlay') continue
    const collect = (n) => {
      const label = n.getComponent('cc.Label')
      if (label && n.activeInHierarchy && String(label.string ?? '').trim() !== '') pickerTexts.push(label.string)
      for (const c of n.children) collect(c)
    }
    collect(child)
  }
  return { missing: false, buttonNode, buttonActive, texts, pickerTexts,
    pickerActive: game.children.some((c) => c.name === 'ChoiceOverlay' && c.activeInHierarchy) }
}

/** 按节点名 emit 已注册的按下事件；这不是物理指针输入。 */
function clickNode(name) {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  let found = null
  const walk = (n) => {
    if (found) return
    if (n.name === name && n.activeInHierarchy) { found = n; return }
    for (const child of n.children) walk(child)
  }
  walk(game)
  if (!found) return false
  found.emit('touch-start')
  return true
}

/** 在已打开选择器的实际选项行上 emit 已注册事件；这不是物理指针输入。 */
function clickPickerRow(text) {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  const unique = (parent, name) => {
    const nodes = (parent?.children ?? []).filter((node) => node.name === name)
    return nodes.length === 1 ? nodes[0] : null
  }
  const pickers = game.children.filter((node) => node.name === 'ChoiceOverlay' && node.activeInHierarchy)
  if (pickers.length !== 1) return false
  const viewport = unique(pickers[0], 'DialogContent')
  const content = unique(viewport, 'DialogContentContent')
  const row = unique(content, 'Choice-0')
  if (!row?.activeInHierarchy) return false
  const titles = row.children.filter((node) => {
    const label = node.getComponent('cc.Label')
    return node.activeInHierarchy && label && String(label.string ?? '') === text
  })
  if (titles.length !== 1 || typeof row.hasEventListener !== 'function') return false
  const event = row.hasEventListener('touch-end') ? 'touch-end'
    : row.hasEventListener('touch-start') ? 'touch-start' : null
  if (event === null) return false
  row.emit(event)
  return true
}

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v),
  `treat-speedup-${Date.now()}`)
const stubRead = makeStubRead(context)
// 夹具必须在 goto 之前挂（深链一进去就发请求）
stubRead('**/army/list*', () => armyFixture)
stubRead('**/bag/list*', bagFixture)

const treatPosts = []
await context.route('**/army/treatSpeedUp*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: {
      'access-control-allow-origin': route.request().headers()['origin'] ?? '*',
      'access-control-allow-headers': '*', 'access-control-allow-methods': 'GET,POST,OPTIONS' } })
    return
  }
  treatPosts.push(JSON.parse(route.request().postData() ?? '{}'))
  await route.fulfill({ status: 200, headers: {
    'access-control-allow-origin': route.request().headers()['origin'] ?? '*',
    'content-type': 'application/json' },
  body: JSON.stringify({ code: 0, msg: '成功', serverNow: Date.now(), data: {
    hospital: { capacity: 200, used: 40, treating: true, treatFinishAt: now + 60_000,
      treatRemainingSeconds: 60, treatSecondsPerWounded: 30, treatCostRatio: 0 },
    cost: [{ type: 'FOOD', amount: 0 }], recovered: [], serverNow: Date.now() } }) })
})

const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()) })

const url = `${preview.origin}/?panel=army`
await page.goto(url, { waitUntil: 'networkidle' })
preview.assertRewritten()
await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
  null, { timeout: 60_000 })
await page.waitForTimeout(3500)
await hideGuideOverlay(page)
await page.waitForTimeout(600)

// 第一相：治疗中
const treating = await page.evaluate(readArmy)
check('军队面板挂上了场景', treating.missing, false)
check('「加速治疗」那颗键在场景里建出来了', treating.buttonNode, true)
check('治疗中它可见', treating.buttonActive, true)
await page.screenshot({ path: path.join(OUT, 'treating.png') })

const clickedButton = await page.evaluate(clickNode, 'TreatSpeedUpButton')
await page.waitForTimeout(900)
const opened = await page.evaluate(readArmy)
check('点得动', clickedButton, true)
check('点下去弹的是选择器（不是直接吃掉道具）', opened.pickerActive, true)
const pickerJoined = (opened.pickerTexts ?? []).join(' ｜ ')
console.log(`  选择器：${pickerJoined}`)
check('候选里有训练令', pickerJoined.includes('一小时训练令'), true)
check('候选里没有建造令（走到这个端点会被服务端拒）', pickerJoined.includes('建造令'), false)
check('候选里没有研究令（同上）', pickerJoined.includes('研究令'), false)
await page.screenshot({ path: path.join(OUT, 'picker.png') })

const before = treatPosts.length
const picked = await page.evaluate(clickPickerRow, '一小时训练令')
await page.waitForTimeout(1200)
check('在选择器里点得到那一行', picked, true)
check('真的发出了 POST /army/treatSpeedUp（新增请求数）', treatPosts.length - before, 1)
const sent = treatPosts[treatPosts.length - 1] ?? {}
check('请求体带的是那一张的 itemId', sent.itemId, TRAIN_ITEM)
check('请求体带幂等键 requestId', typeof sent.requestId === 'string' && sent.requestId.length > 0, true)

// 第二相（对照组）：不在治疗时那颗键必须不可见 —— 没有这一条，"屏上有这颗键"可能来自别处
armyFixture.hospital.treating = false
armyFixture.hospital.treatRemainingSeconds = 0
await page.reload({ waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
  null, { timeout: 60_000 })
await page.waitForTimeout(3000)
await hideGuideOverlay(page)
const idle = await page.evaluate(readArmy)
check('对照组：不在治疗时那颗键不可见', idle.buttonActive, false)
await page.screenshot({ path: path.join(OUT, 'idle.png') })

check('页面零错误', errors.join(' | ') || '无', '无')

await browser.close()
await preview.close()
console.log(` 截图：${path.join(OUT, 'treating.png')} / picker.png / idle.png`)
console.log(`\n=== 加速治疗运行时验收：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)
