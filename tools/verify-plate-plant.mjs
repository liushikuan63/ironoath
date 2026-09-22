/**
 * 职责：证明像素法那一维在**每一个被量的相位**（17 个默认相 + 7 个页签/联盟相）上都不是瞎的 —— 逐个相位运行时植入一块
 * "DFS 次序排在文字之后"的 `Graphics` 底板（#389 那处缺陷的形状），看它报不报得出来。
 * 依赖：node、playwright、已构建的 `client/build/web-mobile`、已启动的后端。
 *
 * <p>读接口夹具与横扫**共用同一份**（`tools/lib/social-fixtures.mjs` + `tools/lib/route-stub.mjs`）：
 * 「已入盟」那一屏只有摘要给了 `alliance` 才画得出来，而 dev 新号本来是个空态 ——
 * 各挂各的桩就会分叉（那批字段是逐条对着契约 required 配的，抄一遍就少抄一条，台账 #420/#423）。
 *
 * <p>用法：`LABELFIT_BACKEND=http://localhost:8199 node tools/verify-plate-plant.mjs`
 * <p>判据（每个相位都要满足，缺一判红）：植入前 0 处 / 植入后 > 0 处 / 撤掉后 0 处。
 * 全程只动浏览器里的节点树 ⇒ 不改源码、不重建，工作树不会留在植入态。
 *
 * <p>用的是横扫同一份 `planPlateCoverage`（`tools/lib/plate-coverage.mjs`）——
 * 复制一份去验证，验证的就是另一个东西了（台账 #410）。
 */
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { decodePng, diffRegion } from './lib/png-diff.mjs'
import { planPlateCoverage } from './lib/plate-coverage.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { socialFixtures } from './lib/social-fixtures.mjs'
import { makeStubRead } from './lib/route-stub.mjs'

