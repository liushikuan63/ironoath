#!/usr/bin/env node
/**
 * 职责：把「加速道具的目标选择器真的画得出来、字不被裁」变成能失败的判据（#316 的运行侧欠账）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 *   BACKEND_ORIGIN=http://localhost:8199 PICKER_PORT=8196 node tools/verify-speedup-picker-runtime.mjs
 *
 * <p><b>为什么要单独跑这一份</b>：#316 改的是 `ChoiceOverlay.createRow` 的版式（色带与两个标签
 * 改成按面板宽排），当时只在军队「队列」菜单上量过。背包这个使用者是**另一条入口**
 * （背包行上的「使用」→ `useItem(needsTarget)` → 目标选择器），不同入口、同一份版式，
 * 只量一处就等于没量另一处。
 *
 * <p><b>它盯的几件事</b>：① 点「使用」真的弹出选择器（不是直接吃掉道具）；
 * ② 选择器里那一条的目标名来自服务端下发的队列（不是写死的字）；
 * ③ **标题盒子是一行高**（#316 修的就是标签没盒子被裁成两行）；
 * ④ 色带宽度不超过面板、也不超过屏幕（`width` 参数真被吃到，不是写死 700）；
 * ⑤ **道具行不压在弹层上面** —— 这一条是本次真跑抓到的缺陷，而且是**两层**：
 * 弹层在 `onLoad` 建、行在每次渲染才 addChild，行排在后面就把「选择加速目标」的标题盖掉；
 * 而既有的 `ChoiceOverlay.raise()` 用的是 `parent.addChild(自己)`，在 3.8.7 里对同一父节点
 * 是空操作（实测 children 为 A,B 时再 addChild(A) 仍是 A,B），抬层一直没生效。
 * 修法：`raise()` 改 `setSiblingIndex(末位)`，`show()` 抬一次，父节点 `child-added` 时
 * 弹层自己顶回末位 —— 八个宿主不需要各自记得抬（宿主侧那一行删掉后本探针仍全绿，就是判据）。
 *
 * <p><b>不验的</b>：真的把道具用掉、队列真的少多少时间 —— 那是服务端 `ItemAppService`
 * 与 `ItemEndpointTest` 那一头；这里的道具与训练队列都是**读接口夹具**（dev 新号两样都没有）。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.PICKER_PORT ?? 8196)
const OUT = path.resolve(process.cwd(), 'client/build/speedup-picker-verify')
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
console.log(`=== 加速目标选择器运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `picker-runtime-${Date.now()}`)
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
const now = Date.now()
// 一个加速道具 + 一口正在训练的兵：选择器就该有这一条可选目标
await context.route('**/bag/list*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, {
    items: [{
      itemId: 'item_speedup_1h', name: '加速道具（1 小时）', type: 'SPEEDUP', rarity: 'RARE',
      obtainFrom: null, count: 3, stackMax: 99, sortKey: 10, effectKind: 'SPEEDUP',
      effectTarget: null, effectValue: 3600, description: null, expiresAt: null,
      usableIn: ['CITY', 'ARMY'], artKey: null, opened: 0,
    }],
    capacityUsed: 1, capacityMax: 100, serverNow: now,
  })
})
await context.route('**/army/list*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, {
    units: [{
      unitId: 'unit_infantry_t1', name: '重步', type: 'INFANTRY', tier: 1, count: 500,
      wounded: 0, training: 30, finishAt: now + 600_000, remainingSeconds: 600,
      unlocked: true, unlockHint: null, trainTimeSec: 10,
      trainCost: [{ type: 'FOOD', amount: 20 }],
    }],
    troopCap: 1000, troopsInUse: 0, trainingInUse: 30, queueSlots: 1, queueSlotsMax: 2,
    hospital: { capacity: 0, used: 0, treating: false, treatFinishAt: null,
      treatRemainingSeconds: 0, treatCostRatio: 0 },
    autoTrain: { enabled: false, unitId: 'none', batchCount: 1, batchBudget: 0, targetCount: 0,
      stopReason: null },
    serverNow: now,
  })
})
const used = []
await context.route('**/item/use*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  used.push(JSON.parse(route.request().postData() ?? '{}'))
  await reply(route, { itemId: 'item_speedup_1h', remaining: 2, savedSeconds: 3600,
    targetId: 'unit_infantry_t1', serverNow: Date.now() })
})

const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'bag')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)


/** 在背包面板子树里按节点名找一颗并点它（面板初始停在「资源明细」页，得先切到「背包」） */
const tapInBag = (name) => `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('bag')
  if (panel === undefined || panel === null || !panel.active) return false
  let hit = null
  const walk = (node) => {
    if (node.name === ${JSON.stringify(name)} && node.active) hit = node
    for (const child of node.children) if (child.active) walk(child)
  }
  walk(panel)
  if (hit === null) return false
  hit.emit('touch-start')
  return true
})()`

