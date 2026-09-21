#!/usr/bin/env node
/**
 * 职责：把「战令面板画得出来、两条线各点各的、领不了的那颗是灰的且说了原因」变成能失败的判据
 * （B24 S-d-f）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 *   BACKEND_ORIGIN=http://localhost:8155 node tools/verify-battlepass-runtime.mjs
 *
 * <p><b>为什么用 web 产物证</b>：`BattlePassPanelView` 在两个平台上都是同一份代码
 * （小游戏那边没有可编程点击通道）。判据读的是场景图（Cocos 画在 canvas 上，DOM 里一个字都没有）。
 *
 * <p><b>它盯的四件事</b>：① 面板挂上了、深链切得过去（导航第 16 项「战令」）；
 * ② 表头把赛季、积分、已领份数与剩余时间都说出来（不是四个数字，是四句话）；
 * ③ 新号（0 分、没买战令）下**两颗按钮都是灰的**，且页面把"付费线为什么是灰的"说出来；
 * ④ 行真的按序号排开了（池化行没摆位置是商店面板踩过的坑，见收口台账 #244），
 * 且最低一行在导航条之上。
 *
 * <p><b>不验的</b>：真的领一份（新号 0 分，一档都没达成 ⇒ 领不动）。「领一次 → 拿到东西 → 再领被拒」
 * 由 `BattlePassEndpointTest`（服务端，7 条）与 `BattlePassPanel.test.ts`（纯逻辑，10 条）覆盖。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = path.resolve('client/build/web-mobile')
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.BATTLEPASS_PORT ?? 8100)
const SHOT_DIR = path.resolve('client/build/battlepass-verify')
/** 屏幕底部要给导航条让出的高度（与面板里的常量同源：8 + 52 + 8）。 */
const BOTTOM_RESERVED = 68
/** 行高与行距（与 `BattlePassPanelView` 的常量同源）。用来判"行是不是真的按序号排开了"。 */
const ROW_HEIGHT = 66
const ROW_GAP = 6

