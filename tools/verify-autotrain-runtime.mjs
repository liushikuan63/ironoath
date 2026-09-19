#!/usr/bin/env node
/**
 * 职责：把「军队面板上的自动续训开关真的画出来了、而且点下去的行为是对的」变成一条能失败的判据。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 *   BACKEND_ORIGIN=http://localhost:8075 node tools/verify-autotrain-runtime.mjs
 *
 * <p><b>为什么用 web 产物证</b>：`ArmyPanelView` 与 `game/army/AutoTrain.ts` 在两个平台上是同一份代码
 * （小游戏那边没有可编程点击通道），所以「这一格画没画出东西、点一下有没有发请求」在浏览器里量一次
 * 与在模拟器里量一次得到的是同一个答案。
 *
 * <p><b>判据读的是场景图而不是 DOM</b>：Cocos 把面板画在 canvas 上，DOM 里一个字都没有。
 * 所以这里按节点名找 `AutoTrainButton` / `AutoTrainStatus`，读它们的 Label 文本与坐标 ——
 * 「文本非空」证的是"画出来了"，而坐标那两条证的是"没被导航条盖住/没压到兵种行"。
 *
 * <p><b>本探针只用新号能到达的状态</b>：新号没有兵营也没有兵 ⇒ 策略关着、也没有"上一次训练"，
 * 所以状态行应当是那句「先手动训一批…」，点一下**不该发请求**（没有可续的那一批）。
 * 「开着时」的样子（「重步兵 ×50 · 还剩 2 批」）今天只能由纯逻辑用例与类型检查保证，
 * 在真服上让它出现要么得建到兵营 1 级（真实时长）、要么得开作弊端点 —— 两条都被本仓库禁止。
 * 这条边界写在收口清单那行里，不假装它验过。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = path.resolve('client/build/web-mobile')
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.AUTOTRAIN_PORT ?? 8096)
const SHOT_DIR = path.resolve('client/build/autotrain-verify')
/**
 * 导航条上沿 = 下边距 8 + `PanelNav.BAR_HEIGHT` 52，换算到屏幕坐标就是 `-h/2 + 60`。
 * **按实测的可视高度算**，不写一个数：写死的那个数在别的窗口尺寸下会变成一条松判据
 * （实测 1280×720 下可视高度是 540，导航条上沿 -210，而不是设计高度 640 对应的 -260）。
 */
function navTopOf(visibleHeight) {
  return -visibleHeight / 2 + 8 + 52
}

