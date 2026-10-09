import { existsSync, mkdirSync, readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const OUT = process.env.ART_VERIFY_OUT
  ?? path.resolve(process.cwd(), 'client/build/art-verify')
mkdirSync(OUT, { recursive: true })

/**
 * 产物**由本工具自己起服务托管**，不再依赖"有人另外开了一个 8090"。
 *
 * <p>理由不是洁癖：这台机器上两个会话会同时跑量具，8090 被谁占着都看不出来 ——
 * 本轮第一次跑就在第五格撞到 `ERR_CONNECTION_REFUSED`（服务是**别人的**、被别人关掉了），
 * 而读数看着像是产物坏了。默认端口也换成 8190，避免与仍在用 8090 的那一份互踩。
 */
const ART_PORT = Number(process.env.ART_VERIFY_PORT ?? 8190)
/**
 * 产物根目录。**默认逐字节等于改动前**（模式隔离），加它只为了一件事：
 * 植入取证要改某张图的 `.png.meta` 里的 border，而 meta 只有**重新构建**才进得了运行时
 * —— 直接重建共享的 `client/build/web-mobile` 会把并行会话正在跑的探针产物清空（`AGENTS.md` 三节）。
 * 所以取证走 `outputName=<独立名>` 建一份，再用本变量指过去。
 */
const ART_ROOT = process.env.ART_VERIFY_ROOT ?? 'client/build/web-mobile'
/**
 * 后端默认仍是产物里写死的那台（8080）；要指向**本轮自己起的那台**用 `ART_VERIFY_BACKEND`。
 *
 * <p>加这个开关不是洁癖：本工具里另有三处 `fetch('http://localhost:8080/…')`
 * （首抽、运维补发道具、领取），只换托管用的那台就变成"页面读我这台、种数据打到别人那台"。
 * 2026-09-21 复检正是这么踩的：`heroPortrait` 判红，根因是那次首抽打到了别的会话 09-19 起的
 * 旧实例上（那台还不下发 `BuildingView.name`）。三处 fetch 与本行统一取同一个 BACKEND。
 */
const BACKEND = process.env.ART_VERIFY_BACKEND ?? 'http://localhost:8080'
const preview = await startPreviewServer({
  root: ART_ROOT,
  backend: BACKEND,
  port: ART_PORT,
})

/**
 * 两个**只给对照实验用**的开关（默认关；关着时行为与本改动前逐字一致）：
 *
 * - `ART_VERIFY_DEVICE`：复用一个已有 deviceId。默认每轮新号 ⇒ 城里只有主城，
 *   "建了才叠正稿"那条判据永远走不到；复用它就能先在同一玩家上建一栋、再跑本工具。
 * - `ART_VERIFY_BLOCK=<URL 片段>`：把 URL 含该片段的请求答成 404（素材多半用 uuid 认，
 *   如 `2ded851f…` 是参考图底图、`68493275…` 是伐木场正稿）。它是**负向对照**用的：
 *   拦掉参考图底图 ⇒ 程序化城景那一条必须接上；拦掉某张建筑正稿 ⇒ `cityIcons` 那条必须红。
 *   判据能不能失败，靠它证明；平时不要开。
 */
const deviceId = process.env.ART_VERIFY_DEVICE ?? `art-runtime-${Date.now()}`
const BLOCK = process.env.ART_VERIFY_BLOCK ?? ''
/**
 * 每次 `page.goto` 之后的落定等待。默认 1600ms = 本改动前的固定值，**不设就等于没改**。
 * 开了 `ART_VERIFY_BLOCK` 做对照实验时要调大（4000~5000）：Playwright 一旦注册路由拦截，
 * 这个页面的 HTTP 缓存就被禁用，各面板首次导航明显变慢，1600ms 会读到"场景还没建好"
 * （实测症状是世界地图那一步抛 `WorldMap 组件不在场景里` —— 量具自己造的噪声）。
 */
const SETTLE_MS = Number(process.env.ART_VERIFY_SETTLE_MS ?? 1600)
/**
 * 等某个面板真的就位（`PanelNav.currentKey === panel`）的上限。故意给得很宽：
 * 这是**反空转**，不是时长预算 —— 超时后照样往下走，读数会以 `panelMismatches` 的形式红出来。
 */
const PANEL_READY_MS = Number(process.env.ART_VERIFY_PANEL_READY_MS ?? 30000)
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, deviceId)
const page = await context.newPage()
if (BLOCK !== '') {
  // **只拦命中的 URL**，不要写成 `**/*` 全量拦截：全量拦截会让每个请求都过一遍 router，
  // 实测把后面世界地图那一步的 1600ms 等待吃掉，偶发报「WorldMap 组件不在场景里」——
  // 那是量具自己造的噪声，不是产品缺陷。BLOCK 传的是 URL 里的一段（多半是素材的 uuid）。
  await page.route(`**/*${BLOCK}*`, (route) => route.fulfill({
    status: 404, contentType: 'text/plain', body: 'blocked by ART_VERIFY_BLOCK',
  }))
}
const errors = []
const warnings = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
  if (message.type() === 'warning' || message.type() === 'warn') warnings.push(message.text())
})

async function inspectPanel(panel) {
  // 必须在 URL 上拼查询串：Cocos 的 loader 会把裸字符串里的 `&` 当分隔符截断，
  // 于是 "?panel=world&verify=1" 实际打开默认面板，校验却仍可能全绿。
  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', panel)
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
  /**
   * **等这个面板真的就位，而不是只睡固定毫秒**（2026-09-21 补）。
   *
   * <p>注册路由拦截（`ART_VERIFY_BLOCK`）会让 Playwright **禁用该页 HTTP 缓存**，boot 从
   * ~2.6s 涨到 ~8.6s（本轮实测：`tmp/diag-ref-boot.mjs`，同一份产物只差拦不拦）。
   * 而固定等待读到的是**还没建好的场景**：表现是"五个面板全 null、截图全黑"——
   * 与"素材缺失导致应用起不来"在读数上**一模一样**，本轮据此误判过一条并不存在的白屏缺陷（审计 §11.6）。
   * 判据用面板自己的状态，不用时长；超时也继续，让 `panelMismatches` 去红。
   */
  await page.waitForFunction((expected) => {
    const scene = window.cc.director.getScene()
    const nav = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
    return nav !== null && nav !== undefined && nav.currentKey === expected
  }, panel, { timeout: PANEL_READY_MS }).catch(() => {})
  await page.waitForTimeout(SETTLE_MS)
  await hideGuideOverlay(page)
  const activePanel = await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const nav = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
    return nav?.currentKey ?? null
  })
  // 先截图再收集：建筑/背包数据可能在两次读之间到达，先收集会把"截图里有图标、计数却是 0"
  // 的竞态固化成假红（排版轮真撞到过一次）
  await page.screenshot({ path: path.join(OUT, `art-${panel}-runtime.png`) })
  const sprites = await collectSprites()
  const fonts = await collectFonts()
  return { activePanel, sprites, fonts }
}

async function collectFonts() {
  return page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const out = []
    const visit = (node) => {
      const label = node.getComponent && node.getComponent('cc.Label')
      if (label !== null && label !== undefined) {
        out.push({
          name: node.name,
          useSystemFont: label.useSystemFont,
          fontFamily: label.fontFamily,
        })
      }
      for (const child of node.children) visit(child)
    }
    visit(scene)
    return out
  })
}

async function collectSprites() {
  return page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const spriteTypes = window.cc.Sprite.Type
    const out = []
    const visit = (node) => {
      const sprite = node.getComponent && node.getComponent('cc.Sprite')
      if (sprite !== null && sprite !== undefined && sprite.spriteFrame !== null) {
        const transform = node.getComponent('cc.UITransform')
        out.push({
          name: node.name,
          enabled: sprite.enabled,
          // 层内 active：判"参考舞台在场时还叠着主城正稿"要用它 —— 只看 spriteFrame 有没有挂，
          // 会把"挂了但整个节点是关闭的"也算成重影。
          activeInHierarchy: node.activeInHierarchy,
          type: sprite.type,
          frameName: sprite.spriteFrame.name,
          typeName: Object.keys(spriteTypes).find((key) => spriteTypes[key] === sprite.type) ?? null,
          contentWidth: transform !== null && transform !== undefined ? transform.width : null,
          contentHeight: transform !== null && transform !== undefined ? transform.height : null,
          insetLeft: sprite.spriteFrame.insetLeft,
          insetTop: sprite.spriteFrame.insetTop,
          insetRight: sprite.spriteFrame.insetRight,
          insetBottom: sprite.spriteFrame.insetBottom,
          width: sprite.spriteFrame.rect.width,
          height: sprite.spriteFrame.rect.height,
          x: sprite.spriteFrame.rect.x,
          y: sprite.spriteFrame.rect.y,
        })
      }
      for (const child of node.children) visit(child)
    }
    visit(scene)
    return out
  })
}