const failures = []
const lines = []
function verdict(ok, label, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}  ${detail}`)
  if (!ok) {
    failures.push(label)
  }
}

/** 读战令那一格：表头四行、每一行的文字与两颗按钮的字幕、行位置、当前导航格。 */
function readBattlePass() {
  // evaluate 只带函数源码（页面里看不到模块作用域）—— 行高必须在函数内再声明一次，
  // 否则整条读数是 ReferenceError（这一族在 B22 的探针上踩过）
  const ROW_H = 66
  const out = { found: false, active: false, currentKey: null, labels: [], rows: [], rowYs: [],
    buttons: [], lowestRowBottom: null, visibleHeight: null, navLabels: [] }
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  out.currentKey = game?.getComponent('PanelNav')?.currentKey ?? null
  const size = window.cc.view.getVisibleSize()
  out.visibleHeight = size.height
  const panel = game?.getChildByName('battlePass')
  if (panel === undefined || panel === null) {
    return out
  }
  out.found = true
  out.active = panel.activeInHierarchy !== false
  const textOf = (node) => {
    const own = node.getComponent && node.getComponent('cc.Label')
    return own !== null && own !== undefined && own.string !== '' ? own.string : null
  }
  for (const child of panel.children) {
    if (child.activeInHierarchy === false) continue
    if (child.name === 'PassRow') {
      const texts = []
      const buttons = []
      for (const grand of child.children) {
        const label = textOf(grand)
        if (label !== null) texts.push(label)
        if (grand.name === 'Claim_FREE' || grand.name === 'Claim_PAID') {
          let caption = null
          for (const inner of grand.children) {
            const inner2 = textOf(inner)
            if (inner2 !== null) caption = inner2
          }
          const graphics = grand.getComponent && grand.getComponent('cc.Graphics')
          const fill = graphics?.fillColor
          buttons.push({
            track: grand.name.slice('Claim_'.length),
            caption: caption ?? '（无文案）',
            color: fill === undefined || fill === null
              ? null
              : `#${[fill.r, fill.g, fill.b].map((v) => v.toString(16).padStart(2, '0')).join('')}`.toUpperCase(),
          })
        }
      }
      out.rows.push(texts.join(' / '))
      out.buttons.push(buttons.map((b) => `${b.track}:${b.caption}${b.color}`).join(','))
      out.rowYs.push(child.position.y)
      const bottom = child.position.y - ROW_H / 2
      if (out.lowestRowBottom === null || bottom < out.lowestRowBottom) out.lowestRowBottom = bottom
      continue
    }
    const text = textOf(child)
    if (text !== null) out.labels.push(`${child.name}:${text}`)
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
  const statusCalls = []
  page.on('pageerror', error => errors.push(String(error)))
  page.on('request', (req) => {
    if (req.url().includes('/battlePass/')) statusCalls.push(req.url())
  })

  let boot = null
  page.on('console', (msg) => {
    const text = msg.text()
    if (text.startsWith('[boot] ')) {
      try { boot = JSON.parse(text.slice('[boot] '.length)) } catch { /* 非结构化那条不算数 */ }
    }
  })

  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', 'battlePass')
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
  verdict(boot.started === true, '启动跑通（started=true）',
    `platform=${boot.platform} bootMs=${boot.bootMs}`)
  verdict(boot.missingPanels === '', '装配自检说没有缺件（新面板挂上了）',
    `attemptedPanels=${boot.attemptedPanels} mountedPanels=${boot.mountedPanels} missing="${boot.missingPanels}"`)

  let read = null
  for (let i = 0; i < 40; i += 1) {
    await page.waitForTimeout(500)
    read = await page.evaluate(readBattlePass)
    if (read.currentKey === 'battlePass' && read.rows.length > 0) {
      break
    }
  }

  verdict(read?.found === true && read?.currentKey === 'battlePass',
    '深链 ?panel=battlePass 生效（导航第 16 项「战令」挂上了）',
    `found=${read?.found} 当前格=${read?.currentKey ?? '—'}`)
  verdict(statusCalls.some(u => u.includes('/battlePass/status')),
    '打开这一格真的拉了状态（不是画一个假进度）', `请求=${statusCalls.join(' ')}`)

  const header = (read?.labels ?? []).find(t => t.startsWith('Header:')) ?? ''
  verdict(/赛季战令 · 第 \d+–\d+ 档 \/ 共 20 档/.test(header),
    '表头说清了这一屏画的是哪一段（画不下 20 档时不许静默少画）', `"${header}"`)
  const points = (read?.labels ?? []).find(t => t.startsWith('Points:')) ?? ''
  verdict(/本赛季积分 \d+ \/ 3000/.test(points),
    '积分行带总目标（3000 = 20 档打满，与买断定价同源）', `"${points}"`)
  const claimed = (read?.labels ?? []).find(t => t.startsWith('Claimed:')) ?? ''
  verdict(/已领 \d+ \/ \d+ 份/.test(claimed), '已领份数说清了分子分母', `"${claimed}"`)
  const remain = (read?.labels ?? []).find(t => t.startsWith('Remain:')) ?? ''
  verdict(/本赛季还剩|赛季尚未启用|本赛季已结束/.test(remain),
    '剩余时间来自服务端两个时刻相减（铁律 5）', `"${remain}"`)
  const notice = (read?.labels ?? []).find(t => t.startsWith('Notice:')) ?? ''
  verdict(/付费线还没解锁/.test(notice),
    '新号没买战令：付费线为什么是灰的被说出来（一颗没有解释的灰按钮会被当成坏了）', `"${notice}"`)

  verdict((read?.rows ?? []).length > 0, '档位行画出来了',
    `行数=${read?.rows?.length}：${(read?.rows ?? [])[0] ?? '—'}`)
  verdict((read?.rows ?? []).every(row => /第 \d+ 档 · \d+ 分 \/ (已达成|还差 \d+ 分)/.test(row)),
    '每一行都写了档位、门槛分与达成状态',
    (read?.rows ?? []).join(' | ') || '—')
  verdict((read?.rows ?? []).every(row => /免费：.+ · (可领取|不可领取|已领取)/.test(row)
      && /付费：.+ · (可领取|不可领取|已领取)/.test(row)),
    '两条线各占一行且各自带状态（免费/付费不能合成一个「领取」）',
    (read?.rows ?? [])[0] ?? '—')

  /** 新号：0 分且没买战令 ⇒ 两颗按钮都该是灰的（灰 = 按钮底色为 COLOR_BUTTON_OFF #40372E）。 */
  const buttonText = (read?.buttons ?? []).join(' ')
  verdict(/FREE:领免费#40372E/.test(buttonText) && /PAID:领付费#40372E/.test(buttonText),
    '新号两颗按钮都是灰的（没达成 / 没买战令时不给一颗点了报错的亮按钮）',
    (read?.buttons ?? [])[0] ?? '—')

  const navTop = -(read?.visibleHeight ?? 640) / 2 + BOTTOM_RESERVED
  verdict(read?.lowestRowBottom !== null && read.lowestRowBottom > navTop,
    '最低那一行仍然在导航条之上（行数按实测可视高度算）',
    `最低行底边=${read?.lowestRowBottom} 导航条上沿=${navTop} 可视高=${read?.visibleHeight}`)
  const ys = read?.rowYs ?? []
  const spacing = ROW_HEIGHT + ROW_GAP
  const spaced = ys.length >= 2 && ys.every((y, i) => i === 0 || Math.abs((ys[i - 1] - y) - spacing) < 0.5)
  verdict(spaced, '画出来的每一行按行高 + 行距真的排开了（不是叠在 y=0）',
    `各行的 y=${JSON.stringify(ys)}（期望间距 ${spacing}）`)

  const shot = path.join(SHOT_DIR, 'battlepass.png')
  await page.screenshot({ path: shot })
  lines.push(`SHOT  ${shot}`)

  /** 点一下灰按钮：不该发请求（面板层就不把不可领的线画成可点）。 */
  const before = statusCalls.length
  await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.getChildByName('battlePass')
    for (const child of panel?.children ?? []) {
      if (child.name !== 'PassRow') continue
      for (const grand of child.children) {
        if (grand.name === 'Claim_FREE') {
          grand.emit('touch-start')
          return
        }
      }
    }
  })
  await page.waitForTimeout(800)
  verdict(!statusCalls.slice(before).some(u => u.includes('/battlePass/claim')),
    '点一颗灰的领取键：不发请求（不可领的线在面板层就不该可点）',
    `新增请求 ${statusCalls.length - before} 条：${statusCalls.slice(before).join(' ')}`)

  // ---------- 表头那五行：会不会互相叠（#363 在军队表头量出同一形状） ----------
  // `BattlePassPanelView` 第 107-112 行五行都是居中的固定 y（间距 24~28），
  // 却没有一行有 `setContentSize` —— 而 Label 会按文本把盒子撑高，撑到两行就必然叠。
  const HEAD5 = `(() => {
    const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.getChildByName('battlePass')
    if (!panel) return null
    const names = ['Header', 'Points', 'Claimed', 'Remain', 'Notice']
    const rows = []
    for (const name of names) {
      const node = panel.getChildByName(name)
      if (node === null || node === undefined) continue
      const w = node.getComponent('cc.UITransform').getBoundingBoxToWorld()
      rows.push({ name, text: node.getComponent('cc.Label')?.string ?? '',
        l: w.x, r: w.x + w.width, b: w.y, t: w.y + w.height, h: Math.round(w.height) })
    }
    const hits = []
    for (let a = 0; a < rows.length; a++) {
      for (let c = a + 1; c < rows.length; c++) {
        const x = rows[a], y = rows[c]
        if (x.l < y.r && y.l < x.r && x.b < y.t && y.b < x.t) hits.push(x.name + ' × ' + y.name)
      }
    }
    return { count: rows.length, hits,
      tooTall: rows.filter((x) => x.h > 30).map((x) => x.name + '=' + x.h),
      withText: rows.filter((x) => x.text.length > 0).length }
  })()`
  const head5 = await page.evaluate(HEAD5)
  verdict(head5 !== null && head5.count === 5 && head5.withText >= 3,
    '反空转前置：表头五行都在且至少三行有内容',
    `count=${head5?.count} withText=${head5?.withText}`)
  verdict(head5 !== null && head5.tooTall.length === 0,
    '表头每行的盒子都不超过一行高（>30 就是被文本撑开了，#363 同形）',
    `tooTall=${head5?.tooTall.join(',')}`)
  verdict(head5 !== null && head5.hits.length === 0,
    '表头五行两两不相交（间距只有 24~28px）',
    `hits=${head5?.hits.join(',')}`)

  verdict(errors.length === 0, '全程零页面异常',
    `errors=${errors.length}${errors.length > 0 ? ' → ' + errors[0] : ''}`)

  await browser.close()
  await preview.close()

  console.log('\n[battlePass] 判定：')
  for (const line of lines) console.log(`  ${line}`)
  if (failures.length > 0) {
    console.error(`\n[battlePass] ${failures.length} 条判据失败：${failures.join('；')}`)
    process.exit(1)
  }
  console.log('\n[battlePass] 全部判据通过。截图见上面那行 SHOT。')
}

main().catch((error) => {
  console.error('[battlePass] 探针自身崩了（不是判据失败）:', error)
  process.exit(2)
})
