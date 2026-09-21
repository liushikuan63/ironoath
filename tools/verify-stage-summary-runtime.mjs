#!/usr/bin/env node
/**
 * 职责：关卡面板两件事的运行判据 —— ①「结算摘要与行标签的版式不再靠默认 100×100 盒子」；
 *       ②「体力那一条真的画得出来，点「买体力」真的发一次带幂等键的请求」。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 STAGE_PORT（默认 8195，同机并发时换一个）
 *   BACKEND_ORIGIN=http://localhost:8199 STAGE_PORT=8195 node tools/verify-stage-summary-runtime.mjs
 *
 * <p><b>为什么单独跑这一份</b>：#316 与 #319 各修过一处「SHRINK 标签没盒子」（弹层行、战报行），
 * 本仓库里同族还剩这一处：`StagePanelView.buildSummary` 的 `SummaryText` 走本地 `addLabel`，
 * 那个助手只 `addComponent(UITransform)` 不给尺寸，于是默认 100×100 —— 摘要是五行上下的多行文本
 * （结算 / 体力 / 差额 / 奖励 / 损失），100 宽会把每一行再挤成两三行。
 *
 * <p><b>对照组是这份量具的关键一条</b>：探针里现建一个裸 `UITransform` 读出默认宽就是 100。
 * 没有它，"`w > 400` 说明修过了"只是我的断言而不是证据（默认值万一不是 100，判据永远为真）。
 *
 * <p><b>体力那三份读口都被钉死</b>（`/stamina`、`/stamina/buy`、`/resource/detail`）：
 * dev 新号的金币与今日已购是随机的，不钉死的话"点一下能不能买成"这条判据每次跑都在换前提。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[stage-summary] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.STAGE_PORT ?? 8195)
const OUT = path.resolve(process.cwd(), 'client/build/stage-summary-verify')
mkdirSync(OUT, { recursive: true })
/** 记录发出去的 `POST /stamina/buy` 请求体，用来断言"这一按真的发出去了、且带了幂等键" */
const BUY_CALLS = []

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
console.log(`=== 关卡摘要版式运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `stage-summary-${Date.now()}`)

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
const STAMINA_NOW = Date.now()
/**
 * 体力的"当前状态"，会被 `/stamina/buy` 推进 —— 面板买完之后必须**重读**才拿得到新值，
 * 所以这份夹具不能是静态的：静态的话"重读到了吗"这条判据根本测不出来（实测就是这样：
 * 第一版把三份读口都写死，买完那一条永远显示 84/120，看着像客户端没刷新）。
 */
const STAMINA = {
  current: 84, cap: 120, recoverPerHour: 5, nextPointAt: STAMINA_NOW + 192_000,
  boughtToday: 2, buyCostGold: 20,
}
const staminaBody = () => ({ ...STAMINA, serverNow: Date.now() })
/** 真点「挑战」那一相：发出去的请求体（要数次数、要核幂等键） */
const CHALLENGE_CALLS = []
/** 夹具到底改没改到第一关 —— 没改到的话后面那几条"结算亮着"就都是在读一个没发生过的状态 */
const stageFlip = { attempted: false, done: false }
await context.route('**/stage/list*', async (route) => {
  if (await passthrough(route)) return
  const upstream = await route.fetch()
  const envelope = await upstream.json()
  stageFlip.attempted = true
  const data = envelope.data ?? envelope
  if (Array.isArray(data.stages) && data.stages.length > 0) {
    // 只把第一关翻成"可挑战"，其余照真数据原样回：手写整份 StageListResp
    // 会造出一个现实中不存在的形状（#347 的教训），而这一相要问的正是真数据到位后画不画得出来
    data.stages[0] = { ...data.stages[0], unlocked: true, lockedReason: null }
    stageFlip.done = true
  }
  await reply(route, data)
})
// 挑战要先有编好的阵容才会弹三选一（`buildLineupChoices` 只认 `lineup.main !== null`）。
// dev 新号可能一个编队都没设主将，所以这里同样**抓真响应改最小一处**：有编队就把第一支的主将补上，
// 一个都没有时不硬造（造出来的 `bonus` 是现实中不存在的形状，#347 的教训），下面那一相改走"被挡下"的分支。
const heroFlip = { lineups: 0, mainSet: false }
/** A 支（完整挑战路径）本次到底跑没跑 —— 汇总行要带着这句话，通过数不能单独被引用 */
let branchAExecuted = false
await context.route('**/hero/list*', async (route) => {
  if (await passthrough(route)) return
  const upstream = await route.fetch()
  const envelope = await upstream.json()
  const data = envelope.data ?? envelope
  const lineups = Array.isArray(data.lineups) ? data.lineups : []
  heroFlip.lineups = lineups.length
  if (lineups.length > 0) {
    const firstHero = (Array.isArray(data.heroes) ? data.heroes : [])[0]
    if (firstHero?.id !== undefined && firstHero !== null) {
      lineups[0] = { ...lineups[0], main: lineups[0].main ?? firstHero.id }
      heroFlip.mainSet = lineups[0].main !== null && lineups[0].main !== undefined
    }
  }
  await reply(route, data)
})
// 挑战**不打桩**：把请求记下来、把服务端的真响应原样转回去。这一相要验的正是
// 「真结算到手后摘要带亮不亮」，用夹具替掉它等于把要验的那一段抽走。
await context.route('**/stage/challenge*', async (route) => {
  if (await passthrough(route)) return
  CHALLENGE_CALLS.push(JSON.parse(route.request().postData() ?? '{}'))
  const upstream = await route.fetch()
  const envelope = await upstream.json()
  await reply(route, envelope.data ?? envelope)
})
// 体力三份读口都钉死：dev 新号的金币与今日已购是随机的，
// 不钉死的话"点一下能不能买成"这条判据每次跑都在换前提
await context.route('**/stamina/buy*', async (route) => {
  if (await passthrough(route)) return
  BUY_CALLS.push(JSON.parse(route.request().postData() ?? '{}'))
  STAMINA.current = 104
  STAMINA.boughtToday = 3
  STAMINA.buyCostGold = 40
  await reply(route, {
    stamina: staminaBody(),
    granted: 20, costGold: 20, boughtToday: 3,
  })
})
await context.route('**/stamina*', async (route) => {
  if (await passthrough(route)) return
  // `**/stamina*` 也吃得到 `/stamina/buy`，让给上面那条处理
  if (route.request().url().includes('/stamina/buy')) {
    await route.fallback()
    return
  }
  await reply(route, staminaBody())
})
await context.route('**/resource/detail*', async (route) => {
  if (await passthrough(route)) return
  await reply(route, {
    resources: [{
      type: 'GOLD', current: 5000, cap: 100000, protectedAmount: 0,
      perHour: 0, lastSettle: STAMINA_NOW, full: false, breakdown: [],
    }],
    serverNow: STAMINA_NOW,
  })
})
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'stage')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
// 自检：产物里那两处写死的后端地址有没有真的被改写成本轮要打的那棵。
// 漏了这一句，传错变量名就是"打到另一台机器上读数"，红得像是产品缺陷（台账 #371）。
preview.assertRewritten()
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)

/** 读摘要标签与它所在面板的盒子；顺带把面板节点在不在报出来 */
const BOX = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('stage')
  if (panel === undefined || panel === null) return { found: false }
  let label = null
  let summaryPanel = null
  const walk = (node) => {
    if (node.name === 'SummaryText') label = node
    if (node.name === 'SummaryPanel') summaryPanel = node
    for (const child of node.children) walk(child)
  }
  walk(panel)
  if (label === null || summaryPanel === null) {
    return { found: true, hasLabel: label !== null, hasPanel: summaryPanel !== null }
  }
  const box = label.getComponent('cc.UITransform')
  const frame = summaryPanel.getComponent('cc.UITransform')
  // 行与摘要面板是同一个父节点的兄弟，位置都在同一套节点坐标里 —— 直接比矩形
  let rowsOver = 0
  let rows = 0
  const walkRows = (node) => {
    if (node.name === 'StageRow' && node.active) {
      rows += 1
      const h = node.getComponent('cc.UITransform').height
      const top = node.position.y + h / 2
      const bottom = node.position.y - h / 2
      const pTop = summaryPanel.position.y + frame.height / 2
      if (bottom < pTop) rowsOver += 1
    }
    for (const child of node.children) walkRows(child)
  }
  walkRows(panel)
  return {
    found: true, hasLabel: true, hasPanel: true,
    w: box.width, h: box.height, panelW: frame.width, panelH: frame.height,
    active: summaryPanel.active, rows, rowsOver,
    text: label.getComponent('cc.Label').string,
  }
})()`

let box = null
for (let i = 0; i < 40; i += 1) {
  await page.waitForTimeout(500)
  box = await page.evaluate(BOX)
  if (box?.hasLabel === true && box?.hasPanel === true) break
}
check('关卡面板里有摘要标签与摘要面板', box?.hasLabel === true && box?.hasPanel === true, true)
// 对照组：不设尺寸时 UITransform 的默认宽就是 100 —— 没有这条，"`w > 400` 说明修过了"
// 只是我的断言，不是证据（默认值万一不是 100，这条判据就永远为真）。
const control = await page.evaluate(`(() => {
  const node = new window.cc.Node('Control')
  // 取组件只用注册名：全局命名空间里的类对象直接 addComponent 会被引擎按"非本场景类"拒掉
  node.addComponent('cc.UITransform')
  return node.getComponent('cc.UITransform').width
})()`)
check('对照组：新建 UITransform 的默认宽就是 100（判据盯的正是这个默认值）', control, 100)
checkTrue('摘要标签的盒子按面板内框给（不再是默认 100 宽）',
  box !== null && box.w > 400 && box.w <= box.panelW)
checkTrue('摘要标签的盒子不高出面板（240 高的框里放得下）',
  box !== null && box.h > 100 && box.h <= box.panelH)

// 把五行仿真摘要喂进真实渲染路径（私有方法在运行时就是普通方法，走的是同一套排版）
const shown = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('stage')
  const view = panel?.getComponent('StagePanelView')
  if (view === undefined || view === null) return false
  view.showSummary([
    '扫荡 10 次 · 全部通关',
    '体力 -120（每次 12）',
    '本次未通关，体力已退回',
    '获得 木材 ×12,400 · 石料 ×8,600 · 金币 ×2,300 · 经验 ×41,000 · 加速道具（1 小时） ×2',
    '损失 T1 重步 ×1,240 · T2 弓手 ×380 · T3 骑兵 ×55（按阶级列，只给总数看不出掉的是哪档）',
  ], new window.cc.Color(226, 214, 190, 255))
  return true
})()`)
checkTrue('摘要能画出来（拿到 StagePanelView 组件并喂进五行仿真文案）', shown)
await page.waitForTimeout(400)
const after = await page.evaluate(BOX)
check('喂进去的摘要有五段（读回来不是空串）',
  (after?.text ?? '').split('\n').filter((line) => line.length > 0).length, 5)
check('摘要面板显示中', after?.active, true)
checkTrue('摘要出现时行区自己收进行数（画得出来的行不少于 1 条，不把列表清空）',
  after?.rows >= 1)
check('没有一行压在摘要面板上（两层半透明文字叠在一起就是这坨）', after?.rowsOver, 0)

// 行标签的盒子：锚点在中心又没设宽度时，左对齐的文字起点会跑到行的左边界外面（标题被屏幕裁字）
const rowBox = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('stage')
  let row = null
  const walk = (node) => { if (node.name === 'StageRow' && node.active) row = node; for (const c of node.children) if (c.active) walk(c) }
  walk(panel)
  if (row === null) return { found: false }
  const title = (row.children || []).find((c) => c.name === 'Title')
  const t = title?.getComponent('cc.UITransform')
  return {
    found: true,
    rowW: row.getComponent('cc.UITransform').width,
    titleW: t?.width ?? null,
    // 行的局部坐标里，文字盒子的左边缘（锚点 0 = 左）
    titleLeft: (t === undefined || t === null) ? null : title.position.x - t.anchorX * t.width,
    titleOverflow: title.getComponent('cc.Label').overflow === window.cc.Label.Overflow.SHRINK,
  }
})()`)
check('行上有标题标签且读得到盒子', rowBox?.found, true)
checkTrue('标题盒子的左边缘在行的框内（不再从行的左边界外面起笔）',
  rowBox !== null && rowBox.titleLeft >= -rowBox.rowW / 2)