/**
 * 按需族（背包 items + 装备 equip + 武将 heroes）的判据用 **resources 包 png 请求数的差值**：
 * 开背包前记基线，走完背包/武将/世界后记终值 —— 增量必须恰为该三族在磁盘上的 png 张数。
 * 期望值**从磁盘算**而不是抄一个数：A10 一次加六张图，抄的那个数当场就过期了
 * （报 41 而真跑 47，看着像"多加载了六张不知哪来的图"，实际是我自己没跟上）。
 * 能失败的方式：某面板不再 ensureFamily → 增量缺该族的张数；
 * 族表与磁盘脱节 → 某张 404（增量不足且 catalogWarnings 变红）；
 * 有人把族图塞回启动预载 → city 阶段基线被抬高，增量同样对不上。
 * 代价（如实记下）：从**磁盘**删一张图会让期望值跟着降，本判据不再红 ——
 * 那一半由 `tests/ArtFamilies.test.ts` 的"族键 → 磁盘 PNG"对账管着，两条合起来才闭环。
 */
const FAMILY_SOURCE_DIRS = [
  'client/assets/resources/ui/generated/items',
  'client/assets/resources/ui/generated/equip',
  'client/assets/resources/ui/generated/heroes',
]
const missingFamilyDirs = FAMILY_SOURCE_DIRS.filter((dir) => !existsSync(dir))
if (missingFamilyDirs.length > 0) {
  // 前置不满足 ≠ 判据失败：退 2 并说出缺什么，别让"目录没找到"读成"图没加载"
  console.error(`[verify-art][前置] 族目录不存在：${missingFamilyDirs.join(', ')}（在仓库根跑本工具）`)
  process.exit(2)
}
const FAMILY_PNG_EXPECTED = FAMILY_SOURCE_DIRS
  .reduce((sum, dir) => sum + readdirSync(dir).filter((n) => n.endsWith('.png')).length, 0)
/** 活动族（activity.json 八行）单独量：任务面板在 bag/hero 之后才打开，混进上面那条会互相遮蔽。 */
const ACTIVITY_PNG_EXPECTED = 8
/**
 * A18 的 15 张建筑正稿**不按 URL 数**：Cocos 打包后 resources 里的图落在
 * `/assets/resources/<bundle>/<hash>/…-<hash>.png` 这种路径上，源目录
 * `ui/generated/buildings/` 在 URL 里一个字都不出现（实测按目录与前缀两种过滤都是 0，
 * 而帧名断言与 `familyBeforeBag` 的增量都说明 15 张确实下发了）。
 * 这一族的加载判据换成两条更硬的：
 * ① `iconMappings.cityMain` —— 主城格子上画的是 `building-main-city-v1` 这张**正稿**，
 *    不是图集小图标（按帧名认，不按矩形位置认，理由见那里）；
 * ② `catalogWarnings` 为空 —— `ensureFamily` 对**每一个成员**单独告警，
 *    15 张里任何一张拉不到都会在这里红，比数 URL 更直接。
 */
const resourcePngRequests = new Set()
page.on('request', (request) => {
  const url = request.url()
  if (url.includes('/assets/resources/') && url.endsWith('.png')) {
    resourcePngRequests.add(url)
  }
})

/**
 * 九宫格框的**带内排版**判定。两张套 `ui.panel.kingdom` 的框（内城卡片 / 行军面板）里，
 * 每个带内容（Label 或 Sprite）的节点矩形都必须落在"四角带以内"。
 *
 * <p>为什么几何判据而不是截图比对：#204 在内城修过"标题压在角饰上"，#213 在行军面板上
 * 又出现一次同一形状 —— 只数"画没画出来"永远看不见"画对了但盖在装饰上"。
 *
 * <p>带厚从源码现读（`PANEL_FRAME_BAND`），并和**运行期 SpriteFrame 的实际 insets** 对账：
 * 两边不一致就是"切分几何又分了家"（本轮拆掉的正是 ArtCatalog 里那份后写的覆盖）。
 * 解析失败/读到 0 直接算错误 —— 否则安全区会等于整块面板，判据恒真。
 *
 * <p>**必须在各自那一次导航之后立刻取数**：`inspectPanel` 每次都重新 `page.goto`，
 * 上一格的面板树已经随页面重载消失（第一版把两次检查都放在最后，读到的就是"节点不在场景里"）。
 */
const bandInSource = readFileSync('client/assets/scripts/game/art/ArtFamilies.ts', 'utf8')
  .match(/export const PANEL_FRAME_BAND = (\d+)/)
const FRAME_BAND = bandInSource === null ? 0 : Number(bandInSource[1])

async function collectFrameLayout(checks) {
  return page.evaluate(({ band, specs }) => {
    const scene = window.cc.director.getScene()
    const byName = (name) => {
      let hit = null
      const visit = (n) => {
        if (hit !== null) return
        if (n.name === name) { hit = n; return }
        for (const child of n.children) visit(child)
      }
      visit(scene)
      return hit
    }
    const out = {}
    for (const spec of specs) {
      const root = byName(spec.root)
      const frameNode = spec.root === spec.frame ? root : (root === null ? null : byName(spec.frame))
      if (root === null || frameNode === null) {
        out[spec.root] = { root: spec.root, error: `${root === null ? spec.root : spec.frame} 不在场景里` }
        continue
      }
      const frameBox = frameNode.getComponent('cc.UITransform')
      const sprite = frameNode.getComponent('cc.Sprite')
      if (frameBox === null || sprite === null || sprite.spriteFrame === null) {
        out[spec.root] = { root: spec.root, error: `${spec.frame} 没有套上九宫格 Sprite` }
        continue
      }
      const live = sprite.spriteFrame
      const width = frameBox.width
      const height = frameBox.height
      const safe = {
        left: -width / 2 + band, right: width / 2 - band,
        top: height / 2 - band, bottom: -height / 2 + band,
      }
      const violations = []
      let counted = 0
      let labeled = 0
      const walk = (node, x, y) => {
        for (const child of node.children) {
          const cx = x + child.position.x
          const cy = y + child.position.y
          if (spec.skip.includes(child.name)) {
            // 覆盖层（选项弹层）是**整屏**的一层，不是卡片内容 —— 按带内判它，就是在判一个不该带的约束
            continue
          } else {
            const box = child.getComponent('cc.UITransform')
            const label = child.getComponent('cc.Label')
            const sprite = child.getComponent('cc.Sprite')
            const graphics = child.getComponent('cc.Graphics')
            // "看得见"才算内容：空串 Label 的 UITransform 是 lineHeight 撑出来的 50.4 高、
            // 甚至 100×100 默认档，拿它判压带会把没画字的节点也算成缺陷（本轮第一版就这样假红过）。
            const ink = (label !== null && label.string !== '')
              || (sprite !== null && sprite.enabled === true && sprite.spriteFrame !== null)
              || (graphics !== null && graphics.enabled === true)
            if (box !== null && ink) {
              counted++
              if (label !== null && label.string !== '') labeled++
              const left = cx - box.anchorX * box.width
              const right = left + box.width
              // 文字的量字高：overflow=NONE 的单行 Label 盒高由 lineHeight（引擎默认 40）决定，
              // 而字是垂直居中画的 —— 用盒高判会把上下各 14px 的空白也算成"压在角饰上"。
              const vExtent = label !== null && label.string !== ''
                ? Math.min(box.height, label.fontSize * 1.2) : box.height
              const bottom = label !== null && label.string !== ''
                ? cy - vExtent / 2 : cy - box.anchorY * box.height
              const top = label !== null && label.string !== ''
                ? cy + vExtent / 2 : bottom + box.height
              const over = []
              if (left < safe.left - 0.5) over.push(`左压带 ${(safe.left - left).toFixed(1)}px`)
              if (right > safe.right + 0.5) over.push(`右压带 ${(right - safe.right).toFixed(1)}px`)
              if (top > safe.top + 0.5) over.push(`上压带 ${(top - safe.top).toFixed(1)}px`)
              if (bottom < safe.bottom - 0.5) over.push(`下压带 ${(safe.bottom - bottom).toFixed(1)}px`)
              if (over.length > 0) violations.push(`${child.name}: ${over.join('、')}`)
            }
          }
          walk(child, cx, cy)
        }
      }
      walk(root, 0, 0)
      out[spec.root] = {
        root: spec.root, width, height, counted, labeled, violations,
        liveInsets: [live.insetLeft, live.insetTop, live.insetRight, live.insetBottom],
      }
    }
    return out
  }, { band: FRAME_BAND, specs: checks })
}

const cityResult = await inspectPanel('city')

/**
 * 内城的**城景形态**探针（2026-09-21 随判据一起改，理由见 `cityStage*` 那几条判据的注释）。
 *
 * <p>读三件事，全部从运行期场景/视口现读，不在工具里抄第二份：
 * ① 满屏参考舞台在不在场（`CityReferenceScene` 及其帧名）；
 * ② 城景内容区（`CityGrid`）是不是等于视口 ⇒ 满屏改造与 resize 重排的机器判据；
 * ③ 面板标题里的「内城 · 建筑 N/36」⇒ 判"该有几栋正稿"时用它，不用工具自己编一个数。
 */
