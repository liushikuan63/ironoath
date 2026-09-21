/**
 * 职责：**训练队列的两个动作**（加速 / 取消）的实机验收 —— 收口清单"客户端发送口缺口"·军队四格的头两个。
 * 依赖：`client/build/web-mobile` 产物 + 一台本轮自己的 dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么需要它：这两个端点（`/army/speedUp`、`/army/cancel`）服务端与协议早就齐了，
 * 缺的只是入口 —— 而"入口接上了没有"只有真点一下才算数（`report-client-send-paths` 只能证明
 * "有方法没人调"，证明不了"点了会发生什么"）。
 *
 * <p>判据（都能失败）：
 *   ① 训练中那一行必须出现「加速」「取消」两只按钮（**只在训练时出现**：没在训练还摆着就是骗点击）；
 *   ② 点「加速」必须发出 `/army/speedUp`，且该行倒计时**变短**（不是只发请求就算过）；
 *   ③ 点「取消」必须发出 `/army/cancel`，且该行不再处于训练态（文本里没有"训练中"）。
 *
 * 退出码：0 全绿；1 判据失败；2 前置不满足（产物/后端/建号/没找到可训练的兵种）。
 *
 * <h3>前置链（2026-09-22 实测踩出来的，别再猜）</h3>
 * 新号**一个兵种都训不了**，链条是：**主城 3 级 → 兵营 → 有上阵武将**（统率值 > 0）→ 才能训练。
 * 三段各自被服务端挡回来的原话：
 *   ① 直接建兵营：`3000 主城等级不足 需要 主城 3 级，当前 主城 2 级`（我第一版按 2 级写，错）；
 *   ② 兵营建好就训练：`5000 兵种尚未解锁 … 超出带兵上限：当前 0，上限 0，本次请求 10。
 *      提升上阵武将的统帅值（升级/升星/装备）可提高上限`；
 *   ③ 于是要补"给测试号一个武将 + 设编队"（仓库唯一那条凭空发奖励的通路 `/ops/mail/send`
 *      支持 `type=HERO`；编队用 `heroSetLineup`）。
 *
 * <p><b>当前状态：本探针停在 ②</b> —— 建号 → 升主城到 3 → 建兵营都通了，训练那一步因为
 * "没有上阵武将"被拒（退出码 2，前置不满足）。③ 那一段没做，所以**这只探针还没有产出全绿读数**；
 * 客户端接线本身（`b7b6a2a`）已过类型检查与 848 条单测，但"点了会发生什么"**尚未在实机上验过**。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.ARMY_QUEUE_PORT ?? 8298)
const OUT = 'client/build/art-verify'
const SHOT = path.join(OUT, '17-army-queue-actions.png')

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error('[army-queue][前置] 产物不存在（先跑 scripts/build-webmobile.sh）')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const deviceId = `army-queue-${Date.now()}`
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
  requestId: `army-queue-init-${Date.now()}`, deviceId, nickName: '军队队列探针',
  clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[army-queue][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}
const playerId = init.data.playerId
const HEAD = { 'X-Player-Id': playerId }

/**
 * 先备好兵营：**新号一个兵种都训不了**（`unlocked=false`，提示"需要兵营 1 级（当前 0 级）"）。
 * 这是前置而不是判据 —— 探针要量的"训练队列的两只动作按钮"必须先有一批在训练。
 * 兵营要主城 2 级，所以先升主城；每一步都等它真的到点（服务端时间差，不用 sleep 猜）。
 */
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const upgradeAndWait = async (configId, coords) => {
  const body = { requestId: `army-queue-${configId}-${Date.now()}`, configId, ...(coords ?? {}) }
  const response = await post('/city/upgrade', body, HEAD)
  if (response.code !== 0) {
    console.error(`[army-queue][前置] ${configId} 起不来：${response.code} ${response.msg} ${response.detail ?? ''}`)
    process.exit(2)
  }
  await sleep(Math.max(0, response.data.finishAt - response.serverNow) + 1500)
}
const city = await get('/city/list', HEAD)
const mainLevel = (city.data.buildings ?? []).find((b) => b.configId === 'main_city')?.level ?? 1
// 兵营的门槛是**主城 3 级**（第一版写成 2，被服务端一句 `需要 主城 3 级` 挡回来 —— 门槛别猜，读报错）
for (let level = mainLevel + 1; level <= 3; level++) {
  await upgradeAndWait('main_city')
  console.log(`[army-queue] 主城 → ${level} 级`)
}
const occupied = new Set((city.data.buildings ?? []).map((b) => `${b.gridX},${b.gridY}`))
const free = [[1, 1], [2, 1], [1, 2]].find(([x, y]) => !occupied.has(`${x},${y}`)) ?? [1, 1]
await upgradeAndWait('barracks', { gridX: free[0], gridY: free[1] })
console.log(`[army-queue] 兵营已建在 (${free[0]},${free[1]})`)