const failures = []
const lines = []
function verdict(ok, label, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}  ${detail}`)
  if (!ok) {
    failures.push(label)
  }
}

/** 在页面里读军队那一格的自动续训两件套：按钮字幕、状态行文本与坐标、最后一行兵种行的底边。 */
function readAutoTrain() {
  const out = { panelFound: false, button: null, caption: null, status: null, rowBottom: null,
    currentKey: null, visible: null }
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  out.currentKey = game?.getComponent('PanelNav')?.currentKey ?? null
  const size = window.cc.view.getVisibleSize()
  out.visible = { width: size.width, height: size.height }
  const panel = game?.getChildByName('army')
  if (panel === undefined || panel === null) {
    return out
  }
  out.panelFound = true
  const labelOf = (node) => {
    const own = node.getComponent('cc.Label')
    if (own !== null && own !== undefined) {
      return { text: own.string, x: node.position.x, y: node.position.y }
    }
    for (const child of node.children) {
      const label = child.getComponent('cc.Label')
      if (label !== null && label !== undefined) {
        return { text: label.string, x: child.position.x, y: child.position.y }
      }
    }
    return null
  }
  for (const child of panel.children) {
    if (child.name === 'AutoTrainButton') {
      out.button = { active: child.activeInHierarchy !== false, y: child.position.y,
        caption: labelOf(child) }
    }
    if (child.name === 'AutoTrainStatus') {
      const own = child.getComponent('cc.Label')
      out.status = { active: child.activeInHierarchy !== false,
        text: own === null || own === undefined ? null : own.string, y: child.position.y }
    }
  }
  // 最后一行（最靠下的那一行激活的兵种行）的底边：ROW_HEIGHT 62 ⇒ 半高 31
  let lowest = null
  for (const child of panel.children) {
    if (child.name !== 'UnitRow' || child.activeInHierarchy === false) continue
    const bottom = child.position.y - 31
    if (lowest === null || bottom < lowest) lowest = bottom
  }
  out.rowBottom = lowest
  const header = panel.children.find(child => child.name === 'Header')
  const headerLabel = header === null || header === undefined ? null : header.getComponent('cc.Label')
  out.headerText = headerLabel === null || headerLabel === undefined ? null : headerLabel.string
  return out
}

async function main() {
  mkdirSync(SHOT_DIR, { recursive: true })
  const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  const page = await context.newPage()

  const consoleLines = []
  const autoTrainCalls = []
  page.on('console', (msg) => consoleLines.push(msg.text()))
  page.on('request', (req) => {
    if (req.url().includes('/army/autoTrain')) {
      autoTrainCalls.push(req.url())
    }
  })

  let boot = null
  page.on('console', (msg) => {
    const text = msg.text()
    if (text.startsWith('[boot] ')) {
      try {
        boot = JSON.parse(text.slice('[boot] '.length))
      } catch {
        // 非结构化那一条：[boot] 是自检回执的专用前缀，解析不了就不算数
      }
    }
  })

  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', 'army')
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  const bootDeadline = Date.now() + 45_000
  while (boot === null && Date.now() < bootDeadline) {
    await page.waitForTimeout(500)
  }
  preview.assertRewritten()
  if (boot === null) {
    await browser.close()
    await preview.close()
    console.error('\n=== 判定中止：没捕获到 [boot] 自检行，后面每格读数都会是假的 ===')
    process.exit(1)
  }
  verdict(boot.started === true, '启动跑通（started=true）',
    `platform=${boot.platform} bootMs=${boot.bootMs}`)

  // 拿到第一份非空读数：登录与首屏预拉之后再等一会儿
  let read = null
  for (let i = 0; i < 40; i += 1) {
    await page.waitForTimeout(500)
    read = await page.evaluate(readAutoTrain)
    if (read.currentKey === 'army' && read.button !== null && read.status !== null) {
      break
    }
  }

  verdict(read?.panelFound === true && read?.currentKey === 'army',
    '深链 ?panel=army 生效（否则后面量的是别的格子）',
    `panelFound=${read?.panelFound} 当前格=${read?.currentKey ?? '—'}`)
  verdict(read?.button !== null && read?.button?.active === true,
    '「自动续训」按钮画出来了且处于激活态',
    `button=${JSON.stringify(read?.button)}`)
  const caption = read?.button?.caption?.text ?? ''
  verdict(caption === '自动续训' || caption === '停止自动',
    '按钮字幕是人话（新号应当是「自动续训」）', `看到 "${caption}"`)
  const statusText = String(read?.status?.text ?? '')
  verdict(read?.status !== null && statusText !== '',
    '状态行画出来了（关着的时候也要说清"现在能不能开"）', `"${statusText}"`)
  verdict(/先手动训一批/.test(statusText),
    '新号那行说的是「先手动训一批…」—— 没有可续的那一批时明确说清，而不是一个点了没反应的按钮',
    `"${statusText}"`)

  // 位置：按钮与状态行都在导航条之上；状态行在按钮之下、兵种行之上
  const buttonY = read?.button?.y ?? 0
  const statusY = read?.status?.y ?? 0
  const navTop = navTopOf(read?.visible?.height ?? 640)
  verdict(buttonY > navTop && statusY > navTop,
    '两个节点都在导航条之上（没被底部导航盖住）',
    `button.y=${buttonY} status.y=${statusY} 可视高=${read?.visible?.height} 导航条上沿=${navTop}`)
  verdict(statusY < buttonY,
    '状态行在按钮下方（不是压在一起）', `status.y=${statusY} < button.y=${buttonY}`)
  verdict(read?.rowBottom !== null && read.rowBottom > navTop,
    '最后一行兵种行的底边也在导航条之上（行数按可视高度算，不是写死的）',
    `最低行底边=${read?.rowBottom} 导航条上沿=${navTop}`)
  verdict(/另有 \d+ 项未显示/.test(String(read?.headerText ?? '')),
    '画不下的行数说出来了（全兵种页 20 行只画得下 4 行，玩家得知道下面还有）',
    `表头="${read?.headerText}"`)

  // 点一下：没有可续的那一批 ⇒ 不发请求，而且要在 console 里说清原因（面板那行字已经说了）
  const before = autoTrainCalls.length
  const clicked = await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const panel = scene.getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('army')
    for (const child of panel?.children ?? []) {
      if (child.name === 'AutoTrainButton') {
        child.emit('touch-start')
        return true
      }
    }
    return false
  })
  await page.waitForTimeout(800)
  verdict(clicked === true, '按钮点得到（节点上注册的就是 touch-start 处理函数）', `clicked=${clicked}`)
  verdict(autoTrainCalls.length === before,
    '没有"可续的那一批"时点一下不发请求（发出去也只会被服务端拒，而玩家该先看到原因）',
    `/army/autoTrain 请求数 ${before} → ${autoTrainCalls.length}`)
  verdict(consoleLines.some(line => line.includes('[army]') && line.includes('先手动训一批')),
    '被挡下时 console 里也留了同一条原因（排障时看得见）',
    `匹配 ${consoleLines.filter(l => l.includes('[army]')).length} 条 [army] 日志`)

  const shot = path.join(SHOT_DIR, 'army-autotrain.png')
  await page.screenshot({ path: shot })
  lines.push(`SHOT  ${shot}`)

  await browser.close()
  await preview.close()

  console.log('\n[autotrain] 判定：')
  for (const line of lines) console.log(`  ${line}`)
  if (failures.length > 0) {
    console.error(`\n[autotrain] ${failures.length} 条判据失败：${failures.join('；')}`)
    process.exit(1)
  }
  console.log('\n[autotrain] 全部判据通过。截图见上面那行 SHOT。')
}

main().catch((error) => {
  console.error('[autotrain] 探针自身崩了（不是判据失败）:', error)
  process.exit(2)
})