async function collectCityStage() {
  return page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const byName = (name) => {
      let hit = null
      const visit = (node) => {
        if (hit !== null) return
        if (node.name === name) { hit = node; return }
        for (const child of node.children) visit(child)
      }
      visit(scene)
      return hit
    }
    const reference = byName('CityReferenceScene')
    const referenceSprite = reference === null ? null : reference.getComponent('cc.Sprite')
    const referenceFrame = referenceSprite !== null && referenceSprite.spriteFrame !== null
      ? referenceSprite.spriteFrame.name : null
    const grid = byName('CityGrid')
    const gridBox = grid === null ? null : grid.getComponent('cc.UITransform')
    const cityView = grid?.parent?.getComponent('CityPanelView')
      ?? scene.getComponentInChildren('CityPanelView')
    const depthByGrid = new Map((cityView?.gridTiles ?? [])
      .map((tile) => [tile.node.name, tile.plate.depth]))
    const depthOrder = (grid?.children ?? []).filter((node) => /^Grid-\d+$/.test(node.name))
      .map((node) => ({ name: node.name, depth: depthByGrid.get(node.name) ?? null }))
    const visible = window.cc.view.getVisibleSize()
    let builtCount = null
    let builtTotal = null
    const lookForHeader = (node, shown) => {
      const on = shown && node.active !== false
      const label = node.getComponent && node.getComponent('cc.Label')
      if (on && label !== null && label !== undefined && label.string !== '') {
        const hit = /内城\s*·\s*建筑\s*(\d+)\s*\/\s*(\d+)/.exec(label.string)
        if (hit !== null) { builtCount = Number(hit[1]); builtTotal = Number(hit[2]) }
      }
      for (const child of node.children) lookForHeader(child, on)
    }
    lookForHeader(scene, true)
    return {
      referenceVisible: reference !== null && reference.activeInHierarchy === true
        && referenceFrame !== null,
      referenceFrame,
      gridSize: gridBox === null ? null : [Math.round(gridBox.width), Math.round(gridBox.height)],
      visibleSize: [Math.round(visible.width), Math.round(visible.height)],
      builtCount,
      builtTotal,
      depthOrder,
    }
  })
}
const cityStageProbe = await collectCityStage()

/**
 * 导航格文字的**对比度**判定。导航格只有 63×44，而按钮母版是 384×143、端帽 54/边框 40 ——
 * 九宫格在这里退化成"整张图缩小"，格子的中心永远是深色皮革。
 * 于是"选中态用深色字"这条前提（为 Graphics 兜底的亮铜底设计的）在 art 在时不成立：
 * 选中格的字直接糊进底里（本轮截图实测：内城选中时看不见"内城"两个字）。
 * 判据：凡是画上了按钮图的格子，文字相对亮度必须 ≥ 0.35；且选中格与未选中格必须不同色。
 */
async function collectNavContrast() {
  return page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const game = scene.getChildByName('Canvas')?.getChildByName('Game')
    const bar = game?.getChildByName('NavBar')
    if (bar === null || bar === undefined) return { error: 'NavBar 不在场景里' }
    const nav = game?.getComponent('PanelNav')
    /**
     * 「更多」抽屉里的格子挂在 `NavMoreLayer/NavMoreTray` 下，整层默认不激活 ——
     * 但节点与组件都已经建好，读它们的 Sprite/Label 不需要先展开（展开只改 active）。
     * 只收 NavBar 会漏掉 10 格，于是"每格都得有页签图 / 字要看得清"这两条对抽屉里的入口失效。
     */
    const tray = game?.getChildByName('NavMoreLayer')?.getChildByName('NavMoreTray')
    const cells = []
    const collect = (parent, group) => {
      for (const cell of parent?.children ?? []) {
        if (!cell.name.startsWith('Nav-')) continue
        const sprite = cell.getComponent('cc.Sprite')
        const graphics = cell.getComponent('cc.Graphics')
        const caption = cell.getChildByName('Caption')
        const label = caption !== null && caption !== undefined ? caption.getComponent('cc.Label') : null
        if (label === null) continue
        const c = label.color
        const lum = (0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b) / 255
        cells.push({
          key: cell.name.slice('Nav-'.length),
          group,
          background: sprite !== null && sprite.enabled === true && sprite.spriteFrame !== null
            ? 'art' : (graphics !== null && graphics.enabled === true ? 'graphics' : 'none'),
          frameName: sprite !== null && sprite.spriteFrame !== null
            ? `${sprite.spriteFrame.name}|${sprite.spriteFrame.texture ? sprite.spriteFrame.texture.name : ''}`
            : null,
          luminance: Number(lum.toFixed(3)),
          color: `${c.r},${c.g},${c.b}`,
          active: nav !== null && nav !== undefined && nav.current() === cell.name.slice('Nav-'.length),
        })
      }
    }
    collect(bar, 'bar')
    collect(tray, 'more')
    return { cells }
  })
}
const navContrast = await collectNavContrast()
/** 导航格数从 PanelNav 现读，不在工具里抄第二份清单（同 verify-devtools-panels 的口径）。 */
const NAV_CELLS_EXPECTED = (readFileSync('client/assets/scripts/scene/PanelNav.ts', 'utf8')
  .match(/^\s*\{ key: '/gm) ?? []).length
/**
 * 面板 key 清单也从 PanelNav 现读：**格数相等不代表格子对得上** ——
 * `MORE_KEYS` 里写错一个 key，那一格会从抽屉挪回常驻条，总数一格不变（18 还是 18），
 * 只有按 key 逐个对才看得出"某个面板根本没有入口"。
 */
const NAV_KEYS_EXPECTED = Array.from(readFileSync('client/assets/scripts/scene/PanelNav.ts', 'utf8')
  .matchAll(/^\s*\{ key: '([^']+)'/gm), (m) => m[1])
/**
 * 抽屉清单也从源码现读。`MORE_KEYS` 里写错一个 key 时那一格只是从抽屉挪回常驻条 ——
 * 17 个 key 一个不少、`navMissingCells` 为空，按 key 对账抓不住它；
 * 抓得住的是**分边计数**（条上 = 17 - |MORE_KEYS|、抽屉 = |MORE_KEYS|）。
 */
const MORE_BLOCK = (readFileSync('client/assets/scripts/scene/PanelNav.ts', 'utf8')
  .match(/const MORE_KEYS: readonly string\[\] = \[([\s\S]*?)\]/) ?? [null, ''])[1]
const MORE_KEYS_EXPECTED = Array.from(MORE_BLOCK.matchAll(/'([^']+)'/g), (m) => m[1])
const navCellsSeen = (navContrast.cells ?? []).filter((cell) => cell.key !== 'more')
const navMissingCells = NAV_KEYS_EXPECTED.filter((key) => !navCellsSeen.some((cell) => cell.key === key))
const navBarCells = (navContrast.cells ?? []).filter((cell) => cell.group === 'bar')
const navTrayCells = (navContrast.cells ?? []).filter((cell) => cell.group === 'more')
const navLowContrast = (navContrast.cells ?? [])
  .filter((cell) => cell.background === 'art' && cell.luminance < 0.35)
  .map((cell) => `${cell.key} 文字亮度 ${cell.luminance} < 0.35（色 ${cell.color}，底是按钮图的深色中心）`)
const navActiveIndistinguishable = (navContrast.cells ?? []).length > 0
  && (navContrast.cells ?? []).filter((cell) => cell.active)
    .every((cell) => (navContrast.cells ?? [])
      .filter((other) => !other.active)
      .every((other) => other.color === cell.color))
/**
 * 页签图真的铺上了没（G8）：常驻条与抽屉里的**每一格**都必须是 nav-tab，
 * 且选中那一格必须换成 selected 变体。
 * 能失败的方式：PanelNav 退回按钮九宫格（帧名对不上）、selected 键没进启动预载（帧为 null）、
 * 抽屉里的格子走了另一套构造（收进 `createCell` 之前正是这个形状）。
 */
const navTabMissing = (navContrast.cells ?? [])
  .filter((cell) => cell.frameName === null || !cell.frameName.includes('nav-tab'))
  .map((cell) => `${cell.key}=${cell.frameName}`)
const navSelectedWrongFrame = (navContrast.cells ?? [])
  .filter((cell) => cell.active && cell.frameName !== null && !cell.frameName.includes('selected'))
  .map((cell) => `选中格 ${cell.key} 用的还是 ${cell.frameName}`)
const familyBeforeBag = resourcePngRequests.size
const bagResult = await inspectPanel('bag')
const armyResult = await inspectPanel('army')
const city = cityResult.sprites
const bag = bagResult.sprites
const army = armyResult.sprites
const drawResult = await page.evaluate(async ({ playerId, base }) => {
  const initResponse = await fetch(`${base}/player/init`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      requestId: `art-verify-init-${Date.now()}`,
      deviceId: playerId,
      nickName: 'ArtVerify',
      clientTime: Date.now(),
    }),
  })
  const init = await initResponse.json()
  if (init.code !== 0) {
    return init
  }
  const response = await fetch(`${base}/gacha/draw`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Player-Id': init.data.playerId,
    },
    body: JSON.stringify({
      requestId: `art-verify-${Date.now()}`,
      poolId: 'gacha_pool_newbie',
      count: 1,
    }),
  })
  return response.json()
}, { playerId: deviceId, base: BACKEND })
if (drawResult.code !== 0) {
  errors.push(`新号首抽失败：${drawResult.msg ?? JSON.stringify(drawResult)}`)
}
const OPS_TOKEN = process.env.ART_VERIFY_OPS_TOKEN ?? 'art-verify-local'

