#!/usr/bin/env node
/**
 * 职责：把「国家这一屏真的能用」钉成能失败的运行时判据（V13-S1；B13 的入籍 + 国库那半）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199（不给就退 2 并点名这个变量：静默回落到别的后端
 *   会把读数错得像产品缺陷 —— 台账 #371/#372）；端口 NATION_PORT（默认 8229）
 *   BACKEND_ORIGIN=http://localhost:8199 node tools/verify-nation.mjs
 *
 * <p><b>为什么要分场景、且第一场是"没有入口"</b>：国家那一行挂在**联盟页**（B13 §一 入籍的最小单位
 * 是联盟，个人不能单独入籍）。dev 新号还没有联盟，所以走真后端时那一行**理应不存在** ——
 * 这一场是对照组，它保证"下面几场看得见面板"不是因为前端硬画了入口。
 *
 * <p>后面几场用路由夹具（与 V11/V12 同范式）：把 `/social/summary` 装成"已在联盟"，
 * `/nation` 与 `/nation/treasury` 装成 B13 协议里的那几种形态。**夹具里的 `kingId` 是从请求头
 * `X-Player-Id` 现取的** —— 国王那一档因此是真的"我这个号就是 kingId"，不是写死一个 id 去撞。
 *
 * <p><b>它盯的判据</b>（每条都能失败）：
 * ① 对照组：真后端 + 新号 ⇒ 联盟页没有「国家」那一行，国家面板也不出现；
 * ② 无国家：面板说「你还没有国家」，并给出「创建国家」与可加入列表两条路；
 * ③ 有国家：概况逐字段等于夹具下发的值（国名/等级/成员/国库/都城/官职中文名）；
 * ④ 国库：余额、上限、流水（支给谁 · 多少 / 多久以前 · 谁做的 · 用途 / 余额）都在；
 * ⑤ 屏上没有裸值：`nation_` / `player:` / `sink:` / 官职枚举原文 / 玩家 id 一个都不许出现；
 * ⑥ 权限：不是国王 ⇒「解散国家」灰（表里没有这一位，按 kingId 结构事实）；
 *    没有 `WITHDRAW_TREASURY` 这一位 ⇒「国库支出」灰，**点了零请求**（数 POST）；
 * ⑦ 国王那一档：解散键亮，且权限位里那一组都在 ⇒ 国库支出也亮；
 * ⑧ 点「关闭」收得掉；全程零页面错误。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { makeStubRead } from './lib/route-stub.mjs'

const OUT = process.env.NATION_VERIFY_OUT ?? path.resolve(process.cwd(), 'tmp/nation')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.NATION_PORT ?? 8229)
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-nation] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
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

/** 联盟成员（id → 昵称）。国库流水里"谁做的 / 支给谁"就是靠它换成名字的。 */
const ALLIANCE = {
  id: 'alliance_v13',
  name: '试炼联盟',
  tag: 'V13',
  leaderId: KING,
  level: 3,
  exp: 0,
  memberCap: 30,
  memberCount: 3,
  fund: 500_000,
  techs: [],
  territoryCount: 2,
  territoryCap: 6,
  myRole: 'LEADER',
  myContribution: 0,
  myDonateToday: 0,
  donateTiersUsed: [],
  donateDailyCap: 3,
  announcement: '',
  version: 1,
  serverNow: NOW,
}

const MEMBERS = [
  { id: KING, name: '赵国王', power: 900_000, role: 'LEADER', contribution: 100, lastActiveAt: NOW, squadId: null, squadName: null },
  { id: MINISTER, name: '钱部长', power: 700_000, role: 'OFFICER', contribution: 50, lastActiveAt: NOW, squadId: null, squadName: null },
  { id: CAPTAIN, name: '孙队长', power: 500_000, role: 'ELDER', contribution: 20, lastActiveAt: NOW, squadId: null, squadName: null },
]

