/**
 * 职责：证明像素法那一维在**每一个被量的相位**（17 个默认相 + 8 个页签/联盟/翻页相）上都不是瞎的 —— 逐个相位运行时植入一块
 * "DFS 次序排在文字之后"的 `Graphics` 底板（#389 那处缺陷的形状），看它报不报得出来。
 * 依赖：node、playwright、已构建的 `client/build/web-mobile`、已启动的后端。
 *
 * <p>读接口夹具与横扫**共用同一份**（`tools/lib/social-fixtures.mjs` + `tools/lib/route-stub.mjs`）：
 * 「已入盟」那一屏只有摘要给了 `alliance` 才画得出来，而 dev 新号本来是个空态 ——
 * 各挂各的桩就会分叉（那批字段是逐条对着契约 required 配的，抄一遍就少抄一条，台账 #420/#423）。
 *
 * <p>用法：`LABELFIT_BACKEND=http://localhost:8199 node tools/verify-plate-plant.mjs`
 * <p>判据（每个相位都要满足，缺一判红）：植入前 0 处 / 植入后 > 0 处 / 撤掉后 0 处。
 * 翻页相另加一条：**点到了按钮还不算翻过去**，屏幕上的字形带文本集合必须真的换掉（见 `paged`）。
 * 全程只动浏览器里的节点树 ⇒ 不改源码、不重建，工作树不会留在植入态。
 *
 * <p>用的是横扫同一份 `planPlateCoverage`（`tools/lib/plate-coverage.mjs`）——
 * 复制一份去验证，验证的就是另一个东西了（台账 #410）。
 */
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { decodePng, diffRegion } from './lib/png-diff.mjs'
import { planPlateCoverage } from './lib/plate-coverage.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { socialFixtures } from './lib/social-fixtures.mjs'
import { makeStubRead } from './lib/route-stub.mjs'
import { clickTabNode, clickRowAction } from './lib/panel-clicks.mjs'

