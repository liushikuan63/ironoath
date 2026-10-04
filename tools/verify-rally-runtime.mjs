#!/usr/bin/env node
/**
 * 职责：把「集结在客户端真的看得见、点得动，且三条动作各走各的路」变成能失败的判据（V02-S1）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 RALLY_PORT（默认 8101，同机并发时换一个）
 *   BACKEND_ORIGIN=http://localhost:8156 node tools/verify-rally-runtime.mjs
 *
 * <p><b>为什么用 web 产物证</b>：`SocialPanelView` 在两端是同一份代码（小游戏没有可编程点击通道）。
 * 判据读场景图（Cocos 画在 canvas 上，DOM 里一个字都没有）。
 *
 * <p><b>它盯的五件事</b>：① 社交面板多了「集结」页签，切过去会真的拉 `/rally/list`；
 * ② 没有集结时**说清这一页本该有什么**（不是一片空白）；③ 注入生产形状的数据后，
 * 行的文字来自响应（目标/坐标/人数/兵力/倒计时），而**倒计时是服务端两个时刻相减**；
 * ④ 三种动作的按钮文案分得开：没参是「加入」、我参了是「退出」、我发起的是「取消集结」；
 * ⑤ 点「加入」**不发 join 请求**（`/rally/join` 必须带承诺兵力 ⇒ 先进编队面板，确认才发）。
 *
 * <p><b>不验的</b>：真的把兵压上去加入成功（要真队伍与真兵力，dev 新号两样都没有）——
 * 「点确认 → POST /rally/join → 兵力锁定」由 `AppRoot.test.ts`（3 条）与
 * `SocialAppService` 的既有服务端用例覆盖。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const ROOT = path.resolve('client/build/web-mobile')
// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，读数错得像产品缺陷
// （2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错 —— 台账 #371/#372）。
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-rally-runtime] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.RALLY_PORT ?? 8101)
const SHOT_DIR = path.resolve('client/build/rally-verify')
const SERVER_NOW = 1_700_000_000_000

/** 注入用的生产形状数据：一支"我没参"的、一支"我发起的"（两张行文案各验一次）。 */
const SAMPLE = {
  myPlayerId: 'P-me',
  notice: null,
  source: {
    serverNow: SERVER_NOW,
    rallies: [
      {
        rallyId: 'r-join', scope: 'SQUAD', groupId: 'sq-1', initiatorId: 'P-leader',
        targetCoord: { x: 433, y: 95 }, targetType: 'MONSTER', maxMembers: 10,
        joinedCount: 3, totalTroops: 12400, prepareUntil: SERVER_NOW + 155_000,
        departAt: SERVER_NOW + 155_000, status: 'PREPARING',
        members: ['P-leader', 'P-2', 'P-3'], heroSlots: [], serverNow: SERVER_NOW,
      },
      {
        rallyId: 'r-mine', scope: 'ALLIANCE', groupId: 'a-1', initiatorId: 'P-me',
        targetCoord: { x: 12, y: 34 }, targetType: 'RESOURCE', maxMembers: 20,
        joinedCount: 1, totalTroops: 500, prepareUntil: SERVER_NOW + 20_000,
        departAt: SERVER_NOW + 20_000, status: 'PREPARING',
        members: ['P-me'], heroSlots: [], serverNow: SERVER_NOW,
      },
    ],
  },
}

