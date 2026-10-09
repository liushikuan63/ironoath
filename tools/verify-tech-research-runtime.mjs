#!/usr/bin/env node
/**
 * 职责：把「研究这一页玩家真的到得了、行上那颗「研究」真的发得出一次带幂等键的请求」变成能失败的判据。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 TECH_PORT（默认 8189，同机并发时换一个）
 *   BACKEND_ORIGIN=http://localhost:8199 TECH_PORT=8189 node tools/verify-tech-research-runtime.mjs
 *
 * <p><b>为什么单独跑这一份</b>：#323 接了写侧（行上一颗「研究」键 + `AppRoot.researchTech`），
 * 但当时**整页没有玩家入口**（`openTech()` 零调用方），所以那一格的运行时目视是显式标注"未做"的 ——
 * 拿不到可失败证据的结论不许说成完成。#330 把入口补上（内城「学院 · 研究」那颗键），这份量具才第一次
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
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[tech-research] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
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
const SPEEDUP_CALLS = []
const CANCEL_BUILD_CALLS = []
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
await context.route('**/tech/speedUp*', async (route) => {
  if (await passthrough(route)) return
  SPEEDUP_CALLS.push(JSON.parse(route.request().postData() ?? '{}'))
  await reply(route, {
    techId: 'tech_mil_attack', reducedSeconds: 3600, remainingSeconds: 120, finished: false,
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

// 「用哪一张加速」的候选来自背包那一份：给一张研究令 + 一张建造令，
// 后者必须**不出现**在候选里（服务端会拒它，宁可不列也不给一颗必然失败的选项）
await context.route('**/bag/list*', async (route) => {
  if (await passthrough(route)) return
  await reply(route, {
    items: [{
      itemId: 'item_speedup_research_1h', name: '研究令', type: 'SPEEDUP', rarity: 'R',
      count: 2, stackMax: 99, sortKey: 1, effectKind: 'REDUCE_RESEARCH_SECONDS', effectTarget: null,
    }, {
      itemId: 'item_speedup_build_1h', name: '建造令', type: 'SPEEDUP', rarity: 'R',
      count: 5, stackMax: 99, sortKey: 2, effectKind: 'REDUCE_BUILD_SECONDS', effectTarget: null,
    }],
    capacityUsed: 2, capacityMax: 100, serverNow: NOW,
  })
})

// 「取消」那颗键只在**正在升级**的那一格上出现，dev 新号没有在建工程 ⇒ 城市列表也钉成夹具
//
// 资源那一条还要测"变短"：服务端 `resources` 是按玩家状态拼的 map，键数不固定，
// 而面板用的是 6 颗固定 Label —— 只写不清就会把上一帧的数值留在屏幕上。
// 所以这里给一个可翻转的相位：先给满 6 项，取消建造触发重拉时缩到 2 项。
const CITY_STATE = { shrunk: false }
// 键取 `contract/config/resource.json` 里真实那六种（WOOD/STONE/IRON/GRAIN/GOLD/STAMINA）——
// 面板的名字走 `game/ui/ResourceNames` 这一份，编一个不存在的键会让量具测的是一个生产不会产生的形状。
const SIX_RESOURCES = {
  WOOD: { current: 8000, cap: 24000, protectedAmount: 0, perHour: 500, lastSettle: NOW },
  STONE: { current: 2400, cap: 24000, protectedAmount: 0, perHour: 260, lastSettle: NOW },
  IRON: { current: 5200, cap: 24000, protectedAmount: 0, perHour: 400, lastSettle: NOW },
  GRAIN: { current: 3100, cap: 30000, protectedAmount: 0, perHour: 300, lastSettle: NOW },
  GOLD: { current: 1500, cap: 100000, protectedAmount: 0, perHour: 120, lastSettle: NOW },
  STAMINA: { current: 900, cap: 5000, protectedAmount: 0, perHour: 60, lastSettle: NOW },
}
await context.route('**/city/cancel*', async (route) => {
  if (await passthrough(route)) return
  CANCEL_BUILD_CALLS.push(JSON.parse(route.request().postData() ?? '{}'))
  await reply(route, { buildingId: 'b_academy', refund: [{ type: 'WOOD', amount: 300 }] })
})
await context.route('**/city/list*', async (route) => {
  if (await passthrough(route)) return
  await reply(route, {
    buildings: [{
      id: 'b_academy', configId: 'academy', name: '学院', level: 2, gridX: 3, gridY: 3,
      status: 'UPGRADING', finishAt: NOW + 60_000, remainingSeconds: 60, progress: 500,
      startedAt: NOW - 60_000, totalSeconds: 120, helpCount: 0,
    }],
    buildOptions: [], queues: { used: 1, available: 1, max: 2 },
    resources: CITY_STATE.shrunk
      ? { WOOD: SIX_RESOURCES.WOOD, IRON: SIX_RESOURCES.IRON }
      : SIX_RESOURCES,
    serverNow: NOW,
  })
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
// 自检：产物里那两处写死的后端地址有没有真的被改写成本轮要打的那棵。
// 漏了这一句，传错变量名就是"打到另一台机器上读数"，红得像是产品缺陷（台账 #371）。
preview.assertRewritten()
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await hideGuideOverlay(page)

/**
 * 开局把引导层摘掉。
 *
 * <p>第 1 步（`panelKey=city`、`skippable=false`，见 `contract/config/guide.json`）的气泡贴着内城
 * 可用区的下沿，正好压住那一格的动作条；判据是 `quest_main_01` 完成，玩家在这一刻既点不掉也跳不掉，
 * 所以每一次截图都会拍到气泡。这份量具验的不是引导（它有 `verify-guide-runtime.mjs` 自己那份），
 * 只是不让它挡住目视 —— 摘完之后按键、读数、请求走的都还是生产代码。
 * 遮罩本身该不该给动作条让位，另记一格判断。
 */
await page.waitForFunction(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  return game?.getChildByName('Guide') !== undefined && game.getChildByName('Guide') !== null
}, { timeout: 20_000 }).catch(() => undefined)
const guideGone = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const guide = game?.getChildByName('Guide')
  if (guide === undefined || guide === null) return false
  const layer = guide.getComponent('GuideView')
  if (layer === null || layer === undefined) return false
  // 只把节点 active 置 false 没用：切面板 / 回执都会 repaint，有帧就重新画回来。
  // 摘驱动器（没帧就 clearLayer）+ 让后续下发不再装上，这一层才真的不再出现。
  layer.driver = null
  layer.attach = () => undefined
  layer.repaint()
  return !guide.active
})()`)
checkTrue('引导层已摘除，不再挡住内城动作条', guideGone)

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
check('内城有那颗「学院 · 研究」且看得见', read?.entryActive, true)
check('研究页此刻还没打开（不是一开始就盖在内城上）', read?.pageActive, false)

await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let hit = null
  const walk = (n) => { if (n.name === 'TechOpenButton') hit = n; for (const c of n.children) walk(c) }
  walk(game)
  if (hit !== null) hit.emit(hit.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
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
// ---------- 成本要让开键位：这条一直只有代码注释、没有判据 ----------
// `TechPanelView.drawRow` 里那句 `const costRight = row.canResearch ? right - 84 : right`
// 注释写明"键占右边 76 宽"，可谁把这个 84 改小，成本就会压到「研究」键上而量具照样全绿
// （与 #336 / #344 那一族同形：版式靠常数撑着，却没人量）。
const OVERLAP = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let tech = null
  const find = (n) => { if (tech === null) { if (n.name === 'techPanel') { tech = n; return }; for (const c of n.children) find(c) } }
  if (game) find(game)
  if (tech === null || !tech.active) return null
  const rect = (n) => {
    const w = n.getComponent('cc.UITransform').getBoundingBoxToWorld()
    return { l: w.x, r: w.x + w.width, b: w.y, t: w.y + w.height }
  }
  const costs = [], keys = []
  const walk = (n) => {
    if (n.activeInHierarchy) {
      const lb = n.getComponent('cc.Label')
      const s = lb ? (lb.string ?? '') : ''
      // 成本那一格长这样：「木材 600」「铁矿 900 · 木材 200」，或「无需资源」；
      // 等级以「级」结尾、耗时以「分」结尾、效果含「%」，所以"以数字结尾且带空格"就是它
      if (lb && s !== '无需资源' && /[0-9,]$/.test(s) && s.indexOf(' ') > 0) {
        costs.push(Object.assign(rect(n), { text: s }))
      }
      if (n.name.indexOf('research-') === 0) keys.push(Object.assign(rect(n), { text: n.name }))
    }
    for (const c of n.children) walk(c)
  }
  walk(tech)
  const hits = []
  for (const c of costs) {
    for (const k of keys) {
      if (c.l < k.r && k.l < c.r && c.b < k.t && k.b < c.t) hits.push(c.text + ' 压到 ' + k.text)
    }
  }
  return { costCount: costs.length, keyCount: keys.length, hits,
    costWidths: costs.map((c) => Math.round(c.r - c.l)),
    costTexts: costs.map((c) => c.text) }
})()`
const overlap = await page.evaluate(OVERLAP)
// 反空转前置：先证"确实抓到了成本标签与那颗键"，否则下面那条"没有相交"是在读一个空集合
checkTrue('抓到可研究那一行的成本标签与「研究」键（否则"不相交"是空转）',
  overlap !== null && overlap.costCount > 0 && overlap.keyCount > 0)
checkTrue('成本标签的盒子是按文本自适应的（不是默认 100 宽 —— 是默认宽就说明这条判据不可信）',
  (overlap?.costWidths ?? []).every((w) => w !== 100))
// `check` 用的是严格相等（`===`），所以数组要 join 成字符串再比 —— 直接传 `[]` 会因引用不同必然红
check('成本让开了键位：两者世界矩形不相交（压上去就是"木材 600"被键盖住）',
  (overlap?.hits ?? ['<没读到>']).join(' | '), '')

check('打开研究页拉了一次列表', techPulls, 1)
await page.screenshot({ path: path.join(OUT, 'tech-page-opened.png') })
console.log(`  截图：${path.join(OUT, 'tech-page-opened.png')}`)

const tapped = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let hit = null
  const walk = (n) => { if (n.name === 'research-tech_agri_wood' && n.active) hit = n; for (const c of n.children) walk(c) }
  walk(game)
  if (hit !== null) hit.emit(hit.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
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

// ---------- 「加速」：先问用哪一张，建造令不许出现在候选里 ----------
const speedTapped = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let hit = null
  const walk = (n) => { if (n.name === 'SpeedUpResearchButton' && n.active) hit = n; for (const c of n.children) if (c.active) walk(c) }
  walk(game)
  if (hit === null) return false
  hit.emit(hit.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
  return true
})()`)
checkTrue('队列那一行旁边有颗「加速」且按得到', speedTapped)
await page.waitForTimeout(800)
const picker = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let overlay = null
  const find = (n) => { if (n.name === 'ChoiceOverlay' && n.active) overlay = n; for (const c of n.children) if (c.active) find(c) }
  find(game)
  if (overlay === null) return { open: false, texts: [] }
  const texts = []
  const walk = (n) => {
    const t = n.getComponent('cc.Label')?.string ?? ''
    if (t.length > 0) texts.push(t)
    for (const c of n.children) walk(c)
  }
  walk(overlay)
  return { open: true, texts }
})()`)
checkTrue('先问「用哪一张加速」，候选里只有研究令：' + JSON.stringify(picker?.texts ?? []).slice(0, 140),
  picker?.open === true && picker.texts.some((t) => t === '用哪一张加速')
    && picker.texts.some((t) => t.includes('研究令')) && !picker.texts.some((t) => t.includes('建造令')))
