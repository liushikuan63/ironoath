/**
 * 职责：招募（抽卡）面板（抽卡入口 S4）的**运行时**验收 —— 真构建产物 + 真引擎里，
 *       从导航条那颗「招募」点进去，走完"看池 → 选池 → 抽一次 → 看结果 → 打开概率公示 → 关闭"一整圈。
 * 依赖：node、playwright、一台能登录的后端（默认 8080）、已构建的 web-mobile 产物。
 *
 * 用法：GACHA_ARTIFACT_ROOT=/d/tmp/tech-wt/client/build/web-mobile node tools/verify-gacha-runtime.mjs
 * 必填：GACHA_BACKEND=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 GACHA_PROBE_PORT（默认 8193，同机并发时换一个）
 *
 * <p><b>为什么这几条路径要经夹具替换</b>：dev 新号 GOLD 只有初始那点、限定池要 `item_chest_hero`
 * （由关卡与礼包产出），而"抽满过的号"在 dev 上根本不存在（没有回档口，也不许造数据）。
 * 于是"抽满的那行灰着""十抽差 7000""抽完已抽次数跟着变"这三态在真数据下够不到。
 * 替换只发生在探针这一侧：夹具按契约必填字段构造，经客户端**自己的读路径**进去，
 * 于是被验的是"入口 → 编排 → 渲染 → 写请求 → 回读"这条真链路。写请求 `/gacha/draw` 打到桩上
 * （桩自己推进池状态与余额），**不碰服务端任何存档**。期望值全由夹具常量独立算出，不从页面抄。
 *
 * <p>判据（12 组）：① 导航那一格点得动；② 三个池都画出来（抽满的也在）；③ 差多少、还剩几次写在行上；
 * ④ 灰键点了不发请求；⑤ 单抽发出的是那一条（poolId 与 count 对得上）；⑥ 抽完重拉卡池；
 * ⑦ 结果行画出来（新武将 / 转碎片 / 保底）；⑧ 道具计价的池余额取背包且跟着扣；
 * ⑨ 概率公示：一次读、四档百分比、**原文呈现**、关得掉；⑩ 行不越界、结果不被导航条压住；
 * ⑪ 屏上不出现 poolId / itemId / 资源枚举；⑫ 零页面错误。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const OUT = process.env.GACHA_VERIFY_OUT ?? path.resolve(process.cwd(), 'client/build/gacha-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.GACHA_PROBE_PORT ?? 8193)
// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.GACHA_BACKEND ?? (() => {
  console.error('[gacha] 缺 GACHA_BACKEND：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const ARTIFACT = process.env.GACHA_ARTIFACT_ROOT ?? 'client/build/web-mobile'

const STANDARD = 'gacha_pool_standard'
const NEWBIE = 'gacha_pool_newbie'
const LIMITED = 'gacha_pool_limited'
const CHEST = 'item_chest_hero'
const STANDARD_COST = 1200
const LIMITED_COST = 1

let pass = 0
let fail = 0
const ok = (msg) => { pass += 1; console.log(`  PASS  ${msg}`) }
const bad = (msg) => { fail += 1; console.log(`  FAIL  ${msg}`) }
const check = (msg, actual, expected) => {
  if (actual === expected) {
    ok(`${msg}（${JSON.stringify(actual)}）`)
  } else {
    bad(`${msg}：期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`)
  }
}
const checkTrue = (msg, actual) => check(msg, Boolean(actual), true)

const fixture = {
  gold: 5000,
  chests: 3,
  /** 新手池限抽 1 次，这个号**已经抽过** —— 屏上必须写"已抽满"，不能留一个能点的键 */
  draws: { [NEWBIE]: 1, [STANDARD]: 0, [LIMITED]: 0 },
  drawCalls: [],
  poolReads: 0,
  probReads: 0,
}

const poolsResp = () => ({
  serverNow: Date.now(),
  pools: [
    {
      poolId: NEWBIE, name: '新手招募池', poolType: 'NEWBIE',
      costItemId: null, costItemName: null, costCount: 150,
      lifetimeLimit: 1, lifetimeDraws: fixture.draws[NEWBIE], costResource: 'GOLD',
    },
    {
      poolId: STANDARD, name: '标准招募池', poolType: 'STANDARD',
      costItemId: null, costItemName: null, costCount: STANDARD_COST,
      lifetimeLimit: 0, lifetimeDraws: fixture.draws[STANDARD], costResource: 'GOLD',
    },
    {
      poolId: LIMITED, name: '限定·李劲池', poolType: 'LIMITED',
      costItemId: CHEST, costItemName: '招募宝箱', costCount: LIMITED_COST,
      lifetimeLimit: 0, lifetimeDraws: fixture.draws[LIMITED], costResource: null,
    },
  ],
})

