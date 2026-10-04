/**
 * 职责：引导气泡那一排的运行时验收 —— ① 只有一颗键时它必须**居中**；② 键必须走全游戏同一张
 *       暗金 chip 母版（不是自己画的一套描边）。
 * 依赖：`client/build/web-mobile` 产物 + 一台本轮自己的 dev 后端（`BACKEND_ORIGIN`）+ Playwright。
 *
 * <p>为什么需要它：新号第 1 步（`guide_01_upgrade_main`，`skippable=false`）屏上只有「我完成了」一颗键，
 * 而排版从前无条件按"两颗并排"算 ⇒ 那颗键坐在左半边（用户 2026-09-26 截图指出的形状）。
 * 这类"少一颗就歪"的缺陷截图能看见、单测看不见（scene/ 层 import 'cc'，不进 node:test）。
 *
 * <p>**两顆并排那一相没有运行时取证**：要走到 `skippable=true` 的步（第 3 步起）得先把主线任务真做完，
 * 探针不造那条进度。它的排位公式与改动前逐字相同（`±(BUTTON_WIDTH+BUTTON_GAP)/2`），
 * 且下面第 2 相的植入正是把键搬到那个位置上证明量具读得出偏移。
 *
 * <p>每一条判据都配**植入对照**（把读数改坏，看它是否真的红）：只报"通过"的量具等于没量。
 *
 * 退出码：0 全绿；1 判据失败；2 前置不满足。
 */
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.GUIDE_BUTTONS_PORT ?? 8304)
const OUT = process.env.GUIDE_SHOT_OUT ?? 'client/build/guide-verify'
const VIEW_SOURCE = 'client/assets/scripts/scene/GuideView.ts'

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error('[guide-buttons][前置] 产物不存在（先跑 scripts/build-webmobile.sh）')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

/** 间距从源码现读，不在量具里抄第二份（抄一份的失效方式是"改了源码量具照绿"）。 */
const source = readFileSync(VIEW_SOURCE, 'utf8')
const GAP = Number((source.match(/const BUTTON_GAP = ([0-9.]+)/) ?? [])[1])
const WIDTH = Number((source.match(/const BUTTON_WIDTH = ([0-9.]+)/) ?? [])[1])
if (!Number.isFinite(GAP) || !Number.isFinite(WIDTH)) {
  console.error('[guide-buttons][前置] 从 GuideView.ts 读不到 BUTTON_GAP / BUTTON_WIDTH')
  process.exit(2)
}

const deviceId = `guide-buttons-${Date.now()}`
const init = await fetch(`${BACKEND}/player/init`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    requestId: `guide-buttons-${Date.now()}`, deviceId, nickName: '引导键探针',
    clientTime: Date.now(), wxCode: '',
  }),
}).then((response) => response.json())
if (init.code !== 0) {
  console.error(`[guide-buttons][前置] 建号失败：${JSON.stringify(init)}`)
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
url.searchParams.set('panel', 'city')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc?.director?.getScene?.() != null, null, { timeout: 25_000 })
  .catch(() => {})
await page.waitForTimeout(3000)

/**
 * 读气泡那一排。世界坐标换算成屏幕像素再比（画布有缩放，直接比世界坐标与 innerWidth 是错的口径）。
 * `plant` 不为 null 时先把键搬到指定位置 / 关掉 Sprite，再读 —— 植入与读数在同一次 evaluate 里做完，
 * 中间不给重绘插进来的机会。
 */
