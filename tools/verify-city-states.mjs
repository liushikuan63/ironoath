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
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
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
      const own = []
      const collect = (child) => {
        const l = child.getComponent && child.getComponent('cc.Label')
        if (l !== null && l !== undefined && l.string !== '') own.push(l.string)
        for (const grand of child.children) collect(grand)
      }
      collect(node)
      const icon = node.getChildByName('BuildingIcon')
      const levelLabel = node.getChildByName('Level')?.getComponent('cc.Label') ?? null
      const nameLabel = node.getChildByName('Name')?.getComponent('cc.Label') ?? null
      const rgb = (color) => color === null || color === undefined
        ? null : `${color.r},${color.g},${color.b}`
      tiles[node.name] = {
        labels: own,
        iconActive: icon === null ? null : icon.active,
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

// ---------- 拦掉 /city/list：让"到点"只发生在客户端 ----------
await page.route('**/city/list*', (route) => route.abort())
console.log('[state] 已拦掉 /city/list —— 保证"到点未收割"这一态不被服务端的自动收割吃掉')

// ---------- 态二：可收取（同一帧内等本地倒计时归零）----------
const remain = buildMs - (Date.now() - startedAt) + 3000
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

await browser.close()
await preview.close()

console.log(`[state] 渲染读数：drawcall=${harvestable.drawCalls}（profiler=${harvestable.profilerDraws}）`
  + ` instances=${harvestable.instances} 屏上 Sprite=${harvestable.sprites}`)
console.log(`[state] JS 堆=${harvestable.heapMb}MB；底图纹理=${JSON.stringify(harvestable.referenceSize)}`
  + ` ⇒ 解码内存按 w×h×4 ≈ `
  + `${harvestable.referenceSize === null ? '?' : Math.round(harvestable.referenceSize[0] * harvestable.referenceSize[1] * 4 / 1048576 * 10) / 10}MB`)
console.log(`[state] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

const failures = []
if (!upgrading.texts.some((t) => /建造队列\s*1\s*\//.test(t))) {
  failures.push('升级中帧的队列行不是 1/N —— 这一帧没量到"正在建造"')
}
if (campA === null || !campA.labels.some((t) => /^Lv0$/.test(t))) {
  failures.push(`升级中帧找不到"伐木场 + Lv0"：${JSON.stringify(campA?.labels ?? null)}`)
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