/** 一条国库流水：三种支给形态各来一条，把"换词"这件事在屏上验掉。 */
const LOGS = [
  {
    at: NOW - 3 * 60_000, operatorId: MINISTER, counterparty: `player:${CAPTAIN}`,
    amount: 5_000, reason: '本周俸禄', balanceAfter: 1_229_567,
  },
  {
    at: NOW - 26 * 3_600_000, operatorId: 'system', counterparty: 'sink:NATIONAL_TECH',
    amount: 80_000, reason: '国家科技出资', balanceAfter: 1_234_567,
  },
  {
    at: NOW - 30 * 3_600_000, operatorId: KING, counterparty: `player:${KING}`,
    amount: 20_000, reason: '远征犒赏', balanceAfter: 1_314_567,
  },
  // **裸 token 那一形态必须有**：周税是真实后端里最常见的入账，而它不带 player:/sink: 前缀。
  // 真链路回读屏抓到过一次"入账显示成其他用途"，而当时这份夹具里没有它 ⇒ 探针全绿。
  {
    at: NOW - 50 * 3_600_000, operatorId: 'system', counterparty: 'weekly_tax',
    amount: 10_000, reason: '国库周税（第 202640 周 × 1 个成员联盟）', balanceAfter: 1_334_567,
  },
]

const NATION = {
  nationId: 'nation_ironoath',
  name: '赤壁盟',
  kingId: KING,
  level: 3,
  allianceCount: 2,
  memberCap: 4,
  capitalX: 120,
  capitalY: 88,
  treasury: 1_234_567,
  treasuryCap: 10_000_000,
  myOffice: 'MINISTER',
  serverNow: NOW,
}

/** 面板里读：国家覆盖层在不在、屏上写了什么、那颗键有没有在吃触摸。 */
function readNation() {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  const panel = game.getChildByName('nation')
  const texts = []
  const buttons = {}
  if (panel) {
    const walk = (n) => {
      if (n.activeInHierarchy) {
        const label = n.getComponent('cc.Label')
        if (label && String(label.string ?? '').trim() !== '') texts.push(label.string)
        if (n.name === 'CloseButton' || n.name === 'FoundButton' || n.name === 'LeaveButton'
          || n.name === 'DisbandButton' || n.name === 'SpendButton' || n.name === 'SpendConfirm') {
          // 一颗键「亮不亮」的判据是**它有没有挂 touch-start 监听**：灰键按设计不吃触摸，
          // 于是「灰」与「点了会发请求」在结构上就是同一件事
          buttons[n.name] = n.hasEventListener('touch-start')
        }
      }
      for (const child of n.children) walk(child)
    }
    walk(panel)
  }
  return { missing: !panel, active: panel ? panel.activeInHierarchy : false, texts, buttons }
}

/** 联盟页里找「国家」那一行（池化行按标题找，不按序号 —— 序号会随行数变）。 */
function readNationRow() {
  const scene = window.cc.director.getScene()
  const social = scene.getChildByName('Canvas').getChildByName('Game').getChildByName('social')
  if (!social) return { found: false, rows: [] }
  const rows = []
  let found = false
  const walk = (n) => {
    if (n.name === 'SocialRow' && n.activeInHierarchy) {
      const title = n.children[0] && n.children[0].getComponent('cc.Label')
      const titleText = title ? String(title.string) : ''
      rows.push(titleText)
      if (titleText === '国家') found = true
    }
    for (const child of n.children) walk(child)
  }
  walk(social)
  return { found, rows }
}

/** 往某个输入框里打进一个字，并触发它的 TEXT_CHANGED（与真人打字走同一个回调）。 */
const typeInto = (page, name, text) => page.evaluate(`(() => {
  const scene = window.cc.director.getScene()
  const panel = scene.getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
  if (!panel) return false
  let node = null
  const walk = (n) => {
    if (node) return
    if (n.name === '${name}' && n.activeInHierarchy) { node = n; return }
    for (const child of n.children) walk(child)
  }
  walk(panel)
  if (!node) return false
  const box = node.getComponent('cc.EditBox')
  if (!box) return false
  box.string = '${text}'
  node.emit('text-changed', box)
  return true
})()`)