// 第三段前置：**上阵一个武将**。新号带兵上限是 0（`超出带兵上限：当前 0，上限 0`），
// 而上限由"上阵武将的统帅值"决定 ⇒ 必须真有一个武将在编队里。
// 武将从仓库**唯一那条**凭空发奖励的通路来（`/ops/mail/send`，`type=HERO` 合法），与整城验收补资源同一条。
const HERO_ID = process.env.ARMY_QUEUE_HERO ?? 'hero_ssr_01'
const mail = await post('/ops/mail/send', {
  requestId: `army-queue-hero-${Date.now()}`, playerId,
  title: '军队队列探针用武将', text: '自动化量具建号后的补发（dev 后端限定）', actor: 'tools/verify-army-queue',
  rewards: [{ type: 'HERO', id: HERO_ID, count: 1, name: HERO_ID }],
}, { 'X-Ops-Token': process.env.ARMY_QUEUE_OPS_TOKEN ?? 'art-verify-local' })
if (mail.code !== 0) {
  console.error(`[army-queue][前置] 补发武将被拒（令牌没配？）：${JSON.stringify(mail)}`)
  process.exit(2)
}
const claimed = await post('/mail/claimAll', { requestId: `army-queue-claim-${Date.now()}` }, HEAD)
if (claimed.code !== 0) {
  console.error(`[army-queue][前置] 领取武将失败：${JSON.stringify(claimed)}`)
  process.exit(2)
}
const lineup = await post('/hero/lineup',
  { requestId: `army-queue-lineup-${Date.now()}`, presetIndex: 0, main: HERO_ID, sub1: null, sub2: null }, HEAD)
if (lineup.code !== 0) {
  console.error(`[army-queue][前置] 上阵 ${HERO_ID} 失败：${JSON.stringify(lineup)}`)
  process.exit(2)
}
const capAfterLineup = lineup.data.troopCap ?? null
console.log(`[army-queue] 已补发并上阵 ${HERO_ID}：带兵上限=${capAfterLineup}`)
if (capAfterLineup !== null && capAfterLineup <= 0) {
  console.error('[army-queue][前置] 上阵之后带兵上限仍是 0 —— 训练一定发不出去，先查编队口径')
  process.exit(2)
}

const list = await get('/army/list', HEAD)
if (list.code !== 0) {
  console.error(`[army-queue][前置] 读军队列表失败：${JSON.stringify(list)}`)
  process.exit(2)
}
const units = list.data.units ?? []
const trainable = units.find((unit) => unit.unlocked !== false && (unit.count ?? 0) >= 0)
if (trainable === undefined) {
  console.error('[army-queue][前置] 没有可训练的兵种（新号也应当有第一个）')
  process.exit(2)
}
const start = await post('/army/train',
  { requestId: `army-queue-train-${Date.now()}`, unitId: trainable.unitId, count: 10 }, HEAD)
