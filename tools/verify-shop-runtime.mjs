#!/usr/bin/env node
/**
 * 职责：把「商店面板画得出来、切页签真的换账本」变成能失败的判据（B24 S-b 的完成判据）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 SHOP_PORT（默认 8098，同机并发时换一个）
 *   BACKEND_ORIGIN=http://localhost:8075 node tools/verify-shop-runtime.mjs
 *
 * <p><b>为什么用 web 产物证</b>：`ShopPanelView` 在两个平台上都是同一份代码（小游戏那边没有
 * 可编程点击通道）。判据读的是场景图（Cocos 画在 canvas 上，DOM 里一个字都没有）。
 *
 * <p><b>它盯的四件事</b>：① 面板挂上了、切过去就能画（四个页签 + 余额 + 货架行）；
 * ② 货架内容来自**服务端下发的那一行**（价签里带币种名、锁定的行也画出来并带原因）；
 * ③ 切页签真的换了账本（发一次 `?currency=SEASON_COIN`，且页面上的币种名跟着变）；
 * ④ 行区没有画到导航条下面（可视高度是量出来的，不是设计高度）。
 *
 * <p><b>不验的</b>：真金白银的兑换（`/shop/buy` 会扣货币发道具，新号余额为 0 ⇒ 每一行都不可兑换，
 * 那正是"锁定行带原因"这一条要验的形状）。所以「点兑换 → 拿到道具」这条链路由
 * `ShopEndpointTest` 与 `AppRoot.test.ts` 覆盖，探针只验"画出来了 + 页签切得对"。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = path.resolve('client/build/web-mobile')
// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，读数错得像产品缺陷
// （2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错 —— 台账 #371/#372）。
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-shop-runtime] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.SHOP_PORT ?? 8098)
const SHOT_DIR = path.resolve('client/build/shop-verify')
/** 屏幕底部要给导航条让出的高度（与面板里的常量同源：8 + 52 + 8）。 */
const BOTTOM_RESERVED = 68
/** 行高与行距（与 `ShopPanelView` 的常量同源）。用来判"行是不是真的按序号排开了"。 */
const ROW_HEIGHT = 62
const ROW_GAP = 5

