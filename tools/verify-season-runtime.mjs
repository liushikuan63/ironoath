/**
 * 职责：赛季页（V04-S1）的**运行时**验收 —— 在真构建产物 + 真后端上打开「战力 → 赛季」页签。
 * 依赖：node、playwright、**已启动的 dev 服务端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：SEASON_BACKEND=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 SEASON_PROBE_PORT（默认 8172，同机并发时换一个）
 *   SEASON_MODE=disabled SEASON_BACKEND=http://localhost:8171 node tools/verify-season-runtime.mjs
 *   SEASON_MODE=enabled  SEASON_BACKEND=http://localhost:8173 node tools/verify-season-runtime.mjs
 *
 * 两种模式都要跑，因为它们是两件不同的事：
 *   - `disabled`（默认 dev：没配 SEASON_START_AT）⇒ 面板**整块收起**，只留一行说明，
 *     绝不画"第 0 天"。这一态最容易做假：服务端把 phase 回成 null 而客户端画成第 1 天，
 *     在屏幕上看不出错，只有读数能分开。
 *   - `enabled`（后端用带赛季锚点的临时 config-dir 起）⇒ 标题/阶段/倒计时/三条闸门/荣耀
 *     逐行与**探针自己直连后端拿到的那份响应**对得上。期望值在探针里按 B14 的措辞独立算一遍，
 *     不从页面上抄 —— 抄页面等于自己证明自己。
 *
 * <p><b>"倒计时来自服务端"是可失败的判据，不是口号</b>：第二个上下文把客户端时钟整体拨快 100 天，
 * 若哪天有人把 `phaseEndAt - serverNow` 换成 `Date.now()`，那一屏会变成"即将切换阶段"而本探针会红。
 * 不拨钟的话，本机客户端与后端同钟，"来自哪里"根本量不出来。
 *
 * <p><b>端口可换</b>（SEASON_PROBE_PORT / SEASON_BACKEND）：两个会话同时跑量具时，
 * 撞端口会表现为"读到别人的产物"，而那看起来像产物坏了。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const OUT = process.env.SEASON_VERIFY_OUT ?? path.resolve(process.cwd(), 'client/build/season-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.SEASON_PROBE_PORT ?? 8172)
// 必须显式给后端：静默回落到 http://localhost:8171 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.SEASON_BACKEND ?? (() => {
  console.error('[season] 缺 SEASON_BACKEND：不给就退回 http://localhost:8171，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
// 产物根可换：并行会话把带缺陷的半成品留在工作区时，主库的 web-mobile 会整包编不出来
// （脚本 bundle 缺类 → 页面黑屏）。这时在干净 worktree 里构建、把这里指过去即可 —— 别去动别人的文件。
const ARTIFACT = process.env.SEASON_ARTIFACT_ROOT ?? 'client/build/web-mobile'
const MODE = process.env.SEASON_MODE ?? 'disabled'
if (MODE !== 'enabled' && MODE !== 'disabled') {
  console.error(`SEASON_MODE 只能是 enabled / disabled，实际=${MODE}`)
  process.exit(2)
}

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

/** 期望值在探针里独立算一遍：措辞抄自 B14 §二，不从页面上读回来。 */
const PHASE_LABEL = {
  PREPARE: '开垦期', EXPAND: '立盟期', CAPITAL_WAR: '问鼎期', SETTLE: '结算期', REST: '休赛期',
}
const TIER_LABEL = {
  BRONZE: '青铜', SILVER: '白银', GOLD: '黄金', PLATINUM: '铂金', DIAMOND: '钻石', KING: '王者',
}
function countdownOf(phaseEndAt, serverNow) {
  const remain = phaseEndAt - serverNow
  if (remain <= 0) {
    return '即将切换阶段'
  }
  const days = Math.floor(remain / 86_400_000)
  return days >= 1 ? `还剩 ${days} 天` : `还剩 ${Math.max(1, Math.floor(remain / 3_600_000))} 小时`
}

const preview = await startPreviewServer({
  root: ARTIFACT,
  backend: BACKEND,
  port: PORT,
})

/** 直连后端（探针自己的对照组）。带不带身份各取一次：名次与荣耀只有带身份才有。 */
async function fetchStatus(playerId) {
  const res = await fetch(`${BACKEND}/season/status`, {
    headers: playerId ? { 'X-Player-Id': playerId } : {},
  })
  if (!res.ok) {
    throw new Error(`GET /season/status 回 ${res.status}`)
  }
  const body = await res.json()
  return body.data ?? body
}