checkTrue('标题盒子按行的可用宽度给（不是默认 100）', rowBox?.titleW > 300)
check('长文案超宽时是缩字而不是溢出（overflow=SHRINK）', rowBox?.titleOverflow, true)

// 行与摘要都不许压到导航条底下 —— "画了但玩家看不见"比少画一行更难发现
const navClip = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  if (game === undefined || game === null) return { found: false }
  let nav = null
  const findNav = (node) => { if (node.name === 'NavBar') nav = node; for (const c of node.children) findNav(c) }
  findNav(game)
  if (nav === null) return { found: false }
  const navTop = nav.worldPosition.y + nav.getComponent('cc.UITransform').height / 2
  const hits = []
  const walk = (node) => {
    if ((node.name === 'StageRow' || node.name === 'SummaryPanel') && node.active) {
      const bottom = node.worldPosition.y - node.getComponent('cc.UITransform').height / 2
      if (bottom < navTop) hits.push(node.name)
    }
    for (const c of node.children) if (c.active) walk(c)
  }
  walk(game)
  return { found: true, hits }
})()`)
check('导航条找得到', navClip?.found, true)
check('没有行、也没有摘要框压在导航条底下', navClip?.hits.length, 0)

// 截断通知必须看得见、且落在行区与摘要之间（钉死在"第 7 行下面"会飘进摘要框里）
const notice = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('stage')
  let noticeNode = null
  let summary = null
  let lastRowBottom = null
  const walk = (node) => {
    if (node.name === 'Overflow' && node.active) noticeNode = node
    if (node.name === 'SummaryPanel' && node.active) {
      summary = node.worldPosition.y + node.getComponent('cc.UITransform').height / 2
    }
    if (node.name === 'StageRow' && node.active) {
      const bottom = node.worldPosition.y - node.getComponent('cc.UITransform').height / 2
      if (lastRowBottom === null || bottom < lastRowBottom) lastRowBottom = bottom
    }
    for (const c of node.children) if (c.active) walk(c)
  }
  walk(panel)
  if (noticeNode === null) return { found: false }
  return {
    found: true,
    text: noticeNode.getComponent('cc.Label').string,
    y: noticeNode.worldPosition.y, summaryTop: summary, lastRowBottom,
  }
})()`)
check('截断通知找得到', notice?.found, true)
checkTrue('截断通知写明了还有多少关没画出来（不钉死具体数字，行数随窗口高度变）',
  /^另有 \d+ 关未显示$/.test(notice?.text ?? ''))