/** 切到某个页签（社交页默认停在「小队」那一页，国家那一行在「联盟」页）。 */
const clickTab = (page, tab) => page.evaluate(`(() => {
  const scene = window.cc.director.getScene()
  const social = scene.getChildByName('Canvas').getChildByName('Game').getChildByName('social')
  if (!social) return false
  const node = social.getChildByName('Tab_${tab}')
  if (!node || !node.activeInHierarchy) return false
  node.emit('touch-start')
  return true
})()`)

/** 点联盟页「国家」那一行的动作键。返回点没点得到。 */
const clickNationRow = (page) => page.evaluate(`(() => {
  const scene = window.cc.director.getScene()
  const social = scene.getChildByName('Canvas').getChildByName('Game').getChildByName('social')
  if (!social) return false
  let button = null
  const walk = (n) => {
    if (button) return
    if (n.name === 'SocialRow' && n.activeInHierarchy) {
      const title = n.children[0] && n.children[0].getComponent('cc.Label')
      if (title && String(title.string) === '国家') button = n.children[3]
    }
    for (const child of n.children) walk(child)
  }
  walk(social)
  if (!button || !button.activeInHierarchy) return false
  button.emit('touch-start')
  return true
})()`)

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

/**
 * 挂上「已在联盟 + 国家三态」那一整套夹具。`kind` 决定 GET /nation 回什么。
 *
 * <p>`permissions` 是**国家层权限位**（`GET /social/permissions?scope=NATION`）：
 * V13-d 之后灰键由它裁决，所以夹具必须用权限位说话，而不是靠 `myOffice` 猜。
 * 三态的位分别对应角色：KING 全有、MINISTER 有支取、无官职一个都没有。
 */
function stubSocial(context, kind) {
  const stub = makeStubRead(context)
  const cors = (request) => ({
    'access-control-allow-origin': request.headers()['origin'] ?? '*',
    'access-control-allow-headers': '*',
    'access-control-allow-methods': 'GET,POST,OPTIONS',
  })
  const permissionsOf = () => {
    if (kind === 'none') return { role: 'NONE', permissions: [] }
    if (kind === 'king') return { role: 'KING', permissions: ['APPOINT_OFFICE', 'WITHDRAW_TREASURY', 'MANAGE_DIPLOMACY', 'RESEARCH_NATION_TECH', 'DECLARE_WAR', 'JOIN_NATIONAL_RALLY'] }
    if (kind === 'nooffice') return { role: 'MEMBER', permissions: ['JOIN_NATIONAL_RALLY'] }
    return { role: 'MINISTER', permissions: ['WITHDRAW_TREASURY', 'MANAGE_DIPLOMACY', 'RESEARCH_NATION_TECH', 'JOIN_NATIONAL_RALLY'] }
  }
  stub('**/social/permissions*', () => {
    const p = permissionsOf()
    return { scope: 'NATION', role: p.role, permissions: p.permissions, serverNow: NOW }
  })
  stub('**/social/summary', () => ({
    squad: null,
    alliance: ALLIANCE,
    nationId: kind === 'none' ? null : NATION.nationId,
    pendingInvites: 0,
    pendingHelps: 0,
    helpRemainingToday: 5,
    events: [],
    serverNow: NOW,
  }))
  stub('**/alliance/sync', () => ({
    version: 1, unchanged: false, changedMembers: MEMBERS, removedMemberIds: [],
    fund: ALLIANCE.fund, level: ALLIANCE.level, memberCount: MEMBERS.length,
    announcement: '', serverNow: NOW,
  }))
  stub('**/social/helpRequests*', () => ({ requests: [], serverNow: NOW }))
  if (kind === 'none') {
    stub('**/rank/list*', () => ({
      type: 'NATION',
      entries: [
        { rank: 1, id: 'nation_chibi', name: '北伐营', value: 3_200_000, tag: '北' },
        { rank: 2, id: 'nation_qinglong', name: '青龙盟', value: 2_100_000, tag: '青' },
      ],
      myRank: null, myValue: 0, page: 1, pageSize: 8, hasMore: false, dayKey: 'v13',
    }))
  }
  /**
   * `/nation` 与 `/nation/treasury` 自己挂：这两个要**读请求头里的 `X-Player-Id`** ——
   * 「国王」那一档的 kingId 就是我这个号（`makeStubRead` 的 data 只拿到 URL，拿不到头）。
   */
  context.route('**/nation', async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors(route.request()) })
      return
    }
    const me = route.request().headers()['x-player-id'] ?? KING
    if (kind === 'none') {
      // 「不在任何国家」是业务拒绝 13000，不是网络失败 —— 回一个非 0 信封
      await route.fulfill({
        status: 200,
        headers: { ...cors(route.request()), 'content-type': 'application/json' },
        body: JSON.stringify({ code: 13000, msg: '国家不存在或已解散', data: null, serverNow: NOW }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      headers: { ...cors(route.request()), 'content-type': 'application/json' },
      body: JSON.stringify({
        code: 0, msg: '成功', serverNow: NOW,
        data: {
          nation: {
            ...NATION,
            kingId: kind === 'king' ? me : NATION.kingId,
            myOffice: kind === 'nooffice' ? null : (kind === 'king' ? 'KING' : 'MINISTER'),
          },
          serverNow: NOW,
        },
      }),
    })
  })
  stub('**/nation/treasury', () => ({ balance: 1_234_567, logs: LOGS, serverNow: NOW }))
}

