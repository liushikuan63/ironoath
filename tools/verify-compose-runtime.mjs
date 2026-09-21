/**
 * 职责：武将「碎片合成」弹层（V03-d 第六条养成线）的**运行时**验收 —— 真构建产物 + 真引擎里，
 *       从武将页页眉那颗「碎片合成」点进去，走完"看差几片 → 选人 → 确认 → 回读"一整圈。
 * 依赖：node、playwright、一台能登录的后端（默认 8080）、已构建的 web-mobile 产物。
 *
 * 用法：COMPOSE_ARTIFACT_ROOT=/d/tmp/tech-wt/client/build/web-mobile node tools/verify-compose-runtime.mjs
 *
 * <p><b>为什么 `/hero/list` 要经本探针替换</b>：dev 上的新号 `heroes=0`，而碎片是抽卡重复保底与
 * 活动的产物 —— 刚建档的号既没有"差一片"也没有"刚好够"这两态，这一屏在真数据下够不到
 * （装备页、觉醒弹层为同一道门，见台账 #267 / #273）。替换只发生在探针这一侧：夹具按契约的
 * 必填字段构造，经客户端**自己的读路径**进去，于是被验的是"入口 → 编排 → 渲染 → 写请求 → 回读"
 * 这条真链路，而不是某个手搓的视图对象。写请求 `/hero/compose` 打到桩上（桩自己推进夹具状态），
 * **不碰服务端任何存档**。期望值全部由夹具常量独立算出，不从页面抄。
 *
 * <p>判据（12 组）：① 弹层挂上且不占首屏；② 点页眉那颗真能弹出；③ 三行候选都列出来（不够的也在）；
 * ④ 差几片写在行上、够的写"已够"；⑤ 抬头那两行钱包 = `fragmentTexts`（#281 欠的消费者）；
 * ⑥ 灰行点了不算选中；⑦ 可用行点了出「已选」、确认键转绿；⑧ 字落在自己那块底板里、行不被按钮压住；
 * ⑨ 确认发出那一条请求（带选中的 heroId 与合法 requestId）；⑩ 回读后候选少一名、钱包跟着扣；
 * ⑪ 候选画不下时写"另有 N 名未列出"而不是静默截断；⑫ 取消不发请求、零页面错误。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const OUT = process.env.COMPOSE_VERIFY_OUT ?? path.resolve(process.cwd(), 'client/build/compose-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.COMPOSE_PROBE_PORT ?? 8191)
// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.COMPOSE_BACKEND ?? (() => {
  console.error('[compose] 缺 COMPOSE_BACKEND：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const ARTIFACT = process.env.COMPOSE_ARTIFACT_ROOT ?? 'client/build/web-mobile'

const SR_ITEM = 'item_mat_hero_frag_sr'
const SSR_ITEM = 'item_mat_hero_frag_ssr'
const SR_NEED = 50
const SSR_NEED = 80
const OWNED_ID = 'hero_probe_guanyu'
const SR_POOL = [
  { heroId: 'hero_probe_chengyuan', name: '程远' },
  { heroId: 'hero_probe_shenmu', name: '沈牧' },
]
const SSR_POOL = [{ heroId: 'hero_probe_liji', name: '李劲' }]
/** 第二幕用的"候选多到画不下"：10 名额外 SSR 武将。 */
const WIDE_POOL = Array.from({ length: 10 }, (_, i) => ({
  heroId: `hero_probe_wide_${i}`, name: `杂号将军${i + 1}`,
}))

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

const fixture = {  /** 差 38 片 */
  srHeld: 12,
  /** 刚好凑够 */
  ssrHeld: SSR_NEED,
  /** 桩推进的账：合成成功的进名册 */
  composed: [],
  composeCalls: [],
  heroReads: 0,
  wide: false,
}

const ownedHero = () => ({
  heroId: OWNED_ID, name: '关羽', rarity: 'SSR', level: 40, exp: 1200, expToNext: 800,
  maxLevel: 60, star: 3, maxStar: 5, awaken: 1, maxAwaken: 3,
  mainSkillId: 'skill_guanyu_main', mainSkillName: '武圣激将', mainSkillLevel: 3,
  subSkillId: 'skill_guanyu_sub', subSkillName: '偃月蓄势', subSkillLevel: 1, maxSkillLevel: 10,
  equips: [],
  baseAttrs: { might: 96, command: 92, wisdom: 75 },
  finalAttrs: { might: 96, command: 92, wisdom: 75 },
  power: 12345, bondWith: null,
})

