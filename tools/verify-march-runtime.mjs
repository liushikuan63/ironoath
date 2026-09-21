/**
 * 职责：出征编成弹层的**运行时**验收（B25-S1 的 S1d）—— 在真构建产物 + 真服务端上确认
 * 「弹层真的存在、默认是收起的、搜索面板正常画出来」。
 * 依赖：node、playwright、**已启动的 dev 服务端**、已构建的 `client/build/web-mobile`。
 * 用法：node tools/verify-march-runtime.mjs
 * 必填：MARCH_BACKEND=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 MARCH_PROBE_PORT（默认 8193，同机并发时换一个）
 *
 * <p><b>本探针不验「点一行 → 编成 → 出征成功」那条完整链路</b>，这是刻意的：
 * 目标搜索的候选来自匹配池，而 dev 服上**没有任何对手**（空服没有真人，Bot 也没进池），
 * 新号自己又有新手护盾、不进候选池 —— 本探针实测 `searchTargets` 回 `targets: []`。
 * 而"给探针造一个对手"要么开作弊端点、要么改服务端配置，两条都是这个仓库明文禁止的。
 * 所以那条链路的证据分两段：**编排与纯逻辑**在 `client/tests/` 里逐条断言（含"只带选中的行"
 * "被拒不提收起"），**画没画出来**由本探针管。真机/真服上有人之后再人工复验一次。
 *
 * <p>端口与后端可用环境变量换（MARCH_PROBE_PORT / MARCH_BACKEND）：两个会话同时跑量具时，
 * 撞端口会表现为"读到别人的产物"，而那看起来像产物坏了。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from 'file:///D:/Java/GitHub/tieshi/tools/lib/preview-server.mjs'

const OUT = process.env.MARCH_VERIFY_OUT ?? path.resolve(process.cwd(), 'client/build/march-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.MARCH_PROBE_PORT ?? 8193)
// 必须显式给后端：静默回落到 8080 等于"打到另一台机器上读数"。
// 2026-09-21 实测：把变量名写成 `BACKEND_ORIGIN` 之后，13 条集结判据全红，
// 而客户端与夹具都没有错 —— 那份 8080 的旧后端根本没有 `/rally/policy`。dev 约定 8199。
const BACKEND = process.env.MARCH_BACKEND ?? (() => {
  console.error('[march] 缺 MARCH_BACKEND：不给就退回 http://localhost:8080，'
    + '那可能不是本轮要打的后端，所有读数都是假的（dev 约定 http://localhost:8199）')
  process.exit(2)
})()

let pass = 0
let fail = 0
const ok = (msg) => { pass += 1; console.log(`  PASS  ${msg}`) }
const bad = (msg) => { fail += 1; console.log(`  FAIL  ${msg}`) }
const checkTrue = (msg, actual) => {
  if (actual === true) {
    ok(msg)
  } else {
    bad(`${msg}：实际 ${JSON.stringify(actual)}`)
  }
}
const check = (msg, actual, expected) => {
  if (actual === expected) {
    ok(`${msg}（${String(actual)}）`)
  } else {
    bad(`${msg}：期望 ${String(expected)}，实际 ${String(actual)}`)
  }
}

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 出征编成弹层运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `march-runtime-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})

const cors = (request) => ({
  'access-control-allow-origin': request.headers()['origin'] ?? '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
})
const reply = async (route, data) => route.fulfill({
  status: 200,
  headers: { ...cors(route.request()), 'content-type': 'application/json' },
  body: JSON.stringify({ code: 0, msg: '成功', data, serverNow: Date.now() }),
})

// ---------- 相位 C 的军队夹具（B26 S14）——必须挂在 goto 之前 ----------
// 军队读口在登录响应之后立刻发出：桩挂晚了等于没挂，编成面板吃的是 dev 新号那份
// 「五口兵全不可出征」，勾不出兵也发不出集结（第一次跑就是这么红的）。
/** 相位 D 会就地把第一口改成"正在训练"，所以夹具放在回调外面（回调里每次读都要重建就没法改状态） */
const ARMY_FIXTURE = {
    units: [
      { unitId: 'unit_infantry_t1', name: '重步', type: 'INFANTRY', tier: 1, count: 500,
        wounded: 0, training: 0, finishAt: null, remainingSeconds: null, unlocked: true,
        unlockHint: null, trainTimeSec: 10,
        // 军队面板那一行会把 trainCost 拼成文案（缺了就是一条 undefined.map，整页崩）
        trainCost: [{ type: 'FOOD', amount: 20 }, { type: 'GOLD', amount: 5 }] },
      { unitId: 'unit_archer_t2', name: '长弓', type: 'ARCHER', tier: 2, count: 200,
        wounded: 0, training: 0, finishAt: null, remainingSeconds: null, unlocked: true,
        unlockHint: null, trainTimeSec: 12,
        trainCost: [{ type: 'FOOD', amount: 30 }, { type: 'GOLD', amount: 8 }] },
    ],
    troopCap: 1000, troopsInUse: 0, trainingInUse: 0, queueSlots: 0, queueSlotsMax: 2,
    hospital: { capacity: 0, used: 0, treating: false, treatFinishAt: null,
      treatRemainingSeconds: 0, treatSecondsPerWounded: 0, treatCostRatio: 0 },
    autoTrain: { enabled: false, unitId: 'none', batchCount: 1, batchBudget: 0, targetCount: 0,
      stopReason: null },
    serverNow: Date.now(),
}
await context.route('**/army/list*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, ARMY_FIXTURE)
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'targets')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
// 自检：产物里那两处写死的 8080 有没有被改写成本轮要打的后端。漏了这一句，
// 传错变量名时所有读数都会打到另一棵旧后端上，跑出一片看着像产品缺陷的假红（2026-09-21 实测 13 条）。
preview.assertRewritten()
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await page.waitForTimeout(1800)

// 弹层挂在 Game 节点下（不是面板，走的是自定义类而不是 panel() 那张按名字查表的通道）
const probe = await page.evaluate(`(() => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  if (!game) return { gameFound: false }
  const overlay = game.getChildByName('MarchCompose')
  const search = game.getChildByName('targets')
  return {
    gameFound: true,
    overlayFound: overlay !== null,
    overlayActive: overlay === null ? null : overlay.active,
    searchActive: search === null ? null : search.active,
  }
})()`)

check('场景装配出来了', probe.gameFound, true)
check('编成弹层节点存在（MarchCompose）', probe.overlayFound, true)
check('弹层默认是收起的（没点目标就不该弹）', probe.overlayActive, false)
check('搜索面板是打开的（探针进的这一页）', probe.searchActive, true)

