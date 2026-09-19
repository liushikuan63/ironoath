#!/usr/bin/env node
/**
 * 职责：社交面板的**权限门**运行时验收（B26 §一 S1）—— 证明「踢出」「捐献」这两行
 *       的亮/灰是从 `GET /social/permissions` 读来的，而不是写死在客户端里。
 * 依赖：node、playwright、一台能登录的后端（默认 8180）、已构建的 web-mobile 产物。
 *
 * 用法：
 *   SOCIAL_PERM_BACKEND=http://localhost:8180 \
 *   SOCIAL_PERM_ARTIFACT_ROOT=/d/tmp/tech-wt/client/build/web-mobile \
 *   node tools/verify-social-permission-runtime.mjs
 *
 * <p><b>这一格修的是什么</b>：`SocialPanelView` 早就画了「踢出」「捐献」两个按钮，
 * 但 `permissions` 在全仓库没有任何读口 —— 于是两行**永远置灰**，玩家看得见功能却永远点不动，
 * 界面还不说原因。属于本仓库反复出现的第四族缺陷（有名字、有视图、零数据源）。
 *
 * <p><b>为什么三相对照</b>：只验"亮"会假绿 —— 把 `actionEnabled` 写成 `true` 同样能过。
 * 所以同一个面板、同一份摘要，只换权限载荷：
 * <ol>
 *   <li>相位 A：真后端、真账号（没入盟）—— 两条权限请求真的按 SQUAD / ALLIANCE 各发一次，
 *       且联盟页签此时**没有**捐献行（先把"幻影"排除掉，后面才谈得上"它亮了"）</li>
 *   <li>相位 B：摘要说已入盟 + 权限说有 DONATE / KICK_MEMBER ⇒ 三档捐献与踢出**都是亮的**，
 *       点一下真的发出 `POST /alliance/donate`（此前这一步点了什么都不发生）</li>
 *   <li>相位 C：摘要一字不改，只把权限换成空表 ⇒ 同样的行变灰、灰行写原因、点了不发请求</li>
 * </ol>
 *
 * <p><b>哪些是夹具、哪些是真的</b>：dev 新号 GOLD 200，而建盟要 500（global
 * `ALLIANCE_CREATE_COST_GOLD`），建小队要主城 5 级 —— 真数据够不到"我是盟主"这一屏。
 * 于是 `/social/summary`、`/alliance/sync`、`/social/permissions` 三个读口与写请求
 * `POST /alliance/donate` 走夹具（按契约必填字段构造，经客户端自己的读路径进去），
 * **服务端存档一个字都不改**；相位 A 全部走真后端。被验的是"读口 → 编排 → 渲染 → 点击发请求"。
 */
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ARTIFACT = path.resolve(process.env.SOCIAL_PERM_ARTIFACT_ROOT ?? 'client/build/web-mobile')
const BACKEND = process.env.SOCIAL_PERM_BACKEND ?? 'http://localhost:8080'
const PORT = Number(process.env.SOCIAL_PERM_PORT ?? 8197)
const OUT = process.env.SOCIAL_PERM_OUT ?? path.resolve(process.cwd(), 'client/build/social-perm-verify')
mkdirSync(OUT, { recursive: true })

let pass = 0
let fail = 0
const log = []
const ok = (msg) => { pass += 1; log.push(`  PASS  ${msg}`) }
const bad = (msg) => { fail += 1; log.push(`  FAIL  ${msg}`) }
const check = (msg, actual, expected) => {
  if (actual === expected) {
    ok(`${msg}（${JSON.stringify(actual)}）`)
  } else {
    bad(`${msg}：期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`)
  }
}
const checkTrue = (msg, actual) => check(msg, actual, true)

/** 面板置灰与常亮的两种字色（与 SocialPanelView 的 COLOR_TEXT / COLOR_TEXT_DIM 同一份）。 */
const LIT = { r: 226, g: 214, b: 190 }
const DIM = { r: 150, g: 140, b: 124 }

const SERVER_NOW = () => Date.now()

/** 夹具状态：三相对照只动这两个字段，摘要与成员行完全一致。 */
const fixture = {
  /** 'leader' = 服务端说有权限；'none' = 说没权限（同一个职位 NONE、空表） */
  mode: 'leader',
  /** 今天捐过哪几档（捐一次加一档，用来验"用完的档位不再摆按钮"） */
  donatedTiers: [],
  donateCalls: [],
  summaryReads: 0,
}

