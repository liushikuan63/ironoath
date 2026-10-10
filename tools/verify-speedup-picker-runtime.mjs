#!/usr/bin/env node
/**
 * 职责：把「加速道具的目标选择器真的画得出来、字不被裁」变成能失败的判据（#316 的运行侧欠账）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 PICKER_PORT（默认 8196，同机并发时换一个）
 *   BACKEND_ORIGIN=http://localhost:8199 PICKER_PORT=8196 node tools/verify-speedup-picker-runtime.mjs
 *
 * <p><b>为什么要单独跑这一份</b>：#316 改的是 `ChoiceOverlay.createRow` 的版式（色带与两个标签
 * 改成按面板宽排），当时只在军队「队列」菜单上量过。背包这个使用者是**另一条入口**
 * （背包行上的「使用」→ `useItem(needsTarget)` → 目标选择器），不同入口、同一份版式，
 * 只量一处就等于没量另一处。
 *
 * <p><b>它盯的几件事</b>：① 点「使用」真的弹出选择器（不是直接吃掉道具）；
 * ② 选择器里那一条的目标名来自服务端下发的队列（不是写死的字）；
 * ③ **标题盒子是一行高**（#316 修的就是标签没盒子被裁成两行）；
 * ④ 色带宽度不超过面板、也不超过屏幕（`width` 参数真被吃到，不是写死 700）；
 * ⑤ **道具行不压在弹层上面** —— 这一条是本次真跑抓到的缺陷，而且是**两层**：
 * 弹层在 `onLoad` 建、行在每次渲染才 addChild，行排在后面就把「选择加速目标」的标题盖掉；
 * 而既有的 `ChoiceOverlay.raise()` 用的是 `parent.addChild(自己)`，在 3.8.7 里对同一父节点
 * 是空操作（实测 children 为 A,B 时再 addChild(A) 仍是 A,B），抬层一直没生效。
 * 修法：`raise()` 改 `setSiblingIndex(末位)`，`show()` 抬一次，父节点 `child-added` 时
 * 弹层自己顶回末位 —— 八个宿主不需要各自记得抬（宿主侧那一行删掉后本探针仍全绿，就是判据）。
 *
 * <p><b>不验的</b>：真的把道具用掉、队列真的少多少时间 —— 那是服务端 `ItemAppService`
 * 与 `BagEndpointTest` 的 speedUpItemReducesRemainingByConfiguredSeconds 那一头；这里的道具与训练队列都是**读接口夹具**（dev 新号两样都没有）。
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[speedup-picker] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.PICKER_PORT ?? 8196)
const OUT = path.resolve(process.cwd(), 'client/build/speedup-picker-verify')
mkdirSync(OUT, { recursive: true })

let pass = 0
let fail = 0
const check = (msg, actual, expected) => {
  if (actual === expected) {
    pass += 1
    console.log(`  PASS  ${msg}（${String(actual)}）`)
  } else {
    fail += 1
    console.log(`  FAIL  ${msg}：期望 ${String(expected)}，实际 ${JSON.stringify(actual)}`)
  }
}
const checkTrue = (msg, actual) => check(msg, actual, true)

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 加速目标选择器运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `picker-runtime-${Date.now()}`)
const cors = (request) => ({
  'access-control-allow-origin': request.headers()['origin'] ?? '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
})
const reply = async (route, data) => route.fulfill({
  status: 200,
  headers: { ...cors(route.request()), 'content-type': 'application/json' },
  body: JSON.stringify({ code: 0, msg: '成功', data, serverNow: Date.now() }),
})
const now = Date.now()
// 一个加速道具 + 一口正在训练的兵：选择器就该有这一条可选目标
await context.route('**/bag/list*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, {
    items: [{
      itemId: 'item_speedup_1h', name: '加速道具（1 小时）', type: 'SPEEDUP', rarity: 'RARE',
      obtainFrom: null, count: 3, stackMax: 99, sortKey: 10, effectKind: 'SPEEDUP',
      effectTarget: null, effectValue: 3600, description: null, expiresAt: null,
      usableIn: ['CITY', 'ARMY'], artKey: null, opened: 0,
    }, {
      itemId: 'item_chest_basic', name: '基础宝箱', type: 'CHEST', rarity: 'RARE',
      obtainFrom: null, count: 12, stackMax: 99, sortKey: 11, effectKind: 'CHEST',
      effectTarget: null, effectValue: 0, description: null, expiresAt: null,
      usableIn: ['CITY'], artKey: null, opened: 0,
    }],
    capacityUsed: 2, capacityMax: 100, serverNow: now,
  })
})
const opened = []
await context.route('**/item/openBatch*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  opened.push(JSON.parse(route.request().postData() ?? '{}'))
  await reply(route, {
    consumed: 5,
    results: [{ type: 'RESOURCE', id: 'WOOD', count: 5000, name: '木材' },
      { type: 'HERO_FRAGMENT', id: 'hero_wei', count: 3, name: '卫无咎碎片' }],
    overflow: [{ type: 'ITEM', id: 'item_speedup_1h', count: 2, name: '加速道具（1 小时）' }],
    mailId: 'm-1', seed: 42, serverNow: Date.now(),
  })
})
await context.route('**/army/list*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, {
    units: [{
      unitId: 'unit_infantry_t1', name: '重步', type: 'INFANTRY', tier: 1, count: 500,
      wounded: 0, training: 30, finishAt: now + 600_000, remainingSeconds: 600,
      unlocked: true, unlockHint: null, trainTimeSec: 10,
      trainCost: [{ type: 'FOOD', amount: 20 }],
    }],
    troopCap: 1000, troopsInUse: 0, trainingInUse: 30, queueSlots: 1, queueSlotsMax: 2,
    hospital: { capacity: 0, used: 0, treating: false, treatFinishAt: null,
      treatRemainingSeconds: 0, treatCostRatio: 0 },
    autoTrain: { enabled: false, unitId: 'none', batchCount: 1, batchBudget: 0, targetCount: 0,
      stopReason: null },
    serverNow: now,
  })
})
const used = []
await context.route('**/item/use*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  used.push(JSON.parse(route.request().postData() ?? '{}'))
  await reply(route, { itemId: 'item_speedup_1h', remaining: 2, savedSeconds: 3600,
    targetId: 'unit_infantry_t1', serverNow: Date.now() })
})