/**
 * 行级画面验证：新号背包是空的，行图标画没画出来根本看不到。
 * 用 /ops/mail/send（运维补发，本机 dev 令牌）塞四件已映射道具 → claimAll 落包 →
 * 重开背包切到"背包"页签。判据：页签内"有 Sprite 的行数 ≥ 道具行数 ≥ 4"。
 * （曾试过塞 eq_iron_sword：装备行走 #166 的实例账本，不在背包页渲染 —— 装备图标的
 * 行级验证属装备面板批次，此处由 tests/ArtFamilies.test.ts 的映射对账兜底。）
 */
const seeded = await page.evaluate(async ({ playerId, token, base }) => {
  const init = await (await fetch(`${base}/player/init`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ requestId: `art-seed-init-${Date.now()}`, deviceId: playerId,
      nickName: 'ArtSeed', clientTime: Date.now() }),
  })).json()
  if (init.code !== 0) return { step: 'init', init }
  const mail = await (await fetch(`${base}/ops/mail/send`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Ops-Token': token },
    body: JSON.stringify({
      requestId: `art-seed-${Date.now()}`, playerId: init.data.playerId,
      title: '美术验证附件', text: '行图标验证用', actor: 'art-verify',
      rewards: [
        { type: 'ITEM', id: 'item_chest_hero', count: 1, name: '武将匣' },
        { type: 'ITEM', id: 'item_chest_resource', count: 1, name: '资源匣' },
        { type: 'ITEM', id: 'item_speedup_build_1h', count: 2, name: '建造加速' },
        { type: 'ITEM', id: 'item_buff_peace_24h', count: 1, name: '免战牌' },
      ],
    }),
  })).json()
  if (mail.code !== 0) return { step: 'mail', mail }
  const claim = await (await fetch(`${base}/mail/claimAll`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Player-Id': init.data.playerId },
    body: JSON.stringify({ requestId: `art-claim-${Date.now()}` }),
  })).json()
  if (claim.code !== 0) return { step: 'claim', claim }
  return { ok: true, playerId: init.data.playerId, claimed: claim.data.claimed }
}, { playerId: deviceId, token: OPS_TOKEN, base: BACKEND })
if (seeded.ok !== true) {
  errors.push(`背包道具种子失败：${JSON.stringify(seeded)}`)
}

const heroResult = await inspectPanel('hero')
const worldResult = await inspectPanel('world')
const familyAfterBag = resourcePngRequests.size
const hero = heroResult.sprites
const world = worldResult.sprites

/** 只读实际场景几何；合规迷雾与已探索地形分别计数，不能把黑雾面积报成漏绘。 */
async function collectWorldSceneLayout() {
  return page.evaluate(() => {
    const scene = window.cc.director.getScene()
    let map = null
    const visit = (node) => {
      const component = node.getComponent?.('WorldMap')
      if (component) map = component
      for (const child of node.children) visit(child)
    }
    visit(scene)
    if (!map?.sceneLayout || !map.mapLayer) return { error: 'WorldMap 净区布局读不到' }
    const layout = map.sceneLayout
    const visible = window.cc.view.getVisibleSize()
    const backdrop = map.backdropNode?.getComponent('cc.UITransform')
    const toolbar = map.toolbarNode
    const buttons = layout.buttons.map((button) => {
      const node = toolbar?.getChildByName(button.name)
      const box = node?.getComponent('cc.UITransform')
      const sprite = node?.getComponent('cc.Sprite')
      return { name: button.name, x: node?.position.x, y: node?.position.y,
        width: box?.width, height: box?.height,
        sliced: sprite?.type === window.cc.Sprite.Type.SLICED }
    })
    const tiles = [...map.drawnTiles.values()]
    return {
      visible: [visible.width, visible.height],
      layout: { width: layout.width, height: layout.height, hudHeight: layout.hudHeight,
        mapBottom: layout.mapBottom, mapTop: layout.mapTop, mapHeight: layout.mapHeight },
      backdropSize: backdrop ? [backdrop.width, backdrop.height] : null,
      toolbarCount: map.hudLayer.children.filter((node) => node.name === 'WorldToolbar').length,
      buttons,
      tileCount: tiles.length,
      exploredTileCount: tiles.filter((node) => map.refs.get(node)?.spriteNode.active).length,
      fogTileCount: tiles.filter((node) => map.refs.get(node)?.tilePaintSignature?.includes(':true:')).length,
      entityCount: map.drawnEntities.size,
      coord: map.coordLabel?.string ?? '',
    }
  })
}

const worldSceneLayouts = [await collectWorldSceneLayout()]

/**
 * 名牌与选中环只在放大档出现（zoom 0 按设计不写 caption）。
 * 调 WorldMap 的公开 zoomIn **一档**后：屏内必须数得到非空名牌文字 —— 数不到就是
 * drawCaptionPlate 断了线。两档会按设计切进城市档，截出来的就不是世界地图了
 * （排版轮实测撞到过一次）。截图另存，供人工比对同类 SLG 的名牌观感。
 *
 * <p>**读不到 `WorldMap` 时不再抛**：原来这里直接 `throw`，于是整个量具**一行读数都不打印**
 * 就死了 —— 而世界地图那三条判据（`worldCaptions`/`terrainTiles`/`entityArt`）本来就是红的，
 * 不需要再用崩溃表达一次；崩溃真正的代价是"内城那几条到底绿没绿"也无从判读
 * （2026-09-21 复检做对照实验时连撞两次）。改成记一条 error 再往下走。
 */
const worldZoomError = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let map = null
  const visit = (n) => {
    if (map !== null) return
    const c = n.getComponent && n.getComponent('WorldMap')
    if (c !== null && c !== undefined) { map = c; return }
    for (const child of n.children) visit(child)
  }
  visit(scene)
  if (map === null) return 'WorldMap 组件不在场景里'
  map.zoomIn()
  return null
})
if (worldZoomError !== null) {
  errors.push(`世界地图放大档没能验证：${worldZoomError}`)
}
await page.waitForTimeout(900)
const worldZoom = await collectSprites()
const worldCaptions = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let count = 0
  const visit = (n) => {
    const label = n.getComponent && n.getComponent('cc.Label')
    if (label !== null && label !== undefined && n.name === 'Caption'
      && label.string !== '') {
      count++
    }
    for (const child of n.children) visit(child)
  }
  visit(scene)
  return count
})
await page.screenshot({ path: path.join(OUT, 'art-world-zoom-runtime.png') })

// 真实浏览器点击已画出来的实体。坐标从同一 MapLayer 经相机换算，不能只调用 handleTap 伪造命中。
const worldEntityTarget = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let map = null
  const visit = (node) => {
    const component = node.getComponent?.('WorldMap')
    if (component) map = component
    for (const child of node.children) visit(child)
  }
  visit(scene)
  const camera = scene.getComponentInChildren('cc.Camera')
  const canvas = document.querySelector('canvas')
  if (!map || !camera || !canvas) return null
  const rect = canvas.getBoundingClientRect()
  const pixels = window.cc.view.getVisibleSizeInPixel()
  for (const [key, node] of map.drawnEntities) {
    const transform = node.getComponent('cc.UITransform')
    const screen = camera.worldToScreen(transform.convertToWorldSpaceAR(new window.cc.Vec3(0, 0, 0)))
    const x = rect.left + screen.x / pixels.width * rect.width
    const y = rect.top + rect.height - screen.y / pixels.height * rect.height
    const localY = node.position.y + map.mapLayer.position.y
    if (x > rect.left + 20 && x < rect.right - 20
      && localY > map.sceneLayout.mapBottom + 20 && localY < map.sceneLayout.mapTop - 20) {
      return { key, x, y }
    }
  }
  return null
})
let worldEntityClick = { tested: false, expected: null, selected: null }
if (worldEntityTarget) {
  await page.mouse.click(worldEntityTarget.x, worldEntityTarget.y)
  await page.waitForTimeout(100)
  const selected = await page.evaluate(() => {
    const visit = (node) => node.getComponent?.('WorldMap')
      ?? node.children.map(visit).find(Boolean)
    return visit(window.cc.director.getScene())?.selectedKey ?? null
  })
  worldEntityClick = { tested: true, expected: worldEntityTarget.key, selected }
}
worldSceneLayouts.push(await collectWorldSceneLayout())
for (const [width, height] of [[1280, 720], [375, 667], [1440, 900]]) {
  await page.setViewportSize({ width, height })
  await page.waitForTimeout(350)
  worldSceneLayouts.push(await collectWorldSceneLayout())
  if (width !== 1440) await page.screenshot({ path: path.join(OUT, `art-world-${width}x${height}-runtime.png`) })
}

