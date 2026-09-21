/**
 * 职责：编队编辑弹层（B06 §4，`POST /hero/lineup`）的**运行时**验收 —— 真构建产物 + 真引擎里，
 *       从武将页的「编队」页签点某一套编队的行进去，走完"换槽位 → 选人 → 保存 → 回读"一整圈。
 * 依赖：node、playwright、一台能登录的后端（默认 8080）、已构建的 web-mobile 产物。
 *
 * 用法：LINEUP_ARTIFACT_ROOT=/d/tmp/tech-wt/client/build/web-mobile node tools/verify-lineup-runtime.mjs
 * 必填：LINEUP_BACKEND=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 LINEUP_PROBE_PORT（默认 8195，同机并发时换一个）
 *
 * <p><b>为什么 `/hero/list` 要经夹具替换</b>：dev 新号只长得出一两名武将（见
 * `tools/probe-gacha-real-account.mjs` 的实测：初始 GOLD 200，只够抽新手池一次），
 * 而编队这一屏要的是"三人一队 + 有人在别的队"那种形状 —— 真数据够不到。
 * 替换只发生在探针这一侧：夹具按契约必填字段构造，经客户端**自己的读路径**进去；
 * 写请求 `/hero/lineup` 打到桩上（桩把三槽最终态写回夹具，下一次 `/hero/list` 就是新的），
 * **不碰服务端任何存档**。于是被验的是"入口 → 编排 → 渲染 → 请求体 → 回读"这条真链路。
 *
 * <p>判据（12 组）：① 编队页签点得动；② 点某一队真能弹出编辑器（这一条此前是死的）；
 * ③ 三槽按存档起步、空位写「空」；④ 点一槽才摊出名单；⑤ 本队已占的那名灰并写原因；
 * ⑥ **在别的队的那名照常能点**，只标注（服务端不判跨队重复）；⑦ 选人只改界面不发请求；
 * ⑧ 保存发出的是三槽最终态那一条；⑨ 回读之后编队行上真的是新阵容；⑩ 板与板不压叠、字整盒在板内；
 * ⑪ 屏上不出现 heroId；⑫ 零页面错误。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const OUT = process.env.LINEUP_VERIFY_OUT ?? path.resolve(process.cwd(), 'client/build/lineup-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.LINEUP_PROBE_PORT ?? 8195)
// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.LINEUP_BACKEND ?? (() => {
  console.error('[lineup] 缺 LINEUP_BACKEND：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const ARTIFACT = process.env.LINEUP_ARTIFACT_ROOT ?? 'client/build/web-mobile'

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

const HEROES = [
  { heroId: 'hero_probe_a', name: '程远', power: 1200 },
  { heroId: 'hero_probe_b', name: '沈牧', power: 1500 },
  { heroId: 'hero_probe_c', name: '李劲', power: 2400 },
].map((row) => ({
  rarity: 'SR', level: 30, exp: 0, expToNext: 500, maxLevel: 60,
  star: 1, maxStar: 5, awaken: 0, maxAwaken: 2,
  mainSkillId: 'skill_a', mainSkillName: '破阵', mainSkillLevel: 1,
  subSkillId: 'skill_b', subSkillName: '蓄势', subSkillLevel: 1, maxSkillLevel: 10,
  equips: [], baseAttrs: { might: 80, command: 70, wisdom: 60 },
  finalAttrs: { might: 80, command: 70, wisdom: 60 }, bondWith: null, ...row,
}))

/** 桩状态：第 1 队主将是程远，第 2 队主将是沈牧，第 3 队空。保存时按三槽最终态写回。 */
const fixture = {
  lineups: [
    { presetIndex: 0, main: 'hero_probe_a', sub1: null, sub2: null },
    { presetIndex: 1, main: 'hero_probe_b', sub1: null, sub2: null },
    { presetIndex: 2, main: null, sub1: null, sub2: null },
  ],
  lineupCalls: [],
  heroReads: 0,
}

const heroList = () => ({
  heroes: HEROES,
  lineups: fixture.lineups.map((row) => ({
    ...row,
    bonus: { atkFixed: 0, defFixed: 0, skillFixed: 0, commandValue: 180, capped: false, breakdown: [] },
    activeBonds: [],
  })),
  fragments: [], troopCap: 1200, troopsInUse: 0, serverNow: Date.now(),
})

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
try {
  await fetch(`${BACKEND}/time/sync`, { method: 'POST' })
} catch (error) {
  console.error(`起跑前直连后端失败：${error.message}\n后端 ${BACKEND} 起来了吗？`)
  process.exit(2)
}
console.log(`=== 编队编辑运行时验收：产物经 ${preview.origin}，后端 ${BACKEND}（/hero/list 与 /hero/lineup 经夹具替换）===`)