const BACKEND = process.env.LABELFIT_BACKEND ?? (() => {
  console.error('[plant] 缺 LABELFIT_BACKEND（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.PLANT_PORT ?? 8197)

/**
 * 七个页签/联盟相位 + 17 个默认相，全部要做正例：只在 quest 上标定过的话，
 * 其余各屏的"0 处"就仍然只是"没量出东西"而不是"证明了没有"（台账 #411/#412）。
 * `tab` 为 null 表示默认相（不点页签）；`joined` 置起「已入盟」开关（见 `tools/lib/social-fixtures.mjs`）。
 */
const DEFAULT_PANELS = ['city', 'army', 'hero', 'gacha', 'bag', 'stage', 'reports', 'quest',
  'battlePass', 'mail', 'social', 'power', 'shop', 'avatarFrames', 'targets', 'world', 'settings']
  .map((panel) => ({ tag: panel, panel, tab: null }))

/** 每个相位：`panel` 是深链键，`tab` 是要点的页签节点名（null = 默认相）。 */
const PHASES = [
  { tag: 'reports/scout', panel: 'reports', tab: 'TabScout' },
  { tag: 'social/alliance', panel: 'social', tab: 'Tab_alliance' },
  { tag: 'social/help', panel: 'social', tab: 'Tab_help' },
  { tag: 'social/events', panel: 'social', tab: 'Tab_events' },
  { tag: 'social/chat', panel: 'social', tab: 'Tab_chat' },
  { tag: 'social/rally', panel: 'social', tab: 'Tab_rally' },
  // #420 才有夹具、#423 才搬进这一份：这一相从前的"报 0 处"只是"没量过"，不是"证明了没有"
  { tag: 'social/alliance-joined', panel: 'social', tab: 'Tab_alliance', joined: true },
  ...DEFAULT_PANELS,
]

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `plant-${Date.now()}`)
// 读接口夹具与横扫共用一份，且必须在任何 `page.goto` 之前挂上（晚挂等于那一相读到空态）
const social = socialFixtures()
await social.install(makeStubRead(context))

async function measure(page, panel, wantText = null) {
  const plan = await page.evaluate(planPlateCoverage, panel)
  if (plan === null) return { hits: -1, bands: 0, plates: 0, plantedHit: false }
  if (plan.plates.length === 0) return { hits: 0, bands: plan.bands.length, plates: 0, plantedHit: false }
  const base = decodePng(await page.screenshot())
  let hits = 0
  let plantedHit = false
  for (const plate of plan.plates) {
    await page.evaluate((h) => { window.__plateNodes[h].getComponent('cc.Graphics').enabled = false }, plate.handle)
    await page.waitForTimeout(120)
    const after = decodePng(await page.screenshot())
    await page.evaluate((h) => { window.__plateNodes[h].getComponent('cc.Graphics').enabled = true }, plate.handle)
    await page.waitForTimeout(80)
    for (const bi of plate.bands) {
      const d = diffRegion(base, after, plan.bands[bi].rect, 24)
      if (d.changed > 0) {
        hits += 1
        // 不只要求"报了点什么"，还要求**被植入的那颗字**在报出来的里面 ——
        // 否则植入没盖住目标、却顺手报了别的行，也算通过，那就是自我安慰。
        // 两边都是同一字符串的截断（植入侧取 8 字、量具侧取 10 字），所以按前缀比，不按等值比
        const band = plan.bands[bi].text
        if (wantText !== null && (band.startsWith(wantText) || wantText.startsWith(band))) plantedHit = true
      }
    }
  }
  return { hits, bands: plan.bands.length, plates: plan.plates.length, plantedHit }
}

const results = []
for (const phase of PHASES) {
  // 「已入盟」那一屏要摘要里的 alliance 非 null 才画得出来（三份桩跟着这个开关一起翻），
  // 必须在 goto 之前置 —— 深链一进来就发请求
  social.state.joinedAlliance = phase.joined === true
  const page = await context.newPage()
  await page.goto(`${preview.origin}/?panel=${phase.panel}`, { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  await page.waitForTimeout(2500)
  // 先藏新手引导板：不藏的话植入可能落在引导遮罩底下，像素不变 ⇒ 假失败
  await hideGuideOverlay(page)
  const switched = phase.tab === null || await page.evaluate((tabName) => {
    const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    let hit = null
    const find = (n) => {
      if (hit !== null) return
      if (n.name === tabName && n.activeInHierarchy) hit = n
      for (const c of n.children) find(c)
    }
    for (const p of game?.children ?? []) find(p)
    if (hit !== null) hit.emit('touch-start', null)
    return hit !== null
  }, phase.tab)
  await page.waitForTimeout(1500)
  const before = await measure(page, phase.panel)
  const planted = await page.evaluate(([panelKey, alpha]) => {
    window.__plantAlpha = alpha
    const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.children.find((c) => c.name === panelKey)
    if (!panel) return { ok: false, why: '面板没找到' }
    // 挑第一颗"够宽"的字（≥3 字，避免挑到单字符把植入面积压到噪声级）
    let target = null
    const walk = (n) => {
      if (target !== null) return
      const lb = n.getComponent('cc.Label')
      if (lb !== null && (lb.string ?? '').length >= 3 && n.activeInHierarchy && n !== panel) {
        target = n
        return
      }
      for (const c of n.children) walk(c)
    }
    walk(panel)
    if (target === null) return { ok: false, why: '这一相没有可植入的文字' }
    // 宿主一律用面板本身：底板挂在面板的**最后一个子节点** ⇒ DFS 次序排在所有文字之后，
    // 这才是 #389 的形状。原先要求"父节点自带 Graphics"，三个相位因此无处可植（规则太窄）。
    const world = target.getComponent('cc.UITransform').getBoundingBoxToWorld()
    const pt = panel.getComponent('cc.UITransform')
    const V = panel.position.constructor // 拿一个 Vec3 类实例，绕开 window.cc.Vec3 可能是 undefined
    const tl = pt.convertToNodeSpaceAR(new V(world.x, world.y + world.height, 0))
    const br = pt.convertToNodeSpaceAR(new V(world.x + world.width, world.y, 0))
    const w = Math.max(4, br.x - tl.x)
    const h = Math.max(4, tl.y - br.y)
    const plate = new window.cc.Node('probePlantPlate')
    plate.layer = panel.layer // new Node() 默认不是 UI_2D 层
    panel.addChild(plate)
    plate.setPosition((tl.x + br.x) / 2, (tl.y + br.y) / 2)
    // window.cc.Graphics 在这个构建里是 undefined（Error 3804 = 传进去的类为空），注册名可用
    const g = plate.addComponent('cc.Graphics')
    // 取 Color 类必须从一个**确实存在**的颜色实例上取：面板根节点没有 Graphics，
    // 原先写 `panel.getComponent('cc.Graphics')?.fillColor ?? {}` 会退化成 Object，
    // `new Object(240,40,40,0)` 当颜色用 ⇒ alpha 旋钮整个失效（alpha 0 也照样报"盖住"就是这么来的）
    const ctor = target.getComponent('cc.Label').color.constructor
    plate.getComponent('cc.UITransform').setContentSize(w, h)
    // alpha 可由环境变量压低：用来量这一维的**边界** —— 半透明底板只是给字染色、
    // 没真盖住，24 的像元阈值下报不报得出来是未知的，测出来才知道（台账 #412 的未做项）
    g.fillColor = new ctor(240, 40, 40, Number(window.__plantAlpha ?? 255))
    g.rect(-w / 2, -h / 2, w, h)
    g.fill()
    window.__probePlant = plate
    return { ok: true, host: panel.name, text: target.getComponent('cc.Label').string.slice(0, 8) }
  }, [phase.panel, Number(process.env.PLANT_ALPHA ?? 255)])
  const after = planted.ok ? await measure(page, phase.panel, planted.text) : { hits: -1 }
  if (planted.ok) {
    await page.evaluate(() => { window.__probePlant?.destroy(); window.__probePlant = null })
    await page.waitForTimeout(200)
  }
  const reverted = planted.ok ? await measure(page, phase.panel) : { hits: -1 }
  const ok = before.hits === 0 && planted.ok === true && after.hits > 0
    && after.plantedHit === true && reverted.hits === 0
  results.push({ tag: phase.tag, switched, before: before.hits, planted, after: after.hits,
    plantedHit: after.plantedHit === true, reverted: reverted.hits, bands: before.bands, ok })
  console.log(`  ${phase.tag}: 切页签=${switched} 字形带=${before.bands} 条；植入前 ${before.hits} → `
    + `植入后 ${after.hits}（命中被植字=${after.plantedHit === true}）→ 撤掉后 ${reverted.hits}；`
    + `植入=${JSON.stringify(planted)} ⇒ ${ok ? 'OK' : '不合格'}`)
  await page.close()
}

await browser.close()
await preview.close()
const bad = results.filter((r) => !r.ok)
console.log(`\n[plant] 全部相位：合格 ${results.length - bad.length} / ${results.length}`)
for (const b of bad) console.log(`  不合格 ${b.tag}：${JSON.stringify(b)}`)
process.exit(bad.length === 0 ? 0 : 1)
