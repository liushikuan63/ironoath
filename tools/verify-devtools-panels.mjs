#!/usr/bin/env node
/**
 * 职责：把「每个面板到底画没画出东西」变成一条能失败的判据（原先它是人工项）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 *   BACKEND_ORIGIN=http://localhost:8155 DEVTOOLS_OPS_TOKEN=<运维令牌> \
 *     node tools/verify-devtools-panels.mjs
 *
 * <p><b>为什么用 web 产物证小游戏的界面</b>：面板与导航条是同一份代码 ——
 * `PanelNav` + 那 13 个 `scene/*PanelView.ts` 在两个平台上完全相同，
 * 唯一的差别是运行时（浏览器 vs 微信小游戏）。所以「挂上节点之后有没有画出文本」
 * 这一项在浏览器里量一次，与在模拟器里量一次得到的是同一个答案；
 * 而小游戏那边没有可编程点击通道，只能靠 `verify-devtools-runtime.mjs` 读自检回执。
 * 两个工具各证一段：那个证「装配齐 + 后端通」，这个证「装配完之后真的画出了东西」。
 *
 * <p><b>面板清单从源码里读，不在这里写第二份</b>：解析 `PanelNav.ts` 的 `PANELS`，
 * 于是新增一个面板忘了在这里登记不会悄悄少测一个（少测的表现是"全绿但没人看过那个面板"）。
 *
 * <p><b>反空转下限</b>：一个面板都没解析出来 / `[boot]` 没捕获 / 全部面板零文本，
 * 都按失败处理，而不是"没量到就跳过"。
 *
 * <p><b>读数只统计"父链全激活"的节点</b>（2026-09-16 修正）：池化归还的行节点仍留在场景里、
 * label.string 也还在，但 active=false（不渲染）。先前不看激活状态的版本能读到"上一页"的文字 ——
 * 表现是切到成就页仍能读到活动行，判据因此说假话。修正后还顺带挡掉了另一个形状：
 * `power`/`settings` 两格在从未激活时子树里就有 21 / 8 条非空 Label（这两个视图在登录推数据那一路
 * 就把节点建好了，不依赖 onLoad），旧读法会把它们算成"画出来了"。
 * 所以对这两格，本工具证的是「切过去了 + 处于激活态 + 激活子树里有内容」，<b>不是</b>"激活之后才画"。
 */
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = path.resolve('client/build/web-mobile')
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const TOKEN = process.env.DEVTOOLS_OPS_TOKEN ?? ''
const PORT = Number(process.env.PANEL_PORT ?? 8093)
const NAV_SOURCE = 'client/assets/scripts/scene/PanelNav.ts'
const ACTIVITY_TABLE = 'contract/config/activity.json'

/** 活动名从表里现读：断言"画出来的是真活动"而不是"表头里有活动两个字"（那在加载态也成立）。 */
function activityNames() {
  const table = JSON.parse(readFileSync(ACTIVITY_TABLE, 'utf8'))
  const names = table.rows.map(row => row.name)
  if (names.length < 5) {
    throw new Error(`从 ${ACTIVITY_TABLE} 里只读到 ${names.length} 个活动名：解析式过期了`)
  }
  return names
}