checkTrue('给两个档位：用 1 张与全用（用超了服务端会退剩下的张数，所以不用玩家算账）',
  picker !== null && picker.texts.some((t) => t === '用 1 张 · 持有 2 张')
    && picker.texts.some((t) => t === '一次用掉 2 张 · 用不完的会退回'))
check('问用哪一张之前不吃道具', SPEEDUP_CALLS.length, 0)
// 按**明写着"用 1 张"的那一档**：按文字找容易撞名（「研究令」与「研究令 全用」都含同一个前缀），
// 所以按说明行定位，并把命中的说明行回读进断言里
const tappedTier = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let hit = null
  const walk = (n) => {
    if (n.name.startsWith('Choice-') && n.active
      && (n.children || []).some((k) => (k.getComponent('cc.Label')?.string ?? '') === '用 1 张 · 持有 2 张')) hit = n
    for (const c of n.children) if (c.active) walk(c)
  }
  walk(game)
  if (hit !== null) hit.emit(hit.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
  return hit !== null
})()`)
checkTrue('按得到「用 1 张」那一档', tappedTier)
await page.waitForTimeout(1_500)
check('恰好发出一条 POST /tech/speedUp', SPEEDUP_CALLS.length, 1)
check('那一档说的是 count=1', SPEEDUP_CALLS[0]?.count, 1)
checkTrue('带幂等键（消耗品 + 改状态，重放不去重就是白丢一张）',
  typeof SPEEDUP_CALLS[0]?.requestId === 'string' && SPEEDUP_CALLS[0].requestId.length > 0)
read = await page.evaluate(READ)
checkTrue('加速那句回执照服务端给的数念（队列还在跑，回执画在队列那一行下面一行）：'
  + JSON.stringify(read?.texts ?? []).slice(-160),
  read !== null && read.texts.some((t) => t.includes('已减 3600 秒') && t.includes('还剩 120 秒')))
await page.screenshot({ path: path.join(OUT, 'tech-speeded-up.png') })
console.log(`  截图：${path.join(OUT, 'tech-speeded-up.png')}`)

// ---------- 队列那一行的「取消研究」：现在能进来了，就得能反悔 ----------
const cancelTapped = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let hit = null
  const walk = (n) => { if (n.name === 'CancelResearchButton' && n.active) hit = n; for (const c of n.children) if (c.active) walk(c) }
  walk(game)
  if (hit === null) return false
  hit.emit(hit.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
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

// ---------- 内城那格「取消建造」：/city/cancel 此前是零调用点，建造只能开始不能反悔 ----------
// 先把研究页关掉：它盖在内城上面，不关的话这一步的截图拍到的是上一页，
// 读数虽然是对的（按节点名直接读内城子树）但**目视**就成了自欺
await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let hit = null
  const walk = (n) => { if (n.name === 'close') hit = n; for (const c of n.children) walk(c) }
  walk(game?.getChildByName('techPanel') ?? game)
  if (hit !== null) hit.emit(hit.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
  return hit !== null
})()`)
await page.waitForTimeout(600)
checkTrue('研究页关得掉（那颗「关闭」按得到）', await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const tech = game?.getChildByName('techPanel')
  return tech !== undefined && tech !== null && !tech.active
})()`))
// ---------- 内城顶部那一行的版式：标题、队列行、两颗常驻角标各占其位 ----------
// #330 加第二颗角标时只按常量算过水平位置，没量矩形 —— 这张图才看出标题被压住。
// 判据按世界坐标的包围盒算，不按常量：常量对了不代表引擎排出来的对。
const RECTS = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('city')
  const rectOf = (name) => {
    let hit = null
    const walk = (n) => { if (n.name === name) hit = n; for (const c of n.children) walk(c) }
    walk(panel)
    if (hit === null) return null
    const box = hit.getComponent('cc.UITransform')?.getBoundingBox()
    if (box === undefined || box === null) return null
    return { x: Math.round(box.x), y: Math.round(box.y), w: Math.round(box.width), h: Math.round(box.height) }
  }
  // 2026-10-04 加：连文案与字号一起取回来。判据要比的是**字形跑多远**，
  // 而 getBoundingBox 给的是布局时写进去的 contentSize（见下面 textRunBox 的说明）。
  const labelOf = (name) => {
    let hit = null
    const walk = (n) => { if (n.name === name) hit = n; for (const c of n.children) walk(c) }
    walk(panel)
    if (hit === null) return null
    const lb = hit.getComponent('cc.Label')
    return lb == null ? null : { text: String(lb.string ?? ''), fontSize: lb.fontSize }
  }
  return {
    header: rectOf('Header'), queue: rectOf('Queue'),
    tech: rectOf('TechOpenButton'), collect: rectOf('CollectAllButton'),
    bar: rectOf('SelectionBar'), message: rectOf('Message'),
    headerText: labelOf('Header'), queueText: labelOf('Queue'),
  }
})()`
const rects = await page.evaluate(RECTS)
const overlaps = (a, b) => a !== null && b !== null
  && !(a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y)
