#!/usr/bin/env node
/**
 * 职责：把国家面板的 S2 三块（国家科技 / 外交 / 任命）钉成能失败的运行时判据（V13-S2）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8080（不给就退 2 并点名这个变量 —— 静默回落到别的后端
 *   会把读数错得像产品缺陷）；端口 NATION_S2_PORT（默认 8231）
 *   BACKEND_ORIGIN=http://localhost:8080 node tools/verify-nation-s2.mjs
 *
 * <p><b>与 S1 那份是同一个套路</b>（`verify-nation.mjs`）：先用真后端验对照组，
 * 再用路由夹具造出「已在联盟 + 已有国家」的三种形态。
 *
 * <p><b>它盯的判据</b>：
 * ① 四个页签都在、当前页签有指示；切到「科技」才发 `/nation/tech`（打开面板不预拉它）；
 * ② 科技行：名字/学派/等级/下一级花费照服务端那一份；`canResearch=false` 时那颗键灰、点了零请求；
 * ③ 六种 `blockedReason` 各自有一句不同的话（把同一份数据只换那一位，结论必须跟着换）；
 * ④ 外交：四个关系键 + 候选目标国；**没打过一次交道时给的是说明而不是空白**；
 *    改一次关系真的发出 `POST /nation/diplomacy`，请求体带 `requestId` 与四值之一；
 * ⑤ 任命：候选是本盟成员的**昵称**、四颗官职键；**国王与议员一颗都不摆**；
 * ⑥ 屏上没有裸值：`nation_` / `player:` / `sink:` / 枚举原文 / 玩家 id。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { makeStubRead } from './lib/route-stub.mjs'

const OUT = process.env.NATION_S2_VERIFY_OUT ?? path.resolve(process.cwd(), 'tmp/nation-s2')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.NATION_S2_PORT ?? 8231)
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-nation-s2] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const ARTIFACT = 'client/build/web-mobile'

let pass = 0
let fail = 0
const ok = (msg) => { pass += 1; console.log(`  PASS  ${msg}`) }
const bad = (msg) => { fail += 1; console.log(`  FAIL  ${msg}`) }
const check = (msg, actual, expected) => {
  if (actual === expected) {
    ok(`${msg}（${actual}）`)
  } else {
    bad(`${msg}：期望 ${expected}，实际 ${actual}`)
  }
}
const checkThat = (msg, actual) => {
  if (actual) {
    ok(msg)
  } else {
    bad(msg)
  }
}

const NOW = 1_800_000_000_000
const KING = 'player_king'
const MINISTER = 'player_minister'
const CAPTAIN = 'player_captain'

const ALLIANCE = {
  id: 'alliance_s2', name: '试炼联盟', tag: 'S2', leaderId: KING, level: 3, exp: 0,
  memberCap: 30, memberCount: 3, fund: 500_000, techs: [], territoryCount: 2, territoryCap: 6,
  myRole: 'LEADER', myContribution: 0, myDonateToday: 0, donateTiersUsed: [], donateDailyCap: 3,
  announcement: '', version: 1, serverNow: NOW,
}

const MEMBERS = [
  { id: KING, name: '赵国王', power: 900_000, role: 'LEADER', contribution: 100, lastActiveAt: NOW, squadId: null, squadName: null },
  { id: MINISTER, name: '钱部长', power: 700_000, role: 'OFFICER', contribution: 50, lastActiveAt: NOW, squadId: null, squadName: null },
  { id: CAPTAIN, name: '孙队长', power: 500_000, role: 'ELDER', contribution: 20, lastActiveAt: NOW, squadId: null, squadName: null },
]

const NATION = {
  nationId: 'nation_ironoath', name: '赤壁盟', kingId: KING, level: 3, allianceCount: 2, memberCap: 4,
  capitalX: 120, capitalY: 88, treasury: 1_234_567, treasuryCap: 10_000_000,
  myOffice: 'KING', serverNow: NOW,
}

function techRow(overrides = {}) {
  return {
    techId: 'nation_tech_wood', name: '林地开发', school: 'AGRICULTURE', effectAttr: 'WOOD_OUTPUT',
    effectValuePerLevelFixed: 400, level: 1, maxLevel: 5, requireNationLevel: 2,
    nextCostTreasury: 12_000, canResearch: true, blockedReason: 'NONE', ...overrides,
  }
}

const TECHS = {
  ok: [techRow()],
  treasuryLow: [techRow({ canResearch: false, blockedReason: 'TREASURY_LOW' })],
  officerLimit: [techRow({ canResearch: false, blockedReason: 'OFFICER_LIMIT' })],
  notOfficer: [techRow({ canResearch: false, blockedReason: 'NOT_OFFICER' })],
  maxLevel: [techRow({ level: 5, canResearch: false, blockedReason: 'MAX_LEVEL', effectValuePerLevelFixed: null })],
}

/** 面板里读：国家覆盖层的文字与「哪几颗键在吃触摸」。 */
function readPanel() {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  const panel = game.getChildByName('nation')
  const texts = []
  const buttons = {}
  const BUILT = ['Tab_TREASURY', 'Tab_TECH', 'Tab_DIPLO', 'Tab_OFFICE', 'TechResearch-nation_tech_wood',
    'DiproTarget-nation_chibi', 'DiproOption-ALLIED', 'AppointTarget-player_minister',
    'AppointOffice-GENERAL', 'AppointOffice-KING', 'AppointOffice-REPRESENTATIVE', 'SpendButton', 'CloseButton']
  if (panel) {
    const walk = (n) => {
      if (n.activeInHierarchy) {
        const label = n.getComponent('cc.Label')
        if (label && String(label.string ?? '').trim() !== '') texts.push(label.string)
        if (BUILT.includes(n.name)) {
          // 灰键按设计不挂 touch-start —— 于是「亮/灰」与「点了会不会发请求」是同一件事
          buttons[n.name] = n.hasEventListener('touch-start')
        }
      }
      for (const child of n.children) walk(child)
    }
    walk(panel)
  }
  return { missing: !panel, active: panel ? panel.activeInHierarchy : false, texts, buttons }
}

