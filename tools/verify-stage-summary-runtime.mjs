#!/usr/bin/env node
/**
 * 职责：把「关卡结算摘要的多行文本不再被挤进 100 宽的默认盒子」变成能失败的判据。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 *   BACKEND_ORIGIN=http://localhost:8199 STAGE_PORT=8195 node tools/verify-stage-summary-runtime.mjs
 *
 * <p><b>为什么单独跑这一份</b>：#316 与 #319 各修过一处「SHRINK 标签没盒子」（弹层行、战报行），
 * 本仓库里同族还剩这一处：`StagePanelView.buildSummary` 的 `SummaryText` 走本地 `addLabel`，
 * 那个助手只 `addComponent(UITransform)` 不给尺寸，于是默认 100×100 —— 摘要是五行上下的多行文本
 * （结算 / 体力 / 差额 / 奖励 / 损失），100 宽会把每一行再挤成两三行。
 *
 * <p><b>它盯的三件事</b>：① 摘要标签的盒子真的按面板内框给了（宽度不再是 100，且不超过面板）；
 * ② 摘要显示时面板本身在（不是靠"看不见"当"没溢出"）；③ 截图目视每行是一行。
 *
 * <p><b>合成数据说明</b>：摘要文本由 `showSummary` 直接喂进真实渲染路径（关卡扫荡要真打过才出摘要，
 * 那是服务端口径、`StageEndpointTest` 那头已验）。这里验的是**版式**，喂的是仿真的五行文案。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.STAGE_PORT ?? 8195)
const OUT = path.resolve(process.cwd(), 'client/build/stage-summary-verify')
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
console.log(`=== 关卡摘要版式运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `stage-summary-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'stage')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
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

check('运行期零 error（页面级报错）', errors.length, 0)
if (errors.length > 0) {
  for (const message of errors.slice(0, 3)) console.log(`    error: ${message.slice(0, 160)}`)
}

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
