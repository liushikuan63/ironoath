/**
 * 职责：**整城验收** —— 真养出15类建筑，逐类确认实际正稿与标注，并验实际绘制序下主体遮挡≤20%。
 * 依赖：`client/build/web-mobile` + 一台**本轮自己的** dev 后端（`BACKEND_ORIGIN`）+ Playwright + 现有Python/Pillow。
 *
 * <p>为什么需要它：主城等级是硬门槛（`building.json` 的 `requireMainLevel` 最高到 8），而新号那点资源
 * 只够建 4 类（`verify-city-multi-types.mjs` 的射程）。于是"15 张正稿里有 10 类**从没在场景里出现过**"
 * 一直是审计里挂着的欠账（`内城界面审计` §8.5 第 4 条、§22 的"仍未做"第 1 条）。
 *
 * <p><b>资源从哪来</b>：走仓库**唯一一条"凭空给玩家发奖励"的通路** —— `POST /ops/mail/send`
 * （需 `X-Ops-Token`；它自身受运维令牌、幂等键、审计日志三道闸门约束，见 `OpsMailSendReq` 的描述），
 * 补发 `type=RESOURCE` 的附件再 `claimAll` 领出来。**不新增任何"开发期作弊"代码路径**，
 * 也不动生产逻辑；这只对**本工具自己建的测试账号**生效，且只在 dev 后端上跑得通（令牌默认值为本地开发用）。
 *
 * 退出码：0 全绿；1 判据失败；2 前置不满足（产物缺失 / 后端不答 / 令牌没配）。
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { clickNodeViaCocos } from './lib/cocos-click.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const OPS_TOKEN = process.env.ART_VERIFY_OPS_TOKEN ?? 'art-verify-local'
const PORT = Number(process.env.FULL_CITY_PORT ?? 8296)
const OUT = process.env.FULL_CITY_OUT ?? 'client/build/art-verify'
const SHOT = path.join(OUT, '14-city-full.png')
const WAIT_CAP_MS = Number(process.env.FULL_CITY_WAIT_CAP_MS ?? 180_000)
const LAYOUT = process.env.FULL_CITY_LAYOUT ?? 'default'
/** 城镇网格 6×6；主城固定中心格。 */
const GRID = 6
const MAIN_CITY = { gridX: 3, gridY: 3 }
/** 其余 14 类各给一个不冲突的落点（城墙按 city_rule_wall_edge_only 放边缘格）。 */
const DEFAULT_PLACEMENTS = {
  lumber_camp: [0, 0], quarry: [1, 0], farm: [2, 0], iron_mine: [4, 0], warehouse: [5, 0],
  barracks: [0, 1], stable: [1, 1], archery_range: [4, 1], siege_workshop: [5, 1],
  drill_ground: [0, 2], hospital: [1, 2], academy: [4, 2], embassy: [5, 2], wall: [0, 3],
}
if (!['default', 'crown'].includes(LAYOUT)) {
  console.error('[full-city][前置] FULL_CITY_LAYOUT 只能是 default 或 crown')
  process.exit(2)
}
// crown 是另外一组真实合法存档格的验收账号；不迁移旧号，不以换格替代修正显示布局。
const PLACEMENTS = LAYOUT === 'crown'
  ? { ...DEFAULT_PLACEMENTS, warehouse: [3, 1], barracks: [2, 2] } : DEFAULT_PLACEMENTS

if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[full-city][前置] 产物不存在（先跑 scripts/build-webmobile.sh）`)
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const deviceId = process.env.FULL_CITY_DEVICE ?? `full-city-${Date.now()}`
const post = async (url, body, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  return response.json()
}
const get = async (url, headers = {}) => {
  const response = await fetch(`${BACKEND}${url}`, { headers })
  return response.json()
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

const init = await post('/player/init', {
  requestId: `full-init-${Date.now()}`, deviceId, nickName: '整城验收',
  clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[full-city][前置] 建号失败（后端在跑吗？）：${JSON.stringify(init)}`)
  process.exit(2)
}
const playerId = init.data.playerId
const HEAD = { 'X-Player-Id': playerId }
console.log(`[full-city] 设备号=${deviceId}（想复跑同一座城时用 FULL_CITY_DEVICE=${deviceId}，可跳过养城阶段）`)
console.log(`[full-city] 后端 ${BACKEND}，布局 ${LAYOUT}，证据目录 ${OUT}`)

