#!/usr/bin/env node
/**
 * 职责：创建小队/联盟这一整条链路的**运行时**验收（B26 S2）—— 从社交页那一行「创建」点进去，
 *       真键盘敲名字，点确认，看请求真发出去、表单关掉、三道门跟着重拉。
 * 依赖：node、playwright、一台能登录的后端（默认 8180）、已构建的 web-mobile 产物。
 *
 * 用法：
 *   SOCIAL_CREATE_BACKEND=http://localhost:8180 \
 *   SOCIAL_CREATE_ARTIFACT_ROOT=/d/tmp/tech-wt/client/build/web-mobile \
 *   node tools/verify-social-create-runtime.mjs
 *
 * <p><b>这一格修的是什么</b>：社交面板的联盟页签在"没加入"时**整块空白**（`allianceDrafts` 直接
 * `return []`），既没有一行说明，也没有任何入口 —— 玩家看不出这游戏有联盟玩法。同时那句门槛
 * 原先是客户端抄的（`SocialPanel.ts` 里写死「主城 5 级且开服第 1 天起可创建小队」），
 * 表一改界面就是说谎。现在门槛与消耗都来自 `GET /social/createPolicy`。
 *
 * <p><b>两相怎么分</b>：
 * <ol>
 *   <li>**A 相全走真后端**：新号主城 1 级 ⇒ 两道门都关着。这一相验的是"行画出来了、
 *       灰着、灰的原因是服务端那句话原样、点了不发请求"。真数据够得到的部分全部真跑。</li>
 *   <li>**B 相换政策夹具**：主城升到 5 级要 ~15 分钟真实时间（实测：139 秒到 4 级，
 *       然后撞 `code=3006 资源不足`，木料增速约 1/秒），探针不该等半小时。
 *       于是 `/social/createPolicy` 换成"能建"、`POST /squad/create` 打到桩上
 *       （**不碰服务端存档**）—— 被验的是"行亮起来 → 打开表单 → 打字 → 确认发出对的请求
 *       → 关掉并重拉门"这半条客户端链路。</li>
 * </ol>
 * "建得成"本身不在这里验：`SocialEndpointTest` 里有 `newPlayer(5)` 走同一套服务真建队真建盟的用例
 * （含扣 500 金币那一条），而 B26-S1 的权限探针已经证明建成之后按钮会亮 —— 两截接起来才是整条。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const OUT = process.env.SOCIAL_CREATE_OUT ?? path.resolve(process.cwd(), 'client/build/social-create-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.SOCIAL_CREATE_PORT ?? 8198)
const BACKEND = process.env.SOCIAL_CREATE_BACKEND ?? 'http://localhost:8080'
const ARTIFACT = process.env.SOCIAL_CREATE_ARTIFACT_ROOT ?? 'client/build/web-mobile'

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
const checkTrue = (msg, actual) => check(msg, actual === true, true)

/** 与 SocialPanelView 的 COLOR_TEXT / COLOR_TEXT_DIM 同一份。 */
const LIT = [226, 214, 190]
const DIM = [150, 140, 124]

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
const alive = await fetch(`${BACKEND}/time/sync`, { method: 'POST' }).catch(() => null)
if (alive === null) {
  console.error(`后端连不上：${BACKEND}`)
  await preview.close()
  process.exit(2)
}

/** 夹具态：政策能不能建、写请求打了几次。 */
const fixture = {
  canCreate: false,
  createCalls: [],
  policyReads: 0,
  /** 可申请列表被读了几次（E8 要证明申请完真的重拉） */
  listReads: 0,
  /** 申请那一枪打桩收到的 body */
  applyCalls: [],
  /** 桩这边记的"我申请过哪些"，用来让下一次列表把那一行翻成「已申请」 */
  appliedIds: [],
  /** F 相：可加入小队列表被读了几次、加入那一枪收到的 body */
  squadListReads: 0,
  joinCalls: [],
  summaryHits: 0,
  /** G 相：摘要里要不要带上"我有一个联盟"（审核只对本盟开放） */
  allianceVisible: false,
  /** G 相：有没有 APPROVE_APPLICATION 这一位 */
  canReview: true,
  /** 桩这边的待审队列（批一条少一条，用来证明那一行是自己消失的） */
  applicants: [
    { playerId: 'p_probe_a', nickname: '阿铁', mainCityLevel: 7 },
    { playerId: 'p_probe_b', nickname: '老周', mainCityLevel: 3 },
  ],
  appReads: 0,
  reviewCalls: [],
  /** F 相：桩这边记的"我已经进了哪支"，摘要据此决定还拉不拉列表 */
  joinedId: null,
}