const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'bag')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
// 自检：产物里那两处写死的后端地址有没有真的被改写成本轮要打的那棵。
// 漏了这一句，传错变量名就是"打到另一台机器上读数"，红得像是产品缺陷（台账 #371）。
preview.assertRewritten()
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await hideGuideOverlay(page)


/** 在背包面板子树里按节点名找一颗并点它（面板初始停在「资源明细」页，得先切到「背包」） */
const tapInBag = (name) => `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('bag')
  if (panel === undefined || panel === null || !panel.active) return false
  let hit = null
  const walk = (node) => {
    if (node.name === ${JSON.stringify(name)} && node.active) hit = node
    for (const child of node.children) if (child.active) walk(child)
  }
  walk(panel)
  if (hit === null) return false
  hit.emit(hit.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
  return true
})()`

/** 只读背包面板子树里激活着的那些节点：整棵树乱找会把别的面板同名字段读进来 */
const PICKER = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('bag')
  if (!panel || !panel.active) return { found: false }
  let overlay = null
  const findOverlay = (node) => {
    if (node.name === 'ChoiceOverlay' && node.active) overlay = node
    for (const child of node.children) if (child.active) findOverlay(child)
  }
  findOverlay(panel)
  const useRow = (() => {
    let hit = null
    const walk = (node) => {
      if (node.name === 'UseButton' && node.active) hit = node
      for (const child of node.children) if (child.active) walk(child)
    }
    walk(panel)
    return hit
  })()
  const pageName = (() => {
    let hit = null
    const walk = (node) => {
      if (node.name === 'Header' && node.active) {
        hit = node.getComponent('cc.Label')?.string ?? ''
      }
      for (const child of node.children) if (child.active) walk(child)
    }
    walk(panel)
    return hit
  })()
  if (overlay === null) {
    return { found: true, pickerOpen: false, useButton: useRow !== null, pageName }
  }
  const visibleW = window.cc.view.getVisibleSize().width
  const panelW = overlay.getComponent('cc.UITransform').width
  // 弹层是 onLoad 建的、道具行是渲染时才 addChild 的：行排在后面就会压在弹层上面
  // （军队那一格同族缺陷，当时是靠把弹层抬到 Game 层修的）
  let rowsAbove = 0
  for (const child of panel.children) {
    if (child.name === 'Row' && child.active && child.getSiblingIndex() > overlay.getSiblingIndex()) {
      rowsAbove += 1
    }
  }
  const row = [...(overlay.children || []), ...(overlay.getChildByName('DialogContent')?.getChildByName('DialogContentContent')?.children ?? [])].find((c) => c.name === 'Choice-0')
  const labels = (row?.children || []).filter((c) => c.getComponent('cc.Label') !== null)
    .map((c) => ({
      name: c.name,
      text: c.getComponent('cc.Label').string,
      w: c.getComponent('cc.UITransform').width,
      h: c.getComponent('cc.UITransform').height,
    }))
  const band = row?.getComponent('cc.UITransform')
  return {
    found: true, pickerOpen: true, useButton: useRow !== null, pageName, visibleW,
    panelW, bandW: band?.width ?? null, rowsAbove,
    title: labels.find((l) => l.name === 'Label' && l.text.length > 0) ?? null,
    texts: labels.map((l) => l.text),
  }
})()`

/** 只读真实加速选择器标题：固定两帧完成绘制，不按读数逢绿停。 */
async function readSpeedupTitleAfterDraw() {
  const cc = window.cc
  const director = cc?.director
  const event = cc?.Director?.EVENT_AFTER_DRAW
  if (!event || typeof director?.on !== 'function' || typeof director?.off !== 'function'
      || typeof director?.getTotalFrames !== 'function') {
    return { found: false, reason: 'Missing after-draw API', frameIds: [], samples: [] }
  }
  const readSnapshot = () => {
    const scene = director.getScene()
    const game = scene?.getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.getChildByName('bag')
    if (!panel?.activeInHierarchy) throw new Error('Missing active bag panel')
    const owner = panel.getComponent('BagPanelView')?.targetPicker
    const overlays = []
    const rows = []
    const walk = (node, output, name) => {
      if (!node?.activeInHierarchy) return
      if (node.name === name) output.push(node)
      for (const child of node.children) walk(child, output, name)
    }
    walk(panel, overlays, 'ChoiceOverlay')
    if (overlays.length !== 1 || owner?.node !== overlays[0]) {
      throw new Error('Missing or duplicate active targetPicker ChoiceOverlay: ' + overlays.length)
    }
    const overlay = overlays[0]
    walk(overlay, rows, 'Choice-0')
    const row = owner.optionNodes?.[0]
    const label = owner.optionTitleLabels?.[0]
    if (rows.length !== 1 || rows[0] !== row || !label
        || owner.optionNodes.filter(value => value === row).length !== 1
        || owner.optionTitleLabels.filter(value => value === label).length !== 1
        || owner.optionDetailLabels?.[0] === label
        || label.node?.parent !== row || !label.node.activeInHierarchy
        || row.children.filter(node => node.getComponent('cc.Label') === label).length !== 1
        || label.node.getComponent('cc.Label') !== label) {
      throw new Error('Missing, duplicate or mismatched real Choice-0 title refs')
    }
    const ui = label.node.getComponent('cc.UITransform')
    const rowUi = row.getComponent('cc.UITransform')
    if (!ui || !rowUi || ui.width <= 0 || rowUi.width <= 0 || rowUi.height <= 0) {
      throw new Error('Missing positive title/row UITransform')
    }
    const cameras = scene.getComponentsInChildren('cc.Camera')
    const drawable = label.enabled === true && label.node._uiProps?.uiComp === label
      && cameras.some(camera => camera.enabled && camera.node.activeInHierarchy
        && (camera.visibility & label.node.layer) !== 0)
    if (!drawable) throw new Error('Title is not the active first Label renderer with a camera')
    const ownWorldBox = transform => {
      const points = [0, 1].flatMap(x => [0, 1].map(y => transform.convertToWorldSpaceAR(
        new cc.Vec3((x - transform.anchorX) * transform.width,
          (y - transform.anchorY) * transform.height, 0))))
      const box = { left: Math.min(...points.map(point => point.x)),
        right: Math.max(...points.map(point => point.x)),
        bottom: Math.min(...points.map(point => point.y)), top: Math.max(...points.map(point => point.y)) }
      if (!Object.values(box).every(Number.isFinite)) throw new Error('Non-finite title/row world box')
      return box
    }
    const titleBox = ownWorldBox(ui), rowBox = ownWorldBox(rowUi)
    const context = document.createElement('canvas').getContext('2d')
    if (!context || !label.useSystemFont || !label.fontFamily || typeof label.string !== 'string') {
      throw new Error('Missing actual system-font canvas measurement')
    }
    context.font = [label.isItalic ? 'italic' : '', label.isBold ? 'bold' : '',
      label.fontSize + 'px', label.fontFamily].filter(Boolean).join(' ')
    const naturalWidth = context.measureText(label.string).width
    return { text: label.string, fontSize: label.fontSize, actualFontSize: label.actualFontSize,
      titleW: ui.width, titleH: ui.height, measuredFont: context.font, naturalWidth,
      titleBox, rowBox, drawable,
      inRow: titleBox.left >= rowBox.left - 0.5 && titleBox.right <= rowBox.right + 0.5
        && titleBox.bottom >= rowBox.bottom - 0.5 && titleBox.top <= rowBox.top + 0.5 }
  }
  return new Promise(resolve => {
    const frameIds = [], samples = []
    let timer = null, done = false
    const finish = result => {
      if (done) return
      done = true
      clearTimeout(timer)
      try { director.off(event, onDraw) } catch (error) {
        result = { found: false, reason: 'After-draw listener cleanup failed: ' + String(error) }
      }
      resolve({ frameIds, samples, ...result })
    }
    const onDraw = () => {
      try {
        const frame = director.getTotalFrames()
        if (!Number.isFinite(frame) || (frameIds.length && frame !== frameIds[0] + 1)) {
          throw new Error('Missing consecutive completed draw frames')
        }
        frameIds.push(frame)
        samples.push({ frame, title: readSnapshot() })
        if (samples.length === 2) {
          const stable = JSON.stringify(samples[0].title) === JSON.stringify(samples[1].title)
          finish(stable ? { found: true, drawn: true, stable, title: samples[1].title }
            : { found: false, stable, reason: 'Title snapshot changed across two completed draw frames' })
        }
      } catch (error) { finish({ found: false, reason: 'After-draw title capture failed: ' + String(error) }) }
    }
    timer = setTimeout(() => finish({ found: false, reason: 'Timed out before two completed draw frames' }), 2000)
    try { director.on(event, onDraw) } catch (error) {
      finish({ found: false, reason: 'After-draw listener registration failed: ' + String(error) })
    }
  })
}

function speedupTitleIssues(evidence) {
  const issues = []
  const title = evidence?.title
  if (evidence?.found !== true) issues.push(evidence?.reason ?? 'Missing real title evidence')
  if (evidence?.drawn !== true || evidence?.stable !== true || evidence?.frameIds?.length !== 2
      || !evidence.frameIds.every(Number.isFinite) || evidence.frameIds[1] !== evidence.frameIds[0] + 1
      || evidence?.samples?.length !== 2) issues.push('Missing fixed two consecutive stable completed draw frames')
  if (!title || title.fontSize !== 17 || title.actualFontSize !== 17 || title.titleH !== 27) {
    issues.push('Title must retain actual 17 font in the 27-high slot')
  }
  if (!title || !Number.isFinite(title.naturalWidth) || title.naturalWidth <= 0
      || !Number.isFinite(title.titleW) || title.naturalWidth > title.titleW + 0.5
      || /[\r\n]/.test(title.text)) issues.push('Title must fit its natural single-line width without a line break')
  if (title?.drawable !== true || title?.inRow !== true) issues.push('Actual title renderer must be fully within its own row')
  return issues
}

// 面板初始页签由 /resource/detail 决定（attachResources 把 tab 钉回「资源明细」），
// 所以要先按「背包」页签才看得到道具行
let state = null
for (let i = 0; i < 40; i += 1) {
  await page.waitForTimeout(500)
  await page.evaluate(tapInBag('Tab_bag'))
  state = await page.evaluate(PICKER)
  if (state?.found && state.useButton === true) break
}
check('背包页激活且道具行上的「使用」画出来了', state?.useButton, true)
checkTrue('页签确实切到了「背包」（表头是容量账，不是「资源产出明细」）',
  (state?.pageName ?? '').includes('背包'))

checkTrue('按得到那一颗「使用」', await page.evaluate(tapInBag('UseButton')))
await page.waitForTimeout(700)
state = await page.evaluate(PICKER)
check('点使用先弹目标选择器（不直接吃掉道具）', state?.pickerOpen, true)
checkTrue('选择器里那条目标写的是服务端下发的队列名（不是写死的字）',
  (state?.texts ?? []).some((t) => t.includes('重步')))
const titleDraw = await page.evaluate(readSpeedupTitleAfterDraw)
const titleIssues = speedupTitleIssues(titleDraw)
console.log('  加速选择器标题后绘：' + JSON.stringify({ ...titleDraw, issues: titleIssues }))
writeFileSync(path.join(OUT, 'speedup-title-draw-evidence.json'), JSON.stringify({ ...titleDraw, issues: titleIssues }, null, 2))
checkTrue('标题实绘保持17号单行字（27高槽、自然宽在自身盒内且整盒在行内）', titleIssues.length === 0)
checkTrue('色带与标题都不宽过面板（width 参数真被吃到）',
  state?.bandW <= state?.panelW && state?.title?.w <= state?.panelW)
checkTrue('色带不宽过屏幕（背包弹层写死 760，窄屏会溢出）',
  state?.bandW <= state?.visibleW)
check('道具行没有压在弹层上面（同军队那格的遮挡缺陷不在这复现）',
  state?.rowsAbove, 0)
await page.screenshot({ path: path.join(OUT, 'speedup-picker.png') })
console.log(`  截图：${path.join(OUT, 'speedup-picker.png')}`)

check('没选目标就不发 /item/use（选了才吃道具）', used.length, 0)

// ---------- 宝箱那一行：先问「开几个」，选完才发 /item/openBatch ----------

const CHEST = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('bag')
  let overlay = null
  const find = (n) => {
    if (n.name === 'ChoiceOverlay' && n.active) overlay = n
    for (const c of n.children) if (c.active) find(c)
  }
  find(panel)
  const row = (panel?.children || []).find((c) => c.name === 'Row' && c.active
    && (c.children || []).some((k) => (k.getComponent('cc.Label')?.string ?? '').includes('宝箱')))
  const texts = overlay === null ? [] : (() => {
    const out = []
    const walk = (n) => {
      const t = n.getComponent('cc.Label')?.string ?? ''
      if (t.length > 0) out.push(t)
      for (const c of n.children) walk(c)
    }
    walk(overlay)
    return out
  })()
  let cover = -1
  if (overlay !== null && overlay.parent !== null) {
    const at = overlay.getSiblingIndex()
    cover = 0
    for (const sib of overlay.parent.children) {
      if (sib.active && sib.getSiblingIndex() > at) cover += 1
    }
  }
  return { hasRow: row !== undefined && row !== null, pickerOpen: overlay !== null, texts, cover }
})()`