// ---------- 相位 A0：一次搜索都还没发出去时，行区那一格必须空着 ----------
// 这一相是三相判据里的第一相：少了它，"永远印着那句话"与"只在零结果时印"在读数上同形，
// 后面那两条"没有这句话"就抓不到任何缺陷。面板不会自动搜索（搜索只由 SearchButton 触发），
// 所以此刻真的还没搜过。
// 反空转前置：先证明 Overflow 那颗控件在树里、视图组件真的装配上了 ——
// 否则"字是空串"可能只是因为压根没那个节点（#347 的整块没画就是这个形状）。
const BEFORE = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('targets')
  const notice = panel?.getChildByName('Overflow')
  const view2 = panel?.getComponent('TargetSearchView')
  return {
    panelFound: panel !== null && panel !== undefined,
    viewFound: view2 !== null && view2 !== undefined,
    noticeFound: notice !== null && notice !== undefined,
    noticeText: notice?.getComponent('cc.Label')?.string ?? null,
    searched: view2 ? view2.searched === true : null,
    rowsLen: view2 && view2.rows ? view2.rows.length : null,
    radius: view2 ? view2.radius ?? null : undefined,
    downActive: panel?.getChildByName('RadiusDown')?.active ?? null,
    upActive: panel?.getChildByName('RadiusUp')?.active ?? null,
  }
})()`
const before = await page.evaluate(BEFORE)
checkTrue('搜索前：面板、视图组件、Overflow 那一格都在（否则"空串"是假绿）',
  before?.panelFound === true && before?.viewFound === true && before?.noticeFound === true)
checkTrue('搜索前：那一格还没有话（控件在、字确实是空的）', before?.noticeText === '')
check('搜索前：rows 是空数组（所以"行数为零"本身不能当"搜过"的证据）', before?.rowsLen, 0)
check('搜索前：searched 仍是 false', before?.searched, false)
check('搜索前：半径还没有值（上下界只在响应里，客户端不猜）', before?.radius, null)
check('搜索前：两颗 ± 键收着（还不知道范围的两颗键比不画更误导）', before?.downActive, false)
check('搜索前：「半径 +」同样收着', before?.upActive, false)

// ---------- 夹具相：dev 服上没有对手，所以把搜索结果换成一条真形状的回包 ----------
// 替换的是**读接口**（网络层响应），视图与被测代码一行没换：这一相要问的正是
// 「真数据到位后，TargetSearchView 画不画得出行」。
/** 夹具回包命中次数：零行那一相到底是"夹具回了空列表"还是"夹具没命中"，靠这个数分。 */
const fixtureHits = { search: 0 }
const fixture = { count: 1 }
/** 半径上下界与起点：夹具故意用 1/48/128 三个互不相同的数，界面写错来源立刻能对出来。 */
const RADIUS_FIXTURE = { min: 1, default: 48, max: 128 }
/** 客户端每次搜索真正发出去的 radius（null = 交给服务端默认）。"屏上写的数==发出去的数"靠它比对。 */
const sentRadii = []
await context.route('**/world/searchTargets*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  fixtureHits.search += 1
  sentRadii.push(JSON.parse(route.request().postData() ?? '{}').radius ?? null)
  await reply(route, {
    targets: Array.from({ length: fixture.count }, (_, i) => ({
      id: `fixture-target-${i + 1}`, name: `测试城·${i + 1}`, coord: { x: 100 + i, y: 77 },
      matchPower: 12_000, powerRatio: 12_000, distanceBand: 'NEAR',
      resourceHint: 'NORMAL', isShielded: false, tyrannyLevel: null,
    })),
    selfMatchPower: 10_000, bandLower: 8_000, bandUpper: 15_000,
    radiusMin: RADIUS_FIXTURE.min, radiusDefault: RADIUS_FIXTURE.default,
    radiusMax: RADIUS_FIXTURE.max,
    serverNow: Date.now(),
  })
})

// ---------- 相位 C 的前置夹具：集结政策（B26 S14）----------
// 换的是**读接口**（网络层响应），视图与编排一行没换：dev 新号没有联盟，
// 真政策会是「你还没有联盟」那句 ⇒ 永远切不到联盟层，这一相要问的是切过去之后画不画得出来。
const POLICY_FIXTURE = {
  squad: { minMembers: 2, maxMembers: 5, minPrepareMinutes: 10, maxPrepareMinutes: 30,
    defaultPrepareMinutes: 30, canStart: true, reason: null },
  alliance: { minMembers: 2, maxMembers: 12, minPrepareMinutes: 10, maxPrepareMinutes: 30,
    defaultPrepareMinutes: 30, canStart: true, reason: null },
  serverNow: Date.now(),
}
await context.route('**/rally/policy*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, POLICY_FIXTURE)
})
/** 发出去的联盟集结请求体（写口用夹具回执：真建集结由 RallyEndpointTest 那一头盯）。 */
const sentRallies = []
await context.route('**/rally/alliance*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  sentRallies.push(JSON.parse(route.request().postData() ?? '{}'))
  await reply(route, { rally: {
    id: 'fixture-rally-alliance', leaderId: 'me', allianceId: 'AL_FIX', scope: 'ALLIANCE',
    targetType: 'PLAYER_CITY', targetCoord: { x: 100, y: 77 }, targetName: '测试城·1',
    state: 'PREPARING', departAt: Date.now() + 600000, prepareMinutes: 25,
    maxMembers: 12, memberCount: 1, troops: 10, myPlayerId: 'me',
  }, serverNow: Date.now() })
})

/** 一次快照同时读「节点树」与「组件自己的账」——只看树分不出没画过还是画过又收回 */
const SNAP = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const node = game?.getChildByName('targets')
  if (!node) return null
  const view = node.getComponent('TargetSearchView')
  const pool = view ? view.rowPool : null
  return {
    childNames: node.children.map(c => c.name),
    rowNodes: node.children.filter(c => c.name === 'TargetRow').length,
    header: node.getChildByName('Header')?.getComponent('cc.Label')?.string ?? '',
    viewFound: view !== null && view !== undefined,
    rowPoolNull: view ? view.rowPool === null : null,
    responseNull: view ? view.response === null : null,
    pendingNull: view ? view.pending === null : null,
    rowsLen: view && view.rows ? view.rows.length : null,
    drawnLen: view && view.drawnRows ? view.drawnRows.length : null,
    poolLive: pool ? pool.liveCount : null,
    poolIdle: pool ? pool.idleCount : null,
    poolCreated: pool ? pool.createdCount : null,
  }
})()`

