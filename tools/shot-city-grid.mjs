/**
 * 内城格子的**干净视图 + 显示名读数**：把引导层关掉再拍 6×6 地皮，并核对 36 格上印的
 * 是建筑真名而不是配置 id。
 *
 * <p>为什么单独一个工具，而不是往 `verify-art-runtime.mjs` 里塞：那条量具每次都
 * `page.goto` 重载并只留最后一张截图，而这里要的是"遮罩已经消失的那一帧"。
 * 引导层（`Canvas/Game/Guide`）压在内城卡片上，正好遮住第 6 行 —— 那一行最能看出
 * 地皮是"按区分成四块"还是"还在按奇偶交替"。
 *
 * <p>颜色对不对的**机器判定不在这里**，在 `tests/CityGroundTint.test.ts`：
 * "同区同色、不成棋盘"是数据属性，不是渲染属性，用单测判它才会真的失败。
 * 本工具只负责"玩家真的看得见"这一半。
 *
 * 退出码：0 成功；2 前置不满足（产物 / 遮罩节点 / 场景结构没找到）。
 */
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
/**
 * 期望出现在屏幕上的主城名字 —— 从配置表现读，不抄第二份。
 * 抄一个字符串在这里，就等于"界面印什么由本工具说了算"，而真源是 building.json。
 */
const BUILDING_ROWS = (() => {
  const doc = JSON.parse(readFileSync('contract/config/building.json', 'utf8'))
  return Array.isArray(doc) ? doc : doc.rows
})()
const MAIN_CITY_NAME = BUILDING_ROWS.find((row) => row.id === 'main_city').name
if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[city-grid][前置] 产物不存在：${ROOT}（先跑 Cocos 构建）`)
  process.exit(2)
}

const OUT = process.env.CITY_SHOT_OUT ?? null
const PORT = Number(process.env.CITY_SHOT_PORT ?? 8192)
/**
 * 后端默认打本机的 8080（产物里写死的那台）。要换端口时用 `BACKEND_ORIGIN` 指到**本轮自己起的**
 * 那一台：契约刚加了 `BuildingView.name`，老后端不会下发这个字段，屏幕上会印出 `undefined Lv1` ——
 * 拿老后端跑这一格只会得到一个看不出名堂的读数。
 */
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
/**
 * 截图默认带上后端端口：跑对照组（老后端）时不会把主证据那张图盖掉。
 * 本轮真的踩过 —— 控制组后跑，把"已修复"那张图换成了 `undefined Lv1` 那张。
 */
const SHOT = OUT ?? path.resolve(process.cwd(),
  `client/build/art-verify/art-city-grid-${BACKEND.replace(/[^0-9]/g, '_')}.png`)
const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `city-grid-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))

const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'city')
await page.goto(url.toString(), { waitUntil: 'networkidle' })
await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
await page.waitForTimeout(2000)

/**
 * 点一格"上面有建筑的地皮"。名字与状态已从格子里收进下面的选择栏（脚印投影后只有
 * 53~89 × 24~45，塞三行字必糊），所以显示名的正向断言**必须先选中一格**才成立。
 *
 * <p>为什么走 `node.emit('touch-start')` 而不是 `page.mouse.click`：后者要把 UI 世界坐标
 * 换算成 canvas 像素，遇到 letterbox 就点偏 —— 而"点偏"的读数与"没接线"长得一模一样。
 * emit 直接打视图自己注册的那个监听器，测的正是"格子被点到 → 选择栏写出名字"这条链路。
 */
const tapped = await page.evaluate((mainIndex) => {
  const scene = window.cc.director.getScene()
  let hit = null
  const visit = (n) => {
    if (hit !== null) return
    if (n.name === `Grid-${mainIndex}`) { hit = n; return }
    for (const c of n.children) visit(c)
  }
  visit(scene)
  if (hit === null) return false
  hit.emit('touch-start', null)
  return true
}, 3 * 6 + 3)
if (!tapped) {
  console.error('[city-grid][前置] 找不到中心格 Grid-21 —— 主城不在 (3,3) 了，本判据的落点假设失效')
  await browser.close()
  await preview.close()
  process.exit(2)
}
await page.waitForTimeout(300)

const probe = await page.evaluate(() => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game') ?? null
  if (game === null) return { ok: false, why: 'Canvas/Game 不在场景里' }
  const guide = game.getChildByName('Guide')
  if (guide === null) return { ok: false, why: 'Canvas/Game/Guide 不在场景里 —— 遮罩换了位置，截图仍会被挡住' }
  // 只置 active=false 会被它自己画回来：GuideView 在数据到达后会重排那一层。
  // 组件一起禁掉，才是"这一帧没有引导层"。
  const guideView = guide.getComponent('GuideView')
  if (guideView !== null) guideView.enabled = false
  guide.active = false
  let ground = null
  let tiles = 0
  const positions = []
  const names = []
  const visit = (n) => {
    if (n.name === 'Ground') ground = n
    if (/^Grid-\d+$/.test(n.name)) {
      tiles += 1
      positions.push([n.position.x, n.position.y])
    }
    const label = n.getComponent('cc.Label')
    if (label !== null && label.string !== '') names.push(label.string)
    for (const c of n.children) visit(c)
  }
  visit(scene)
  return { ok: true, groundFound: ground !== null, tiles, positions, labels: names }
})
if (!probe.ok) {
  console.error(`[city-grid][前置] ${probe.why}`)
  await browser.close()
  await preview.close()
  process.exit(2)
}
await page.waitForTimeout(400)
await page.screenshot({ path: SHOT })
await browser.close()
await preview.close()

