/**
 * 职责：**离开世界地图时的收尾**验收（`GameApi.leaveWorld` —— 收口清单"客户端发送口缺口"里的 `leaveWorld`）。
 * 依赖：`client/build/web-mobile` 产物 + 一台本轮自己的 dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么需要它：这个方法原先**一处调用都没有**。它的作用不是画错什么，而是"绑了要解"：
 * 世界那套 requester 一直挂在适配层上，且 `enterWorld` 因为 `worldReady` 仍为真会**跳过重新初始化**。
 * 所以判据只能是**状态翻转**：进世界 ⇒ `worldReady` 为真；切回内城 ⇒ 变假；再进 ⇒ 又为真。
 * 没有这条读数，"接线了没有"就只能靠肉眼看代码。
 *
 * 退出码：0 全绿；1 判据失败；2 前置不满足（产物/后端/建号/找不到启动组件）。
 */
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { resolveCocosClickPoint } from './lib/cocos-click.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.WORLD_LEAVE_PORT ?? 8302)
const OUT = 'client/build/art-verify'
const SHOT = path.join(OUT, '19-world-leave.png')

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error('[world-leave][前置] 产物不存在（先跑 scripts/build-webmobile.sh）')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const deviceId = `world-leave-${Date.now()}`
const init = await fetch(`${BACKEND}/player/init`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    requestId: `world-leave-${Date.now()}`, deviceId, nickName: '离界探针',
    clientTime: Date.now(), wxCode: '',
  }),
}).then((response) => response.json())
if (init.code !== 0) {
  console.error(`[world-leave][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'world')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc?.director?.getScene?.() != null, null, { timeout: 25_000 })
  .catch(() => {})
await page.waitForTimeout(2500)

function worldReadySnapshot(expected = null) {
  const scene = window.cc?.director?.getScene?.()
  const unique = (parent, name) => {
    const nodes = (parent?.children ?? []).filter(node => node.name === name)
    return nodes.length === 1 ? nodes[0] : null
  }
  const game = unique(unique(scene, 'Canvas'), 'Game')
  const hosts = (game?.components ?? []).filter(component => component?.root?.api != null)
  const host = hosts.length === 1 ? hosts[0] : null
  const found = host !== null && typeof host.root.api.worldReady === 'boolean'
    && typeof host.nav?.currentKey === 'string'
  const state = found
    ? { found: true, worldReady: host.root.api.worldReady, navKey: host.nav.currentKey }
    : { found: false, reason: `Expected unique Game/root.api and boolean worldReady/string navKey; hosts=${hosts.length}` }
  return expected === null ? state
    : state.found && state.navKey === expected.key && state.worldReady === expected.ready
}

function inputLayerSnapshot() {
  const scene = window.cc?.director?.getScene?.()
  const matches = (root, name) => {
    const nodes = []
    const visit = (node, depth) => {
      if (!node || depth > 64) return
      if (node.name === name) nodes.push(node)
      for (const child of node.children ?? []) visit(child, depth + 1)
    }
    visit(root, 0)
    return nodes
  }
  const gifts = matches(scene, 'giftPopup')
  const guides = matches(scene, 'GuideNext').filter(node => node.activeInHierarchy === true)
  const popup = gifts.length === 1 ? gifts[0] : null
  const nodes = []
  const visit = node => {
    if (!node) return
    const sprite = node.getComponent?.('cc.Sprite')
    const graphics = node.getComponent?.('cc.Graphics')
    const label = node.getComponent?.('cc.Label')
    if (sprite || graphics || label || ['giftPopup', 'panel', 'close'].includes(node.name)) {
      nodes.push({ name: node.name, active: node.activeInHierarchy === true,
        spriteEnabled: sprite?.enabled ?? null, spriteFrame: sprite?.spriteFrame?.name ?? null,
        graphicsEnabled: graphics?.enabled ?? null,
        labelEnabled: label?.enabled ?? null, text: label?.string ?? null })
    }
    for (const child of node.children ?? []) visit(child)
  }
  visit(popup)
  return { accepted: gifts.length === 1 && guides.length === 0,
    giftCount: gifts.length, giftActive: popup?.activeInHierarchy ?? null,
    activeGuideNextCount: guides.length, nodes }
}

function worldClickTarget(opts) {
  const registry = window.__worldLeaveClickObservers ??= Object.create(null)
  if (opts.action === 'read') {
    const observer = registry[opts.token]
    if (!observer) return { accepted: false, reason: 'observer-missing', events: [] }
    observer.node.off('touch-start', observer.callback)
    delete registry[opts.token]
    return { accepted: observer.events.length === 1 && observer.events[0].locationMatches,
      reason: observer.events.length === 1 && observer.events[0].locationMatches ? null : 'actual-target-event-missing-or-wrong',
      events: observer.events }
  }
  const scene = window.cc?.director?.getScene?.()
  const matches = (root, name) => {
    const nodes = []
    const visit = (node, depth) => {
      if (!node || depth > 64) return
      if (node.name === name) nodes.push(node)
      for (const child of node.children ?? []) visit(child, depth + 1)
    }
    visit(root, 0)
    return nodes
  }
  const hosts = matches(scene, opts.within)
  const targets = hosts.length === 1 ? matches(hosts[0], opts.name) : []
  const node = targets.length === 1 ? targets[0] : null
  if (!node || node.activeInHierarchy !== true) {
    return { accepted: false, reason: 'missing-duplicate-or-inactive-target', hostCount: hosts.length, targetCount: targets.length }
  }
  if (typeof node.hasEventListener !== 'function' || !node.hasEventListener('touch-start')) {
    return { accepted: false, reason: 'target-has-no-production-touch-start-listener' }
  }
  const box = node.getComponent?.('cc.UITransform')
  if (!box) return { accepted: false, reason: 'target-has-no-uitransform' }
  if (opts.action === 'preflight') return { accepted: true, hostCount: 1, targetCount: 1, node: node.name }
  const rect = document.querySelector('canvas')?.getBoundingClientRect()
  const dpr = window.devicePixelRatio || 1
  const point = opts.point
  if (!rect || point?.verified !== true || point.node !== node.name
      || !Number.isFinite(point.x) || !Number.isFinite(point.y)) {
    return { accepted: false, reason: 'unverified-point' }
  }
  const engine = { x: (point.x - rect.left) * dpr, y: (rect.top + rect.height - point.y) * dpr }
  if (box.hitTest(new window.cc.Vec2(engine.x, engine.y), 0) !== true) {
    return { accepted: false, reason: 'independent-target-hittest-rejected' }
  }
  const rendered = []
  const inspect = n => {
    if (n.activeInHierarchy !== true) return
    const sprite = n.getComponent?.('cc.Sprite')
    const label = n.getComponent?.('cc.Label')
    if (sprite?.enabled === true && sprite.spriteFrame) rendered.push({ type: 'Sprite', name: n.name, frame: sprite.spriteFrame.name })
    if (label?.enabled === true && String(label.string ?? '').trim()) rendered.push({ type: 'Label', name: n.name, text: label.string })
    for (const child of n.children ?? []) inspect(child)
  }
  inspect(node)
  if (opts.name === 'close' && rendered.length === 0) return { accepted: false, reason: 'gift-close-has-no-active-renderer' }
  if (registry[opts.token]) return { accepted: false, reason: 'duplicate-observer-token' }
  const events = []
  const callback = event => {
    const location = typeof event?.getLocation === 'function' ? event.getLocation() : null
    events.push({ type: event?.type ?? null, node: node.name,
      x: location?.x ?? null, y: location?.y ?? null,
      locationMatches: Number.isFinite(location?.x) && Number.isFinite(location?.y)
        && Math.abs(location.x - engine.x) <= dpr * 2 && Math.abs(location.y - engine.y) <= dpr * 2 })
  }
  registry[opts.token] = { node, callback, events }
  node.on('touch-start', callback)
  return { accepted: true, node: node.name, engine, rendered, independentHit: true }
}

function waitWorldDraw() {
  return new Promise(resolve => {
    const cc = window.cc
    const director = cc?.director
    const event = cc?.Director?.EVENT_AFTER_DRAW
    if (!director || !event || typeof director.getTotalFrames !== 'function') {
      resolve({ accepted: false, reason: 'after-draw-unavailable', frames: [] }); return
    }
    const frames = []
    const finish = reason => {
      clearTimeout(timer)
      director.off(event, tick)
      resolve({ accepted: frames.length === 2, reason, frames })
    }
    const tick = () => {
      const frame = director.getTotalFrames()
      if (Number.isFinite(frame) && !frames.includes(frame)) frames.push(frame)
      if (frames.length === 2) finish(null)
    }
    const timer = setTimeout(() => finish('after-draw-timeout'), 2000)
    director.on(event, tick)
  })
}

const readWorldReady = () => page.evaluate(worldReadySnapshot)

async function clickWorldInput(name, within, phase) {
  const options = { name, within, token: phase }
  const preflight = await page.evaluate(worldClickTarget, { ...options, action: 'preflight' })
  if (!preflight.accepted) return { accepted: false, preflight, reason: preflight.reason }
  const point = await page.evaluate(resolveCocosClickPoint, { name, within })
  if (point.verified !== true) return { accepted: false, point, reason: point.reason }
  const armed = await page.evaluate(worldClickTarget, { ...options, action: 'arm', point })
  if (!armed.accepted) return { accepted: false, point, armed, reason: armed.reason }
  let drawn
  let actual
  try {
    await page.mouse.click(point.x, point.y)
    drawn = await page.evaluate(waitWorldDraw)
  } finally {
    actual = await page.evaluate(worldClickTarget, { ...options, action: 'read' })
  }
  const result = { accepted: drawn?.accepted === true && actual.accepted === true,
    clicked: true, point, armed, drawn, actual }
  console.log(`[world-leave] 实际点击 ${phase}：${JSON.stringify(result)}`)
  return result
}

async function prepareNavigation(phase) {
  const guideFixtureApplied = await hideGuideOverlay(page)
  const drawn = await page.evaluate(waitWorldDraw)
  const before = await page.evaluate(inputLayerSnapshot)
  const result = { accepted: drawn.accepted && before.accepted, guideFixtureApplied, drawn, before }
  if (result.accepted && before.giftActive === true) {
    const beforeShot = path.join(OUT, `19-world-leave-${phase}-gift-before-close.png`)
    await page.screenshot({ path: beforeShot })
    result.beforeShot = beforeShot
    result.close = await clickWorldInput('close', 'giftPopup', `${phase}-gift-close`)
    result.after = await page.evaluate(inputLayerSnapshot)
    result.accepted = result.close.accepted && result.after.accepted && result.after.giftActive === false
    result.afterShot = path.join(OUT, `19-world-leave-${phase}-gift-after-close.png`)
    await page.screenshot({ path: result.afterShot })
  } else if (result.accepted) {
    result.accepted = before.giftActive === false
  }
  console.log(`[world-leave] 导航输入前置 ${phase}：${JSON.stringify(result)}`)
  return result
}

function worldLeaveFailures(readout) {
  const failures = []
  for (const [name, state, key, ready] of [
    ['进世界', readout.first, 'world', true],
    ['切回内城', readout.afterLeave, 'city', false],
    ['再进世界', readout.reenter, 'world', true],
  ]) {
    if (state?.found !== true || state.navKey !== key || state.worldReady !== ready) {
      failures.push(`${name}必须是 navKey=${key}/worldReady=${ready}，实际 ${JSON.stringify(state)}`)
    }
  }
  for (const phase of ['city', 'world']) {
    if (readout[`${phase}Fixture`]?.accepted !== true) failures.push(`${phase} 导航输入前置未通过`)
    if (readout[`${phase}Click`]?.accepted !== true) failures.push(`${phase} 唯一导航键未收到本次实际指针事件，或绘制未完成`)
    if (readout[`${phase}Settled`] !== true) failures.push(`${phase} 导航状态未在期限内完成`)
  }
  if (readout.errors.length > 0) failures.push(`页面报错 ${readout.errors.length} 条：${readout.errors[0]}`)
  return failures
}

const first = await readWorldReady()
console.log(`[world-leave] 进世界后：导航键=${first.navKey} worldReady=${first.worldReady}`)
const cityFixture = await prepareNavigation('city')
const cityClick = first.found && first.navKey === 'world' && first.worldReady === true && cityFixture.accepted
  ? await clickWorldInput('Nav-city', 'NavBar', 'city')
  : { accepted: false, reason: 'initial-world-or-input-precondition-failed' }
const citySettled = cityClick.accepted && await page.waitForFunction(worldReadySnapshot,
  { key: 'city', ready: false }, { timeout: 5000 }).then(() => true, () => false)
const afterLeave = await readWorldReady()
console.log(`[world-leave] 切回内城后：导航键=${afterLeave.navKey} worldReady=${afterLeave.worldReady}`)
const worldFixture = citySettled ? await prepareNavigation('world')
  : { accepted: false, reason: 'city-state-not-established' }
const worldClick = citySettled && afterLeave.found && afterLeave.navKey === 'city'
  && afterLeave.worldReady === false && worldFixture.accepted
  ? await clickWorldInput('Nav-world', 'NavBar', 'world')
  : { accepted: false, reason: 'city-or-input-precondition-failed' }
const worldSettled = worldClick.accepted && await page.waitForFunction(worldReadySnapshot,
  { key: 'world', ready: true }, { timeout: 10000 }).then(() => true, () => false)
const reenter = await readWorldReady()
console.log(`[world-leave] 再进世界后：导航键=${reenter.navKey} worldReady=${reenter.worldReady}`)
await page.screenshot({ path: SHOT })
await browser.close()
await preview.close()
const readout = { first, cityFixture, cityClick, citySettled, afterLeave,
  worldFixture, worldClick, worldSettled, reenter, errors, shot: SHOT }
const failures = worldLeaveFailures(readout)
const receipt = path.join(OUT, '19-world-leave-readout.json')
writeFileSync(receipt, JSON.stringify({ ...readout, failures, accepted: failures.length === 0 }, null, 2) + '\n')
console.log(`[world-leave] 截图：${SHOT}；独立读数：${receipt}`)
console.log(`[world-leave] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)
if (failures.length > 0) {
  console.error(`[world-leave] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[world-leave] 全绿：导航 world→city→world，worldReady=true→false→true，两次真实指针命中')