const composedView = (heroId) => ({
  ...ownedHero(), heroId,
  name: [...SR_POOL, ...SSR_POOL, ...WIDE_POOL].find((h) => h.heroId === heroId)?.name ?? heroId,
  rarity: SR_POOL.some((h) => h.heroId === heroId) ? 'SR' : 'SSR',
  power: 9000,
})

/** 候选＝这一档全部减去已拥有（含刚合成进来的），与生产的服务端算法同一件事。 */
const candidatesOf = (pool) => pool.filter((h) => !fixture.composed.includes(h.heroId))

const heroList = () => ({
  heroes: [ownedHero(), ...fixture.composed.map(composedView)],
  lineups: [0, 1, 2].map((presetIndex) => ({
    presetIndex, main: null, sub1: null, sub2: null,
    bonus: { atkFixed: 0, defFixed: 0, skillFixed: 0, commandValue: 0, capped: false, breakdown: [] },
    activeBonds: [],
  })),
  fragments: [
    {
      itemId: SR_ITEM, name: 'SR 武将碎片', count: fixture.srHeld, composeFragment: SR_NEED,
      candidates: candidatesOf(SR_POOL),
    },
    {
      itemId: SSR_ITEM, name: 'SSR 武将碎片', count: fixture.ssrHeld, composeFragment: SSR_NEED,
      candidates: candidatesOf(fixture.wide ? [...SSR_POOL, ...WIDE_POOL] : SSR_POOL),
    },
  ],
  troopCap: 1000, troopsInUse: 0, serverNow: Date.now(),
})

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
try {
  await fetch(`${BACKEND}/time/sync`, { method: 'POST' })
} catch (error) {
  console.error(`起跑前直连后端失败：${error.message}\n后端 ${BACKEND} 起来了吗？`)
  process.exit(2)
}
console.log(`=== 碎片合成弹层运行时验收：产物经 ${preview.origin}，后端 ${BACKEND}（/hero/list 与 /hero/compose 经夹具替换）===`)

const NODE_PATH = 'window.cc.director.getScene().getChildByName("Canvas").getChildByName("Game")'

/**
 * 读回弹层里的 Label 文本、行几何、底部按钮与卡片高度。
 *
 * <p>组件一律按注册名取（`getComponent('cc.Label')`）：`debug=false` 的构建把类名压缩掉，
 * 按 `constructor.name` 匹配读回来是空的 —— 症状不是报错而是所有文本判据一起变 false；
 * `window.cc.UITransform` 这个键在 release 包里也不存在。
 */
const SNAPSHOT = (rootName) => `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === ${JSON.stringify(rootName)})
  if (!root) return null
  const labels = []
  const rows = []
  const footers = []
  const buttons = []
  let card = null
  const walk = (n) => {
    // 被摘掉的那一层不算"屏上的字"：#268 那次没过滤 active，正向判据读的是隐藏节点，全绿而画面没有
    if (!n.active) return
    if (/Button$/.test(n.name)) {
      // 编队页该不该有养成按钮，读**按钮节点的激活态**而不是屏上的字 ——
      // 编队行自己就写着"技能强度 +3%"，按文字判会把一句正常文案当成残留按钮（本探针第一版栽在这儿）
      buttons.push(n.name)
    }
    const label = n.getComponent('cc.Label')
    if (label && label.string) {
      const labelBox = n.getComponent('cc.UITransform')
      labels.push({
        text: label.string, x: n.getPosition().x, y: n.getPosition().y,
        h: labelBox ? labelBox.contentSize.height : -1,
      })
    }
    const box = n.getComponent('cc.UITransform')
    if (/^compose-/.test(n.name)) {
      rows.push({ name: n.name, y: n.getPosition().y, h: box ? box.contentSize.height : -1 })
    }
    if (n.name === 'card') {
      card = { h: box ? box.contentSize.height : -1 }
    }
    if (n.name === 'confirm' || n.name === 'cancel') {
      footers.push({ name: n.name, x: n.getPosition().x, y: n.getPosition().y })
    }
    for (const child of n.children) walk(child)
  }
  walk(root)
  return { active: root.active, labels, rows, footers, buttons, card }
})()`