const failures = []
const lines = []
function verdict(ok, label, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}  ${detail}`)
  if (!ok) {
    failures.push(label)
  }
}

/** 读社交面板：当前页签、顶部提示行、每一行的文字与按钮、集结页签在不在。 */
function readSocial() {
  const out = { found: false, currentKey: null, tabs: [], hint: '', rows: [], buttons: [],
    visibleHeight: null, lineCount: 0 }
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  out.currentKey = game?.getComponent('PanelNav')?.currentKey ?? null
  out.visibleHeight = window.cc.view.getVisibleSize().height
  const panel = game?.getChildByName('social')
  if (panel === undefined || panel === null) {
    return out
  }
  out.found = true
  const textOf = (node) => {
    const own = node.getComponent && node.getComponent('cc.Label')
    return own !== null && own !== undefined && own.string !== '' ? own.string : null
  }
  for (const child of panel.children) {
    if (child.activeInHierarchy === false) continue
    if (child.name.startsWith('Tab_')) {
      // 页签的文案在子节点上（页签图是画在节点自身的 Graphics 上的）——
      // 只读节点自身会得到空列表，而那看起来像"页签没画出来"
      let text = textOf(child)
      for (const inner of child.children) {
        const deep = textOf(inner)
        if (deep !== null) { text = deep; break }
      }
      if (text !== null) out.tabs.push(text)
      continue
    }
    if (child.name === 'Hint') {
      const text = textOf(child)
      if (text !== null) out.hint = text
      continue
    }
    // 行（池化节点）：名字里带 Row，且带按钮的就是一行
    if (/Row/i.test(child.name)) {
      const texts = []
      let caption = null
      let enabled = false
      for (const grand of child.children) {
        const label = textOf(grand)
        if (label !== null) texts.push(label)
        for (const inner of grand.children) {
          const deep = textOf(inner)
          if (deep !== null) {
            texts.push(deep)
            caption = deep
            const graphics = inner.getComponent && inner.getComponent('cc.Graphics')
            const fill = graphics && graphics.fillColor
            enabled = fill !== undefined && fill !== null && !(fill.r < 50 && fill.g < 45 && fill.b < 40)
          }
        }
      }
      out.rows.push(texts.join(' / '))
      out.buttons.push(caption)
      out.lineCount += 1
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
  const rallyCalls = []
  page.on('pageerror', error => errors.push(String(error)))
  page.on('request', (req) => {
    if (req.url().includes('/rally/')) rallyCalls.push(`${req.method()} ${req.url()}`)
  })

  let boot = null
  page.on('console', (msg) => {
    const text = msg.text()
    if (text.startsWith('[boot] ')) {
      try { boot = JSON.parse(text.slice('[boot] '.length)) } catch { /* 非结构化那条不算数 */ }
    }
  })

  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', 'social')
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  await hideGuideOverlay(page)
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
  verdict(boot.missingPanels === '', '装配自检说没有缺件',
    `attemptedPanels=${boot.attemptedPanels} mountedPanels=${boot.mountedPanels} missing="${boot.missingPanels}"`)

  // 切到「集结」页签
  let read = await page.evaluate(readSocial)
  for (let i = 0; i < 30 && !(read.tabs ?? []).includes('集结'); i += 1) {
    await page.waitForTimeout(500)
    read = await page.evaluate(readSocial)
  }
  verdict((read?.tabs ?? []).includes('集结'),
    '社交面板多了「集结」页签（不是导航第 17 项）', `页签=${JSON.stringify(read?.tabs)}`)

  const before = rallyCalls.length
  await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.getChildByName('social')
    for (const child of panel?.children ?? []) {
      if (child.name === 'Tab_rally') {
        child.emit('touch-start')
        return
      }
    }
  })
  await page.waitForTimeout(1200)
  verdict(rallyCalls.slice(before).some(call => call.includes('/rally/list')),
    '切到集结页签真的拉了 /rally/list', `新增请求=${JSON.stringify(rallyCalls.slice(before))}`)

  read = await page.evaluate(readSocial)
  verdict(/没有进行中的集结/.test(read.hint ?? ''),
    '没有集结时把"这一页本该有什么"说出来（不是一片空白）', `提示="${read.hint}"`)

  // 注入生产形状的数据：一支我没参、一支我发起。
  //
  // **两份都要置**：视图那份负责画，编排层那份（`AppRoot.rallyResp`）负责答"点加入要打开哪一支"。
  // 只置视图会让「点加入」走"这一支已经结束了"那条拒绝路 —— 探针首跑就这么假红过一次。
  await page.evaluate((sample) => {
    const withMe = (source, myId) => ({
      ...source,
      rallies: source.rallies.map(rally => rally.rallyId === 'r-mine'
        ? { ...rally, initiatorId: myId, members: [myId] }
        : rally),
    })
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.getChildByName('social')
    const component = panel?.getComponent('SocialPanelView')
    if (component === undefined || component === null) {
      throw new Error('拿不到 SocialPanelView（场景装配变了？）')
    }
    component.attachRallies({ ...sample, myPlayerId: game?.getComponent('GameBootstrap')?.root?.playerId ?? sample.myPlayerId })
    const bootstrap = game?.getComponent('GameBootstrap')
    const root = bootstrap === undefined || bootstrap === null ? null : bootstrap.root
    if (root === null) {
      throw new Error('拿不到 AppRoot（组合根装配变了？）')
    }
    // 用真实身份重建那一支「我发起的」：视图与编排层都按它组装
    root.rallyResp = withMe(sample.source, root.playerId)
    root.deliverRallies()
  }, SAMPLE)
  await page.waitForTimeout(400)
  const filled = await page.evaluate(readSocial)
  const joinedRow = (filled.rows ?? []).find(row => row.includes('野外怪')) ?? ''
  verdict(/目标：野外怪 \(433,95\)/.test(joinedRow),
    '行里的目标与坐标来自响应', `"${joinedRow}"`)
  verdict(/已加入 3\/10 人 · 兵力 12400/.test(joinedRow),
    '人数与兵力都在（集结的核心决策是"这波打得过吗"）', `"${joinedRow}"`)
  verdict(/准备还剩 2 分 35 秒/.test(joinedRow),
    '倒计时是服务端两个时刻相减（铁律 5）', `"${joinedRow}"`)
  verdict((filled.buttons ?? []).includes('加入'),
    '没参的那一支给的是「加入」', `按钮=${JSON.stringify(filled.buttons)}`)
  verdict((filled.buttons ?? []).includes('取消集结'),
    '我发起的那一支给的是「取消集结」而不是「退出」', `按钮=${JSON.stringify(filled.buttons)}`)

  const shot = path.join(SHOT_DIR, 'rally-tab.png')
  await page.screenshot({ path: shot })
  lines.push(`SHOT  ${shot}`)

  // 点「加入」：应当只打开编队面板，不发 join
  const beforeJoin = rallyCalls.length
  const clicked = await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.getChildByName('social')
    for (const row of panel?.children ?? []) {
      for (const button of row.children ?? []) {
        // **监听挂在按钮节点上**（不是它的 Caption 子节点）：Cocos 的 emit 不冒泡，
        // 对着 Caption 发 touch-start 什么都不会发生 —— 而那看起来像"按钮点了没反应"
        if (button.name !== 'ActionButton') continue
        for (const inner of button.children ?? []) {
          const label = inner.getComponent && inner.getComponent('cc.Label')
          if (label !== null && label !== undefined && label.string === '加入') {
            button.emit('touch-start')
            return button.name + ':' + label.string
          }
        }
      }
    }
    return null
  })
  verdict(clicked !== null, '找到了「加入」那颗按钮（点击目标 = 监听所在节点）', `clicked=${clicked}`)
  await page.waitForTimeout(800)
  const afterJoin = await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas')?.getChildByName('Game')
    const overlay = game?.getChildByName('MarchCompose')
    return { composeActive: overlay === undefined ? null : overlay.activeInHierarchy !== false }
  })
  verdict(!rallyCalls.slice(beforeJoin).some(call => call.includes('/rally/join')),
    '点「加入」不发 join（协议要求带承诺兵力 ⇒ 先进编队，确认才发）',
    `新增请求=${JSON.stringify(rallyCalls.slice(beforeJoin))}`)
  verdict(afterJoin.composeActive !== false,
    '点「加入」真的打开了编队面板（复用出征那套）',
    `MarchCompose.active=${afterJoin.composeActive}`)

  verdict(errors.length === 0, '全程零页面异常',
    `errors=${errors.length}${errors.length > 0 ? ' → ' + errors[0] : ''}`)

  await browser.close()
  await preview.close()

  console.log('\n[rally] 判定：')
  for (const line of lines) console.log(`  ${line}`)
  if (failures.length > 0) {
    console.error(`\n[rally] ${failures.length} 条判据失败：${failures.join('；')}`)
    process.exit(1)
  }
  console.log('\n[rally] 全部判据通过。截图见上面那行 SHOT。')
}

main().catch((error) => {
  console.error('[rally] 探针自身崩了（不是判据失败）:', error)
  process.exit(2)
})
