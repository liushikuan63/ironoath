/**
 * 职责：排行榜面板的**运行时**验收（B23 S3）—— 在真构建产物 + 真服务端上把四个页签走一遍。
 * 依赖：node、playwright、**已启动的 dev 服务端**、已构建的 `client/build/web-mobile`。
 * 用法：node tools/verify-rank-runtime.mjs
 * 必填：RANK_BACKEND=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 RANK_PROBE_PORT（默认 8192，同机并发时换一个）
 *
 * <p>为什么必须有这一个：纯逻辑用例（`client/tests/RankBoard.test.ts`）与编排用例证的是
 * "数据装对了、请求发对了"，而**面板有没有把页签画出来、点了页签会不会切**只有真跑才知道 ——
 * 这个仓库已经栽过一次（`[...map.values()]` 被 SWC 编成不展开迭代器，节点测试全绿、面板整个画不出来）。
 *
 * <p><b>本探针不验"榜上真有人"那一屏</b>，刻意如此：dev 服上的新号没有战力上报也没有击杀
 * （要有得练兵/上阵/真打一仗），而"给探针发兵"正是被禁止的作弊端点。所以这里验的是
 * 页签条、切换、空态与翻页按钮的状态 —— 行渲染那条留给真服上来之后的人工/真机复核。
 *
 * <p>端口用环境变量可换（RANK_PROBE_PORT / RANK_BACKEND）：两个会话同时跑量具时，
 * 撞端口会表现为"读到别人的产物"，而那看起来像产物坏了。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const OUT = process.env.RANK_VERIFY_OUT ?? path.resolve(process.cwd(), 'client/build/rank-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.RANK_PROBE_PORT ?? 8192)
// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.RANK_BACKEND ?? (() => {
  console.error('[rank] 缺 RANK_BACKEND：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()

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

const preview = await startPreviewServer({
  root: 'client/build/web-mobile',
  backend: BACKEND,
  port: PORT,
})
console.log(`=== 榜单面板运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const deviceId = `rank-runtime-${Date.now()}`
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, deviceId)
const page = await context.newPage()
const errors = []
const requests = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
page.on('request', (request) => requests.push(request.url()))

const rankCalls = () => requests.filter((u) => u.includes('/rank/list')).length

/** 打开战力页并等场景就绪。深度链与美术量具同一条（裸串里带 & 会被 Cocos 的 loader 截断）。 */
async function openPowerPanel() {
  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', 'power')
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  // 自检：产物里那两处写死的后端地址有没有真的被改写成本轮要打的那棵。
  // 漏了这一句，传错变量名就是"打到另一台机器上读数"，红得像是产品缺陷（台账 #371）。
  preview.assertRewritten()
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
  await hideGuideOverlay(page)
  await page.waitForTimeout(1800)
}

/**
 * 面板上的组件按**构造器名**找，不用 `getComponent('PowerPanelView')`：
 * 在构建产物里按字符串查类名查不到（实测返回 null，而同一节点的 `components` 里它就在那儿），
 * 于是"面板没打开"会变成一个假红 —— 探针自己先红在工具用法上。
 */
const FIND_PANEL = `(game) => game.children
  .map(c => c.getComponent('PowerPanelView'))
  .find(Boolean)`

/** 面板树上所有可见文案（递归收集 Label），**同时记下每一行的纵向位置**。 */
const LABEL_SNAPSHOT = `(() => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  const panel = (${FIND_PANEL})(game)
  if (!panel) return null
  const out = []
  const ys = []
  const walk = (node, dy) => {
    const y = dy + node.getPosition().y
    const label = node.getComponent('cc.Label')
    if (label && label.string) {
      out.push(label.string)
      ys.push(y)
    }
    for (const child of node.children) walk(child, y)
  }
  walk(panel.node, 0)
  const transform = panel.node.getComponent('cc.UITransform')
  return {
    active: panel.node.active,
    labels: out,
    ys,
    halfHeight: transform ? transform.contentSize.height / 2 : -1,
  }
})()`

const readPanel = () => page.evaluate(LABEL_SNAPSHOT)

/** 点一个页签：直接对它发 touch-start（与真人点下去走的是同一个回调）。 */
async function clickTab(key) {
  const hit = await page.evaluate(`(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas').getChildByName('Game')
    const panel = (${FIND_PANEL})(game)
    const node = panel.node.getChildByName('tab-${key}')
    if (!node) return false
    node.emit('touch-start')
    return true
  })()`)
  if (!hit) {
    bad(`页签 tab-${key} 不在面板上`)
    return
  }
  await page.waitForTimeout(900)
}

const has = (labels, text) => labels.some((l) => l.includes(text))

/**
 * 内容有没有跑出面板：**每一行文字的纵向位置都要落在面板高度之内**。
 *
 * <p>这条判据是"加页签条"那次真跑发现的：页签占掉 44 单位之后，明细页最后那行提示
 * 掉到面板外、压在底部导航条上（截图看得见，而 21 条读数全绿）。
 * 只数"画了几个节点"永远看不出这类溢出，所以要量位置。
 */