checkTrue('截断通知在最后一行之下、摘要框之上（没飘进摘要里）',
  notice !== null && notice.y < notice.lastRowBottom && notice.y > notice.summaryTop)
await page.screenshot({ path: path.join(OUT, 'stage-summary.png') })
console.log(`  截图：${path.join(OUT, 'stage-summary.png')}`)

// ---------- 体力那一条 + 买一次（B26 S22：`staminaView` / `staminaBuy` 第一次有玩家入口） ----------

const BAND = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('stage')
  if (panel === undefined || panel === null) return { found: false }
  const text = (name) => {
    let hit = null
    const walk = (node) => {
      if (node.name === name) hit = node.getComponent('cc.Label')?.string ?? ''
      for (const c of node.children) walk(c)
    }
    walk(panel)
    return hit
  }
  let band = null
  let button = null
  const spans = []
  const find = (node) => {
    if (node.name === 'StaminaBand') band = node
    if (node.name === 'BuyStaminaButton') button = node
    if (node.name === 'StaminaText' || node.name === 'BuyText') {
      const t = node.getComponent('cc.UITransform')
      spans.push({
        name: node.name,
        want: node.getComponent('cc.Label')?.fontSize ?? 0,
        left: node.worldPosition.x - t.width * t.anchorX,
        right: node.worldPosition.x + t.width * (1 - t.anchorX),
        top: node.worldPosition.y + t.height * (1 - t.anchorY),
        bottom: node.worldPosition.y - t.height * t.anchorY,
      })
    }
    for (const c of node.children) find(c)
  }
  find(panel)
  const a = spans.find((s) => s.name === 'StaminaText')
  const b = spans.find((s) => s.name === 'BuyText')
  // 两行都左对齐上下排：横向重叠本来就该有，但**字形带**不许撞上。
  // 下限按字号（em）算而不是按行盒：行盒 ≈ 字号×1.5，两行行盒相切并不代表玩家看见字叠
  // （#365 的教训）。#368/#369 把盒高抬到"装得下一行字"的实测下限之后，这条按行盒写的判据
  // 把中心距 28px、字号 17/15 的健康两行判成了红 —— 红的是判据，不是版式。
  const need = ((a?.want ?? 0) + (b?.want ?? 0)) / 2 + 4
  const centerGap = (a === undefined || b === undefined)
    ? null : Math.abs((a.top + a.bottom) / 2 - (b.top + b.bottom) / 2)
  const stacked = centerGap === null ? null : centerGap >= need
  return {
    found: true,
    bandActive: band !== null && band.active,
    hasButton: button !== null,
    stacked,
    wantA: a?.want ?? 0,
    wantB: b?.want ?? 0,
    centerGap: centerGap === null ? null : Math.round(centerGap),
    need: Math.round(need),
    // 左对齐真的设上了：addLabel 默认是 CENTER，忘了改会把两行文字往中间飘
    textInset: (a !== undefined && band !== null)
      ? a.left - (band.worldPosition.x - band.getComponent('cc.UITransform').width / 2) : null,
    staminaText: text('StaminaText'),
    buyText: text('BuyText'),
    headerText: text('Header'),
  }
})()`

let band = null
for (let i = 0; i < 40; i += 1) {
  await page.waitForTimeout(500)
  band = await page.evaluate(BAND)
  if (band?.bandActive === true) break
}
check('体力那一条画出来了（`/stamina` 到手且带子激活）', band?.bandActive, true)
check('那一条的两行字形带不相交（中心距 ≥ 两行字号之和的一半 + 4；行盒相交不算，#365）',
  band?.stacked, true)
// 字号读成 0 时上一条的 `need` 会小到恒真 ⇒ 先自证两个数都读到了
checkTrue('反空转前置：两行的字号都读到了（读不到则上一条恒真）',
  band !== null && band.wantA > 0 && band.wantB > 0)
checkTrue('两行都从带子的左内边起笔（没留在默认的中心对齐上）',
  band !== null && band.textInset >= 0 && band.textInset <= 40)
checkTrue('那一条写的是当前/上限与恢复倒计时（不是只有个数字）',
  band !== null && band.staminaText.includes('84/120') && band.staminaText.includes('后 +1'))
checkTrue('价格、今日已购与金币余额都摆出来了', band !== null
  && band.buyText.includes('20 金币') && band.buyText.includes('今日已购 2 次')
  && band.buyText.includes('金币 5000'))
check('表头不再同屏印第二个「体力」（谁新听谁的，两个数迟早有一个是旧的）',
  band?.headerText.includes('体力'), false)

const tappedBuy = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('stage')
  let hit = null
  const walk = (node) => { if (node.name === 'BuyStaminaButton') hit = node; for (const c of node.children) walk(c) }
  walk(panel)
  if (hit === null) return false
  hit.emit('touch-start')
  return true
})()`)
checkTrue('按得到那颗「买体力」', tappedBuy)
await page.waitForTimeout(1500)
check('点一次只发一个 `/stamina/buy`', BUY_CALLS.length, 1)
check('请求体里是 times=1', BUY_CALLS[0]?.times, 1)
checkTrue('扣金币的写口带了幂等键（没带就等于允许重放刷体力）',
  typeof BUY_CALLS[0]?.requestId === 'string' && BUY_CALLS[0].requestId.length > 0)
