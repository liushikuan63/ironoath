/**
 * 职责：武将技能弹层（V03-d 最后一条养成线）的**运行时**验收 —— 真构建产物 + 真引擎里，
 *       从武将页的「技能」按钮点进去，走完"选书 → 确认 → 回读 → 再看一眼等级"一整圈。
 * 依赖：node、playwright、一台能登录的后端（默认 8080）、已构建的 web-mobile 产物。
 *
 * 用法：SKILL_ARTIFACT_ROOT=/d/tmp/tech-wt/client/build/web-mobile node tools/verify-skill-runtime.mjs
 * 必填：SKILL_BACKEND=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 SKILL_PROBE_PORT（默认 8188，同机并发时换一个）
 *
 * <p><b>读接口经夹具替换</b>（与 `verify-awaken-runtime.mjs` 同一套做法与同一句理由）：
 * dev 新号 `heroes=0`，而这一屏要的是"有一个武将、他两路技能一路满级一路没满、背包里三本同型技能书"
 * —— 真数据下够不到。夹具按契约必填字段构造、经客户端自己的读路径进去，写请求打到桩上并由桩推进状态，
 * 于是被验的仍是"入口 → 编排 → 渲染 → 请求体 → 回读"这条真链路。
 *
 * <p><b>这一格最该被钉住的一件事</b>：发出去的 `skillSlot` 必须来自那本书的 `effectTarget`。
 * 夹具故意把副技能做成已满级、又放了一本没标注的书 ⇒ 三行里只有一本可点，
 * 一旦客户端改成"先选槽位再赌一本书"，第 15 组判据就会红。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const OUT = process.env.SKILL_VERIFY_OUT ?? path.resolve(process.cwd(), 'client/build/skill-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.SKILL_PROBE_PORT ?? 8188)
// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.SKILL_BACKEND ?? (() => {
  console.error('[skill] 缺 SKILL_BACKEND：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const ARTIFACT = process.env.SKILL_ARTIFACT_ROOT ?? 'client/build/web-mobile'

const HERO_ID = 'hero_probe_guanyu'
const MAIN_BOOK = 'item_hero_skillbook_main'
const SUB_BOOK = 'item_hero_skillbook_sub'
const ODD_BOOK = 'item_hero_skillbook_odd'

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

const SERVER_NOW = Date.now()

/** 夹具状态：主技能 3/10（可升），副技能 10/10（满级），另有一本没标主副的书。 */
const fixture = {
  mainLevel: 3,
  subLevel: 10,
  maxLevel: 10,
  held: { [MAIN_BOOK]: 3, [SUB_BOOK]: 2, [ODD_BOOK]: 1 },
  skillCalls: [],
  heroReads: 0,
}

const heroView = () => ({
  heroId: HERO_ID, name: '关羽', rarity: 'SSR', level: 40, exp: 1200, expToNext: 800,
  maxLevel: 60, star: 3, maxStar: 5, awaken: 0, maxAwaken: 3,
  mainSkillId: 'skill_guanyu_main', mainSkillName: '武圣激将', mainSkillLevel: fixture.mainLevel,
  subSkillId: 'skill_guanyu_sub', subSkillName: '偃月蓄势', subSkillLevel: fixture.subLevel, maxSkillLevel: fixture.maxLevel,
  // 穿一件在身上：这一格的验收点就是那一行印的是名字与 +N，而不是 uid / 行 id
  equips: [{ slot: 'WEAPON', uid: 'eq-7f3a', equipId: 'eq_iron_sword', name: '铁剑', forgeLevel: 2 }],
  baseAttrs: { might: 96, command: 92, wisdom: 75 },
  finalAttrs: { might: 96, command: 92, wisdom: 75 },
  power: 12345, bondWith: null,
})

const heroList = () => ({
  heroes: [heroView()],
  lineups: [0, 1, 2].map((presetIndex) => ({
    presetIndex, main: null, sub1: null, sub2: null,
    bonus: { atkFixed: 0, defFixed: 0, skillFixed: 0, commandValue: 0, capped: false, breakdown: [] },
    activeBonds: [],
  })),
  fragments: [], troopCap: 1000, troopsInUse: 0, serverNow: SERVER_NOW,
})

const BOOKS = [
  { itemId: MAIN_BOOK, name: '主技能秘卷', rarity: 'SR', effectTarget: 'MAIN', sortKey: 10 },
  { itemId: SUB_BOOK, name: '副技能残卷', rarity: 'R', effectTarget: 'SUB', sortKey: 11 },
  // 没标 effectTarget 的那本：客户端不许猜它升哪一路
  { itemId: ODD_BOOK, name: '无字残页', rarity: 'R', effectTarget: null, sortKey: 12 },
]