const policyBody = (scope) => ({
  canCreate: fixture.canCreate,
  costGold: scope === 'ALLIANCE' ? 500 : 0,
  costResource: 'GOLD',
  reason: fixture.canCreate ? null
    : (scope === 'ALLIANCE' ? '需要主城 10 级，当前 1 级' : '需要主城 5 级，当前 1 级'),
})

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
const deviceId = `social-create-${Date.now()}`
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
let boot = null
const sent = []
page.on('request', (request) => {
  const url = new URL(request.url())
  sent.push({ method: request.method(), path: url.pathname })
  if (request.method() === 'POST' && (url.pathname === '/squad/create' || url.pathname === '/alliance/create')) {
    try {
      fixture.createCalls.push({ path: url.pathname, body: JSON.parse(request.postData() ?? '{}') })
    } catch {
      fixture.createCalls.push({ path: url.pathname, body: {} })
    }
  }
})
page.on('console', (message) => {
  const text = message.text()
  if (text.startsWith('[boot] ')) {
    try {
      boot = JSON.parse(text.slice('[boot] '.length))
    } catch {
      // 不是那条结构化自检行
    }
  }
})

const NODE_PATH = 'window.cc.director.getScene().getChildByName("Canvas").getChildByName("Game")'

/** 读社交面板里的行（标题 / detail / 按钮文字与字色）。 */
const ROWS = `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === 'social')
  if (!root) return null
  const rows = []
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
      const caption = button ? button.children.find((c) => c.name === 'Caption') : null
      const label = caption ? caption.getComponent('cc.Label') : null
      rows.push({
        y: n.getPosition().y,
        title: text('Title'), detail: text('Detail'),
        buttonActive: button ? button.active === true : false,
        caption: label && label.string ? label.string : '',
        color: label ? [label.color.r, label.color.g, label.color.b] : null,
      })
    }
    for (const child of n.children) walk(child)
  }
  walk(root)
  rows.sort((a, b) => b.y - a.y)
  const labels = []
  const collect = (n) => {
    if (!n.activeInHierarchy) return
    const l = n.getComponent('cc.Label')
    if (l && l.string) labels.push(l.string)
    for (const c of n.children) collect(c)
  }
  collect(root)
  return { rows, labels }
})()`

/** 读创建弹层：每块板的字与字色、输入框里的当前文本、按钮的触摸有没有挂上。 */
const OVERLAY = `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === 'socialCreate')
  if (!root) return null
  const plates = []
  const labels = []
  const captions = []
  let nameText = null
  let tagText = null
  const walk = (n) => {
    if (!n.activeInHierarchy) return
    const t = n.getComponent('cc.UITransform')
    const box = n.getComponent('cc.EditBox')
    if (box) {
      if (n.name === 'name-field') nameText = box.string
      if (n.name === 'tag-field') tagText = box.string
    }
    const label = n.getComponent('cc.Label')
    if (label && label.string) {
      labels.push({ text: label.string, color: [label.color.r, label.color.g, label.color.b] })
    }
    if (/^(name-field|tag-field|cancel|submit|title|cost|hint)$/.test(n.name) && t) {
      const p = n.getWorldPosition()
      plates.push({ name: n.name, x: p.x, y: p.y, w: t.contentSize.width, h: t.contentSize.height })
    }
    if (/caption$/.test(n.name) && t) {
      const p = n.getWorldPosition()
      captions.push({ name: n.name, x: p.x, w: t.contentSize.width, anchorX: t.anchorX })
    }
    for (const c of n.children) walk(c)
  }
  walk(root)
  return { active: root.active === true, plates, labels, captions, nameText, tagText,
    visibleWidth: window.cc.view.getVisibleSize().width,
    submitTouched: (() => {
      const submit = (() => {
        let found = null
        const find = (n) => {
          if (found !== null) return
          if (n.name === 'submit') { found = n; return }
          for (const c of n.children) find(c)
        }
        find(root)
        return found
      })()
      return submit === null ? null : submit.hasEventListener('touch-start')
    })() }
})()`