const clickPanelButton = (page, name) => page.evaluate(`(() => {
  const scene = window.cc.director.getScene()
  const panel = scene.getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
  if (!panel) return false
  let target = null
  const walk = (n) => {
    if (target) return
    if (n.name === '${name}' && n.activeInHierarchy) { target = n; return }
    for (const child of n.children) walk(child)
  }
  walk(panel)
  if (!target) return false
  target.emit('touch-start')
  return true
})()`)

const clickTab = (page, tab) => clickPanelButton(page, `Tab_${tab}`)

/** 点联盟页上「国家」那颗键：入口挂在**概况行的第二颗键**上（不是独立一行 ——
 * 单开一行会把小联盟的成员行挤到第 2 页）。所以按**按钮文案**找，不按行标题。 */
const clickAllianceNationRow = (page) => page.evaluate(`(() => {
  const scene = window.cc.director.getScene()
  const social = scene.getChildByName('Canvas').getChildByName('Game').getChildByName('social')
  if (!social) return false
  social.getChildByName('Tab_alliance').emit('touch-start')
  let target = null
  const walk = (n) => {
    if (target) return
    if (/^ActionButton[23]?$/.test(n.name) && n.activeInHierarchy) {
      const caption = n.getComponentInChildren('cc.Label')
      if (caption !== null && caption !== undefined && String(caption.string) === '国家') { target = n; return }
    }
    for (const child of n.children) walk(child)
  }
  walk(social)
  if (target === null) return false
  target.emit('touch-start')
  return true
})()`)

/** 挂夹具：已在联盟 + 已有国家 + 一张科技表。`techCase` 选哪一行形态。
 *
 * <p>`permissions` 是**国家层权限位**（V13-d 之后灰键由它裁决，不再靠 `myOffice` 猜）：
 * 默认给全（国王档），`permissions: []` 用来验"位缺了就是灰的"。
 */