/**
 * 行军面板：先 show() 再量。构造函数里 Label 的 string 还是空串，而空串的 Label 宽度是 0 ——
 * 量不到"压没压带"，只数得到节点。WorldMap.update 每帧把表头与行刷出来，所以 show 之后等一下。
 * 判据里的 `labeled` 就是这条的防空转：一个非空文字都没有时，这一段的绿不作数。
 */
await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let map = null
  const visit = (n) => {
    if (map !== null) return
    const c = n.getComponent && n.getComponent('WorldMap')
    if (c !== null && c !== undefined) { map = c; return }
    for (const child of n.children) visit(child)
  }
  visit(scene)
  if (map !== null && map.marchPanel !== null && map.marchPanel !== undefined) map.marchPanel.show()
})
await page.waitForTimeout(700)
const frameMarch = (await collectFrameLayout(
  [{ root: 'MarchPanel', frame: 'MarchPanel', skip: [] }],
)).MarchPanel
await page.screenshot({ path: path.join(OUT, 'art-march-runtime.png') })

/**
 * 框的读数合到一起判：带厚分家 = 几何又有两个家；压带 = 内容盖在装饰上。
 *
 * <p>**内城卡片 2026-09-21 退出这一组**：满屏参考舞台之后 `CardFrame`
 * 是 `active=false`、不挂 Sprite 的空容器（`CityPanelView.buildCard`），
 * "九宫格带内排版"对它已不成立。它由下面 `cityCriteria` 那几条替换：
 * 内容区 = 视口、城景恰好一套、参考舞台在场时不叠主城正稿。行军面板照旧。
 */
const frameEntries = [frameMarch]
const frameBandDrift = frameEntries
  .filter((entry) => entry.error === undefined)
  .filter((entry) => entry.liveInsets.some((value) => value !== FRAME_BAND))
  .map((entry) => `${entry.root} 运行期 insets=${entry.liveInsets.join('/')}，源码常量=${FRAME_BAND}`)
const frameOverlaps = frameEntries
  .filter((entry) => entry.error === undefined)
  .flatMap((entry) => entry.violations.map((text) => `${entry.root}/${text}`))

/**
 * 行级画面判定：重开背包（种子道具已在包里）→ 切"背包"页签 → 逐页统计
 * 画出了 Sprite 的 Icon 行数。四件种子分属不同页签，总和必须 ≥ 4。
 * 能失败的方式：行没接族图（Icon active=false 或无 Sprite）→ 计数 0；
 * 映射表错 → 某件道具落在 null 图标上 → 计数不足。
 */
await inspectPanel('bag')
const bagTab = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let panel = null
  const visit = (n) => {
    if (panel !== null) return
    const c = n.getComponent && n.getComponent('BagPanelView')
    if (c !== null && c !== undefined) { panel = c; return }
    for (const child of n.children) visit(child)
  }
  visit(scene)
  if (panel === null) return { error: 'BagPanelView 不在场景里' }
  panel.switchTab('bag')
  const pages = panel.bag === null ? [] : panel.bag.pages
  let iconRows = 0
  let itemRows = 0
  for (const page of pages) {
    panel.selectBagPage(page.type)
    itemRows += page.items.length
    for (const row of panel.drawnRows) {
      const icon = row.getChildByName('Icon')
      const sprite = icon !== null && icon !== undefined
        ? icon.getComponent('cc.Sprite') : null
      if (icon !== null && icon.active === true && sprite !== null
          && sprite.enabled === true && sprite.spriteFrame !== null) {
        iconRows++
      }
    }
  }
  if (pages.length > 0) {
    panel.selectBagPage(pages[0].type)
  }
  return { iconRows, itemRows, pages: pages.map(p => ({ type: p.type, items: p.items.length })) }
})
await page.waitForTimeout(400)
await page.screenshot({ path: path.join(OUT, 'art-bag-items-runtime.png') })

/**
 * G4 活动族：任务面板的"活动"页签（活动列表在面板 onShow 时由 AppRoot 拉，不用自己发请求）。
 * 切页签 → 等数据到货 → 数"画出 Sprite 的 Icon 行数"。
 * 能失败的方式：行没接族图 → iconRows 为 0；族表与磁盘脱节 → png 请求数不足 8 且 catalogWarnings 变红；
 * activity.json 加了新行而映射没跟上 → 那一行走 null 图标，iconRows < activityRows。
 */
const familyBeforeActivity = resourcePngRequests.size
await inspectPanel('quest')
const activityTab = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let panel = null
  const visit = (n) => {
    if (panel !== null) return
    const c = n.getComponent && n.getComponent('QuestPanelView')
    if (c !== null && c !== undefined) { panel = c; return }
    for (const child of n.children) visit(child)
  }
  visit(scene)
  if (panel === null) return { error: 'QuestPanelView 不在场景里' }
  // 切页签前先记下任务页的文本列起点：三个页签必须共用同一列，否则切页时整列文字横跳
  const questTitle = panel.drawnRows.length > 0 ? panel.drawnRows[0].getChildByName('Title') : null
  const questTitleX = questTitle !== null && questTitle !== undefined ? questTitle.position.x : null
  panel.switchTab('activity')
  return { ok: true, questTitleX }
})
await page.waitForTimeout(1800)
const activityDrawn = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let panel = null
  const visit = (n) => {
    if (panel !== null) return
    const c = n.getComponent && n.getComponent('QuestPanelView')
    if (c !== null && c !== undefined) { panel = c; return }
    for (const child of n.children) visit(child)
  }
  visit(scene)
  if (panel === null) return { error: 'QuestPanelView 不在场景里' }
  const rows = panel.activity === null || panel.activity === undefined ? [] : panel.activity.rows
  let iconRows = 0
  // 图标列与文本列的几何关系：只数"图标画出来了"会漏掉"图标压在标题上"这种排版事故
  // （活动行第一版就是这样，截图里标题被图标糊住）。
  let overlaps = 0
  for (const row of panel.drawnActivity) {
    const icon = row.getChildByName('Icon')
    const title = row.getChildByName('Title')
    const sprite = icon !== null && icon !== undefined ? icon.getComponent('cc.Sprite') : null
    if (icon !== null && icon.active === true && sprite !== null
        && sprite.enabled === true && sprite.spriteFrame !== null) {
      iconRows++
    }
    if (icon === null || title === null) continue
    const iconBox = icon.getComponent('cc.UITransform')
    const titleBox = title.getComponent('cc.UITransform')
    if (iconBox === null || titleBox === null) continue
    if (icon.position.x + iconBox.width / 2 > title.position.x - titleBox.width * titleBox.anchorX) {
      overlaps++
    }
  }
  return {
    activityRows: rows.length,
    drawnRows: panel.drawnActivity.length,
    iconRows,
    overlaps,
    activityTitleX: panel.drawnActivity.length > 0
      ? panel.drawnActivity[0].getChildByName('Title')?.position.x ?? null : null,
  }
})
await page.screenshot({ path: path.join(OUT, 'art-quest-activity-runtime.png') })
const familyAfterActivity = resourcePngRequests.size


/**
 * **九宫格退化**判定（本轮的通则判据，先于任何具体修复存在）。
 * 一张按 SLICED 铺的图，若目标尺寸小于它自己左右（或上下）边框之和，引擎就只能把整张图缩小 ——
 * 画出来不再是"能拉伸的按钮"，而是一团缩小的装饰。#215 的导航格（63×44 配 54px 端帽）
 * 就是这个形状第一次现形；同一条规则也解释了面板里那批 46×26 / 78×28 的小按钮。
 * 能失败的方式：任何消费者把大边框母版铺到小格子上；素材改了 border 却没同步尺寸。
 */
function findDegenerateSlices(sprites) {
  return sprites
    .filter((sprite) => sprite.typeName === 'SLICED' && sprite.contentWidth > 0)
    .filter((sprite) => sprite.contentWidth < sprite.insetLeft + sprite.insetRight
      || sprite.contentHeight < sprite.insetTop + sprite.insetBottom)
    .map((sprite) => `${sprite.name} ${sprite.contentWidth.toFixed(1)}x${sprite.contentHeight.toFixed(1)}`
      + ` 小于自身边框 ${sprite.insetLeft + sprite.insetRight}x${sprite.insetTop + sprite.insetBottom}`)
}
const degenerateSlices = Array.from(new Set([
  ...findDegenerateSlices(city),
  ...findDegenerateSlices(bag),
  ...findDegenerateSlices(army),
  ...findDegenerateSlices(hero),
  ...findDegenerateSlices(world),
  ...findDegenerateSlices(worldZoom),
]))