let status
try {
  status = await fetchStatus(null)
} catch (error) {
  console.error(`探针起跑前先直连后端失败：${error.message}\n后端 ${BACKEND} 起来了吗？`)
  process.exit(2)
}
console.log(`=== 赛季页运行时验收：模式=${MODE}，产物经 ${preview.origin}，后端 ${BACKEND} ===`)
console.log(`  后端现状：phase=${status.phase} dayIndex=${status.dayIndex} seasonId=${status.seasonId}`)
if (MODE === 'enabled' && status.phase === null) {
  console.error('SEASON_MODE=enabled 但后端说赛季未启用 —— 这台后端起错了（要用带 SEASON_START_AT 的 config-dir）')
  process.exit(2)
}
if (MODE === 'disabled' && status.phase !== null) {
  console.error('SEASON_MODE=disabled 但后端已经在跑赛季 —— 这台后端不该配 SEASON_START_AT')
  process.exit(2)
}

const FIND_PANEL = `(game) => game.children
  .map(c => c.getComponent('PowerPanelView'))
  .find(Boolean)`

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

const has = (labels, text) => labels.some((l) => l.includes(text))

/** 打不开战力页（`?panel=power` 深链）。与其它面板量具同一条链。 */
async function openPowerPage(page) {
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

async function clickSeasonTab(page) {
  const hit = await page.evaluate(`(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas').getChildByName('Game')
    const panel = (${FIND_PANEL})(game)
    const node = panel.node.getChildByName('tab-SEASON')
    if (!node) return false
    node.emit('touch-start')
    return true
  })()`)
  if (!hit) {
    bad('页签 tab-SEASON 不在面板上')
  }
  await page.waitForTimeout(1200)
}

/** 每一行文字的纵向位置都要落在面板高度之内（溢出只有量位置才看得见，截图也未必注意到）。 */
function checkInsidePanel(snapshot, where) {
  const { ys, halfHeight } = snapshot
  if (halfHeight <= 0) {
    bad(`${where}：拿不到面板高度，量不了溢出`)
    return
  }
  const out = ys.filter((y) => y > halfHeight || y < -halfHeight)
  check(`${where}：${ys.length} 行文字都落在面板内`, out.length, 0)
}

const deviceId = `season-runtime-${Date.now()}`
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, deviceId)
const page = await context.newPage()
const errors = []
const requests = []
let playerId = null
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
// 从**应用自己发的请求**里取身份：探针要拿同一个身份再问一次，才能对齐名次与荣耀两行
page.on('request', (request) => {
  requests.push(request.url())
  const header = request.headers()['x-player-id']
  if (header) {
    playerId = header
  }
})

await openPowerPage(page)
const first = await page.evaluate(LABEL_SNAPSHOT)
if (first === null || !first.active) {
  bad('战力面板没打开（PowerPanelView 不在场景里）—— 后面的读数都无从谈起')
  await browser.close()
  process.exit(1)
}
ok('战力页打开了')

// 页签条：六个（明细 + 四类榜 + 赛季）。赛季页签是这一页的唯一入口，缺了整页就访问不到
for (const label of ['明细', '战力榜', '击杀榜', '联盟榜', '国家榜', '赛季']) {
  check(`页签条上有「${label}」`, has(first.labels, label), true)
}
check('默认停在明细页（总计那一行在）', has(first.labels, '总计'), true)
check('打开战力页不拉赛季（首屏预算）', requestsOf('/season/status'), 0)

await clickSeasonTab(page)
const snap = await page.evaluate(LABEL_SNAPSHOT)
if (snap === null) {
  bad('点完赛季页签后读不到面板')
  await browser.close()
  process.exit(1)
}
check('赛季页签下不再画榜（总计那一行让位了）', has(snap.labels, '总计'), false)

