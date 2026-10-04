/**
 * 职责：**背包「全开」的实机验收** —— 批量开箱（B04 验收 3）从"有端点没人调"到"点了真开"。
 * 依赖：`client/build/web-mobile` 产物 + 一台本轮自己的 dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>种子走仓库**唯一那条**凭空发奖励的通路 `/ops/mail/send`（`type=ITEM` + 宝箱 id），
 * 与整城验收补资源、军队探针补武将是同一条 —— 不新增任何"开发期作弊"代码路径。
 *
 * <p>判据（都能失败）：
 *   ① 宝箱页里那一行必须出现「全开」（按钮按名字取；只在宝箱页且有库存时出现）；
 *   ② 点「全开」必须发出 `/item/openBatch`，且请求体里的 **count = 持有数量**（不是 0、不是 1）；
 *   ③ 开完之后那一行的数量必须**变少**（服务端真的扣了箱）。
 *
 * 退出码：0 全绿；1 判据失败；2 前置不满足（产物/后端/建号/补发被拒/找不到宝箱页）。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.BAG_BATCH_PORT ?? 8299)
const OPS_TOKEN = process.env.BAG_BATCH_OPS_TOKEN ?? 'art-verify-local'
const OUT = 'client/build/art-verify'
const SHOT = path.join(OUT, '18-bag-open-batch.png')
/** 持有 5 个：足够看出"全开"扣了库存，又不必等太久。 */
const CHESTS = 5
const CHEST_ID = process.env.BAG_BATCH_ITEM ?? 'item_chest_resource'

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error('[bag-batch][前置] 产物不存在（先跑 scripts/build-webmobile.sh）')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const deviceId = `bag-batch-${Date.now()}`
const post = async (url, body, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  return response.json()
}
const get = async (url, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, { headers })
  return response.json()
}
const init = await post('/player/init', {
  requestId: `bag-batch-init-${Date.now()}`, deviceId, nickName: '开箱探针',
  clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[bag-batch][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}
const playerId = init.data.playerId
const HEAD = { 'X-Player-Id': playerId }

const mail = await post('/ops/mail/send', {
  requestId: `bag-batch-mail-${Date.now()}`, playerId,
  title: '开箱探针用宝箱', text: '自动化量具建号后的补发（dev 后端限定）', actor: 'tools/verify-bag-open-batch',
  rewards: [{ type: 'ITEM', id: CHEST_ID, count: CHESTS, name: CHEST_ID }],
}, { 'X-Ops-Token': OPS_TOKEN })
if (mail.code !== 0) {
  console.error(`[bag-batch][前置] 补发宝箱被拒（令牌没配？）：${JSON.stringify(mail)}`)
  process.exit(2)
}
const claimed = await post('/mail/claimAll', { requestId: `bag-batch-claim-${Date.now()}` }, HEAD)
if (claimed.code !== 0) {
  console.error(`[bag-batch][前置] 领取失败：${JSON.stringify(claimed)}`)
  process.exit(2)
}
// /bag/list 回的是**扁平 items**（+ capacityUsed/Max）；**分页是客户端按类型分的**
// （game/bag/BagPanel.ts 的 buildBagPanel 干这事）—— 第一版按 pages 读，什么都没找到。
const bagBefore = await get('/bag/list', HEAD)
const chestRow = (bagBefore.data?.items ?? []).find((item) => item.itemId === CHEST_ID)
console.log(`[bag-batch] 建号 ${playerId}：${CHEST_ID} 持有 ${chestRow?.count ?? '(读不到)'} 个`)
if (chestRow === undefined || chestRow.count !== CHESTS) {
  console.error('[bag-batch][前置] 宝箱没进背包（数量对不上）—— 先确认补发真的落袋')
  process.exit(2)
}

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
const bagPosts = []
const openBatchBodies = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('request', (request) => {
  if (request.method() === 'POST' && request.url().includes('/item/')) {
    bagPosts.push(request.url().split('/item/')[1])
    openBatchBodies.push({ url: request.url(), body: request.postData() ?? '' })
  }
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'bag')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => {
  if (window.cc === undefined || window.cc.director === undefined) return false
  const scene = window.cc.director.getScene()
  if (scene === null) return false
  const nav = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
  return nav !== null && nav !== undefined && nav.currentKey === 'bag'
}, null, { timeout: 25_000 }).catch(() => {})
await page.waitForTimeout(2000)
await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const visit = (node) => {
    if (/Guide/i.test(node.name)) {
      const view = node.getComponent && node.getComponent('GuideView')
      if (view !== null && view !== undefined) view.enabled = false
      node.removeFromParent()
      return
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
})

/** 点一个按名字取的节点（返回是否点到）。 */
const clickNode = (nodeName) => page.evaluate((name) => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let target = null
  const visit = (node) => {
    if (node.name === name && node.activeInHierarchy === true) target = node
    for (const child of node.children) visit(child)
  }
  visit(scene)
  if (target === null) return null
  const box = target.getComponent('cc.UITransform')
  const camera = scene.getComponentInChildren('cc.Camera')
  if (box === null || camera === null) return null
  const screen = camera.worldToScreen(box.convertToWorldSpaceAR(new cc.Vec3(0, 0, 0)))
  const rect = document.querySelector('canvas').getBoundingClientRect()
  const pixel = cc.view.getVisibleSizeInPixel()
  return {
    x: rect.left + (screen.x / pixel.width) * rect.width,
    y: rect.top + rect.height - (screen.y / pixel.height) * rect.height,
  }
}, nodeName)

/** 当前页里那一行宝箱的读数：文本 + 「全开」是否可见。 */
const chestReadout = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const out = { texts: [], openBatch: null, useButton: null, tabFound: false }
  const visit = (node) => {
    if (/^Tab_CHEST$/.test(node.name)) out.tabFound = true
    if (node.name === 'Row') {
      const collect = (child) => {
        const label = child.getComponent && child.getComponent('cc.Label')
        if (label !== null && label !== undefined && label.string !== '') out.texts.push(label.string)
        for (const grand of child.children) collect(grand)
      }
      collect(node)
      const openBatch = node.getChildByName('OpenBatchButton')
      if (openBatch !== null && openBatch.active === true && out.openBatch !== true) out.openBatch = true
      const use = node.getChildByName('UseButton')
      if (use !== null && out.useButton === null) out.useButton = use.active === true
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
  return out
})

// 先切到「背包」页签 —— 面板只有两只页签：`Tab_resource`（资源明细）与 `Tab_bag`（背包）。
// **第一版直接去找 `Tab_CHEST` 并以为"产物里没有类型页签"，其实是我压根没切到背包页**，
// 于是一直在资源明细上找宝箱（STONE 那几行）。这两步都要：
//   ① 点 `Tab_bag`（真实按钮，产物里就有）；
//   ② 再切**背包页内部**的类型页到 CHEST —— 那一步靠编辑器控件（`selectBagPage` 的注释写着
//      "由编辑器的页签控件调用"），产物里没有可点的按钮，所以直接调视图的公开方法。
await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let view = null
  const visit = (node) => {
    if (node.getComponent !== undefined && node.getComponent('BagPanelView') !== null) view = node
    for (const child of node.children) visit(child)
  }
  visit(scene)
  return view !== null
})
const bagTab = await clickNode('Tab_bag')
if (bagTab !== null) {
  await page.mouse.click(bagTab.x, bagTab.y)
  await page.waitForTimeout(900)
}
const switched = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let view = null
  const visit = (node) => {
    if (node.getComponent !== undefined && node.getComponent('BagPanelView') !== null) view = node
    for (const child of node.children) visit(child)
  }
  visit(scene)
  if (view === null) return { found: false, pages: [], current: null }
  const component = view.getComponent('BagPanelView')
  component.selectBagPage('CHEST')
  return { found: true, pages: (component.bag?.pages ?? []).map((page) => page.type), current: 'CHEST' }
})
await page.waitForTimeout(900)
console.log(`[bag-batch] 切页：视图找到=${switched.found} 页类型=${JSON.stringify(switched.pages)}`)
const before = await chestReadout()
console.log(`[bag-batch] 「全开」可见=${before.openBatch} 行文本=${JSON.stringify(before.texts.slice(0, 8))}`)