// ---- 资源：走运维补发（唯一那条通路），只在必要时发 ----
const cityOf = async () => {
  const resp = await get('/city/list', HEAD)
  if (resp.code !== 0) {
    console.error(`[full-city][前置] 读城建失败：${JSON.stringify(resp)}`)
    process.exit(2)
  }
  return resp.data
}
let city = await cityOf()
const need = { WOOD: 60_000, STONE: 60_000, IRON: 20_000, GRAIN: 10_000 }
const short = Object.entries(need)
  .filter(([type, want]) => (city.resources[type]?.current ?? 0) < want)
if (short.length > 0) {
  const mail = await post('/ops/mail/send', {
    requestId: `full-fund-${Date.now()}`, playerId,
    title: '整城验收用资源', text: '自动化量具建号后的补发（dev 后端限定）', actor: 'tools/verify-city-full-city',
    rewards: short.map(([type, want]) => ({
      type: 'RESOURCE', id: type, count: want, name: type,
    })),
  }, { 'X-Ops-Token': OPS_TOKEN })
  if (mail.code !== 0) {
    console.error(`[full-city][前置] 运维补发被拒（令牌没配？）：${JSON.stringify(mail)}`)
    process.exit(2)
  }
  const claim = await post('/mail/claimAll', { requestId: `full-claim-${Date.now()}` }, HEAD)
  if (claim.code !== 0) {
    console.error(`[full-city][前置] 领取补发邮件失败：${JSON.stringify(claim)}`)
    process.exit(2)
  }
  city = await cityOf()
  const after = Object.entries(need).map(([type]) => `${type}=${city.resources[type]?.current ?? 0}`)
  console.log(`[full-city] 运维补发 ${short.map(([t, w]) => `${t}:${w}`).join('、')} → ${after.join(' ')}`)
}

/**
 * 把四种资源补到接近上限。
 *
 * <p><b>为什么要"边建边补"而不是一次发够</b>：资源有仓容上限（wood/stone 2 万、iron 1 万），
 * 一次发 6 万 **只有 2 万能落袋**，其余在入库时就被截掉 —— 第一版就是这么把后续两栋建崩的
 * （`3006 资源不足 需要 WOOD 400，当前 WOOD 399`）。所以按"目标值 − 当前值"分批发、发完就领，
 * 目标值留在上限之下一点。每次补发都走同一道运维闸门（令牌 + 幂等 + 审计日志）。
 */
const topUp = async () => {
  const city = await cityOf()
  const targets = [['WOOD', 19_000], ['STONE', 19_000], ['IRON', 9_000], ['GRAIN', 25_000]]
  const rewards = targets
    .map(([type, target]) => ({ type, count: target - (city.resources[type]?.current ?? 0) }))
    .filter((entry) => entry.count > 500)
    .map((entry) => ({ type: 'RESOURCE', id: entry.type, count: entry.count, name: entry.type }))
  if (rewards.length === 0) {
    return
  }
  const mail = await post('/ops/mail/send', {
    requestId: `full-fund-${Date.now()}`, playerId,
    title: '整城验收用资源', text: '自动化量具建号后的补发（dev 后端限定）', actor: 'tools/verify-city-full-city',
    rewards,
  }, { 'X-Ops-Token': OPS_TOKEN })
  if (mail.code !== 0) {
    console.error(`[full-city][前置] 运维补发被拒（令牌没配？）：${JSON.stringify(mail)}`)
    process.exit(2)
  }
  const claim = await post('/mail/claimAll', { requestId: `full-claim-${Date.now()}` }, HEAD)
  if (claim.code !== 0) {
    console.error(`[full-city][前置] 领取补发邮件失败：${JSON.stringify(claim)}`)
    process.exit(2)
  }
  console.log(`[full-city] 补发 ${rewards.map((r) => `${r.id}:${r.count}`).join('、')}`)
}