const resourceResp = () => ({
  serverNow: Date.now(),
  resources: [
    { type: 'GOLD', current: fixture.gold, cap: 100000, protectedAmount: 0,
      perHour: 120, lastSettle: Date.now(), full: false, breakdown: [] },
    { type: 'WOOD', current: 8000, cap: 20000, protectedAmount: 0,
      perHour: 500, lastSettle: Date.now(), full: false, breakdown: [] },
  ],
})

const bagResp = () => ({
  items: fixture.chests > 0
    ? [{ itemId: CHEST, name: '招募宝箱', type: 'MATERIAL', rarity: 'R',
      obtainFrom: '关卡', count: fixture.chests, stackMax: 99, sortKey: 3,
      effectKind: 'OPEN_CHEST', effectTarget: null }] : [],
  capacityUsed: 1, capacityMax: 200,
})

/** 公示夹具：四档之和恰为 10000（少一个单位 buildDisclosure 就抛，那正是判据要的）。 */
const probResp = (poolId) => ({
  poolId, name: poolsResp().pools.find((p) => p.poolId === poolId).name, poolType: 'STANDARD',
  items: [
    { heroId: 'hero_probe_liji', name: '李劲', rarity: 'SSR', rateFixed: 1800, isUp: true },
    { heroId: 'hero_probe_chengyuan', name: '程远', rarity: 'SR', rateFixed: 1200, isUp: false },
  ],
  tierRates: [
    { rarity: 'SSR', rateFixed: 200 }, { rarity: 'SR', rateFixed: 1200 },
    { rarity: 'R', rateFixed: 4800 }, { rarity: 'N', rateFixed: 3800 },
  ],
  pityRule: { ssrPity: 60, srPity: 10, ssrUpGuarantee: 0 },
  disclosureText: '抽取获得随机武将。SSR 综合概率 2%（含保底），每 60 抽未出 SSR 必出。'
    + '重复武将自动转化为该武将碎片。概率以本次公示为准，公示与实际一致。',
  costItemId: null, costItemName: null, costCount: STANDARD_COST,
  lifetimeLimit: 0, serverNow: Date.now(), costResource: 'GOLD',
})

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
try {
  await fetch(`${BACKEND}/time/sync`, { method: 'POST' })
} catch (error) {
  console.error(`起跑前直连后端失败：${error.message}\n后端 ${BACKEND} 起来了吗？`)
  process.exit(2)
}
console.log(`=== 招募面板运行时验收：产物经 ${preview.origin}，后端 ${BACKEND}`
  + `（/gacha/pools、/gacha/draw、/gacha/probability、/resource/detail、/bag/list 经夹具替换）===`)

const NODE_PATH = 'window.cc.director.getScene().getChildByName("Canvas").getChildByName("Game")'

/** 读一个子树里的 Label（含盒高）、行几何与底部按钮；未激活的层整棵跳过。 */
const SNAPSHOT = (rootName) => `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === ${JSON.stringify(rootName)})
  if (!root) return null
  const labels = []
  const rows = []
  let card = null
  const walk = (n) => {
    if (!n.active) return
    const label = n.getComponent('cc.Label')
    if (label && label.string) {
      const box = n.getComponent('cc.UITransform')
      labels.push({ text: label.string, x: n.getPosition().x, y: n.getPosition().y,
        h: box ? box.contentSize.height : -1 })
    }
    const t = n.getComponent('cc.UITransform')
    const plate = { name: n.name, x: n.getPosition().x, y: n.getPosition().y,
      h: t ? t.contentSize.height : -1, w: t ? t.contentSize.width : -1 }
    if (/^pool-|^TierRow_/.test(n.name)) {
      rows.push(plate)
    }
    if (n.name === 'drawOnce' || n.name === 'drawTen' || n.name === 'probability'
        || n.name === 'close') {
      rows.push(plate)
    }
    if (n.name === 'card') card = { h: t ? t.contentSize.height : -1 }
    for (const child of n.children) walk(child)
  }
  walk(root)
  return { active: root.active, labels, rows, card }
})()`

/** 从 Canvas 整棵树里按节点名找一个并按下（导航格在 PanelNav 那一层，不在 Game 之下）。 */
const TAP = (name) => `(() => {
  const canvas = window.cc.director.getScene().getChildByName("Canvas")
  let found = null
  const walk = (n) => {
    if (found !== null) return
    if (n.name === ${JSON.stringify(name)}) { found = n; return }
    for (const child of n.children) walk(child)
  }
  walk(canvas)
  if (found === null) return 'missing'
  found.emit('touch-start')
  return 'tapped'
})()`

