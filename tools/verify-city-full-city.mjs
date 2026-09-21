/**
 * 职责：**整城验收** —— 把主城升到 8 级、把 15 类建筑全部建出来，逐类确认画面上叠着各自的 `building-*` 正稿。
 * 依赖：`client/build/web-mobile` 产物 + 一台**本轮自己的** dev 后端（`BACKEND_ORIGIN`）+ Playwright。
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
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const OPS_TOKEN = process.env.ART_VERIFY_OPS_TOKEN ?? 'art-verify-local'
const PORT = Number(process.env.FULL_CITY_PORT ?? 8296)
const OUT = 'client/build/art-verify'
const SHOT = path.join(OUT, '14-city-full.png')
const WAIT_CAP_MS = Number(process.env.FULL_CITY_WAIT_CAP_MS ?? 180_000)
/** 城镇网格 6×6；主城固定中心格。 */
const GRID = 6
const MAIN_CITY = { gridX: 3, gridY: 3 }
/** 其余 14 类各给一个不冲突的落点（城墙按 city_rule_wall_edge_only 放边缘格）。 */
const PLACEMENTS = {
  lumber_camp: [0, 0], quarry: [1, 0], farm: [2, 0], iron_mine: [4, 0], warehouse: [5, 0],
  barracks: [0, 1], stable: [1, 1], archery_range: [4, 1], siege_workshop: [5, 1],
  drill_ground: [0, 2], hospital: [1, 2], academy: [4, 2], embassy: [5, 2], wall: [0, 3],
}

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

const frame = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const tiles = []
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
      tiles.push({
        tile: node.name, texts,
        iconActive: icon === null ? null : icon.active,
        frameName: sprite === null || sprite.spriteFrame === null ? null : sprite.spriteFrame.name,
      })
    }
    for (const child of node.children) visit(child, on)
  }
  visit(scene, true)
  return { tiles }
})
await page.screenshot({ path: SHOT })
await browser.close()
await preview.close()

// ---- 逐类判据：15 类都必须有一格画着 building-* 正稿，且名字对得上 ----
const built = (await cityOf()).buildings
const arts = frame.tiles.filter((tile) => tile.iconActive === true && tile.frameName !== null)
console.log(`[full-city] 城内 ${built.length} 栋；画面上有正稿的格子 ${arts.length} 个：`)
for (const tile of arts) {
  console.log(`   ${tile.tile}  ${tile.texts.join(' ')}  帧名=${tile.frameName}`)
}
console.log(`[full-city] 截图：${SHOT}`)
console.log(`[full-city] 页面报错 ${errors.length} 条${errors.length ? '：' + errors[0] : ''}`)

for (const building of built) {
  if (building.configId === 'main_city') {
    continue
  }
  const tile = arts.find((candidate) => candidate.texts.includes(building.name))
  if (tile === undefined) {
    failures.push(`${building.name}（${building.configId}）在画面上没有正稿`)
  } else if (!/^building-/.test(tile.frameName ?? '')) {
    failures.push(`${building.name} 画的是 ${tile.frameName}，不是 building-* 正稿`)
  }
}
if (built.length < 15) {
  failures.push(`只建起 ${built.length} 栋（应为 15：主城 + 14 类）`)
}
const mainCityArt = arts.find((tile) => tile.texts.includes('主城'))
if (mainCityArt !== undefined) {
  failures.push(`主城不该叠正稿（底图已有城堡）：${mainCityArt.frameName}`)
}
if (errors.length > 0) {
  failures.push(`页面报错 ${errors.length} 条：${errors[0]}`)
}
if (failures.length > 0) {
  console.error(`[full-city] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log('[full-city] 全绿：15 类建筑全部就位，每一类都画着自己的正稿，主城不叠图')
void GRID
void MAIN_CITY
