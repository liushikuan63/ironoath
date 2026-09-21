#!/usr/bin/env node
/**
 * 职责：把「哪一行字正被 SHRINK 的盒子按比例压小」变成一次跑完的清单，而不是逐屏肉眼读码。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：LABELFIT_BACKEND=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 LABELFIT_PORT（默认 8191，同机并发时换一个）
 *   LABELFIT_BACKEND=http://localhost:8199 LABELFIT_PORT=8191 node tools/verify-label-fit-runtime.mjs
 *   修完一批后用 `--print-baseline` 重生成 BASELINE，别手抄。
 *
 * <p><b>为什么要这么一份量具</b>：台账 #366 页内量出 `overflow = SHRINK` 的**盒高就是字形的缩放
 * 系数**——盒高低于"装得下一行"就把整行字按比例缩小（战令表头五行落地 17/13/10/10/9 对设定
 * 20/17/15/15/14，最小的只剩 64%），给高了还会反向放大。而 `grep Overflow.SHRINK` 在客户端里
 * 有二十多处，逐屏判断"这屏的文案会不会长到顶出面板"要人看，但**哪些行正在被压小**是可以机器量的。
 * 这份量具就做这一件：把全客户端每格里被压小的行点名点齐。
 *
 * <p><b>下限为什么是 max(字号+14, 30)</b>：迁移曲线实测 14~20 号字都要盒高 **30** 才等于设定字号，
 * 且这个 30 不随字号走（`lineHeight` 没设时引擎拿默认 40 参与）；更大字号按比例走。取保守界。
 *
 * <p><b>棘轮基线</b>：判据是"实测集合 == 基线集合"，两个方向都会红 —— 新出现一处压字立刻红；
 * 修好一处却忘了删基线行也红（逼着每格把成果落进这张表）。清零之后这份量具就恒绿，
 * 并继续挡住"再拿 SHRINK 猜盒高"这条回头路。
 *
 * <p><b>覆盖边界</b>：只量"新号一进这一格就画出来"的行。背包/邮件等格在空态下只有几颗 Label，
 * 有数据之后的行不在这份清单里 —— 那些要等带数据的宿主探针，别把这里的"没点到"读成"没问题"。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

// 必须显式给后端：静默回落到 8080 等于"打到另一台机器上读数"（同一条教训见 march 探针第 25 行）
const BACKEND = process.env.LABELFIT_BACKEND ?? (() => {
  console.error('[label-fit] 缺 LABELFIT_BACKEND：不给就退回 http://localhost:8080，'
    + '那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.LABELFIT_PORT ?? 8191)
const OUT = path.resolve(process.cwd(), 'client/build/label-fit-verify')
mkdirSync(OUT, { recursive: true })
const KEYS = ['city', 'army', 'hero', 'gacha', 'bag', 'stage', 'reports', 'quest', 'battlePass',
  'mail', 'social', 'power', 'shop', 'avatarFrames', 'targets', 'world', 'settings']

/**
 * 已知仍在被压小的行（`面板/文本前缀(盒高<下限,字号)`）。
 *
 * <p>**现在是空的**：#367 建表时 32 条，#368 还掉内城 2 + 设置 10，#369 还掉关卡 14，
 * #370 还掉战令档位行 4 处与外观页 3 处 ⇒ 全客户端清零。这张表从此是**只增不许有**的闸门：
 * 谁再拿 SHRINK + 猜的盒高压一行字，这里就会多出一条，量具当场红。
 */
const BASELINE = new Set([])

let pass = 0
let fail = 0
const check = (msg, actual, expected) => {
  if (actual === expected) {
    pass += 1
    console.log(`  PASS  ${msg}（${String(actual)}）`)
  } else {
    fail += 1
    console.log(`  FAIL  ${msg}：期望 ${String(expected)}，实际 ${JSON.stringify(actual)}`)
  }
}
const checkTrue = (msg, actual) => check(msg, actual, true)