/**
 * **九宫格边厚吃掉可读区**（V25-e，规格 §二 那条「不吃内容」判据的机器化）。
 *
 * <p>比上面那条**严一档**：退化判据只在「目标尺寸小于自身边框之和」时红 —— 那时引擎把整张图
 * 缩小，画出来明显不对。但「目标大于边框之和、边框却占了可读区 60% 以上」那一段是**绿的**，
 * 而玩家看到的正是四条铜边夹一小块内容、净区里放不下一行正文。§二 的自检就是这条线：
 * A 档 48·36 铺到 460×300 ⇒ 72/300 = 0.24，C 档 6·4 铺到 64×26 ⇒ 8/26 = 0.31，都在阈内；
 * 把 A 档母版（border 48·36）挪去铺 C 档小件会直接落到这条上 —— 所以它同时是
 * §八.2「不许跨档复用」在**消费侧**的机器判据（登记侧由 `client/tests/ArtFamilies.test.ts` 钉）。
 *
 * <p>**判全部 SLICED，但带一张必须写理由的豁免名单**（2026-10-08 拍板的口径）。
 * 为什么要豁免：第一次现跑（2026-10-08）无条件判全部 SLICED 时打出 19 条命中，全部是
 * `button-chip-*`（12·12 的边框铺在 26~34 高的格子上，竖边占 0.71~0.92）。量了那张 chip：
 * 边带 4512 px 里 4074 px 不透明、中心净区只有 **8 种颜色** ⇒ 它是**纯色平底**，
 * 边带里没有任何独立装饰，"边框吃掉可读区"这个前提对它不成立（玩家看到的仍是整块底色 + 字）。
 * 豁免条目自己会烂，所以 `staleExemptions` 那条会核：**前缀在盘上对应不到任何 png 就判红** ——
 * 与本仓 `scripts/check-art-quantized.sh` 的白名单完整性是同一族纪律。
 *
 * <p>能失败的方式：任何消费者把大 border 的母版铺到小格子；素材改了 border 而消费尺寸没跟着改。
 * 取证（2026-10-08）：把 `panel-iron-v1.png.meta` 的 border 临时改成 200 重建产物，
 * 本条点名 5 处，而同一轮旧退化判据只点名 1 处 ⇒ 另外 4 处只有这条抓得到（严一档是真的）。
 * `borderRatioWorst` 是**读数不是判据**：它印出当前屏上最凶的那一档，改阈值前先看得见现状。
 */
const BORDER_RATIO_LIMIT = 0.6
/** 豁免名单：frame 前缀 → 为什么这条判据对它不成立（理由要能被实测复核）。 */
const BORDER_RATIO_EXEMPT = [
  {
    prefix: 'button-chip',
    reason: '纯色平底：实测边带 4074/4512 像素不透明而中心净区只有 8 种颜色，边带里没有独立装饰',
  },
]
const borderRatio = (sprite) => Math.max(
  (sprite.insetLeft + sprite.insetRight) / sprite.contentWidth,
  (sprite.insetTop + sprite.insetBottom) / sprite.contentHeight,
)
const isExemptFrame = (sprite) => BORDER_RATIO_EXEMPT
  .some((entry) => (sprite.frameName ?? '').startsWith(entry.prefix))
const judgedSlices = [city, bag, army, hero, world, worldZoom].flat()
  .filter((sprite) => sprite.typeName === 'SLICED'
    && sprite.contentWidth > 0 && sprite.contentHeight > 0)
  .filter((sprite) => !isExemptFrame(sprite))
function findContentEatenSlices(sprites) {
  return sprites
    .filter((sprite) => borderRatio(sprite) > BORDER_RATIO_LIMIT)
    .map((sprite) => `${sprite.frameName} 画在 ${sprite.name}`
      + ` ${sprite.contentWidth.toFixed(1)}x${sprite.contentHeight.toFixed(1)}`
      + ` ⇒ 边框占可读区 横${((sprite.insetLeft + sprite.insetRight) / sprite.contentWidth).toFixed(2)}`
      + `/纵${((sprite.insetTop + sprite.insetBottom) / sprite.contentHeight).toFixed(2)}`
      + `（阈 ${BORDER_RATIO_LIMIT}，边框 ${sprite.insetLeft + sprite.insetRight}`
      + `x${sprite.insetTop + sprite.insetBottom}）`)
}
const contentEatenSlices = Array.from(new Set(findContentEatenSlices(judgedSlices)))
/** 现跑最凶的一档（读数，不作判据）。 */
const borderRatioWorst = judgedSlices.length === 0 ? null
  : judgedSlices.map((sprite) => ({
    frameName: sprite.frameName,
    drawnOn: sprite.name,
    size: `${sprite.contentWidth}x${sprite.contentHeight}`,
    ratio: Number(borderRatio(sprite).toFixed(3)),
  })).sort((a, b) => b.ratio - a.ratio)[0]
/**
 * 豁免名单的完整性：前缀在盘上对应不到任何 png 就是**烂条目**（文件已删/改名，豁免还留着），
 * 判红而不是放过 —— 与本仓「白名单自己也在被检之内」同一条纪律。
 */
const UI_GENERATED_DIR = 'client/assets/resources/ui/generated/ui'
const uiPngNames = existsSync(UI_GENERATED_DIR)
  ? readdirSync(UI_GENERATED_DIR).filter((name) => name.endsWith('.png'))
  : []
const staleExemptions = BORDER_RATIO_EXEMPT
  .filter((entry) => !uiPngNames.some((name) => name.startsWith(entry.prefix)))
  .map((entry) => `豁免条目 ${entry.prefix} 在盘上没有对应 png（理由：${entry.reason}）`)

const cityIcons = city.filter((sprite) => sprite.name === 'BuildingIcon')
const bagIcons = bag.filter((sprite) => sprite.name === 'Icon' && sprite.height === 128)
// 资源行的图集映射断言原来钉死在 grain 的矩形上，而"哪几行在屏内"由服务端 map 顺序决定 ——
// 换一次 dev 账号数据顺序就假红。改成：任一在屏资源行的图标矩形命中六类资源在图集里的矩形集合。
const atlasIndex = JSON.parse(readFileSync('client/assets/resources/ui/generated/icons/icons-atlas.json', 'utf8'))
const resourceRects = new Set(['gold', 'grain', 'iron', 'stamina', 'stone', 'wood']
  .map((type) => atlasIndex.items.find((item) => item.key === `resources/${type}`))
  .filter((item) => item !== undefined)
  .map((item) => `${item.x}:${item.y}`))
const bagResourceIconMapped = bagIcons.some((sprite) => resourceRects.has(`${sprite.x}:${sprite.y}`))
const armyIcons = army.filter((sprite) => sprite.name === 'Icon' && sprite.height === 128)
const heroIcons = hero.filter((sprite) => sprite.name === 'Icon'
  && (sprite.height === 128 || sprite.height === 256))
const terrainTiles = world.filter((sprite) => sprite.name === 'Art' && sprite.width === 64)
const terrainRects = new Set(terrainTiles.map((sprite) => `${sprite.x}:${sprite.y}`))
const entityArt = world.filter((sprite) => sprite.name === 'Art' && sprite.width !== 64)
const catalogWarnings = warnings.filter((message) => message.includes('[ArtCatalog]'))
/**
 * 内城那条（原 `cityMain`：要求主城格子必须画 `building-main-city` 正稿）**2026-09-21 删掉**：
 * 满屏参考舞台的底图里已经画着城堡，`CityPanelView.paintTile` 因此刻意**不叠主城正稿**
 * （叠了就是重影）。方向反过来钉在 `cityCriteria.mainCityOverdrawn`：参考舞台在场时**不许**
 * 出现可见的主城正稿。下面这三条仍按原样"必须有"。
 */
const iconMappings = {
  bagResourceIcon: bagResourceIconMapped,
  armyInfantry: armyIcons.some((sprite) => sprite.x === 384 && sprite.y === 384),
  // 武将行现在优先画立绘（G1 族，256 高；Cocos 导入会裁透明边所以宽可能 <256），
  // 稀有度图集图标（128 格）只是无立绘时的退路
  heroPortrait: heroIcons.some((sprite) => sprite.height === 256)
    || heroIcons.some((sprite) => sprite.y === 128 || sprite.y === 256),
}
/** 必须命中的映射（内城那条已从这里移出，改由 `cityCriteria` 反向钉住）。 */
const requiredMappings = ['bagResourceIcon', 'armyInfantry']
/**
 * `heroPortrait` 只在**名册真的有行**时判（反空转，2026-09-21 补）。
 *
 * <p>它原先是"必须有立绘"直接判红，而这一帧的武将来自"页内原始 fetch 抽一次卡 + 面板自己重拉列表"，
 * 偶尔会读到空名册 —— 于是同一个工具对同一份代码**时红时绿**（本轮复现 3 次：A 绿/C 红/A 红）。
 * 现在：名册为空 ⇒ **不判**，但要看得见（JSON 里 `heroRosterEmpty: true` + 控制台一行 WARN）；
 * 名册有行却一张立绘都没有 ⇒ 照样红。空名册本身（抽完卡却没进名册）**不在这里判** ——
 * 那是服务端侧的账，混进美术量具只会让两边都说不清。
 */
