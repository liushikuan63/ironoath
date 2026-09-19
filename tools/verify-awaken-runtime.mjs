/**
 * 职责：武将觉醒弹层（V03-d 第二条养成线）的**运行时**验收 —— 真构建产物 + 真引擎里，
 *       从武将页的「觉醒」按钮点进去，走完"选石 → 确认 → 回读"一整圈。
 * 依赖：node、playwright、一台能登录的后端（默认 8080）、已构建的 web-mobile 产物。
 *
 * 用法：AWAKEN_ARTIFACT_ROOT=/d/tmp/tech-wt/client/build/web-mobile node tools/verify-awaken-runtime.mjs
 *
 * <p><b>为什么 `/hero/list` 与 `/bag/list` 要经本探针替换</b>：dev 上的新号 `heroes=0`，
 * 而觉醒石是赛季通行证 / 限定活动的投放物 —— 一个刚建档的号既没有武将、也没有石头，
 * 弹层这一屏在真数据下**根本够不到**（装备页与升级弹层为同一道门，见台账 #267 与队列）。
 * 替换只发生在探针这一侧：夹具按契约的必填字段构造，经客户端**自己的读路径**进去，
 * 于是被验的是"入口 → 编排 → 渲染 → 写请求 → 回读"这条真链路，而不是某个手搓的视图对象。
 * 写请求 `/hero/awaken` 打到桩上（桩自己推进夹具状态），**不碰服务端任何存档**。
 * 期望值全部由夹具常量 + B06 §2.3 那条"最后一阶只认高阶石"独立算出，不从页面抄。
 *
 * <p>判据（10 组）：① 弹层挂上且不占首屏；② 点「觉醒」真能弹出；③ 两块石都列出来；
 * ④ 只有这一阶认的那块点亮，另一块的原因写在行上；⑤ 灰行点了不算选中；
 * ⑥ 可用行点了出「已选」、确认键转绿；⑦ 确认发出的是那一条请求（含 skillSlot 为 null）；
 * ⑧ 回读后武将行上的觉醒进度真的变了；⑨ 满阶再进弹层说实话；⑩ 行不越界、零页面错误。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const OUT = process.env.AWAKEN_VERIFY_OUT ?? path.resolve(process.cwd(), 'client/build/awaken-verify')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.AWAKEN_PROBE_PORT ?? 8187)
const BACKEND = process.env.AWAKEN_BACKEND ?? 'http://localhost:8080'
const ARTIFACT = process.env.AWAKEN_ARTIFACT_ROOT ?? 'client/build/web-mobile'

const HERO_ID = 'hero_probe_guanyu'
const LOW_STONE = 'item_hero_awaken_1'
const HIGH_STONE = 'item_hero_awaken_2'

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

/**
 * 夹具状态：**故意从第 2 阶起步**（maxAwaken 3），这样第一屏要验的就是
 * "最后一阶只认高阶石"那一条分支 —— 反过来的分支由同一份夹具在第 ⑨ 组判据里补上。
 */
const fixture = {
  awaken: 2,
  maxAwaken: 3,
  lowStoneHeld: 4,
  /** 留 2 块：确认要吃掉一块，而服务端（这里照做）不把余数为 0 的行留在背包里 */
  highStoneHeld: 2,
  awakenCalls: [],
  heroReads: 0,
  bagReads: 0,
}

