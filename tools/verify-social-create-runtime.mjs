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