const readRow = (plant = null) => page.evaluate(({ mode, offset }) => {
  const scene = window.cc.director.getScene()
  let bubble = null
  const walk = (n) => {
    if (bubble !== null) return
    if (n.name === 'GuideBubble') { bubble = n; return }
    for (const c of n.children) walk(c)
  }
  walk(scene)
  if (bubble === null) return { found: false }
  const row = bubble.getChildByName('GuideButtons')
  const next = row === null ? null : row.getChildByName('GuideNext')
  const skip = row === null ? null : row.getChildByName('GuideSkip')
  if (next === null || skip === null) return { found: false, reason: 'GuideButtons 下缺键' }

  if (mode === 'off-center') {
    // 植入①：把「我完成了」搬到旧版双键排位上（左半边）—— 居中那条判据必须因此变红
    next.setPosition(offset, 0, 0)
  }
  const readOne = (node) => {
    const sprite = node.getComponent('cc.Sprite')
    const graphics = node.getComponent('cc.Graphics')
    if (mode === 'no-art' && node.name === 'GuideNext' && sprite !== null) {
      // 植入②：关掉 chip 的 Sprite（退回 Graphics 兜底那个形状）—— 主题那条判据必须因此变红。
      // **先植入再读数**：反过来就会读到植入前的值，植入等于没做（本轮真踩过一次）。
      sprite.enabled = false
    }
    const art = sprite !== null && sprite.enabled === true && sprite.spriteFrame !== null
    const world = node.getWorldPosition()
    const scale = window.cc.view.getScaleX()
    return {
      active: node.activeInHierarchy,
      screenX: world.x * scale,   // 世界原点在设计区左下角，不是屏幕中心（见 tools/lib/node-screen-pos.mjs）
      frameName: art ? String(sprite.spriteFrame.name) : null,
      graphicsOn: graphics !== null && graphics.enabled === true,
    }
  }
  const bubbleWorld = bubble.getWorldPosition()
  const scale = window.cc.view.getScaleX()
  const result = {
    found: true,
    bubbleScreenX: bubbleWorld.x * scale,
    next: readOne(next),
    skip: readOne(skip),
    stepText: (bubble.getChildByName('GuideStepText')?.getComponent('cc.Label')?.string ?? ''),
  }
  if (mode === 'no-art' && next.getComponent('cc.Sprite') !== null) {
    next.getComponent('cc.Sprite').enabled = true   // 当场还原，别让植入留在后面几相里
  }
  if (mode === 'off-center') {
    next.setPosition(0, 0, 0)
  }
  return result
}, { mode: plant, offset: -(WIDTH + GAP) / 2 })

let failed = 0
const report = (label, ok, detail) => {
  if (!ok) failed += 1
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail === undefined ? '' : `：${detail}`}`)
}

// ---------- 第 1 相：真实态（新号第 1 步，不可跳过 ⇒ 只有一颗键） ----------
const real = await readRow()
if (real.found !== true) {
  console.error(`[guide-buttons][前置] 读不到引导气泡：${JSON.stringify(real)}`)
  await browser.close()
  await preview.close()
  process.exit(2)
}
console.log(`[guide-buttons] 这一步的文案：${real.stepText}`)
report('这一帧确实是"只有一颗键"（跳过键未激活）', real.skip.active === false,
  `skip.active=${real.skip.active}`)
const offsetPx = Math.abs(real.next.screenX - real.bubbleScreenX)
report('单键时「我完成了」居中（偏离 ≤ 2px）', offsetPx <= 2,
  `偏离 ${offsetPx.toFixed(1)}px（键 ${real.next.screenX.toFixed(1)} / 气泡 ${real.bubbleScreenX.toFixed(1)}）`)
report('键走的是暗金 chip 母版（不是 Graphics 兜底）',
  real.next.frameName !== null && real.next.frameName.includes('button-chip') && real.next.graphicsOn === false,
  `frame=${real.next.frameName} graphicsOn=${real.next.graphicsOn}`)
report('次要键也是同一张 chip（两顆一套皮）',
  real.skip.frameName !== null && real.skip.frameName.includes('button-chip'),
  `frame=${real.skip.frameName}`)
await page.screenshot({ path: path.join(OUT, 'guide-bubble-single.png') })

// ---------- 第 2 相：植入对照（判据必须能红） ----------
const planted = await readRow('off-center')
const plantedOffset = Math.abs(planted.next.screenX - planted.bubbleScreenX)
report('植入①把键搬到旧排位后，居中判据确实读到偏移（> 2px）', plantedOffset > 2,
  `偏移 ${plantedOffset.toFixed(1)}px（源码常量 ${(WIDTH + GAP) / 2} 设计px）`)
report('植入①还原后重新居中', Math.abs((await readRow()).next.screenX - real.bubbleScreenX) <= 2)

const noArt = await readRow('no-art')
report('植入②关掉 chip 的 Sprite 后，主题判据确实变红',
  noArt.next.frameName === null || !String(noArt.next.frameName).includes('button-chip'),
  `frame=${noArt.next.frameName}`)
const restored = await readRow()
report('植入②还原后 chip 又在', restored.next.frameName !== null
  && String(restored.next.frameName).includes('button-chip'))

await browser.close()
await preview.close()
if (errors.length > 0) {
  console.error(`[guide-buttons][FAIL] 页面报错 ${errors.length} 条：${errors.slice(0, 3).join(' | ')}`)
  process.exit(1)
}
if (failed > 0) {
  console.error(`[guide-buttons][FAIL] ${failed} 条判据未过`)
  process.exit(1)
}
console.log(`[guide-buttons] 全绿：单键居中 + 暗金 chip，两条判据各自植入后都能红（截图 ${OUT}/guide-bubble-single.png）`)
process.exit(0)