const NODE_PATH = 'window.cc.director.getScene().getChildByName("Canvas").getChildByName("Game")'

/** 读一个子树里的 Label（含盒高）与所有"板"（行 / 槽 / 名单 / 按钮）。 */
const SNAPSHOT = (rootName) => `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === ${JSON.stringify(rootName)})
  if (!root) return null
  const labels = []
  const plates = []
  const heroRows = []
  const walk = (n) => {
    if (!n.active) return
    const t = n.getComponent('cc.UITransform')
    const label = n.getComponent('cc.Label')
    if (label && label.string) {
      labels.push({ text: label.string, x: n.getPosition().x, y: n.getPosition().y,
        h: t ? t.contentSize.height : -1 })
    }
    if (/^(slot-|pick-|pool-|card$|cancel$|save$|clearSlot$|HeroRow$)/.test(n.name)
        && t && t.contentSize.width > 40) {
      plates.push({ name: n.name, x: n.getPosition().x, y: n.getPosition().y,
        w: t.contentSize.width, h: t.contentSize.height })
    }
    if (n.name === 'HeroRow') {
      const texts = []
      const collect = (m) => {
        const l = m.getComponent('cc.Label')
        if (l && l.string) texts.push(l.string)
        for (const c of m.children) collect(c)
      }
      collect(n)
      heroRows.push({ y: n.getPosition().y, texts })
    }
    for (const child of n.children) walk(child)
  }
  walk(root)
  heroRows.sort((a, b) => b.y - a.y)
  return { active: root.active, labels, plates, heroRows }
})()`

/** 按下某个名字的节点（限定子树，避免按到别的弹层里同名的按钮）。 */
const TAP = (rootName, name) => `(() => {
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
  if (found === null) return 'missing'
  found.emit('touch-start')
  return 'tapped'
})()`

/** 按下第 index 个编队行（行是池化节点，同名，只能按顺序取）。 */
const TAP_ROW = (rootName, index) => `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === ${JSON.stringify(rootName)})
  if (!root) return 'missing-root'
  const rows = []
  const walk = (n) => {
    if (!n.active) return
    if (n.name === 'HeroRow') rows.push(n)
    for (const child of n.children) walk(child)
  }
  walk(root)
  rows.sort((a, b) => b.getPosition().y - a.getPosition().y)
  const target = rows[${index}]
  if (target === undefined) return 'missing-row'
  target.emit('touch-start')
  return 'tapped'
})()`

const has = (snapshot, text) => snapshot !== null && snapshot.labels.some((l) => l.text.includes(text))
const textOf = (snapshot) => snapshot === null ? '' : snapshot.labels.map((l) => l.text).join('\n')

/** 板与板两两矩形不相交（招募那一格刚踩到的判据，这里同一把尺子）。 */
const plateCollisions = (snapshot) => {
  const list = snapshot === null ? [] : snapshot.plates
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
  // 名单与槽位都在同一列上，彼此之间是"上下排"，所以只报真的压叠
  return hits
}

/** 一行的两行字整盒落在自己那块板里。 */
const linesInsidePlate = (snapshot, plateName, texts) => {
  const plate = snapshot.plates.find((p) => p.name === plateName)
  if (plate === undefined) return false
  return texts.every((text) => {
    const label = snapshot.labels.find((l) => l.text.includes(text))
    return label !== undefined && label.h > 0
      && Math.abs(label.y - plate.y) + label.h / 2 <= plate.h / 2 + 0.5
  })
}

/**
 * **每一块**行板里的字都整盒在里面（原先只抽查第二行，于是最后一行被按钮带切掉半截而全绿）。
 * 做法：对每块 slot-/pick- 板，取"离它中心最近的那两条字"，逐条量盒子是否越出板外。
 */
const everyRowFitsItsPlate = (snapshot) => {
  const offenders = []
  for (const plate of snapshot.plates) {
    if (!/^(slot-|pick-)/.test(plate.name)) continue
    // 只认"中心落在这块板带里"的字：底下那行「另有 N 名未列出」离得近但不属于这一行
    const near = snapshot.labels.filter((l) => Math.abs(l.y - plate.y) < plate.h / 2 && l.h > 0)
    for (const label of near) {
      if (Math.abs(label.y - plate.y) + label.h / 2 > plate.h / 2 + 0.5) {
        offenders.push(`${plate.name}:${label.text.slice(0, 6)}`)
      }
    }
  }
  return offenders
}