// 先把上面那个「选择加速目标」收掉：探针是按节点名直接 emit 的，绕过了命中测试，
// 不收的话下一段截图里会留着上一层弹层（看着像宝箱的选择器还开着）
await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('bag')
  let hit = null
  const walk = (n) => {
    if (n.name === 'ChoiceCancel' && n.active) hit = n
    for (const c of n.children) if (c.active) walk(c)
  }
  walk(panel)
  if (hit !== null) hit.emit(hit.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
  return hit !== null
})()`)
await page.waitForTimeout(400)

// #327 之前这里直接调 `selectBagPage('CHEST')` 绕过去了 —— 那等于把"玩家到不了这一页"当成前提。
// 现在按真实路径：类型页签是代码建出来的节点，按它切到「宝箱」页
const pageTapped = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('bag')
  let hit = null
  const walk = (n) => {
    if (n.name === 'Page_CHEST' && n.active) hit = n
    for (const c of n.children) if (c.active) walk(c)
  }
  walk(panel)
  if (hit === null) return false
  hit.emit(hit.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
  return true
})()`)
checkTrue('类型页签里有「宝箱」那一颗，按得动', pageTapped)
await page.waitForTimeout(500)
const strip = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('bag')
  let holder = null
  const find = (n) => { if (n.name === 'BagPages') holder = n; for (const c of n.children) find(c) }
  find(panel)
  if (holder === null) return { found: false }
  const caps = []
  const walk = (n) => {
    if (n.name === 'Caption' && n.active) caps.push(n.getComponent('cc.Label')?.string ?? '')
    for (const c of n.children) if (c.active) walk(c)
  }
  walk(holder)
  return { found: true, active: holder.active, caps }
})()`)
check('类型页签那一条在（两种道具 ⇒ 该出现）', strip?.active, true)
checkTrue('每颗都带数量，玩家不点也知道这一类有几个：' + JSON.stringify(strip?.caps ?? []),
  strip !== null && strip.caps.some((c) => c.includes('宝箱') && /\d/.test(c)))

const chestTapped = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('bag')
  const row = (panel?.children || []).find((c) => c.name === 'Row' && c.active
    && (c.children || []).some((k) => (k.getComponent('cc.Label')?.string ?? '').includes('宝箱')))
  const btn = (row?.children || []).find((c) => c.name === 'UseButton')
  if (btn === undefined) return false
  btn.emit(btn.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
  return true
})()`)
checkTrue('宝箱那一行上有可点的键', chestTapped)
await page.waitForTimeout(700)
const chest = await page.evaluate(CHEST)
checkTrue('按「使用」先问「开几个」（服务端对宝箱的 /item/use 是直接拒绝的）',
  chest?.pickerOpen === true && chest?.texts.some((t) => t === '开几个'))