const has = (snapshot, text) => snapshot !== null && snapshot.labels.some((l) => l.text.includes(text))
const textOf = (snapshot) => snapshot === null ? '' : snapshot.labels.map((l) => l.text).join('\n')

/** 一行的两行字是否**整盒**落在自己那块底板里（合成那一格量到过：中心在板内而字被切）。 */
const linesInsidePlate = (snapshot, rowName, texts) => {
  const row = snapshot.rows.find((r) => r.name === rowName)
  if (row === undefined) return false
  return texts.every((text) => {
    const label = snapshot.labels.find((l) => l.text.includes(text))
    return label !== undefined && label.h > 0
      && Math.abs(label.y - row.y) + label.h / 2 <= row.h / 2 + 0.5
  })
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const deviceId = `gacha-runtime-${Date.now()}`
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, deviceId)

const cors = (request) => ({
  'access-control-allow-origin': request.headers()['origin'] ?? '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
})
const stub = (pathName, make) => context.route(`**${pathName}*`, async (route) => {
  // 尾部带 `*`：`/gacha/probability?poolId=…` 这类带 query 的 GET 用精确尾巴匹配不到，
  // 症状是"点了没反应"而没有任何错误 —— 与端点坏掉长得一模一样
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  if (pathName === '/gacha/pools') {
    fixture.poolReads += 1
  } else if (pathName === '/gacha/probability') {
    fixture.probReads += 1
  } else if (pathName === '/gacha/draw') {
    const body = JSON.parse(request.postData() ?? '{}')
    fixture.drawCalls.push(body)
    // 桩自己推进：扣对应的钱、该池已抽次数 +count（服务端 doDraw 做的就是这两件事）
    if (body.poolId === LIMITED) {
      fixture.chests = Math.max(0, fixture.chests - LIMITED_COST * body.count)
    } else {
      fixture.gold = Math.max(0, fixture.gold - (body.poolId === NEWBIE ? 150 : STANDARD_COST) * body.count)
    }
    fixture.draws[body.poolId] = (fixture.draws[body.poolId] ?? 0) + body.count
  }
  await route.fulfill({
    status: 200,
    headers: { ...cors(request), 'content-type': 'application/json' },
    body: JSON.stringify({ code: 0, msg: '成功', data: make(request.url()), serverNow: Date.now() }),
  })
})
await stub('/gacha/pools', poolsResp)
await stub('/resource/detail', resourceResp)
await stub('/bag/list', bagResp)
await stub('/gacha/probability', (url) => probResp(new URL(url).searchParams.get('poolId') ?? STANDARD))
await stub('/gacha/draw', (url) => {
  const poolId = fixture.drawCalls.at(-1)?.poolId ?? STANDARD
  const single = poolId === LIMITED ? '程远' : '李劲'
  return {
    results: [
      { heroId: 'hero_probe_liji', name: single, rarity: 'SSR', isNew: true, isPity: true, fragments: 0 },
      { heroId: 'hero_probe_other', name: '周柯', rarity: 'SR', isNew: false, isPity: false, fragments: 20 },
    ],
    ssrPityCounter: 0, srPityCounter: 4, fragmentsAwarded: 20,
    costItemId: poolId === LIMITED ? CHEST : null,
    costCount: poolId === LIMITED ? LIMITED_COST : STANDARD_COST,
    seed: 7, serverNow: Date.now(), costResource: poolId === LIMITED ? null : 'GOLD',
  }
})

const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})

// 从内城进招募：真人走的就是导航条那一格，所以这里也点它（不是 ?panel= 的调试出口）
const url = new URL(`${preview.origin}/`)
await page.goto(url.toString(), { waitUntil: 'networkidle' })
// 自检：产物里那两处写死的后端地址有没有真的被改写成本轮要打的那棵。
// 漏了这一句，传错变量名就是"打到另一台机器上读数"，红得像是产品缺陷（台账 #371）。
preview.assertRewritten()
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await page.waitForTimeout(2500)

const city = await page.evaluate(SNAPSHOT('city'))
checkTrue('开局落在内城（导航条可点的前置）', city !== null && city.active)
const drawsAtStart = fixture.drawCalls.length
check('点导航条的「招募」', await page.evaluate(TAP('Nav-gacha')), 'tapped')
await page.waitForTimeout(1200)