const MEMBER = {
  id: 'p_probe_member', name: '周校', power: 18_400, role: 'MEMBER', contribution: 620,
  lastActiveAt: SERVER_NOW() - 3_600_000, squadId: null,
}

const allianceView = (version) => ({
  id: 'al_probe', name: '铁誓', tag: 'TS', leaderId: 'p_probe_me', level: 3, exp: 1_200,
  memberCap: 30, memberCount: 2,
  // 捐一次就按桩里那笔账往前走：夹具若把资金钉死，截图上就是"捐完了钱没动"，
  // 看着像面板不重算 —— 那是量具自己造出来的假症状
  fund: 8_400 + 200 * fixture.donatedTiers.length,
  techs: [], territoryCount: 4, territoryCap: 12,
  // 职位与权限两份数据要自洽：C 相若还写着「盟主」却什么都不能做，
  // 截图上就是一个真服务端不可能出现的矛盾形状，评审会先怀疑量具
  myRole: fixture.mode === 'leader' ? 'LEADER' : 'MEMBER',
  myContribution: 980 + 40 * fixture.donatedTiers.length,
  myDonateToday: fixture.donatedTiers.length,
  donateTiersUsed: [...fixture.donatedTiers], donateDailyCap: 3,
  announcement: '每晚八点集结', version, serverNow: SERVER_NOW(),
})

const summary = () => ({
  squad: null, alliance: allianceView(7), nationId: null,
  pendingInvites: 0, pendingHelps: 0, helpRemainingToday: 20, events: [],
  serverNow: SERVER_NOW(),
})

const permissions = (scope) => (fixture.mode === 'leader'
  ? {
    scope, role: 'LEADER',
    permissions: ['DONATE', 'KICK_MEMBER', 'INVITE', 'APPROVE', 'EXPAND_TERRITORY'],
    serverNow: SERVER_NOW(),
  }
  : { scope, role: 'NONE', permissions: [], serverNow: SERVER_NOW() })

if (!existsSync(path.join(ARTIFACT, 'index.html'))) {
  console.error(`没有 web 产物：${ARTIFACT}/index.html 不在。先跑一次 web-mobile 构建。`)
  process.exit(2)
}

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })

/** 后端可达性：连不上就直接退 2，不要一路判绿到结尾。 */
const alive = await fetch(`${BACKEND}/time/sync`, { method: 'POST' }).catch(() => null)
if (alive === null) {
  console.error(`后端连不上：${BACKEND}（先跑 bash scripts/dev.sh，或用 SOCIAL_PERM_BACKEND 指一台）`)
  await preview.close()
  process.exit(2)
}

const cors = (request) => ({
  'access-control-allow-origin': request.headers()['origin'] ?? '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
})

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
const deviceId = `social-perm-${Date.now()}`
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, deviceId)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') {
    errors.push(message.text())
  }
})

/** 浏览器真发出去的请求（相位 A 的判据全部从这里读）。 */
const sent = []
page.on('request', (request) => {
  const url = new URL(request.url())
  sent.push({ method: request.method(), path: url.pathname, query: url.search, backend: url.origin })
})

const NODE_PATH = 'window.cc.director.getScene().getChildByName("Canvas").getChildByName("Game")'

/** 读社交面板里的行：标题、detail、按钮是否可见、按钮文字与其字色、行的板。 */
const ROWS = `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === 'social')
  if (!root) return null
  const rows = []
  // 板的坐标一律取世界位：行内按钮的局部坐标每一行都一样（72×30 钉在行右侧同一点），
  // 拿局部坐标互相比会把所有行都判成"压叠"—— 那是量具算错，不是排版错
  const world = (node) => {
    const p = node.getWorldPosition()
    return { x: p.x, y: p.y }
  }
  const walk = (n) => {
    if (!n.activeInHierarchy) return
    if (n.name === 'SocialRow') {
      const pick = (name) => n.children.find((c) => c.name === name)
      const text = (name) => {
        const node = pick(name)
        const label = node ? node.getComponent('cc.Label') : null
        return label && label.string ? label.string : ''
      }
      const button = pick('ActionButton')
      const captionNode = button ? button.children.find((c) => c.name === 'Caption') : null
      const caption = captionNode ? captionNode.getComponent('cc.Label') : null
      const t = n.getComponent('cc.UITransform')
      const bt = button ? button.getComponent('cc.UITransform') : null
      rows.push({
        y: n.getPosition().y,
        title: text('Title'), detail: text('Detail'), value: text('Value'),
        buttonActive: button ? button.active === true : false,
        caption: caption && caption.string ? caption.string : '',
        color: caption ? [caption.color.r, caption.color.g, caption.color.b] : null,
        rowPlate: t ? { ...world(n), w: t.contentSize.width, h: t.contentSize.height } : null,
        buttonPlate: bt ? { ...world(button), w: bt.contentSize.width, h: bt.contentSize.height } : null,
      })
    }
    for (const child of n.children) walk(child)
  }
  walk(root)
  rows.sort((a, b) => b.y - a.y)
  const allLabels = []
  const collect = (n) => {
    if (!n.activeInHierarchy) return
    const l = n.getComponent('cc.Label')
    if (l && l.string) allLabels.push(l.string)
    for (const c of n.children) collect(c)
  }
  collect(root)
  return { rows, allLabels }
})()`