function checkInsidePanel(snapshot, where) {
  const { ys, halfHeight } = snapshot
  if (halfHeight <= 0) {
    bad(`${where}：拿不到面板高度，量不了溢出`)
    return
  }
  const worst = ys.reduce((acc, y) => (Math.abs(y) > Math.abs(acc) ? y : acc), 0)
  const inside = Math.abs(worst) <= halfHeight + 1
  check(`${where}：最靠边的一行仍在面板内（${worst.toFixed(1)} vs ±${halfHeight.toFixed(1)}）`,
    inside, true)
}

/** 底部导航条的上沿。翻页控件落在它下面 = 玩家点不到（画出来了但没用的那一类）。 */
const NAV_TOP = -210

/** 翻页那一行有没有让开底部导航条（位置判据，不是"画没画"）。 */
async function checkPagerAboveNav(where) {
  const ys = await page.evaluate(`(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas').getChildByName('Game')
    const panel = (${FIND_PANEL})(game)
    return panel.node.children.filter(n => n.name === 'pager').map(n => n.getPosition().y)
  })()`)
  if (ys.length === 0) {
    bad(`${where}：翻页控件一个都没找到`)
    return
  }
  const lowest = Math.min(...ys)
  check(`${where}：翻页那一行在导航条上方（${lowest.toFixed(1)} > ${NAV_TOP}）`,
    lowest > NAV_TOP, true)
}

await openPowerPanel()
const first = await readPanel()
if (first === null || !first.active) {
  bad('战力面板没打开（PowerPanelView 不在场景里）—— 后面的读数都无从谈起')
  await browser.close()
  process.exit(1)
}
ok('战力页打开了')

// 验收：六个页签都在（明细 + 四类榜 + 赛季）。页签是点进榜的唯一入口，缺一个就有一张榜看不到
for (const label of ['明细', '战力榜', '击杀榜', '联盟榜', '国家榜', '赛季']) {
  check(`页签条上有「${label}」`, has(first.labels, label), true)
}
// 默认停在明细页：原来那一页的内容一行没少（总计那一行是它最显眼的标志）
check('默认停在明细页（总计那一行在）', has(first.labels, '总计'), true)
const beforeTabs = rankCalls()
check('明细页不发榜单请求', beforeTabs, 0)
checkInsidePanel(first, '明细页')

// 切到战力榜：请求发出去、空态说明画出来、页签高亮跟着走
await clickTab('POWER')
const power = await readPanel()
check('切到战力榜后发了一次 /rank/list', rankCalls(), 1)
check('明细页的内容让位（总计那一行不在了）', has(power.labels, '总计'), false)
check('空榜有说明（"这个榜还没有人"）', has(power.labels, '这个榜还没有人'), true)
check('未上榜有下一步指引', has(power.labels, '还没有上榜') || has(power.labels, '还没有分数'), true)
check('页号画出来了', has(power.labels, '第 1 页'), true)
check('翻页按钮画出来了', has(power.labels, '上一页') && has(power.labels, '下一页'), true)
checkInsidePanel(power, '战力榜')
await checkPagerAboveNav('战力榜')
await page.screenshot({ path: path.join(OUT, 'rank-board-page.png') })

// 第 1 页点「上一页」：不发请求（按钮在服务端说 canPrev=false 时就不该接触摸）
const beforePrev = rankCalls()
const prevHit = await page.evaluate(`(() => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  const panel = (${FIND_PANEL})(game)
  for (const node of panel.node.children) {
    if (node.name !== 'pager') continue
    const label = node.children[0] && node.children[0].getComponent('cc.Label')
    if (label && label.string === '上一页') {
      node.emit('touch-start')
      return true
    }
  }
  return false
})()`)
await page.waitForTimeout(700)
check('第 1 页的「上一页」按钮点得到', prevHit, true)
check('第 1 页点「上一页」不发请求（canPrev=false 时按钮不吃触摸）', rankCalls(), beforePrev)

// 换一张榜：页签高亮与请求的 type 都要跟着走
await clickTab('KILL')
const kill = await readPanel()
check('切到击杀榜后 /rank/list 的次数', rankCalls(), 2)
check('击杀榜那一屏也画出来了（"这个榜还没有人"）', has(kill.labels, '这个榜还没有人'), true)
checkInsidePanel(kill, '击杀榜')

// 明细页来回切：不重复发榜请求
const beforeBack = rankCalls()
await clickTab('DETAIL')
const back = await readPanel()
check('切回明细页不发榜单请求', rankCalls(), beforeBack)
check('切回明细页后总计又回来了', has(back.labels, '总计'), true)
checkInsidePanel(back, '明细页（回切后）')

await page.screenshot({ path: path.join(OUT, 'rank-power-panel.png') })
console.log(`  截图：${path.join(OUT, 'rank-power-panel.png')}`)
check('运行期零 error（页面级报错）', errors.length, 0)
if (errors.length > 0) {
  for (const message of errors.slice(0, 3)) console.log(`    error: ${message.slice(0, 160)}`)
}

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
