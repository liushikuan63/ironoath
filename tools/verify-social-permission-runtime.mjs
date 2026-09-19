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
  /** 退出联盟发出去的那几枪（B26 S3 的"两步行内确认"要数它） */
  leaveCalls: [],
  /** 转让发出去的那几枪（B26 S4） */
  transferCalls: [],
  /** 夹具成员数：D 相把它撑到 2，用来看那句"一屏画不下"的提示 */
  members: 1,
  summaryReads: 0,
}

const MEMBER = {
  id: 'p_probe_member', name: '周校', power: 18_400, role: 'MEMBER', contribution: 620,
  lastActiveAt: SERVER_NOW() - 3_600_000, squadId: null,
}
/** 第二个成员：只用来把名单撑到"一屏画不下"，验那句截断提示（D 相）。 */
const MEMBER2 = {
  id: 'p_probe_member_two', name: '吴顺', power: 9_100, role: 'MEMBER', contribution: 130,
  lastActiveAt: SERVER_NOW() - 7_200_000, squadId: null,
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
    permissions: ['DONATE', 'KICK_MEMBER', 'INVITE', 'APPROVE', 'EXPAND_TERRITORY',
      'DISBAND_ALLIANCE', 'DISBAND_SQUAD', 'TRANSFER_LEADER'],
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
      const second = pick('ActionButton2')
      const captionNode = button ? button.children.find((c) => c.name === 'Caption') : null
      const caption = captionNode ? captionNode.getComponent('cc.Label') : null
      const secondNode = second ? second.children.find((c) => c.name === 'Caption') : null
      const caption2 = secondNode ? secondNode.getComponent('cc.Label') : null
      const t = n.getComponent('cc.UITransform')
      const bt = button ? button.getComponent('cc.UITransform') : null
      const b2t = second ? second.getComponent('cc.UITransform') : null
      rows.push({
        y: n.getPosition().y,
        title: text('Title'), detail: text('Detail'), value: text('Value'),
        buttonActive: button ? button.active === true : false,
        caption: caption && caption.string ? caption.string : '',
        color: caption ? [caption.color.r, caption.color.g, caption.color.b] : null,
        secondActive: second ? second.active === true : false,
        caption2Text: caption2 && caption2.string ? caption2.string : '',
        color2: caption2 ? [caption2.color.r, caption2.color.g, caption2.color.b] : null,
        rowPlate: t ? { ...world(n), w: t.contentSize.width, h: t.contentSize.height } : null,
        buttonPlate: bt ? { ...world(button), w: bt.contentSize.width, h: bt.contentSize.height } : null,
        button2Plate: b2t ? { ...world(second), w: b2t.contentSize.width, h: b2t.contentSize.height } : null,
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

/** 点某一行的**第二颗**按钮（转让挂在成员行上）。 */
const TAP_CAPTION2 = (text) => `(() => {
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
  for (const row of rows) {
    const second = row.children.find((c) => c.name === 'ActionButton2')
    const caption = second ? second.children.find((c) => c.name === 'Caption') : null
    const label = caption ? caption.getComponent('cc.Label') : null
    if (label && label.string === ${JSON.stringify(text)} && second.active) {
      second.emit('touch-start')
      return 'tapped'
    }
  }
  return 'no-row'
})()`

/** 按按钮文字找到那一行再点它（行序会随权限与捐献档位变化，按文字找才不脆）。 */
const TAP_CAPTION = (text) => `(() => {
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
  for (const row of rows) {
    const button = row.children.find((c) => c.name === 'ActionButton')
    const caption = button ? button.children.find((c) => c.name === 'Caption') : null
    const label = caption ? caption.getComponent('cc.Label') : null
    if (label && label.string === ${JSON.stringify(text)} && button.active) {
      button.emit('touch-start')
      return 'tapped'
    }
  }
  return 'no-row'
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
        version: 7, unchanged: false,
        changedMembers: [MEMBER, ...(fixture.members === 2 ? [MEMBER2] : [])],
        removedMemberIds: [],
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
// 转让打桩（B26 S4）
await context.route('**/alliance/transfer*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  fixture.transferCalls.push(JSON.parse(request.postData() ?? '{}'))
  await route.fulfill({
    status: 200,
    headers: { ...cors(request), 'content-type': 'application/json' },
    body: JSON.stringify({
      code: 0, msg: '成功',
      data: {
        squad: null, alliance: null, nationId: null, pendingInvites: 0, pendingHelps: 0,
        helpRemainingToday: 20, events: [], serverNow: SERVER_NOW(),
      },
      serverNow: SERVER_NOW(),
    }),
  })
})
// 退出联盟打桩：B26 S3 的"两步行内确认"要数它发了几枪
await context.route('**/alliance/leave*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  fixture.leaveCalls.push(JSON.parse(request.postData() ?? '{}'))
  await route.fulfill({
    status: 200,
    headers: { ...cors(request), 'content-type': 'application/json' },
    body: JSON.stringify({
      code: 0, msg: '成功',
      data: {
        squad: null, alliance: null, nationId: null, pendingInvites: 0, pendingHelps: 0,
        helpRemainingToday: 20, events: [], serverNow: SERVER_NOW(),
      },
      serverNow: SERVER_NOW(),
    }),
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

// ============================ 相位 B：已入盟 + 有权限 ============================fixture.mode = 'leader'
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
const tapB = await page.evaluate(TAP_CAPTION('捐献'))
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

// ---- B26 S3：退出与解散那一行（一行两颗按钮） ----
const exitB = (afterDonate?.rows ?? []).find(r => r.caption === '退出联盟')
checkTrue('B14 「退出联盟」亮着（服务端没有 LEAVE 这一位，那是每个成员的权利）',
  exitB !== undefined && JSON.stringify(exitB.color) === JSON.stringify([LIT.r, LIT.g, LIT.b]))
checkTrue('B15 同一行第二颗「解散联盟」也亮：夹具这次给了 DISBAND_ALLIANCE',
  exitB !== undefined && exitB.secondActive === true
    && JSON.stringify(exitB.color2) === JSON.stringify([LIT.r, LIT.g, LIT.b]))
checkTrue('B15b 两颗按钮不叠在同一个点上（池化行复用的老毛病：第二颗默认与第一颗同位）',
  exitB !== undefined && Math.abs(exitB.buttonPlate.x - exitB.button2Plate.x) >= 70)
const leaveCallsBefore = fixture.leaveCalls.length
check('B16 点「退出联盟」第一下', await page.evaluate(TAP_CAPTION('退出联盟')), 'tapped')
await page.waitForTimeout(900)
check('B17 第一下不发请求（不可逆动作不该一次误触就生效）', fixture.leaveCalls.length, leaveCallsBefore)
const armedRow = (await readRows())?.rows?.find(r => r.caption === '确认退出联盟')
checkTrue('B18 第一下之后那一行改字成「确认退出联盟」并写着下一次会发生什么',
  armedRow !== undefined && armedRow.detail.includes('再点一次真的离开'))
check('B19 第二下才真发', await page.evaluate(TAP_CAPTION('确认退出联盟')), 'tapped')
await page.waitForTimeout(1_200)
check('B20 恰好一条 /alliance/leave', fixture.leaveCalls.length - leaveCallsBefore, 1)
await shot('B-after-leave')
// ---- B26 S4：成员行上的「转让」（第二颗按钮） ----
const memberB = (afterDonate?.rows ?? []).find(r => r.caption === '踢出')
checkTrue('B21 成员行第二颗是「转让」且亮着（夹具给了 TRANSFER_LEADER）',
  memberB !== undefined && memberB.caption2Text === '转让'
    && JSON.stringify(memberB.color2) === JSON.stringify([LIT.r, LIT.g, LIT.b]))
const transferBefore = fixture.transferCalls.length
check('B22 第一下', await page.evaluate(TAP_CAPTION2('转让')), 'tapped')
await page.waitForTimeout(900)
check('B23 第一下不发请求', fixture.transferCalls.length, transferBefore)
const armedTransferRow = ((await readRows())?.rows ?? []).find(r => r.caption2Text === '确认转让')
checkTrue('B24 第一下之后那颗键改字成「确认转让」并写清后果',
  armedTransferRow !== undefined && armedTransferRow.detail.includes('自己降为成员'))
check('B25 第二下才真发', await page.evaluate(TAP_CAPTION2('确认转让')), 'tapped')
await page.waitForTimeout(1_200)
check('B26 恰好一条 /alliance/transfer，且带的就是那一行的成员 id',
  `${fixture.transferCalls.length - transferBefore}/${fixture.transferCalls[0]?.memberId}`,
  `1/${MEMBER.id}`)
await shot('B-two-step-transfer')
const headerAfter = ((await readRows())?.rows ?? []).find(r => r.title.startsWith('Lv3'))
checkTrue('B27 联盟概况行上一颗按钮都不该有（池化复用：上一行留下的那颗「转让」必须显式收起 —— '
  + '第一版就是凭空多出一颗转让键，而所有读数都是绿的）',
  headerAfter !== undefined && headerAfter.secondActive === false
    && headerAfter.buttonActive === false)

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
await page.evaluate(TAP_CAPTION('捐献'))
await page.waitForTimeout(1_200)
check('C5 灰着的那一行点了不发请求（B 相与 C 相只差权限载荷）',
  fixture.donateCalls.length, callsBeforeC)
await shot('C-no-permissions')
const leaveC = (snapC?.rows ?? []).find(r => r.caption === '退出联盟')
checkTrue('C6 权限清空后「退出联盟」**仍然亮** —— 这条就是那条不对称的运行时证据：'
  + '把退出也挂到权限上，就会把成员关在他想退的联盟里',
  leaveC !== undefined && JSON.stringify(leaveC.color) === JSON.stringify([LIT.r, LIT.g, LIT.b]))
checkTrue('C7 同一行第二颗「解散联盟」灰着并写原因（DISBAND_ALLIANCE 是服务端下发的位，不是客户端猜的职位）',
  leaveC !== undefined && JSON.stringify(leaveC.color2) === JSON.stringify([DIM.r, DIM.g, DIM.b])
    && leaveC.detail.includes('你当前的职位不能做这件事'))
const memberC = (snapC?.rows ?? []).find(r => r.caption === '踢出')
checkTrue('C8 「转让」跟着权限一起灰（它看的是 TRANSFER_LEADER 那一位，不是"我是盟主"这句猜测）',
  memberC !== undefined && memberC.caption2Text === '转让'
    && JSON.stringify(memberC.color2) === JSON.stringify([DIM.r, DIM.g, DIM.b]))

// ============================ 相位 D：版面与错误 ============================
/**
 * 最后一行与导航条：导航条的上沿**从场景里实测**（写死数字就是松判据 —— 本仓库在别的面板
 * 踩过"写死 7 行把第 5 行压进导航条"）。C 相那一屏有 6 行（三档捐献 + 成员 + 退出 + 解散），
 * 正是行数最多的一屏。
 */
const NAV = `(() => {
  const scene = window.cc.director.getScene()
  let nav = null
  const find = (n) => {
    if (nav !== null) return
    if (n.name === 'NavBar') { nav = n; return }
    for (const c of n.children) find(c)
  }
  find(scene)
  if (nav === null) return null
  const nt = nav.getComponent('cc.UITransform')
  const np = nav.getWorldPosition()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  const root = game.children.find((c) => c.name === 'social')
  let lowest = null
  const walk = (n) => {
    if (!n.activeInHierarchy) return
    if (n.name === 'SocialRow') {
      const t = n.getComponent('cc.UITransform')
      const p = n.getWorldPosition()
      const bottom = p.y - t.contentSize.height / 2
      if (lowest === null || bottom < lowest) lowest = bottom
    }
    for (const c of n.children) walk(c)
  }
  if (root !== undefined) walk(root)
  return { navTop: np.y + nt.contentSize.height / 2, lowestRowBottom: lowest }
})()`
const nav = await page.evaluate(NAV)
checkTrue(`D0 最后一行不被导航条压住（实测：导航条上沿 ${nav?.navTop?.toFixed(1)}，`
  + `行底 ${nav?.lowestRowBottom?.toFixed(1)}）`,
  nav !== null && nav.lowestRowBottom !== null && nav.lowestRowBottom > nav.navTop)
const exitD = (snapC?.rows ?? []).find(r => r.caption === '退出联盟')
checkTrue('D0b 固定那一行（退出与解散）排在成员名单之前，名单再长也挤不掉它',
  exitD !== undefined && exitD.secondActive === true)
// 把名单撑到两个人：一屏只有 6 格，画不下的那一格必须换成提示，而不是静默少画一行
fixture.members = 2
await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
await bootIn()
await openAllianceTab()
const snapD = await waitForRows(rows => rows.some(r => /^另有 \d+ 项未显示/.test(r.title)))
checkTrue('D0c 装不下时最后一格改成「另有 N 项未显示」（静默截等于把功能藏起来）',
  (snapD?.rows ?? []).some(r => /^另有 \d+ 项未显示/.test(r.title)))
checkTrue('D0d 被挤掉的只能是不定长的名单：固定那一行仍在屏上',
  (snapD?.rows ?? []).some(r => r.caption === '退出联盟'))
await shot('D-truncated-list')
fixture.members = 1
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