/** 按下社交面板里某个名字的节点（页签条用）。 */
const TAP_NAMED = (name) => `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === 'social')
  if (!root) return 'missing-root'
  let found = null
  const walk = (n) => {
    if (found !== null) return
    if (n.name === ${JSON.stringify(name)}) { found = n; return }
    for (const child of n.children) walk(child)
  }
  walk(root)
  if (found === null) return 'no-node'
  found.emit('touch-start')
  return 'tapped'
})()`

/** 第 index 行的 ActionButton（行序与 ROWS 一致：按 y 从大到小）。 */
const TAP_ROW = (index) => `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === 'social')
  if (!root) return 'missing-root'
  const rows = []
  const walk = (n) => {
    if (!n.activeInHierarchy) return
    if (n.name === 'SocialRow') rows.push(n)
    for (const child of n.children) walk(child)
  }
  walk(root)
  rows.sort((a, b) => b.getPosition().y - a.getPosition().y)
  const row = rows[${index}]
  if (row === undefined) return 'no-row'
  const button = row.children.find((c) => c.name === 'ActionButton')
  if (button === undefined || !button.active) return 'no-button'
  button.emit('touch-start')
  return 'tapped'
})()`

const readRows = async () => page.evaluate(ROWS)
const waitForRows = async (predicate, timeoutMs = 25_000) => {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    last = await page.evaluate(ROWS)
    if (last !== null && predicate(last.rows)) {
      return last
    }
    await page.waitForTimeout(400)
  }
  return last
}
/** 切到联盟页签（默认停在「小队」，不点过去就永远读不到联盟行）。 */
const openAllianceTab = async () => {
  const tapped = await page.evaluate(TAP_NAMED('Tab_alliance'))
  await page.waitForTimeout(900)
  return tapped
}
const shot = async (name) => {
  const file = path.join(OUT, `${name}.png`)
  await page.screenshot({ path: file })
  log.push(`  SHOT  ${name} → ${file}`)
}
const bootIn = async () => {
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null,
    null, { timeout: 60_000 })
  await page.waitForTimeout(3_000)
}

let boot = null
const bootListener = (message) => {
  const text = message.text()
  if (!text.startsWith('[boot] ')) {
    return
  }
  try {
    boot = JSON.parse(text.slice('[boot] '.length))
  } catch {
    // 不是那条结构化自检行，忽略
  }
}
page.on('console', bootListener)