const upgrade = async (configId, coords) => {
  const body = { requestId: `full-${configId}-${Date.now()}`, configId, ...(coords ? { gridX: coords[0], gridY: coords[1] } : {}) }
  const resp = await post('/city/upgrade', body, HEAD)
  if (resp.code !== 0) {
    return { ok: false, message: `${configId}` + (coords ? `@${coords}` : '') + ` 被拒：${resp.code} ${resp.msg} ${resp.detail ?? ''}` }
  }
  const waitMs = Math.max(0, resp.data.finishAt - resp.serverNow)
  await sleep(Math.min(waitMs + 1500, WAIT_CAP_MS))
  return { ok: true, waitMs }
}

// ---- 养城：已经有 15 栋就直接进场景验收（复跑同一座城时省掉十来分钟）----
const failures = []
const alreadyBuilt = new Set((await cityOf()).buildings.map((b) => b.configId))
const needBuild = Object.keys(PLACEMENTS).filter((configId) => !alreadyBuilt.has(configId))
if (needBuild.length === 0 && alreadyBuilt.has('main_city')
    && (await cityOf()).buildings.find((b) => b.configId === 'main_city')?.level >= 8) {
  console.log('[full-city] 这座城已经养好了（主城 ≥8 级 + 14 类齐）——跳过养城，直接验收')
} else {
  // ---- 主城升到 8 级 ----
  const city0 = await cityOf()
  const mainLevel = city0.buildings.find((b) => b.configId === 'main_city')?.level ?? 1
  for (let level = Math.max(2, mainLevel + 1); level <= 8; level++) {
    await topUp()
    const result = await upgrade('main_city')
    if (!result.ok) {
      console.error(`[full-city][前置] 主城升到 ${level} 级失败：${result.message}`)
      process.exit(2)
    }
    console.log(`[full-city] 主城 → ${level} 级（${result.waitMs}ms）`)
  }

  // ---- 缺哪类建哪类（两个队列并行：成对发起、等最慢的那个）----
  const entries = Object.entries(PLACEMENTS).filter(([configId]) => !alreadyBuilt.has(configId))
  for (let i = 0; i < entries.length; i += 2) {
    const pair = entries.slice(i, i + 2)
    await topUp()
    const results = await Promise.all(pair.map(([configId, coords]) => upgrade(configId, coords)))
    results.forEach((result, index) => {
      const [configId] = pair[index]
      if (result.ok) {
        console.log(`[full-city] 建 ${configId} @${pair[index][1]}（${result.waitMs}ms）`)
      } else {
        console.error(`[full-city][判据失败] ${result.message}`)
        failures.push(result.message)
      }
    })
    await sleep(1500)
  }
}

// ---- 场景验收 ----
const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => { if (message.type() === 'error') errors.push(`console: ${message.text()}`) })

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'city')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => {
  if (window.cc === undefined || window.cc.director === undefined) return false
  const scene = window.cc.director.getScene()
  if (scene === null) return false
  const nav = scene.getChildByName('Canvas')?.getChildByName('Game')?.getComponent('PanelNav')
  return nav !== null && nav !== undefined && nav.currentKey === 'city'
}, null, { timeout: 25_000 }).catch(() => {})
await page.waitForTimeout(2500)
// 收掉引导层与落成贺礼弹窗（都挡城景）
await page.evaluate((source) => {
  const re = new RegExp(source)
  const scene = window.cc.director.getScene()
  const visit = (node) => {
    const label = node.getComponent && node.getComponent('cc.Label')
    if (/Guide/i.test(node.name) || (label !== null && label !== undefined && re.test(label.string ?? ''))) {
      const view = node.getComponent('GuideView')
      if (view !== null && view !== undefined) view.enabled = false
      node.removeFromParent()
      return
    }
    for (const child of node.children) visit(child)
  }
  visit(scene)
  const popup = (() => {
    let hit = null
    const walk = (n) => { if (hit !== null) return; if (n.name === 'giftPopup') { hit = n; return } for (const c of n.children) walk(c) }
    walk(scene)
    return hit
  })()
  if (popup !== null && popup.activeInHierarchy === true) {
    const view = popup.getComponent('GiftPopupView')
    if (view !== null && view !== undefined) view.hide()
  }
  // 「自上次登录以来」那一屏（B25-S3）：本工具建了 15 栋楼、中间隔了十几分钟，
  // 首登必然弹它，而它正好盖住城景中心（第一版截图就是这么被挡的）。
  // 它是 `new Node('OfflineReport')` 建出来的宿主（OfflineReportOverlay 只是包着节点的普通类，
  // 不是 Component）——所以按**节点名**整棵摘掉；第一版按组件找、结果只摘掉了那行标题。
  const offline = (() => {
    let hit = null
    const walk = (n) => { if (hit !== null) return; if (n.name === 'OfflineReport') { hit = n; return } for (const c of n.children) walk(c) }
    walk(scene)
    return hit
  })()
  if (offline !== null) {
    offline.active = false
  }
}, /第\s*\d+\s*\/\s*\d+\s*步|我完成了|升级主城：/.source)

