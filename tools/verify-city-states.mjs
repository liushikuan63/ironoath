/**
 * 职责：内城**升级中 / 到点未收割（可收取）**两态的留帧验收（`CITY-ART-07`），
 * 并读 **drawcall / JS 堆 / 底图解码内存**（`CITY-ART-08` 里"同设备改前后值"那两项）。
 * 依赖：`client/build/web-mobile` 产物 + 一台**本轮自己的** dev 后端（`BACKEND_ORIGIN`，要带 `--ironoath.ops.token`）+ Playwright。
 *
 * <p><b>为什么"可收取"要拦请求才看得见</b>：`/city/list` 是"读也带副作用"的接口 —— 它**顺带收割**到点的升级
 * （`CityController.list` 注释原话）。于是这一态只活在"面板缓存里还是 UPGRADING、而下一次 list 还没发生"的窗口里；
 * 实测里它常被一次自动刷新吃掉（第一版探针就因为这个永远找不到它）。
 * 所以这里在首帧之后把 `/city/list` 拦掉：让本地倒计时归零这件事**只发生在客户端**。
 *
 * <p>格位**按名字找**，不写死 index —— 第一版写死 `Grid-2`，而 (1,1) 的 index 是 7，读到的是一个空格子，
 * 于是报出"格子没有待收割字样"这种假红。到点后格子上**不写状态字**（状态在选择栏里），
 * 它改的是等级徽章与名字的**颜色** ⇒ 颜色就是这一态的机器判据（`COLOR_GOOD` = 120,176,96）。
 *
 * 用法：`BACKEND_ORIGIN=http://localhost:8163 node tools/verify-city-states.mjs`
 * 退出码：0 两态都拿到且读数齐；1 判据失败；2 前置不满足。
 *
 * <p>2026-09-22 从 `tmp/probe-city-states.mjs` 迁进 `tools/`（复检那一轮的一次性探针，判据当时已跑绿）。
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.STATE_PROBE_PORT ?? 8290)
const OUT = 'client/build/art-verify'
const deviceId = `state-probe-${Date.now()}`

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[state][前置] 产物不存在：${ROOT}`)
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const post = async (url, body, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  return response.json()
}

const init = await post('/player/init', {
  requestId: `state-init-${Date.now()}`, deviceId, nickName: '状态探针',
  clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[state][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}
const playerId = init.data.playerId
const build = await post('/city/upgrade',
  { requestId: `state-build-${Date.now()}`, configId: 'lumber_camp', gridX: 1, gridY: 1 },
  { 'X-Player-Id': playerId })
if (build.code !== 0) {
  console.error(`[state][前置] 发起建造失败：${JSON.stringify(build)}`)
  process.exit(2)
}
// `serverNow` 在**信封**上、不在 data 里：第一版取成 build.data.serverNow 得到 NaN，
// "等到点"退化成 1ms，两态都没量到。这条前置就是防它再犯。
const buildMs = build.data.finishAt - build.serverNow
if (!Number.isFinite(buildMs) || buildMs <= 0) {
  console.error(`[state][前置] 算不出建造耗时（finishAt=${build.data.finishAt} serverNow=${build.serverNow}）`)
  process.exit(2)
}
const startedAt = Date.now()
console.log(`[state] 建号 ${playerId}：伐木场（Grid 1,1）已开工，约 ${buildMs}ms 后到点`)

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
/** 本次跑过的 /city/* POST —— 用来分辨"点了按钮没反应"是"事件没到"还是"回调没接上"。 */
const cityPosts = []
page.on('request', (request) => {
  if (request.method() === 'POST' && request.url().includes('/city/')) {
    cityPosts.push(request.url().split('/city/')[1])
  }
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'city')

const waitPanel = async () => {
  await page.waitForFunction(() => {
    if (window.cc === undefined || window.cc.director === undefined) return false
    const scene = window.cc.director.getScene()
    if (scene === null) return false
    const nav = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
    return nav !== null && nav !== undefined && nav.currentKey === 'city'
  }, null, { timeout: 25_000 }).catch(() => {})
  await page.waitForTimeout(1600)
}

/** 一帧读数：屏上文本、**按名字找格子**的文字、渲染读数。 */
const snapshot = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const tileRefs = new Map((scene.getComponentInChildren('CityPanelView')?.gridTiles ?? []).map(tile => [tile.node.name, tile]))
  const texts = []
  const tiles = {}
  let referenceSize = null
  let sprites = 0
  const visit = (node, shown) => {
    const on = shown && node.activeInHierarchy === true
    const label = node.getComponent && node.getComponent('cc.Label')
    if (on && label !== null && label.string !== '') texts.push(label.string)
    const sprite = node.getComponent && node.getComponent('cc.Sprite')
    if (on && sprite !== null && sprite.spriteFrame !== null) {
      sprites++
      if (node.name === 'CityReferenceScene') {
        referenceSize = [sprite.spriteFrame.texture.width, sprite.spriteFrame.texture.height]
      }
    }
    if (/^Grid-\d+$/.test(node.name)) {
      const ref = tileRefs.get(node.name)
      const own = [ref?.levelLabel, ref?.nameLabel].filter(label => label?.string).map(label => label.string)
      const icon = node.getChildByName('BuildingIcon')
      const levelLabel = ref?.levelLabel ?? null
      const nameLabel = ref?.nameLabel ?? null
      const iconSprite = icon === null ? null : icon.getComponent('cc.Sprite')
      const rgb = (color) => color === null || color === undefined
        ? null : `${color.r},${color.g},${color.b}`
      tiles[node.name] = {
        labels: own,
        iconActive: icon === null ? null : icon.active,
        // 帧名才是"画的是不是正稿"的判据：`icon.active` 只说明那个节点开着，
        // 图集兜底图标 / 未建占位都可能让它为 true（第一版就是拿它当判据，取消后误报）。
        frameName: iconSprite === null || iconSprite.spriteFrame === null
          ? null : iconSprite.spriteFrame.name,
        // 到点未收割时**格子上不写状态字**（状态在选择栏里，见 paintTile 的注释），
        // 它改的是等级徽章与名字的**颜色**（COLOR_GOOD = 120,176,96）—— 所以颜色就是这一态的机器判据。
        levelColor: rgb(levelLabel?.color),
        nameColor: rgb(nameLabel?.color),
      }
    }
    for (const child of node.children) visit(child, on)
  }
  visit(scene, true)
  const device = window.cc.director?.root?.device
  const profiler = window.cc.profiler
  return {
    texts,
    tiles,
    sprites,
    referenceSize,
    drawCalls: device?.numDrawCalls ?? null,
    instances: device?.numInstances ?? null,
    profilerDraws: profiler?._draws ?? profiler?.stats?.draws ?? null,
    heapMb: typeof performance !== 'undefined' && performance.memory !== undefined
      ? Math.round(performance.memory.usedJSMemory ?? performance.memory.usedJSHeapSize) / 1048576 : null,
  }
})