const TAP_NAMED = (rootName, name) => `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === ${JSON.stringify(rootName)})
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

/** 点第 index 行（按 y 排序）里的 ActionButton。 */
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

/** 按按钮文字找那一行再点它（列表行序与状态有关，按索引点会点错）。 */
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

/** 聚焦某个输入框（真键盘要引擎自己建的那个 DOM 输入框拿到焦点）。 */
const FOCUS_FIELD = (nodeName) => `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === 'socialCreate')
  if (!root) return 'missing-root'
  let found = null
  const walk = (n) => {
    if (found !== null) return
    if (n.name === ${JSON.stringify(nodeName)}) { found = n; return }
    for (const c of n.children) walk(c)
  }
  walk(root)
  if (found === null) return 'no-node'
  const box = found.getComponent('cc.EditBox')
  if (!box) return 'no-editbox'
  box.setFocus(true)
  return 'focused'
})()`

const readRows = () => page.evaluate(ROWS)
const readOverlay = () => page.evaluate(OVERLAY)
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
const openSocial = async () => {
  await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
  await bootIn()
}

console.log(`=== 创建小队/联盟运行时验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

// ============================ A 相：真后端，两道门都关着 ============================
await openSocial()
const playerId = typeof boot?.playerId === 'string' ? boot.playerId : ''
checkTrue(`A1 启动跑通且拿到 playerId（platform=${boot?.platform ?? '—'}）`,
  boot?.started === true && playerId !== '')

const bogus = await fetch(`${BACKEND}/social/no-such-endpoint`)
check('A2 对照组：不存在的路径回 404', bogus.status, 404)
const real = await fetch(`${BACKEND}/social/createPolicy`, { headers: { 'X-Player-Id': playerId } })
  .then((r) => r.json())
check('A3 真服务端答这条读口（code 0）', real.code, 0)
check('A4 新号主城 1 级 ⇒ 小队门是关的', real.data?.squad?.canCreate, false)
checkTrue('A5 门槛那句由服务端给出（含「主城 5 级」与「当前 1 级」）',
  String(real.data?.squad?.reason ?? '').includes('主城 5 级')
    && String(real.data?.squad?.reason ?? '').includes('当前 1 级'))
check('A6 联盟收 500（global.ALLIANCE_CREATE_COST_GOLD），小队 0',
  `${real.data?.alliance?.costGold}/${real.data?.squad?.costGold}`, '500/0')

const tapAllianceTab = await page.evaluate(TAP_NAMED('social', 'Tab_alliance'))
check('A7 联盟页签点得动', tapAllianceTab, 'tapped')
const snapA = await waitForRows(rows => rows.some(r => r.caption === '创建联盟'))
const createA = (snapA?.rows ?? []).find(r => r.caption === '创建联盟')
checkTrue('A8 未入盟的联盟页**不再是一片空白**：有一行「创建联盟」（此前整块空白，玩家以为没这玩法）',
  createA !== undefined)
checkTrue('A9 那一行灰着，且 detail 就是服务端那句（客户端不重写门槛）',
  JSON.stringify(createA?.color) === JSON.stringify(DIM)
    && String(createA?.detail ?? '').includes('主城 10 级'))
const realList = await fetch(`${BACKEND}/alliance/list`, { headers: { 'X-Player-Id': playerId } })
  .then((r) => r.json())
check('A9b 真服务端答这条发现型读口（code 0）', realList.code, 0)
checkTrue('A9c 响应有界：带 total 与 limit（界面才写得出"共 X 个，只显示前 Y 个"）',
  typeof realList.data?.total === 'number' && typeof realList.data?.limit === 'number'
    && Array.isArray(realList.data?.alliances)
    && realList.data.alliances.length <= realList.data.limit)
checkTrue('A9d 未入盟那一屏把"有没有得申请"说清楚了（不是只有一句"未加入联盟"）',
  (snapA?.labels ?? []).some(text => /^还没有人建立联盟$|^共 \d+ 个联盟，这里只显示前 \d+ 个$/.test(text)))
const callsBeforeA = fixture.createCalls.length
await page.evaluate(TAP_ROW(0))
await page.waitForTimeout(900)
check('A10 灰着的那一行点了不发请求', fixture.createCalls.length, callsBeforeA)
await shot('A-locked-real-backend')
const snapASquad = await (async () => {
  await page.evaluate(TAP_NAMED('social', 'Tab_squad'))
  await page.waitForTimeout(900)
  return waitForRows(rows => rows.some(r => r.caption === '创建小队'))
})()
const createASquad = (snapASquad?.rows ?? []).find(r => r.caption === '创建小队')
checkTrue('A11 小队页同一套（那句是 5 级，不是把联盟那句复用过来）',
  String(createASquad?.detail ?? '').includes('主城 5 级')
    && !String(createASquad?.detail ?? '').includes('主城 10 级'))
