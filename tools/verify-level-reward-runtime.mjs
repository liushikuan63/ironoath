/**
 * 职责：等级奖励面板的运行时量具（收口清单 #829 三项裁决的前台验收：表 + 点领取才发 + 新建面板）。
 * 后端：**必须打在带 dev 提速档的后端上**（`IRONOATH_DEV_CITY_LEVEL=16`）。
 *   理由不是"想快"，而是量具前提：新号只有 1 级时屏上不可能有「待领取」这一态，
 *   「已领/待领两态可辨」这条判据根本走不到 —— 所以读不到 16 级时本量具**退 2 报前提不足**，
 *   不退 1（退 1 会把"量具没架对"算成功能红， nation 那一族已经为此加过同款守卫）。
 * 依赖：playwright + client/build/web-mobile 产物 + 活后端（先跑 scripts/build-webmobile.sh）。
 *
 * 用法（三条都要现跑，不许引用旧读数）：
 *   BOOST 档后端在 8198 时：`LEVEL_REWARD_BACKEND=http://localhost:8198 node tools/verify-level-reward-runtime.mjs`
 *   批跑：`bash scripts/run-batch-dual-backend.sh <清单>`（本份已在 run-runtime-probes.sh 的 BOOST_PROBES 默认名单里）
 * 可覆盖：`LEVEL_REWARD_BACKEND` · `LEVEL_REWARD_PORT`（预览端口，**绝不能等于后端端口**）· `LEVEL_REWARD_OUT`
 *
 * 七相：
 *   A 深链进面板 → 当前页真画出来、三态文案与 HTTP 现读一致、屏上不出现裸 id
 *   B 点「领取」→ 屏上那一行真翻成已领（点了不换屏 = 假绿），并让服务端复验
 *   C 走玩家路径（导航条 →「更多」抽屉 →「等级」）重新进入 → 已领与待领同时可辨 + 截图
 *   P 普通/高/极矮窗口逐页真点击 → 1..40 全可达、范围与实画一致、分页净区/边界、翻页领取保留页
 *   M1 植入：把服务端下发的中文名换成内部码 → 裸 id 判据**必须**报红
 *   M2 植入：把 claimable 全置 false 而 claimableCount 留 1 → 计数同源判据**必须**报红
 *   R  还原：撤掉桩重开 → 两条植入判据复绿
 *   （M/R 是量具自己的可失败性证据 —— 没有它们，A/C 的"绿"只是"没触发"）
 */
import path from 'node:path'
import fs from 'node:fs'
import { chromium } from 'playwright'
import { startPreviewServer } from '../tools/lib/preview-server.mjs'
import { makeStubRead } from '../tools/lib/route-stub.mjs'

const BACKEND = process.env.LEVEL_REWARD_BACKEND ?? 'http://localhost:8198'
const PORT = Number(process.env.LEVEL_REWARD_PORT ?? 8312)
const OUT = process.env.LEVEL_REWARD_OUT ?? 'tmp/level-reward-shots'
console.log(`[level-reward] 后端 ${BACKEND} · 预览端口 ${PORT} · 产物 ${OUT}`)

