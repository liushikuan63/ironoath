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
 *   A 深链进面板 → 40 行真画出来、三态文案与 HTTP 现读一致、屏上不出现裸 id
 *   B 点「领取」→ 屏上那一行真翻成已领（点了不换屏 = 假绿），并让服务端复验
 *   C 走玩家路径（导航条 →「更多」抽屉 →「等级」）重新进入 → 已领与待领同时可辨 + 截图
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