const BACKEND = process.env.LABELFIT_BACKEND ?? (() => {
  console.error('[plant] 缺 LABELFIT_BACKEND（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.PLANT_PORT ?? 8197)

/**
 * 八个页签/联盟/翻页相位 + 17 个默认相，全部要做正例：只在 quest 上标定过的话，
 * 其余各屏的"0 处"就仍然只是"没量出东西"而不是"证明了没有"（台账 #411/#412）。
 * `tab` 为 null 表示默认相（不点页签）；`joined` 置起「已入盟」开关（见 `tools/lib/social-fixtures.mjs`）；
 * `pageAction` 是翻页相要点的行内按钮文本（见 `tools/lib/panel-clicks.mjs`），点了还必须真换屏（见 `pageToNext`）。
 *
 * <p><b>为什么翻页相只有这一例</b>（2026-09-22 逐相取证，台账 #444）：全客户端只有三处会画「下一页」——
 * `SocialPanelView.drawRows`（`pages > 1` 才加那一行）、`PowerPanelView.drawPager`、`TargetSearchView`。
 * 17 个默认相里有 15 个所在的面板**根本没有翻页行**（背包/邮件/战报/任务/部队那一族用 ScrollView），
 * 四张排行榜榜行为 0 颗（`下一页` 那颗连监听都没挂，`canNext` 是服务端给的），目标搜索在首次搜索前
 * `changePage` 直接 return ⇒ 只有「已入盟」那一屏（夹具 9 项 > 一屏容量）有真第二屏。
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
  // 翻页相：第二屏的行从前只被横扫量过，植入正例没证过 ⇒ 点不到「下一页」、或点了屏幕没换，都判不合格
  { tag: 'social/alliance-joined#p2', panel: 'social', tab: 'Tab_alliance', joined: true,
    pageAction: '下一页' },
  // power 的五张榜从前只有默认那一相被量过（默认落在哪张榜由服务端下发决定）⇒ 其余四张榜的字形带
  // 从没进过植入正例。页签节点名是 `tab-<key>`（`PowerPanelView.drawTabs`），点不到的那一相会自己红
  { tag: 'power/POWER', panel: 'power', tab: 'tab-POWER' },
  { tag: 'power/KILL', panel: 'power', tab: 'tab-KILL' },
  { tag: 'power/ALLIANCE', panel: 'power', tab: 'tab-ALLIANCE' },
  { tag: 'power/NATION', panel: 'power', tab: 'tab-NATION' },
  { tag: 'power/SEASON', panel: 'power', tab: 'tab-SEASON' },
  ...DEFAULT_PANELS,
]

/**
 * 每一相至少要量到几条字形带（像素法切出的文字横带）。
 *
 * <p><b>为什么从前它只打印不判红是错的</b>（台账 #424 未做栏①，本格收口）：这一相若夹具没命中、
 * 页签没点到、面板根本没开，读数就是 0 条字形带，于是"植入前 0 处 → 植入后 0 处"看起来完全正常，
 * 门全绿而那一屏<b>从没被量过</b>。`switched`/`paged` 只挡住"相位走没走到"，挡不住"走到了但什么都没画出来"。
 *
 * <p>值取 2026-09-22 直跑 `node tools/verify-plate-plant.mjs` 的<b>实测一半</b>（不是随手挑的阈值）：
 * 正当增删几行字不许把它弄红，而"整相忽然量不到字"一定红。
 * 生成器与读数的条数交叉校验写在台账里；改布局后要重量并同步上调，别调松到全绿了事。
 */
const BAND_FLOORS = {
  'army': 13, // 实测 26
  'avatarFrames': 5, // 实测 11
  'bag': 10, // 实测 20
  'battlePass': 14, // 实测 29
  'city': 7, // 实测 15
  'gacha': 6, // 实测 12
  'hero': 2, // 实测 5
  'mail': 1, // 实测 2
  'power': 13, // 实测 27
  'power/POWER': 7, // 实测 14
  'power/KILL': 7, // 实测 14
  'power/ALLIANCE': 6, // 实测 13
  'power/NATION': 6, // 实测 13
  'power/SEASON': 3, // 实测 7
  'quest': 11, // 实测 22
  'reports': 1, // 实测 3
  'reports/scout': 2, // 实测 4
  'settings': 5, // 实测 10
  'shop': 15, // 实测 31
  'social': 18, // 实测 36
  'social/alliance': 5, // 实测 11
  'social/alliance-joined': 17, // 实测 34
  'social/alliance-joined#p2': 13, // 实测 27
  'social/chat': 11, // 实测 22
  'social/events': 8, // 实测 16
  'social/help': 8, // 实测 17
  'social/rally': 9, // 实测 19
  'stage': 10, // 实测 21
  'targets': 1, // 实测 3
  'world': 3, // 实测 6
}

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `plant-${Date.now()}`)
// 读接口夹具与横扫共用一份，且必须在任何 `page.goto` 之前挂上（晚挂等于那一相读到空态）
const social = socialFixtures()
await social.install(makeStubRead(context))

async function measure(page, panel, wantText = null) {
  const plan = await page.evaluate(planPlateCoverage, panel)
  // 2026-10-04：这里**曾经**加过一段打 `plan` 内容的诊断（`plates.length` / 每张牌带数 /
  // 场景里的牌名），用来判别「`plan.plates === 0` 会不会就是失败相位」。
  // ⚠️ **已撤掉，原因值得记住**：这段诊断在**每次 `measure` 里多打了一次 `page.evaluate`**，
  // 而 `measure` 处在时序敏感路径上 ⇒ 实测**撤掉之前连跑 4 次全绿、加了诊断之后一次失败都没复现**
  // ⇒ 结论：**「加了诊断之后变绿」不能当成「抖动不存在」**。
  // 这是本会话第二次栽在「侵入式观测改变被测行为」（第一次是给 city 探针 170 个节点挂监听）。
  // ⇒ 以后要取这类读数，**只能取不落在时序路径上的**（例如在**相位结束**时读一次汇总），
  //    绝不能挂在 `measure` 这种每相位跑三次的函数里。
  // 保留判据本身不变：`plan.plates.length === 0` 时直接短路返回 hits=0（压根没比像素）——
  // 这条短路**实测真实存在**，但**未证实**它与失败相位一一对应（见 vibiecoding 文档 16:29x）。
  if (plan === null) return { hits: -1, bands: 0, plates: 0, plantedHit: false }
  if (plan.plates.length === 0) return { hits: 0, bands: plan.bands.length, plates: 0, plantedHit: false }
  const base = decodePng(await page.screenshot())
  let hits = 0
  let plantedHit = false
  // 2026-10-05 **零新增 evaluate 的读数**：`diffRegion` 的 `d.changed` 本来就现成，
  // 这里只把它累加成 `maxChanged` 带回。用途是量化「差有多小」——
  // 判据只看 `d.changed > 0`，所以"差=3"和"差=0"在判据眼里一样，
  // 但两者指向的原因完全不同（前者=盖住了但盖得轻，后者=压根没盖住）。
  // ⚠️ 刻意**不**新加 `page.evaluate`：上一格实测证明挂在 measure 里的采集会把抖动抹平。
  // ⚠️ 声明必须在**循环外**：第一版误写在 `for (const plate …)` 体内，
  // 而 return 在循环外 ⇒ 运行时报 `maxChanged is not defined`、探针直接崩（已踩，见文档 16:32x）。
  let maxChanged = 0
  for (const plate of plan.plates) {
    await page.evaluate((h) => { window.__plateNodes[h].getComponent('cc.Graphics').enabled = false }, plate.handle)
    await page.waitForTimeout(120)
    const after = decodePng(await page.screenshot())
    await page.evaluate((h) => { window.__plateNodes[h].getComponent('cc.Graphics').enabled = true }, plate.handle)
    await page.waitForTimeout(80)
    for (const bi of plate.bands) {
      const d = diffRegion(base, after, plan.bands[bi].rect, 24)
      if (d.changed > maxChanged) maxChanged = d.changed
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
  // 2026-10-05 **零新增 evaluate**：牌自己的矩形（`planPlateCoverage` 已经算好带出来了）
  // 与它自己那些带的矩形比重叠面积占比。
  // 判别口诀：**带已挂到这张牌上（`planPlateCoverage` 就是按重叠挂的）**，
  //   ⇒ 重叠面积本该接近整块；若这里算出来**接近 0**，
  //   就说明"带矩形"与"牌矩形"在数学上就对不上 —— 指向 `diffRegion` 取到的矩形不是同一版版面。
  // ⚠️ 纯计算，不新增 evaluate。
  let worstOverlap = 1
  for (const plate of plan.plates) {
    const pr = plate.rect
    if (pr === undefined) continue
    const [px, py, pw, ph] = pr
    let covered = 0
    for (const bi of plate.bands) {
      const r = plan.bands[bi].rect
      const ox = Math.max(0, Math.min(px + pw, r.x + r.width) - Math.max(px, r.x))
      const oy = Math.max(0, Math.min(py + ph, r.y + r.height) - Math.max(py, r.y))
      const area = r.width * r.height
      if (area > 0) covered += (ox * oy) / area
    }
    const ratio = plate.bands.length === 0 ? 0 : covered / plate.bands.length
    if (ratio < worstOverlap) worstOverlap = ratio
  }
  return { hits, bands: plan.bands.length, plates: plan.plates.length, plantedHit, maxChanged, worstOverlap }
}

/**
 * 这一屏上"有哪些字"（像素法切出的字形带文本，与 `measure` 读同一份计划）。
 *
 * <p>只用来证"换了屏"，不截图 ⇒ 一次树遍历的量级。
 */
async function bandTexts(page, panel) {
  const plan = await page.evaluate(planPlateCoverage, panel)
  return plan === null ? null : plan.bands.map((b) => b.text)
}

/**
 * 翻页相要"真翻过去了"。
 *
 * <p><b>为什么"点到了壳"不够</b>：`clickRowAction` 按文本找到 Label、再沿祖先链爬到名字对得上的壳
 * `emit('touch-start')`，而**那颗壳未必挂着处理器**（社交面板的 `renderRow` 只在
 * `actionEnabled` 为真时才 `on('touch-start')`），甚至挂了也可能是空转
 * （目标搜索的 `changePage` 在 `response === null` 时直接 return，而首次搜索前两颗翻页键是活的）。
 * 两种情况下 `paged` 都会是 true，于是这一相把**第一屏量了两遍**还报绿 ——
 * 与 #424 抓到的"`ok` 里没有 `switched`"是同一族的空转。
 *
 * <p>判据因此是"点得到 **且** 屏上的字形带集合确实换了"（对称差非空）。
 */
async function pageToNext(page, panel, actionText) {
  const pre = await bandTexts(page, panel)
  const clicked = await page.evaluate(clickRowAction, actionText)
  await page.waitForTimeout(1500)
  const post = await bandTexts(page, panel)
  if (pre === null || post === null) {
    return { paged: false, proof: `点击=${clicked}·换屏=读不到那一屏` }
  }
  const gone = pre.filter((t) => !post.includes(t)).length
  const added = post.filter((t) => !pre.includes(t)).length
  return {
    paged: clicked === true && (gone > 0 || added > 0),
    // 这一串里不许有空格：逐相读数行由 `翻页=(\S+)` 抓，空格会把生成器的条数自检搅成假红
    proof: `点击=${clicked}·换出${gone}·换进${added}`,
  }
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
  const switched = phase.tab === null || await page.evaluate(clickTabNode, phase.tab)
  await page.waitForTimeout(1500)
  // 翻页相要点到「下一页」**并且真换了一屏**才量得到第二屏；点不到或点了没反应都判不合格（见 `pageToNext`）
  let paged = true
  let pageProof = '非翻页相'
  if (phase.pageAction !== undefined) {
    const p = await pageToNext(page, phase.panel, phase.pageAction)
    paged = p.paged
    pageProof = p.proof
  }
  await page.waitForTimeout(1500)
  const before = await measure(page, phase.panel)
  const planted = await page.evaluate(([panelKey, alpha]) => {
    window.__plantAlpha = alpha
    const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.children.find((c) => c.name === panelKey)
    if (!panel) return { ok: false, why: '面板没找到' }
    // 挑第一颗"够宽"的字（≥2 字，避免挑到单字符把植入面积压到噪声级）
    // 从前是 ≥3：`targets` 那一相一直靠「上一页」那颗三字的翻页键当植入对象，
    // 而 #449 把未搜索时的两颗翻页键收掉了 ⇒ 那一屏只剩两字的「搜索」（16 号字约 36px 宽，
    // 远在噪声之上）。留 2 字的下界既保住"别挑单字"的原意，又不让这一相变成没法验。
    let target = null
    const walk = (n) => {
      if (target !== null) return
      const lb = n.getComponent('cc.Label')
      if (lb !== null && (lb.string ?? '').length >= 2 && n.activeInHierarchy && n !== panel) {
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
    // 2026-10-04 **只读诊断**：把"底板到底有没有真被写进去"读出来。
    // 起因：`power` 系相位 `after.hits===0`（关掉底板后像素差一个都没过阈值 24），
    // 而上一轮 ALPHA 扫描证明"调 alpha 不解决" ⇒ 得先确认 alpha/颜色**有没有真的落到组件上**。
    // ⚠️ 只加读数，不改判据。
    const _g = plate.getComponent('cc.Graphics')
    return { ok: true, host: panel.name, text: target.getComponent('cc.Label').string.slice(0, 8),
      plantAlphaSeen: Number(window.__plantAlpha ?? -1),
      fillAlphaActual: _g === null ? null : _g.fillColor.a,
      fillColorActual: _g === null ? null : [_g.fillColor.r, _g.fillColor.g, _g.fillColor.b],
      nodeActive: plate.activeInHierarchy === true, uiSize: [Math.round(plate.getComponent('cc.UITransform').width), Math.round(plate.getComponent('cc.UITransform').height)] }
  }, [phase.panel, Number(process.env.PLANT_ALPHA ?? 255)])
  const after = planted.ok ? await measure(page, phase.panel, planted.text) : { hits: -1 }
  if (planted.ok) {
    await page.evaluate(() => { window.__probePlant?.destroy(); window.__probePlant = null })
    await page.waitForTimeout(200)
  }
  const reverted = planted.ok ? await measure(page, phase.panel) : { hits: -1 }
  const ok = before.hits === 0 && planted.ok === true && after.hits > 0
    && after.plantedHit === true && reverted.hits === 0
    // 相位没真走到就"报 0 处"，那是没量过而不是没问题 —— 从前这两条只打印不进判据
    && switched === true && paged === true
    // 走到但没量到字，等于这一相没验过 —— 与 switched/paged 同一类"别把空转当通过"
    && before.bands >= (BAND_FLOORS[phase.tag] ?? 1)
  // 2026-10-04 **只读诊断**（不改判据）：`after.hits === 0` 时再等一短延时量一次，
  // 两次读数都记下来。目的是分辨「植入真的没生效」与「**读早了**」。
  // ⚠️ 判据 `ok` 仍然只看**第一次**的 `after` —— 这里只加读数，不放宽也不收紧通过条件。
  // 实测依据：连采 3 次得 2 红 1 绿、失败 tag 每次都不同（power/KILL、power/ALLIANCE、
  // power/SEASON），症状统一 `plantedHit:false` ⇒ 非确定性抖动，疑似读早。
  let retry = null
  if (after.hits === 0) {
    await page.waitForTimeout(400)
    retry = await measure(page, phase.panel, planted.text)
    console.log(`  [retry] ${phase.tag}：等 400ms 后复量 hits=${retry.hits} 命中被植字=${retry.plantedHit}`
      + `（第一次 hits=${after.hits}）⇒ ${retry.hits > 0 ? '**读早了**：延时后能看到' : '延时后仍看不到'}`)
  }
  results.push({ tag: phase.tag, switched, paged, pageProof, before: before.hits, planted,
    after: after.hits, plantedHit: after.plantedHit === true, reverted: reverted.hits,
    bands: before.bands, ok, retry: retry === null ? null : { hits: retry.hits, plantedHit: retry.plantedHit } })
  console.log(`  ${phase.tag}: 切页签=${switched} 翻页=${paged}(${pageProof}) 字形带=${before.bands} 条（下限 ${BAND_FLOORS[phase.tag] ?? 1}）；植入前 ${before.hits} → `
    + `植入后 ${after.hits}（命中被植字=${after.plantedHit === true} 识别到牌=${after.plates} 最大像素差=${after.maxChanged} 最小重叠=${Math.round((after.worstOverlap ?? -1)*100)}%）→ 撤掉后 ${reverted.hits}；`
    + `植入=${JSON.stringify(planted)} ⇒ ${ok ? 'OK' : '不合格'}`)
  await page.close()
}

await browser.close()
await preview.close()
const bad = results.filter((r) => !r.ok)
console.log(`\n[plant] 全部相位：合格 ${results.length - bad.length} / ${results.length}`)
for (const b of bad) console.log(`  不合格 ${b.tag}：${JSON.stringify(b)}`)
process.exit(bad.length === 0 ? 0 : 1)
