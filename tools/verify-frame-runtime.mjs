#!/usr/bin/env node
/**
 * 职责：把「外观面板画得出来、未拥有的框不能戴、戴上之后预览跟着变」变成能失败的判据
 * （B24 块③ 头像框的完成判据）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 FRAME_PORT（默认 8099，同机并发时换一个）
 *   BACKEND_ORIGIN=http://localhost:8075 node tools/verify-frame-runtime.mjs
 *
 * <p><b>为什么用 web 产物证</b>：`AvatarFramePanelView` 在两个平台上都是同一份代码
 * （小游戏那边没有可编程点击通道）。判据读的是场景图（Cocos 画在 canvas 上，DOM 里一个字都没有）。
 *
 * <p><b>它盯的四件事</b>：① 面板挂上了、深链切得过去（导航第 15 项「外观」）；
 * ② 两枚框都列出来（含**没拥有**的那枚 —— 看不见的东西不会被收集）；
 * ③ **未拥有的行没有按钮**（客户端没有「在哪买」这一位，给它一颗按钮就是造一个假指向，
 * 那正是 B24 验收 5 在修的毛病）；
 * ④ 注入一份生产形状的视图之后，「佩戴中」与「卸下」跟着变，而且点它**不会**发出一份
 * 注定被拒的请求（本地那道防线：服务端说没拥有就不发）。
 *
 * <p><b>不验的</b>：真的买一枚再戴上（要 500 赛季币，赛季币只由赛季结算发放 —— 新号没有）。
 * 「兑换 → 拥有 → 戴得上 → 卸下仍拥有」由 `ShopEndpointTest` + `AvatarFrameEndpointTest`
 * （服务端）与 `AvatarFramePanel.test.ts` / `AppRoot.test.ts`（纯逻辑与编排）覆盖。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = path.resolve('client/build/web-mobile')
// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，读数错得像产品缺陷
// （2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错 —— 台账 #371/#372）。
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-frame-runtime] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.FRAME_PORT ?? 8099)
const SHOT_DIR = path.resolve('client/build/frame-verify')
/** 屏幕底部要给导航条让出的高度（与面板里的常量同源：8 + 52 + 8）。 */
const BOTTOM_RESERVED = 68
/** 行高与行距（与 `AvatarFramePanelView` 的常量同源）。用来判"行是不是真的按序号排开了"。 */
const ROW_HEIGHT = 58
const ROW_GAP = 5
/** 服务端 avatar_frame 表里的两枚。名字取自表，探针只拿来对账（不猜）。 */
const EXPECTED_FRAMES = ['赛季征战框', '拓荒者框']

/** 注入用的生产形状视图：一枚戴着、一枚没拥有（与 `buildAvatarFramePanel` 的产出一字不差）。 */
const SAMPLE_VIEW = {
  rows: [
    { frameId: 'frame_season_s1', name: '赛季征战框', rarityText: 'SR', color: '#C8A24B',
      owned: true, worn: true, stateText: '佩戴中', actionText: '卸下', action: 'unwear' },
    { frameId: 'frame_founder_n', name: '拓荒者框', rarityText: 'N', color: '#8C7A5B',
      owned: false, worn: false, stateText: '未拥有', actionText: null, action: null },
  ],
  wornFrameId: 'frame_season_s1',
  wornText: '佩戴中：赛季征战框',
  previewColor: '#C8A24B',
  initialText: '无',
  ownedCountText: '已拥有 1 / 2',
  emptyText: null,
  noticeText: null,
  notice: null,
}