const after2 = await page.evaluate(BAND)
checkTrue('买完那一条按重读到的响应更新（104/120、今日已购 3 次、下一次 40 金币）',
  after2 !== null && after2.staminaText.includes('104/120')
    && after2.buyText.includes('今日已购 3 次') && after2.buyText.includes('40 金币'))
const receipt = await page.evaluate(BOX)
checkTrue('回执写在摘要带上：到账与扣币都照服务端说的念',
  (receipt?.text ?? '').includes('到账 20 体力 · 扣 20 金币'))
// 反空转的另一半：**字在 ≠ 看得见**。`attach()` 里那句 hideSummary() 只把面板关掉、不清字，
// 所以只读 text 的话，「回执被这次写操作自己触发的列表刷新抹掉」这一整类缺陷都是绿的
checkTrue('摘要面板此刻还亮着（等得到刷新回来，回执不会被自己抹掉）',
  receipt?.active === true)
await page.screenshot({ path: path.join(OUT, 'stage-stamina-bought.png') })
console.log(`  截图：${path.join(OUT, 'stage-stamina-bought.png')}`)

// ---------- 真点一次「挑战」：结算要走到摘要带上（#354 只钉到"送到了"，这一相钉"看得见"） ----------
// 反空转前置：夹具没真的把第一关翻成可挑战，下面每一条读的都是一个从没发生过的状态。
checkTrue('夹具确实把第一关翻成可挑战（`/stage/list` 真回过、真改到了一行）',
  stageFlip.attempted === true && stageFlip.done === true)