function stubAll(context, { techCase = 'ok', permissions = null } = {}) {
  const stub = makeStubRead(context)
  const cors = (request) => ({
    'access-control-allow-origin': request.headers()['origin'] ?? '*',
    'access-control-allow-headers': '*',
    'access-control-allow-methods': 'GET,POST,OPTIONS',
  })
  const codes = permissions ?? ['APPOINT_OFFICE', 'WITHDRAW_TREASURY', 'MANAGE_DIPLOMACY', 'RESEARCH_NATION_TECH']
  const techCalls = []
  stub('**/social/permissions*', () => ({
    scope: 'NATION', role: codes.length === 0 ? 'MEMBER' : 'KING', permissions: codes, serverNow: NOW,
  }))
  stub('**/social/summary', () => ({
    squad: null, alliance: ALLIANCE, nationId: NATION.nationId, pendingInvites: 0, pendingHelps: 0,
    helpRemainingToday: 5, events: [], serverNow: NOW,
  }))
  stub('**/alliance/sync', () => ({
    version: 1, unchanged: false, changedMembers: MEMBERS, removedMemberIds: [],
    fund: ALLIANCE.fund, level: ALLIANCE.level, memberCount: MEMBERS.length, announcement: '', serverNow: NOW,
  }))
  stub('**/social/helpRequests*', () => ({ requests: [], serverNow: NOW }))
  stub('**/nation/treasury', () => ({ balance: 1_234_567, logs: [], serverNow: NOW }))
  stub('**/nation/tech', () => {
    techCalls.push(1)
    return { nationId: NATION.nationId, nationName: NATION.name, nationLevel: 3, treasury: 1_234_567, techs: TECHS[techCase], serverNow: NOW }
  })
  stub('**/rank/list*', () => ({
    type: 'NATION',
    entries: [
      { rank: 1, id: 'nation_chibi', name: '北伐营', value: 3_200_000, tag: '北' },
      { rank: 2, id: 'nation_qinglong', name: '青龙盟', value: 2_100_000, tag: '青' },
    ],
    myRank: null, myValue: 0, page: 1, pageSize: 8, hasMore: false, dayKey: 's2',
  }))
  context.route('**/nation', async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors(route.request()) })
      return
    }
    await route.fulfill({
      status: 200,
      headers: { ...cors(route.request()), 'content-type': 'application/json' },
      body: JSON.stringify({ code: 0, msg: '成功', serverNow: NOW, data: { nation: NATION, serverNow: NOW } }),
    })
  })
  const posts = []
  context.route('**/nation/diplomacy', async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors(route.request()) })
      return
    }
    if (route.request().method() !== 'POST') {
      await route.fulfill({ status: 200, headers: { ...cors(route.request()), 'content-type': 'application/json' },
        body: JSON.stringify({ code: 0, msg: '成功', serverNow: NOW, data: { balance: 0, logs: [], serverNow: NOW } }) })
      return
    }
    const body = JSON.parse(route.request().postData() ?? '{}')
    posts.push(body)
    await route.fulfill({
      status: 200,
      headers: { ...cors(route.request()), 'content-type': 'application/json' },
      body: JSON.stringify({
        code: 0, msg: '成功', serverNow: NOW,
        data: {
          targetNationId: body.targetNationId,
          relation: body.relation,
          allRelations: [
            { nationId: 'nation_chibi', nationName: '北伐营', relation: body.relation },
            { nationId: 'nation_qinglong', nationName: '青龙盟', relation: 'NEUTRAL' },
          ],
          serverNow: NOW,
        },
      }),
    })
  })
  context.route('**/nation/tech/research', async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors(route.request()) })
      return
    }
    const body = JSON.parse(route.request().postData() ?? '{}')
    posts.push(body)
    await route.fulfill({
      status: 200,
      headers: { ...cors(route.request()), 'content-type': 'application/json' },
      body: JSON.stringify({ code: 0, msg: '成功', serverNow: NOW, data: { techId: body.techId, level: 2, costTreasury: 12_000, treasuryAfter: 1_222_567 } }),
    })
  })
  context.route('**/nation/appoint', async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors(route.request()) })
      return
    }
    const body = JSON.parse(route.request().postData() ?? '{}')
    posts.push(body)
    await route.fulfill({
      status: 200,
      headers: { ...cors(route.request()), 'content-type': 'application/json' },
      body: JSON.stringify({ code: 0, msg: '成功', serverNow: NOW, data: { nation: NATION, serverNow: NOW } }),
    })
  })
  return { techCalls, posts }
}

