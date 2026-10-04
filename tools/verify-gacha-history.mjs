#!/usr/bin/env node
/**
 * 职责：把「抽卡记录查得到」钉成一条能失败的运行时判据（V10；B15 §三 合规三件套的第三件）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199（不给就退 2 并点名这个变量：静默回落到别的后端
 *   会把读数错得像产品缺陷 —— 台账 #371/#372）；端口 GH_PORT（默认 8217）
 *   BACKEND_ORIGIN=http://localhost:8199 node tools/verify-gacha-history.mjs
 * 第二段（真抽一枪再看记录）：GH_DRAW=1 —— 需要这个号的金币够抽一次，不够时探针**如实记
 *   「未执行」而不是假装通过。
 *
 * <p><b>为什么这一相只能运行时量</b>：`buildGachaHistory` 有免引擎单测（`client/tests/GachaHistory.test.ts`），
 * 但"那颗键点得动、点了之后那一屏真的挂上去、关得掉"过的是另一条路：
 * `RecruitPanelView` 的按钮**灰掉时不吃触摸**，`GachaHistoryView` 建出来是 `active = false`，
 * 编排层要先把节点点亮再 attach。把其中任何一环写漏，964 项单测仍然全绿，而屏上永远没有这一页。
 *
 * <p><b>读的是场景图不是 DOM</b>：Cocos 把面板画在 canvas 上，DOM 里一个字都没有；
 * 组件只按注册名 `getComponent('cc.Label')` 取（release 产物会压缩类名，`constructor.name` 不可靠）。
 */
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { clickTabNode } from './lib/panel-clicks.mjs'
import path from 'node:path'

const ROOT = path.resolve('client/build/web-mobile')
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-gacha-history] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.GH_PORT ?? 8217)
const WITH_DRAW = process.env.GH_DRAW === '1'

/** 在页面里读记录屏：节点在不在、亮没亮、屏上写了什么。 */
function readHistoryPanel() {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('gachaHistory')
  if (!panel) return { missing: true, active: false, texts: [] }
  const texts = []
  const walk = (n) => {
    const lab = n.getComponent('cc.Label')
    if (lab && n.activeInHierarchy && String(lab.string ?? '').trim() !== '') texts.push(lab.string)
    for (const c of n.children) walk(c)
  }
  walk(panel)
  return { missing: false, active: panel.activeInHierarchy, texts }
}

/** 在页面里读招募面板：那颗「抽取记录」的键在不在。 */
function readRecruitKeys() {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('gacha')
  if (!panel) return { missing: true, keys: [] }
  const keys = []
  const walk = (n) => {
    if (['history', 'drawOnce', 'drawTen', 'probability'].includes(n.name)) keys.push(n.name)
    for (const c of n.children) walk(c)
  }
  walk(panel)
  return { missing: false, keys }
}

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v),
  `gacha-history-${Date.now()}`)
const page = await context.newPage()
await page.goto(`${preview.origin}/?panel=gacha`, { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
  null, { timeout: 60_000 })
await page.waitForTimeout(4500)
await hideGuideOverlay(page)

const failures = []
const keys = await page.evaluate(readRecruitKeys)
console.log(`招募面板：${keys.missing ? '找不到（深链没进去）' : `键 ${keys.keys.join(' / ')}`}`)
if (keys.missing || !keys.keys.includes('history')) {
  failures.push(`招募面板上没有「抽取记录」那颗键（读到：${keys.keys.join(' / ') || '空'}）`)
}

// 对照组：还没点之前，记录屏必须是关着的 —— 没有这一条，"屏上有记录"可能来自别的地方
const before = await page.evaluate(readHistoryPanel)
console.log(`点之前的记录屏：active=${before.active} 文字数=${before.texts.length}`)
if (before.missing) failures.push('场景里没有 gachaHistory 节点（面板没挂上）')
if (before.active) failures.push('还没点「抽取记录」，记录屏就已经亮着（对照组失败）')
const shotClosed = path.resolve('tmp/gacha-history/closed.png')
await page.screenshot({ path: shotClosed })

const clicked = await page.evaluate(clickTabNode, 'history')
await page.waitForTimeout(1500)
const opened = await page.evaluate(readHistoryPanel)
console.log(`点击=${clicked}；打开后 active=${opened.active}`)
console.log(`屏上文字：${opened.texts.join(' ｜ ')}`)
const shotOpen = path.resolve('tmp/gacha-history/empty.png')
await page.screenshot({ path: shotOpen })

if (clicked !== true) failures.push('那颗「抽取记录」的键没点到（emit 没命中）')
if (!opened.active) failures.push('点了「抽取记录」之后记录屏没有亮起来')
const joined = opened.texts.join(' ｜ ')
if (!joined.includes('抽取记录')) failures.push(`记录屏上没有标题「抽取记录」（实际：${joined}）`)
if (!/记录保留 \d+ 天/.test(joined)) failures.push(`记录屏上没有保留天数（实际：${joined}）`)
if (!joined.includes('还没有抽取记录')) {
  failures.push(`新号应该是空态「还没有抽取记录」，实际：${joined}`)
}
// 不许把内部 id 印给玩家（#422 那一族：屏上不出现裸 id）
if (/pool_|hero_/.test(joined)) failures.push(`记录屏上出现了裸 id：${joined}`)

// 第二段（可选）：真抽一枪，再看记录里有没有那一枪
let drawNote = '未执行（GH_DRAW != 1）'
if (WITH_DRAW) {
  const drew = await page.evaluate(clickTabNode, 'drawOnce')
  await page.waitForTimeout(2500)
  await page.evaluate(clickTabNode, 'history')
  await page.waitForTimeout(1500)
  const after = await page.evaluate(readHistoryPanel)
  const afterText = after.texts.join(' ｜ ')
  const shotDrawn = path.resolve('tmp/gacha-history/after-draw.png')
  await page.screenshot({ path: shotDrawn })
  if (!drew) {
    drawNote = '未执行（那颗「抽一次」的键当时点不动，多半是余额不够）'
  } else if (afterText.includes('还没有抽取记录')) {
    drawNote = '执行了但记录仍为空'
    failures.push(`抽了一枪之后记录页仍是空态：${afterText}`)
  } else if (!afterText.includes('刚刚')) {
    drawNote = '执行了但首行不是「刚刚」'
    failures.push(`抽完那一枪的首行应当是「刚刚」，实际：${afterText}`)
  } else {
    drawNote = `已执行：${afterText}`
  }
  console.log(`第二段：${drawNote}`)
  console.log(`SHOT  ${shotDrawn}`)
}

// 关闭：这一屏是模态的，关不掉就等于把玩家锁在记录页里
const closedClick = await page.evaluate(clickTabNode, 'CloseButton')
await page.waitForTimeout(800)
const afterClose = await page.evaluate(readHistoryPanel)
console.log(`点关闭=${closedClick}；关闭后 active=${afterClose.active}`)
if (closedClick !== true || afterClose.active) {
  failures.push(`「关闭」没把关掉的屏收起来（点击=${closedClick}，active=${afterClose.active}）`)
}

await browser.close()
await preview.close()

console.log(`SHOT  ${shotClosed}`)
console.log(`SHOT  ${shotOpen}`)
console.log(`第二段读数：${drawNote}`)
if (failures.length > 0) {
  console.error(`[verify-gacha-history] 失败 ${failures.length} 条：`)
  for (const line of failures) console.error(`  - ${line}`)
  process.exit(1)
}
console.log('[verify-gacha-history] 抽卡记录查得到：入口在、点了能开、空态有话说、关得掉')