const WALK = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.children.find((c) => c.name === KEY_PLACEHOLDER)
  if (panel === undefined || !panel.activeInHierarchy) return null
  const out = []
  let shrink = 0
  let seen = 0
  const walk = (n) => {
    if (n.activeInHierarchy) {
      const lb = n.getComponent('cc.Label')
      const str = lb ? (lb.string ?? '') : ''
      if (str.length > 0) {
        seen += 1
        // 2 = Label.Overflow.SHRINK；盒高用本地值（字号也是本地单位）
        if (lb.overflow === 2) {
          shrink += 1
          const h = Math.round(n.getComponent('cc.UITransform').height)
          const floor = Math.max(lb.fontSize + 14, 30)
          if (h < floor) {
            out.push({ text: str.slice(0, 10), h, want: lb.fontSize, floor,
              wrap: lb.enableWrapText !== false })
          }
        }
      }
    }
    for (const c of n.children) walk(c)
  }
  walk(panel)
  return { seen, shrink, out }
})()`

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 全客户端"字被盒子压小"清单：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `labelfit-${Date.now()}`)

const offenders = []
const reached = []
let totalLabels = 0
let totalShrink = 0

for (const key of KEYS) {
  const page = await context.newPage()
  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', key)
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  preview.assertRewritten()
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  let read = null
  for (let i = 0; i < 24; i += 1) {
    await page.waitForTimeout(250)
    read = await page.evaluate(WALK.replace('KEY_PLACEHOLDER', JSON.stringify(key)))
    if (read !== null && read.seen > 0) break
  }
  if (read === null) {
    console.log(`  SKIP  ${key}：这一格没画出来（深链没生效或面板名不是节点名）`)
  } else {
    reached.push(key)
    totalLabels += read.seen
    totalShrink += read.shrink
    for (const x of read.out) offenders.push(`${key}/${x.text}(${x.h}<${x.floor},字${x.want})`)
    console.log(`  READ  ${key}: Label ${read.seen} 颗，SHRINK ${read.shrink} 颗，被压小 ${read.out.length} 颗`)
  }
  // 每格落一张图：这一族改的是"盒高 + 对齐"，判据全绿也可能把字挪位，必须目视
  await page.screenshot({ path: path.join(OUT, `${key}.png`) })
  await page.close()
}
console.log(`  截图目录：${OUT}`)

console.log('\n=== 被盒子压小的行 ===')
if (offenders.length === 0) console.log('  （无）')
for (const line of offenders) console.log('  ' + line)

// 修完一批之后用 `--print-baseline` 重生成上面那张表，别手抄
if (process.argv.includes('--print-baseline')) {
  console.log('\n// --- BASELINE 片段（贴进源文件替换 BASELINE 的构造）---')
  for (const x of [...new Set(offenders)].sort()) console.log(`  ${JSON.stringify(x)},`)
  console.log(`// 共 ${new Set(offenders).size} 条（去重后）`)
  await browser.close()
  await preview.close()
  process.exit(0)
}

// 反空转前置：每格都要走到、且真的读到过 Label —— 否则"零缺陷"是在读空集合
check('每一格都走到位（漏格会让这份清单假绿）', reached.length, KEYS.length)
checkTrue('这些格里确实读到过 Label（读到 0 颗说明遍历写错了）', totalLabels > 100)
// 恒真的"totalShrink >= 0"不写：清单不能靠一个不会失败的条件交差。
// 这条要能失败：基线里有点名行、却一颗 SHRINK 都没量到 ⇒ 遍历或枚举值变了，读的是空集合。
checkTrue('基线不是在读空集合（有基线行就必须量到 SHRINK 行）',
  totalShrink > 0 || BASELINE.size === 0)
const measured = new Set(offenders)
check('被压小的行**恰好**等于基线（新增会红；修好没删基线行也会红）',
  JSON.stringify([...measured].sort()) === JSON.stringify([...BASELINE].sort()), true)

console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项；SHRINK 行共 ${totalShrink} 颗 ===`)
await browser.close()
await preview.close()
process.exit(fail === 0 ? 0 : 1)