// ⚠️ 2026-10-04 判据修正（这条此前是**恒红**的假判据，不是产品缺陷）：
// Header / Queue 是 `addLabel(..., leftAligned=false, maxWidth=headerCap)` 建的居中 Label，
// contentSize 被写成满宽（实测 header 盒子 766px，横跨 x∈[-383,383]），而字形只占中间一小段。
// 于是拿**盒子**去判"压没压住"，无论文案多短都会与右上角那颗常驻键相交 ——
// 实测读数 header x∈[-383,383] y∈[255,282]、collect x∈[294,426] y∈[235,269]，判相交；
// 而同一时刻的截图（client/build/tech-verify/city-cancel-build.png）里标题居中于
// x∈[635,805]、队列行 x∈[557,841]、一键收割 x∈[1163,1357]，**三者水平区间毫无交叠**。
// 改按**字形的保守上界**判：非空白字符一律按 1em 宽估（CJK 就是 1em，拉丁更窄 ⇒ 这是上界），
// 上界不撞 ⇒ 真字形必不撞；上界仍撞 ⇒ 才判红（文案长到压上去时它会红，判据仍能失败）。
const textRunBox = (rect, label) => {
  if (rect === null || rect === undefined || label === null || label === undefined) return null
  const chars = label.text.replace(/\s+/g, '').length
  const run = Math.min(rect.w, chars * label.fontSize)
  return { x: Math.round(rect.x + rect.w / 2 - run / 2), y: rect.y, w: Math.round(run), h: rect.h }
}
console.log(`  顶部那一行量到的矩形：${JSON.stringify(rects)}`)
console.log(`  字形保守上界：header=${JSON.stringify(textRunBox(rects?.header, rects?.headerText))} queue=${JSON.stringify(textRunBox(rects?.queue, rects?.queueText))}`)
check('标题不被「学院 · 研究」那颗压住', overlaps(rects?.header, rects?.tech), false)
check('标题不被「一键收割」角标压住', overlaps(textRunBox(rects?.header, rects?.headerText), rects?.collect), false)
check('队列那一行不被两颗常驻键压住',
  overlaps(textRunBox(rects?.queue, rects?.queueText), rects?.tech) === true
  || overlaps(textRunBox(rects?.queue, rects?.queueText), rects?.collect) === true, false)