let panel = await page.evaluate(SNAPSHOT('gacha'))
checkTrue('招募面板挂上了且已激活', panel !== null && panel.active)
check('三个池都画出来（抽满那一行也在，不藏）', poolRows(panel).length, 3)
checkTrue('池名是服务端下发的中文名', has(panel, '新手招募池')
  && has(panel, '标准招募池') && has(panel, '限定·李劲池'))
checkTrue('抽满那行写「已抽满」', has(panel, '已抽满'))
checkTrue('默认选中第一行 ⇒ 两个键都灰（选中的就是抽满那个）',
  has(panel, '这个号在该池已抽满'))
checkTrue('屏上不出现 poolId / itemId / 资源枚举（#268 同族）',
  !/gacha_pool_|item_chest_hero|GOLD/.test(textOf(panel)))
checkTrue('行上两行字都落在自己那块底板里',
  linesInsidePlate(panel, `pool-${NEWBIE}`, ['新手招募池', '已抽满'])
  && linesInsidePlate(panel, `pool-${STANDARD}`, ['标准招募池', '不限次']))
/**
 * 同一屏里所有"板"（池行 / 档位行 / 按钮）的两两**矩形**相交。
 * 判据来自截图：按钮那一行原本只留了 28px 间距，而两块板合计需要 ~45px，
 * 于是按钮的字压在第三行池的第二行字上 —— 行与行之间的判据看不见，因为按钮不是"行"。
 * 三个按钮在同一行、横向挨着，所以必须按矩形判，只量纵向会把它们全判成压叠。
 */
const plateCollisions = (snapshot) => {
  const list = snapshot === null ? [] : snapshot.rows
  const hits = []
  for (let i = 0; i < list.length; i++) {
    for (let j = i + 1; j < list.length; j++) {
      const a = list[i]
      const b = list[j]
      if (a.h <= 0 || b.h <= 0 || a.w <= 0 || b.w <= 0) continue
      const vertical = Math.abs(a.y - b.y) < (a.h + b.h) / 2 - 0.5
      const horizontal = Math.abs(a.x - b.x) < (a.w + b.w) / 2 - 0.5
      if (vertical && horizontal) hits.push(`${a.name}×${b.name}`)
    }
  }
  return hits
}

function poolRows(snapshot) {
  return snapshot.rows.filter((r) => r.name.startsWith('pool-'))
}

const ys = panel.rows.filter((r) => r.name.startsWith('pool-')).map((r) => r.y)
check('三行落点互不重叠', new Set(ys).size, 3)
check('池行与三个按钮彼此不压叠（截图抓到过按钮压在第三行上）',
  plateCollisions(panel).join(','), '')
checkTrue('读到了按钮那三块板（读不到上面的判据就是失效）',
  panel.rows.some((r) => r.name === 'drawOnce') && panel.rows.some((r) => r.name === 'probability'))
await page.screenshot({ path: path.join(OUT, 'recruit-open.png') })

const poolsReads = fixture.poolReads
check('点灰着的「抽一次」键（选中的是抽满的池）', await page.evaluate(TAP('drawOnce')), 'tapped')
await page.waitForTimeout(500)
check('抽满时点了也不发请求', fixture.drawCalls.length, drawsAtStart)
check('也不重拉卡池（没发生的事不该改状态）', fixture.poolReads, poolsReads)

check('选标准池那一行', await page.evaluate(TAP(`pool-${STANDARD}`)), 'tapped')
await page.waitForTimeout(600)
panel = await page.evaluate(SNAPSHOT('gacha'))
checkTrue('余额那一行报的是选中池那一档（金币 5000）', has(panel, '5000 金币'))
checkTrue('单抽键写着价格', has(panel, `抽一次 · ${STANDARD_COST} 金币`))
checkTrue('十抽键灰着并写明差多少', has(panel, `还差 ${STANDARD_COST * 10 - fixture.gold} 金币`))
check('点十抽（钱不够）不发请求', await page.evaluate(TAP('drawTen')), 'tapped')
await page.waitForTimeout(400)
check('仍然一条都没发', fixture.drawCalls.length, drawsAtStart)

check('点单抽', await page.evaluate(TAP('drawOnce')), 'tapped')
await page.waitForTimeout(1500)
check('真的发出了一次 /gacha/draw', fixture.drawCalls.length, drawsAtStart + 1)
const sent = fixture.drawCalls.at(-1)
check('带的是选中的那个池', sent?.poolId, STANDARD)
check('count 是 1', sent?.count, 1)
checkTrue('requestId 合法（长度 [8,64]，短了会被端点回 1003）',
  typeof sent?.requestId === 'string' && sent.requestId.length >= 8 && sent.requestId.length <= 64)