const bagList = () => ({
  items: BOOKS.map((book) => ({
    itemId: book.itemId, name: book.name, type: 'MATERIAL', rarity: book.rarity,
    obtainFrom: '章节宝箱', count: fixture.held[book.itemId], stackMax: 999,
    sortKey: book.sortKey, effectKind: 'UP_HERO_SKILL', effectTarget: book.effectTarget,
    // 服务端不把余数为 0 的行留在背包里，夹具照做
  })).filter((item) => item.count > 0),
  capacityUsed: 3, capacityMax: 200,
})

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
try {
  await fetch(`${BACKEND}/time/sync`, { method: 'POST' })
} catch (error) {
  console.error(`起跑前直连后端失败：${error.message}\n后端 ${BACKEND} 起来了吗？`)
  process.exit(2)
}
console.log(`=== 技能弹层运行时验收：产物经 ${preview.origin}，后端 ${BACKEND}（三个路径经夹具替换）===`)

const NODE_PATH = 'window.cc.director.getScene().getChildByName("Canvas").getChildByName("Game")'

/** 组件只按注册名取（`getComponent('cc.Label')`）：release 构建里类名被压缩、cc 上也没有那些键。 */
const SNAPSHOT = (rootName) => `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === ${JSON.stringify(rootName)})
  if (!root) return null
  const labels = []
  const rows = []
  const footers = []
  const walk = (n) => {
    const label = n.getComponent('cc.Label')
    if (label && label.string) {
      labels.push({ text: label.string, x: n.worldPosition.x, y: n.worldPosition.y })
    }
    const box = n.getComponent('cc.UITransform')
    if (/^skill-/.test(n.name)) {
      rows.push({ name: n.name, y: n.worldPosition.y, h: box ? box.contentSize.height : -1 })
    }
    if (n.name === 'confirm' || n.name === 'cancel') {
      footers.push({ name: n.name, x: n.worldPosition.x, y: n.worldPosition.y })
    }
    for (const child of n.children) walk(child)
  }
  walk(root)
  return { active: root.active, labels, rows, footers }
})()`