checkTrue('A12 屏上不出现资源枚举与权限码原文（名字走 `ui/ResourceNames` 那唯一一份）',
  (snapASquad?.labels ?? []).every(text => !/\bGOLD\b|\bSQUAD\b|\bALLIANCE\b|KICK_MEMBER/.test(text)))
// 这一条只走真后端：夹具里再怎么写都不证明那个端点真的在、真的回得来。
const squadListFired = sent.filter(c => c.path === '/squad/list').length
checkTrue(`A13 打开社交页真的发了一次 GET /squad/list（实测 ${squadListFired} 次）`,
  squadListFired >= 1)
checkTrue('A14 真后端上的小队页：要么列出可加入的队，要么老实说「还没有人建立小队」——'
  + '两种都是真话，画一张既没行也没说明的空屏才是假话',
  (snapASquad?.rows ?? []).some(r => r.caption === '加入')
    || (snapASquad?.labels ?? []).some(text => text.includes('还没有人建立小队')))

// ============================ B 相：政策换成"能建"，写请求打桩 ============================
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
await context.route('**/social/createPolicy*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  fixture.policyReads += 1
  await reply(route, {
    squad: policyBody('SQUAD'), alliance: policyBody('ALLIANCE'), serverNow: Date.now(),
  })
})
await context.route('**/squad/create*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, {
    squad: null, alliance: null, nationId: null, pendingInvites: 0, pendingHelps: 0,
    helpRemainingToday: 20, events: [], serverNow: Date.now(),
  })
})

fixture.canCreate = true
await openSocial()
await page.evaluate(TAP_NAMED('social', 'Tab_squad'))
const snapB = await waitForRows(rows => rows.some(r => r.caption === '创建小队'))
const createB = (snapB?.rows ?? []).find(r => r.caption === '创建小队')
checkTrue('B1 只换政策那一份数据，那一行就亮了（A 相与 B 相只差它）',
  createB !== undefined && JSON.stringify(createB.color) === JSON.stringify(LIT))
checkTrue('B2 亮了以后行上不再写门槛，改写消耗（小队是 0 ⇒「不消耗资源」）',
  createB.detail === '不消耗资源')
await shot('B-unlocked-row')

const tapsBefore = fixture.createCalls.length
check('B3 点那一行', await page.evaluate(TAP_ROW(0)), 'tapped')
await page.waitForTimeout(900)
const overlay1 = await readOverlay()
checkTrue('B4 表单弹层挂上且激活', overlay1 !== null && overlay1.active)
checkTrue('B5 标题是「创建小队」', overlay1.labels.some(l => l.text === '创建小队'))
check('B6 小队没有标签输入框（服务端也不收，画一个填了没用的框是骗人）',
  overlay1.plates.filter(p => p.name === 'tag-field').length, 0)
check('B7 名字没填时确认不挂触摸（点了不发那一枪）', overlay1.submitTouched, false)
check('B8 打开表单没顺手多发一次创建请求', fixture.createCalls.length, tapsBefore)

check('B9 聚焦名字框', await page.evaluate(FOCUS_FIELD('name-field')), 'focused')
const typed = '五个人的队'
await page.keyboard.type(typed, { delay: 40 })
await page.waitForTimeout(900)
const overlay2 = await readOverlay()
check('B10 打完字，输入框里还是那一整串（重画没把字吃掉）', overlay2.nameText, typed)
check('B11 填上名字后确认挂上触摸', overlay2.submitTouched, true)
check('B12 打字一条请求都不发', fixture.createCalls.length, tapsBefore)
await shot('B-form-typed')

const policyReadsBefore = fixture.policyReads
check('B13 点确认', await page.evaluate(TAP_NAMED('socialCreate', 'submit')), 'tapped')
await page.waitForTimeout(1_800)
const sentCreate = fixture.createCalls.filter(c => c.path === '/squad/create')
check('B14 真的发出那一枪（恰好一条）', sentCreate.length, 1)
check('B15 请求体是 trim 过的小队名', sentCreate[0]?.body?.name, typed)
checkTrue('B16 幂等键带上且长度够（短于 8 服务端直接回 1003）',
  typeof sentCreate[0]?.body?.requestId === 'string'
    && sentCreate[0].body.requestId.length >= 8)
const overlay3 = await readOverlay()
checkTrue('B17 建成即关表单（留着会让玩家再点一次）', overlay3.active === false)
checkTrue('B18 发完重拉了三道门（职位变了，按钮状态不能停在旧的那份）',
  fixture.policyReads >= policyReadsBefore + 1)