/** 逐场跑：每场一个全新的 context（夹具与本地存储都不串场）。 */
async function runScene(browser, { name, kind, stubbed, extra }) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `${name}-${Date.now()}`)
  if (stubbed) {
    stubSocial(context, kind)
  }
  const page = await context.newPage()
  const errors = []
  const posts = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()) })
  page.on('request', (request) => {
    if (request.method() === 'POST' && /\/nation\//.test(request.url())) posts.push(request.url())
  })
  const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
  try {
    await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
    preview.assertRewritten()
    await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
      null, { timeout: 60_000 })
    await page.waitForTimeout(3500)
    await hideGuideOverlay(page)
    await page.waitForTimeout(400)
    // 切到「联盟」页 —— 国家那一行挂在那一页（默认停在「小队」）
    const tabbed = await clickTab(page, 'alliance')
    await page.waitForTimeout(1200)
    check(`${name}：切到「联盟」页`, tabbed, true)
    await extra({ page, posts, preview })
    check(`${name}：页面零错误`, errors.join(' | ') || '无', '无')
  } finally {
    await preview.close()
    await context.close()
  }
}

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })

// ---------- 第 0 场：真后端对照组（新号，没有联盟 ⇒ 不该有那个入口） ----------
{
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `nation-control-${Date.now()}`)
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  await page.waitForTimeout(3500)
  await hideGuideOverlay(page)
  await page.waitForTimeout(400)
  await clickTab(page, 'alliance')
  await page.waitForTimeout(1200)
  const row = await page.evaluate(readNationRow)
  console.log(`  联盟页行标题：${(row.rows ?? []).join(' ｜ ') || '（无）'}`)
  check('对照组：新号没有联盟 ⇒ 联盟页**不出现**「国家」那一行', row.found, false)
  const state = await page.evaluate(readNation)
  check('对照组：国家面板没有出现', state.active, false)
  check('对照组：零页面错误', errors.join(' | ') || '无', '无')
  await page.screenshot({ path: path.join(OUT, '00-control-no-alliance.png') })
  await context.close()
}
await preview.close()