/** 跑一场：挂夹具 → 进联盟页 → 开国家面板 → 交回页面给断言用。 */
async function runScene(browser, name, fixture, body) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `${name}-${Date.now()}`)
  const state = fixture === null ? { techCalls: [], posts: [] } : stubAll(context, fixture)
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()) })
  const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
  try {
    await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
    preview.assertRewritten()
    await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
      null, { timeout: 60_000 })
    await page.waitForTimeout(3500)
    await hideGuideOverlay(page)
    await page.waitForTimeout(400)
    await body({ page, state, errors })
    check(`${name}：页面零错误`, errors.join(' | ') || '无', '无')
  } finally {
    await preview.close()
    await context.close()
  }
}

const browser = await chromium.launch({ headless: true })

// ---------- 第 0 场：真后端对照组（无联盟 ⇒ 连面板都开不起来，S2 无从验） ----------
await runScene(browser, '对照组', null, async ({ page }) => {
  const clicked = await clickAllianceNationRow(page)
  await page.waitForTimeout(1500)
  const state = await page.evaluate(readPanel)
  check('对照组：真后端 + 新号点不出国家面板', state.active, false)
  check('对照组：那一下根本没点得着（没有联盟就没有那一行）', clicked, false)
  await page.screenshot({ path: path.join(OUT, '00-control.png') })
})

// ---------- 第 1 场：页签与「切到科技才发那一枪」 ----------
await runScene(browser, '页签', { techCase: 'ok' }, async ({ page, state }) => {
  checkThat('点得开国家面板', await clickAllianceNationRow(page))
  await page.waitForTimeout(1500)
  const opened = await page.evaluate(readPanel)
  check('面板出现了', opened.active, true)
  const shown = (opened.texts ?? []).join('')
  check('打开面板时**没有**预拉国家科技', state.techCalls.length, 0)
  checkThat('四个页签都在', ['国库', '国家科技', '外交', '任命'].every(t => shown.includes(t)))

  checkThat('切到「国家科技」', await clickTab(page, 'TECH'))
  await page.waitForTimeout(1500)
  check('切过去才发那一枪 /nation/tech', state.techCalls.length, 1)
  const tech = await page.evaluate(readPanel)
  const techText = (tech.texts ?? []).join('')
  console.log(`  科技页文字：${(tech.texts ?? []).join(' ｜ ')}`)
  checkThat('表头带国家等级与国库（服务端那一份）', /国家等级 Lv3 · 国库 1,234,567/.test(techText))
  checkThat('行上有名有学派', techText.includes('林地开发') && techText.includes('农政'))
  checkThat('行上有等级与下一级花费', techText.includes('Lv1/5 · 下一级 12,000'))
  checkThat('行上有效果（定点万分比换成人话）', techText.includes('木材产量 +4%/级'))
  check('能研究时那颗键是亮的', tech.buttons['TechResearch-nation_tech_wood'], true)
  for (const raw of ['nation_tech_wood', 'WOOD_OUTPUT', 'AGRICULTURE', 'NATION_LOW', 'nation_ironoath']) {
    checkThat(`科技页屏上没有裸值 ${raw}`, !techText.includes(raw))
  }
  await page.screenshot({ path: path.join(OUT, '01-tech.png') })
  // 再切一次不该重复拉（手里那一份还在）
  await clickTab(page, 'TREASURY')
  await page.waitForTimeout(900)
  await clickTab(page, 'TECH')
  await page.waitForTimeout(1200)
  check('切回来再切过去不重拉（手里那份还在）', state.techCalls.length, 1)
})

