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
 *   ② 点「加速」并真实选择一张训练令，`/army/speedUp` 业务成功；同批次 finishAt 按回执提前、库存只减一；
 *   ③ 点「取消」必须发出业务成功的 `/army/cancel`，权威列表和该行都不再处于训练态。
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
 * <p>登录时服务端可能下发升级礼包，它的模态输入层会先接到军队按钮位置上的点击。
 * 测量前须真实点击礼包关闭键并确认宿主已隐藏，不能直接调用 hide 或发射触摸事件绕过输入层。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { clickNodeViaCocos } from './lib/cocos-click.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.ARMY_QUEUE_PORT ?? 8298)
const OUT = 'client/build/art-verify'
const SHOT = path.join(OUT, '17-army-queue-actions.png')
console.log(`[army-queue] 后端 ${BACKEND} 预览端口 ${PORT}`)

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
 * 兵营要主城 3 级，所以先升主城；每一步都等它真的到点（服务端时间差，不用 sleep 猜）。
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
const TRAIN_ITEM_ID = process.env.ARMY_QUEUE_TRAIN_ITEM ?? 'item_speedup_train_1h'
// 一小时训练令会直接完成 10 人批次；用 100 人批次保留之后的取消前提。
const TRAIN_COUNT = 100
const mail = await post('/ops/mail/send', {
  requestId: `army-queue-hero-${Date.now()}`, playerId,
  title: '军队队列探针用武将', text: '自动化量具建号后的补发（dev 后端限定）', actor: 'tools/verify-army-queue',
  rewards: [
    { type: 'HERO', id: HERO_ID, count: 1, name: HERO_ID },
    { type: 'ITEM', id: TRAIN_ITEM_ID, count: 2, name: '训练令' },
    // dev 量具预算，覆盖 100 人批次；不改变产品的新号初始资源。
    { type: 'RESOURCE', id: 'IRON', count: 10_000, name: '铁矿' },
    { type: 'RESOURCE', id: 'GRAIN', count: 10_000, name: '粮草' },
  ],
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
// 奖励入账会受仓容截断；按本次真实下发的单兵消耗核算，不能只相信发奖请求成功。
const budgetCity = await get('/city/list', HEAD)
const trainingBudget = (trainable.trainCost ?? []).map(cost => ({
  type: cost.type, needed: cost.amount * TRAIN_COUNT,
  current: budgetCity.data?.resources?.[cost.type]?.current,
  cap: budgetCity.data?.resources?.[cost.type]?.cap,
}))
console.log(`[army-queue] 训练资源与仓容：${JSON.stringify(trainingBudget)}`)
if (budgetCity.code !== 0 || trainingBudget.length === 0 || trainingBudget.some(resource =>
  typeof resource.current !== 'number' || typeof resource.cap !== 'number'
  || resource.current < resource.needed || resource.cap < resource.needed)) {
  console.error('[army-queue][前置] 发奖后资源或仓容仍容不下本次训练预算')
  process.exit(2)
}
const start = await post('/army/train',
  { requestId: `army-queue-train-${Date.now()}`, unitId: trainable.unitId, count: TRAIN_COUNT }, HEAD)
if (start.code !== 0) {
  console.error(`[army-queue][前置] 发起训练失败（${trainable.unitId}）：${JSON.stringify(start)}`)
  process.exit(2)
}
console.log(`[army-queue] 建号 ${playerId}：${trainable.name ?? trainable.unitId} 已开始训练 ${TRAIN_COUNT} 个`)

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
let browser
const verifyArmyQueue = async () => {
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

// 升城前置会触发登录礼包；必须先走玩家的关闭入口，才能测到下面的军队动作。
const giftPopupState = await page.evaluate(() => {
  const game = window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
  const popup = game?.getChildByName('giftPopup')
  const nodes = []
  const visit = (node) => {
    if (['giftPopup', 'mask', 'panel', 'plate', 'close', 'DialogContent', 'DialogFooter'].includes(node.name)) {
      const sprite = node.getComponent('cc.Sprite')
      const graphics = node.getComponent('cc.Graphics')
      nodes.push({ name: node.name, layer: node.layer, active: node.activeInHierarchy,
        spriteEnabled: sprite?.enabled ?? null, spriteFrame: sprite?.spriteFrame?.name ?? null,
        graphicsEnabled: graphics?.enabled ?? null })
    }
    for (const child of node.children) visit(child)
  }
  if (popup) visit(popup)
  return { active: popup?.activeInHierarchy === true, nodes }
})
console.log(`[army-queue] 登录礼包材质与可见层：${JSON.stringify(giftPopupState)}`)
if (giftPopupState.active) {
  const popupShot = path.join(OUT, '17-army-queue-gift-before-close.png')
  await page.screenshot({ path: popupShot })
  const closePoint = await clickNodeViaCocos(page, { name: 'close', within: 'giftPopup' })
  const closed = closePoint.clicked && await page.waitForFunction(() => {
    const game = window.cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
    return game?.getChildByName('giftPopup')?.activeInHierarchy === false
  }, null, { timeout: 5000 }).then(() => true, () => false)
  console.log(`[army-queue] 登录礼包真实关闭：${JSON.stringify(closePoint)} 关闭=${closed} 截图=${popupShot}`)
  if (!closed) {
    console.error('[army-queue][前置] 登录礼包关闭键没有关闭模态输入层，军队动作尚不能测量')
    process.exitCode = 2
    return
  }
} else {
  console.log('[army-queue] 登录礼包未显示，无需关闭')
}

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

// 量具先过引擎 hitTest 与相机往返，不能把设计空间归一化后的错点当成按钮断线。
const clickNode = async (nodeName) => {
  // 同名池化行的非训练按钮也存在；临时命名已显示的训练行，限制引擎查找范围。
  const scoped = await page.evaluate((name) => {
    const rows = []
    const visit = (node) => {
      if (node.name === 'UnitRow' && node.activeInHierarchy) rows.push(node)
      for (const child of node.children) visit(child)
    }
    visit(window.cc.director.getScene())
    const row = rows.find((node) => node.children.some((child) =>
      child.getComponent('cc.Label')?.string.includes('训练中'))
      && node.getChildByName(name)?.activeInHierarchy === true)
    if (!row) return false
    row.name = 'ArmyQueueProbeTrainingRow'
    return true
  }, nodeName)
  if (!scoped) return { clicked: false, reason: 'training-row-not-found' }
  try {
    const point = await clickNodeViaCocos(page, { name: nodeName, within: 'ArmyQueueProbeTrainingRow' })
    console.log(`[army-queue] 点击 ${nodeName}：${JSON.stringify(point)}`)
    return point
  } finally {
    await page.evaluate(() => {
      const visit = (node) => {
        if (node.name === 'ArmyQueueProbeTrainingRow') node.name = 'UnitRow'
        for (const child of node.children) visit(child)
      }
      visit(window.cc.director.getScene())
    })
  }
}

const training = await rowReadout()
const activeRow = training.active
console.log(`[army-queue] 行数=${training.rowCount} 训练中那一行：加速键=${activeRow?.speed} 取消键=${activeRow?.cancel}`
  + ` 倒计时=${activeRow?.countdown ?? '(无)'} 文本=${JSON.stringify(activeRow?.texts ?? null)}`)

// 读权威批次与库存；自然过去两秒不能冒充道具加速成功。
const before = activeRow?.countdown ?? null
const armyBefore = await get('/army/list', HEAD)
const unitBefore = armyBefore.data?.units?.find(unit => unit.unitId === trainable.unitId)
const bagBefore = await get('/bag/list', HEAD)
const itemBefore = bagBefore.data?.items?.find(item => item.itemId === TRAIN_ITEM_ID)
const captureArmyResponse = endpoint => page.waitForResponse(response =>
  response.request().method() === 'POST' && new URL(response.url()).pathname === `/army/${endpoint}`,
{ timeout: 10_000 }).then(async response => ({ status: response.status(),
  request: response.request().postDataJSON(), ...await response.json() }))
  .catch(error => ({ missing: error.message }))

const postsBeforeSpeed = armyPosts.length
const speedResponsePromise = captureArmyResponse('speedUp')
const speedPoint = await clickNode('SpeedTrainButton')
let speedChoice = null
let choicePoint = { clicked: false, reason: 'speedup-picker-not-found' }
if (speedPoint.clicked) {
  await page.waitForFunction(() => {
    const walk = node => (node.name === 'ChoiceOverlay' && node.activeInHierarchy)
      || node.children.some(walk)
    return walk(window.cc.director.getScene())
  }, null, { timeout: 3000 }).catch(() => {})
  speedChoice = await page.evaluate(itemName => {
    let popup = null
    const walk = node => {
      if (node.name === 'ChoiceOverlay' && node.activeInHierarchy) popup = node
      for (const child of node.children) walk(child)
    }
    walk(window.cc.director.getScene())
    if (!popup) return null
    const options = []
    const rows = node => {
      if (/^Choice-\d+$/.test(node.name) && node.activeInHierarchy) {
        const texts = []
        const labels = child => {
          const text = child.getComponent('cc.Label')?.string
          if (text) texts.push(text)
          for (const grand of child.children) labels(grand)
        }
        labels(node)
        options.push({ name: node.name, texts })
      }
      for (const child of node.children) rows(child)
    }
    rows(popup)
    const picked = options.find(option => option.texts.includes(itemName)
      && option.texts.some(text => text.startsWith('用 1 张')))
    popup.name = 'ArmyQueueProbeSpeedupPicker'
    return { options, picked: picked?.name ?? null }
  }, itemBefore?.name ?? '')
  if (speedChoice?.picked) {
    await page.screenshot({ path: path.join(OUT, '17-army-queue-speedup-choice.png') })
    choicePoint = await clickNodeViaCocos(page,
      { name: speedChoice.picked, within: 'ArmyQueueProbeSpeedupPicker' })
  }
  await page.evaluate(() => {
    const walk = node => {
      if (node.name === 'ArmyQueueProbeSpeedupPicker') node.name = 'ChoiceOverlay'
      for (const child of node.children) walk(child)
    }
    walk(window.cc.director.getScene())
  })
}
const speedResponse = await speedResponsePromise
await page.waitForTimeout(1500)
const afterSpeed = await rowReadout()
const afterText = afterSpeed.active?.countdown ?? null
const armyAfterSpeed = await get('/army/list', HEAD)
const unitAfterSpeed = armyAfterSpeed.data?.units?.find(unit => unit.unitId === trainable.unitId)
const bagAfterSpeed = await get('/bag/list', HEAD)
const itemAfterSpeed = bagAfterSpeed.data?.items?.find(item => item.itemId === TRAIN_ITEM_ID)
console.log(`[army-queue] 点「加速」→ /army/* 新增 [${armyPosts.slice(postsBeforeSpeed).join('、') || '(无)'}]`
  + ` 倒计时：${before ?? '(无)'} → ${afterText ?? '(无)'}`)
console.log(`[army-queue] 真实训练令选项=${JSON.stringify(speedChoice)} 点击=${JSON.stringify(choicePoint)}`)
console.log(`[army-queue] 加速回执=${JSON.stringify(speedResponse)} 权威finishAt=${unitBefore?.finishAt}→${unitAfterSpeed?.finishAt}`
  + ` 库存=${itemBefore?.count ?? 0}→${itemAfterSpeed?.count ?? 0}`)

// 再点「取消」：训练态应当消失（取消不返还到 UI 文本里，但队列要腾出来）
const postsBeforeCancel = armyPosts.length
const cancelResponsePromise = captureArmyResponse('cancel')
const cancelPoint = await clickNode('CancelTrainButton')
const cancelResponse = await cancelResponsePromise
if (cancelPoint.clicked) {
  await page.waitForTimeout(2000)
}
const afterCancel = await rowReadout()
const armyAfterCancel = await get('/army/list', HEAD)
const unitAfterCancel = armyAfterCancel.data?.units?.find(unit => unit.unitId === trainable.unitId)
await page.screenshot({ path: SHOT })

console.log(`[army-queue] 点「取消」→ /army/* 新增 [${armyPosts.slice(postsBeforeCancel).join('、') || '(无)'}]`
  + ` 取消后训练中的行=${afterCancel.active === null ? '(没有了)' : `加速键=${afterCancel.active.speed} 取消键=${afterCancel.active.cancel}`}`)
console.log(`[army-queue] 取消回执=${JSON.stringify(cancelResponse)} 权威training=${unitAfterCancel?.training}`)
console.log(`[army-queue] 截图：${SHOT}`)
console.log(`[army-queue] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

const failures = []
if (!speedPoint.clicked) failures.push(`加速按钮点击坐标不可信：${speedPoint.reason}`)
if (!cancelPoint.clicked) failures.push(`取消按钮点击坐标不可信：${cancelPoint.reason}`)
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
if (!choicePoint.clicked || speedChoice?.options?.length !== 1) {
  failures.push(`没有真实选择唯一的「用 1 张」训练令：${JSON.stringify(speedChoice)} ${choicePoint.reason ?? ''}`)
}
if (speedResponse.status !== 200 || speedResponse.code !== 0
    || speedResponse.request?.unitId !== trainable.unitId || speedResponse.request?.itemId !== TRAIN_ITEM_ID) {
  failures.push(`加速未以正确兵种与道具业务成功：${JSON.stringify(speedResponse)}`)
}
const reducedSeconds = speedResponse.data?.reducedSeconds
if (!Number.isSafeInteger(reducedSeconds) || reducedSeconds <= 0
    || unitBefore?.finishAt - speedResponse.data?.finishAt !== reducedSeconds * 1000
    || unitAfterSpeed?.finishAt !== speedResponse.data?.finishAt
    || unitAfterSpeed?.training !== TRAIN_COUNT) {
  failures.push(`权威同批次finishAt未按回执提前：${unitBefore?.finishAt}→${unitAfterSpeed?.finishAt}，回执=${JSON.stringify(speedResponse.data)}`)
}
if (itemBefore?.effectKind !== 'REDUCE_TRAIN_SECONDS' || itemBefore?.count !== 2
    || (itemAfterSpeed?.count ?? 0) !== itemBefore.count - 1) {
  failures.push(`训练令未恰好消费一张：${itemBefore?.count ?? 0}→${itemAfterSpeed?.count ?? 0}`)
}
if (before === null || afterText === null) {
  failures.push(`训练行的倒计时读不到（${before ?? '(无)'} → ${afterText ?? '(无)'}）—— 这条判据走不到，不许当绿`)
} else if (before === afterText) {
  failures.push(`点了「加速」但倒计时没变（${before}）—— 请求出去了而状态没跟着走`)
}
if (!armyPosts.includes('cancel')) {
  failures.push('点了「取消」没有发出 /army/cancel —— 按钮没接上')
}
if (cancelResponse.status !== 200 || cancelResponse.code !== 0
    || cancelResponse.request?.unitId !== trainable.unitId || unitAfterCancel?.training !== 0) {
  failures.push(`取消未业务成功并清空权威训练状态：${JSON.stringify(cancelResponse)} training=${unitAfterCancel?.training}`)
}
if (afterCancel.active !== null) {
  failures.push('取消之后「取消」键还在 —— 那一行已经不在训练中了，按钮该收起来')
}
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}
if (failures.length > 0) {
  console.error(`[army-queue] 判据失败：${failures.join('；')}`)
  process.exitCode = 1
  return
}
console.log('[army-queue] 全绿：真选一张训练令、业务成功、权威finishAt按回执提前且库存减一；真取消业务成功且训练行收起')
}
try {
  browser = await chromium.launch({ headless: true })
  await verifyArmyQueue()
} finally {
  try {
    await browser?.close()
  } finally {
    await preview.close()
  }
}