checkTrue('档位按手里有几个给：1 / 5 / 10 / 全开 12', chest !== null
  && chest.texts.some((t) => t === '开 5 个') && chest.texts.some((t) => t === '全开 12 个'))
check('「开几个」这个弹层也没被行盖住', chest?.cover, 0)
check('问「开几个」之前不发 /item/use', used.length, 0)
await page.screenshot({ path: path.join(OUT, 'chest-count-picker.png') })
console.log(`  截图：${path.join(OUT, 'chest-count-picker.png')}`)

await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('bag')
  let hit = null
  const walk = (n) => {
    if (n.name.startsWith('Choice-') && n.active
      && (n.children || []).some((k) => (k.getComponent('cc.Label')?.string ?? '') === '开 5 个')) hit = n
    for (const c of n.children) if (c.active) walk(c)
  }
  walk(panel)
  if (hit !== null) hit.emit(hit.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
  return hit !== null
})()`)
await page.waitForTimeout(1500)
check('选「开 5 个」恰好发一条 /item/openBatch', opened.length, 1)
check('请求体里 count=5', opened[0]?.count, 5)
checkTrue('开箱带幂等键（断网重放会让玩家白丢一箱）',
  typeof opened[0]?.requestId === 'string' && opened[0].requestId.length > 0)
check('宝箱那条不走 /item/use', used.length, 0)
const receipt = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('bag')
  let hit = ''
  const walk = (n) => { if (n.name === 'Warning') hit = n.getComponent('cc.Label')?.string ?? ''; for (const c of n.children) walk(c) }
  walk(panel)
  return hit
})()`)
checkTrue('回执把"开了几个 / 开出什么 / 装不下的已转邮件"三件都说清：' + receipt,
  receipt.includes('开了 5 个') && receipt.includes('卫无咎碎片 ×3')
    && receipt.includes('加速道具（1 小时） ×2 装不下，已转邮件'))
await page.screenshot({ path: path.join(OUT, 'chest-receipt.png') })
console.log(`  截图：${path.join(OUT, 'chest-receipt.png')}`)

check('运行期零 error（页面级报错）', errors.length, 0)
if (errors.length > 0) {
  for (const message of errors.slice(0, 3)) console.log(`    error: ${message.slice(0, 160)}`)
}

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