const tapSearch = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const button = game?.getChildByName('targets')?.getChildByName('SearchButton')
  if (!button) return false
  button.emit('touch-start')
  return true
})()`)
checkTrue('按得到搜索按钮那一颗（SearchButton 节点在）', tapSearch)
await page.waitForTimeout(300)
const early = await page.evaluate(SNAP)
await page.waitForTimeout(1700)
const late = await page.evaluate(SNAP)

console.log('  快照 300ms：', JSON.stringify(early))
console.log('  快照 2.0s ：', JSON.stringify(late))

checkTrue('夹具回包真的到了组件（rows 长度 1）', early?.rowsLen === 1)
check('300ms：树里 TargetRow 节点数', late?.rowNodes, 1)
check('2.0s：池里在用的节点数', late?.poolLive, 1)
check('2.0s：drawnRows 记账', late?.drawnLen, 1)
checkTrue('表头按夹具数据更新', (late?.header ?? '').includes('可攻击目标 1 个'))

// ---------- 相位 A1：半径的上下界与起点必须来自那份响应 ----------
// 这一相盯的是「两颗 ± 键到底动不动」和「屏上写的数是不是真发出去的那个数」。
// 在注入之前它永远停在 0（setRadiusBounds 零调用点），0 到服务端被夹成 1 格 ⇒
// 候选只剩上下左右四格 ⇒ 面板永远空着，而屏幕上没有任何一处说明半径其实是 1。
const RADIUS_SNAP = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('targets')
  const view2 = panel?.getComponent('TargetSearchView')
  return {
    radius: view2 ? view2.radius ?? null : undefined,
    band: panel?.getChildByName('Band')?.getComponent('cc.Label')?.string ?? '',
    downActive: panel?.getChildByName('RadiusDown')?.active ?? null,
    upActive: panel?.getChildByName('RadiusUp')?.active ?? null,
  }
})()`
/** 把同一颗半径键按 N 次（Cocos 的 node.emit 不看 active，所以次数就是步数）。 */
const tapRadius = (name, times) => page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const button = game?.getChildByName('targets')?.getChildByName('${name}')
  if (!button) return false
  for (let i = 0; i < ${times}; i++) button.emit('touch-start')
  return true
})()`)

const bounds = await page.evaluate(RADIUS_SNAP)
check('第一次搜索发出去的是 null（半径起点由服务端定，客户端不猜）', sentRadii[0], null)
check('响应里的默认半径变成了屏上那个数', bounds?.radius, 48)
checkTrue('那一行说明写的是同一个数（不再是「搜索半径 0 格」那种谎）',
  (bounds?.band ?? '').includes('搜索半径 48 格'))
check('上下界到位后「半径 −」回来了', bounds?.downActive, true)
check('上下界到位后「半径 +」回来了', bounds?.upActive, true)
await page.screenshot({ path: path.join(OUT, 'march-search-radius-live.png') })

checkTrue('按得到「半径 +」那一颗', await tapRadius('RadiusUp', 1))
const stepped = await page.evaluate(RADIUS_SNAP)
check('按一次 + 真的推进了一步（min=max=0 的旧版里它永远停在原地）', stepped?.radius, 61)

const retapped = await page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const button = game?.getChildByName('targets')?.getChildByName('SearchButton')
  if (!button) return false
  button.emit('touch-start')
  return true
})()`)
checkTrue('按得到「搜索」（把调好的半径发出去）', retapped)
await page.waitForTimeout(300)
const shownAfterTap = (await page.evaluate(RADIUS_SNAP)).radius
check('发出去的半径等于屏上写的那个数（旧版发 0、显示 0，这条抓不到东西）',
  sentRadii[sentRadii.length - 1], shownAfterTap)
check('而且它就是刚按出来的 61，不是又弹回默认值', sentRadii[sentRadii.length - 1], 61)

checkTrue('把「半径 +」连按 12 次', await tapRadius('RadiusUp', 12))
check('连按 + 停在下发的上界 128（越界只会让服务端截断并记日志）',
  (await page.evaluate(RADIUS_SNAP)).radius, 128)
checkTrue('把「半径 −」连按 20 次', await tapRadius('RadiusDown', 20))
check('连按 − 停在下发的地板值 1，不是 0（0 会被服务端当成「没给半径」）',
  (await page.evaluate(RADIUS_SNAP)).radius, 1)

// ---------- 相位 A2：行区的几何与分页（这一相盯的是"看得见"而不是"存在"）----------
// 相位 A 只证明树里有 TargetRow；行压不压控件条、装不下的那些翻不翻得到，
// 文字快照一个都看不见 —— 社交面板那一格就是靠量坐标才抓到两颗按钮重叠的。
const GEO = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('targets')
  if (!panel) return null
  const size = window.cc.view.getVisibleSize()
  const box = (n) => {
    const t = n.getComponent('cc.UITransform')
    return { top: n.position.y + t.height / 2, bottom: n.position.y - t.height / 2 }
  }
  const rows = panel.children.filter((c) => c.name === 'TargetRow')
  const controls = ['PrevPageButton', 'RadiusDown', 'RadiusUp', 'SearchButton', 'NextPageButton']
    .map((name) => panel.getChildByName(name)).filter((n) => n !== null && n !== undefined)
  const pagerActive = controls
    .filter((n) => n.name === 'PrevPageButton' || n.name === 'NextPageButton')
    .filter((n) => n.active === true).length
  const notice = panel.getChildByName('Overflow')
  const view2 = panel.getComponent('TargetSearchView')
  return {
    rowCount: rows.length,
    pagerActive,
    // 容量按可视高现算，探针不猜那个数：判据写成"和容量的关系"，改窗口也不会假红
    capacity: view2 ? view2.rowCapacity() : null,
    rowNames: rows.map((c) => c.children[0]?.getComponent('cc.Label')?.string ?? ''),
    topRowTop: rows.length === 0 ? null : box(rows[0]).top,
    lowestControlBottom: Math.min(...controls.map((c) => box(c).bottom)),
    lastRowBottom: rows.length === 0 ? null : box(rows[rows.length - 1]).bottom,
    navTop: -size.height / 2 + 8 + 52,
    noticeText: notice?.getComponent('cc.Label')?.string ?? '',
    noticeBottom: notice === null ? null : box(notice).bottom,
    screenBottom: -size.height / 2,
    // 反空转用：这一格控件在不在、表头有没有真画过、视图自己记的"搜过没有"
    noticeFound: notice !== null && notice !== undefined,
    header: panel.getChildByName('Header')?.getComponent('cc.Label')?.string ?? '',
    searched: view2 ? view2.searched === true : null,
  }
})()`

const tapPanelButton = (name) => page.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const button = game?.getChildByName('targets')?.getChildByName('${name}')
  if (!button) return false
  button.emit('touch-start')
  return true
})()`)

/** 换夹具内容：改条数后重按一次搜索，读接口就会回那么多条 */
const searchWith = async (count) => {
  fixture.count = count
  if (!await tapPanelButton('SearchButton')) {
    throw new Error('SearchButton 不在树里，夹具相跑不下去')
  }
  await page.waitForTimeout(600)
  return page.evaluate(GEO)
}

const geo1 = await searchWith(1)
check('只有一页（1 个目标）时两颗翻页键收掉，不留点了没反应的按钮', geo1?.pagerActive, 0)
checkTrue('行没有压住控件条（第一行上沿在按钮下沿之下）',
  geo1 !== null && geo1.topRowTop <= geo1.lowestControlBottom)
checkTrue('行没有压到底部导航条', geo1 !== null && geo1.lastRowBottom >= geo1.navTop)
checkTrue('页码那行字落在屏幕内（写死八行时它在 y=-318，玩家从来没见过）',
  geo1 !== null && geo1.noticeBottom > geo1.screenBottom)
// 三相判据的第三相：有行时那句话必须不出现（有行还印"没有目标"等于自己打自己）
checkTrue('有行（1 行）时那一格不写空态那句话', (geo1?.noticeText ?? '') !== '这一带没有可打的目标')
check('有行时那一格仍是空的（一行正好画得下，既没有页码也没有截断提示）', geo1?.noticeText, '')

// ---------- 相位 A3：搜过且零目标 ----------
// dev 新号真后端对搜索就是回 `targets: []`（本探针开头记的实测），所以把夹具条数换成 0
// 不是造一个到不了的状态，而是把那个到得了的状态稳定下来量。
const zeroBefore = fixtureHits.search
const zero = await searchWith(0)
// 反空转前置（#347 那条假绿的成因就是"整块没画"与"画了但没数据"同形）：
// 先证明这一相真的走完了"发请求 → 夹具回包 → 视图落地"，再让零行类判据计分。
checkTrue('零目标相：夹具确实命中过（searchTargets 又打了一次，不是读到的旧状态）',
  fixtureHits.search > zeroBefore)
checkTrue('零目标相：表头真的画了（面板渲染过，不是整块没画）', (zero?.header ?? '') !== '')
checkTrue('零目标相：表头写着 0 个、战力是夹具那个数（空列表真落进了视图）',
  (zero?.header ?? '').includes('可攻击目标 0 个') && (zero?.header ?? '').includes('我的匹配战力'))