const uniq = `lvprobe-${Date.now()}-${Math.floor(Math.random() * 1e6)}`
const post = async (url, body) => (await fetch(`${BACKEND}${url}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
})).json()
const getPlayer = async (playerId, url) => (await fetch(`${BACKEND}${url}`, {
  headers: { 'X-Player-Id': playerId },
})).json()

const fail = []
const skip = []
const readings = []
const ok = (name, cond, detail) => {
  if (cond) {
    readings.push(`${name} 绿`)
    return true
  }
  fail.push(`${name}：${detail}`)
  return false
}
const bad = (name, cond, detail) => {
  // 植入相要的正是"判据报红"：条件成立（判据抓到了）才算这一相过
  if (cond) {
    readings.push(`${name} 按计划翻红`)
    return true
  }
  fail.push(`${name}：植入之后判据没翻红（量具假绿）：${detail}`)
  return false
}

const init = await post('/player/init', {
  requestId: `${uniq}-init`, deviceId: uniq, nickName: '等级奖励量具', clientTime: Date.now(), wxCode: '',
})
if (init.code !== 0) {
  console.error(`[level-reward][前置] 建号失败：${JSON.stringify(init)}`)
  process.exit(2)
}
const playerId = init.data.playerId
const listed = await getPlayer(playerId, '/level-reward/list')
if (listed.code !== 0 || listed.data === undefined) {
  console.error(`[level-reward][前置] 读口 /level-reward/list 不可用：${JSON.stringify(listed)}`)
  process.exit(2)
}
const mainLevel = listed.data.mainLevel
const claimableNow = listed.data.claimableCount
console.log(`[level-reward][前置] playerId=${playerId} 主城 ${mainLevel} 级 · 可领 ${claimableNow} 级 · 表 ${listed.data.rows.length} 行`)
if (mainLevel < 2 || listed.data.rows.length < 3) {
  console.error(`[level-reward][前提不足] 主城 ${mainLevel} 级 ⇒ 屏上不会有「待领取」那一态，`
    + `「已领/待领两态可辨」这条判据走不到。请把本份跑在带 IRONOATH_DEV_CITY_LEVEL 的提速档后端上。`)
  process.exit(2)
}

fs.mkdirSync(OUT, { recursive: true })
const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), uniq)
const stubRead = makeStubRead(context)
const page = await context.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(e.message))

/** 屏上当前所有非空 Label 文本（组件一律按注册名取：release 产物里 constructor.name 被压缩）。 */
const dumpLabels = () => page.evaluate(() => {
  const out = []
  const walk = (n) => {
    if (!n.activeInHierarchy) return
    const label = n.getComponent('cc.Label')
    if (label !== null && label !== undefined && label.string !== '') out.push(label.string)
    for (const child of n.children) walk(child)
  }
  walk(window.cc.director.getScene())
  return out
})

/**
 * 逐行读数：行名 / 状态文案 /「领取」键在屏幕上的位置。
 * 结构：LevelRow 节点下挂着 Name、Reward、State 三个 Label 与 ClaimButton 节点。
 */
const dumpRows = () => page.evaluate(() => {
  const scene = window.cc.director.getScene()
  let cam = null
  const findCam = (n) => {
    if (cam === null && n.getComponent) {
      const c = n.getComponent('cc.Camera')
      if (c !== null && c !== undefined) cam = c
    }
    for (const child of n.children) findCam(child)
  }
  findCam(scene)
  const rect = document.querySelector('canvas').getBoundingClientRect()
  const toScreen = (node) => {
    const u = node.getComponent('cc.UITransform')
    const wb = u.getBoundingBoxToWorld()
    const s = cam.worldToScreen(new window.cc.Vec3(wb.x + wb.width / 2, wb.y + wb.height / 2, 0))
    return { x: Math.round(s.x + rect.left), y: Math.round(rect.top + rect.height - s.y) }
  }
  const out = []
  const walk = (n) => {
    if (n.activeInHierarchy && n.name === 'LevelRow') {
      const labels = {}
      let button = null
      const inner = (child) => {
        const label = child.getComponent('cc.Label')
        if (label !== null && label !== undefined && label.string !== '') labels[child.name] = label.string
        if (child.name === 'ClaimButton') button = child
        for (const g of child.children) inner(g)
      }
      for (const child of n.children) inner(child)
      out.push({
        name: labels.Name ?? '', reward: labels.Reward ?? '', state: labels.State ?? '',
        pos: button !== null ? toScreen(button) : null,
      })
    }
    for (const child of n.children) walk(child)
  }
  walk(scene)
  return out
})

/** 分页壳与净区盒子；坐标和现有点击助手一样从相机换算，不按页码等分猜位置。 */
const dumpPaging = () => page.evaluate(() => {
  const cc = window.cc
  const scene = cc.director.getScene()
  let cam = null
  let panel = null
  const find = (node) => {
    if (cam === null) cam = node.getComponent('cc.Camera')
    if (node.name === 'levelReward' && node.activeInHierarchy) panel = node
    node.children.forEach(find)
  }
  find(scene)
  const canvas = document.querySelector('canvas').getBoundingClientRect()
  const boxOf = (node) => {
    const b = node.getComponent('cc.UITransform').getBoundingBoxToWorld()
    return { x: b.x, y: b.y, width: b.width, height: b.height }
  }
  const buttonOf = (name) => {
    const node = panel?.getChildByName(name)
    if (!node?.activeInHierarchy || cam === null) return null
    const box = boxOf(node)
    const p = cam.worldToScreen(new cc.Vec3(box.x + box.width / 2, box.y + box.height / 2, 0))
    const color = node.getChildByName('Caption').getComponent('cc.Label').color
    return {
      box, pos: { x: p.x + canvas.left, y: canvas.top + canvas.height - p.y },
      dim: color.r === 150 && color.g === 140 && color.b === 124,
    }
  }
  const header = panel?.getChildByName('Header')?.getComponent('cc.Label')?.string ?? ''
  const notice = panel?.getChildByName('PageNotice')
  return {
    header,
    pageText: notice?.activeInHierarchy ? notice.getComponent('cc.Label').string : '',
    pageBox: notice?.activeInHierarchy ? boxOf(notice) : null,
    prev: buttonOf('PrevPageButton'), next: buttonOf('NextPageButton'),
    rowBoxes: (panel?.children ?? []).filter(n => n.name === 'LevelRow' && n.activeInHierarchy).map(boxOf),
    headerBoxes: (panel?.children ?? []).filter(n => ['Header', 'Summary', 'Notice'].includes(n.name)
      && n.activeInHierarchy && n.getComponent('cc.Label')?.string !== '').map(boxOf),
    visible: { width: cc.view.getVisibleSize().width, height: cc.view.getVisibleSize().height },
  }
})

const intersects = (a, b) => a.x < b.x + b.width && b.x < a.x + a.width
  && a.y < b.y + b.height && b.y < a.y + a.height
const pagingLayoutErrors = (paging) => {
  const boxes = [paging.prev?.box, paging.pageBox, paging.next?.box].filter(Boolean)
  const issues = []
  if (boxes.length !== 3) issues.push('页码或两颗翻页键缺失')
  for (const [index, box] of boxes.entries()) {
    if (box.y < 68 || box.y + box.height > paging.visible.height
      || box.x < 0 || box.x + box.width > paging.visible.width) {
      issues.push(`分页盒 ${index} 越出净区：${JSON.stringify(box)}`)
    }
    if (paging.rowBoxes.some(row => intersects(row, box))) issues.push(`分页盒 ${index} 与奖励行相撞`)
    if (paging.headerBoxes.some(header => intersects(header, box))) issues.push(`分页盒 ${index} 与表头文案相撞`)
    if (boxes.slice(index + 1).some(other => intersects(other, box))) issues.push(`分页盒 ${index} 与同排按钮/页码相撞`)
  }
  return issues
}

const pageRangeErrors = (paging, rows, source) => {
  const levels = rows.map(row => source.rows.find(candidate => candidate.name === row.name)?.level)
  const range = paging.header.match(/第 (\d+)–(\d+) 级 \/ 共 (\d+) 级/)
  if (range === null || levels.length === 0 || levels.some(level => level === undefined)) return ['表头/等级行缺失']
  const errors = []
  if (Number(range[1]) !== levels[0] || Number(range[2]) !== levels.at(-1)
    || Number(range[3]) !== source.rows.length) errors.push(`表头 ${paging.header} 与实画 ${levels.join(',')} 不符`)
  const declared = source.rows.filter(row => row.level >= Number(range[1]) && row.level <= Number(range[2])).map(row => row.level)
  if (JSON.stringify(levels) !== JSON.stringify(declared)) errors.push('范围内有漏画或重复等级')
  return errors
}

/** 导航格（条上与抽屉里都算）：按名字取屏幕坐标，点不到就返回 null（不许用写死的等分）。 */
const navPos = (key) => page.evaluate((target) => {
  const scene = window.cc.director.getScene()
  let cam = null
  const findCam = (n) => {
    if (cam === null && n.getComponent) {
      const c = n.getComponent('cc.Camera')
      if (c !== null && c !== undefined) cam = c
    }
    for (const child of n.children) findCam(child)
  }
  findCam(scene)
  const rect = document.querySelector('canvas').getBoundingClientRect()
  let hit = null
  const walk = (n) => {
    if (hit === null && n.name === `Nav-${target}` && n.activeInHierarchy) {
      const u = n.getComponent('cc.UITransform')
      const wb = u.getBoundingBoxToWorld()
      const s = cam.worldToScreen(new window.cc.Vec3(wb.x + wb.width / 2, wb.y + wb.height / 2, 0))
      hit = { x: Math.round(s.x + rect.left), y: Math.round(rect.top + rect.height - s.y) }
    }
    for (const child of n.children) walk(child)
  }
  walk(scene)
  return hit
}, key)

// 引导会在数据到达后重新激活并吃掉触摸（实测导航因此停在 city）⇒ 每次点击前都藏一次
const hideGuide = () => page.evaluate(() => {
  const walk = (n) => {
    if (n.name === 'Guide') n.active = false
    for (const child of n.children) walk(child)
  }
  walk(window.cc.director.getScene())
})

/** 两条运行时判据。绿相应为空，植入相必须非空 —— 同一对函数，不许为植入另写一套。 */
const BARE_ID = /\b(WOOD|STONE|IRON|GRAIN|GOLD|RESOURCE|lr_lv\d+|main_city|pool_std)\b/
const bareIdHits = (labels) => labels.filter((text) => BARE_ID.test(text))
/**
 * 汇总行「主城 N 级 · 可领 M 级」的两个数必须等于**同一时刻**服务端现读的那两位。
 *
 * <p>为什么不是"汇总数 = 屏上待领行数"（第一版写成这样，跑出来是红的）：面板画的是一个窗口
 * （5~6 行），而可领总数是整张 40 行表里的总数 —— 两个量本来就不同。真正不同源的风险是
 * **客户端自己数了一份** 或 **用了上一次打开时的缓存**，这条判据抓的正是那两种形状。
 */
const summaryMismatch = (labels, live) => {
  const summary = labels.find((text) => /^主城 \d+ 级 · 可领 \d+ 级$/.test(text))
  if (summary === undefined) return ['表头那行汇总根本没画出来']
  const hit = summary.match(/主城 (\d+) 级 · 可领 (\d+) 级/)
  if (hit === null) return [`汇总行念的是「${summary}」，取不到两个数`]
  const out = []
  if (Number(hit[1]) !== live.mainLevel) out.push(`屏上主城 ${hit[1]} 级而服务端现读 ${live.mainLevel} 级`)
  if (Number(hit[2]) !== live.claimableCount) {
    out.push(`屏上汇总说可领 ${hit[2]} 级，服务端同刻现读是 ${live.claimableCount} 级`)
  }
  return out
}

const openByDeepLink = async () => {
  await page.goto(`${preview.origin}/?panel=levelReward`)
  await page.waitForFunction(() => window.cc?.director?.getScene() != null, null, { timeout: 60000 })
  await page.waitForTimeout(2500)
  await hideGuide()
  await page.waitForFunction(() => {
    const out = []
    const walk = (n) => {
      if (!n.activeInHierarchy) return
      const label = n.getComponent('cc.Label')
      if (label !== null && label !== undefined && /^主城 \d+ 级奖励$/.test(label.string)) out.push(1)
      for (const child of n.children) walk(child)
    }
    walk(window.cc.director.getScene())
    return out.length > 0
  }, null, { timeout: 30000 }).catch(() => false)
}

// ============ 相 A：深链进面板 ============
await openByDeepLink()
const labelsA = await dumpLabels()
const rowsA = await dumpRows()
const expectA = await getPlayer(playerId, '/level-reward/list')
const leafOf = (nodes, key) => {
  for (const node of nodes) {
    if (node.key === key) return node
    const child = leafOf(node.children ?? [], key)
    if (child !== null) return child
  }
  return null
}
const navDots = () => page.evaluate(() => {
  let nav = null
  const find = node => {
    nav ??= node.getComponent('PanelNav')
    node.children.forEach(find)
  }
  find(window.cc.director.getScene())
  return { level: nav?.navDots?.get('levelReward')?.active ?? null,
    more: nav?.navDots?.get('more')?.active ?? null }
})
const notificationA = (await getPlayer(playerId, '/social/reddot')).data
ok('A等级奖励叶子和父链按权威可领数点亮',
  leafOf(notificationA.nodes, 'levelReward/claimable')?.lit === (claimableNow > 0)
    && leafOf(notificationA.nodes, 'levelReward')?.lit === (claimableNow > 0), JSON.stringify(notificationA))
const dotsA = await navDots()
ok('A等级入口与更多真实红点点亮', dotsA.level === true && dotsA.more === true, JSON.stringify(dotsA))
readings.push(`A 画出 ${rowsA.length} 行 / 服务端 ${expectA.data.rows.length} 行`)
ok('A1 面板真画出行', rowsA.length >= 3, `只画出 ${rowsA.length} 行`)
ok('A2 行名逐条对得上服务端下发的中文名',
  rowsA.every((r) => expectA.data.rows.some((row) => row.name === r.name)),
  `画出来的行名 ${JSON.stringify(rowsA.slice(0, 3).map(r => r.name))} 不在服务端那一份里`)
ok('A3 三态文案与服务端那三位一致',
  rowsA.every((r) => {
    const row = expectA.data.rows.find((candidate) => candidate.name === r.name)
    if (row === undefined) return false
    const want = row.claimed ? '已领取' : (row.locked ? '主城' : '待领取')
    return r.state.startsWith(want)
  }), '某一行画出来的态与服务端的结论位不符')
ok('A4 奖励串里出现的是中文名而不是内部码',
  rowsA.every((r) => /×[\d,]+/.test(r.reward) && r.reward !== ''), '奖励串为空或没有数量')
ok('A5 屏上不出现裸 id', bareIdHits(labelsA).length === 0, JSON.stringify(bareIdHits(labelsA).slice(0, 3)))
await page.screenshot({ path: path.join(OUT, 'a-panel-open.png') })

// ============ 相 B：点「领取」真换屏 ============
const target = rowsA.find((r) => r.state === '待领取' && r.pos !== null)
if (target === undefined) {
  skip.push('B：屏上没有「待领取」的行（提速档后端没给到 2 级以上？）—— 这一相未执行')
} else {
  const levelHit = target.name.match(/主城 (\d+) 级奖励/)
  if (levelHit === null) {
    fail.push(`B：那一行的行名「${target.name}」读不出等级，无法与服务端逐行对账`)
  }
  const claimedLevel = levelHit === null ? -1 : Number(levelHit[1])
  const before = await getPlayer(playerId, '/level-reward/list')
  const woodOf = (detail) => detail.resources.find((r) => r.type === 'WOOD')
  const resBefore = woodOf((await getPlayer(playerId, '/resource/detail')).data)
  await hideGuide()
  await page.mouse.click(target.pos.x, target.pos.y)
  // 点了还得真换屏：轮询那一行翻成已领（写后 AppRoot 会重拉本域列表，界面自己会更新）
  let flipped = false
  for (let i = 0; i < 24 && !flipped; i += 1) {
    await page.waitForTimeout(500)
    const rows = await dumpRows()
    flipped = rows.some((r) => r.name === target.name && r.state === '已领取')
  }
  const after = await getPlayer(playerId, '/level-reward/list')
  const serverClaimed = after.data.rows.find((r) => r.level === claimedLevel)?.claimed === true
  readings.push(`B 点了 ${target.name} → 屏上换屏=${flipped} · 服务端 claimed=${serverClaimed} · 可领数 ${before.data.claimableCount}→${after.data.claimableCount}`)
  ok('B1 点领取之后屏上那一行真翻成已领', flipped, `轮询 12 秒后那一行仍是 ${JSON.stringify((await dumpRows()).find(r => r.name === target.name)?.state)}`)
  ok('B2 服务端确实记下了这一级（不是本地假翻牌）', serverClaimed, '服务端仍说没领过')
  ok('B3 可领数随领取下降', after.data.claimableCount === before.data.claimableCount - 1,
    `${before.data.claimableCount} → ${after.data.claimableCount}`)
  // B4：入账量、表值、提示行三者必须自洽。提速档后端起始就把仓打满 ⇒
  //      "领了但一分没进仓"是这条链路上真会出现的一种，必须被说出来而不是静默。
  const rowAfter = before.data.rows.find((r) => r.level === claimedLevel)
  const tableWood = (rowAfter?.rewards.find((r) => r.id === 'WOOD') ?? { count: 0 }).count
  const resAfter = woodOf((await getPlayer(playerId, '/resource/detail')).data)
  const delta = resAfter.current - resBefore.current
  const fits = Math.min(tableWood, resAfter.cap - resBefore.current)
  const noticeLine = ((await dumpLabels()).find((t) => /已领取|装不下/.test(t)) ?? '').trim()
  const selfConsistent = delta >= fits
    && (fits < tableWood ? noticeLine.includes('装不下') : noticeLine.startsWith('已领取'))
  readings.push(`B4 表值 ${tableWood} · 装得下 ${fits} · 实入账 ${delta} · 提示「${noticeLine}」`)
  ok('B4 入账量与表值/提示行自洽（装不下就必须说出来）', selfConsistent,
    `表值 ${tableWood}、装得下 ${fits}、实入账 ${delta}、提示「${noticeLine}」`)
  await page.screenshot({ path: path.join(OUT, 'b-claimed.png') })
}

// ============ 相 C：走玩家路径重开，两态同时可辨 ============
await hideGuide()
const more = await navPos('more')
const barHit = await navPos('levelReward')
if (barHit !== null) {
  await page.mouse.click(barHit.x, barHit.y)
} else if (more !== null) {
  await page.mouse.click(more.x, more.y)
  await page.waitForTimeout(700)
  const drawerHit = await navPos('levelReward')
  if (drawerHit !== null) await page.mouse.click(drawerHit.x, drawerHit.y)
  else fail.push('C：抽屉展开后找不到 Nav-levelReward（导航注册没接上？）')
} else {
  fail.push('C：既找不到 Nav-levelReward 也找不到 Nav-more，玩家路径无法验收')
}
await page.waitForTimeout(1200)
await hideGuide()
// 从别的面板切回来要重新拉：先确认画出来了再说两态
const rowsC = await dumpRows()
const labelsC = await dumpLabels()
const liveC = (await getPlayer(playerId, '/level-reward/list')).data
const statesC = new Set(rowsC.map((r) => (r.state === '已领取' ? '已领'
  : (r.state === '待领取' ? '待领' : (r.state.startsWith('主城') ? '未达' : '其他')))))
readings.push(`C 玩家路径重开，画出 ${rowsC.length} 行，态集合 ${JSON.stringify(Array.from(statesC))}`)
ok('C1 导航真点得开这一页', rowsC.length >= 3, `只画出 ${rowsC.length} 行`)
ok('C2 已领与待领两态同时可辨', statesC.has('已领') && statesC.has('待领'),
  `屏上只有 ${JSON.stringify(Array.from(statesC))}`)
ok('C3 重开之后屏上仍不出现裸 id', bareIdHits(labelsC).length === 0, JSON.stringify(bareIdHits(labelsC).slice(0, 3)))
ok('C4 汇总行的两个数与同刻服务端现读一致（不许客户端自己数、也不许用旧缓存）',
  summaryMismatch(labelsC, liveC).length === 0, summaryMismatch(labelsC, liveC).join('；'))
await page.screenshot({ path: path.join(OUT, 'c-two-states.png') })

// ============ 相 P：真实翻页、完整可达与写后保留页（普通/高/极矮窗口） ============
let claimRequests = 0
page.on('request', request => {
  if (request.method() === 'POST' && request.url().includes('/level-reward/claim')) claimRequests += 1
})
const clickPager = async (direction) => {
  await hideGuide()
  const button = (await dumpPaging())[direction]
  if (button === null) return false
  // 真鼠标点击经过 Cocos 命中测试；emit 只证明处理器有接线，不能证明玩家按得着。
  await page.mouse.click(button.pos.x, button.pos.y)
  await page.waitForTimeout(120)
  return true
}
for (const [phase, viewport] of [
  ['P普通', { width: 1440, height: 900 }],
  ['P高窗', { width: 1200, height: 1400 }],
  // 项目的 FIXED_WIDTH=960，真实视口 1440×480 对应可视高 320，容量只剩一个内容槽位。
  ['P极矮', { width: 1440, height: 480 }],
  ['P更矮', { width: 1440, height: 360 }],
  ['P竖屏', { width: 390, height: 844 }],
]) {
  await page.setViewportSize(viewport)
  await openByDeepLink()
  const source = (await getPlayer(playerId, '/level-reward/list')).data
  const shotPrefix = ({ P普通: 'p-normal', P高窗: 'p-tall', P极矮: 'p-short',
    P更矮: 'p-compact', P竖屏: 'p-portrait' })[phase]
  let paging = await dumpPaging()
  if (paging.prev === null || paging.next === null) {
    ok(`${phase} 分页控件存在`, false, '找不到上一页/下一页，40 行仍只露一个窗口')
    continue
  }
  // 首开位于待领附近时先翻回第一页，再按玩家的操作逐页看完；全过程不发领取。
  const writesBeforePaging = claimRequests
  for (let guard = 0; guard < source.rows.length && !paging.prev.dim; guard += 1) {
    const previous = paging.pageText
    await clickPager('prev')
    paging = await dumpPaging()
    if (paging.pageText === previous) {
      fail.push(`${phase} 上一页真点击后页码没变`)
      break
    }
  }
  const firstText = paging.pageText
  await clickPager('prev')
  ok(`${phase} 首页上一页置灰且点击不变页`, paging.prev.dim && (await dumpPaging()).pageText === firstText,
    `首页 ${firstText}，点击后 ${(await dumpPaging()).pageText}`)
  const seen = []
  const rangeErrors = []
  const layoutErrors = []
  let lastRows = []
  for (let guard = 0; guard < source.rows.length; guard += 1) {
    paging = await dumpPaging()
    const rows = await dumpRows()
    lastRows = rows
    seen.push(...rows.map(row => source.rows.find(candidate => candidate.name === row.name)?.level))
    rangeErrors.push(...pageRangeErrors(paging, rows, source))
    layoutErrors.push(...pagingLayoutErrors(paging))
    if (rows.length > 5) rangeErrors.push(`分页后实画 ${rows.length} 内容行，越过六节点池应留一槽给分页的上限`)
    if (paging.next === null || paging.next.dim) break
    const previous = paging.pageText
    await clickPager('next')
    if ((await dumpPaging()).pageText === previous) {
      fail.push(`${phase} 下一页真点击后页码没变：${previous}`)
      break
    }
  }
  ok(`${phase} 逐页真点击可达全部等级且顺序无漏项/重复`,
    JSON.stringify(seen) === JSON.stringify(source.rows.map(row => row.level)), `实画 ${JSON.stringify(seen)}`)
  ok(`${phase} 每页表头范围等于真正画出的行`, rangeErrors.length === 0, rangeErrors.slice(0, 3).join('；'))
  ok(`${phase} 页码和按钮位于净区且不与奖励行/彼此碰撞`, layoutErrors.length === 0,
    layoutErrors.slice(0, 3).join('；'))
  const lastText = paging.pageText
  await clickPager('next')
  ok(`${phase} 末页下一页置灰且点击不变页`, paging.next?.dim && (await dumpPaging()).pageText === lastText,
    `末页 ${lastText}，点击后 ${(await dumpPaging()).pageText}`)
  ok(`${phase} 翻页和灰键点击不发领取请求`, claimRequests === writesBeforePaging,
    `领取请求 ${writesBeforePaging}→${claimRequests}`)
  readings.push(`${phase} 视口 ${viewport.width}×${viewport.height}，全程实画 ${seen.length} 级，末页 ${lastText}`)
  if (phase === 'P极矮') {
    ok('P极矮 真可视高320且每页仅画一行', Math.abs(paging.visible.height - 320) < 1
      && lastRows.length === 1, `可视高 ${paging.visible.height}，实画 ${lastRows.length} 行`)
  }
  await page.screenshot({ path: path.join(OUT, `${shotPrefix}-last.png`) })

  // 两个几何/范围判据各植入一次：少画一行、把下一页挪到内容行中心，必须被同一判据抓住。
  const plantedIndex = await page.evaluate(() => {
    const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.getChildByName('levelReward')
    const index = panel?.children.findIndex(node => node.name === 'LevelRow' && node.activeInHierarchy) ?? -1
    if (index >= 0) panel.children[index].active = false
    return index
  })
  bad(`${phase} 少画一行时范围判据翻红`, plantedIndex >= 0
    && pageRangeErrors(await dumpPaging(), await dumpRows(), source).length > 0, '少画行植入未被范围判据抓住')
  await page.evaluate(index => {
    const panel = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('levelReward')
    if (index >= 0) panel.children[index].active = true
  }, plantedIndex)
  const oldY = await page.evaluate(() => {
    const panel = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('levelReward')
    const button = panel?.getChildByName('NextPageButton')
    const row = panel?.children.find(node => node.name === 'LevelRow' && node.activeInHierarchy)
    if (!button || !row) return null
    const original = button.position.y
    button.setPosition(new window.cc.Vec3(button.position.x, row.position.y, 0))
    return original
  })
  await page.waitForTimeout(80)
  bad(`${phase} 按钮挪入奖励行时碰撞判据翻红`, oldY !== null
    && pagingLayoutErrors(await dumpPaging()).length > 0, '按钮碰撞植入未被净区判据抓住')
  await page.evaluate(y => {
    const panel = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('levelReward')
    const button = panel?.getChildByName('NextPageButton')
    if (button && y !== null) button.setPosition(new window.cc.Vec3(button.position.x, y, 0))
  }, oldY)
  await page.waitForTimeout(80)
  ok(`${phase} 撤桩后范围和碰撞判据复绿`, pageRangeErrors(await dumpPaging(), await dumpRows(), source).length === 0
    && pagingLayoutErrors(await dumpPaging()).length === 0, `撤桩后行数 ${lastRows.length}`)

  // 特意翻到第二页后领取，确保刷新没有按下一个待领等级把玩家搬回第一页。
  paging = await dumpPaging()
  for (let guard = 0; guard < source.rows.length && !paging.prev.dim; guard += 1) {
    await clickPager('prev')
    paging = await dumpPaging()
  }
  await clickPager('next')
  const beforeClaim = await dumpPaging()
  const claimRow = (await dumpRows()).find(row => row.state === '待领取' && row.pos !== null)
  if (claimRow === undefined) {
    skip.push(`${phase} 第二页没有待领行，未执行翻页后领取保留页判据`)
  } else {
    const beforeList = (await getPlayer(playerId, '/level-reward/list')).data
    const level = beforeList.rows.find(row => row.name === claimRow.name)?.level
    await hideGuide()
    await page.mouse.click(claimRow.pos.x, claimRow.pos.y)
    let claimed = false
    for (let attempt = 0; attempt < 24 && !claimed; attempt += 1) {
      await page.waitForTimeout(500)
      claimed = (await dumpRows()).some(row => row.name === claimRow.name && row.state === '已领取')
    }
    const afterClaim = await dumpPaging()
    const live = (await getPlayer(playerId, '/level-reward/list')).data
    ok(`${phase} 翻页领取后保留页码和范围且本行真翻成已领`, claimed
      && afterClaim.pageText === beforeClaim.pageText && afterClaim.header === beforeClaim.header
      && live.rows.find(row => row.level === level)?.claimed === true,
      `${beforeClaim.pageText} / ${beforeClaim.header}→${afterClaim.pageText} / ${afterClaim.header}，已领=${claimed}`)
    ok(`${phase} 领取刷新后汇总仍与权威读数一致`, summaryMismatch(await dumpLabels(), live).length === 0,
      summaryMismatch(await dumpLabels(), live).join('；'))
    await page.screenshot({ path: path.join(OUT, `${shotPrefix}-claimed.png`) })
  }
}
// 植入相沿用原先的普通窗口，避免改变 A/C 的量具前提。
await page.setViewportSize({ width: 1440, height: 900 })

// ============ 相 M1：把中文名换成内部码（裸 id 判据必须翻红） ============
const planted = JSON.parse(JSON.stringify(expectA.data))
let plantedRows = 0
for (const row of planted.rows) {
  for (const item of row.rewards) {
    if (item.name !== item.id) { item.name = item.id; plantedRows += 1 }
  }
}
await stubRead('**/level-reward/list', () => planted)
await openByDeepLink()
const labelsM1 = await dumpLabels()
const hitsM1 = bareIdHits(labelsM1)
readings.push(`M1 植入了 ${plantedRows} 项名称，屏上裸 id 命中 ${hitsM1.length} 条`)
ok('M1a 植入本身生效了（桩真被读到）', plantedRows > 0 && hitsM1.length > 0,
  `植入 ${plantedRows} 项而屏上命中 ${hitsM1.length} 条 —— 桩没生效就等于这一相没跑`)
bad('M1b 裸 id 判据在植入态翻红', hitsM1.length > 0, JSON.stringify(hitsM1.slice(0, 2)))

// ============ 相 M2：屏上的可领数与服务端权威数分叉（同源判据必须翻红） ============
// 植入的形状就是"客户端自己数了一份 / 用了旧缓存"在屏幕上的样子：
// 浏览器读到的是这份桩（可领数被写成 1、每一行的 claimable 都抹掉），而服务端权威读数仍是十几级。
const liveM2 = (await getPlayer(playerId, '/level-reward/list')).data
const planted2 = JSON.parse(JSON.stringify(liveM2))
planted2.rows = planted2.rows.map((row) => ({ ...row, claimable: false }))
planted2.claimableCount = 1
await stubRead('**/level-reward/list', () => planted2)
await openByDeepLink()
const labelsM2 = await dumpLabels()
const mismatch2 = summaryMismatch(labelsM2, liveM2)
const summaryDrawn = labelsM2.some((t) => /^主城 \d+ 级 · 可领 \d+ 级$/.test(t))
readings.push(`M2 屏上汇总 ${JSON.stringify(labelsM2.find(t => /^主城 \d+ 级 · 可领/.test(t)))} · 服务端现读可领 ${liveM2.claimableCount} 级`)
ok('M2a 植入生效（汇总行画出来了，且屏上那个数确实与权威读数不等）',
  summaryDrawn && mismatch2.length > 0,
  mismatch2.join('；') || '汇总行根本没画出来，等于这一相没跑')
bad('M2b 同源判据在植入态翻红', mismatch2.length > 0, '屏上那个数与权威读数一致，判据没抓手')

// ============ 相 R：撤桩复绿 ============
await context.unroute('**/level-reward/list')
await openByDeepLink()
const labelsR = await dumpLabels()
const rowsR = await dumpRows()
const liveR = (await getPlayer(playerId, '/level-reward/list')).data
readings.push(`R 撤桩后画出 ${rowsR.length} 行 · 裸 id 命中 ${bareIdHits(labelsR).length} · 汇总偏差 ${summaryMismatch(labelsR, liveR).length}`)
ok('R1 还原后裸 id 判据复绿', bareIdHits(labelsR).length === 0, JSON.stringify(bareIdHits(labelsR).slice(0, 2)))
ok('R2 还原后同源判据复绿', summaryMismatch(labelsR, liveR).length === 0, summaryMismatch(labelsR, liveR).join('；'))
await page.screenshot({ path: path.join(OUT, 'r-restored.png') })

// 页面不重载的尺寸变化：内容与导航都需重排，并保住同一等级所在页。
const beforeResize = await dumpRows()
const firstLevelBeforeResize = liveR.rows.find(row => row.name === beforeResize[0]?.name)?.level
await page.setViewportSize({ width: 1440, height: 360 })
await page.waitForTimeout(400)
const resizedPaging = await dumpPaging()
const resizedRows = await dumpRows()
ok('实时resize后同一等级仍在可见页且布局不压栏',
  resizedRows.some(row => liveR.rows.find(candidate => candidate.name === row.name)?.level === firstLevelBeforeResize)
    && pagingLayoutErrors(resizedPaging).length === 0, JSON.stringify(resizedPaging))
await page.setViewportSize({ width: 1440, height: 900 })
await page.waitForTimeout(400)
ok('实时resize回普通窗口仍可翻页且范围与实画同源',
  pageRangeErrors(await dumpPaging(), await dumpRows(), liveR).length === 0
    && pagingLayoutErrors(await dumpPaging()).length === 0, JSON.stringify(await dumpPaging()))

// 只剩最后一份可领取时，让玩家真点击领取；熄灭必须由写后刷新权威树完成。
const remaining = liveR.rows.filter(row => row.claimable)
ok('红点末项验收至少有一份真实可领奖励', remaining.length > 0, remaining.length)
for (const row of remaining.slice(0, -1)) {
  const response = await fetch(`${BACKEND}/level-reward/claim`, { method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Player-Id': playerId },
    body: JSON.stringify({ requestId: `${uniq}-notification-${row.level}`, level: row.level }) })
  const claimed = await response.json()
  ok(`红点前置领取第${row.level}级`, claimed.code === 0, JSON.stringify(claimed))
}
await openByDeepLink()
const lastRow = (await dumpRows()).find(row => row.name === remaining.at(-1)?.name)
if (lastRow?.pos !== null && lastRow?.pos !== undefined) {
  await page.mouse.click(lastRow.pos.x, lastRow.pos.y)
  await page.waitForTimeout(1200)
}
const notificationAfter = (await getPlayer(playerId, '/social/reddot')).data
const finalDots = await navDots()
ok('真实点击最后一份后权威叶子与父链同时熄灭',
  leafOf(notificationAfter.nodes, 'levelReward/claimable')?.lit === false
    && leafOf(notificationAfter.nodes, 'levelReward')?.lit === false, JSON.stringify(notificationAfter))
ok('末项领取后不重载页面等级入口红点熄灭', finalDots.level === false, JSON.stringify(finalDots))
await page.screenshot({ path: path.join(OUT, 'notification-cleared.png') })

console.log('[level-reward] 读数：')
for (const line of readings) console.log(`  - ${line}`)
if (skip.length > 0) {
  for (const line of skip) console.log(`  ! ${line}`)
}
await browser.close()
await preview.close()

if (errors.length > 0) {
  console.error(`[level-reward] 页面报错 ${errors.length} 条：${errors.slice(0, 3).join(' | ')}`)
  fail.push('页面运行时报错')
}
if (fail.length > 0) {
  console.error(`[level-reward] 红 ${fail.length} 条：`)
  for (const f of fail) console.error(`  × ${f}`)
  console.error('[level-reward] SKIP 未执行的相数：' + skip.length + '（SKIP 不算绿）')
  process.exit(1)
}
console.log(`[level-reward] 绿 ${readings.length} 条 · 红 0 条 · SKIP ${skip.length} 条`)
if (skip.length > 0) {
  console.error('[level-reward] 有相没跑到（见上面 SKIP），不算完整验收')
  process.exit(2)
}
process.exit(0)