const collectFrame = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const cameras = scene.getComponentsInChildren('cc.Camera')
  const cityView = scene.getComponentInChildren('CityPanelView')
  const artEntry = typeof window.System?.entries === 'function' ? Array.from(window.System.entries())
    .find(([key, module]) => /(?:^|\/)ArtFamilies\.ts(?:$|\?)/.test(key) && module?.FAMILY_ASSETS?.building) : null
  const art = artEntry?.[1] ?? null
  const rect = document.querySelector('canvas')?.getBoundingClientRect()
  const dpr = window.devicePixelRatio || 1
  const refs = new Map((cityView?.gridTiles ?? []).map(tile => [tile.node.name, tile]))
  const rows = new Map((cityView?.panel?.rows ?? []).map(row => [`${row.gridX}:${row.gridY}`, row]))
  const tiles = []
  const geometryErrors = []
  const project = (node, box, x, y) => {
    const camera = cameras.find(camera => camera.enabled && camera.node.activeInHierarchy && (camera.visibility & node.layer) !== 0)
    if (!camera || !rect || !box) return null
    const world = box.convertToWorldSpaceAR(new window.cc.Vec3(x, y, 0))
    const screen = camera.worldToScreen(new window.cc.Vec3(world.x, world.y, world.z))
    const back = camera.screenToWorld(new window.cc.Vec3(screen.x, screen.y, screen.z))
    const roundTripDrift = Math.max(Math.abs(back.x - world.x), Math.abs(back.y - world.y))
    return { x: rect.left + screen.x / dpr, y: rect.top + rect.height - screen.y / dpr, roundTripDrift }
  }
  const visit = (node, shown) => {
    const on = shown && node.activeInHierarchy === true
    if (on && /^Grid-\d+$/.test(node.name)) {
      const icon = node.getChildByName('BuildingIcon')
      const sprite = icon === null ? null : icon.getComponent('cc.Sprite')
      const texts = []
      const collect = (child) => {
        const label = child.getComponent && child.getComponent('cc.Label')
        if (label !== null && label !== undefined && label.string !== '') texts.push(label.string)
        for (const grand of child.children) collect(grand)
      }
      collect(node)
      let ancestorOpacity = true
      for (let ancestor = icon; ancestor; ancestor = ancestor.parent) {
        if (ancestor.getComponent('cc.UIOpacity')?.opacity === 0) ancestorOpacity = false
      }
      const drawable = !!icon && icon.activeInHierarchy && !!sprite?.spriteFrame && sprite.enabled
        && icon._uiProps?.uiComp === sprite && icon.layer === node.layer
        && cameras.some(camera => camera.enabled && camera.node.activeInHierarchy && (camera.visibility & icon.layer) !== 0)
        && sprite.color.a > 0 && ancestorOpacity
      const ref = refs.get(node.name)
      const row = ref ? rows.get(`${ref.plate.gridX}:${ref.plate.gridY}`) : null
      const box = icon?.getComponent('cc.UITransform') ?? null
      const actualFrame = sprite?.spriteFrame ?? null
      const resource = actualFrame && art ? Object.values(art.FAMILY_ASSETS.building)
        .find(asset => asset.split('/').at(-1) === actualFrame.name) : null
      const corners = box ? [project(icon, box, -box.anchorX * box.width, (1 - box.anchorY) * box.height),
        project(icon, box, (1 - box.anchorX) * box.width, (1 - box.anchorY) * box.height),
        project(icon, box, (1 - box.anchorX) * box.width, -box.anchorY * box.height),
        project(icon, box, -box.anchorX * box.width, -box.anchorY * box.height)] : null
      if (drawable && (!row || !box || !resource || !corners?.every(point => point && Number.isFinite(point.x)
        && Number.isFinite(point.y) && Number.isFinite(point.roundTripDrift) && point.roundTripDrift <= 0.5))) {
        geometryErrors.push(`${node.name}: 生产行/实际素材路径/相机投影四角缺失或往返失败`)
      }
      if (drawable && (sprite.trim !== false || sprite.type !== 0 || sprite.color.a !== 255)) {
        geometryErrors.push(`${node.name}: 主体mask要求当前真实Sprite为SIMPLE、trim=false且alpha=255`)
      }
      for (let ancestor = icon; drawable && ancestor; ancestor = ancestor.parent) {
        const opacity = ancestor.getComponent('cc.UIOpacity')?.opacity
        if (opacity !== undefined && opacity !== 255) geometryErrors.push(`${node.name}: 祖先opacity非255，不能可靠计算主体mask`)
      }
      tiles.push({
        tile: node.name, texts,
        iconActive: icon === null ? null : icon.active,
        drawable,
        frameName: sprite === null || sprite.spriteFrame === null ? null : sprite.spriteFrame.name,
        configId: row?.configId ?? null, gridX: row?.gridX ?? null, gridY: row?.gridY ?? null,
        expectedFrame: row && art ? art.FAMILY_ASSETS.building[row.configId]?.split('/').at(-1) ?? null : null,
        name: String(node.getChildByName('Name')?.getComponent('cc.Label')?.string ?? ''),
        level: String(node.getChildByName('Level')?.getComponent('cc.Label')?.string ?? ''),
        pngPath: resource ? `client/assets/resources/${resource}.png` : null,
        trim: sprite?.trim ?? null, spriteType: sprite?.type ?? null, spriteAlpha: sprite?.color.a ?? null,
        originalSize: actualFrame ? { width: actualFrame.originalSize.width, height: actualFrame.originalSize.height } : null,
        frameRect: actualFrame ? { x: actualFrame.rect.x, y: actualFrame.rect.y,
          width: actualFrame.rect.width, height: actualFrame.rect.height } : null,
        contentSize: box ? { width: box.width, height: box.height, anchorX: box.anchorX, anchorY: box.anchorY } : null,
        screenCorners: corners, order: [node.getSiblingIndex(), icon?.getSiblingIndex() ?? -1],
        depth: ref?.plate.depth ?? null,
        actualPosition: { x: node.position.x, y: node.position.y },
        plate: ref ? { x: ref.plate.x, y: ref.plate.y, depth: ref.plate.depth } : null,
      })
    }
    for (const child of node.children) visit(child, on)
  }
  visit(scene, true)
  if (!cityView || !art || !rect) geometryErrors.push('CityPanelView、产物ArtFamilies或canvas元数据缺失')
  return { tiles, geometryErrors, artSource: artEntry?.[0] ?? null, zoom: cityView?.stage?.scale.x ?? null,
    canvas: rect ? { left: rect.left, top: rect.top, width: rect.width, height: rect.height } : null }
})
const frame = await collectFrame()
await page.screenshot({ path: SHOT })