check('零目标相：searched 已置真（这一相与"没搜过"的区分全靠它）', zero?.searched, true)
check('零目标相：行区一行都没画', zero?.rowCount, 0)
checkTrue('零目标相：那一格控件在、话也真印出来了（不是空串冒充）',
  zero?.noticeFound === true && (zero?.noticeText ?? '') === '这一带没有可打的目标')
check('零目标相：两颗翻页键仍收着（#345 的口径没被这次改动带坏）', zero?.pagerActive, 0)
checkTrue('零目标相：那句话落在屏幕内（复用 Overflow 那一格，不动几何）',
  zero !== null && zero.noticeBottom > zero.screenBottom)
await page.screenshot({ path: path.join(OUT, 'march-search-zero-targets.png') })
console.log(`  截图：${path.join(OUT, 'march-search-zero-targets.png')}`)

const nine = await searchWith(9)
const indexOf = (name) => Number(String(name ?? '').replace(/\D+/g, ''))
checkTrue(`一屏装不下 9 条时确实分页（容量 ${nine?.capacity}，本页画 ${nine?.rowCount} 行）`,
  nine !== null && nine.capacity > 1 && nine.rowCount === nine.capacity - 1 && nine.rowCount < 9)
checkTrue('第一页写明页码与总数', /^第 1\/\d+ 页/.test(nine?.noticeText ?? '')
  && (nine?.noticeText ?? '').includes('共 9 个'))
checkTrue('第一页画的是最前那条', indexOf(nine?.rowNames?.[0]) === 1)
checkTrue('第一页的行仍然不压控件条', nine !== null && nine.topRowTop <= nine.lowestControlBottom)
await page.screenshot({ path: path.join(OUT, 'march-search-page1.png') })
console.log(`  截图：${path.join(OUT, 'march-search-page1.png')}`)

checkTrue('按得到「下一页」那一颗', await tapPanelButton('NextPageButton'))
await page.waitForTimeout(400)
const nine2 = await page.evaluate(GEO)
check('真有多页时两颗翻页键又回来（收掉是为了不误导，不是为了少画）', nine2?.pagerActive, 2)
checkTrue('第二页页码跟着变', /^第 2\/\d+ 页/.test(nine2?.noticeText ?? ''))
// 这一条才是"每一行都够得着"的真判据：两页并起来必须正好覆盖 1..9 且顺序不断。
// 只判"第二页有行"不够 —— 漏一条、重一条、顺序错一条，它都照样绿。
const paged = [...(nine?.rowNames ?? []), ...(nine2?.rowNames ?? [])].map(indexOf)
check('两页并起来的行数等于总条数', paged.length, 9)
checkTrue('两页并起来正好是 1..9 且顺序不断', paged.every((v, i) => v === i + 1))
await page.screenshot({ path: path.join(OUT, 'march-search-page2.png') })
console.log(`  截图：${path.join(OUT, 'march-search-page2.png')}`)

checkTrue('按得到「上一页」那一颗', await tapPanelButton('PrevPageButton'))
await page.waitForTimeout(400)
checkTrue('翻回第一页了', /^第 1\/\d+ 页/.test((await page.evaluate(GEO))?.noticeText ?? ''))

// 重新搜索必须回到第一页：停在第 2 页等一份只有 1 条的结果，表现是"搜索没结果"
const back = await searchWith(1)
check('重搜后回到第一页且只画一行', back?.rowCount, 1)
check('零目标相之后重搜到有行：那句话跟着清掉（空态文案不许常驻）', back?.noticeText, '')
await page.screenshot({ path: path.join(OUT, 'march-search-paging.png') })
console.log(`  截图：${path.join(OUT, 'march-search-paging.png')}`)

// ---------- 相位 B：点一行 → 编成弹层 → 切成集结 ----------
// B26 S12（`24977c1`）当时只能交编排断言，理由是"目标行画不出来 ⇒ 弹层在真页面上打不开"。
// 上面那一相已经证明行画得出来，所以这里把缺的那份**真点击 + 截图**补上：
// 这一路是出征与发起集结共用的唯一入口，只验装配等于没验。
const tapRow = await page.evaluate(`(() => {
  const targets = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('targets')
  const row = targets?.children.find((c) => c.name === 'TargetRow')
  if (!row) return false
  row.emit('touch-start')
  return true
})()`)
checkTrue('点得动夹具那一行（TargetRow 在树里且能收 touch-start）', tapRow)
await page.waitForTimeout(700)