check('抽完重拉卡池（已抽次数变了）', fixture.poolReads, poolsReads + 1)
panel = await page.evaluate(SNAPSHOT('gacha'))
checkTrue('结果行画出来（新武将 + 保底 + 转碎片各一条）',
  has(panel, '李劲 · 新武将（保底）') && has(panel, '周柯 · 转 20 碎片'))
checkTrue('余额跟着扣（3800 金币）', has(panel, `${fixture.gold} 金币`) && fixture.gold === 5000 - STANDARD_COST)
checkTrue('结果那一块没被导航条压住（最后一行仍在底栏之上）',
  panel.labels.filter((l) => l.text.includes('·')).every((l) => l.y > -450))
await page.screenshot({ path: path.join(OUT, 'recruit-drawn.png') })

check('选限定池（按道具计价）', await page.evaluate(TAP(`pool-${LIMITED}`)), 'tapped')
await page.waitForTimeout(500)
panel = await page.evaluate(SNAPSHOT('gacha'))
checkTrue('消耗那一行写的是道具中文名，不是 item_ 行 id', has(panel, '1 招募宝箱'))
checkTrue('余额取背包那一行（3 招募宝箱）', has(panel, '3 招募宝箱'))
check('点单抽（宝箱计价）', await page.evaluate(TAP('drawOnce')), 'tapped')
await page.waitForTimeout(1500)
check('发出的是限定池那一条', fixture.drawCalls.at(-1)?.poolId, LIMITED)
panel = await page.evaluate(SNAPSHOT('gacha'))
checkTrue('背包跟着扣（剩 2 个宝箱）', has(panel, '2 招募宝箱'))

const probReads = fixture.probReads
check('点「概率公示」', await page.evaluate(TAP('probability')), 'tapped')
await page.waitForTimeout(1200)
check('读了一次公示', fixture.probReads, probReads + 1)
const disclosure = await page.evaluate(SNAPSHOT('gachaDisclosure'))
checkTrue('公示浮层挂上且已激活', disclosure !== null && disclosure.active)
checkTrue('四档百分比按定点整数格式化（2% / 12% / 48% / 38%）',
  has(disclosure, '2%') && has(disclosure, '12%')
  && has(disclosure, '48%') && has(disclosure, '38%'))
checkTrue('公示**原文**在屏上（B06 §6 不得删减折叠）',
  has(disclosure, '重复武将自动转化为该武将碎片'))
checkTrue('公示屏上也不出现行 id 与枚举',
  !/gacha_pool_|item_chest_hero|GOLD|hero_probe_/.test(textOf(disclosure)))
check('四行档位板与「关闭」键彼此不压叠', plateCollisions(disclosure).join(','), '')
// 截图抓到过：footer 那三行按 26 算盒子，而 Label 默认按 1.7 倍行高排（18 号 ≈ 31），
// 三行字比盒子高 15px，第一行"保底"就压进了 N 那一行的板下沿
const lastTier = disclosure.rows.filter((r) => r.name.startsWith('TierRow_')).at(-1)
const footerLine = disclosure.labels.find((l) => l.text.includes('保底：'))
checkTrue('「保底」那一行在最后一行档位板之下（不被压住）',
  footerLine !== undefined && lastTier !== undefined
    && footerLine.y + footerLine.h / 2 < lastTier.y - lastTier.h / 2)
const original = disclosure.labels.find((l) => l.text.includes('重复武将自动转化'))
checkTrue('公示原文那一块在 footer 之下（长文案不被裁，也不与上面重叠）',
  original !== undefined && footerLine !== undefined
    && original.y + original.h / 2 < footerLine.y - footerLine.h / 2)
await page.screenshot({ path: path.join(OUT, 'recruit-disclosure.png') })
check('点「关闭」', await page.evaluate(TAP('close')), 'tapped')
await page.waitForTimeout(400)
check('公示浮层收起（不是留在屏幕上挡招募页）',
  (await page.evaluate(SNAPSHOT('gachaDisclosure')))?.active, false)
check('关公示不会顺手再抽一次', fixture.drawCalls.length, drawsAtStart + 2)

check('零页面错误', errors.length, 0)
if (errors.length > 0) {
  console.log(errors.slice(0, 6).join('\n'))
}

await browser.close()
console.log(`\n=== ${pass} PASS / ${fail} FAIL，截图落在 ${OUT} ===`)
process.exit(fail === 0 ? 0 : 1)