// ── 选中那一格（#753 收尾）：这一段是这 10 项判据能成立的前提 ──
// 2026-10-03 实测：探针此前**从未选中任何一格** ⇒ `selectedId` 恒为 null ⇒
// `CityPanelView.wireActionButtons`（CityPanelView.ts:1478）的 `row !== null &&`
// 把**所有**动作按钮置为不可见 ⇒ `DetailCancelButton` 节点被创建了（hasCancel=true）
// 却 `active=false`，于是「点不到那颗取消键 / POST /city/cancel 实发 0 条 /
// buildingId 是 undefined / 资源不退」这 10 项全崩 —— 它们不是十条独立问题，
// 是同一个「没选中」的下游。
// 目标格子 `b_academy` 在夹具里是 gridX=3, gridY=3（:182），而线性索引按
// `CityPanel.ts:171  index = gridY * CITY_GRID_WIDTH + gridX`（CITY_GRID_WIDTH=6，:57）
// 算出来是 21，tile 的节点名是 `Grid-<index>`（CityPanelView.ts:805），
// 选中动作绑在 tile 的 touch-start 上（:1226）。
// ⚠️ 先打读数再跑判据：读不到 row 就不往下走，别再"读代码猜"。
const SELECT_TILE = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('city')
  let tile = null
  const walk = (n) => { if (n.name === 'Grid-21') tile = n; for (const c of n.children) walk(c) }
  if (panel) walk(panel)
  if (tile === null) return { ok: false, why: 'Grid-21 节点不存在' }
  // 走产品自己的入口：tile 的 touch-start 会做 selectedId = row.id 与 renderSelection(row)
  // （CityPanelView.ts:1226-1230）。这里 emit 而不是直接改 selectedId ——
  // 直接改 private 字段会测出一条生产走不到的路径。
  tile.emit('touch-start', { touch: { getID: () => 0, getUILocation: () => ({ x: 0, y: 0 }) } })
  return { ok: true, hasTile: true }
})()`
const selRead = await page.evaluate(SELECT_TILE)
console.log('[tech][select] 选中动作已下发 ' + JSON.stringify(selRead))
await page.waitForTimeout(500)
// ⚠️ 读数只做诊断输出，**不作判据**：`selectedId`/`selectedRow` 是 private，
//    组件挂在哪个节点上没查证过，拿它当判据就是"读代码猜"（今天已经错过 9 次）。
//    判据用下面那条**零假设**的：详情条文案不再是「点击建筑查看详情」。
const rowRead = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('city')
  let statusText = null
  const walk = (n) => {
    if (statusText === null && n.name === 'SelectionBar') {
      const visit = (m) => {
        const t = m.getComponent?.('cc.Label')?.string
        if (typeof t === 'string' && t.length > 0 && t !== '取消' && t !== '升级' && t !== '建造' && t !== '收割') statusText = t
        for (const k of m.children) visit(k)
      }
      visit(n)
    }
    for (const c of n.children) walk(c)
  }
  if (panel) walk(panel)
  return { statusText }
})()`)
console.log('[tech][select] 详情条读数 ' + JSON.stringify(rowRead)
  + ' ｜ 若 statusText 仍是「点击建筑查看详情」⇒ 那一格没被选中，下面所有按钮判据都只是量具没架对')