/** 只读背包面板子树里激活着的那些节点：整棵树乱找会把别的面板同名字段读进来 */
const PICKER = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('bag')
  if (!panel || !panel.active) return { found: false }
  let overlay = null
  const findOverlay = (node) => {
    if (node.name === 'ChoiceOverlay' && node.active) overlay = node
    for (const child of node.children) if (child.active) findOverlay(child)
  }
  findOverlay(panel)
  const useRow = (() => {
    let hit = null
    const walk = (node) => {
      if (node.name === 'UseButton' && node.active) hit = node
      for (const child of node.children) if (child.active) walk(child)
    }
    walk(panel)
    return hit
  })()
  const pageName = (() => {
    let hit = null
    const walk = (node) => {
      if (node.name === 'Header' && node.active) {
        hit = node.getComponent('cc.Label')?.string ?? ''
      }
      for (const child of node.children) if (child.active) walk(child)
    }
    walk(panel)
    return hit
  })()
  if (overlay === null) {
    return { found: true, pickerOpen: false, useButton: useRow !== null, pageName }
  }
  const visibleW = window.cc.view.getVisibleSize().width
  const panelW = overlay.getComponent('cc.UITransform').width
  // 弹层是 onLoad 建的、道具行是渲染时才 addChild 的：行排在后面就会压在弹层上面
  // （军队那一格同族缺陷，当时是靠把弹层抬到 Game 层修的）
  let rowsAbove = 0
  for (const child of panel.children) {
    if (child.name === 'Row' && child.active && child.getSiblingIndex() > overlay.getSiblingIndex()) {
      rowsAbove += 1
    }
  }
  const row = (overlay.children || []).find((c) => c.name === 'Choice-0')
  const labels = (row?.children || []).filter((c) => c.getComponent('cc.Label') !== null)
    .map((c) => ({
      name: c.name,
      text: c.getComponent('cc.Label').string,
      w: c.getComponent('cc.UITransform').width,
      h: c.getComponent('cc.UITransform').height,
    }))
  const band = row?.getComponent('cc.UITransform')
  return {
    found: true, pickerOpen: true, useButton: useRow !== null, pageName, visibleW,
    panelW, bandW: band?.width ?? null, rowsAbove,
    title: labels.find((l) => l.name === 'Label' && l.text.length > 0) ?? null,
    texts: labels.map((l) => l.text),
  }
})()`

// 面板初始页签由 /resource/detail 决定（attachResources 把 tab 钉回「资源明细」），
// 所以要先按「背包」页签才看得到道具行
let state = null
for (let i = 0; i < 40; i += 1) {
  await page.waitForTimeout(500)
  await page.evaluate(tapInBag('Tab_bag'))
  state = await page.evaluate(PICKER)
  if (state?.found && state.useButton === true) break
}
check('背包页激活且道具行上的「使用」画出来了', state?.useButton, true)
checkTrue('页签确实切到了「背包」（表头是容量账，不是「资源产出明细」）',
  (state?.pageName ?? '').includes('背包'))

checkTrue('按得到那一颗「使用」', await page.evaluate(tapInBag('UseButton')))
await page.waitForTimeout(700)
state = await page.evaluate(PICKER)
check('点使用先弹目标选择器（不直接吃掉道具）', state?.pickerOpen, true)
checkTrue('选择器里那条目标写的是服务端下发的队列名（不是写死的字）',
  (state?.texts ?? []).some((t) => t.includes('重步')))
checkTrue('标题盒子是一行高（#316 修的就是标签没盒子被裁成两行）',
  state?.title?.h !== undefined && state.title.h <= 26)
checkTrue('色带与标题都不宽过面板（width 参数真被吃到）',
  state?.bandW <= state?.panelW && state?.title?.w <= state?.panelW)
checkTrue('色带不宽过屏幕（背包弹层写死 760，窄屏会溢出）',
  state?.bandW <= state?.visibleW)
check('道具行没有压在弹层上面（同军队那格的遮挡缺陷不在这复现）',
  state?.rowsAbove, 0)
await page.screenshot({ path: path.join(OUT, 'speedup-picker.png') })
console.log(`  截图：${path.join(OUT, 'speedup-picker.png')}`)

check('没选目标就不发 /item/use（选了才吃道具）', used.length, 0)
check('运行期零 error（页面级报错）', errors.length, 0)
if (errors.length > 0) {
  for (const message of errors.slice(0, 3)) console.log(`    error: ${message.slice(0, 160)}`)
}

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