/** 在指定子树里按节点名找一个并按下它（与真人按下走同一个 touch-start 回调）。 */
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

const has = (snapshot, text) => snapshot !== null && snapshot.labels.some((l) => l.text.includes(text))

/**
 * 一行的两行字是否**整盒**落在自己那块底板里。
 *
 * <p>判据从"中心在板内"升成"盒子在板内"：截图抓到名字的上沿被底板顶边切掉一截，
 * 而旧写法量的是中心 —— 中心在板内、字却被切，于是 55 条读数全绿而画面是错的。
 */
const linesInsidePlate = (snapshot, rowName, texts) => {
  const row = snapshot.rows.find((r) => r.name === rowName)
  if (row === undefined) {
    return false
  }
  return texts.every((text) => {
    const label = snapshot.labels.find((l) => l.text.includes(text))
    return label !== undefined && label.h > 0
      && Math.abs(label.y - row.y) + label.h / 2 <= row.h / 2 + 0.5
  })
}

/** 只读某个底部按钮自己那一条字：全局匹配判不出**键上**写的是什么。 */
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
const deviceId = `compose-runtime-${Date.now()}`
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
  } else if (pathName === '/hero/compose') {
    const body = JSON.parse(request.postData() ?? '{}')
    fixture.composeCalls.push(body)
    // 桩自己推进状态：扣掉随行下发的门槛、把人进名册（服务端真做的事，夹具照着做）
    if (SR_POOL.some((h) => h.heroId === body.heroId)) {
      fixture.srHeld = Math.max(0, fixture.srHeld - SR_NEED)
    } else {
      fixture.ssrHeld = Math.max(0, fixture.ssrHeld - SSR_NEED)
    }
    if (!fixture.composed.includes(body.heroId)) {
      fixture.composed.push(body.heroId)
    }
  }
  await route.fulfill({
    status: 200,
    headers: { ...cors(request), 'content-type': 'application/json' },
    body: JSON.stringify({ code: 0, msg: '成功', data: make(), serverNow: Date.now() }),
  })
})
await stub('/hero/list', heroList)
await stub('/hero/compose', () => ({
  hero: composedView(fixture.composeCalls.at(-1)?.heroId ?? ''),
  consumed: [], serverNow: Date.now(),
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
await page.waitForTimeout(2000)

const hero = await page.evaluate(SNAPSHOT('hero'))
checkTrue('武将页挂上了', hero !== null)
checkTrue('夹具里那个已有武将会话进画面（名字来自服务端下发的 name）', has(hero, '关羽'))
checkTrue('页眉那颗「碎片合成」在画面里（不依附任何一行武将）', has(hero, '碎片合成'))
check('武将行上五颗养成按钮都亮着（下面那句判据的正向对照：读得到才说明 active 过滤没把话读死）',
  hero.buttons.length, 5)
checkTrue('其中就有「技能」那颗（原先 slice(4,8) 漏掉的正是它）',
  hero.buttons.includes('SkillButton'))
check('切到编队页', await page.evaluate(TAP('hero', 'Tab_lineups')), 'tapped')
await page.waitForTimeout(400)
const lineup = await page.evaluate(SNAPSHOT('hero'))
check('编队行上不残留任何养成按钮（原先 slice(4,8) 漏第五颗，那颗一直亮着）',
  lineup.buttons.length, 0)
check('切回武将页', await page.evaluate(TAP('hero', 'Tab_heroes')), 'tapped')
await page.waitForTimeout(400)
check('切回来五颗又在（页签切换没把状态留在上一条数据上）',
  (await page.evaluate(SNAPSHOT('hero'))).buttons.length, 5)

const closed = await page.evaluate(SNAPSHOT('composePick'))
checkTrue('合成弹层挂上了（composePick 节点在）', closed !== null)
check('没点入口时弹层不激活（不占首屏）', closed?.active, false)

check('点页眉「碎片合成」', await page.evaluate(TAP('hero', 'ComposeEntry')), 'tapped')
await page.waitForTimeout(500)
let opened = await page.evaluate(SNAPSHOT('composePick'))
check('弹层已激活', opened?.active, true)
checkTrue('抬头是「碎片合成武将」', has(opened, '碎片合成武将'))
check('三行候选都列出来（差 38 片那两名也在，不藏）', opened?.rows.length, 3)
check('行序：凑够那名浮到最前，灰行之间保持服务端顺序（截断切掉的是尾部，不能切掉能点的）',
  opened?.rows.map((r) => r.name.replace('compose-', '')).join(','),
  `${SSR_POOL[0].heroId},${SR_POOL[0].heroId},${SR_POOL[1].heroId}`)
checkTrue('候选行带的是服务端给的中文名', has(opened, '程远') && has(opened, '沈牧') && has(opened, '李劲'))
checkTrue('画面里没有 heroId / itemId（#255 一路的同族判据）',
  !opened.labels.some((l) => /hero_probe_|item_mat_/.test(l.text)))
check('总览把两个数分开报：几名可合成、几名已凑够',
  has(opened, '3 名武将可以合成 · 其中 1 名碎片已凑够'), true)
checkTrue('钱包那两行画在抬头（fragmentTexts 的消费点，#281 欠的）',
  has(opened, 'SR 武将碎片 ×12') && has(opened, 'SSR 武将碎片 ×80'))
const purseLabels = opened.labels.filter((l) => /×\d+/.test(l.text))
check('钱包画了几行（两档各一行）', purseLabels.length, 2)
checkTrue('钱包最后一行没被第一行底板切掉（截图抓到过一次，这条判据就是为了让它自己会红）',
  purseLabels.every((l) => Math.abs(l.y - opened.rows[0].y) >= opened.rows[0].h / 2 + l.h / 2))
checkTrue('差几片写在行上', has(opened, `SR 武将碎片 需 ${SR_NEED} 片 · 还差 ${SR_NEED - fixture.srHeld} 片`))
checkTrue('刚好凑够那一档写"碎片已够"', has(opened, `SSR 武将碎片 需 ${SSR_NEED} 片 · 碎片已够`))
check('没挑人时确认键是灰的', footerText(opened, 'confirm'), '先选一名武将')
checkTrue('两行字都落在自己那块底板里（不被下一行盖住）',
  linesInsidePlate(opened, `compose-${SR_POOL[0].heroId}`,
    [SR_POOL[0].name, `还差 ${SR_NEED - fixture.srHeld} 片`])
  && linesInsidePlate(opened, `compose-${SSR_POOL[0].heroId}`, [SSR_POOL[0].name, '碎片已够']))
if (process.env.COMPOSE_DEBUG) {
  // 判据红了要能自己量出为什么红：把那一行的板与字的落点/盒高原样打出来
  for (const name of [`compose-${SR_POOL[0].heroId}`, `compose-${SSR_POOL[0].heroId}`]) {
    const row = opened.rows.find((r) => r.name === name)
    const near = opened.labels
      .filter((l) => row && Math.abs(l.y - row.y) <= 40)
      .map((l) => `${l.text.slice(0, 8)}@y${l.y.toFixed(1)}/h${l.h.toFixed(1)}`).join(' | ')
    console.log(`  GEOM  ${name} y=${row?.y} h=${row?.h} ⇒ ${near}`)
  }
}

check('点灰的那一行（差 38 片）', await page.evaluate(TAP('composePick', `compose-${SR_POOL[0].heroId}`)), 'tapped')
await page.waitForTimeout(300)
opened = await page.evaluate(SNAPSHOT('composePick'))
check('灰行点了不算选中（确认键仍灰）', footerText(opened, 'confirm'), '先选一名武将')
check('页面上没有「已选」', has(opened, '已选'), false)

check('点够的那一行', await page.evaluate(TAP('composePick', `compose-${SSR_POOL[0].heroId}`)), 'tapped')
await page.waitForTimeout(300)
opened = await page.evaluate(SNAPSHOT('composePick'))
check('选中后出现「已选」', has(opened, '已选'), true)
check('确认键转成可发态', footerText(opened, 'confirm'), '确认合成')
const ys = opened.rows.map((r) => r.y)
check('三行落点互不重叠', new Set(ys).size, 3)
check('读到了那两个底部按钮（读不到下面的几何判据就是失效）', opened.footers.length, 2)
const footerY = Math.max(...opened.footers.map((f) => f.y))
checkTrue('每一行都在底部按钮之上（没有哪一行被压在按钮后面）', ys.every((y) => y > footerY))
const visible = await page.evaluate('window.cc.view.getVisibleSize().height')
checkTrue('卡片没超出可视高度（写死行数的那一版会越界）', opened.card.h <= visible)
await page.screenshot({ path: path.join(OUT, 'compose-picked.png') })

const callsBefore = fixture.composeCalls.length
const heroReadsBefore = fixture.heroReads
check('点确认', await page.evaluate(TAP('composePick', 'confirm')), 'tapped')
await page.waitForTimeout(1500)
check('真的发出了一次 /hero/compose', fixture.composeCalls.length, callsBefore + 1)
const sent = fixture.composeCalls.at(-1)
check('带的是选中的那一名', sent?.heroId, SSR_POOL[0].heroId)
checkTrue('requestId 合法（长度 [8,64]，短了会被端点回 1003 看着像端点坏了）',
  typeof sent?.requestId === 'string' && sent.requestId.length >= 8 && sent.requestId.length <= 64)
check('合成完重读武将（新武将进名册）', fixture.heroReads > heroReadsBefore, true)
check('确认之后弹层收起', (await page.evaluate(SNAPSHOT('composePick')))?.active, false)
checkTrue('武将页多了一行（回读的是真名册，不是本地猜的）',
  has(await page.evaluate(SNAPSHOT('hero')), '李劲'))

check('合成完再开弹层', await page.evaluate(TAP('hero', 'ComposeEntry')), 'tapped')
await page.waitForTimeout(600)
opened = await page.evaluate(SNAPSHOT('composePick'))
check('刚合成那名从候选里消失（服务端把它当已拥有排掉了）', opened?.rows.length, 2)
checkTrue('SSR 那一档的余额被门槛扣光（钱包跟着变）', has(opened, 'SSR 武将碎片 ×0'))
checkTrue('剩下的两行都是灰的（SR 那两名还差 38 片）',
  has(opened, `还差 ${SR_NEED - fixture.srHeld} 片`))
check('一屏里没凑够时确认键仍灰', footerText(opened, 'confirm'), '先选一名武将')
await page.screenshot({ path: path.join(OUT, 'compose-after.png') })

const cancelCalls = fixture.composeCalls.length
check('点取消', await page.evaluate(TAP('composePick', 'cancel')), 'tapped')
await page.waitForTimeout(400)
check('取消不发任何请求', fixture.composeCalls.length, cancelCalls)
check('取消后弹层收起', (await page.evaluate(SNAPSHOT('composePick')))?.active, false)

// ---------- 第二幕：候选多到画不下，必须说实话而不是静默截断 ----------
fixture.wide = true
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForTimeout(2000)
check('宽列表这一幕点入口', await page.evaluate(TAP('hero', 'ComposeEntry')), 'tapped')
await page.waitForTimeout(600)
const wide = await page.evaluate(SNAPSHOT('composePick'))
const totalCandidates = SR_POOL.length + WIDE_POOL.length
checkTrue('候选总数大于画得下的行数（这一幕的前置）', wide.rows.length < totalCandidates)
checkTrue('少画的那些有一句"另有 N 名未列出"',
  wide.labels.some((l) => /另有 \d+ 名未列出/.test(l.text)))
checkTrue('卡片仍在可视高度内（截断是有效的而不是把行推到屏外）', wide.card.h <= visible)
const wideYs = wide.rows.map((r) => r.y)
check('画出来的行彼此不重叠', new Set(wideYs).size, wideYs.length)
await page.screenshot({ path: path.join(OUT, 'compose-wide.png') })

check('零页面错误', errors.length, 0)
if (errors.length > 0) {
  console.log(errors.slice(0, 6).join('\n'))
}

await browser.close()
console.log(`\n=== ${pass} PASS / ${fail} FAIL，截图落在 ${OUT} ===`)
process.exit(fail === 0 ? 0 : 1)