// ---- 逐类判据：15 类都必须有一格画着 building-* 正稿，且名字对得上 ----
const built = (await cityOf()).buildings
const arts = frame.tiles.filter((tile) => tile.drawable === true)
console.log(`[full-city] 城内 ${built.length} 栋；画面上有正稿的格子 ${arts.length} 个：`)
for (const tile of arts) {
  console.log(`   ${tile.tile}  ${tile.texts.join(' ')}  帧名=${tile.frameName}`)
}
console.log(`[full-city] 截图：${SHOT}`)

for (const building of built) {
  const tile = arts.find((candidate) => candidate.texts.includes(building.name))
  if (tile === undefined) {
    failures.push(`${building.name}（${building.configId}）在画面上没有正稿`)
  } else if (tile.frameName !== `building-${building.configId.replaceAll('_', '-')}-v1`) {
    failures.push(`${building.name} 画的是 ${tile.frameName}，不是自身的建筑正稿`)
  }
}
if (built.length < 15) {
  failures.push(`只建起 ${built.length} 栋（应为 15：主城 + 14 类）`)
}

const intended = { main_city: [MAIN_CITY.gridX, MAIN_CITY.gridY], ...PLACEMENTS }
for (const building of built) {
  const coords = intended[building.configId]
  if (!coords || building.gridX !== coords[0] || building.gridY !== coords[1]) {
    failures.push(`${building.configId} 实际存档格(${building.gridX},${building.gridY})不属于 ${LAYOUT} fixture；请用该布局的新探针账号，不迁移旧号`)
  }
}
const snapshotFailures = (snapshot) => {
  const issues = [...snapshot.geometryErrors]
  const drawn = snapshot.tiles.filter(tile => tile.drawable)
  if (drawn.length !== built.length || new Set(drawn.map(tile => `${tile.gridX}:${tile.gridY}`)).size !== drawn.length) {
    issues.push('实际绘制建筑与实时城建存档未一一覆盖')
  }
  for (const building of built) {
    const tile = drawn.find(tile => tile.configId === building.configId && tile.gridX === building.gridX && tile.gridY === building.gridY)
    if (!tile || tile.name !== building.name || tile.level !== `${building.level}级`
      || !tile.expectedFrame || tile.frameName !== tile.expectedFrame) {
      issues.push(`${building.configId}@${building.gridX},${building.gridY}: 真实renderer的正稿/名字/等级/格位未对应本轮API城建行`)
    }
  }
  return issues
}
const analyze = (snapshot, phase) => {
  const input = path.join(OUT, `city-occlusion-${phase}-snapshot.json`)
  const output = path.join(OUT, `city-occlusion-${phase}.json`)
  const issues = snapshotFailures(snapshot)
  writeFileSync(input, JSON.stringify({ ...snapshot, phase, layout: LAYOUT, backend: BACKEND, deviceId, built,
    buildings: snapshot.tiles.filter(tile => tile.drawable), identityErrors: issues,
    metadata: { layout: LAYOUT, backend: BACKEND, deviceId, zoom: snapshot.zoom, artSource: snapshot.artSource, input } }, null, 2))
  const command = process.platform === 'win32' ? 'python' : 'python3'
  const result = spawnSync(command, ['tools/lib/city-occlusion.py', input, output], {
    encoding: 'utf8', env: { ...process.env, PYTHONIOENCODING: 'utf-8' }, maxBuffer: 2 * 1024 * 1024,
  })
  if (result.error || !existsSync(output)) {
    return { ok: false, errors: [...issues, `${command}/Pillow主体mask量具未完成：${result.error?.message ?? result.stderr?.trim() ?? result.status}`], buildings: [] }
  }
  const measured = JSON.parse(readFileSync(output, 'utf8'))
  if (issues.length > 0) measured.errors.push(...issues)
  measured.ok = result.status === 0 && measured.ok === true && issues.length === 0
  writeFileSync(output, JSON.stringify(measured, null, 2))
  for (const building of measured.buildings) {
    console.log(`[full-city][${phase}] ${building.name} ${building.frameName} 主体遮挡=${(building.occludedRatio * 100).toFixed(2)}%`)
  }
  return measured
}