check('选中生效：详情条不再是「点建筑查看详情」（这是 row !== null 的等价可观测判据）',
  rowRead?.statusText !== null && rowRead.statusText !== '点击建筑查看详情', true)

const CITY_BAR = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('city')
  let cancel = null
  let upgrade = null
  const texts = []
  const resourceSlots = []
  const walk = (n) => {
    if (n.name === 'DetailCancelButton') cancel = n
    if (n.name === 'DetailUpgradeButton') upgrade = n
    const t = n.getComponent('cc.Label')?.string ?? ''
    if (t.length > 0) texts.push(t)
    const slot = /^Resource-([0-9])-([0-9])$/.exec(n.name)
    if (slot !== null) resourceSlots[Number(slot[1]) * 3 + Number(slot[2])] = t
    for (const c of n.children) walk(c)
  }
  walk(panel)
  return {
    hasCancel: cancel !== null,
    cancelVisible: cancel !== null && cancel.active,
    upgradeVisible: upgrade !== null && upgrade.active,
    // ── 诊断读数（#753 收尾，2026-10-03）：先有读数，再有判据 ──
    // ⚠️ 本段在反引号模板里，注释里**不许再出现反引号**，否则字符串提前闭合（node --check 立刻报）。
    // 「取消」键的渲染条件是 CityPanelView.ts:1486 的
    // (row.upgrading || row.paused) && !row.collectable，而 collectable 由
    // CityPanel.ts:138 的 done = countdown !== null && countdown <= 0 推导，
    // countdown 又来自 countdownMs(building.finishAt, offsetMs, localNow)。
    // ⇒ 键不出现只可能是两种：upgrading 没成立（已排除：夹具给的是 status:'UPGRADING'），
    //   或者 done 一上来就是 true —— 也就是**夹具的固定时间戳 NOW 与页面真实墙钟对不上**。
    // ⚠️ 只有 page 能看见的东西能写在这里：NOW 是 Node 侧变量，页面里**没有它**，
    //    在这里引用它会让 evaluate 直接抛 ReferenceError（node --check 抓不到，语法是合法的）。
    //    夹具的 nowMs 在 Node 侧打印时带上即可。
    wallClock: Date.now(),
    resourceSlots,
    overflow: (() => {
      let hit = ''
      const find = (n) => { if (n.name === 'ResourceOverflow') hit = n.getComponent('cc.Label')?.string ?? ''; for (const c of n.children) find(c) }
      find(panel)
      return hit
    })(),
    texts,
  }
})()`
let cityBar = null
const BAR_FRAMES = []
for (let i = 0; i < 20; i += 1) {
  cityBar = await page.evaluate(CITY_BAR)
  // 逐帧留读数：键一旦一直不出现，"它在哪一帧开始不出现"本身就是证据
  //（旧写法的 `hasCancel` 只留最后一帧，把过程全丢了 —— 今天这串误判有一半是它造成的）。
  BAR_FRAMES.push({ i, hasCancel: cityBar?.hasCancel, visible: cityBar?.cancelVisible,
    upgrade: cityBar?.upgradeVisible, wallClock: cityBar?.wallClock })
  if (cityBar?.hasCancel === true) break
  await page.waitForTimeout(500)
}
console.log('[tech][cityBar] 夹具 nowMs=' + NOW + ' finishAt=' + (NOW + 60_000)
  + ' · 逐帧 ' + JSON.stringify(BAR_FRAMES))
console.log('[tech][cityBar] 末帧 墙钟=' + cityBar?.wallClock + ' 与夹具 nowMs 相差 '
  + ((cityBar?.wallClock ?? 0) - NOW) + 'ms'
  + ' ⇒ 差值若是几十万量级，说明夹具的固定时间戳与真实墙钟严重错位，'
  + 'countdown 会一上来就 ≤0、collectable 直接为 true，按设计就不给「取消」键')
check('在升级那一格的动作条上有「取消」键', cityBar?.cancelVisible, true)
check('同一槽位的「升级」让位给「取消」（这一格已经在建，不能再开一次）',
  cityBar?.upgradeVisible, false)
// 先量"满 6 种"这一相：六颗固定 Label 都有字，且不许冒出"另有 N 项"那句
check('六种资源时六颗槽位都画上（对照组：不是只有前两颗有字）',
  (cityBar?.resourceSlots ?? []).filter((t) => (t ?? '').length > 0).length, 6)
check('装得下时不许多出"另有 N 项资源未显示"那句', cityBar?.overflow, '')
// 下一枪（取消建造）会重拉 /city/list —— 这一相服务端只给两种资源，
// 面板要是只写不清，尾部那四颗就会留着上一帧的 8000/2400/5200/3100。
CITY_STATE.shrunk = true
const cancelBuildTapped = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('city')
  let hit = null
  const walk = (n) => { if (n.name === 'DetailCancelButton' && n.active) hit = n; for (const c of n.children) if (c.active) walk(c) }
  walk(panel)
  if (hit === null) return false
  hit.emit(hit.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
  return true
})()`)
checkTrue('按得到那颗「取消」', cancelBuildTapped)
await page.waitForTimeout(1_500)
check('恰好发出一条 POST /city/cancel', CANCEL_BUILD_CALLS.length, 1)
check('请求体里是那一格的 buildingId', CANCEL_BUILD_CALLS[0]?.buildingId, 'b_academy')
checkTrue('取消建造带幂等键（退资源是写操作，重放会退两次）',
  typeof CANCEL_BUILD_CALLS[0]?.requestId === 'string' && CANCEL_BUILD_CALLS[0].requestId.length > 0)