if (MODE === 'disabled') {
  // 未启用赛季：整块收起。**不许出现"第 N / 45 天"这个形状的任何东西**
  checkTrue('未启用时画了说明行', has(snap.labels, '本服尚未启用赛季'))
  check('未启用时不画赛季标题（没有"第 N / 45 天"）', snap.labels.some((l) => l.includes('/ 45 天')), false)
  check('未启用时不画阶段行', has(snap.labels, '阶段'), false)
  check('未启用时不画闸门句', has(snap.labels, '可以攻击其他玩家'), false)
  checkTrue('赛季页签亮着（说明是这一页在收起，而不是页签没切过去）', snap.labels.length >= 6)
} else {
  const playerStatus = playerId === null ? null : await fetchStatus(playerId)
  const shown = playerStatus ?? status
  // 逐行与探针自己算的期望值对账
  const expectTitle = `${shown.seasonId} 赛季 · 第 ${shown.dayIndex + 1} / ${shown.totalDays} 天`
  checkTrue('标题是「赛季名 · 第 N / 45 天」', has(snap.labels, expectTitle))
  const expectPhase = `${PHASE_LABEL[shown.phase]} · ${countdownOf(shown.phaseEndAt, shown.serverNow)}`
  checkTrue(`阶段行与后端一致（${expectPhase}）`, has(snap.labels, expectPhase))
  checkTrue('允许玩家间攻击时那句照抄服务端布尔', has(snap.labels, '可以攻击其他玩家'))
  checkTrue('王城未开时说的是"尚未开放（问鼎期才开）"，不是"维护中"',
    has(snap.labels, '中央王城尚未开放（问鼎期才开）'))
  checkTrue('赛季进行中那一句在（readOnly=false）', has(snap.labels, '赛季进行中'))
  // 保留项那一句由 drawHint 按 22 字**换行**画，所以拼起来再匹配 —— 一行行找整句会永远找不到，
  // 而那不是面板的问题（"这句在不在"与怎么换行无关）
  const flat = snap.labels.join('')
  checkTrue('保留项那一句在（B14：保留什么、降 1~2 段、归档 3 季）',
    flat.includes('保留：荣耀等级、历史最高段位、赛季徽章')
    && flat.includes('降 1~2 段') && flat.includes('归档保留 3 个赛季'))
  // 名次与荣耀：identity 那一路（探针用应用自己发的身份再问一次）
  if (shown.myRank !== null && shown.myRank > 0) {
    checkTrue(`我的名次逐字一致（第 ${shown.myRank} 名）`, has(snap.labels, `我的名次：第 ${shown.myRank} 名`))
  } else {
    check('没上榜（myRank=0）时不画名次行 —— 0 名不是"第 0 名"',
      snap.labels.some((l) => l.includes('我的名次')), false)
  }
  if (shown.glory !== null) {
    const expectGlory = `荣耀 ${shown.glory.gloryLevel} 级 · 最高段位 ${TIER_LABEL[shown.glory.highestTier]}`
      + ` · 徽章 ${shown.glory.badges.length} 枚`
    checkTrue(`荣耀行是中文段位（${expectGlory}）`, has(snap.labels, expectGlory))
  }
  check('启用时不画"尚未启用"那一句（对照组）', has(snap.labels, '尚未启用赛季'), false)
}

checkInsidePanel(snap, MODE === 'disabled' ? '未启用态' : '启用态')
check('页面零错误', errors.join(' | ') || '无', '无')
await page.screenshot({ path: path.join(OUT, `season-${MODE}.png`) })
console.log(`  截图：${path.join(OUT, `season-${MODE}.png`)}`)

if (MODE === 'enabled') {
  // 倒计时必须来自服务端两个时刻：把客户端时钟整体拨快 100 天，那一行不该变
  const shifted = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await shifted.addInitScript(() => {
    const realNow = Date.now.bind(Date)
    Date.now = () => realNow() + 100 * 86_400_000
  })
  await shifted.addInitScript((value) => {
    localStorage.setItem('ironoath.deviceId', value)
  }, `${deviceId}-shifted`)
  const shiftedPage = await shifted.newPage()
  const shiftedErrors = []
  shiftedPage.on('pageerror', (error) => shiftedErrors.push(error.message))
  await openPowerPage(shiftedPage)
  await clickSeasonTab(shiftedPage)
  const shiftedSnap = await shiftedPage.evaluate(LABEL_SNAPSHOT)
  if (shiftedSnap === null || !shiftedSnap.active) {
    bad('拨钟那一轮：面板没打开，量不了倒计时来源')
  } else {
    const expectPhase = `${PHASE_LABEL[status.phase]} · ${countdownOf(status.phaseEndAt, status.serverNow)}`
    checkTrue(`客户端时钟快 100 天，倒计时仍是服务端算的（${expectPhase}）`, has(shiftedSnap.labels, expectPhase))
    check('拨钟后没有变成"即将切换阶段"', has(shiftedSnap.labels, '即将切换阶段'), false)
    await shiftedPage.screenshot({ path: path.join(OUT, 'season-enabled-clock-shifted.png') })
  }
  check('拨钟那一轮页面零错误', shiftedErrors.join(' | ') || '无', '无')
  await shifted.close()
}

await browser.close()
await preview.close?.()

console.log(`\n=== 赛季页运行时验收（${MODE}）：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)

/** 产物页面自己发的请求里，打到某个路径的条数。 */
function requestsOf(pathname) {
  return requests.filter((u) => u.includes(pathname)).length
}