/** 导航表里的面板 key。顺序就是导航条的顺序，也是本工具的走查顺序。 */
function panelKeys() {
  const source = readFileSync(NAV_SOURCE, 'utf8')
  const keys = [...source.matchAll(/^\s*\{ key: '([a-zA-Z]+)', label: '([^']+)', view: (\w+)/gm)]
  if (keys.length < 5) {
    throw new Error(`从 ${NAV_SOURCE} 里只解析出 ${keys.length} 个面板：解析式过期了，不是"面板少了"`)
  }
  return keys.map(m => ({ key: m[1], label: m[2], view: m[3] }))
}

const failures = []
const lines = []

function verdict(ok, label, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}  ${detail}`)
  if (!ok) {
    failures.push(label)
  }
}

/** 在页面里按节点名找到那个面板的根节点，收集它子树里所有非空 Label 文本。 */
function readPanel({ key }) {
  const out = { found: false, active: false, currentKey: null, texts: [] }
  const walk = (node, inside) => {
    // 只算**父链全激活**的节点：池化归还的行节点仍留在场景里、label.string 也还在，
    // 但 active=false（不渲染）。不看激活状态的话，切到另一页仍会读到上一页的文字
    const live = inside && node.activeInHierarchy !== false
    const label = live && node.getComponent ? node.getComponent('cc.Label') : null
    if (label !== null && label !== undefined && label.string !== '') {
      out.texts.push(label.string)
    }
    for (const child of node.children) walk(child, live)
  }
  const scene = window.cc.director.getScene()
  // 导航层当前的那一格：只看文本分不清「深链没生效」与「面板自己没画」
  out.currentKey = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
    ?.currentKey ?? null
  const stack = [...scene.children]
  while (stack.length > 0) {
    const node = stack.pop()
    if (node.name === key) {
      out.found = true
      out.active = node.active === true
      walk(node, true)
      return out
    }
    for (const child of node.children) stack.push(child)
  }
  return out
}

async function main() {
  if (TOKEN === '') {
    console.error('DEVTOOLS_OPS_TOKEN 未给：邮件那格的"有数据时真的画出一行"要用运维补发一封，'
      + '拿不到令牌就只能退回"空状态也画出来了"这一条，那不算验到内容。')
    process.exit(2)
  }
  const panels = panelKeys()
  const activityNameList = activityNames()
  console.log(`[panels] 从 ${ACTIVITY_TABLE} 读到 ${activityNameList.length} 个活动名：${activityNameList.join(' ')}`)
  console.log(`[panels] 从 ${NAV_SOURCE} 解析出 ${panels.length} 个面板：${panels.map(p => p.key).join(' ')}`)

  const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  const page = await context.newPage()

  let boot = null
  page.on('console', (msg) => {
    const text = msg.text()
    if (text.startsWith('[boot] ')) {
      try {
        boot = JSON.parse(text.slice('[boot] '.length))
      } catch {
        // 不是 JSON 的 [boot] 前缀日志：忽略，不影响判据（判据要的是那一条结构化的）
      }
    }
  })
  await page.goto(`${preview.origin}/`, { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  await page.waitForFunction(() => document.querySelector('canvas') !== null, null, { timeout: 30_000 })

  // [boot] 在登录 + 首屏预拉之后才打，等它而不是等固定毫秒
  const bootDeadline = Date.now() + 45_000
  while (boot === null && Date.now() < bootDeadline) {
    await page.waitForTimeout(500)
  }
  // 指错后端的话，下面 13 格量的是别人那台的数据 —— 越早发现越不白跑
  preview.assertRewritten()
  if (boot === null) {
    await browser.close()
    await preview.close()
    console.error('\n=== 判定中止：没捕获到 [boot] 自检行，后面每格读数都会是假的 ===')
    process.exit(1)
  }
  verdict(boot.started === true, '启动跑通（started=true）',
    `platform=${boot.platform} mounted=${boot.mountedPanels}/${boot.attemptedPanels} bootMs=${boot.bootMs}`)
  verdict(boot.missingPanels === '' && boot.attemptedPanels === panels.length,
    '导航表与自检回执对上：面板数一致且一个都不缺',
    `回执 attempted=${boot.attemptedPanels} missing="${boot.missingPanels}"，源码解析 ${panels.length}`)

  const results = []
  for (const panel of panels) {
    // 查询串必须用 URL 拼：Cocos 的 loader 会把裸串里的 `&` 当分隔符截断
    const url = new URL(`${preview.origin}/`)
    url.searchParams.set('panel', panel.key)
    await page.goto(url.toString(), { waitUntil: 'domcontentloaded' })
    await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
      null, { timeout: 60_000 })
    let read = { found: false, active: false, currentKey: null, texts: [] }
    for (let i = 0; i < 40; i += 1) {
      await page.waitForTimeout(500)
      read = await page.evaluate(readPanel, { key: panel.key })
      if (read.currentKey === panel.key && read.texts.length > 0) {
        break
      }
    }
    results.push({ ...panel, ...read, sample: read.texts.slice(0, 3).join(' | ') })
  }

  console.log('\n[panels] 逐格读数（Label 取自 Cocos 场景图，不是 DOM）：')
  for (const r of results) {
    verdict(r.found && r.active && r.currentKey === r.key && r.texts.length > 0,
      `${r.key}（${r.label}）画出了内容`,
      `found=${r.found} active=${r.active} 当前格=${r.currentKey ?? '—'} 文本 ${r.texts.length} 条`
      + ` → ${r.sample.slice(0, 80)}`)
  }
  verdict(results.every(r => r.texts.length > 0), '一个面板都没有静默空白',
    `${results.filter(r => r.texts.length === 0).length} 格零文本 / 共 ${results.length} 格`)

  // ---- 有数据的那一格：运维补发一封，再走一次邮件格 ----
  const playerId = typeof boot.playerId === 'string' ? boot.playerId : ''
  verdict(playerId !== '', '自检行给了 playerId（没有它就验不了"邮件格里画出那一封"）',
    `playerId=${playerId === '' ? '—' : playerId}`)
  if (playerId !== '') {
    const title = `实时校验-${Date.now() % 100000}`
    const send = await fetch(`${BACKEND}/ops/mail/send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Ops-Token': TOKEN },
      body: JSON.stringify({ requestId: `panel-check-${Date.now()}`, playerId,
        title, text: '面板渲染校验用的一封补偿', rewards: [], actor: 'verify-devtools-panels' }),
    }).then(r => r.json())
    verdict(send.code === 0, '补发这封邮件成功（否则下一条判据测的是空态）',
      `code=${send.code} mailId=${send.data?.mailId ?? '—'}`)

    // 未读标题在场景图里带 '● ' 前缀，所以按子串命中而不是整条相等
    let texts = []
    for (let i = 0; i < 24 && !texts.some(t => t.includes(title)); i += 1) {
      const url = new URL(`${preview.origin}/`)
      url.searchParams.set('panel', 'mail')
      await page.goto(url.toString(), { waitUntil: 'domcontentloaded' })
      await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
        null, { timeout: 60_000 })
      await page.waitForTimeout(1200)
      texts = (await page.evaluate(readPanel, { key: 'mail' })).texts
    }
    const hit = texts.some(t => t.includes(title))
    verdict(hit, '邮件格里真的画出了那一封的标题（不是只有骨架）',
      `读到 ${texts.length} 条文本，找 "${title}" ${hit ? '命中' : '没命中'}`)
  }

  // ---- 任务面板的页签（B17 新增 活动 / 成就）----
  {
    const openQuest = async () => {
      const url = new URL(`${preview.origin}/`)
      url.searchParams.set('panel', 'quest')
      await page.goto(url.toString(), { waitUntil: 'domcontentloaded' })
      await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
        null, { timeout: 60_000 })
    }
    const questTexts = async () => (await page.evaluate(readPanel, { key: 'quest' })).texts
    const clickTab = async (name) => page.evaluate((tabName) => {
      const scene = window.cc.director.getScene()
      const quest = scene.getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('quest')
      if (quest === undefined || quest === null) return 'no-quest-node'
      const tab = quest.children.find(child => child.name === tabName)
      if (tab === undefined) return 'no-tab:' + tabName
      // 与玩家点一下同一条路：节点上注册的就是 touch-start 处理函数
      tab.emit('touch-start')
      return 'ok'
    }, `Tab-${name}`)

    await openQuest()
    let texts = []
    for (let i = 0; i < 40 && texts.length === 0; i += 1) {
      await page.waitForTimeout(500)
      texts = await questTexts()
    }
    verdict(texts.length > 0, '任务页签切之前就有内容（对照组：切页签不是"从空白变有"）',
      `任务页文本 ${texts.length} 条 → ${texts.slice(0, 2).join(' | ').slice(0, 60)}`)

    const clicked = await clickTab('activity')
    // 命中判据是**表里现读的活动名**：只匹配"活动 "表头的话，加载态（"活动 加载中"）也会绿
    const hitsOf = (texts) => activityNameList.filter(name => texts.some(text => text.includes(name)))
    let activityTexts = []
    let hits = []
    for (let i = 0; i < 30; i += 1) {
      await page.waitForTimeout(500)
      activityTexts = await questTexts()
      hits = hitsOf(activityTexts)
      if (hits.length >= 2) break
    }
    verdict(clicked === 'ok' && hits.length >= 2,
      '活动页签切过去后画出了表里的活动行（名称来自 activity.json，至少两条）',
      `click=${clicked} 命中 ${hits.length}/${activityNameList.length} → ${hits.slice(0, 3).join('、')}`
      + `｜文本 ${activityTexts.length} 条：${activityTexts.slice(0, 3).join(' | ').slice(0, 60)}`)

    const clickedAch = await clickTab('achievement')
    let achTexts = []
    for (let i = 0; i < 20; i += 1) {
      await page.waitForTimeout(500)
      achTexts = await questTexts()
      if (achTexts.some(text => text.includes('成就 '))) break
    }
    verdict(clickedAch === 'ok' && achTexts.some(text => text.includes('成就 '))
      && !achTexts.some(text => activityNameList.some(name => text.includes(name))),
      '成就页签切过去后有表头、且不含活动行（B17 只落了机制、没配行，所以这里是 0 条 —— 空态也要看得见）',
      `click=${clickedAch} 文本 ${achTexts.length} 条 → ${achTexts.join(' | ').slice(0, 400)}`)
  }

  console.log('\n' + lines.join('\n'))
  console.log(`\n产物里那两个写死地址换到 ${BACKEND}：${preview.rewrites()} 处`)
  console.log(failures.length === 0
    ? '\n=== 判定：面板都画出了东西（界面这一格从人工项变成了判据）==='
    : `\n=== 判定：${failures.length} 条不过 ===`)

  await browser.close()
  await preview.close()
  process.exit(failures.length === 0 ? 0 : 1)
}

main().catch((error) => {
  console.error('ERROR ' + error.message)
  process.exit(2)
})