const heroView = () => ({
  heroId: HERO_ID, name: '关羽', rarity: 'SSR', level: 40, exp: 1200, expToNext: 800,
  maxLevel: 60, star: 3, maxStar: 5, awaken: fixture.awaken, maxAwaken: fixture.maxAwaken,
  mainSkillId: 'skill_guanyu_main', mainSkillLevel: 3, subSkillId: 'skill_guanyu_sub',
  subSkillLevel: 1, maxSkillLevel: 10, equips: [null, null, null, null],
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

const bagList = () => ({
  items: [
    { itemId: LOW_STONE, name: '觉醒石·初阶', type: 'MATERIAL', rarity: 'SR',
      obtainFrom: '赛季通行证', count: fixture.lowStoneHeld, stackMax: 999, sortKey: 10,
      effectKind: 'AWAKEN_HERO' },
    { itemId: HIGH_STONE, name: '觉醒石·高阶', type: 'MATERIAL', rarity: 'SSR',
      obtainFrom: '限定活动', count: fixture.highStoneHeld, stackMax: 999, sortKey: 11,
      effectKind: 'AWAKEN_HERO' },
    { itemId: 'item_hero_exp_s', name: '小经验书', type: 'MATERIAL', rarity: 'R',
      obtainFrom: '主线任务', count: 5, stackMax: 999, sortKey: 12, effectKind: 'GRANT_HERO_EXP' },
    // 服务端不把余数为 0 的行留在背包里，夹具照做：不然这一屏会测到一个生产不再产生的形状
  ].filter((item) => item.count > 0),
  capacityUsed: 3, capacityMax: 200,
})

/** 夹具自己按 B06 §2.3 那条规则算一遍期望，不读页面。 */
const nextTier = () => fixture.awaken + 1
const isFinalTier = () => fixture.awaken + 1 === fixture.maxAwaken
const usableStone = () => (isFinalTier() ? HIGH_STONE : LOW_STONE)

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
try {
  // 端点要身份：裸调只要不是连接失败就说明服务在（对照：产物里写死的地址就是这个）
  await fetch(`${BACKEND}/time/sync`, { method: 'POST' })
} catch (error) {
  console.error(`起跑前直连后端失败：${error.message}\n后端 ${BACKEND} 起来了吗？`)
  process.exit(2)
}
console.log(`=== 觉醒弹层运行时验收：产物经 ${preview.origin}，后端 ${BACKEND}（三个路径经夹具替换）===`)

const NODE_PATH = 'window.cc.director.getScene().getChildByName("Canvas").getChildByName("Game")'

/**
 * 把某个二级节点里的 Label 文本与逐行落点读回来（行名一起带回，供"哪一行是什么"对账）。
 *
 * <p><b>组件识别走 `getComponent('cc.Label')` 这种注册名形式</b>：`debug=false` 的构建会把类名压缩掉，
 * 按 `constructor.name` 匹配的写法读回来是空的 —— 症状不是报错而是**所有文本判据一起变成 false**，
 * 看着像"面板没画字"；而 `instanceof window.cc.UITransform` 更直接：那个键在 release 包里就不存在。
 * 仓库里另几个量具按名字匹配，它们只在 debug 构建上验过（记进台账，别再抄那一版）。
 */
const SNAPSHOT = (rootName) => `(() => {
  const game = ${NODE_PATH}
  const root = game.children.find((c) => c.name === ${JSON.stringify(rootName)})
  if (!root) return null
  const labels = []
  const rows = []
  const footers = []
  const walk = (n) => {
    // 按注册名取组件：release 构建里 window.cc.UITransform 这个键根本不存在
    // （报 "Right-hand side of 'instanceof' is not an object"），而 constructor.name 会被压缩掉。
    // 字符串这条边走的是引擎内部的类名表，两种构建都稳。
    const label = n.getComponent('cc.Label')
    if (label && label.string) {
      labels.push({ text: label.string, x: n.getPosition().x, y: n.getPosition().y })
    }
    const box = n.getComponent('cc.UITransform')
    if (/^awaken-/.test(n.name)) {
      rows.push({ name: n.name, y: n.getPosition().y, h: box ? box.contentSize.height : -1 })
    }
    if (n.name === 'confirm' || n.name === 'cancel') {
      footers.push({ name: n.name, x: n.getPosition().x, y: n.getPosition().y })
    }
    for (const child of n.children) walk(child)
  }
  walk(root)
  return { active: root.active, labels, rows, footers }
})()`

/**
 * 在指定子树里按节点名找一个，然后按下它（与真人按下走的是同一个 touch-start 回调）。
 * **必须限定子树**：升级弹层里也叫 `confirm` / `cancel`，而它在 `game` 的兄弟里排得更靠前 ——
 * 从整棵树找会按到那个没激活的弹层上，症状是"探针点了没反应"。
 */
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
 * 一行的两行字是否都落在**自己那块底板**里。
 * 判据来自画出来的几何（行的 UITransform 高度与行的落点），不是探针侧另抄的一份常量 ——
 * 第一版就是栽在这儿：第二行字写在行中心下方 32px，而底板只有 ±23，
 * 于是被下一行的底板盖掉半截，而 38 条读数全绿。
 */
const linesInsidePlate = (snapshot, itemId, texts) => {
  const row = snapshot.rows.find((r) => r.name === `awaken-${itemId}`)
  if (row === undefined) {
    return false
  }
  return texts.every((text) => {
    const label = snapshot.labels.find((l) => l.text.includes(text))
    return label !== undefined && Math.abs(label.y - row.y) <= row.h / 2
  })
}

/**
 * 只读某个底部按钮自己那一条字。整屏文本里"已达觉醒上限"也会出现在进度那一行，
 * 全局匹配判不出**键上**写的是什么 —— 那正是满阶那一版要改的地方。
 */
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
const deviceId = `awaken-runtime-${Date.now()}`
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, deviceId)

// 三个路径经夹具替换（其余照常打到后端：登录、时钟、其它面板都在真跑）
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
  } else if (pathName === '/bag/list') {
    fixture.bagReads += 1
  } else if (pathName === '/hero/awaken') {
    fixture.awakenCalls.push(JSON.parse(request.postData() ?? '{}'))
    // 桩自己推进状态：一块石换一阶，余数跟着减（服务端真做的事，夹具照着做）
    fixture.awaken += 1
    if (request.postData()?.includes(HIGH_STONE)) {
      fixture.highStoneHeld -= 1
    } else {
      fixture.lowStoneHeld -= 1
    }
  }
  await route.fulfill({
    status: 200,
    headers: { ...cors(request), 'content-type': 'application/json' },
    body: JSON.stringify({ code: 0, msg: '成功', data: make(), serverNow: Date.now() }),
  })
})
await stub('/hero/list', heroList)
await stub('/bag/list', bagList)
await stub('/hero/awaken', () => ({
  hero: heroView(), consumed: [{ itemId: usableStone(), count: 1 }], serverNow: Date.now(),
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
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await page.waitForTimeout(2000)

const hero = await page.evaluate(SNAPSHOT('hero'))
checkTrue('武将页挂上了', hero !== null)
checkTrue('夹具里那个武将会话进画面（名字来自服务端下发的 name）', has(hero, '关羽'))
check('觉醒进度出现在行上', has(hero, `觉醒 ${fixture.awaken}/${fixture.maxAwaken}`), true)

const before = await page.evaluate(SNAPSHOT('awakenPick'))
checkTrue('觉醒弹层挂上了（awakenPick 节点在）', before !== null)
check('没点「觉醒」时弹层不激活（不占首屏）', before?.active, false)

check('点武将页的「觉醒」按钮', await page.evaluate(TAP('hero', 'AwakenButton')), 'tapped')
await page.waitForTimeout(500)
let opened = await page.evaluate(SNAPSHOT('awakenPick'))
check('弹层已激活', opened?.active, true)
check('两块石都列出来（不藏不可用的那块）', opened?.rows.length, 2)
check('两行就是那两块石（按背包顺序）',
  opened?.rows.map((r) => r.name).join(','), `awaken-${LOW_STONE},awaken-${HIGH_STONE}`)
checkTrue('标题带武将名字', has(opened, '给 关羽 觉醒'))
checkTrue('进度行说实话', has(opened,
  `第 ${fixture.awaken} 阶 → 第 ${nextTier()} 阶（共 ${fixture.maxAwaken} 阶）`))

const expectedUsable = usableStone()
checkTrue('可用那块石显示自己的持有数', has(opened,
  `持有 ${expectedUsable === HIGH_STONE ? fixture.highStoneHeld : fixture.lowStoneHeld}`))
checkTrue('不可用那块石把原因写在行上，而不是藏掉', has(opened,
  isFinalTier() ? '最后一阶要用高阶觉醒石' : '这一阶用初阶觉醒石'))
check('灰掉的那行不显示持有数（显示的是原因）', has(opened,
  `持有 ${expectedUsable === HIGH_STONE ? fixture.lowStoneHeld : fixture.highStoneHeld}`), false)
checkTrue('经验书没混进觉醒候选（同 type，靠 effectKind 筛）', !has(opened, '小经验书'))
checkTrue('两块石的行名与第二行都落在自己那块底板里（不被下一行盖住）',
  linesInsidePlate(opened, LOW_STONE, ['觉醒石·初阶', '最后一阶要用高阶觉醒石'])
  && linesInsidePlate(opened, HIGH_STONE, ['觉醒石·高阶', `持有 ${fixture.highStoneHeld}`]))
check('两块底板不互相重叠',
  Math.abs(opened.rows[0].y - opened.rows[1].y) >= (opened.rows[0].h + opened.rows[1].h) / 2, true)
check('没选石之前确认键是灰的', footerText(opened, 'confirm'), '先选觉醒石')

const wrong = expectedUsable === HIGH_STONE ? LOW_STONE : HIGH_STONE
check('点不可用的那行', await page.evaluate(TAP('awakenPick', `awaken-${wrong}`)), 'tapped')
await page.waitForTimeout(300)
opened = await page.evaluate(SNAPSHOT('awakenPick'))
check('灰行点了不算选中（确认键仍灰）', footerText(opened, 'confirm'), '先选觉醒石')
check('页面上没有「已选」', has(opened, '已选'), false)

check('点可用的那行', await page.evaluate(TAP('awakenPick', `awaken-${expectedUsable}`)), 'tapped')
await page.waitForTimeout(300)
opened = await page.evaluate(SNAPSHOT('awakenPick'))
check('选中后出现「已选」', has(opened, '已选'), true)
check('确认键转成可发态', footerText(opened, 'confirm'), '确认觉醒')
const ys = opened.rows.map((r) => r.y)
check('两行落点互不重叠', new Set(ys).size, 2)
check('读到了那两个底部按钮（读不到下面的几何判据就是失效）', opened.footers.length, 2)
const footerY = Math.max(...opened.footers.map((f) => f.y))
checkTrue('每一行都在底部按钮之上（没有哪一行被压在按钮后面）', ys.every((y) => y > footerY))
await page.screenshot({ path: path.join(OUT, 'awaken-picked.png') })

const callsBefore = fixture.awakenCalls.length
const heroReadsBefore = fixture.heroReads
check('点确认', await page.evaluate(TAP('awakenPick', 'confirm')), 'tapped')
await page.waitForTimeout(1500)
check('真的发出了一次 /hero/awaken', fixture.awakenCalls.length, callsBefore + 1)
const sent = fixture.awakenCalls.at(-1)
check('带上选中的那一块', sent?.itemId, expectedUsable)
check('heroId 是夹具里那一个（画面不印 id，但请求要带）', sent?.heroId, HERO_ID)
check('skillSlot 显式为 null（那是技能线用的字段）', sent?.skillSlot, null)
check('觉醒完重读武将', fixture.heroReads > heroReadsBefore, true)
check('确认之后弹层收起', (await page.evaluate(SNAPSHOT('awakenPick')))?.active, false)
checkTrue('回读后武将行上的觉醒进度跟着变', has(await page.evaluate(SNAPSHOT('hero')),
  `觉醒 ${fixture.awaken}/${fixture.maxAwaken}`))

// 满阶再进一次：这一次两块都该灰、确认键该灰
check('满阶后再点「觉醒」', await page.evaluate(TAP('hero', 'AwakenButton')), 'tapped')
await page.waitForTimeout(400)
const maxed = await page.evaluate(SNAPSHOT('awakenPick'))
checkTrue('进度行改成"已达觉醒上限 N 阶"', has(maxed, `已达觉醒上限 ${fixture.maxAwaken} 阶`))
check('确认键说实话（不再写着"先选觉醒石"引玩家去点两块灰石）',
  footerText(maxed, 'confirm'), '已达觉醒上限')
checkTrue('满阶的两块石照常报余数，不把那句上限重复两遍',
  has(maxed, `持有 ${fixture.lowStoneHeld}`) && has(maxed, `持有 ${fixture.highStoneHeld}`))
check('满阶时页面上没有"已选"', has(maxed, '已选'), false)
await page.screenshot({ path: path.join(OUT, 'awaken-maxed.png') })

check('零页面错误', errors.length, 0)
if (errors.length > 0) {
  console.log(errors.slice(0, 6).join('\n'))
}

await browser.close()
console.log(`\n=== ${pass} PASS / ${fail} FAIL，截图落在 ${OUT} ===`)
process.exit(fail === 0 ? 0 : 1)