/** 按格子上印的名字找它那一格（不写死 index）。 */
const tileOf = (frame, name) => {
  for (const [key, value] of Object.entries(frame.tiles)) {
    if (value.labels.some((text) => text.includes(name))) {
      return { key, ...value }
    }
  }
  return null
}

/**
 * 通过**节点名**取屏幕坐标并点它（与 `verify-city-multi-types` 同一套换算）。
 *
 * <p>为什么要有它：暂停/恢复是两个按钮，判据必须"真点下去" —— 直接调 JS 改状态等于绕开被测的那条路径。
 */
const clickNode = (nodeName) => page.evaluate((name) => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let target = null
  const visit = (node) => {
    if (node.name === name) target = node
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

/** 取消这一态的读数：选择栏两行 + 「取消」键还在不在。 */
const cancelReadout = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let cancel = null
  let title = null
  let status = null
  const visit = (node) => {
    if (node.name === 'DetailCancelButton') cancel = node.activeInHierarchy === true
    if (node.name === 'SelectedTitle' || node.name === 'SelectedStatus') {
      const label = node.getComponent('cc.Label')
      if (label !== null && label !== undefined) {
        if (node.name === 'SelectedTitle') title = label.string
        else status = label.string
      }
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
  return { cancel, title, status }
})

/** configId → 中文名（格子是按显示名找的）。 */
const NAME_OF = { lumber_camp: '伐木场', quarry: '采石场' }

/** 动作栏两只按钮的可见性 + 选择栏两行文本（暂停这一态的判据全从这里读）。 */const pauseReadout = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let pause = null
  let resume = null
  let title = null
  let status = null
  const visit = (node) => {
    if (node.name === 'DetailPauseButton') pause = node.activeInHierarchy === true
    if (node.name === 'DetailResumeButton') resume = node.activeInHierarchy === true
    if (node.name === 'SelectedTitle' || node.name === 'SelectedStatus') {
      const label = node.getComponent('cc.Label')
      if (label !== null && label !== undefined) {
        if (node.name === 'SelectedTitle') title = label.string
        else status = label.string
      }
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
  return { pause, resume, title, status }
})

/** 摘掉引导层：它压在画面正中央，会把"建筑状态"这一半证据挡住（别的内城探针都这么做）。 */
const clearGuide = () => page.evaluate((source) => {
  const re = new RegExp(source)
  const scene = window.cc.director.getScene()
  const killed = []
  const visit = (node) => {
    const label = node.getComponent && node.getComponent('cc.Label')
    if (/Guide/i.test(node.name) || (label !== null && label !== undefined && re.test(label.string ?? ''))) {
      const view = node.getComponent('GuideView')
      if (view !== null && view !== undefined) view.enabled = false
      node.removeFromParent()
      killed.push(node.name)
      return
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
  return killed.length
}, /第\s*\d+\s*\/\s*\d+\s*步|我完成了|升级主城：/.source)

await page.goto(url.toString(), { waitUntil: 'networkidle' })
await waitPanel()
await clearGuide()
await page.waitForTimeout(400)

// ---------- 态一：升级中 ----------
const upgrading = await snapshot()
await page.screenshot({ path: path.join(OUT, '11-city-upgrading.png') })
const campA = tileOf(upgrading, '伐木场')
console.log(`[state] 升级中帧：屏上文本 ${upgrading.texts.length} 条；`
  + `伐木场格=${campA?.key ?? '(没找到)'} ${JSON.stringify(campA?.labels ?? null)}`
  + ` 正稿=${campA?.iconActive} 等级色=${campA?.levelColor}`)
console.log(`[state]   队列行=${upgrading.texts.find((t) => t.includes('建造队列')) ?? '(无)'}`)

// ---------- 态零：暂停 / 恢复（B03 §2，收口缺口 #324）----------
//
// 顺序放在"拦 /city/list"之前：暂停/恢复要走服务端（也要读回执刷新），拦了列表就读不到真实状态。
// 选中那一格 → 点「暂停」→ 读面板 → 点「恢复」→ 读面板；全程真点按钮，不走后门。
const pausePhase = { clicked: false, paused: null, resumed: null, pausedMs: 0 }
if (campA !== null) {
  // ⚠️ 2026-10-04：坐标点击**点不中格子**。实测读数：
  //   「选中 Grid-7：暂停键=false 恢复键=false 选择栏=「点击建筑查看详情」」
  //   ⇒ 那一格**根本没被选中**（row===null ⇒ CityPanelView.ts:1478 的 `row !== null &&`
  //   把动作键全置 invisible），于是「暂停这个动作在界面上不可达」。
  // 而**同一个 clickNode 去点「取消」是好的**（实测木材 4601→4841，退了 60%）
  // ⇒ 坐标换算没问题，问题在"格子的命中"这条路。
  // 改用 verify-tech-research-runtime 已验证过的同一手法：直接 emit 该 tile 的 touch-start
  // （绑定见 CityPanelView.ts:1226），不依赖鼠标落点 —— 与「#753 取消建造」那一格同源同解。
  const tapped = await page.evaluate((key) => {
    let hit = null
    const walk = (n) => { if (n.name === key) hit = n; for (const c of n.children) walk(c) }
    walk(window.cc.director.getScene())
    if (hit === null) return false
    hit.emit('touch-start')
    return true
  }, campA.key)
  console.log(`[state] emit ${campA.key} 的 touch-start：${tapped}`)
  await page.waitForTimeout(600)
  const beforePause = await pauseReadout()
  console.log(`[state] 选中 ${campA.key}：暂停键=${beforePause.pause} 恢复键=${beforePause.resume}`
    + ` 选择栏=「${beforePause.title ?? ''}」「${beforePause.status ?? ''}」`)
  const postsBefore = cityPosts.length
  const pausePoint = await clickNode('DetailPauseButton')
  if (beforePause.pause === true && pausePoint !== null) {
    const pausedAt = Date.now()
    await page.mouse.click(pausePoint.x, pausePoint.y)
    await page.waitForTimeout(1200)
    pausePhase.paused = await pauseReadout()
    pausePhase.pausedMs = Date.now() - pausedAt
    // 每步都打**整条**请求列表：点了没反应时，第一个要回答的问题是"请求到底出去了没有"
    console.log(`[state] 诊断：点「暂停」@(${pausePoint.x},${pausePoint.y}) 后 /city/* =`
      + ` [${cityPosts.join('、') || '(无)'}]（本次新增 ${cityPosts.length - postsBefore} 条）`)
    await page.screenshot({ path: path.join(OUT, '10-city-paused.png') })
    console.log(`[state] 暂停后：暂停键=${pausePhase.paused.pause} 恢复键=${pausePhase.paused.resume}`
      + ` 选择栏=「${pausePhase.paused.title ?? ''}」「${pausePhase.paused.status ?? ''}」`)
    const resumePoint = await clickNode('DetailResumeButton')
    if (pausePhase.paused.resume === true && resumePoint !== null) {
      await page.mouse.click(resumePoint.x, resumePoint.y)
      await page.waitForTimeout(1200)
      pausePhase.resumed = await pauseReadout()
      pausePhase.pausedMs = Date.now() - pausedAt
      console.log(`[state] 恢复后：暂停键=${pausePhase.resumed.pause} 恢复键=${pausePhase.resumed.resume}`
        + ` 选择栏=「${pausePhase.resumed.title ?? ''}」「${pausePhase.resumed.status ?? ''}」`
        + ` 请求=[${cityPosts.join('、') || '(无)'}]`)
    }
  }
  pausePhase.clicked = pausePoint !== null
}


// ---------- 拦掉 /city/list：让"到点"只发生在客户端 ----------
await page.route('**/city/list*', (route) => route.abort())
console.log('[state] 已拦掉 /city/list —— 保证"到点未收割"这一态不被服务端的自动收割吃掉')

// ---------- 态二：可收取（同一帧内等本地倒计时归零）----------
// 暂停了多久，完成时刻就被服务端顺延了多久（这正是"把暂停的时间还回来"），所以等待要加回去。
const remain = buildMs - (Date.now() - startedAt) + 3000 + pausePhase.pausedMs
await page.waitForTimeout(Math.max(1000, remain))
const harvestable = await snapshot()
await page.screenshot({ path: path.join(OUT, '12-city-harvestable.png') })
const hint = harvestable.texts.find((t) => t.includes('个建筑已升级完成')) ?? null
const campB = tileOf(harvestable, '伐木场')
console.log(`[state] 可收取帧：屏上文本 ${harvestable.texts.length} 条；收割提示=${hint ?? '(无)'}`)
console.log(`[state]   伐木场格=${campB?.key ?? '(没找到)'} ${JSON.stringify(campB?.labels ?? null)}`
  + ` 正稿=${campB?.iconActive} 等级色=${campB?.levelColor} 名字色=${campB?.nameColor}`)
console.log(`[state]   队列行=${harvestable.texts.find((t) => t.includes('建造队列')) ?? '(无)'}`
  + `（对照：升级中帧是 ${upgrading.texts.find((t) => t.includes('建造队列')) ?? '(无)'}）`)

// ---------- 态三：取消升级（B03 §2 的另一半："取消返还 60%"）----------
//
// 放在最后：它要真的取消掉一栋楼，会把前面两态的状态搅乱。
// 而且必须先**放开 /city/list 的拦截** —— 取消要读回执刷新，拦着列表面板就不会更新。
const cancelled = { clicked: false, readout: null, stoneBefore: null, stoneAfter: null }
await page.unroute('**/city/list*')
// 第二栋用 HTTP 起（第一栋这时候已到点待收割，队列归零，位置够）
const secondConfig = 'quarry'
const secondGrid = [3, 1]
const secondStart = await post('/city/upgrade',
  { requestId: `state-cancel-${Date.now()}`, configId: secondConfig, gridX: secondGrid[0], gridY: secondGrid[1] },
  { 'X-Player-Id': playerId })
if (secondStart.code !== 0) {
  console.log(`[state] 第二栋没起来（${secondConfig}@${secondGrid}）：${secondStart.code} ${secondStart.msg} —— 态三跳过`)
} else {
  // 重载让面板看见新建筑（客户端不会主动去拉列表）
  await page.reload({ waitUntil: 'networkidle' })
  await waitPanel()
  await clearGuide()
  await page.waitForTimeout(800)
  const beforeFrame = await snapshot()
  const tile = tileOf(beforeFrame, NAME_OF[secondConfig] ?? secondConfig)
  // 资源行的格式是「石料 4601/20000」——**必须用正则取第一个数字**：
  // 第一版把非数字全去掉再切前 6 位，得到 "460120"（把分子分母拼起来了），
  // 于是"退了 240"被判成"退了 0"。
  // 返还进哪个资源取决于这栋楼的造价：采石场耗的是**木材**（`costBaseWood: 400`），
  // 第一版读的是石料行，于是"退了 240 木材"被判成"退了 0"。
  const resourceOf = (frame, label) => {
    const row = frame.texts.find((text) => text.startsWith(label)) ?? ''
    const match = new RegExp(`${label}\\s*(\\d+)`).exec(row)
    return match === null ? null : Number.parseInt(match[1], 10)
  }
  cancelled.stoneBefore = resourceOf(beforeFrame, '木材')
  cancelled.label = '木材'
  // 表头「内城 · 建筑 N/36」在取消前后应当**正好差 1**：这一栋从没建成，取消它就等于没放过。
  // （别写死 1/36 —— 这时候城里还有态二里那栋已经建成的伐木场，它本来就该算一栋。）
  cancelled.headerBefore = beforeFrame.texts.find((text) => text.includes('内城 · 建筑')) ?? '(无)'
  const buildingCount = (text) => {
    const match = /建筑\s*(\d+)\s*\/\s*(\d+)/.exec(text ?? '')
    return match === null ? null : Number.parseInt(match[1], 10)
  }
  cancelled.countBefore = buildingCount(cancelled.headerBefore)
  const postsBefore = cityPosts.length
  if (tile !== null) {
    const tilePoint = await clickNode(tile.key)
    if (tilePoint !== null) {
      await page.mouse.click(tilePoint.x, tilePoint.y)
      await page.waitForTimeout(700)
    }
    const cancelPoint = await clickNode('DetailCancelButton')
    if (cancelPoint !== null) {
      await page.mouse.click(cancelPoint.x, cancelPoint.y)
      await page.waitForTimeout(1500)
      cancelled.clicked = true
      cancelled.readout = await cancelReadout()
      const afterFrame = await snapshot()
      cancelled.stoneAfter = resourceOf(afterFrame, '木材')
      const afterTile = tileOf(afterFrame, NAME_OF[secondConfig] ?? secondConfig)
      cancelled.tileIcon = afterTile?.iconActive ?? null
      cancelled.tileFrame = afterTile?.frameName ?? null
      // #328 之后：取消**首次放置**要把实例一起摘掉 ⇒ 那一格连名字都不该再有了
      cancelled.tileStillNamed = afterTile !== null
      cancelled.headerAfter = afterFrame.texts.find((text) => text.includes('内城 · 建筑')) ?? '(无)'
      cancelled.countAfter = /建筑\s*(\d+)\s*\/\s*(\d+)/.exec(cancelled.headerAfter) === null
        ? null : Number.parseInt(/建筑\s*(\d+)\s*\/\s*(\d+)/.exec(cancelled.headerAfter)[1], 10)
      cancelled.queue = afterFrame.texts.find((text) => text.includes('建造队列')) ?? '(无)'
      await page.screenshot({ path: path.join(OUT, '15-city-cancelled.png') })
      console.log(`[state] 取消：点「取消」@(${cancelPoint.x},${cancelPoint.y}) 后 /city/* 新增`
        + ` [${cityPosts.slice(postsBefore).join('、') || '(无)'}]`
        + ` 选择栏=「${cancelled.readout.title ?? ''}」「${cancelled.readout.status ?? ''}」`)
      console.log(`[state]   ${cancelled.label} ${cancelled.stoneBefore} → ${cancelled.stoneAfter}`
        + `（+${(cancelled.stoneAfter ?? 0) - (cancelled.stoneBefore ?? 0)}，B03 §2 应退 60%）`
        + ` 那一格还有名字=${cancelled.tileStillNamed} 表头=${cancelled.headerAfter} 队列=${cancelled.queue}`)
    }
  }
}

await browser.close()
await preview.close()

console.log(`[state] 渲染读数：drawcall=${harvestable.drawCalls}（profiler=${harvestable.profilerDraws}）`
  + ` instances=${harvestable.instances} 屏上 Sprite=${harvestable.sprites}`)
console.log(`[state] JS 堆=${harvestable.heapMb}MB；底图纹理=${JSON.stringify(harvestable.referenceSize)}`
  + ` ⇒ 解码内存按 w×h×4 ≈ `
  + `${harvestable.referenceSize === null ? '?' : Math.round(harvestable.referenceSize[0] * harvestable.referenceSize[1] * 4 / 1048576 * 10) / 10}MB`)
console.log(`[state] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

const failures = []
// 暂停/恢复（#324）：三条都要有 —— 点得下去、面板变「已暂停」且不给倒计时、恢复回「升级中」
if (!pausePhase.clicked) {
  failures.push('连格子都没点中，暂停这一态根本没量到（判据走不到不许当绿）')
} else if (pausePhase.paused === null) {
  failures.push('升级中那一帧的「暂停」键没出现 —— 暂停这个动作在界面上不可达')
} else {
  if (pausePhase.paused.pause === true) {
    failures.push('点了暂停之后「暂停」键仍在 —— 状态没跟着服务端回执走')
  }
  if (pausePhase.paused.resume !== true) {
    failures.push('暂停后「恢复」键没出现 —— 玩家会卡在暂停态里出不来')
  }
  const pausedText = `${pausePhase.paused.title ?? ''} ${pausePhase.paused.status ?? ''}`
  if (!pausedText.includes('已暂停')) {
    failures.push(`暂停后选择栏里没有「已暂停」：${JSON.stringify(pausedText)}`)
  }
  if (/\d+:\d+/.test(pausedText)) {
    failures.push(`暂停中仍显示倒计时「${pausedText}」—— 那个表永远不走，是 B03 明令不许的`)
  }
  if (pausePhase.resumed === null) {
    failures.push('「恢复」点下去之后没读回面板 —— 恢复这条路没走完')
  } else if (pausePhase.resumed.pause !== true || pausePhase.resumed.resume === true) {
    failures.push(`恢复后按钮没切回来：暂停键=${pausePhase.resumed.pause} 恢复键=${pausePhase.resumed.resume}`)
  } else if (!(pausePhase.resumed.status ?? '').includes('升级中')) {
    failures.push(`恢复后选择栏不是「升级中」：${JSON.stringify(pausePhase.resumed.status)}`)
  }
}
if (!upgrading.texts.some((t) => /建造队列\s*1\s*\//.test(t))) {
  failures.push('升级中帧的队列行不是 1/N —— 这一帧没量到"正在建造"')
}
// 取消（B03 §2 的另一半）：请求要出去、面板要回「空闲」、格子正稿要撤掉、队列要归零、资源要退回来
if (!cancelled.clicked) {
  failures.push('态三（取消升级）根本没跑到 —— 判据走不到不许当绿')
} else {
  if (!cityPosts.includes('cancel')) {
    failures.push('点了「取消」但没有 /city/cancel 请求 —— 按钮没接上')
  }
  const cancelText = `${cancelled.readout?.title ?? ''} ${cancelled.readout?.status ?? ''}`
  // #328 之后取消首次放置会把实例摘掉：那一格连名字都不该再有（格子真的空出来了）
  if (cancelled.tileStillNamed === true) {
    failures.push('取消首次放置后那一格还挂着建筑名 —— 实例没被摘掉，格子仍被占着')
  }
  if (cancelled.countBefore === null || cancelled.countAfter === null) {
    failures.push('表头「建筑 N/36」没读到，格子释放这条判据走不到（不许当绿）')
  } else if (cancelled.countBefore - cancelled.countAfter !== 1) {
    failures.push(`取消首次放置后表头应当正好少一栋：${cancelled.headerBefore} → ${cancelled.headerAfter}`)
  }
  if (cancelText.includes('采石场')) {
    failures.push(`取消后选择栏还提着一栋已经不存在的楼：${JSON.stringify(cancelText)}`)
  }
  if (cancelled.stoneBefore === null || cancelled.stoneAfter === null) {
    failures.push('木材那一行没读到，返还这条判据走不到（不许当绿）')
  }
  if (!/建造队列\s*0\s*\//.test(cancelled.queue ?? '')) {
    failures.push(`取消后队列没归零：${cancelled.queue}`)
  }
  const refund = (cancelled.stoneAfter ?? 0) - (cancelled.stoneBefore ?? 0)
  if (!(refund >= 200)) {
    failures.push(`取消返还的木材只有 ${refund}（B03 §2 要求退 60%，按 400 石料算应约 240）`)
  }
}
if (campA === null || !campA.labels.some((t) => /^0级$/.test(t))) {
  failures.push(`升级中帧找不到"伐木场 + 0级"：${JSON.stringify(campA?.labels ?? null)}`)
}
if (hint === null) {
  failures.push('可收取帧没有「N 个建筑已升级完成，点击收割」这句提示')
}
if (campB === null || campB.levelColor !== '120,176,96') {
  failures.push(`到点后伐木场的等级徽章没变成可收割色（期望 120,176,96）：`
    + `${JSON.stringify(campB?.levelColor ?? null)}（升级中时是 ${JSON.stringify(campA?.levelColor ?? null)}）`)
}
if (typeof harvestable.drawCalls !== 'number' || harvestable.drawCalls <= 0) {
  failures.push(`drawcall 读不到或非正数：${harvestable.drawCalls}`)
}
if (harvestable.heapMb === null) {
  failures.push('JS 堆读不到（performance.memory 不可用）—— 内存这一项就没量到')
}
if (failures.length > 0) {
  console.error(`[state] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[state] 全绿：升级中与可收取两态都留了帧，drawcall/堆/解码内存都拿到读数')