if (start.code !== 0) {
  console.error(`[army-queue][前置] 发起训练失败（${trainable.unitId}）：${JSON.stringify(start)}`)
  process.exit(2)
}
console.log(`[army-queue] 建号 ${playerId}：${trainable.name ?? trainable.unitId} 已开始训练 10 个`)

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
const armyPosts = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('request', (request) => {
  if (request.method() === 'POST' && request.url().includes('/army/')) {
    armyPosts.push(request.url().split('/army/')[1])
  }
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'army')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => {
  if (window.cc === undefined || window.cc.director === undefined) return false
  const scene = window.cc.director.getScene()
  if (scene === null) return false
  const nav = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
  return nav !== null && nav !== undefined && nav.currentKey === 'army'
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

/**
 * 训练中那一行的读数。
 *
 * <p>**必须"先锁定那一行再读行内的按钮"**：行是池化复用的，屏幕上同时有好几个 `UnitRow`，
 * 每个行节点里都有一只 `SpeedTrainButton`/`CancelTrainButton`（非训练行的被隐藏）。
 * 第一版遍历全场景、用"最后访问到的那只"当读数 ⇒ 被非训练行盖成 `false`，
 * 于是"按钮明明出来了"被判成没出来（而点击是成功的，请求都发出去了）。
 * 顺带把倒计时也改成**行内**读（原来在全场景文本里找"剩"字，一无所获 ⇒ 那条判据静默不生效）。
 */
const rowReadout = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const rows = []
  const visit = (node) => {
    if (node.name === 'UnitRow') {
      const texts = []
      const collect = (child) => {
        const label = child.getComponent && child.getComponent('cc.Label')
        if (label !== null && label !== undefined && label.string !== '') texts.push(label.string)
        for (const grand of child.children) collect(grand)
      }
      collect(node)
      const find = (name) => node.children.find((child) => child.name === name) ?? null
      const speed = find('SpeedTrainButton')
      const cancel = find('CancelTrainButton')
      const countdown = find('Countdown')?.getComponent('cc.Label')?.string ?? null
      rows.push({
        texts,
        training: texts.some((text) => text.includes('训练中')),
        speed: speed === null ? null : speed.active === true,
        cancel: cancel === null ? null : cancel.active === true,
        countdown,
      })
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
  const active = rows.find((row) => row.training) ?? null
  return { rows, active, rowCount: rows.length }
})

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

const training = await rowReadout()
const activeRow = training.active
console.log(`[army-queue] 行数=${training.rowCount} 训练中那一行：加速键=${activeRow?.speed} 取消键=${activeRow?.cancel}`
  + ` 倒计时=${activeRow?.countdown ?? '(无)'} 文本=${JSON.stringify(activeRow?.texts ?? null)}`)

// 点「加速」→ 请求要出去，倒计时要变短
const before = activeRow?.countdown ?? null
const postsBeforeSpeed = armyPosts.length
const speedPoint = await clickNode('SpeedTrainButton')
if (speedPoint !== null) {
  await page.mouse.click(speedPoint.x, speedPoint.y)
  await page.waitForTimeout(2000)
}
const afterSpeed = await rowReadout()
const afterText = afterSpeed.active?.countdown ?? null
console.log(`[army-queue] 点「加速」→ /army/* 新增 [${armyPosts.slice(postsBeforeSpeed).join('、') || '(无)'}]`
  + ` 倒计时：${before ?? '(无)'} → ${afterText ?? '(无)'}`)

// 再点「取消」：训练态应当消失（取消不返还到 UI 文本里，但队列要腾出来）
const postsBeforeCancel = armyPosts.length
const cancelPoint = await clickNode('CancelTrainButton')
if (cancelPoint !== null) {
  await page.mouse.click(cancelPoint.x, cancelPoint.y)
  await page.waitForTimeout(2000)
}
const afterCancel = await rowReadout()
await page.screenshot({ path: SHOT })
await browser.close()
await preview.close()

console.log(`[army-queue] 点「取消」→ /army/* 新增 [${armyPosts.slice(postsBeforeCancel).join('、') || '(无)'}]`
  + ` 取消后训练中的行=${afterCancel.active === null ? '(没有了)' : `加速键=${afterCancel.active.speed} 取消键=${afterCancel.active.cancel}`}`)
console.log(`[army-queue] 截图：${SHOT}`)
console.log(`[army-queue] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

const failures = []
if (activeRow === null) {
  failures.push('屏幕上没有"训练中"的行 —— 前置没生效，判据走不到（不许当绿）')
} else {
  if (activeRow.speed !== true || activeRow.cancel !== true) {
    failures.push(`训练中那一行没有出现「加速」「取消」两只按钮：加速=${activeRow.speed} 取消=${activeRow.cancel}`)
  }
}
if (!armyPosts.includes('speedUp')) {
  failures.push('点了「加速」没有发出 /army/speedUp —— 按钮没接上')
}
if (before === null || afterText === null) {
  failures.push(`训练行的倒计时读不到（${before ?? '(无)'} → ${afterText ?? '(无)'}）—— 这条判据走不到，不许当绿`)
} else if (before === afterText) {
  failures.push(`点了「加速」但倒计时没变（${before}）—— 请求出去了而状态没跟着走`)
}
if (!armyPosts.includes('cancel')) {
  failures.push('点了「取消」没有发出 /army/cancel —— 按钮没接上')
}
if (afterCancel.active !== null && afterCancel.active.cancel === true) {
  failures.push('取消之后「取消」键还在 —— 那一行已经不在训练中了，按钮该收起来')
}
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}
if (failures.length > 0) {
  console.error(`[army-queue] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[army-queue] 全绿：训练行出现两只动作按钮，加速发了请求且倒计时变短，取消发了请求且按钮收起')