await shot('B-after-create')

// ============================ C 相：联盟表单要有标签栏 ============================
await openSocial()
await page.evaluate(TAP_NAMED('social', 'Tab_alliance'))
await waitForRows(rows => rows.some(r => r.caption === '创建联盟'))
await page.evaluate(TAP_ROW(0))
await page.waitForTimeout(900)
const allianceForm = await readOverlay()
checkTrue('C1 联盟表单有标签那一栏（服务端 `联盟名与标签都不得为空` 两条都判）',
  allianceForm.plates.some(p => p.name === 'tag-field'))
check('C2 只填名字不给确认', allianceForm.submitTouched, false)
checkTrue('C3 行上写的是政策的消耗（500 金币起步，客户端不抄表；余额不够时后面还会跟「还差 N」）',
  allianceForm.labels.some(l => l.text.startsWith('消耗 500 金币')))
await shot('C-alliance-form')

// ============================ E 相：可申请联盟那一屏（列表夹具 + 申请打桩） ============================
// 真服务端此刻一个联盟都没有（A9d 刚验过那句"还没有人建立联盟"），
// 而"满 / 已申请 / 能申"三种状态要同时出现才验得出判定，所以这里换一份列表夹具；
// 申请那一枪打到桩上，**服务端存档不动**。
await context.route('**/alliance/list*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  fixture.listReads += 1
  await reply(route, {
    alliances: [
      { id: 'al_probe_open', name: '铁誓', tag: 'TS', level: 3, memberCount: 4, memberCap: 30,
        full: false, applied: fixture.appliedIds.includes('al_probe_open') },
      { id: 'al_probe_full', name: '铜雀', tag: 'QQ', level: 5, memberCount: 60, memberCap: 60,
        full: true, applied: false },
      { id: 'al_probe_applied', name: '雪岭', tag: 'XL', level: 2, memberCount: 12, memberCap: 20,
        full: false, applied: true },
    ],
    total: 57, limit: 20, serverNow: Date.now(),
  })
})
await context.route('**/alliance/apply*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  const body = JSON.parse(request.postData() ?? '{}')
  fixture.applyCalls.push(body)
  if (!fixture.appliedIds.includes(body.allianceId)) {
    fixture.appliedIds.push(body.allianceId)
  }
  await reply(route, {
    squad: null, alliance: null, nationId: null, pendingInvites: 0, pendingHelps: 0,
    helpRemainingToday: 20, events: [], serverNow: Date.now(),
  })
})

fixture.canCreate = false
await openSocial()
await page.evaluate(TAP_NAMED('social', 'Tab_alliance'))
const snapE = await waitForRows(rows => rows.some(r => r.caption === '申请加入'))
const rowTexts = (snapE?.rows ?? []).map(r => `${r.title}|${r.caption}|${r.detail}`)
checkTrue('E1 三种状态三样按钮：能申的那行亮着「申请加入」',
  rowTexts.some(t => t.includes('[TS] 铁誓') && t.includes('|申请加入|')))
checkTrue('E2 「已满」那行灰着并写原因（不是只把按钮灰掉让玩家猜）',
  (snapE?.rows ?? []).some(r => r.caption === '已满'
    && JSON.stringify(r.color) === JSON.stringify(DIM)
    && r.detail.includes('位置满了')))
checkTrue('E3 「已申请」那行灰着并写「等盟主或官员审核」',
  (snapE?.rows ?? []).some(r => r.caption === '已申请' && r.detail.includes('等盟主或官员审核')))
checkTrue('E4 有界列表说清了总量（屏上该有那句「共 57 个联盟，这里只显示前 3 个」）'
  + ` 实际带"联盟"的标签=${JSON.stringify((snapE?.labels ?? []).filter(t => t.includes('联盟')).slice(0, 6))}`,
  (snapE?.labels ?? []).some(text => text.includes('共 57 个联盟')))
const applyBefore = fixture.applyCalls.length
await page.evaluate(TAP_CAPTION('已满'))
await page.waitForTimeout(800)
check('E5 点「已满」那一行不发请求', fixture.applyCalls.length, applyBefore)
await page.evaluate(TAP_CAPTION('申请加入'))
await page.waitForTimeout(1_600)
check('E6 点「申请加入」发恰好一条', fixture.applyCalls.length - applyBefore, 1)
check('E7 请求体带的是列表里那个联盟的 id', fixture.applyCalls[applyBefore]?.allianceId, 'al_probe_open')
checkTrue('E8 申请完列表重拉，那一行自己变成「已申请」（不是客户端本地改的字）',
  fixture.listReads >= 2
    && ((await readRows())?.rows ?? []).some(r => r.title.includes('[TS] 铁誓') && r.caption === '已申请'))