/** 在指定子树里按名字找节点并按下（升级 / 觉醒弹层里也有 confirm，必须限定子树）。 */
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
  found.emit(found.hasEventListener('touch-end') ? 'touch-end' : 'touch-start')
  return 'tapped'
})()`

const has = (snapshot, text) => snapshot !== null && snapshot.labels.some((l) => l.text.includes(text))

/** 一行的两行字是否都落在自己那块底板里（判据来自画出来的几何，不是探针侧另抄的常量）。 */
const linesInsidePlate = (snapshot, itemId, texts) => {
  const row = snapshot.rows.find((r) => r.name === `skill-${itemId}`)
  if (row === undefined) {
    return false
  }
  return texts.every((text) => {
    const label = snapshot.labels.find((l) => l.text.includes(text))
    return label !== undefined && Math.abs(label.y - row.y) <= row.h / 2
  })
}

/** 只读某个底部按钮自己那一条字（整屏文本里同样的字也会出现在别处）。 */
const footerText = (snapshot, name) => {
  const footer = snapshot.footers.find((f) => f.name === name)
  if (footer === undefined) {
    return '(没有这个按钮)'
  }
  return snapshot.labels
    .filter((l) => l.y === footer.y && l.x === footer.x)
    .map((l) => l.text).join('|')
}

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const deviceId = `skill-runtime-${Date.now()}`
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, deviceId)

const cors = (request) => ({
  'access-control-allow-origin': request.headers()['origin'] ?? '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
})
const stub = (pathName, make) => context.route(`**${pathName}`, async (route) => {
  const request = route.request()
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(request) })
    return
  }
  if (pathName === '/hero/list') {
    fixture.heroReads += 1
  }
  if (pathName === '/hero/skillUp') {
    const body = JSON.parse(request.postData() ?? '{}')
    fixture.skillCalls.push(body)
    // 桩自己推进：一本换一级，余数跟着减（服务端真做的事，夹具照着做）
    if (body.skillSlot === 'MAIN') {
      fixture.mainLevel += 1
    } else if (body.skillSlot === 'SUB') {
      fixture.subLevel += 1
    }
    fixture.held[body.itemId] -= 1
  }
  await route.fulfill({
    status: 200,
    headers: { ...cors(request), 'content-type': 'application/json' },
    body: JSON.stringify({ code: 0, msg: '成功', data: make(), serverNow: Date.now() }),
  })
})
await stub('/hero/list', heroList)
await stub('/bag/list', bagList)
await stub('/hero/skillUp', () => ({
  hero: heroView(), consumed: [{ itemId: MAIN_BOOK, count: 1 }], serverNow: Date.now(),
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
await page.waitForTimeout(2000)

const hero = await page.evaluate(SNAPSHOT('hero'))
checkTrue('武将页挂上了', hero !== null)
checkTrue('夹具里那个武将进画面', has(hero, '关羽'))
checkTrue('行上带主技能当前等级', has(hero, `Lv${fixture.mainLevel}/`))
checkTrue('技能那一行印的是中文名（服务端随视图下发的 mainSkillName）', has(hero, '武圣激将'))
check('画面上不出现 skill 表的行 id（#255 同族第三处的回归位）', has(hero, 'skill_guanyu_main'), false)
checkTrue('装备那一行印的是名字与强化等级（同族第五处）', has(hero, '武器：铁剑 +2'))
check('那一行不出现实例 uid 与 equip 行 id',
  has(hero, 'eq-7f3a') || has(hero, 'eq_iron_sword'), false)
// 弹层会盖住武将行，那张截图看不到技能那一行 —— 进弹层之前先留一张没遮挡的
await page.screenshot({ path: path.join(OUT, 'hero-row.png') })

const before = await page.evaluate(SNAPSHOT('skillPick'))
checkTrue('技能弹层挂上了（skillPick 节点在）', before !== null)
check('没点「技能」时弹层不激活（不占首屏）', before?.active, false)

check('点武将页的「技能」按钮', await page.evaluate(TAP('hero', 'SkillButton')), 'tapped')
await page.waitForTimeout(500)
let opened = await page.evaluate(SNAPSHOT('skillPick'))
check('弹层已激活', opened?.active, true)
check('三本书都列出来（满级与没标的都照样画）', opened?.rows.length, 3)
check('三行就是那三本（按背包顺序）', opened?.rows.map((r) => r.name).join(','),
  `skill-${MAIN_BOOK},skill-${SUB_BOOK},skill-${ODD_BOOK}`)
checkTrue('标题带武将名字', has(opened, '给 关羽 升技能'))
checkTrue('可用那本写明升哪一路、从几级到几级', has(opened,
  `主技能 Lv${fixture.mainLevel} → Lv${fixture.mainLevel + 1}（上限 ${fixture.maxLevel}） · 持有 3`))
checkTrue('满级那本写满级，而不是继续给等级变化', has(opened, '副技能已满级'))
checkTrue('没标注那本说明是配置的事，而不是猜一路', has(opened, '这本没标注主 / 副技能'))
checkTrue('三行的两行字都落在自己那块底板里',
  linesInsidePlate(opened, MAIN_BOOK, ['主技能秘卷', '主技能 Lv3'])
  && linesInsidePlate(opened, SUB_BOOK, ['副技能残卷', '副技能已满级'])
  && linesInsidePlate(opened, ODD_BOOK, ['无字残页', '这本没标注主 / 副技能']))
check('相邻两块底板不重叠',
  Math.abs(opened.rows[0].y - opened.rows[1].y) >= (opened.rows[0].h + opened.rows[1].h) / 2, true)
check('没选书之前确认键是灰的', footerText(opened, 'confirm'), '先选技能书')

check('点满级那本', await page.evaluate(TAP('skillPick', `skill-${SUB_BOOK}`)), 'tapped')
await page.waitForTimeout(300)
opened = await page.evaluate(SNAPSHOT('skillPick'))
check('满级那本点了不算选中', footerText(opened, 'confirm'), '先选技能书')
check('页面上没有「已选」', has(opened, '已选'), false)

check('点没标注那本', await page.evaluate(TAP('skillPick', `skill-${ODD_BOOK}`)), 'tapped')
await page.waitForTimeout(300)
opened = await page.evaluate(SNAPSHOT('skillPick'))
check('没标注那本点了也不算选中（客户端不许猜槽位）', has(opened, '已选'), false)

check('点可用那本', await page.evaluate(TAP('skillPick', `skill-${MAIN_BOOK}`)), 'tapped')
await page.waitForTimeout(300)
opened = await page.evaluate(SNAPSHOT('skillPick'))
check('选中后出现「已选」', has(opened, '已选'), true)
check('确认键转成可发态', footerText(opened, 'confirm'), '确认升级')
await page.screenshot({ path: path.join(OUT, 'skill-picked.png') })

const callsBefore = fixture.skillCalls.length
const readsBefore = fixture.heroReads
check('点确认', await page.evaluate(TAP('skillPick', 'confirm')), 'tapped')
await page.waitForTimeout(1500)
check('真的发出了一次 /hero/skillUp', fixture.skillCalls.length, callsBefore + 1)
const sent = fixture.skillCalls.at(-1)
check('带上选中的那一本', sent?.itemId, MAIN_BOOK)
check('槽位就是那本书标的 MAIN（不是玩家先选的）', sent?.skillSlot, 'MAIN')
check('升完重读武将', fixture.heroReads > readsBefore, true)
check('确认之后弹层收起', (await page.evaluate(SNAPSHOT('skillPick')))?.active, false)
checkTrue('回读后武将行上的技能等级跟着变',
  has(await page.evaluate(SNAPSHOT('hero')), `Lv${fixture.mainLevel}/`))

check('升一级后再点「技能」', await page.evaluate(TAP('hero', 'SkillButton')), 'tapped')
await page.waitForTimeout(400)
const again = await page.evaluate(SNAPSHOT('skillPick'))
checkTrue('弹层里的等级接着往上走（Lv4 → Lv5）',
  has(again, `主技能 Lv${fixture.mainLevel} → Lv${fixture.mainLevel + 1}`))
checkTrue('余数也跟着减（3 → 2）', has(again, ' · 持有 2'))
await page.screenshot({ path: path.join(OUT, 'skill-after.png') })

check('零页面错误', errors.length, 0)
if (errors.length > 0) {
  console.log(errors.slice(0, 6).join('\n'))
}

await browser.close()
console.log(`\n=== ${pass} PASS / ${fail} FAIL，截图落在 ${OUT} ===`)
process.exit(fail === 0 ? 0 : 1)