console.log(`=== 社交权限门运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

// ============================ 相位 A：真后端、真账号 ============================
await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
await bootIn()
const playerId = typeof boot?.playerId === 'string' ? boot.playerId : ''
checkTrue(`A1 启动跑通且拿到 playerId（platform=${boot?.platform ?? '—'}）`,
  boot?.started === true && playerId !== '')

// 对照组：不存在的路径必须 404，否则下面的"读到 NONE"可能来自某个兜底处理器
const bogus = await fetch(`${BACKEND}/social/no-such-endpoint`)
check('A2 对照组：不存在的路径回 404', bogus.status, 404)

const real = await fetch(`${BACKEND}/social/permissions?scope=ALLIANCE`,
  { headers: { 'X-Player-Id': playerId } }).then((r) => r.json())
check('A3 真服务端答这条读口（code 0，不是 500）', real.code, 0)
check('A4 真账号未入盟 ⇒ 职位 NONE', real.data?.role, 'NONE')
checkTrue('A5 真账号未入盟 ⇒ 权限表为空（结论下发，不是矩阵）',
  Array.isArray(real.data?.permissions) && real.data.permissions.length === 0)

const permCalls = sent.filter((r) => r.path === '/social/permissions')
const scopes = permCalls.map((r) => new URLSearchParams(r.query).get('scope')).sort()
check('A6 面板一次把两个 scope 各拉一次（服务端一次只回一个，只拉一个就等于只验一半）',
  JSON.stringify(scopes), JSON.stringify(['ALLIANCE', 'SQUAD']))

const tapA = await openAllianceTab()
check('A7 联盟页签点得动（默认停在「小队」，不点过去根本读不到联盟行）', tapA, 'tapped')
const snapA = await waitForRows((rows) => rows.length > 0)
const donateA = (snapA?.rows ?? []).filter((r) => r.caption === '捐献')
check('A8 未入盟时画不出捐献行（先把"幻影"排除，后面才谈得上"它亮了"）', donateA.length, 0)
await shot('A-real-no-alliance')

// ============================ 夹具接线 ============================
await context.route('**/social/summary*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  fixture.summaryReads += 1
  await route.fulfill({
    status: 200,
    headers: { ...cors(request), 'content-type': 'application/json' },
    body: JSON.stringify({ code: 0, msg: '成功', data: summary(), serverNow: SERVER_NOW() }),
  })
})
await context.route('**/alliance/sync*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  await route.fulfill({
    status: 200,
    headers: { ...cors(request), 'content-type': 'application/json' },
    body: JSON.stringify({
      code: 0, msg: '成功',
      data: {
        version: 7, unchanged: false, changedMembers: [MEMBER], removedMemberIds: [],
        fund: 8_400 + 200 * fixture.donatedTiers.length,
        level: 3, memberCount: 2, announcement: '每晚八点集结',
        serverNow: SERVER_NOW(),
      },
      serverNow: SERVER_NOW(),
    }),
  })
})
await context.route('**/social/permissions*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  const scope = new URLSearchParams(new URL(request.url()).search).get('scope') ?? 'ALLIANCE'
  await route.fulfill({
    status: 200,
    headers: { ...cors(request), 'content-type': 'application/json' },
    body: JSON.stringify({ code: 0, msg: '成功', data: permissions(scope), serverNow: SERVER_NOW() }),
  })
})
await context.route('**/alliance/donate*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  const body = JSON.parse(request.postData() ?? '{}')
  fixture.donateCalls.push(body)
  if (!fixture.donatedTiers.includes(body.tier)) {
    fixture.donatedTiers.push(body.tier)
  }
  await route.fulfill({
    status: 200,
    headers: { ...cors(request), 'content-type': 'application/json' },
    body: JSON.stringify({
      code: 0, msg: '成功',
      data: {
        fundGained: 200, contributionGained: 40, fund: 8_600, contribution: 1_020,
        donateToday: fixture.donatedTiers.length, donateDailyCap: 3, serverNow: SERVER_NOW(),
      },
      serverNow: SERVER_NOW(),
    }),
  })
})

// ============================ 相位 B：已入盟 + 有权限 ============================
fixture.mode = 'leader'
fixture.donatedTiers = []
await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
await bootIn()
await openAllianceTab()
const snapB = await waitForRows((rows) => rows.some((r) => r.title === '免费捐献'))
checkTrue('B1 已入盟的摘要进了面板（首行是联盟等级与人数）',
  (snapB?.rows ?? []).some((r) => r.title.startsWith('Lv3 ·')))
const donateB = (snapB?.rows ?? []).filter((r) => r.caption === '捐献')
check('B2 三档捐献都摆出按钮（夹具给了 3 档上限、0 档已用）', donateB.length, 3)
checkTrue('B3 三档捐献**全是亮的**：字色是 COLOR_TEXT 而不是置灰那档',
  donateB.length === 3 && donateB.every((r) => JSON.stringify(r.color) === JSON.stringify([LIT.r, LIT.g, LIT.b])))
checkTrue('B4 有权限时 detail 不写"不能做这件事"',
  donateB.every((r) => !r.detail.includes('不能做这件事')))
const kickB = (snapB?.rows ?? []).filter((r) => r.caption === '踢出')
check('B5 成员行的「踢出」也摆出来了', kickB.length, 1)
checkTrue('B6 「踢出」亮：这一行此前因为读不到权限而永远点不动',
  kickB[0]?.buttonActive === true && JSON.stringify(kickB[0]?.color) === JSON.stringify([LIT.r, LIT.g, LIT.b]))
checkTrue('B7 屏上不出现权限码字样（下发的是结论，不是给玩家看的枚举）',
  (snapB?.allLabels ?? []).every((text) => !/KICK_MEMBER|DONATE|APPROVE|EXPAND_TERRITORY/.test(text)))
await shot('B-leader-permissions')

const donateCallsBefore = fixture.donateCalls.length
const tapB = await page.evaluate(TAP_ROW(1))
await page.waitForTimeout(1_500)
check('B8 点「免费捐献」那行 ⇒ 真的发出写请求（这一步此前是死的）', tapB, 'tapped')
check('B9 捐献恰好一条', fixture.donateCalls.length - donateCallsBefore, 1)
const body = fixture.donateCalls[0] ?? {}
check('B10 请求体带的是档位（0），不是数额', body.tier, 0)
checkTrue('B11 幂等键带上且长度够（短于 8 服务端直接回 1003）',
  typeof body.requestId === 'string' && body.requestId.length >= 8)
const afterDonate = await waitForRows((rows) => !rows.some((r) => r.title === '免费捐献'))
checkTrue('B12 捐完那一档不再摆按钮（夹具摘要 donateTiersUsed 已经收了 0 档）',
  (afterDonate?.rows ?? []).filter((r) => r.caption === '捐献').length === 2)
checkTrue('B13 捐完资金与贡献值跟着走：画的是**重读摘要**里的那份 8600，不是本地加一笔',
  (afterDonate?.rows ?? []).some((r) => r.value.includes('8600')))
await shot('B-after-donate')

// ============================ 相位 C：同一份摘要，权限换成空表 ============================
fixture.mode = 'none'
fixture.donatedTiers = []
await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
await bootIn()
await openAllianceTab()
const snapC = await waitForRows((rows) => rows.some((r) => r.title === '免费捐献'))
const donateC = (snapC?.rows ?? []).filter((r) => r.caption === '捐献')
check('C1 行还在（置灰而不是隐藏：看不见功能，玩家会以为游戏没有这个玩法）', donateC.length, 3)
checkTrue('C2 三档全部置灰：字色是 COLOR_TEXT_DIM',
  donateC.length === 3 && donateC.every((r) => JSON.stringify(r.color) === JSON.stringify([DIM.r, DIM.g, DIM.b])))
checkTrue('C3 灰行把原因写进 detail',
  donateC.every((r) => r.detail.includes('你当前的职位不能做这件事')))
const kickC = (snapC?.rows ?? []).find((r) => r.caption === '踢出')
checkTrue('C4 成员行的「踢出」同样灰，并且理由写在同一行上',
  kickC !== undefined && JSON.stringify(kickC.color) === JSON.stringify([DIM.r, DIM.g, DIM.b])
    && kickC.detail.includes('不能做这件事'))
const callsBeforeC = fixture.donateCalls.length
await page.evaluate(TAP_ROW(1))
await page.waitForTimeout(1_200)
check('C5 灰着的那一行点了不发请求（B 相与 C 相只差权限载荷）',
  fixture.donateCalls.length, callsBeforeC)
await shot('C-no-permissions')

// ============================ 相位 D：版面与错误 ============================
const plates = (snapC?.rows ?? []).filter((r) => r.buttonPlate !== null)
let overlap = 0
for (let i = 0; i < plates.length; i += 1) {
  for (let j = i + 1; j < plates.length; j += 1) {
    const a = plates[i].buttonPlate
    const b = plates[j].buttonPlate
    if (Math.abs(a.x - b.x) < (a.w + b.w) / 2 && Math.abs(a.y - b.y) < (a.h + b.h) / 2) {
      overlap += 1
    }
  }
}
check('D1 按钮板两两不压叠', overlap, 0)
checkTrue('D2 按钮板不出行板（72×30 的按钮在行右侧，越界就是排版算错了）',
  (snapC?.rows ?? []).filter((r) => r.buttonActive).every((r) =>
    Math.abs(r.buttonPlate.y - r.rowPlate.y) <= r.rowPlate.h / 2))
check('D3 全程零页面错误', errors.length, 0)

await browser.close()
await preview.close()

console.log(log.join('\n'))
console.log(`\n=== 判定：${pass} PASS / ${fail} FAIL（夹具读口 summary+sync+permissions，写请求 donate 打桩；相位 A 全走真后端）===`)
if (boot === null) {
  console.log('  注：没捕获到 [boot] 自检行 —— A1 之后的读数都不可信')
}
if (fixture.summaryReads === 0) {
  console.log('  注：夹具摘要一次都没被读 —— 面板可能根本没刷新，B/C 两相的读数不可信')
}
process.exit(fail === 0 ? 0 : 1)