checkTrue('E9 屏上不出现联盟 id 与字段名（id 只进请求，不进玩家的眼睛）',
  ((await readRows())?.labels ?? []).every(text => !/al_probe|memberCap|applied/.test(text)))
await shot('E-discovery-apply')

// ============================ F 相：可加入小队那一屏（B26 S7）============================
// 与 E 相同一族：真后端上凑不出「能加 + 已满」两态（要么没人建队，要么只有别的探针留下的队），
// 所以列表换夹具、加入那一枪打桩。**桩回的摘要带上一支真小队** —— 只有这样才验得到
// 「进了队之后那一屏自己收起」，而不只是列表画得对。
await context.route('**/squad/list*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  fixture.squadListReads += 1
  await reply(route, {
    squads: [
      { id: 'sq_probe_open', name: '铁血队', level: 2, memberCount: 3, memberCap: 5,
        full: false },
      { id: 'sq_probe_full', name: '雪岭队', level: 4, memberCount: 10, memberCap: 10,
        full: true },
    ],
    total: 31, limit: 20, serverNow: Date.now(),
  })
})
await context.route('**/squad/join*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  const body = JSON.parse(request.postData() ?? '{}')
  fixture.joinCalls.push(body)
  fixture.joinedId = body.squadId
  await reply(route, joinedSummary(false))
})
// 摘要必须是状态相关的桩：加入之后服务端就会说"我有队了"，客户端正是读这一项决定
// 「可加入那一屏要不要收起来、还要不要再拉列表」。上一版只把 /squad/join 打了桩、
// 摘要仍走真服务端（它当然还说没队），于是"那一屏该收起"永远验不到 —— F8 就是这么红的。
await context.route('**/social/summary*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  fixture.summaryHits += 1
  await reply(route, joinedSummary(fixture.joinedId === null))
})
/** 加入前后的同一份摘要：`squadless=true` 就按"我还没队"回，否则回我刚进的那支队。 */
const joinedSummary = (squadless) => ({
  squad: squadless ? null : {
    id: 'sq_probe_mine', name: '铁血队', leaderId: 'p_other',
    members: [{ id: 'p_other', name: '阿铁', power: 12_400, lastActiveAt: Date.now() - 60_000,
      role: 'LEADER', mainCityLevel: 8 }],
    level: 2, exp: 60, expToNext: 140, memberCap: 5, shopLevel: 1, squadCoin: 0,
    allianceId: null, isSubSquad: false, dailyQuestProgress: 0, dailyQuestTarget: 20,
    serverNow: Date.now(),
  },
  alliance: fixture.allianceVisible ? {
    id: 'al_probe_mine', name: '铁誓', tag: 'TS', leaderId: 'p_me', level: 3, exp: 120,
    memberCap: 30, memberCount: 4, fund: 900, techs: [], territoryCount: 1, territoryCap: 12,
    myRole: 'LEADER', myContribution: 40, myDonateToday: 0, donateTiersUsed: [],
    donateDailyCap: 3, announcement: '', version: 2, serverNow: Date.now(),
  } : null,
  nationId: null, pendingInvites: 0, pendingHelps: 0,
  helpRemainingToday: 20, events: [], serverNow: Date.now(),
})

fixture.canCreate = false
await openSocial()
await page.evaluate(TAP_NAMED('social', 'Tab_squad'))
const snapF = await waitForRows(rows => rows.some(r => r.caption === '加入'))
checkTrue('F1 小队页有两种按钮：能加的那行亮着「加入」',
  (snapF?.rows ?? []).some(r => r.title.includes('铁血队') && r.caption === '加入'
    && JSON.stringify(r.color) === JSON.stringify(LIT)))
checkTrue('F2 「已满」那行灰着并写原因（上限挂在队长主城等级上，客户端算不出，只能由服务端说）',
  (snapF?.rows ?? []).some(r => r.caption === '已满' && JSON.stringify(r.color) === JSON.stringify(DIM)
    && r.detail.includes('位置满了') && r.detail.includes('10/10 人')))
checkTrue('F3 有界列表说清总量（那句「共 31 支小队，这里只显示前 2 支」）',
  (snapF?.labels ?? []).some(text => text.includes('共 31 支小队')))