const failures = []
const lines = []
function verdict(ok, label, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}  ${detail}`)
  if (!ok) {
    failures.push(label)
  }
}

/** 读商店那一格：页签文字、表头与余额/提示、每一行的文字、最低行底边、当前导航格。 */
function readShop() {
  const out = { found: false, active: false, currentKey: null, tabs: [], labels: [], rows: [],
    rowYs: [], lowestRowBottom: null, visibleHeight: null, tabPositions: [] }
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  out.currentKey = game?.getComponent('PanelNav')?.currentKey ?? null
  const size = window.cc.view.getVisibleSize()
  out.visibleHeight = size.height
  const panel = game?.getChildByName('shop')
  if (panel === undefined || panel === null) {
    return out
  }
  out.found = true
  out.active = panel.activeInHierarchy !== false
  const labelOf = (node) => {
    const own = node.getComponent && node.getComponent('cc.Label')
    if (own !== null && own !== undefined && own.string !== '') {
      return own.string
    }
    for (const child of node.children) {
      const label = child.getComponent && child.getComponent('cc.Label')
      if (label !== null && label !== undefined && label.string !== '') {
        return label.string
      }
    }
    return null
  }
  for (const child of panel.children) {
    if (child.activeInHierarchy === false) continue
    if (child.name.startsWith('Tab_')) {
      const text = labelOf(child)
      if (text !== null) out.tabs.push(text)
      out.tabPositions.push({ currency: child.name.slice(4), x: child.position.x, y: child.position.y })
      continue
    }
    if (child.name === 'ShopRow') {
      const texts = []
      for (const grand of child.children) {
        const label = grand.getComponent && grand.getComponent('cc.Label')
        if (label !== null && label !== undefined && label.string !== '') texts.push(label.string)
      }
      out.rows.push(texts.join(' / '))
      out.rowYs.push(child.position.y)
      const bottom = child.position.y - 31
      if (out.lowestRowBottom === null || bottom < out.lowestRowBottom) out.lowestRowBottom = bottom
      continue
    }
    const text = labelOf(child)
    if (text !== null) out.labels.push(`${child.name}:${text}`)
  }
  return out
}

/**
 * 货架文字的几何：一颗 Label 的盒子高度低于 27 就会被引擎**按比例缩字**
 * （`overflow=SHRINK` 把盒子当缩放系数，常数见收口清单 #381）。
 * 只查这一件事；"字与字有没有碰上"交给横扫量具，它已经按估宽算过了。
 */
function readGeometry() {
  const FLOOR = 27
  const out = { labels: 0, tight: [] }
  const scene = window.cc.director.getScene()
  const panel = scene.getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('shop')
  if (panel === null || panel === undefined) return out
  const shots = []
  const walk = (node, depth) => {
    if (node.activeInHierarchy === false || depth > 12) return
    const label = node.getComponent && node.getComponent('cc.Label')
    if (label !== null && label !== undefined && label.string !== '') {
      const box = node.getComponent('cc.UITransform')
      shots.push({
        text: label.string.slice(0, 22),
        h: box !== null && box !== undefined ? box.height : 0,
        font: label.fontSize,
        shrink: label.overflow === 2,
      })
    }
    for (const child of node.children) walk(child, depth + 1)
  }
  walk(panel, 0)
  out.labels = shots.length
  for (const s of shots) {
    if (s.shrink && s.h < FLOOR) {
      out.tight.push(s.text + '(' + Math.round(s.h) + '<' + FLOOR + ',字' + s.font + ')')
    }
  }
  return out
}

async function main() {
  mkdirSync(SHOT_DIR, { recursive: true })
  const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  const page = await context.newPage()
  const errors = []
  const listCalls = []
  page.on('pageerror', error => errors.push(String(error)))
  page.on('request', (req) => {
    if (req.url().includes('/shop/list')) listCalls.push(req.url())
  })

  let boot = null
  page.on('console', (msg) => {
    const text = msg.text()
    if (text.startsWith('[boot] ')) {
      try { boot = JSON.parse(text.slice('[boot] '.length)) } catch { /* 非结构化那条不算数 */ }
    }
  })

  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', 'shop')
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  const deadline = Date.now() + 45_000
  while (boot === null && Date.now() < deadline) {
    await page.waitForTimeout(500)
  }
  preview.assertRewritten()
  if (boot === null) {
    await browser.close()
    await preview.close()
    console.error('\n=== 判定中止：没捕获到 [boot] 自检行，读数会是假的 ===')
    process.exit(1)
  }
  verdict(boot.started === true, '启动跑通（started=true）', `platform=${boot.platform} bootMs=${boot.bootMs}`)

  let read = null
  for (let i = 0; i < 40; i += 1) {
    await page.waitForTimeout(500)
    read = await page.evaluate(readShop)
    if (read.currentKey === 'shop' && read.tabs.length >= 4 && read.rows.length > 0) {
      break
    }
  }

  verdict(read?.found === true && read?.currentKey === 'shop',
    '深链 ?panel=shop 生效（导航第 14 项挂上了）',
    `found=${read?.found} 当前格=${read?.currentKey ?? '—'}`)
  verdict(JSON.stringify(read?.tabs) === JSON.stringify(['金币', '贡献', '小队币', '赛季币']),
    '四个币种页签按顺序画出来了', `tabs=${JSON.stringify(read?.tabs)}`)
  const balance = (read?.labels ?? []).find(text => text.startsWith('Balance:')) ?? ''
  verdict(/余额|金币|赛季币|贡献|小队币/.test(balance),
    '余额那一行画出来了（带币种名）', `"${balance}"`)
  verdict((read?.rows ?? []).length > 0, '货架行画出来了',
    `行数=${read?.rows?.length}：${(read?.rows ?? [])[0] ?? '—'}`)
  verdict((read?.rows ?? []).some(row => /金币/.test(row)),
    '价签里带币种名（说明价签来自服务端下发的那一行）',
    (read?.rows ?? []).find(row => /金币/.test(row)) ?? '—')
  const header = (read?.labels ?? []).find(text => text.startsWith('Header:')) ?? ''
  verdict(/货架 \d+\/\d+ 件/.test(header), '表头说清了这一页有多少件', `"${header}"`)

  const navTop = -(read?.visibleHeight ?? 640) / 2 + BOTTOM_RESERVED
  verdict(read?.lowestRowBottom !== null && read.lowestRowBottom > navTop,
    '最低那一行仍然在导航条之上（行数按实测可视高度算）',
    `最低行底边=${read?.lowestRowBottom} 导航条上沿=${navTop} 可视高=${read?.visibleHeight}`)
  /**
   * 行**真的按序号排开了**：池化节点建出来都在 y=0，忘了摆就是所有行叠在同一处。
   * 这一条是补的 —— 原来的判据只问"最低行在不在导航条之上"，而叠在 y=0 反而更靠上，
   * 于是"表头说 4 件、屏幕上只看得见 1 件"这个缺陷能从判据底下走过去（见收口清单 #244）。
   */
  const ys = read?.rowYs ?? []
  const spacing = ROW_HEIGHT + ROW_GAP
  const spaced = ys.length >= 2 && ys.every((y, i) => i === 0 || Math.abs((ys[i - 1] - y) - spacing) < 0.5)
  verdict(spaced, '画出来的每一行按行高 + 行距真的排开了（不是叠在 y=0）',
    `各行的 y=${JSON.stringify(ys)}（期望间距 ${spacing}）`)

  /**
   * 盒子矮于 27 引擎就缩字——横扫量具的那 15 条商店基线全在这一族。
   * 只查"矮"这一件事，配一条"真的读到过 Label"的正向断言，否则空集合会假绿。
   */
  const geo = await page.evaluate(readGeometry)
  verdict(geo.labels > 0, '几何这一支真的读到了 Label（读到 0 颗说明遍历写错，判据会假绿）',
    `shop 子树下 ${geo.labels} 颗`)
  verdict(geo.tight.length === 0, '没有一颗字被盒子压小（盒高 < 27 即按比例缩字）',
    geo.tight.length === 0 ? `${geo.labels} 颗全部不低于 27` : `${geo.tight.length} 颗：${geo.tight.slice(0, 8).join('、')}`)

  const shot1 = path.join(SHOT_DIR, 'shop-gold.png')
  await page.screenshot({ path: shot1 })
  lines.push(`SHOT  ${shot1}`)

  // 切页签：赛季币 —— 请求要带 currency，页面上的币种名要跟着换
  const before = listCalls.length
  await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const panel = scene.getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('shop')
    for (const child of panel?.children ?? []) {
      if (child.name === 'Tab_SEASON_COIN') {
        child.emit('touch-start')
        return
      }
    }
  })
  await page.waitForTimeout(1200)
  const switched = await page.evaluate(readShop)
  verdict(listCalls.length > before
    && listCalls.slice(before).some(u => u.includes('currency=SEASON_COIN')),
    '切到赛季币真的换了账本（发的请求带 currency=SEASON_COIN）',
    `新增请求 ${listCalls.length - before} 条：${listCalls.slice(before).join(' ')}`)
  const seasonBalance = (switched.labels ?? []).find(text => text.startsWith('Balance:')) ?? ''
  verdict(/赛季币/.test(seasonBalance) || /赛季币/.test((switched.rows ?? []).join(' ')),
    '页面上的文字跟着换成了赛季币那一页',
    `余额行="${seasonBalance}" 首行="${(switched.rows ?? [])[0] ?? '—'}"`)

  const shot2 = path.join(SHOT_DIR, 'shop-season.png')
  await page.screenshot({ path: shot2 })
  lines.push(`SHOT  ${shot2}`)

  verdict(errors.length === 0, '全程零页面异常',
    `errors=${errors.length}${errors.length > 0 ? ' → ' + errors[0] : ''}`)

  await browser.close()
  await preview.close()

  console.log('\n[shop] 判定：')
  for (const line of lines) console.log(`  ${line}`)
  if (failures.length > 0) {
    console.error(`\n[shop] ${failures.length} 条判据失败：${failures.join('；')}`)
    process.exit(1)
  }
  console.log('\n[shop] 全部判据通过。截图见上面那两行 SHOT。')
}

main().catch((error) => {
  console.error('[shop] 探针自身崩了（不是判据失败）:', error)
  process.exit(2)
})
