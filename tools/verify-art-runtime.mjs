import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'

const OUT = process.env.ART_VERIFY_OUT
  ?? path.resolve(process.cwd(), 'client/build/art-verify')
mkdirSync(OUT, { recursive: true })

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
  const url = new URL('http://localhost:8090/')
  url.searchParams.set('panel', panel)
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
  await page.waitForTimeout(1600)
  const activePanel = await page.evaluate(() => {
    const scene = window.cc.director.getScene()
    const nav = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
    return nav?.currentKey ?? null
  })
  const sprites = await collectSprites()
  const fonts = await collectFonts()
  await page.screenshot({ path: path.join(OUT, `art-${panel}-runtime.png`) })
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
 * 背包两族（items 13 + equip 16）是按需加载，判据用 **resources 包 png 请求数的差值**：
 * 开背包前记一个数，开过背包后再记一个 —— 增量必须恰为 29。
 * 这条能失败的方式：BagPanelView 不再 ensureFamily → 增量为 0；
 * 族表与磁盘脱节 → 某张 404（增量不足且 catalogWarnings 变红）；
 * 有人把两族塞回启动预载 → city 阶段基线被抬高，增量同样对不上。
 */
const FAMILY_PNG_EXPECTED = 29
const resourcePngRequests = new Set()
page.on('request', (request) => {
  const url = request.url()
  if (url.includes('/assets/resources/') && url.endsWith('.png')) {
    resourcePngRequests.add(url)
  }
})

const cityResult = await inspectPanel('city')
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

const cityIcons = city.filter((sprite) => sprite.name === 'BuildingIcon')
const bagIcons = bag.filter((sprite) => sprite.name === 'Icon' && sprite.height === 128)
const armyIcons = army.filter((sprite) => sprite.name === 'Icon' && sprite.height === 128)
const heroIcons = hero.filter((sprite) => sprite.name === 'Icon' && sprite.height === 128)
const terrainTiles = world.filter((sprite) => sprite.name === 'Art' && sprite.width === 64)
const terrainRects = new Set(terrainTiles.map((sprite) => `${sprite.x}:${sprite.y}`))
const entityArt = world.filter((sprite) => sprite.name === 'Art' && sprite.width !== 64)
const catalogWarnings = warnings.filter((message) => message.includes('[ArtCatalog]'))
const iconMappings = {
  cityMain: cityIcons.some((sprite) => sprite.x === 128 && sprite.y === 128),
  bagGrain: bagIcons.some((sprite) => sprite.x === 512 && sprite.y === 256),
  armyInfantry: armyIcons.some((sprite) => sprite.x === 384 && sprite.y === 384),
  // N 在索引第二行，R/SR/SSR 在第三行；两种都可能被新手池随机抽到。
  heroRarity: heroIcons.some((sprite) => sprite.y === 128 || sprite.y === 256),
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
    commandButtons: commandButtons.length,
    fontLabels: fonts.length,
  },
  commandButtonSizes,
  commandButtonsNotSliced: commandButtonsNotSliced.map((sprite) => sprite.name),
  iconMappings,
  fontFamilies,
  fontPolicyFailures: fontPolicyFailures.map((font) => font.name),
  panelMismatches,
  screenshots: {
    city: path.join(OUT, 'art-city-runtime.png'),
    bag: path.join(OUT, 'art-bag-runtime.png'),
    army: path.join(OUT, 'art-army-runtime.png'),
    hero: path.join(OUT, 'art-hero-runtime.png'),
    world: path.join(OUT, 'art-world-runtime.png'),
  },
  errors,
  catalogWarnings,
}
console.log(JSON.stringify(result, null, 2))

await browser.close()
if (errors.length > 0
  || catalogWarnings.length > 0
  || panelMismatches.length > 0
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
  || bagTab.error !== undefined
  || bagTab.itemRows < 4
  || bagTab.iconRows < 4) {
  process.exitCode = 1
}
