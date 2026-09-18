import { mkdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

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
const preview = await startPreviewServer({
  root: 'client/build/web-mobile',
  // 后端就用产物里写死的那台：不换地址 ⇒ 不需要 rewrite，也不会有读数打到别的机器
  backend: 'http://localhost:8080',
  port: ART_PORT,
})

const deviceId = `art-runtime-${Date.now()}`
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, deviceId)
const page = await context.newPage()
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
  await page.waitForTimeout(1600)
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
          type: sprite.type,
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
 * 按需族（items 13 + equip 16 + hero 12 = 41）的判据用 **resources 包 png 请求数的差值**：
 * 开背包前记基线，走完背包/武将/世界后记终值 —— 增量必须恰为 41。
 * 能失败的方式：某面板不再 ensureFamily → 增量缺该族的张数；
 * 族表与磁盘脱节 → 某张 404（增量不足且 catalogWarnings 变红）；
 * 有人把族图塞回启动预载 → city 阶段基线被抬高，增量同样对不上。
 */
const FAMILY_PNG_EXPECTED = 41
/** 活动族（activity.json 八行）单独量：任务面板在 bag/hero 之后才打开，混进上面那条会互相遮蔽。 */
const ACTIVITY_PNG_EXPECTED = 8
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
 * <p>为什么几何判据而不是截图比对：#204 在内城修过"标题压在角饰上"，#211 在行军面板上
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
const frameCity = (await collectFrameLayout(
  [{ root: 'Card', frame: 'CardFrame', skip: ['CardFrame', 'ChoiceOverlay'] }],
)).Card

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
    const bar = scene.getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('NavBar')
    if (bar === null || bar === undefined) return { error: 'NavBar 不在场景里' }
    const nav = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
    const cells = []
    for (const cell of bar.children) {
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
    return { cells }
  })
}
const navContrast = await collectNavContrast()
/** 导航格数从 PanelNav 现读，不在工具里抄第二份清单（同 verify-devtools-panels 的口径）。 */
const NAV_CELLS_EXPECTED = (readFileSync('client/assets/scripts/scene/PanelNav.ts', 'utf8')
  .match(/^\s*\{ key: '/gm) ?? []).length
const navLowContrast = (navContrast.cells ?? [])
  .filter((cell) => cell.background === 'art' && cell.luminance < 0.35)
  .map((cell) => `${cell.key} 文字亮度 ${cell.luminance} < 0.35（色 ${cell.color}，底是按钮图的深色中心）`)
const navActiveIndistinguishable = (navContrast.cells ?? []).length > 0
  && (navContrast.cells ?? []).filter((cell) => cell.active)
    .every((cell) => (navContrast.cells ?? [])
      .filter((other) => !other.active)
      .every((other) => other.color === cell.color))
/**
 * 页签图真的铺上了没（G8）：13 格都必须是 nav-tab，且选中那一格必须换成 selected 变体。
 * 能失败的方式：PanelNav 退回按钮九宫格（帧名对不上）、selected 键没进启动预载（帧为 null）。
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
const drawResult = await page.evaluate(async ({ playerId }) => {
  const initResponse = await fetch('http://localhost:8080/player/init', {
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
  const response = await fetch('http://localhost:8080/gacha/draw', {
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
}, { playerId: deviceId })
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
const seeded = await page.evaluate(async ({ playerId, token }) => {
  const init = await (await fetch('http://localhost:8080/player/init', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ requestId: `art-seed-init-${Date.now()}`, deviceId: playerId,
      nickName: 'ArtSeed', clientTime: Date.now() }),
  })).json()
  if (init.code !== 0) return { step: 'init', init }
  const mail = await (await fetch('http://localhost:8080/ops/mail/send', {
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
  const claim = await (await fetch('http://localhost:8080/mail/claimAll', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Player-Id': init.data.playerId },
    body: JSON.stringify({ requestId: `art-claim-${Date.now()}` }),
  })).json()
  if (claim.code !== 0) return { step: 'claim', claim }
  return { ok: true, playerId: init.data.playerId, claimed: claim.data.claimed }
}, { playerId: deviceId, token: OPS_TOKEN })
if (seeded.ok !== true) {
  errors.push(`背包道具种子失败：${JSON.stringify(seeded)}`)
}

const heroResult = await inspectPanel('hero')
const worldResult = await inspectPanel('world')
const familyAfterBag = resourcePngRequests.size
const hero = heroResult.sprites
const world = worldResult.sprites

/**
 * 名牌与选中环只在放大档出现（zoom 0 按设计不写 caption）。
 * 调 WorldMap 的公开 zoomIn **一档**后：屏内必须数得到非空名牌文字 —— 数不到就是
 * drawCaptionPlate 断了线。两档会按设计切进城市档，截出来的就不是世界地图了
 * （排版轮实测撞到过一次）。截图另存，供人工比对同类 SLG 的名牌观感。
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
  if (map === null) throw new Error('WorldMap 组件不在场景里')
  map.zoomIn()
})
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

/** 两张框的读数合到一起判：带厚分家 = 几何又有两个家；压带 = 内容盖在装饰上。 */
const frameEntries = [frameMarch, frameCity]
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
const iconMappings = {
  cityMain: cityIcons.some((sprite) => sprite.x === 128 && sprite.y === 128),
  bagResourceIcon: bagResourceIconMapped,
  armyInfantry: armyIcons.some((sprite) => sprite.x === 384 && sprite.y === 384),
  // 武将行现在优先画立绘（G1 族，256 高；Cocos 导入会裁透明边所以宽可能 <256），
  // 稀有度图集图标（128 格）只是无立绘时的退路
  heroPortrait: heroIcons.some((sprite) => sprite.height === 256)
    || heroIcons.some((sprite) => sprite.y === 128 || sprite.y === 256),
}
const commandButtons = [...city, ...bag, ...army, ...hero, ...world]
  .filter((sprite) => sprite.insetLeft === 54 && sprite.insetTop === 40)
const commandButtonSizes = Array.from(new Set(commandButtons
  .map((sprite) => `${sprite.contentWidth}x${sprite.contentHeight}`)))
const commandButtonsNotSliced = commandButtons.filter((sprite) => sprite.typeName !== 'SLICED')
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

const result = {
  activePanels,
  counts: {
    cityIcons: cityIcons.length,
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
    commandButtons: commandButtons.length,
    fontLabels: fonts.length,
    worldCaptions,
  },
  commandButtonSizes,
  commandButtonsNotSliced: commandButtonsNotSliced.map((sprite) => sprite.name),
  iconMappings,
  fontFamilies,
  fontPolicyFailures: fontPolicyFailures.map((font) => font.name),
  panelMismatches,
  frame: {
    bandFromSource: FRAME_BAND,
    march: frameMarch,
    city: frameCity,
    bandDrift: frameBandDrift,
    bandOverlaps: frameOverlaps,
  },
  nav: {
    cells: navContrast.cells ?? navContrast,
    expected: NAV_CELLS_EXPECTED,
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
if (errors.length > 0
  || catalogWarnings.length > 0
  || panelMismatches.length > 0
  // 带内排版的四条：读得到常量、两张框都在场景里、几何两份真源没分家、内容一处都没压带。
  // counted / labeled 的下限是反空转 —— 走不到节点、或一片空文字时，violations 天然是空的。
  || FRAME_BAND <= 0
  || frameMarch.error !== undefined || frameMarch.counted < 5 || frameMarch.labeled < 1
  || frameCity.error !== undefined || frameCity.counted < 20
  || frameBandDrift.length > 0
  || frameOverlaps.length > 0
  || navContrast.error !== undefined
  || (navContrast.cells ?? []).length < NAV_CELLS_EXPECTED
  || navLowContrast.length > 0
  || navActiveIndistinguishable
  || navTabMissing.length > 0
  || navSelectedWrongFrame.length > 0
  || cityIcons.length === 0
  || bagIcons.length === 0
  || armyIcons.length === 0
  || heroIcons.length === 0
  || Object.values(iconMappings).some((matched) => !matched)
  || commandButtons.length === 0
  || commandButtonsNotSliced.length > 0
  || fonts.length === 0
  || fontPolicyFailures.length > 0
  || terrainTiles.length === 0
  || entityArt.length === 0
  || familyAfterBag - familyBeforeBag !== FAMILY_PNG_EXPECTED
  || worldCaptions === 0
  || bagTab.error !== undefined
  || bagTab.itemRows < 4
  || bagTab.iconRows < 4
  || familyAfterActivity - familyBeforeActivity !== ACTIVITY_PNG_EXPECTED
  || activityTab.error !== undefined
  || activityDrawn.error !== undefined
  || activityDrawn.activityRows !== 8
  || activityDrawn.iconRows !== activityDrawn.drawnRows
  || activityDrawn.overlaps > 0
  || activityTab.questTitleX === null
  || activityDrawn.activityTitleX === null
  || Math.abs(activityTab.questTitleX - activityDrawn.activityTitleX) > 0.5) {
  process.exitCode = 1
}