/** 编成弹层的可读状态：标题前缀 + 两颗键上的字。按节点名取，不按下标（加一颗键就会错位）。 */
const COMPOSE = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const overlay = game?.getChildByName('MarchCompose')
  if (!overlay) return { found: false }
  const textOf = (name) => {
    const node = overlay.getChildByName(name)
    return node?.getChildByName('label')?.getComponent('cc.Label')?.string ?? null
  }
  const title = (overlay.children || [])
    .filter((c) => c.name === 'label')
    .map((c) => c.getComponent('cc.Label')?.string ?? '')
    .find((s) => s.includes('：')) ?? ''
  return {
    found: true,
    active: overlay.active,
    titleHead: title.slice(0, 2),
    toggleText: textOf('编成种类'),
    toggleGold: (() => {
      const c = overlay?.getChildByName('编成种类')?.getChildByName('label')
        ?.getComponent('cc.Label')?.color
      return c === null || c === undefined ? null : (c.r === 184 && c.g === 134 && c.b === 11)
    })(),
    confirmText: textOf('编成出征'),
  }
})()`

const march = await page.evaluate(COMPOSE)
check('编成弹层节点在', march?.found, true)
check('点目标行后弹层真的打开（active）', march?.active, true)
check('出征态的标题前缀是「出征」', march?.titleHead, '出征')
check('出征态下那颗命令键写「集结」（三态各一颗，不再用带方向的"改成X"措辞）',
  march?.toggleText, '集结')
check('出征态下「集结」不是选中色（选中的是出征那颗）', march?.toggleGold, false)
await page.screenshot({ path: path.join(OUT, 'compose-mode-march.png') })
console.log(`  截图：${path.join(OUT, 'compose-mode-march.png')}`)

const tapped = await page.evaluate(`(() => {
  const overlay = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('MarchCompose')
  const toggle = overlay?.getChildByName('编成种类')
  if (!toggle) return false
  toggle.emit('touch-start')
  return true
})()`)
checkTrue('按得到「集结」那一颗', tapped)
await page.waitForTimeout(500)
const rally = await page.evaluate(COMPOSE)
check('切成集结后标题前缀跟着变（同一份兵、同一个目标，只换命令种类）', rally?.titleHead, '集结')
check('切过去之后那颗仍写「集结」，选中态靠字色标出来', rally?.toggleText, '集结')
check('切到集结后「集结」那颗变成选中色（再点一次会回出征，所以字面不需要反过来写）',
  rally?.toggleGold, true)
await page.screenshot({ path: path.join(OUT, 'compose-mode-rally.png') })
console.log(`  截图：${path.join(OUT, 'compose-mode-rally.png')}`)

// ---------- 相位 C：联盟层（B26 S14 的"玩家真够得着"）----------
// 相位 B 只证明"能切成集结"。这一相盯的是：层级切得动、政策给的两个数画得出来、
// 越界那一侧的键不画、按节点名读得到（#291 的教训：只量中心在板内抓不到被切掉的半截字）。
const BAND = `(() => {
  const overlay = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('MarchCompose')
  const band = overlay?.getChildByName('rallyBand')
  if (!band) return { found: false }
  const labelOf = (name) => band.getChildByName(name)?.getComponent('cc.Label')?.string ?? null
  const chipText = (scope) => band.getChildByName('层级-' + scope)
    ?.getChildByName('label')?.getComponent('cc.Label')?.string ?? null
  const isActive = (name) => band.getChildByName(name)?.active ?? null
  const box = (node) => {
    const t = node.getComponent('cc.UITransform')
    return { top: node.position.y, bottom: node.position.y - t.height }
  }
  const rows = (overlay.children || []).filter((c) => /^composeRow\\d+$/.test(c.name) && c.active)
  const footer = ['编成取消', '编成出征', '编成种类'].map((n) => overlay.getChildByName(n)).filter(Boolean)
  return {
    found: true,
    active: band.active,
    chips: [chipText('SQUAD'), chipText('ALLIANCE')],
    numbersShown: [isActive('数-0-数'), isActive('数-1-数')],
    values: [labelOf('数-0-数'), labelOf('数-1-数')],
    steps: [[isActive('数-0-减'), isActive('数-0-加')], [isActive('数-1-减'), isActive('数-1-加')]],
    bandTop: box(band).top,
    bandBottom: box(band).bottom,
    lastRowBottom: rows.length === 0 ? null : Math.min(...rows.map((r) => box(r).bottom)),
    footerTop: footer.length === 0 ? null : Math.max(...footer.map((f) => box(f).top)),
    totalText: (overlay.children || [])
      .filter((c) => c.name === 'label')
      .map((c) => c.getComponent('cc.Label')?.string ?? '')
      .find((s) => s.startsWith('共派')) ?? '',
  }
})()`

/** 从编成弹层往下按节点名找（路径（'rallyBand/层级-ALLIANCE'）并喂一次 touch-start */
const tapCompose = (trail) => page.evaluate(`(() => {
  const overlay = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('MarchCompose')
  let node = overlay
  for (const part of '${trail}'.split('/')) { node = node?.getChildByName(part) }
  if (!node) return false
  node.emit('touch-start')
  return true
})()`)

const squadBand = await page.evaluate(BAND)
check('集结态下那一条真的画出来了（rallyBand active）', squadBand?.active, true)
check('两颗层级键上的字来自政策', JSON.stringify(squadBand?.chips), JSON.stringify(['小队', '联盟']))
check('小队层不画那两个数（服务端自己定，客户端没有可填的字段）',
  JSON.stringify(squadBand?.numbersShown), JSON.stringify([false, false]))
await page.screenshot({ path: path.join(OUT, 'compose-rally-squad.png') })
console.log(`  截图：${path.join(OUT, 'compose-rally-squad.png')}`)

checkTrue('按得到「联盟」那颗', await tapCompose('rallyBand/层级-ALLIANCE'))
// 等的是**状态**不是时间：那两个数要等 `/rally/policy` 落地后由恢复分支补上，
// 而同一批里的 `/social/permissions` 一旦在慢后端上重试（2026-09-21 实测 8080 那棵返 500 + 重试两轮），
// 政策就晚到一秒以上 —— 原来固定等 400ms 直接把 13 条判据全打成红（客户端本身没错）。
// 上限 12 秒：真不到就是缺陷，下面那条判据照样会红，这里不静默放过。
for (let i = 0; i < 40; i += 1) {
  await page.waitForTimeout(300)
  const seen = await page.evaluate(BAND)
  if (seen?.numbersShown?.[0] === true) {
    break
  }
}
const allianceBand = await page.evaluate(BAND)
check('切到联盟层后两个数都画出来了', JSON.stringify(allianceBand?.numbersShown),
  JSON.stringify([true, true]))
check('两个数的起始值照政策：12 是"此刻实际人数"，30 是服务端给的默认等待档',
  JSON.stringify(allianceBand?.values), JSON.stringify(['12/12人', '30分']))
check('两个数此刻都停在政策的上界 ⇒ 两颗 ＋ 都不画（发了也会被服务端夹回去）',
  JSON.stringify(allianceBand?.steps), JSON.stringify([[true, false], [true, false]]))
checkTrue('那条带压在最后一行兵力行之下',
  allianceBand !== null && allianceBand.lastRowBottom !== null
    && allianceBand.bandTop <= allianceBand.lastRowBottom)
checkTrue('那条带压在页脚三颗键之上（不盖住确认）',
  allianceBand !== null && allianceBand.bandBottom >= allianceBand.footerTop)
await page.screenshot({ path: path.join(OUT, 'compose-rally-alliance.png') })
console.log(`  截图：${path.join(OUT, 'compose-rally-alliance.png')}`)

/**
 * 那条带内部的版式：把所有可见控件（两颗层级键 + 每组的 表头/数/−/＋）的盒子取出来两两比对。
 * 截图抓到的第一版是「−/＋ 上印着引擎默认的 label 字样、还压在数字头上」——
 * 那种缺陷读数全绿（值对、位置在板内），只有量盒子或看画面才抓得到。
 */
const BAND_LAYOUT = `(() => {
  const overlay = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('MarchCompose')
  const band = overlay?.getChildByName('rallyBand')
  if (!band) return null
  const boxes = []
  for (const child of band.children) {
    if (!child.active) continue
    const t = child.getComponent('cc.UITransform')
    const isButton = /^(层级|数)-/.test(child.name) && child.name !== undefined
      && child.getComponent('cc.Label') === null
    const label = child.getComponent('cc.Label')
    if (!isButton && label === null) continue
    boxes.push({
      name: child.name,
      text: isButton ? (child.getChildByName('label')?.getComponent('cc.Label')?.string ?? '')
        : (label?.string ?? ''),
      left: child.position.x - t.width / 2, right: child.position.x + t.width / 2,
      top: child.position.y + t.height / 2, bottom: child.position.y - t.height / 2,
    })
  }
  const overlaps = []
  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i]; const b = boxes[j]
      if (a.left < b.right && b.left < a.right && a.bottom < b.top && b.bottom < a.top) {
        overlaps.push(a.name + '×' + b.name)
      }
    }
  }
  const leaked = boxes.filter((b) => b.text === 'label' || b.text === '').map((b) => b.name)
  const half = band.getComponent('cc.UITransform').width / 2
  const outside = boxes.filter((b) => Math.abs(b.left) > half || Math.abs(b.right) > half)
    .map((b) => b.name)
  return { count: boxes.length, overlaps, leaked, outside }
})()`

const layout = await page.evaluate(BAND_LAYOUT)
check('那条带里画得出 8 件东西（两颗层级键 + 两组 表头/数/−/＋ 里没被界挡住的）',
  layout?.count, 8)
check('带内控件两两不重叠（第一版 −/＋ 压在数字头上）',
  JSON.stringify(layout?.overlaps), '[]')
check('没有引擎默认的 label 字样漏到屏幕上', JSON.stringify(layout?.leaked), '[]')
check('所有控件整盒落在带内（不是只量中心在板内）', JSON.stringify(layout?.outside), '[]')

checkTrue('按得到「等待时长 −」', await tapCompose('rallyBand/数-1-减'))
await page.waitForTimeout(300)
const afterStep = await page.evaluate(BAND)
check('时长按 5 分钟一跳（政策给的界内）', afterStep?.values?.[1], '25分')
check('离开上界之后 ＋ 又画回来了（键随界走，不是点了没反应）',
  JSON.stringify(afterStep?.steps?.[1]), JSON.stringify([true, true]))
checkTrue('按得到「人数上限 +」（已在界上，加不动）', await tapCompose('rallyBand/数-0-加'))
await page.waitForTimeout(300)
check('人数停在上界不越界（越界的数发出去会被服务端夹回去）',
  (await page.evaluate(BAND))?.values?.[0], '12/12人')

const tapRowPlus = await page.evaluate(`(() => {
  const overlay = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('MarchCompose')
  const row = (overlay?.children || []).find((c) => c.name === 'composeRow0')
  const plus = row?.getChildByName('row-＋')
  if (!plus) return false
  plus.emit('touch-start')
  return true
})()`)
checkTrue('按得到第一行兵力的「＋」', tapRowPlus)
await page.waitForTimeout(300)
const armed = await page.evaluate(BAND)
check('兵力合计跟着勾选走', (armed?.totalText ?? '').includes('10'), true)

checkTrue('按得到「发起集结」', await tapCompose('编成出征'))
await page.waitForTimeout(900)
const sent = sentRallies.at(-1) ?? null
check('真的打到了 POST /rally/alliance（一次确认一条）', sentRallies.length, 1)
check('请求带的是屏幕上那两个数', JSON.stringify([sent?.maxMembers, sent?.prepareMinutes]),
  JSON.stringify([12, 25]))
check('承诺的兵力随这一枪交出去（服务端拿它建第一个参与者）',
  JSON.stringify(sent?.troops), JSON.stringify([{ unitId: 'unit_infantry_t1', count: 10 }]))
check('目标是编成前点的那一座', JSON.stringify(sent?.targetCoord), JSON.stringify({ x: 100, y: 77 }))
check('幂等键由 GameApi 新生成（重放等于多开一支集结）',
  typeof sent?.requestId === 'string' && (sent?.requestId ?? '').length > 0, true)
await page.screenshot({ path: path.join(OUT, 'compose-rally-submitted.png') })
console.log(`  截图：${path.join(OUT, 'compose-rally-submitted.png')}`)

await page.screenshot({ path: path.join(OUT, 'march-search-rows.png') })
console.log(`  截图：${path.join(OUT, 'march-search-rows.png')}`)

await page.screenshot({ path: path.join(OUT, 'march-search-panel.png') })
console.log(`  截图：${path.join(OUT, 'march-search-panel.png')}`)
// ---------- 相位 D：军队行上的「队列」菜单与取消训练（B26 S15）----------
// 另开一页走 ?panel=army：前面那页停在搜索/编成那一屏，切页签要摸导航条的节点名，
// 而深链本来就是这一格的入口。军队数据用相位 C 那份夹具，就地把第一口改成"正在训练"。
ARMY_FIXTURE.units[0].training = 30
ARMY_FIXTURE.units[0].remainingSeconds = 600
ARMY_FIXTURE.units[0].finishAt = Date.now() + 600000
const sentCancels = []
await context.route('**/army/cancel*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  sentCancels.push(JSON.parse(route.request().postData() ?? '{}'))
  await reply(route, { unitId: 'unit_infantry_t1', count: 30, refund: [], serverNow: Date.now() })
})

const page2 = await context.newPage()
page2.on('pageerror', (error) => errors.push(error.message))
page2.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})
const url2 = new URL(`${preview.origin}/`)
url2.searchParams.set('panel', 'army')
await page2.goto(url2.toString(), { waitUntil: 'networkidle' })
await page2.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
/** 整棵树里找同名节点（面板节点名不写死，免得换个名字就假红），并把那一行的直接子节点盒子一起带回来 */
const SCAN = `(() => {
  const root = window.cc.director.getScene().getChildByName('Canvas')
  const out = []
  const walk = (node, depth) => {
    if (node.name === 'QueueButton') {
      const row = node.parent
      const box = (n) => {
        const t = n.getComponent('cc.UITransform')
        return {
          name: n.name,
          left: n.position.x - t.width / 2, right: n.position.x + t.width / 2,
          top: n.position.y + t.height / 2, bottom: n.position.y - t.height / 2,
        }
      }
      out.push({
        active: node.active,
        rowTitle: row?.getChildByName('Title')?.getComponent('cc.Label')?.string ?? '',
        boxes: row === null || row === undefined ? [] : row.children
          .filter((c) => c.active && c.getComponent('cc.UITransform') !== null)
          .map((c) => box(c)),
      })
    }
    for (const child of node.children) walk(child, depth + 1)
  }
  walk(root, 0)
  return out
})()`
// 轮询到行真的画出来为止（固定等 2.5 秒是抖动源：登录慢一点就整相全红）
let queueRows = []
for (let i = 0; i < 40; i += 1) {
  await page2.waitForTimeout(500)
  queueRows = await page2.evaluate(SCAN)
  if (queueRows.length > 0) {
    break
  }
}



check('两行兵都画出了「队列」这颗键（可见性由渲染决定，节点先都在）', queueRows?.length, 2)
check('只有正在练的那一口把键点亮，另一口收起',
  JSON.stringify(queueRows?.map((it) => it.active)), JSON.stringify([true, false]))
check('点亮的那一行是重步（读的是行自己的标题，不是按下标认行）',
  (queueRows?.find((it) => it.active)?.rowTitle ?? '').includes('重步'), true)
// 版式判据：**键与字、键与键**两两不重叠（字与字是行内三行紧排，本来就上下相接，不算撞）。
// 编成弹层那一排就是被"值全对但字压字"坑过一次，所以这里按盒子量，不按眼睛。
const litBoxes = queueRows?.find((it) => it.active)?.boxes ?? []
const isButton = (name) => /Button$/.test(name)
const overlaps = []
for (let i = 0; i < litBoxes.length; i++) {
  for (let j = i + 1; j < litBoxes.length; j++) {
    const a = litBoxes[i]
    const b = litBoxes[j]
    if (!isButton(a.name) && !isButton(b.name)) continue
    if (a.left < b.right && b.left < a.right && a.bottom < b.top && b.bottom < a.top) {
      overlaps.push(`${a.name}×${b.name}`)
    }
  }
}
check('点亮那一行里「键压字 / 键压键」的重叠为零', JSON.stringify(overlaps), '[]')
checkTrue('这一行至少排开了 6 件东西（三行字 + 三颗键），判据不是空转', litBoxes.length >= 6)

const tappedQueue = await page2.evaluate(`(() => {
  const root = window.cc.director.getScene().getChildByName('Canvas')
  let hit = null
  const walk = (node) => {
    if (node.name === 'QueueButton' && node.active && hit === null) hit = node
    for (const child of node.children) walk(child)
  }
  walk(root)
  if (hit === null) return false
  hit.emit('touch-start')
  return true
})()`)
checkTrue('按得到那颗「队列」', tappedQueue)
await page2.waitForTimeout(500)

const MENU = `(() => {
  const root = window.cc.director.getScene().getChildByName('Canvas')
  let overlay = null
  const findOverlay = (node) => {
    if (node.name === 'ChoiceOverlay' && node.active) overlay = node
    for (const child of node.children) findOverlay(child)
  }
  findOverlay(root)
  if (overlay === null) return { found: false, texts: [], parentName: null }
  // 弹层必须排在宿主子节点的最后一位：列表行是渲染时才加进同一个父节点的，
  // 加得晚就压在菜单上面（军队那格实测被挡住半截，读数却全绿）
  const parentName = overlay.parent?.name ?? ''
  const texts = []
  const collect = (node) => {
    const label = node.getComponent('cc.Label')
    if (label !== null && label.string.length > 0) texts.push(label.string)
    for (const child of node.children) collect(child)
  }
  collect(overlay)
  return { found: true, texts, parentName }
})()`
const menu = await page2.evaluate(MENU)
checkTrue('菜单真的弹出来了（ChoiceOverlay 激活）', menu?.found === true)
check('菜单挂在场景层（Game 节点）：面板每秒为倒计时重挂行，建在面板里的弹层会被压住',
  menu?.parentName, 'Game')
const oneLine = await page2.evaluate(`(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let overlay = null
  const find = (n) => { if (n.name === 'ChoiceOverlay' && n.active) overlay = n; for (const c of n.children) find(c) }
  find(game)
  if (overlay === null) return null
  const panelW = overlay.getComponent('cc.UITransform')?.width ?? 0
  const row = (overlay.children || []).find((c) => c.name === 'Choice-0')
  const title = (row?.children || []).find((c) => (c.getComponent('cc.Label')?.string ?? '').includes('取消'))
  const t = title?.getComponent('cc.UITransform')
  const band = row?.getComponent('cc.UITransform')
  return { panelW, titleH: t?.height ?? null, titleW: t?.width ?? null, bandW: band?.width ?? null }
})()`)
checkTrue('选项标题是一行（盒子高度不超过一行 17 号字）：被挤成两行就是版式没吃到面板宽',
  oneLine !== null && oneLine.titleH !== null && oneLine.titleH <= 26)
checkTrue('行的色带与标题盒子都在面板内（窄面板不再溢出）',
  oneLine !== null && oneLine.bandW <= oneLine.panelW && oneLine.titleW <= oneLine.panelW)
checkTrue('菜单里那条写的是「取消这一口训练」，并把在练的数量说清了',
  (menu?.texts ?? []).some((t) => t.includes('取消这一口训练'))
    && (menu?.texts ?? []).some((t) => t.includes('30') && t.includes('重步')), true)
await page2.screenshot({ path: path.join(OUT, 'army-queue-menu.png') })
console.log(`  截图：${path.join(OUT, 'army-queue-menu.png')}`)

const picked = await page2.evaluate(`(() => {
  const root = window.cc.director.getScene().getChildByName('Canvas')
  let target = null
  const walk = (node) => {
    const label = node.getComponent('cc.Label')
    if (label !== null && (label.string ?? '').includes('取消这一口训练')) target = node
    for (const child of node.children) walk(child)
  }
  walk(root)
  if (target === null) return false
  // 可点的那一层是行的父节点：从字往上连发三次，谁挂了监听谁收到
  let node = target
  for (let i = 0; i < 3 && node !== null && node !== undefined; i++) {
    node.emit('touch-start')
    node = node.parent
  }
  return true
})()`)
checkTrue('按得到菜单里那条「取消这一口训练」', picked)
await page2.waitForTimeout(900)
const cancel = sentCancels.at(-1) ?? null
check('取消真的发出去了（一次点选一条请求）', sentCancels.length, 1)
check('打的是取消那一口：unitId 是点亮那行的兵种', cancel?.unitId, 'unit_infantry_t1')
check('不带加速参数（这一口是"不练了"，不是"练快点"）',
  JSON.stringify([cancel?.seconds, cancel?.itemId]), JSON.stringify([null, null]))
checkTrue('幂等键在（取消会退资源，重放等于退两次）',
  typeof cancel?.requestId === 'string' && (cancel?.requestId ?? '').length > 0)
await page2.screenshot({ path: path.join(OUT, 'army-queue-cancelled.png') })
console.log(`  截图：${path.join(OUT, 'army-queue-cancelled.png')}`)

// ---------- 相位 E：编成面板的第三种命令 —— 派侦察（B26 S18）----------
// 页脚从三颗键变四颗（取消 / 侦察 / 集结 / 出征）：旧版三颗 180 宽摆在 -140/0/140，
// 盒子彼此压了 40px —— 加第四颗时才量出来，所以这一相顺手把"页脚不重叠"也钉成判据。
const sentScouts = []
await context.route('**/world/scout*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  sentScouts.push(JSON.parse(route.request().postData() ?? '{}'))
  await reply(route, {
    march: {
      marchId: 'fixture-scout-1', from: { x: 48, y: 48 }, to: { x: 100, y: 77 }, status: 'MARCHING',
      targetType: 'CITY', targetId: 'fixture-target-1', rallyId: null, action: 'SCOUT',
      startAt: Date.now(), arriveAt: Date.now() + 60000, returnStartAt: null, returnArriveAt: null,
      units: [], heroes: [], load: 0, loadCap: 0, teamSpeed: 0, position: { x: 60, y: 60 },
      progressFixed: 0, gatherFinishAt: null, serverNow: Date.now(),
    },
    distance: 24, durationSec: 60, serverNow: Date.now(),
  })
})

const FOOT = `(() => {
  const overlay = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('MarchCompose')
  if (!overlay) return null
  const names = [\x27编成取消\x27, \x27编成侦察\x27, \x27编成种类\x27, \x27编成出征\x27]
  const boxes = names.map((n) => {
    const node = overlay.getChildByName(n)
    if (!node) return null
    const t = node.getComponent(\x27cc.UITransform\x27)
    return {
      name: n, left: node.position.x - t.width / 2, right: node.position.x + t.width / 2,
      bottom: node.position.y - t.height / 2, top: node.position.y + t.height / 2,
      caption: node.getChildByName(\x27label\x27)?.getComponent(\x27cc.Label\x27)?.string ?? null,
    }
  }).filter((b) => b !== null)
  const half = window.cc.view.getVisibleSize().width / 2
  return { boxes, screenHalf: half }
})()`

// 重新点一行拉起编成面板（相位 C 提交后已经收起）
await page.evaluate(`(() => {
  const targets = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('targets')
  const row = targets?.children.find((c) => c.name === 'TargetRow')
  if (row) row.emit('touch-start')
  return row !== null && row !== undefined
})()`)
await page.waitForTimeout(700)

const footBefore = await page.evaluate(FOOT)
check('页脚四颗键都在（取消 / 侦察 / 集结 / 出征）', footBefore?.boxes?.length, 4)
const footOverlap = []
for (let i = 0; i < (footBefore?.boxes ?? []).length; i++) {
  for (let j = i + 1; j < (footBefore?.boxes ?? []).length; j++) {
    const a = footBefore.boxes[i]
    const b = footBefore.boxes[j]
    if (a.left < b.right && b.left < a.right && a.bottom < b.top && b.bottom < a.top) {
      footOverlap.push(`${a.name}×${b.name}`)
    }
  }
}
check('页脚四颗键两两不重叠（旧版三颗 180 宽摆 -140/0/140 会互压 40px）',
  JSON.stringify(footOverlap), '[]')

// ---------- 军队表头那两行：医院与警告（#362 补的宽度守卫，判据要能守住它） ----------
// 同一屏的 header 早就限过宽（`ArmyPanelView` 第 207 行的注释引着 #22x 教训），
// 而紧挨着的 Hospital / Warning 两行没有限 —— 两行都是居中的、垂直间距只有 26px，
// 长文案一旦换行就会互相压住，或顶出面板。这里量世界矩形，不按常数推算。
const HEADER_ROWS = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('army')
  if (!panel) return null
  const size = window.cc.view.getVisibleSize()
  const pick = (name) => {
    let hit = null
    const walk = (n) => { if (hit === null && n.name === name) hit = n; for (const c of n.children) walk(c) }
    walk(panel)
    if (hit === null) return null
    const t = hit.getComponent('cc.UITransform')
    const w = t.getBoundingBoxToWorld()
    const lb = hit.getComponent('cc.Label')
    return { text: lb?.string ?? '',
      l: w.x, r: w.x + w.width, b: w.y, t: w.y + w.height,
      boxW: Math.round(t.width), overflowing: w.x < 0 || w.x + w.width > size.width,
      want: lb?.fontSize ?? 0, ov: lb?.overflow ?? -1, actual: lb?.actualFontSize ?? -1 }
  }
  const hospital = pick('Hospital')
  const warning = pick('Warning')
  // 两行的**字形带**不相交：中心距下限按字号（em）算，不按行盒算。
  // 行盒按字模给（实测 ≈ 字号×1.54~1.79），拿行盒比会把健康版式判成一片红（#365），
  // 而 #364 为了让行盒比开，去猜盒高 + SHRINK，结果整行字被压小（#366 的迁移曲线）。
  const crowd = hospital !== null && warning !== null
    && hospital.l < warning.r && warning.l < hospital.r
    && Math.abs((hospital.b + hospital.t) / 2 - (warning.b + warning.t) / 2)
      < (hospital.want + warning.want) / 2 + 4
  return {
    found: hospital !== null && warning !== null,
    // 守卫的判据从「两行同宽」换成「两行都只有一行、且都没被盒子改了字号」：
    // 同宽是"钉宽度"的产物，而钉宽度 + SHRINK 的真实代价是把字压小一号（#366 实测曲线）。
    // 现在限的是"不换行"，宽度由文本决定，所以判据直接量意图本身。
    oneRowEach: hospital !== null && warning !== null
      && hospital.want > 0 && warning.want > 0
      && hospital.t - hospital.b <= hospital.want * 2 + 8
      && warning.t - warning.b <= warning.want * 2 + 8,
    // 正向前置：警告行此刻真的有内容 —— 否则"两行不相交"是在读一个空集合（#347 那族假绿）
    warningHasText: warning !== null && warning.text.length > 0,
    // 盒高低于一行字的实测下限 ⇒ SHRINK 正在拿缩字服从盒子。下限见台账 #366 的迁移曲线
    // （14~20 号字都要到 30 才等于设定字号，且不随字号走）。
    // ⚠ 别改用 actualFontSize：本轮植入实测它在翻转 overflow 后给的是**上一个模式的残值**
    //   （NONE 的 1.5×字号），判据会恒绿 —— 是能骗过眼睛的那种假绿。
    shrunk: [hospital, warning].filter((x) => x !== null && x.ov === 2 && x.text.length > 0
      && (x.t - x.b) < Math.max(x.want + 14, 30))
      .map((x) => x.text.slice(0, 6) + '=盒' + Math.round(x.t - x.b) + '<' + Math.max(x.want + 14, 30)),
    rowHeights: [hospital, warning].filter((x) => x !== null)
      .map((x) => Math.round(x.t - x.b) + '@' + x.want),
    crowd,
    outside: [hospital, warning].filter((x) => x !== null && x.overflowing)
      .map((x) => x.text.slice(0, 18)),
  }
})()`
// ⚠ 必须用 `page2`：军队那一屏是第 691 行为它另开的一页（`?panel=army`），
// 写成 `page` 会找不到 army 面板 ⇒ 判据在基线假红（本轮实测踩过，别当成产品缺陷）。
const headerRows = await page2.evaluate(HEADER_ROWS)