await shot('F-squad-discovery-list')
const joinBefore = fixture.joinCalls.length
await page.evaluate(TAP_CAPTION('已满'))
await page.waitForTimeout(800)
check('F4 点「已满」那一行不发请求', fixture.joinCalls.length, joinBefore)
await page.evaluate(TAP_CAPTION('加入'))
await page.waitForTimeout(1_600)
check('F5 点「加入」发恰好一条', fixture.joinCalls.length - joinBefore, 1)
check('F6 请求体带的是列表里那支小队的 id', fixture.joinCalls[joinBefore]?.squadId, 'sq_probe_open')
checkTrue('F7 请求带幂等键（加入会改组织成员表，重放等于多占一个位置）',
  typeof fixture.joinCalls[joinBefore]?.requestId === 'string')
const readsAfterJoin = fixture.squadListReads
await page.evaluate(TAP_NAMED('social', 'Tab_alliance'))
await page.evaluate(TAP_NAMED('social', 'Tab_squad'))
await page.waitForTimeout(1_200)
const snapF2 = await readRows()
const f8Clean = !(snapF2?.rows ?? []).some(r => r.caption === '加入' || r.caption === '已满')
checkTrue(`F8 进了队之后那一屏自己收起：不再画「加入」「已满」那些行（诊断 joinedId=${fixture.joinedId} summaryHits=${fixture.summaryHits} captions=${JSON.stringify((snapF2?.rows ?? []).map(r => r.caption))}）`, f8Clean)
check('F9 有队之后不再拉可加入列表（换两次页签，一次都不发）', fixture.squadListReads, readsAfterJoin)
checkTrue('F10 屏上不出现小队 id 与字段名',
  ((await readRows())?.labels ?? []).every(text => !/sq_probe|memberCap|isSubSquad/.test(text)))
await shot('F-squad-discovery-joined')

// ============================ G 相：入盟申请那一屏（B26 S8） ============================
// 审核只对"本盟 + 有 APPROVE_APPLICATION"的人开放，所以这一相要把摘要换成"我有一个联盟"
// （F 相结束时玩家刚进了一支独立小队），并把权限桩换成带审核位的。
await context.route('**/social/permissions*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  const scope = request.url().includes('scope=SQUAD') ? 'SQUAD' : 'ALLIANCE'
  await reply(route, {
    scope, role: scope === 'SQUAD' ? 'MEMBER' : 'LEADER',
    permissions: fixture.canReview
      ? ['KICK_MEMBER', 'DONATE', 'APPROVE_APPLICATION']
      : ['KICK_MEMBER', 'DONATE'],
    serverNow: Date.now(),
  })
})
await context.route('**/alliance/applications*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  fixture.appReads += 1
  await reply(route, {
    applicants: fixture.applicants.map(a => ({ ...a })),
    total: fixture.applicants.length, limit: 50, serverNow: Date.now(),
  })
})
await context.route('**/alliance/review*', async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  const body = JSON.parse(request.postData() ?? '{}')
  fixture.reviewCalls.push(body)
  // 批准与拒绝都会把这一条从待审队列里拿掉（服务端两条路都 removeApplication），
  // 所以这里不分支：G6 要证的是"那一行是自己消失的"，不是本地抹掉。
  fixture.applicants = fixture.applicants.filter(a => a.playerId !== body.applicantId)
  await reply(route, joinedSummary(fixture.joinedId === null))
})

fixture.allianceVisible = true
fixture.canReview = true
await openSocial()
await page.evaluate(TAP_NAMED('social', 'Tab_alliance'))
const snapG = await waitForRows(rows => rows.some(r => r.caption === '批准'))
checkTrue('G1 联盟页签画出待审申请：昵称与主城等级都用服务端那一份（客户端没有玩家表）',
  (snapG?.rows ?? []).some(r => r.title.includes('阿铁') && r.detail.includes('主城 7 级'))
    && (snapG?.rows ?? []).some(r => r.title.includes('老周') && r.detail.includes('主城 3 级')))
checkTrue('G2 一行两颗按钮：批准与拒绝都亮着（都是真动作，不做两下确认）',
  (snapG?.rows ?? []).some(r => r.caption === '批准'
    && JSON.stringify(r.color) === JSON.stringify(LIT)))
checkTrue('G3 屏上不出现申请人 id 与字段名（id 只进请求）',
  ((snapG?.labels ?? []).every(text => !/p_probe|playerId|mainCityLevel/.test(text))))