// 保留默认主堡镜头截图；真实点缩小键，再取全城实体mask，避免把视口外截断当建筑间遮挡。
for (let i = 0; i < 3; i += 1) {
  const point = await clickNodeViaCocos(page, { name: 'ZoomOutButton', within: 'city' })
  if (!point.clicked) failures.push(`全城缩小第${i + 1}次：真鼠标坐标未通过相机往返/引擎命中`)
  await page.waitForTimeout(240)
}
const overview = await collectFrame()
if (Math.abs(overview.zoom - 1) > 0.02) failures.push(`全城主体mask前提：真缩小后zoom=${overview.zoom}，必须为1`)
await page.screenshot({ path: path.join(OUT, '14-city-full-overview.png') })
const normalOcclusion = analyze(overview, 'overview')
if (!normalOcclusion.ok) failures.push(...normalOcclusion.errors)

// 同一页面复现旧前庭东格的真实遮挡：只改当前节点显示位置与绘制序，绝不写城建API或存档。
const negativeReady = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const cityView = scene.getComponentInChildren('CityPanelView')
  const entry = typeof window.System?.entries === 'function' ? Array.from(window.System.entries())
    .find(([key, module]) => /(?:^|\/)CitySceneAnchors\.ts(?:$|\?)/.test(key)
      && typeof module?.scenePlatesBackToFront === 'function') : null
  const layout = entry?.[1]
  const rows = cityView?.panel?.rows ?? []
  const academyRow = rows.find(row => row.configId === 'academy')
  const academy = academyRow ? cityView.gridTiles.find(tile => tile.plate.gridX === academyRow.gridX && tile.plate.gridY === academyRow.gridY) : null
  const scale = cityView?.sceneLayout?.scale
  if (!academy || !layout || !Number.isFinite(scale) || scale <= 0 || !academy.node.parent) return false
  const grid = academy.node.parent
  globalThis.__fullCityOcclusionRestore = { node: academy.node, position: academy.node.position.clone(), order: [...grid.children] }
  // 历史反证坐标（1000×625工作视图的565,213）；尺寸与当前投影尺度均来自运行产物。
  academy.node.setPosition(new window.cc.Vec3((565 - layout.SCENE_VIEW_WIDTH / 2) * scale,
    (layout.SCENE_VIEW_HEIGHT / 2 - 213) * scale, academy.node.position.z))
  const ordered = layout.scenePlatesBackToFront(cityView.gridTiles.map(tile => ({ ...tile.plate,
    depth: tile === academy ? 213 : tile.plate.depth,
    x: tile === academy ? academy.node.position.x : tile.plate.x })))
  const first = Math.min(...cityView.gridTiles.map(tile => tile.node.getSiblingIndex()))
  ordered.forEach((plate, index) => cityView.gridTiles.find(tile => tile.plate.gridX === plate.gridX
    && tile.plate.gridY === plate.gridY)?.node.setSiblingIndex(first + index))
  return true
})
if (!negativeReady) failures.push('旧学院实画遮挡负控前提缺失：生产学院节点、投影模块或真实绘制序不可读')
if (negativeReady) {
  try {
    await page.waitForTimeout(180)
    const negative = await collectFrame()
    const negativeIssues = snapshotFailures(negative)
    if (negativeIssues.length) failures.push(...negativeIssues)
    await page.screenshot({ path: path.join(OUT, '14-city-full-negative-old-academy.png') })
    const result = analyze(negative, 'negative-old-academy')
    const academy = result.buildings.find(building => building.configId === 'academy')
    if (negativeIssues.length || result.ok || !(academy?.occludedRatio > 0.20)) {
      failures.push('旧学院真实节点/绘制序负控没有让同一主体遮挡门因学院遮挡>20%翻红')
    } else {
      console.log(`[full-city] PASS 旧学院真实负控被同门抓到：主体遮挡 ${(academy.occludedRatio * 100).toFixed(2)}%`)
    }
  } finally {
    await page.evaluate(() => {
      const restore = globalThis.__fullCityOcclusionRestore
      restore.node.setPosition(restore.position)
      restore.order.forEach((node, index) => node.setSiblingIndex(index))
      delete globalThis.__fullCityOcclusionRestore
    })
  }
  await page.waitForTimeout(180)
  const restored = await collectFrame()
  await page.screenshot({ path: path.join(OUT, '14-city-full-restored-overview.png') })
  const restoredResult = analyze(restored, 'restored')
  if (!restoredResult.ok) failures.push(...restoredResult.errors)
  if (JSON.stringify(restored.tiles.map(tile => [tile.tile, tile.actualPosition, tile.order]))
    !== JSON.stringify(overview.tiles.map(tile => [tile.tile, tile.actualPosition, tile.order]))) {
    failures.push('实体遮挡负控还原后实际位置/绘制序未恢复原快照')
  }
}
await browser.close()
await preview.close()
console.log(`[full-city] 页面与控制台报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}
if (failures.length > 0) {
  console.error(`[full-city] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[full-city] 全绿：15类实际正稿、中文标注与存档格位完整对应；主体遮挡均≤20%，旧学院真实负控红→还原绿')
void GRID
void MAIN_CITY