// 反空转前置：两行都得在树里（`addLabel` 建的节点名就是 Hospital / Warning），否则后面全是读空集合
checkTrue('军队表头那两行都在（医院行与警告行）', headerRows?.found === true)
checkTrue('两行各自只占一行高（盒子 ≤ 字号×2+8；撑到两行就是又让 Label 换行了）',
  headerRows?.oneRowEach === true)
check('两行都不顶出屏幕宽度', JSON.stringify(headerRows?.outside ?? null), '[]')
// #362 撤下又还回来的那条：先证警告行此刻有内容，再断两行不互相压住
checkTrue('警告行此刻确实有内容（否则下一条是在读空集合）', headerRows?.warningHasText === true)
// #366：这两行现在靠 `keepOneLine` 关换行，不再用 SHRINK 猜盒高。这条此刻是空跑
// （本屏 SHRINK 行数 0），但它钉住那条回头路：谁再用 SHRINK 压行盒，落地字号这条立刻红。
check('这两行没有被盒子改了字号（SHRINK 行的落地字号 ≥ 设定字号）',
  JSON.stringify(headerRows?.shrunk ?? null), '[]')
check('医院行与警告行的字形带不相交（中心距 ≥ 两行字号之和的一半 + 4；行盒相交不算，#365）',
  headerRows?.crowd, false)