const reviewBefore = fixture.reviewCalls.length
await page.evaluate(TAP_CAPTION('批准'))
await page.waitForTimeout(1_600)
check('G4 点「批准」发恰好一条', fixture.reviewCalls.length - reviewBefore, 1)
check('G5 请求体带的是那一行的申请人且 approve=true',
  `${fixture.reviewCalls[reviewBefore]?.applicantId}|${fixture.reviewCalls[reviewBefore]?.approve}`,
  'p_probe_a|true')
checkTrue('G6 批完那一行自己消失（重拉名单，不是客户端本地抹掉）',
  fixture.appReads >= 2 && !((await readRows())?.rows ?? []).some(r => r.title.includes('阿铁')))
await shot('G-applications-list')
checkTrue('G7 剩下那条还在（只批掉点的那一个，不是一键清空）',
  (await readRows())?.rows.some(r => r.title.includes('老周')) === true)

// 反向：把审核位撤掉，重进社交页 —— 这一段不该画，那一问也不该发
fixture.canReview = false
const readsBeforeG8 = fixture.appReads
await openSocial()
await page.evaluate(TAP_NAMED('social', 'Tab_alliance'))
await page.waitForTimeout(1_400)
check('G8 没有 APPROVE_APPLICATION 时一次都不拉申请名单（读口与写口同一条门）',
  fixture.appReads, readsBeforeG8)
checkTrue('G9 屏上也不出现「批准」那颗按钮（看不见功能存在与看得见但不能点是两回事，这里是前者：这一屏本来就不该给他）',
  !((await readRows())?.rows ?? []).some(r => r.caption === '批准'))
await shot('G-applications-review')
// ============================ D 相：版面与错误 ============================
// 只比"要点要填"的那几块板：标题/消耗/提示三条 Label 的盒本来就是整幅宽，
// 把它们算进压叠判定必然报红 —— 那是量具算错，不是排版错
const boxes = allianceForm.plates.filter(p => /^(name-field|tag-field|cancel|submit)$/.test(p.name))
let overlap = 0
for (let i = 0; i < boxes.length; i += 1) {
  for (let j = i + 1; j < boxes.length; j += 1) {
    const a = boxes[i]
    const b = boxes[j]
    if (Math.abs(a.x - b.x) < (a.w + b.w) / 2 && Math.abs(a.y - b.y) < (a.h + b.h) / 2) {
      overlap += 1
    }
  }
}
check('D1 弹层里的板两两不压叠', overlap, 0)
const captions = allianceForm.captions ?? []
check('D2 联盟表单有两个小标签（名字与标签各一个）', captions.length, 2)
/**
 * 屏内判定按"世界原点在可视区左下角"来算：本机实测 `view.getVisibleSize()` 是设计分辨率
 * （960×540，不是浏览器的 1280×720），而 UI 节点的 `getWorldPosition()` 从左下角起算
 * —— 按"原点在中心"判会把一个正好居中的节点说成越界（第一版就是这么假红的）。
 */
const width = allianceForm.visibleWidth ?? 960
const outside = captions.filter(c => {
  const left = c.anchorX === 0 ? c.x : c.x - c.w / 2
  return left < 2 || left + c.w > width - 2
})
checkTrue(`D3 小标签整盒在屏内（截图抓到过它跑到屏幕外：锚点没设成左对齐，"贴左"反而推出去半个盒宽）${
  outside.length === 0 ? '' : ` 越界：${JSON.stringify(outside)} 设计宽=${width}`}`,
  outside.length === 0)
const titlePlate = allianceForm.plates.find(p => p.name === 'title')
const firstField = allianceForm.plates.find(p => p.name === 'name-field')
checkTrue('D4 标题不被第一个输入框压住（两板在 y 上不相交）',
  titlePlate !== undefined && firstField !== undefined
    && Math.abs(titlePlate.y - firstField.y) >= (titlePlate.h + firstField.h) / 2)
const clipped = allianceForm.labels.filter(l => !l.text.startsWith(' ')).length
checkTrue(`D5 弹层里的字都画出来了（${clipped} 条 Label）`, clipped >= 6)
check('D6 全程零页面错误', errors.length, 0)

await browser.close()
await preview.close()
console.log(log.join('\n'))
console.log(`\n=== 判定：${pass} PASS / ${fail} FAIL（A 相全走真后端；B/C 相只把 /social/createPolicy 换成"能建"、/squad/create 打桩，服务端存档未动）===`)
process.exit(fail === 0 ? 0 : 1)