// ---------- 第 2~5 场：四种 blockedReason 各给一句不同的话，且灰键零请求 ----------
for (const [techCase, expected] of [['treasuryLow', '国库余额不足'], ['officerLimit', '本周国库支出额度已经用完，等下周'],
  ['notOfficer', '你没有研究国家科技的权限'], ['maxLevel', '已经满级']]) {
  await runScene(browser, `科技/${techCase}`, { techCase }, async ({ page, state }) => {
    await clickAllianceNationRow(page)
    await page.waitForTimeout(1200)
    await clickTab(page, 'TECH')
    await page.waitForTimeout(1200)
    const view = await page.evaluate(readPanel)
    const shown = (view.texts ?? []).join('')
    check(`${techCase}：那颗键灰着`, view.buttons['TechResearch-nation_tech_wood'], false)
    checkThat(`${techCase}：写明原因（${expected}）`, shown.includes(expected))
    await clickPanelButton(page, 'TechResearch-nation_tech_wood')
    await page.waitForTimeout(800)
    check(`${techCase}：点灰键零请求`, state.posts.length, 0)
    await page.screenshot({ path: path.join(OUT, `02-tech-${techCase}.png`) })
  })
}

// ---------- 第 6 场：真研究一笔（正向链路） ----------
await runScene(browser, '科技/研究', { techCase: 'ok' }, async ({ page, state }) => {
  await clickAllianceNationRow(page)
  await page.waitForTimeout(1200)
  await clickTab(page, 'TECH')
  await page.waitForTimeout(1200)
  checkThat('点得下「研究一级」', await clickPanelButton(page, 'TechResearch-nation_tech_wood'))
  await page.waitForTimeout(2000)
  check('真发出去了 POST /nation/tech/research', state.posts.length, 1)
  const body = state.posts[0] ?? {}
  check('请求体带 techId（行 id，不是下标）', body.techId, 'nation_tech_wood')
  checkThat('请求体带幂等键（国库是公共池，重投要挡住）', typeof body.requestId === 'string' && body.requestId.length >= 8)
  const after = await page.evaluate(readPanel)
  const shown = (after.texts ?? []).join('')
  checkThat('回执把花掉多少与剩多少说清了（千分位）',
    shown.includes('12,000') && shown.includes('1,222,567'))
  checkThat('回执说行名而不是 techId', shown.includes('林地开发'))
  checkThat('回执屏上没有 techId', !shown.includes('nation_tech_wood'))
  await page.screenshot({ path: path.join(OUT, '03-research.png') })
})

