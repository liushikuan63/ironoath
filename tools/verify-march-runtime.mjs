/**
 * 职责：出征编成弹层的**运行时**验收（B25-S1 的 S1d）—— 在真构建产物 + 真服务端上确认
 * 「弹层真的存在、默认是收起的、搜索面板正常画出来」。
 * 依赖：node、playwright、**已启动的 dev 服务端**、已构建的 `client/build/web-mobile`。
 * 用法：node tools/verify-march-runtime.mjs
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
const BACKEND = process.env.MARCH_BACKEND ?? 'http://localhost:8080'

let pass = 0
let fail = 0
const ok = (msg) => { pass += 1; console.log(`  PASS  ${msg}`) }
const bad = (msg) => { fail += 1; console.log(`  FAIL  ${msg}`) }
const checkTrue = (msg, actual, detail) => {
  if (actual === true) {
    ok(msg)
  } else {
    bad(`${msg}：实际 ${JSON.stringify(actual)}${detail === undefined ? '' : ` ${detail}`}`)
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

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'targets')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
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

// ---------- 夹具相：dev 服上没有对手，所以把搜索结果换成一条真形状的回包 ----------
// 替换的是**读接口**（网络层响应），视图与被测代码一行没换：这一相要问的正是
// 「真数据到位后，TargetSearchView 画不画得出行」。
const fixture = { count: 1, policyOpen: false }
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
await context.route('**/world/searchTargets*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, {
    targets: Array.from({ length: fixture.count }, (_, i) => ({
      id: `fixture-target-${i + 1}`, name: `测试城·${i + 1}`, coord: { x: 100 + i, y: 77 },
      matchPower: 12_000, powerRatio: 12_000, distanceBand: 'NEAR',
      resourceHint: 'NORMAL', isShielded: false, tyrannyLevel: null,
    })),
    selfMatchPower: 10_000, bandLower: 8_000, bandUpper: 15_000, serverNow: Date.now(),
  })
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
  const notice = panel.getChildByName('Overflow')
  const view2 = panel.getComponent('TargetSearchView')
  return {
    rowCount: rows.length,
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
checkTrue('行没有压住控件条（第一行上沿在按钮下沿之下）',
  geo1 !== null && geo1.topRowTop <= geo1.lowestControlBottom)
checkTrue('行没有压到底部导航条', geo1 !== null && geo1.lastRowBottom >= geo1.navTop)
checkTrue('页码那行字落在屏幕内（写死八行时它在 y=-318，玩家从来没见过）',
  geo1 !== null && geo1.noticeBottom > geo1.screenBottom)

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
await page.screenshot({ path: path.join(OUT, 'march-search-paging.png') })
console.log(`  截图：${path.join(OUT, 'march-search-paging.png')}`)

// ---------- 相位 B：点一行 → 编成弹层 → 切成集结 ----------
// B26 S12（`24977c1`）当时只能交编排断言，理由是"目标行画不出来 ⇒ 弹层在真页面上打不开"。
// 上面那一相已经证明行画得出来，所以这里把缺的那份**真点击 + 截图**补上：
// 这一路是出征与发起集结共用的唯一入口，只验装配等于没验。
//
// 政策读口必须在**点开编成之前**就挂上：`beginMarchCompose` 一进去就后台拉一次
// `/rally/policy`，挂晚了那一发已经打到真后端（本机 8080 是旧 jar，它 404），
// 客户端读不到政策就按"暂时不知道"放行，B1 那条"政策说不能发起就不切"根本走不到。
//
// 为什么这一相用桩而不是真后端：本机 8080 上跑的是**旧 jar**，`GET /rally/policy` 直接 404
// （`curl` 实测 `detail:"接口不存在"`，那条读口是 `ef8032c` 才加的）。⇒ 端到端的"服务端真的会挡"
// 由 `RallyEndpointTest` 的四条用例覆盖，这里只验客户端拿到 canStart=false 之后的表现。
await context.route('**/rally/policy*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, {
    squad: { minMembers: 2, maxMembers: 5, minPrepareMinutes: 5, maxPrepareMinutes: 60,
      defaultPrepareMinutes: 60, canStart: fixture.policyOpen,
      reason: fixture.policyOpen ? null : '你还没有小队，先加入或建一支再发起集结' },
    alliance: { minMembers: 3, maxMembers: 20, minPrepareMinutes: 10, maxPrepareMinutes: 120,
      defaultPrepareMinutes: 120, canStart: true, reason: null },
    serverNow: Date.now(),
  })
})
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
  // 提示行与标题/坐标/合计同为 overlay 的直接 label 子节点，只能按 y 认（它们同名）
  const near = (y) => (overlay.children || [])
    .filter((c) => c.name === 'label' && Math.abs(c.position.y - y) < 2)
    .map((c) => c.getComponent('cc.Label')?.string ?? '')
    .join('')
  const rallyRow = overlay.getChildByName('编成集结参数')
  const size = window.cc.view.getVisibleSize()
  // 键的 y 是**面板内**坐标，而面板整体可能抬起（PANEL_LIFT）⇒ 比导航条要换算到世界
  const bottoms = ['编成取消', '编成种类', '编成出征'].map((name) => {
    const n = overlay.getChildByName(name)
    if (n === null || n === undefined) {
      return 0
    }
    return overlay.position.y + n.position.y - n.getComponent('cc.UITransform').height / 2
  })
  const panel = overlay.getComponent('cc.UITransform')
  // 节点的 UITransform 现在是**遮罩命中框**（整屏），不是面板那一块 ⇒ 面板边界只能从内容量：
  // 取最上面那条标题（玩家必须看见的第一行）
  const titleNode = (overlay.children || [])
    .filter((c) => c.name === 'label' && (c.getComponent('cc.Label')?.string ?? '').includes('：'))
    .at(0)
  const titleTop = titleNode === undefined ? null
    : overlay.position.y + titleNode.position.y + titleNode.getComponent('cc.UITransform').height / 2
  return {
    found: true,
    active: overlay.active,
    titleHead: title.slice(0, 2),
    toggleText: textOf('编成种类'),
    confirmText: textOf('编成出征'),
    noticeText: near(-186),
    // 弹层加高之后最容易犯的就是底部那一叠沉到导航条上：文字快照看不出来，只能量
    navTop: -size.height / 2 + 8 + 52,
    lowestButton: Math.min(...bottoms),
    designHeight: size.height,
    clearsNav: Math.min(...bottoms) >= (-size.height / 2 + 8 + 52),
    // 命中框现在就是整屏：它盖不住屏幕边缘的话，边缘那一条仍然会穿透到底下的面板
    scrimBottom: overlay.position.y - panel.height / 2,
    scrimTop: overlay.position.y + panel.height / 2,
    // 抬起之后玩家第一眼那行字不能被顶出可视区
    titleTop,
    designTop: size.height / 2,
    rallyActive: rallyRow ? rallyRow.active : null,
    rallyText: rallyRow ? rallyRow.children
      .filter((c) => c.name === 'label')
      .map((c) => c.getComponent('cc.Label')?.string ?? '')
      .filter((s) => s.length > 0).join(' / ') : '',
  }
})()`

const tapCompose = (name) => page.evaluate(`(() => {
  const overlay = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('MarchCompose')
  const node = overlay?.getChildByName('${name}')
  if (!node) return false
  node.emit('touch-start')
  return true
})()`)
/** 步进那颗在参数行里，且到界时自己消失：按一次返回是否还真按得到 */
const tapRallyButton = (name) => page.evaluate(`(() => {
  const overlay = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('MarchCompose')
  const row = overlay?.getChildByName('编成集结参数')
  const node = row?.children.find((c) => c.name === '${name}')
  if (!node || !node.active) return false
  node.emit('touch-start')
  return true
})()`)

const march = await page.evaluate(COMPOSE)
check('编成弹层节点在', march?.found, true)
check('点目标行后弹层真的打开（active）', march?.active, true)
check('出征态的标题前缀是「出征」', march?.titleHead, '出征')
check('出征态下切种类那颗写明下一档是「小队集结」', march?.toggleText, '改成小队集结')
check('出征那一档没有参数行：出征不吃"等人"这个维度', march?.rallyActive, false)
checkTrue('弹层底部三颗键都没压到导航条', march?.clearsNav === true,
  `最低 ${march?.lowestButton} vs 导航条上沿 ${march?.navTop}（可视高 ${march?.designHeight}）`)
checkTrue('遮罩命中框盖住整屏（边缘那一条不再穿透）',
  march !== null && march.scrimBottom <= -march.designTop + 1 && march.scrimTop >= march.designTop - 1,
  `命中框 [${march?.scrimBottom}, ${march?.scrimTop}] vs 屏幕 [${-march?.designTop}, ${march?.designTop}]`)
checkTrue('抬起后标题仍在可视区内（第一眼那行字不能被顶掉）',
  march !== null && march.titleTop !== null && march.titleTop <= march.designTop - 1,
  `标题顶 ${march?.titleTop} vs 可视顶 ${march?.designTop}`)
await page.screenshot({ path: path.join(OUT, 'compose-mode-march.png') })
console.log(`  截图：${path.join(OUT, 'compose-mode-march.png')}`)

// ---------- 相位 B1：政策说"不能发起" —— 切不动要把服务端那句原因原样说出来 ----------
checkTrue('按得到切种类那一颗', await tapCompose('编成种类'))
await page.waitForTimeout(500)
const blockedOn = await page.evaluate(COMPOSE)
check('政策说不能发起就停在出征（不假装切过去了）', blockedOn?.toggleText, '改成小队集结')
checkTrue('切不动时把读口那句原因原样显示出来（不是"按钮坏了"，也不出现权限码）',
  (blockedOn?.noticeText ?? '') === '你还没有小队，先加入或建一支再发起集结',
  `实际：${JSON.stringify(blockedOn?.noticeText)}`)
await page.screenshot({ path: path.join(OUT, 'compose-rally-blocked.png') })
console.log(`  截图：${path.join(OUT, 'compose-rally-blocked.png')}`)

// ---------- 相位 B2：政策换成"能发起"，验三态循环与联盟那一档的参数行 ----------
fixture.policyOpen = true
await page.reload({ waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null,
  null, { timeout: 60_000 })
await page.waitForTimeout(3_000)
fixture.count = 1
await tapPanelButton('SearchButton')
await page.waitForTimeout(600)
checkTrue('重开后再点得动目标行', await page.evaluate(`(() => {
  const targets = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    ?.getChildByName('targets')
  const row = targets?.children.find((c) => c.name === 'TargetRow')
  if (!row) return false
  row.emit('touch-start')
  return true
})()`))
await page.waitForTimeout(700)

checkTrue('按得动切种类', await tapCompose('编成种类'))
await page.waitForTimeout(400)
const squad = await page.evaluate(COMPOSE)
check('小队档：标题变集结', squad?.titleHead, '集结')
check('小队档：确认键写「发起小队集结」', squad?.confirmText, '发起小队集结')
check('小队档：那颗指向下一档「联盟集结」', squad?.toggleText, '改成联盟集结')
check('小队档仍然没有参数行（SquadRallyReq 不吃人数与时长）', squad?.rallyActive, false)

checkTrue('按得动切到联盟档', await tapCompose('编成种类'))
await page.waitForTimeout(400)
const alliance = await page.evaluate(COMPOSE)
check('联盟档：确认键写「发起联盟集结」', alliance?.confirmText, '发起联盟集结')
check('联盟档：切回出征的那颗', alliance?.toggleText, '改回出征')
check('联盟档长出参数行', alliance?.rallyActive, true)
checkTrue('参数行的两个数来自读口（20 人 / 120 分钟，不是客户端挑的）',
  (alliance?.rallyText ?? '').includes('20 人') && (alliance?.rallyText ?? '').includes('120 分钟'),
  `实际：${JSON.stringify(alliance?.rallyText)}`)
await page.screenshot({ path: path.join(OUT, 'compose-mode-rally.png') })
console.log(`  截图：${path.join(OUT, 'compose-mode-rally.png')}`)

checkTrue('按得动「集结人数减」', await tapRallyButton('集结人数减'))
await page.waitForTimeout(400)
const stepped = await page.evaluate(COMPOSE)
checkTrue('点一下减就少 2 人（跨度 17 ⇒ 步长 2，界由读口给）',
  (stepped?.rallyText ?? '').includes('18 人'), `实际：${JSON.stringify(stepped?.rallyText)}`)
// 一路减到底：到界那一格要自己消失，而不是"按了没反应"
let taps = 0
while (taps < 12 && await tapRallyButton('集结人数减')) {
  taps += 1
  await page.waitForTimeout(220)
}
const floored = await page.evaluate(COMPOSE)
checkTrue(`减到底停在读口给的下界 3 人（按了 ${taps} 下）`,
  (floored?.rallyText ?? '').includes('3 人'), `实际：${JSON.stringify(floored?.rallyText)}`)
checkTrue('到界之后「减」那颗不再吃触摸（探针据此返回 false，而不是静默无反应）', taps <= 9)

checkTrue('按得动切回出征', await tapCompose('编成种类'))
await page.waitForTimeout(400)
const backToMarch = await page.evaluate(COMPOSE)
check('切回出征后参数行整条收起', backToMarch?.rallyActive, false)
check('确认键回到「出征」', backToMarch?.confirmText, '出征')
checkTrue('加高之后弹层底部仍然不压导航条（三态各测一次）', backToMarch?.clearsNav === true)

// ---------- 相位 B3：遮罩的命中区域（真指针事件，不是 emit touch-start）----------
// 只 emit 事件证明不了"命中区盖住整屏"——那正是这条要验的东西，必须按屏幕坐标真点一下。
const clickAt = async (dx, dy) => {
  const box = await page.locator('canvas').boundingBox()
  if (!box) {
    throw new Error('找不到 canvas，无法按屏幕坐标点击')
  }
  const scale = box.width / 960
  await page.mouse.click(box.x + box.width / 2 + dx * scale, box.y + box.height / 2 - dy * scale)
}
const NAV = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  return game?.getComponent('PanelNav')?.current() ?? null
})()`
const navBefore = await page.evaluate(NAV)
// 面板之外的一个点：面板占 y ∈ [-234,294]，-285 落在它下面的导航条上
await clickAt(0, -285)
await page.waitForTimeout(500)
const scrim = await page.evaluate(COMPOSE)
check('点遮罩空白处不关闭弹层（口径：关闭只走「编成取消」那颗键）', scrim?.active, true)
check('点遮罩空白处也不穿透打到导航条（没换页）', await page.evaluate(NAV), navBefore)