/** 最后一块行板与按钮带之间必须留出空气（截图抓到的就是这一格被按钮切掉半截）。 */
const rowButtonGap = (snapshot) => {
  const rows = snapshot.plates.filter((p) => /^(slot-|pick-)/.test(p.name))
  const buttons = snapshot.plates.filter((p) => /^(cancel|save|clearSlot)$/.test(p.name))
  if (rows.length === 0 || buttons.length === 0) return -1
  const lowestRow = rows.reduce((a, b) => (a.y - a.h / 2 < b.y - b.h / 2 ? a : b))
  const highestButton = buttons.reduce((a, b) => (a.y + a.h / 2 > b.y + b.h / 2 ? a : b))
  return lowestRow.y - lowestRow.h / 2 - (highestButton.y + highestButton.h / 2)
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const deviceId = `lineup-runtime-${Date.now()}`
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, deviceId)

const cors = (request) => ({
  'access-control-allow-origin': request.headers()['origin'] ?? '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
})
const stub = (pathName, make) => context.route(`**${pathName}*`, async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  if (pathName === '/hero/list') {
    fixture.heroReads += 1
  } else if (pathName === '/hero/lineup') {
    const body = JSON.parse(request.postData() ?? '{}')
    fixture.lineupCalls.push(body)
    const row = fixture.lineups.find((lineup) => lineup.presetIndex === body.presetIndex)
    if (row !== undefined) {
      // 桩做服务端做的事：三槽整体覆盖（null 就是这一位没人）
      row.main = body.main ?? null
      row.sub1 = body.sub1 ?? null
      row.sub2 = body.sub2 ?? null
    }
  }
  await route.fulfill({
    status: 200,
    headers: { ...cors(request), 'content-type': 'application/json' },
    body: JSON.stringify({ code: 0, msg: '成功', data: make(), serverNow: Date.now() }),
  })
})
await stub('/hero/list', heroList)
await stub('/hero/lineup', () => ({
  lineups: heroList().lineups, troopCap: 1200, serverNow: Date.now(),
}))

const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text())
})

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'hero')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
// 自检：产物里那两处写死的后端地址有没有真的被改写成本轮要打的那棵。
// 漏了这一句，传错变量名就是"打到另一台机器上读数"，红得像是产品缺陷（台账 #371）。
preview.assertRewritten()
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await hideGuideOverlay(page)
await page.waitForTimeout(2200)

const hero = await page.evaluate(SNAPSHOT('hero'))
checkTrue('武将页挂上了', hero !== null)
checkTrue('三名夹具武将进了画面', has(hero, '程远') && has(hero, '李劲'))
check('切到编队页签', await page.evaluate(TAP('hero', 'Tab_lineups')), 'tapped')
await page.waitForTimeout(600)
const lineupTab = await page.evaluate(SNAPSHOT('hero'))
checkTrue('编队页画出三套预设（措辞与 HeroPanel.presetText 同一份：编队 N）',
  has(lineupTab, '编队 1') && has(lineupTab, '编队 3'))
check('点第 2 套编队那一行（这一条此前从来没接过：点了什么都不发生）',
  await page.evaluate(TAP_ROW('hero', 1)), 'tapped')
await page.waitForTimeout(700)

let overlay = await page.evaluate(SNAPSHOT('lineupEdit'))
checkTrue('编队编辑弹层挂上且已激活', overlay !== null && overlay.active)
checkTrue('标题是「编队 2 · 编队编辑」（presetIndex 1 画给玩家要 +1，措辞与编队行一致）',
  has(overlay, '编队 2 · 编队编辑'))
check('三槽都画着（空位也在，写「空」）',
  overlay.plates.filter((p) => p.name.startsWith('slot-')).length, 3)
checkTrue('主将位是沈牧（存档起步），其余两槽写空',
  has(overlay, '沈牧') && has(overlay, '空'))
check('没点任何一槽时不摊出名单',
  overlay.plates.filter((p) => p.name.startsWith('pick-')).length, 0)
checkTrue('保存键写着当前人数', has(overlay, '保存 · 1 名'))

check('点「副将一」那一槽', await page.evaluate(TAP('lineupEdit', 'slot-sub1')), 'tapped')
await page.waitForTimeout(600)
overlay = await page.evaluate(SNAPSHOT('lineupEdit'))
const picks = overlay.plates.filter((p) => p.name.startsWith('pick-')).map((p) => p.name)
check('摊出名单：三名武将都在（能点的排前面）', picks.length, 3)
checkTrue('在别的队的那名（程远在第 1 队）**照常能点**，只标注',
  has(overlay, '已在第 1 队'))