check('出征态下那颗命令键写「集结」（不再是\u300c改成集结\u300d这种带方向的措辞）',
  JSON.stringify(footBefore?.boxes?.map((b) => b.caption)),
  JSON.stringify(['取消', '侦察', '集结', '出征']))

checkTrue('按得到「侦察」那颗', await tapCompose('编成侦察'))
await page.waitForTimeout(400)
const scoutState = await page.evaluate(COMPOSE)
check('切成侦察后标题前缀跟着变', scoutState?.titleHead, '侦察')
const footScout = await page.evaluate(FOOT)
await page.screenshot({ path: path.join(OUT, 'compose-scout-selected.png') })
console.log('  截图：' + path.join(OUT, 'compose-scout-selected.png'))

checkTrue('按得到第一行兵力的「＋」', await page.evaluate(`(() => {
  const overlay = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('MarchCompose')
  const row = (overlay?.children || []).find((c) => c.name === 'composeRow0')
  const plus = row?.getChildByName('row-＋')
  if (!plus) return false
  plus.emit('touch-start')
  return true
})()`))
await page.waitForTimeout(300)
checkTrue('按得到「派侦察」（确认键的字跟着命令种类走）', await tapCompose('编成出征'))
await page.waitForTimeout(900)
check('真的打到 POST /world/scout（一次确认一条）', sentScouts.length, 1)
const scout = sentScouts.at(-1) ?? null
check('侦察带的是屏幕上这一队', JSON.stringify(scout?.units),
  JSON.stringify([{ unitId: 'unit_infantry_t1', count: 10 }]))
checkTrue('侦察目标是编成前点的那一座',
  scout?.toX === 100 && scout?.toY === 77)
checkTrue('确认键的字在侦察态写「派侦察」',
  (footScout?.boxes ?? []).some((b) => b.name === '编成出征' && b.caption === '派侦察'))
await page.screenshot({ path: path.join(OUT, 'compose-scout-mode.png') })
console.log(`  截图：${path.join(OUT, 'compose-scout-mode.png')}`)

check('运行期零 error（页面级报错）', errors.length, 0)
if (errors.length > 0) {
  for (const message of errors.slice(0, 3)) console.log(`    error: ${message.slice(0, 160)}`)
}

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