const postsBefore = bagPosts.length
const openPoint = await clickNode('OpenBatchButton')
if (openPoint !== null) {
  await page.mouse.click(openPoint.x, openPoint.y)
  await page.waitForTimeout(2500)
}
await page.screenshot({ path: SHOT })
const after = await chestReadout()

const bagAfter = await get('/bag/list', HEAD)
const chestAfter = (bagAfter.data?.items ?? []).find((item) => item.itemId === CHEST_ID)
await browser.close()
await preview.close()

const newPosts = bagPosts.slice(postsBefore)
// 按 **URL** 挑那一条：按 body 内容筛是错的（body 里当然没有路径）—— 第一版就栽在这
const body = openBatchBodies.filter((entry) => entry.url.includes('/item/openBatch')).pop()?.body ?? ''
console.log(`[bag-batch] 点「全开」→ /item/* 新增 [${newPosts.join('、') || '(无)'}] 请求体=${body || '(无)'}`)
console.log(`[bag-batch] 服务端持有：${CHESTS} → ${chestAfter?.count ?? 0}；行文本=${JSON.stringify(after.texts.slice(0, 6))}`)
console.log(`[bag-batch] 截图：${SHOT}`)
console.log(`[bag-batch] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

const failures = []
if (before.openBatch !== true) {
  failures.push('宝箱页里那一行没有出现「全开」按钮 —— 要么页签没切过去，要么按钮判据不对')
}
if (!newPosts.includes('openBatch')) {
  failures.push('点了「全开」没有发出 /item/openBatch —— 按钮没接上')
}
const parsed = (() => {
  try {
    return JSON.parse(body)
  } catch {
    return null
  }
})()
if (parsed === null) {
  failures.push(`openBatch 的请求体读不出来（${body || '(空)'}）—— 数量这条判据走不到`)
} else {
  if (parsed.itemId !== CHEST_ID) {
    failures.push(`请求体的 itemId 是 ${parsed.itemId}，期望 ${CHEST_ID}`)
  }
  if (parsed.count !== CHESTS) {
    failures.push(`请求体的 count 是 ${parsed.count}，期望持有数量 ${CHESTS} —— 数量取自结构化 count，不是猜的`)
  }
}
// 开完之后箱子是 0 ⇒ 那一行**不再显示**（背包不列空行）⇒ "读不到"就是 0，不是"没变"
if ((chestAfter?.count ?? 0) !== 0) {
  failures.push(`开完之后持有数量不是 0（${chestAfter?.count ?? '读不到'}）—— 请求出去了而箱子没扣干净`)
}
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}
if (failures.length > 0) {
  console.error(`[bag-batch] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[bag-batch] 全绿：宝箱页出现「全开」，点它发出 /item/openBatch 且 count=持有数量，开完库存变少')