checkTrue('本队主将位占着的那名灰并写原因', has(overlay, '已在本队其他槽位'))
checkTrue('屏上不出现 heroId（#255 一路同族判据）',
  !/hero_probe_/.test(textOf(overlay)))
checkTrue('槽位行与名单行整盒不压叠', plateCollisions(overlay).join(','), '')
checkTrue('名单行两行字整盒在板里',
  linesInsidePlate(overlay, 'pick-hero_probe_c', ['李劲', '战力 2400']))
check('每一块行板里的字都整盒在里面（抽查会漏掉最后一行）',
  everyRowFitsItsPlate(overlay).join(','), '')
checkTrue('最后一块行板与按钮带之间留出空气（截图抓到过被切半截）',
  rowButtonGap(overlay) >= 8)
await page.screenshot({ path: path.join(OUT, 'lineup-picks.png') })

const callsBefore = fixture.lineupCalls.length
check('选李劲进副将一', await page.evaluate(TAP('lineupEdit', 'pick-hero_probe_c')), 'tapped')
await page.waitForTimeout(500)
overlay = await page.evaluate(SNAPSHOT('lineupEdit'))
check('换人只改界面，一条请求都不发', fixture.lineupCalls.length, callsBefore)
checkTrue('槽位立刻显出新人（画的是编辑中的状态，不是存档）', has(overlay, '李劲'))
checkTrue('保存键跟着变成 2 名', has(overlay, '保存 · 2 名'))

const readsBefore = fixture.heroReads
check('点保存', await page.evaluate(TAP('lineupEdit', 'save')), 'tapped')
await page.waitForTimeout(1500)
check('发出了一条 /hero/lineup', fixture.lineupCalls.length, callsBefore + 1)
const sent = fixture.lineupCalls.at(-1)
check('带的是第 2 队', sent?.presetIndex, 1)
check('三槽最终态：主将沈牧 + 副将一李劲 + 副将二 null',
  JSON.stringify([sent?.main, sent?.sub1, sent?.sub2]), JSON.stringify(['hero_probe_b', 'hero_probe_c', null]))
checkTrue('requestId 合法（长度 [8,64]）',
  typeof sent?.requestId === 'string' && sent.requestId.length >= 8 && sent.requestId.length <= 64)
check('保存完重读武将列表', fixture.heroReads, readsBefore + 1)
check('编辑器已收起（保存即关：再点不该发第二条）',
  (await page.evaluate(SNAPSHOT('lineupEdit')))?.active, false)
await page.evaluate(TAP('hero', 'Tab_lineups'))
await page.waitForTimeout(700)
const after = await page.evaluate(SNAPSHOT('hero'))
checkTrue('回读之后编队那一行真的写着李劲（存档被服务端改过了）', has(after, '李劲'))
await page.screenshot({ path: path.join(OUT, 'lineup-after-save.png') })

check('再进去点「清空这一槽」这条路：先开编辑器', await page.evaluate(TAP_ROW('hero', 1)), 'tapped')
await page.waitForTimeout(600)
check('点副将二（空的）', await page.evaluate(TAP('lineupEdit', 'slot-sub2')), 'tapped')
await page.waitForTimeout(400)
check('选程远（他在第 1 队，但服务端不判跨队 ⇒ 照常能点）',
  await page.evaluate(TAP('lineupEdit', 'pick-hero_probe_a')), 'tapped')
await page.waitForTimeout(400)
check('再点副将二，然后清空', await page.evaluate(TAP('lineupEdit', 'slot-sub2')), 'tapped')
await page.waitForTimeout(300)
const callsNow = fixture.lineupCalls.length
check('点「清空这一槽」', await page.evaluate(TAP('lineupEdit', 'clearSlot')), 'tapped')
await page.waitForTimeout(400)
check('清空只是改界面，不发请求', fixture.lineupCalls.length, callsNow)
checkTrue('那一槽回到「空」', has(await page.evaluate(SNAPSHOT('lineupEdit')), '空'))
check('取消：什么都不发', await page.evaluate(TAP('lineupEdit', 'cancel')), 'tapped')
await page.waitForTimeout(400)
check('取消后弹层收起', (await page.evaluate(SNAPSHOT('lineupEdit')))?.active, false)
check('取消不发任何请求', fixture.lineupCalls.length, callsNow)

check('零页面错误', errors.length, 0)
if (errors.length > 0) {
  console.log(errors.slice(0, 6).join('\n'))
}

await browser.close()
console.log(`\n=== ${pass} PASS / ${fail} FAIL，截图落在 ${OUT} ===`)
process.exit(fail === 0 ? 0 : 1)