cityBar = await page.evaluate(CITY_BAR)
checkTrue('底部把"退回来多少"照服务端给的数念出来：'
  + JSON.stringify((cityBar?.texts ?? []).filter((t) => t.includes('退回') || t.includes('取消'))),
  (cityBar?.texts ?? []).some((t) => t.includes('已取消建造') && t.includes('退回 木材 +300')))
// 回执画出来之后再量一次：那句回执与那颗键都在卡片底部，空文本时量不到（w=0 是假绿）。
const afterCancel = await page.evaluate(RECTS)
console.log(`  底部那一条量到的矩形：${JSON.stringify(afterCancel?.message)} vs 键 ${JSON.stringify(afterCancel?.tech)}`)
check('「学院 · 研究」不压选中详情条（压住就等于压住升级/取消那几颗能点的键）',
  overlaps(afterCancel?.bar, afterCancel?.tech), false)
check('「学院 · 研究」不压底部那句回执', overlaps(afterCancel?.message, afterCancel?.tech), false)
// 重拉之后量"变短"这一相：尾部四颗必须被清空，留下的两颗得是当帧的数
const shrunkSlots = cityBar?.resourceSlots ?? []
console.log(`  资源槽位（缩到两种之后）：${JSON.stringify(shrunkSlots)}`)
// ⚠️ 2026-10-04 判据修正（此前把"已清空的一种形态"当成没清空）：
// `resourceSlots` 是**稀疏数组**（CITY_BAR 只在扫到 `Resource-<a>-<b>` 节点时才赋值下标），
// 而 `JSON.stringify` 会把**空洞**序列化成 `null` ⇒ 实测读到
//   ["木材 8000/24000","铁矿 5200/24000",null,"","",null,"",""]
// 其中 `null` 是「那一格的节点已经不存在了」（比留一个空壳更彻底的清空），`""` 是「节点在、文本已清空」。
// 旧写法 `(slots[i] ?? 'x') === ''` 把 `null` 判成没清空 ⇒ 面板行为正确却记了一次红。
// 现在两种形态都算"清空"，但**留了旧数仍然判红**（判据没有因此变松）。
const slotCleared = (i) => i >= shrunkSlots.length || (shrunkSlots[i] ?? '') === ''
check('资源从六种缩到两种后，尾部那四颗被清空（不是留着上一帧的 2400/5200/3100/1500）',
  [2, 3, 4, 5].every(slotCleared), true)
check('留下的两颗是当帧的数（木材 8000、铁矿 5200）',
  (shrunkSlots[0] ?? '').includes('8000') === true && (shrunkSlots[1] ?? '').includes('5200') === true, true)
await page.screenshot({ path: path.join(OUT, 'city-cancel-build.png') })
console.log(`  截图：${path.join(OUT, 'city-cancel-build.png')}`)

check('运行期零 error（页面级报错）', errors.length, 0)
if (errors.length > 0) {
  for (const message of errors.slice(0, 3)) console.log(`    error: ${message.slice(0, 160)}`)
}

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