const heroRosterEmpty = heroIcons.length === 0
if (heroRosterEmpty) {
  console.warn('[verify-art] 武将名册这一帧是空的 —— heroPortrait 这条判据本轮走不到（不判红，也不当绿）')
}
const cityStage = [
  'city-ground-cobble-v1',
  'city-wall-band-v1',
  'city-ridge-v1',
].map((name) => ({
  name,
  visible: city.some((sprite) => (sprite.frameName ?? '').startsWith(name)),
}))
/**
 * 内城的四条判据（2026-09-21 改，原三条已与实现脱节 ⇒ 恒红，见 `内城界面审计_2026-09-21.md` §十）。
 *
 * <p>背景：2026-09-20 起内城改成**满屏参考舞台**（`city.scene.reference` = 重绘版底图），
 * `CityPanelView.buildBackground` 在参考图加载成功时**直接 return**，程序化 `CityGround/CityRidge/
 * CityWall` 根本不建。所以"程序化三件必须在场"这条判据从那天起不可能再绿 —— 换成下面这套
 * **二选一 + 反重影 + 满屏 + 该有的正稿**，每条都能失败，且比原来更强（原判据查不出"两套都没画"）：
 *
 * - `stageMissing`：参考舞台不在场时，程序化地面必须接上 ⇒ 两条路都不画才算红。
 * - `stageBothOn`：两套同时在画 ⇒ 重影（参考舞台铺底 + 程序化地表/山脊叠上去）。
 * - `mainCityOverdrawn`：参考舞台在场却又叠了**可见**的主城正稿 ⇒ 城堡画两遍。
 * - `gridOffViewport`：城景内容区 ≠ 视口 ⇒ 满屏改造或 resize 重排断了（§8.1 第 5 条、§8.3 第 10 条）。
 * - `headerMissing`：读不到「内城 · 建筑 N/36」⇒ 下面那条判据走不到，不许静默算绿。
 * - `iconsMissing`：面板说已建 ≥2 栋，却**一栋正稿**都没画出来（`building-*` 帧名的可见图标）——
 *   正稿拉不到时 `CityPanelView` 会退回图集小图标，那正是这条要抓的形态（旧的 `cityMain` 想抓它，
 *   但写成了"主城必须有正稿"，而主城按设计恰恰不叠）。新号只有 1 栋（建筑 1/36）时**刻意不判**：
 *   那是"没有该画的东西"，不是"该画的没画"。
 */
const cityIconsVisible = cityIcons.filter((sprite) => sprite.activeInHierarchy === true)
const isDrawnOnAtlas = (sprite) => !/^building-/.test(sprite.frameName ?? '')
const isMainCityFrame = (sprite) => /^building-main-city/.test(sprite.frameName ?? '')
const cityVisibleStage = cityStage.filter((entry) => entry.visible).map((entry) => entry.name)
const cityGroundVisible = cityStage
  .find((entry) => entry.name === 'city-ground-cobble-v1')?.visible === true
const referenceStageOn = cityStageProbe.referenceVisible === true
const cityCriteria = {
  referenceStageOn,
  referenceFrame: cityStageProbe.referenceFrame,
  visibleStageKeys: cityVisibleStage,
  gridSize: cityStageProbe.gridSize,
  visibleSize: cityStageProbe.visibleSize,
  builtCount: cityStageProbe.builtCount,
  builtTotal: cityStageProbe.builtTotal,
  stageMissing: !referenceStageOn && !cityGroundVisible,
  stageBothOn: referenceStageOn && cityVisibleStage.length > 0,
  mainCityOverdrawn: referenceStageOn && cityIconsVisible.some(isMainCityFrame),
  gridOffViewport: !(Array.isArray(cityStageProbe.gridSize)
    && Array.isArray(cityStageProbe.visibleSize)
    && Math.abs(cityStageProbe.gridSize[0] - cityStageProbe.visibleSize[0]) <= 1
    && Math.abs(cityStageProbe.gridSize[1] - cityStageProbe.visibleSize[1]) <= 1),
  headerMissing: cityStageProbe.builtCount === null,
  iconsMissing: cityStageProbe.builtCount !== null && cityStageProbe.builtCount >= 2
    && cityIconsVisible.filter((sprite) => !isDrawnOnAtlas(sprite) && !isMainCityFrame(sprite))
      .length === 0,
  atlasFallbackIcons: cityIconsVisible.filter(isDrawnOnAtlas).length,
  depthOrder: cityStageProbe.depthOrder,
  depthOrderInvalid: cityStageProbe.depthOrder.length !== cityStageProbe.builtTotal
    || cityStageProbe.depthOrder.some((entry, index, entries) => entry.depth === null
      || (index > 0 && entries[index - 1].depth > entry.depth)),
}
// 按钮按**帧名**认，不按 insets 认 —— insets 各家族不同（面板框 44、chip 12），
// 拿一个数字当身份就是给下一个家族埋雷（#213 那条判据的同族教训）。
const chipButtons = [...city, ...bag, ...army, ...hero, ...world]
  .filter((sprite) => /^button-chip/.test(sprite.frameName ?? ''))
const chipButtonSizes = Array.from(new Set(chipButtons
  .map((sprite) => `${sprite.contentWidth}x${sprite.contentHeight}`)))
const chipButtonsNotSliced = chipButtons.filter((sprite) => sprite.typeName !== 'SLICED')
const panelResults = {
  city: cityResult,
  bag: bagResult,
  army: armyResult,
  hero: heroResult,
  world: worldResult,
}
const activePanels = Object.fromEntries(Object.entries(panelResults)
  .map(([panel, entry]) => [panel, entry.activePanel]))
const panelMismatches = Object.entries(activePanels)
  .filter(([panel, active]) => active !== panel)
  .map(([panel, active]) => `${panel}->${active}`)
const fonts = Object.values(panelResults).flatMap((entry) => entry.fonts)
const fontFamilies = Array.from(new Set(fonts.map((font) => font.fontFamily)))
const fontPolicyFailures = fonts.filter((font) =>
  font.useSystemFont !== true || !font.fontFamily.includes('Microsoft YaHei'))

/**
 * 指定了非默认后端、却一次都没换到产物里写死的那个地址 ⇒ 这轮读数其实来自别的机器。
 * `preview-server` 把这个判据做成了 `assertRewritten`，但**必须等到服务过文件之后再问**：
 * `rewrites()` 是在服务文件的过程中累加的，启动那一刻恒为 0。
 * 折成一条 `errors` 而不是自己再写一遍判据 —— 退出码那条里已经有 `errors.length > 0`。
 */
let rewriteFailure = null
try {
  preview.assertRewritten()
} catch (error) {
  rewriteFailure = error.message
  errors.push(`后端替换失败：${rewriteFailure}`)
}

const result = {
  activePanels,
  /** 本轮读数到底是谁答的：换过后端就必须换到过东西（默认后端时 rewritten 恒 0，属正常）。 */
  backend: { origin: BACKEND, rewrittenFromBaked: preview.rewrites(), rewriteFailure },
  counts: {
    cityIcons: cityIcons.length,
    cityIconsVisible: cityIconsVisible.length,
    cityStage,
    cityCriteria,
    bagIcons: bagIcons.length,
    armyIcons: armyIcons.length,
    heroIcons: heroIcons.length,
    terrainTiles: terrainTiles.length,
    terrainVariants: terrainRects.size,
    entityArt: entityArt.length,
    familyBeforeBag,
    familyAfterBag,
    familyExpected: FAMILY_PNG_EXPECTED,
    bagTab,
    familyBeforeActivity,
    familyAfterActivity,
    activityTab,
    activityDrawn,
    chips: chipButtons.length,
    fontLabels: fonts.length,
    worldCaptions,
    worldSceneLayouts,
    worldEntityClick,
  },
  chipButtonSizes,
  chipButtonsNotSliced: chipButtonsNotSliced.map((sprite) => sprite.name),
  iconMappings,
  heroRosterEmpty,
  fontFamilies,
  fontPolicyFailures: fontPolicyFailures.map((font) => font.name),
  panelMismatches,
  degenerateSlices,
  contentEatenSlices,
  borderRatioWorst,
  judgedSliceCount: judgedSlices.length,
  borderRatioExempt: BORDER_RATIO_EXEMPT.map((entry) => entry.prefix),
  staleExemptions,
  frame: {
    bandFromSource: FRAME_BAND,
    march: frameMarch,
    bandDrift: frameBandDrift,
    bandOverlaps: frameOverlaps,
  },
  nav: {
    cells: navContrast.cells ?? navContrast,
    expected: NAV_CELLS_EXPECTED,
    barCells: navBarCells.length,
    trayCells: navTrayCells.length,
    missingCells: navMissingCells,
    lowContrast: navLowContrast,
    activeIndistinguishable: navActiveIndistinguishable,
    tabMissing: navTabMissing,
    selectedWrongFrame: navSelectedWrongFrame,
  },
  screenshots: {
    city: path.join(OUT, 'art-city-runtime.png'),
    bag: path.join(OUT, 'art-bag-runtime.png'),
    army: path.join(OUT, 'art-army-runtime.png'),
    hero: path.join(OUT, 'art-hero-runtime.png'),
    world: path.join(OUT, 'art-world-runtime.png'),
    march: path.join(OUT, 'art-march-runtime.png'),
    questActivity: path.join(OUT, 'art-quest-activity-runtime.png'),
  },
  errors,
  catalogWarnings,
}
console.log(JSON.stringify(result, null, 2))