// ---------- 第 7 场：外交（空表说明 + 真改一次关系） ----------
await runScene(browser, '外交', { techCase: 'ok' }, async ({ page, state }) => {
  await clickAllianceNationRow(page)
  await page.waitForTimeout(1200)
  checkThat('切到「外交」', await clickTab(page, 'DIPLO'))
  await page.waitForTimeout(1500)
  const empty = await page.evaluate(readPanel)
  const emptyText = (empty.texts ?? []).join('')
  checkThat('没打过一次交道时给的是说明而不是空白', emptyText.includes('还没有打过一次交道'))
  checkThat('候选目标国来自国家榜（服务端下发的名字）', emptyText.includes('北伐营') && emptyText.includes('青龙盟'))
  check('还没选目标 ⇒ 四颗关系键都是灰的', empty.buttons['DiproOption-ALLIED'], false)
  // 「目标国」那颗键**未选中时也必须可点**：把"选中"当 enabled 用会把入口自己关掉
  check('还没选目标时，目标国那颗键就能点（不然永远选不中）', empty.buttons['DiproTarget-nation_chibi'], true)
  await page.screenshot({ path: path.join(OUT, '04-diplomacy-empty.png') })

  checkThat('选一个目标国', await clickPanelButton(page, 'DiproTarget-nation_chibi'))
  await page.waitForTimeout(700)
  const picked = await page.evaluate(readPanel)
  check('选完之后四颗关系键亮了', picked.buttons['DiproOption-ALLIED'], true)
  checkThat('把「盟约要双方都记着才成立」摆在选择旁边', (picked.texts ?? []).join('').includes('盟约之间不能互相攻击')
    || (picked.texts ?? []).join('').includes('双方都记着'))
  checkThat('点「敌对」', await clickPanelButton(page, 'DiproOption-HOSTILE'))
  await page.waitForTimeout(1800)
  check('真发出去了 POST /nation/diplomacy', state.posts.length, 1)
  const body = state.posts[0] ?? {}
  check('请求体带 targetNationId', body.targetNationId, 'nation_chibi')
  check('请求体带四值之一的 relation', body.relation, 'HOSTILE')
  checkThat('请求体带幂等键', typeof body.requestId === 'string' && body.requestId.length >= 8)
  const after = await page.evaluate(readPanel)
  const afterText = (after.texts ?? []).join('')
  checkThat('回执之后关系表出来了（服务端回的那张全表）', afterText.includes('敌对') && afterText.includes('中立'))
  checkThat('那一句没有宣布条约成立（C21：双方都记着才成立）', !afterText.includes('已生效'))
  for (const raw of ['HOSTILE', 'NEUTRAL', 'nation_chibi', 'nation_qinglong', 'DIPLOMACY']) {
    checkThat(`外交页屏上没有裸值 ${raw}`, !afterText.includes(raw))
  }
  await page.screenshot({ path: path.join(OUT, '05-diplomacy-set.png') })
})

// ---------- 第 8 场：任命（昵称、四颗官职键、国王与议员不摆） ----------
await runScene(browser, '任命', { techCase: 'ok' }, async ({ page, state }) => {
  await clickAllianceNationRow(page)
  await page.waitForTimeout(1200)
  checkThat('切到「任命」', await clickTab(page, 'OFFICE'))
  await page.waitForTimeout(1500)
  const view = await page.evaluate(readPanel)
  const shown = (view.texts ?? []).join('')
  checkThat('候选是本盟成员的昵称', shown.includes('钱部长') && shown.includes('孙队长'))
  checkThat('四颗官职键都在', ['首相', '大将军', '内政官', '外交官'].every(t => shown.includes(t)))
  // 国王与议员**按节点名**判：屏上的「国王」会出现在身份那句里（我就是国王），
  // 所以判文字会假红；要判的是"那两颗键根本没有被画出来"
  check('国王那颗官职键不存在（服务端当场拒绝，摆出来就是骗人）',
    Object.prototype.hasOwnProperty.call(view.buttons, 'AppointOffice-KING'), false)
  check('议员那颗官职键不存在（那是每盟主一席的派生席位，不是任出来的）',
    Object.prototype.hasOwnProperty.call(view.buttons, 'AppointOffice-REPRESENTATIVE'), false)
  check('还没选人 ⇒ 官职键是灰的', view.buttons['AppointOffice-GENERAL'], false)
  check('还没选人时，那个人名那颗键就能点', view.buttons['AppointTarget-player_minister'], true)
  checkThat('说明了为什么没有国王/议员', shown.includes('国王与议员没有任命入口'))
  for (const raw of ['player_minister', 'PRIME_MINISTER', 'MINISTER', 'DIPLOMAT', 'GENERAL', 'REPRESENTATIVE']) {
    checkThat(`任命页屏上没有裸值 ${raw}`, !shown.includes(raw))
  }
  await page.screenshot({ path: path.join(OUT, '06-appoint.png') })

  checkThat('选一个人', await clickPanelButton(page, 'AppointTarget-player_minister'))
  await page.waitForTimeout(700)
  const picked = await page.evaluate(readPanel)
  check('选完之后官职键亮了', picked.buttons['AppointOffice-GENERAL'], true)
  checkThat('点「大将军」', await clickPanelButton(page, 'AppointOffice-GENERAL'))
  await page.waitForTimeout(1800)
  check('真发出去了 POST /nation/appoint', state.posts.length, 1)
  const body = state.posts[0] ?? {}
  check('请求体带被任命者 playerId', body.playerId, 'player_minister')
  check('请求体带官职枚举', body.office, 'GENERAL')
  checkThat('请求体带幂等键', typeof body.requestId === 'string' && body.requestId.length >= 8)
  const after = await page.evaluate(readPanel)
  checkThat('回执把任命说清了（用昵称，不是 id）', (after.texts ?? []).join('').includes('已任命 钱部长'))
  await page.screenshot({ path: path.join(OUT, '07-appointed.png') })
})