// 摘要带是**跨相复用的同一块**：上一相买体力那两行字还留在里面。
// 不先快照，下一相"读到字"就可能读到旧内容（#356 撤掉的那条假绿正是这个形状）。
const bandBefore = await page.evaluate(BOX)
const tappedChallenge = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('stage')
  if (!panel) return false
  let hit = null
  const walk = (node) => {
    if (hit === null && node.name === 'ChallengeButton' && node.activeInHierarchy) hit = node
    for (const c of node.children) walk(c)
  }
  walk(panel)
  if (hit === null) return false
  hit.emit('touch-start')
  return true
})()`)
checkTrue('按得到某一关那颗「挑战」', tappedChallenge)
await page.waitForTimeout(600)

const picker = await page.evaluate(`(() => {
  let overlay = null
  const walk = (node) => {
    if (overlay === null && node.name === 'ChoiceOverlay' && node.activeInHierarchy) overlay = node
    for (const c of node.children) walk(c)
  }
  walk(window.cc.director.getScene())
  if (overlay === null) return null
  const row = overlay.getChildByName('Choice-0')
  return {
    rowCount: overlay.children.filter((c) => /^Choice-[0-9]+$/.test(c.name)).length,
    firstRowText: row?.getComponent('cc.Label')?.string
      ?? row?.children.map((c) => c.getComponent('cc.Label')?.string ?? '').join(' ') ?? '',
  }
})()`)

if (heroFlip.mainSet) {
  // ---- 分支 A：dev 号有编队 ⇒ 走完整路径（弹窗 → 选 → 一个请求 → 真结算看得见）----
  branchAExecuted = true
  checkTrue('点挑战弹出的是阵容三选一（ChoiceOverlay 亮着且有候选行）',
    picker !== null && picker.rowCount > 0)
  checkTrue('候选那一行有字（空行等于弹层没数据也判绿）', (picker?.firstRowText ?? '').length > 0)

  const pickedLineup = await page.evaluate(`(() => {
    let overlay = null
    const walk = (node) => {
      if (overlay === null && node.name === 'ChoiceOverlay' && node.activeInHierarchy) overlay = node
      for (const c of node.children) walk(c)
    }
    walk(window.cc.director.getScene())
    const row = overlay?.getChildByName('Choice-0')
    if (!row) return false
    row.emit('touch-start')
    return true
  })()`)
  checkTrue('按得到候选那一行', pickedLineup)
  await page.waitForTimeout(1500)

  check('选完阵容只发一个 `/stage/challenge`（拆成多个请求会在弱网下只成一半）',
    CHALLENGE_CALLS.length, 1)
  checkTrue('打阵容的写口带了幂等键',
    typeof CHALLENGE_CALLS[0]?.requestId === 'string' && CHALLENGE_CALLS[0].requestId.length > 0)
  checkTrue('请求带上了刚选的那个阵容（heroes 或 units 至少一样非空）',
    (CHALLENGE_CALLS[0]?.units?.length ?? 0) > 0 || (CHALLENGE_CALLS[0]?.heroes?.length ?? 0) > 0)

  const settled = await page.evaluate(BOX)
  checkTrue('结算摘要**亮着**（不是字留在树里而面板已 hide —— #354 那条假绿的教训）',
    settled?.active === true)
  checkTrue('摘要里念的是这次结算（星级与体力都在服务端那份响应里）',
    (settled?.text ?? '').includes('星') && (settled?.text ?? '').includes('体力'))
} else {
  // ---- 分支 B：dev 号连一支编队都没有 ⇒ 走不到弹窗，但**被挡下这件事本身要说得出、看得见**----
  // 这一支不是降级凑数：`rejectNeeds('stage', ...)` 走的就是 #354 修的那条摘要带，
  // 挡下的话没画出来，玩家按「挑战」就会得到"没反应"——正是这一族最坏的样子。
  console.log(`  注：dev 号 lineups=${heroFlip.lineups}、heroes=0，走「被挡下」分支。`)
  console.log('  ⚠ **A 支那五条判据本次没有执行、历史上也从没执行过**（要跑它得给这个号一个真武将，'
    + '或凭空造一份 23 字段的 HeroView —— 那是 #347 警告过的"现实中不存在的形状"）。'
    + '所以本文件的通过数**不等于**挑战完整路径验过了。')
  check('这一支不该发出挑战请求', CHALLENGE_CALLS.length, 0)
  // #356 撤掉的那两条断言现在**还回来了**，而且是带防假绿形状的：先快照、再断"变了"，
  // 因为摘要带是跨相复用的同一块（上一相买体力的字会一直留在那儿）。
  const blocked = await page.evaluate(BOX)
  const reason = blocked?.text ?? ''
  console.log(`  被挡下时带上写的字：${reason.slice(0, 60)}`)
  checkTrue('带上那句话**变了**（不是读到上一相残留的字）',
    reason !== (bandBefore?.text ?? ''))
  checkTrue('变成的是这一件事的原因（`AppRoot.challenge` 三条真分支之一）',
    /没有已编成的阵容|没有可出战兵力|出战阵容/.test(reason))
  checkTrue('而且此刻摘要带是亮着的（#354 那条：字在 ≠ 看得见）', blocked?.active === true)
}
await page.screenshot({ path: path.join(OUT, 'stage-challenge-settled.png') })
console.log(`  截图：${path.join(OUT, 'stage-challenge-settled.png')}`)

check('运行期零 error（页面级报错）', errors.length, 0)
if (errors.length > 0) {
  for (const message of errors.slice(0, 3)) console.log(`    error: ${message.slice(0, 160)}`)
}

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
if (!branchAExecuted) {
  console.log('=== 其中「完整挑战路径」那一支（A 支五条）**未执行**：dev 号没有武将，补不出主将 ===')
}
process.exit(fail === 0 ? 0 : 1)