if (errors.length > 0) {
  console.error(`[city-grid][前置] 页面报错 ${errors.length} 条：${errors[0]}`)
  process.exit(2)
}
if (!probe.groundFound) {
  console.error('[city-grid][前置] 场景里没有 Ground 容器 —— 地皮那一层没画出来')
  process.exit(2)
}
console.log(`[city-grid] 截图：${SHOT}`)
console.log(`[city-grid] 格子节点 ${probe.tiles} 个，引导层已摘除`)

/**
 * 显示名读数 —— 扫**整屏所有 Label 文本**，不盯某一个节点名。
 *
 * <p>第一版按 `Grid-N/Name` 取标签，跑出来"一格名字都没读到"却看不出是没数据还是找错了节点，
 * 那种判据只会把工具自己的毛病报成产品的毛病。整屏文本判的是同一件事，但不依赖节点命名。
 *
 * 判据都能失败（三条，缺一条就是假绿）：
 * ① 屏幕上必须出现 `主城` —— 新号唯一的那栋楼，它的名字**只能**来自服务端下发的 `name`。
 *    没有这条正向断言，第 ② ③ 条会把"什么都没渲染出来"也判成绿：
 *    第一版就栽在这里 —— 它只查"有没有下划线串"，于是后端不下发 name、界面印 `undefined`
 *    时它照样全绿（对照跑了一次老后端，果然假绿）。
 * ② 出现 `undefined` / `null` ⇒ 字段没下发，模板串把它原样印了出来；
 * ③ 出现 `main_city` / `lumber_camp` 这类下划线 ASCII 串 ⇒ 配置 id 漏到了玩家眼前（#255 的正面）。
 * ④ 一条非 ASCII 文本都没有 ⇒ 面板没渲染，判据走不到，退 2 不算绿。
 */
const labels = probe.labels
const undefinedTokens = labels.filter((s) => s.includes('undefined') || s.includes('null'))
const asciiTokens = labels.filter((s) => /^[A-Za-z0-9_ ]+$/.test(s) && s.includes('_'))
// 非 ASCII 即"有中文"—— 用范围而不是码点区间，免得脚本编码一换就静默失配
const hanTokens = labels.filter((s) => /[^\x00-\x7F]/.test(s))
console.log(`[city-grid] 屏幕文本 ${labels.length} 条，含汉字 ${hanTokens.length} 条`)
if (hanTokens.length === 0) {
  console.error('[city-grid][前置] 屏幕上没有一条汉字文本 —— 面板没渲染或数据没到，本判据走不到')
  process.exit(2)
}
const failures = []
// 子串匹配，不是全等：选择栏的标题是「主城 Lv1」这种"名字 + 等级"的形状
if (!labels.some((s) => s.includes(MAIN_CITY_NAME))) {
  failures.push(`屏幕上找不到含「${MAIN_CITY_NAME}」的文本 —— name 没下发时这里就该红，`
    + `而不是等下划线判据去猜`)
}
if (undefinedTokens.length > 0) {
  failures.push(`${undefinedTokens.length} 条文本是 undefined/null —— 服务端没下发该字段`)
}
if (asciiTokens.length > 0) {
  failures.push(`${asciiTokens.length} 条文本是配置 id 形态：${asciiTokens.join('、')}`)
}
if (failures.length > 0) {
  console.error(`[city-grid] 判据失败：${failures.join('；')}`)
  process.exit(1)
}
console.log(`[city-grid] 全绿：「${MAIN_CITY_NAME}」由服务端下发的 name 渲染出来了，`
  + '屏上没有 undefined、没有配置 id')

/**
 * 落点读数：单测判的是"投影函数对不对"，判不到"视图有没有照它摆"。
 * 所以这里从**运行期场景**读 36 个格子的位置，要求至少有一行的水平间距不全相等 ——
 * 全相等就是均匀棋盘（规格 §3.3 禁止的形态），也是"改了投影但 buildGrid 没接线"的症状。
 */
const rows = new Map()
for (const [x, y] of probe.positions) {
  const key = Math.round(y / 4)
  if (!rows.has(key)) rows.set(key, [])
  rows.get(key).push(x)
}
const uniformRows = [...rows.values()].filter((xs) => {
  if (xs.length < 3) return false
  const sorted = [...xs].sort((a, b) => a - b)
  const gaps = sorted.slice(1).map((v, i) => Math.round((v - sorted[i]) * 10) / 10)
  return new Set(gaps).size === 1
})
const distinctX = new Set(probe.positions.map((p) => Math.round(p[0]))).size
console.log(`[city-grid] 格子 ${probe.positions.length} 个，横向落点 ${distinctX} 种，`
  + `纵向 ${rows.size} 档，其中间距全等的行 ${uniformRows.length} 个`)
if (probe.positions.length !== 36) {
  console.error(`[city-grid] 判据失败：场景里 ${probe.positions.length} 个格子，应为 36`)
  process.exit(1)
}
if (distinctX < 12) {
  console.error(`[city-grid] 判据失败：36 格只有 ${distinctX} 种横向落点 —— 视图没照投影摆，仍是均匀棋盘`)
  process.exit(1)
}
console.log('[city-grid] 落点全绿：视图按锚点投影摆放，不是均匀棋盘')