// ---------- 第 9 场：权限位缺了就是灰的（V13-d 的判别场景） ----------
await runScene(browser, '权限位缺位', { techCase: 'ok', permissions: [] }, async ({ page, state }) => {
  await clickAllianceNationRow(page)
  await page.waitForTimeout(1200)
  // 任命：没有 APPOINT_OFFICE ⇒ 先选人，官职键也不许亮；点了零请求
  await clickTab(page, 'OFFICE')
  await page.waitForTimeout(1200)
  const office = await page.evaluate(readPanel)
  const officeText = (office.texts ?? []).join('')
  checkThat('缺 APPOINT_OFFICE：选人那颗键仍可点（它是选择，不是动作）',
    office.buttons['AppointTarget-player_minister'] === true)
  check('缺 APPOINT_OFFICE：官职键是灰的', office.buttons['AppointOffice-GENERAL'], false)
  checkThat('缺位时写明的是「职位不能做」，不是「没读到」',
    officeText.includes('你当前的职位不能任命官职') && !officeText.includes('权限还没读到'))
  await clickPanelButton(page, 'AppointTarget-player_minister')
  await page.waitForTimeout(700)
  const picked = await page.evaluate(readPanel)
  check('选了人之后官职键**仍然**灰（位缺了就是缺了）', picked.buttons['AppointOffice-GENERAL'], false)
  await clickPanelButton(page, 'AppointOffice-GENERAL')
  await page.waitForTimeout(800)
  check('点灰的官职键零请求', state.posts.length, 0)
  await page.screenshot({ path: path.join(OUT, '08-no-appoint-bit.png') })

  // 外交：没有 MANAGE_DIPLOMACY ⇒ 目标可选、四颗关系键全灰、零请求
  await clickTab(page, 'DIPLO')
  await page.waitForTimeout(1200)
  const diplo = await page.evaluate(readPanel)
  const diploText = (diplo.texts ?? []).join('')
  check('缺 MANAGE_DIPLOMACY：四颗关系键是灰的', diplo.buttons['DiproOption-ALLIED'], false)
  checkThat('缺位时写明的是「职位不能变更外交」', diploText.includes('你当前的职位不能变更外交'))
  await clickPanelButton(page, 'DiproTarget-nation_chibi')
  await page.waitForTimeout(700)
  await clickPanelButton(page, 'DiproOption-HOSTILE')
  await page.waitForTimeout(800)
  check('点灰的关系键零请求', state.posts.length, 0)
  await page.screenshot({ path: path.join(OUT, '09-no-diplomacy-bit.png') })

  // 国库：没有 WITHDRAW_TREASURY ⇒ 支出灰（S1 已细验，这里确认同一份权限位也管着这一颗）
  await clickTab(page, 'TREASURY')
  await page.waitForTimeout(1000)
  const treasury = await page.evaluate(readPanel)
  check('缺 WITHDRAW_TREASURY：国库支出也是灰的', treasury.buttons.SpendButton, false)
})

await browser.close()
console.log(`  截图：${OUT}`)
console.log(`\n=== 国家面板 S2（科技/外交/任命）运行时验收：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)