const failures = []
const lines = []
function verdict(ok, label, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}  ${detail}`)
  if (!ok) {
    failures.push(label)
  }
}

/** 读外观那一格：标题/计数/佩戴行/提示行/头像首字/预览框颜色/每一行的文字与按钮。 */
function readFrames() {
  const out = { found: false, active: false, currentKey: null, labels: [], rows: [], buttons: [],
    rowYs: [], lowestRowBottom: null, visibleHeight: null, frameStrokeColor: null, navLabels: [] }
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  const nav = game?.getComponent('PanelNav')
  out.currentKey = nav?.currentKey ?? null
  const size = window.cc.view.getVisibleSize()
  out.visibleHeight = size.height
  const panel = game?.getChildByName('avatarFrames')
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
    if (child.name === 'FrameRow') {
      const texts = []
      let buttonCaption = null
      let buttonActive = false
      for (const grand of child.children) {
        const label = textOf(grand)
        if (label !== null) texts.push(label)
        if (grand.name === 'WearButton') {
          buttonActive = grand.activeInHierarchy !== false
          for (const inner of grand.children) {
            const caption = textOf(inner)
            if (caption !== null) buttonCaption = caption
          }
        }
      }
      out.rows.push(texts.join(' / '))
      out.buttons.push(buttonActive ? (buttonCaption ?? '（无文案）') : null)
      out.rowYs.push(child.position.y)
      const bottom = child.position.y - 29
      if (out.lowestRowBottom === null || bottom < out.lowestRowBottom) out.lowestRowBottom = bottom
      continue
    }
    if (child.name === 'FrameOverlay') {
      const graphics = child.getComponent && child.getComponent('cc.Graphics')
      const color = graphics?.strokeColor
      out.frameStrokeColor = color === undefined || color === null
        ? null
        : `#${[color.r, color.g, color.b].map((v) => v.toString(16).padStart(2, '0')).join('')}`.toUpperCase()
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
  const frameCalls = []
  page.on('pageerror', error => errors.push(String(error)))
  page.on('request', (req) => {
    if (req.url().includes('/player/frame')) {
      frameCalls.push(`${req.method()} ${req.url()} ${req.postData() ?? ''}`)
    }
  })

  let boot = null
  page.on('console', (msg) => {
    const text = msg.text()
    if (text.startsWith('[boot] ')) {
      try { boot = JSON.parse(text.slice('[boot] '.length)) } catch { /* 非结构化那条不算数 */ }
    }
  })

  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', 'avatarFrames')
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
    read = await page.evaluate(readFrames)
    if (read.currentKey === 'avatarFrames' && read.rows.length > 0) {
      break
    }
  }

  verdict(read?.found === true && read?.currentKey === 'avatarFrames',
    '深链 ?panel=avatarFrames 生效（导航第 15 项「外观」挂上了）',
    `found=${read?.found} 当前格=${read?.currentKey ?? '—'}`)
  const header = (read?.labels ?? []).find(text => text.startsWith('Header:')) ?? ''
  const count = (read?.labels ?? []).find(text => text.startsWith('Count:')) ?? ''
  verdict(/外观 · \d+\/\d+ 枚/.test(header), '表头说清了这一页有多少枚', `"${header}"`)
  verdict(count === 'Count:已拥有 0 / 2', '新号：两枚都没拥有，计数如实', `"${count}"`)
  const names = (read?.rows ?? []).map(row => row.split(' / ')[0])
  verdict(EXPECTED_FRAMES.every(name => names.includes(name)),
    '两枚框都列出来了（含没拥有的那枚 —— 看不见的东西不会被收集）',
    `行=${JSON.stringify(names)}`)
  verdict((read?.rows ?? []).every(row => / · 外观$/.test(row.split(' / ')[1] ?? '')),
    '每一行都带稀有度且标明是外观（不是道具）',
    (read?.rows ?? []).join(' | ') || '—')
  verdict((read?.rows ?? []).every(row => row.includes('未拥有')),
    '新号的两枚都是「未拥有」', (read?.rows ?? []).join(' | ') || '—')
  verdict((read?.buttons ?? []).every(button => button === null),
    '未拥有的行一个按钮都没有（不给一颗点了必然报错的键）',
    `按钮=${JSON.stringify(read?.buttons)}`)
  const initial = (read?.labels ?? []).find(text => text.startsWith('Initial:')) ?? ''
  verdict(initial === 'Initial:无', '预览头像是昵称首字（默认昵称「无名君主」）', `"${initial}"`)
  const emptyLine = (read?.labels ?? []).find(text => text.startsWith('Notice:')) ?? ''
  verdict(/还没有拿到任何头像框/.test(emptyLine), '一枚都没有时给出那句引导', `"${emptyLine}"`)

  const navTop = -(read?.visibleHeight ?? 640) / 2 + BOTTOM_RESERVED
  verdict(read?.lowestRowBottom !== null && read.lowestRowBottom > navTop,
    '最低那一行仍然在导航条之上（行数按实测可视高度算）',
    `最低行底边=${read?.lowestRowBottom} 导航条上沿=${navTop} 可视高=${read?.visibleHeight}`)
  /**
   * 行**真的按序号排开了**：池化节点建出来都在 y=0，忘了摆就是所有行叠在同一处 ——
   * 表现是"表头说 2 枚、屏幕上只看得见 1 枚"，而"最低行在导航条之上"这条判据照样绿
   * （叠在 0 处反而离导航条更远）。所以这里逐行读 y，要求严格递减且间距等于行高 + 行距。
   */
  const ys = read?.rowYs ?? []
  const spacing = ROW_HEIGHT + ROW_GAP
  const spaced = ys.length >= 2 && ys.every((y, i) => i === 0 || Math.abs((ys[i - 1] - y) - spacing) < 0.5)
  verdict(spaced, '两行按行高 + 行距真的排开了（不是叠在 y=0）',
    `各行的 y=${JSON.stringify(ys)}（期望间距 ${spacing}）`)

  const shot1 = path.join(SHOT_DIR, 'frame-empty.png')
  await page.screenshot({ path: shot1 })
  lines.push(`SHOT  ${shot1}`)

  // ② 注入一份生产形状的视图：一枚戴着、一枚没拥有
  await page.evaluate((view) => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.getChildByName('avatarFrames')
    const component = panel?.getComponent('AvatarFramePanelView')
    if (component === undefined || component === null) {
      throw new Error('拿不到 AvatarFramePanelView（场景装配变了？）')
    }
    component.attach(view)
  }, SAMPLE_VIEW)
  await page.waitForTimeout(400)
  const worn = await page.evaluate(readFrames)
  const wornLine = (worn.labels ?? []).find(text => text.startsWith('Worn:')) ?? ''
  verdict(wornLine === 'Worn:佩戴中：赛季征战框', '戴上的那一枚在预览里说了出来', `"${wornLine}"`)
  verdict((worn.buttons ?? [])[0] === '卸下', '戴着的那一行按钮变成「卸下」',
    `按钮=${JSON.stringify(worn.buttons)}`)
  verdict((worn.buttons ?? [])[1] === null, '没拥有的那一枚**依然**没有按钮（注入也改不了这条）',
    `按钮=${JSON.stringify(worn.buttons)}`)
  verdict(worn.frameStrokeColor === '#C8A24B',
    '预览框的颜色 = 表里 placeholderColor 那一列（#C8A24B）',
    `strokeColor=${worn.frameStrokeColor}`)

  const shot2 = path.join(SHOT_DIR, 'frame-worn.png')
  await page.screenshot({ path: shot2 })
  lines.push(`SHOT  ${shot2}`)

  // ③ 点「卸下」：本地点得到，但服务端说新号没拥有 —— 编排层先挡下，不发那份注定被拒的请求
  const before = frameCalls.length
  await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.getChildByName('avatarFrames')
    for (const child of panel?.children ?? []) {
      if (child.name === 'FrameRow') {
        for (const grand of child.children) {
          if (grand.name === 'WearButton' && grand.activeInHierarchy !== false) {
            grand.emit('touch-start')
            return
          }
        }
      }
    }
  })
  await page.waitForTimeout(800)
  verdict(frameCalls.length === before,
    '点一个"本地点得到、服务端说没拥有"的行：不进网络（本地那道防线在）',
    `新增 /player/frame 请求 ${frameCalls.length - before} 条${frameCalls.length > 0 ? ' → ' + frameCalls[0] : ''}`)

  verdict(errors.length === 0, '全程零页面异常',
    `errors=${errors.length}${errors.length > 0 ? ' → ' + errors[0] : ''}`)

  await browser.close()
  await preview.close()

  console.log('\n[frame] 判定：')
  for (const line of lines) console.log(`  ${line}`)
  if (failures.length > 0) {
    console.error(`\n[frame] ${failures.length} 条判据失败：${failures.join('；')}`)
    process.exit(1)
  }
  console.log('\n[frame] 全部判据通过。截图见上面那两行 SHOT。')
}

main().catch((error) => {
  console.error('[frame] 探针自身崩了（不是判据失败）:', error)
  process.exit(2)
})