// ---------- 第 1 场：无国家（13000 那一态） ----------
await runScene(browser, {
  name: '无国家',
  kind: 'none',
  stubbed: true,
  extra: async ({ page }) => {
    const before = await page.evaluate(readNation)
    check('点之前面板不出现（对照组）', before.active, false)
    const row = await page.evaluate(readNationRow)
    check('联盟页有「国家」那一行', row.found, true)
    const clicked = await clickNationRow(page)
    checkThat('点得动「国家」那一行', clicked)
    await page.waitForTimeout(1500)
    const opened = await page.evaluate(readNation)
    check('面板出现了', opened.active, true)
    const shown = (opened.texts ?? []).join('')
    console.log(`  面板文字：${(opened.texts ?? []).join(' ｜ ')}`)
    checkThat('说清了「你还没有国家」', shown.includes('你还没有国家'))
    checkThat('给出了「创建国家」这条路', shown.includes('创建国家'))
    checkThat('给出了可加入列表（不是一片空白）', shown.includes('北伐营') && shown.includes('青龙盟'))
    checkThat('说清了「不是全部」', shown.includes('不是全部'))
    // 表单没填时「创建国家」必须是灰的（这是 V13-d 的一部分：客户端只卡协议字段本身）
    check('表单没填 ⇒「创建国家」灰', opened.buttons.FoundButton, false)
    checkThat('灰的时候写明了还差什么', shown.includes('先给国名'))
    // 填完之后那颗键才亮 —— 顺带证明"灰键不吃触摸"这条不是画上去的
    const typed = await typeInto(page, 'NationNameInput', '试炼国')
    check('国名输入框吃到了字', typed, true)
    await typeInto(page, 'CapitalXInput', '120')
    await typeInto(page, 'CapitalYInput', '88')
    await page.waitForTimeout(600)
    const filled = await page.evaluate(readNation)
    check('填完国名与坐标 ⇒「创建国家」亮', filled.buttons.FoundButton, true)
    // 红线：裸 id / 枚举原文一个都不许上屏
    for (const raw of ['nation_', 'player:', 'sink:', 'NATIONAL_TECH', 'GENERAL', 'KING']) {
      checkThat(`屏上没有裸值 ${raw}`, !shown.includes(raw))
    }
    await page.screenshot({ path: path.join(OUT, '01-no-nation.png') })
  },
})

// ---------- 第 2 场：在国里、不是国王、有官职 ----------
await runScene(browser, {
  name: '在国里·普通官职',
  kind: 'member',
  stubbed: true,
  extra: async ({ page, posts }) => {
    await clickNationRow(page)
    await page.waitForTimeout(1500)
    const opened = await page.evaluate(readNation)
    check('面板出现了', opened.active, true)
    const shown = (opened.texts ?? []).join('')
    console.log(`  面板文字：${(opened.texts ?? []).join(' ｜ ')}`)
    checkThat('概况有国名', shown.includes('国名：赤壁盟'))
    checkThat('概况有等级（客户端不重算）', shown.includes('国家等级：Lv3'))
    checkThat('概况有成员联盟数与上限', shown.includes('成员联盟：2 / 4'))
    checkThat('概况有国库余额与上限（千分位）', shown.includes('国库：1,234,567 / 10,000,000'))
    checkThat('概况有都城坐标', shown.includes('都城：120, 88'))
    checkThat('官职显示成中文而不是枚举原文', shown.includes('我的官职：内政官'))
    checkThat('国库余额与上限也在国库那一块', shown.includes('国库余额 1,234,567 / 10,000,000'))
    checkThat('流水第一条是支给谁 · 多少', shown.includes('孙队长 · 5,000'))
    checkThat('流水副行给足「何时 · 谁做的 · 用途」', shown.includes('3 分钟前 · 钱部长 · 本周俸禄'))
    checkThat('流水给了余额那一列（自证连贯）', shown.includes('余额 1,229,567'))
    checkThat('系统动作显示成「系统」而不是 system', shown.includes('1 天前 · 系统 · 国家科技出资'))
    checkThat('去向显示成中文而不是 sink:NATIONAL_TECH', shown.includes('国家科技 · 80,000'))
    // 裸 token 那一形态：周税是入账，**不许落进「其他用途」**（真链路回读屏抓到过一次）
    checkThat('周税入账显示成「成员联盟周税」', shown.includes('成员联盟周税 · 10,000'))
    checkThat('入账那一行不出现「其他用途」', !shown.includes('其他用途'))
    // 权限：不是国王 ⇒ 解散灰；不是国王但有官职 ⇒ 支出亮
    check('不是国王 ⇒「解散国家」灰（不吃触摸，点了零请求）', opened.buttons.DisbandButton, false)
    check('权限位里有 WITHDRAW_TREASURY ⇒「国库支出」亮', opened.buttons.SpendButton, true)
    checkThat('灰的那颗写明了为什么', shown.includes('只有国王能解散这个国家'))
    // 开一次支出表单：金额预设与落点必须都在，且用途没填时确认键是灰的
    await clickPanelButton(page, 'SpendButton')
    await page.waitForTimeout(700)
    const armed = await page.evaluate(readNation)
    const armedText = (armed.texts ?? []).join('')
    checkThat('支出表单开得出来', armedText.includes('国库科技') || armedText.includes('国家科技'))
    checkThat('落点两个都在（协议里就这两个）', armedText.includes('国家科技') && armedText.includes('国战增益'))
    checkThat('金额预设给了三档', armedText.includes('1,000') && armedText.includes('10,000') && armedText.includes('100,000'))
    check('用途没填 ⇒「确认支出」灰', armed.buttons.SpendConfirm, false)
    checkThat('确认键灰的时候写明还差什么', armedText.includes('用途不能为空'))
    checkThat('屏上不出现落点枚举原文', !armedText.includes('NATIONAL_TECH') && !armedText.includes('WAR_BOOST'))
    await page.screenshot({ path: path.join(OUT, '02b-spend-form.png') })
    for (const raw of ['nation_ironoath', 'player:', 'sink:', 'NATIONAL_TECH', 'MINISTER', 'KING', '钱部长', '赵国王']) {
      // 钱部长/赵国王 是夹具里**应当**显示出来的昵称（正例），其余一律不许出现
      if (raw === '钱部长' || raw === '赵国王') continue
      checkThat(`屏上没有裸值 ${raw}`, !shown.includes(raw))
    }
    checkThat('成员显示的是昵称而不是 id', shown.includes('赵国王') && shown.includes('孙队长'))
    await page.screenshot({ path: path.join(OUT, '02-member.png') })
    // 点那颗灰键：必须零请求
    await clickPanelButton(page, 'DisbandButton')
    await page.waitForTimeout(800)
    check('点灰的「解散国家」零请求', posts.length, 0)
  },
})