await browser.close()
await preview.close()
/**
 *
 * <p>为什么不再写成一坨 `||`：那条形状红起来只说"退了 1"，不说红在哪一条 ——
 * 2026-09-22 我自己为了定位一条红，手工把十来个字段逐个打印比对才找到
 * （真凶是遗留的 `heroIcons.length === 0`，与刚改好的 `heroPortrait` 反空转自相矛盾：
 * 名册为空时前者恒红，于是"不判红也不当绿"那条改动被它整个抵消）。
 * 现在红的时候直接把命中项打出来。
 */
const gates = [
  ['页面/控制台有报错', errors.length > 0],
  ['ArtCatalog 有告警', catalogWarnings.length > 0],
  ['面板与导航不一致', panelMismatches.length > 0],
  ['PANEL_FRAME_BAND 读不到', FRAME_BAND <= 0],
  ['行军面板框缺失/内容为空', frameMarch.error !== undefined || frameMarch.counted < 5 || frameMarch.labeled < 1],
  ['框带厚与源码常量分家', frameBandDrift.length > 0],
  ['内容压到框的角饰上', frameOverlaps.length > 0],
  ['九宫格退化（目标小于自身边框）', degenerateSlices.length > 0],
  // V25-e：比上一条严一档 —— 没退化但边框吃掉 60% 以上可读区（规格 §二「不吃内容」）。
  // 取证：`panel-iron-v1.png.meta` 的 border 植入 200 后本条点名 5 处、上一条只点名 1 处。
  ['九宫格边厚吃掉可读区（边框占内容 > 0.6）', contentEatenSlices.length > 0],
  // 反空转（本仓那条"只查坏东西不存在必假绿"）：上一条要判，这一屏就必须真的量到九宫格；
  // 量到 0 张就是判据根本没跑（面板没画出来 / 收集口坏了），不能读成绿。
  ['九宫格一帧都没量到（0.6 判据空转）', judgedSlices.length === 0],
  // 豁免名单自己会烂：文件删了或改名而条目留着 = 给下一个越界件开的后门。
  ['边厚判据的豁免条目已失效（盘上无对应 png）', staleExemptions.length > 0],
  ['导航对比度读不到', navContrast.error !== undefined],
  ['导航格数不足', (navContrast.cells ?? []).length < NAV_CELLS_EXPECTED],
  ['导航格与面板清单对不上', navMissingCells.length > 0],
  // 导航瘦身（2026-09-26）：17 格挤一条 ⇒ 常驻只留核心几格，其余进「更多」抽屉。
  // 三条都能失败：退回"每格都上条"时第一条红；删掉抽屉时第二、三条红。
  ['常驻条没收窄（还是每格都上条）', navBarCells.length >= NAV_KEYS_EXPECTED.length],
  ['「更多」那格不在常驻条上', !navBarCells.some((cell) => cell.key === 'more')],
  ['抽屉里一格都没有', navTrayCells.length === 0],
  // 分边计数：MORE_KEYS 写错一个 key 只会把格子挪边，按 key 对账抓不住，这两条抓得住
  ['常驻条格数与 MORE_KEYS 对不上',
    navBarCells.filter((cell) => cell.key !== 'more').length
    !== NAV_KEYS_EXPECTED.length - MORE_KEYS_EXPECTED.length],
  ['抽屉格数与 MORE_KEYS 对不上', navTrayCells.length !== MORE_KEYS_EXPECTED.length],
  ['导航文字对比度不足', navLowContrast.length > 0],
  ['导航选中态与未选中同色', navActiveIndistinguishable],
  ['导航页签图缺失', navTabMissing.length > 0],
  ['导航选中格用错变体', navSelectedWrongFrame.length > 0],
  // 内城六条（城区形态 + 反重影 + 满屏 + 正稿；详见 cityCriteria 的注释）
  ['内城：两套城景都不在场', cityCriteria.stageMissing],
  ['内城：参考舞台与程序化城景同时在画', cityCriteria.stageBothOn],
  ['内城：参考舞台之上又叠了主城正稿', cityCriteria.mainCityOverdrawn],
  ['内城：城景内容区不等于视口', cityCriteria.gridOffViewport],
  ['内城：读不到「建筑 N/36」标题', cityCriteria.headerMissing],
  ['内城：已建 ≥2 栋却没有一栋正稿', cityCriteria.iconsMissing],
  ['内城：建筑绘制次序没有按实际落地深度', cityCriteria.depthOrderInvalid],
  ['背包图标数为 0', bagIcons.length === 0],
  ['军队图标数为 0', armyIcons.length === 0],
  ['必需的艺术映射未命中', requiredMappings.some((key) => iconMappings[key] !== true)],
  // 名册为空时这条走不到（见 heroRosterEmpty 的注释）；有行而没有立绘照样红。
  // **不再另加 `heroIcons.length === 0`** —— 那条与"反空转"自相矛盾，会把它整个抵消。
  ['武将名册有行但一张立绘都没有', !heroRosterEmpty && iconMappings.heroPortrait !== true],
  ['chip 按钮数不足', chipButtons.length < 20],
  ['chip 按钮没走九宫格', chipButtonsNotSliced.length > 0],
  ['字体标签数为 0', fonts.length === 0],
  ['字体策略不符', fontPolicyFailures.length > 0],
  ['地形块数为 0', terrainTiles.length === 0],
  ['地图实体美术为 0', entityArt.length === 0],
  ['按需族增量与磁盘张数不符', familyAfterBag - familyBeforeBag !== FAMILY_PNG_EXPECTED],
  ['世界地图名牌为 0', worldCaptions === 0],
  ['世界地图净区或静态雾底未跟随视口', worldSceneLayouts.some((entry) => entry.error
    || entry.visible[0] !== entry.layout.width || entry.visible[1] !== entry.layout.height
    || entry.layout.mapHeight <= 0 || !entry.backdropSize
    || entry.backdropSize[0] < entry.visible[0] || entry.backdropSize[1] < entry.visible[1])],
  ['世界地图操作条缺失、重复或越过净区', worldSceneLayouts.some((entry) => entry.error
    || entry.toolbarCount !== 1 || entry.buttons.length !== 5
    || entry.buttons.some((button) => !button.sliced || button.height > 40
      || button.y - button.height / 2 < entry.layout.mapTop
      || Math.abs(button.x) + button.width / 2 > entry.visible[0] / 2 + 0.5))],
  ['世界地图真实实体点击没选中同一单位', !worldEntityClick.tested
    || worldEntityClick.expected !== worldEntityClick.selected],
  ['背包页签读数失败', bagTab.error !== undefined],
  ['背包道具行不足', bagTab.itemRows < 4],
  ['背包图标行不足', bagTab.iconRows < 4],
  ['活动族增量与磁盘张数不符', familyAfterActivity - familyBeforeActivity !== ACTIVITY_PNG_EXPECTED],
  ['活动页签读数失败', activityTab.error !== undefined],
  ['活动绘制读数失败', activityDrawn.error !== undefined],
  ['活动行数不是 8', activityDrawn.activityRows !== 8],
  ['活动行图标与绘制行数不符', activityDrawn.iconRows !== activityDrawn.drawnRows],
  ['活动页签有重叠', activityDrawn.overlaps > 0],
  ['活动页签标题读不到', activityTab.questTitleX === null],
  ['活动页签标题（绘制侧）读不到', activityDrawn.activityTitleX === null],
  ['两页签标题未对齐', activityTab.questTitleX !== null && activityDrawn.activityTitleX !== null
    && Math.abs(activityTab.questTitleX - activityDrawn.activityTitleX) > 0.5],
]
const tripped = gates.filter(([, bad]) => bad).map(([name]) => name)
if (tripped.length > 0) {
  console.error(`[verify-art] 判据失败 ${tripped.length} 条：${tripped.join('；')}`)
  process.exitCode = 1
} else {
  console.log('[verify-art] 全绿：判据表全部通过')
}