// 兵种名压出面板那条只**记录量到的数**，不判红：修法要连 −/＋ 两颗键一起重排，是另一格
const OVERFLOW = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const overlay = game?.getChildByName('MarchCompose')
  const row = overlay?.children.find((c) => c.name === 'composeRow0')
  const label = row?.children[0]
  const t = label?.getComponent('cc.UITransform')
  return { labelLeft: label ? label.position.x - t.width / 2 : null, panelLeft: -310 }
})()`
console.log('  量测（未判据）：兵种名左边缘 vs 面板左边缘 =', JSON.stringify(await page.evaluate(OVERFLOW)))

await page.screenshot({ path: path.join(OUT, 'compose-scrim-click.png') })
console.log(`  截图：${path.join(OUT, 'compose-scrim-click.png')}`)

await page.screenshot({ path: path.join(OUT, 'march-search-rows.png') })
console.log(`  截图：${path.join(OUT, 'march-search-rows.png')}`)

await page.screenshot({ path: path.join(OUT, 'march-search-panel.png') })
console.log(`  截图：${path.join(OUT, 'march-search-panel.png')}`)
check('运行期零 error（页面级报错）', errors.length, 0)
if (errors.length > 0) {
  for (const message of errors.slice(0, 3)) console.log(`    error: ${message.slice(0, 160)}`)
}

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