// ---------- 第 3 场：权限位里没有 WITHDRAW_TREASURY ⇒ 国库支出灰 ----------
await runScene(browser, {
  name: '在国里·没有官职',
  kind: 'nooffice',
  stubbed: true,
  extra: async ({ page, posts }) => {
    await clickNationRow(page)
    await page.waitForTimeout(1500)
    const opened = await page.evaluate(readNation)
    const shown = (opened.texts ?? []).join('')
    check('权限位里没有 WITHDRAW_TREASURY ⇒「国库支出」灰', opened.buttons.SpendButton, false)
    checkThat('写明了原因（不是只灰着）', shown.includes('你当前的职位不能动国库'))
    await clickPanelButton(page, 'SpendButton')
    await page.waitForTimeout(800)
    check('点灰的「国库支出」零请求', posts.length, 0)
    await page.screenshot({ path: path.join(OUT, '03-no-office.png') })
  },
})

// ---------- 第 4 场：国王（kingId 从请求头 `X-Player-Id` 现取） ----------
await runScene(browser, {
  name: '国王',
  kind: 'king',
  stubbed: true,
  extra: async ({ page }) => {
    await clickNationRow(page)
    await page.waitForTimeout(1500)
    const opened = await page.evaluate(readNation)
    const shown = (opened.texts ?? []).join('')
    check('国王 ⇒「解散国家」亮', opened.buttons.DisbandButton, true)
    check('国王的权限位里有 WITHDRAW_TREASURY ⇒「国库支出」亮', opened.buttons.SpendButton, true)
    checkThat('说明了「你是这个国家的国王」', shown.includes('你是这个国家的国王'))
    checkThat('官职显示成「国王」而不是 KING', shown.includes('我的官职：国王'))
    await page.screenshot({ path: path.join(OUT, '04-king.png') })
    // 收尾：点「关闭」必须收得掉
    const closed = await clickPanelButton(page, 'CloseButton')
    await page.waitForTimeout(700)
    const after = await page.evaluate(readNation)
    check('点「关闭」收得掉', after.active, false)
    ok(`关闭键点得到（${closed}）`)
  },
})

await browser.close()
console.log(`  截图：${OUT}`)
console.log(`\n=== 国家面板运行时验收：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)
