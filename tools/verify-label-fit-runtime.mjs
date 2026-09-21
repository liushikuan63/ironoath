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
const BASELINE = new Set([
  "army/先手动训一批，「自动(20<27,字14)",
  "army/可用 0 · 需要兵(22<27,字14)",
  "army/训练消耗 铁矿 30(20<27,字13)",
  "army/训练消耗 铁矿 34(20<27,字13)",
  "army/训练消耗 铁矿 38(20<27,字13)",
  "army/训练消耗 铁矿 42(20<27,字13)",
  "army/训练消耗 铁矿 47(20<27,字13)",
  "army/重步兵 T1(26<27,字18)",
  "army/重步兵 T2(26<27,字18)",
  "army/重步兵 T3(26<27,字18)",
  "army/重步兵 T4(26<27,字18)",
  "army/重步兵 T5(26<27,字18)",
  "bag/    合计(24<27,字17)",
  "bag/    科技加成(24<27,字17)",
  "bag/    联盟加成(24<27,字17)",
  "bag/    道具 buf(24<27,字17)",
  "bag/    领地基础产出(24<27,字17)",
  "bag/STAMINA(24<27,字17)",
  "bag/STONE(24<27,字17)",
  "bag/每小时 +10 · (20<27,字13)",
  "bag/每小时 +200 ·(20<27,字13)",
  "city/Lv1(14<27,字10)",
  "city/主城(12<27,字9)",
  "social/未加入小队(24<27,字17)",
  "social/还没有人建立小队(24<27,字17)",
  "social/需要主城 5 级，当(20<27,字13)"
])

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
  const stretched = []
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
          const t = n.getComponent('cc.UITransform')
          const h = Math.round(t.height)
          const floor = 27
          if (h < floor) {
            out.push({ text: str.slice(0, 10), h, want: lb.fontSize, floor,
              wrap: lb.enableWrapText !== false })
          }
          // 反方向也真实存在：#366 的迁移曲线上 20 号字给盒高 36 时落地 24（**被放大**）。
          // 只报"单行、且文本宽度根本用不满盒子"的那几颗 —— 多行文案给高盒子是正当需求，
          // 混进来会把正常排版报成缺陷。
          // 这里必须写「反斜杠 + n」：整段是模板字符串，直接写单反斜杠加 n 会被 Node 先转成
          // 真实换行 —— 落在字符串里就是语法错，落在注释里就是把注释截断成代码（本轮两次都踩了）。
          if (h > floor + 8 && str.indexOf('\\n') < 0 && lb.enableWrapText !== false) {
            const est = Math.round(str.length * lb.fontSize * 0.95)
            if (est < t.width) {
              stretched.push({ text: str.slice(0, 10), h, want: lb.fontSize, floor, est, boxW: Math.round(t.width) })
            }
          }
        }
      }
    }
    for (const c of n.children) walk(c)
  }
  walk(panel)
  return { seen, shrink, out, stretched }
})()`

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 全客户端"字被盒子压小"清单：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `labelfit-${Date.now()}`)

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
/**
 * 背包夹具：新号一进这一格只有 3 颗 Label（空态），量不到"有货之后才画出来的行"。
 * 字段逐条对着 `contract/proto/bag.schema.json` 的 `BagItem.required` 给
 * （itemId/name/type/rarity/count/stackMax/sortKey/effectKind）—— 少一个就是"fixture 没镜像真实接线"，
 * 那条假红比缺覆盖更坑（记忆 [[tooling-windows-sandbox-orphan-lock]]）。
 */
const BAG_ITEMS = ['木材箱(1万)', '强化石 ×12', '限时头像框体验卡'].map((name, i) => ({
  itemId: `probe_item_${i}`, name, type: 'RESOURCE', rarity: i === 2 ? 'SR' : 'N',
  count: i + 1, stackMax: 99, sortKey: i, effectKind: 'NONE',
}))
await context.route('**/bag/list*', async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, { items: BAG_ITEMS, capacityUsed: BAG_ITEMS.length, capacityMax: 60 })
})

const offenders = []
const stretched = []
const reached = []
let totalLabels = 0
let totalShrink = 0

for (const key of KEYS) {
  const page = await context.newPage()
  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', key)
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  let read = null
  // 不能"见到 >0 就停"：空态本来就有几颗 Label，那样永远读不到"有数据之后才画出来的行"。
  // 改成**计数稳定**才收（连续两次一样），最多 24 次 ×250ms。
  let prevSeen = -1
  for (let i = 0; i < 24; i += 1) {
    await page.waitForTimeout(250)
    read = await page.evaluate(WALK.replace('KEY_PLACEHOLDER', JSON.stringify(key)))
    if (read !== null && read.seen > 0 && read.seen === prevSeen) break
    prevSeen = read?.seen ?? -1
  }
  // 计数稳定 ≠ 覆盖完整：实测同一份产物连跑两遍，SHRINK 总数会 91 / 71 跳（列表虚拟化 + 渲染时机），
  // 那意味着"基线"不可复现、门会随机红。所以再补三轮，**取并集与最大值**让覆盖单调收敛。
  for (let round = 0; round < 3; round += 1) {
    await page.waitForTimeout(400)
    const again = await page.evaluate(WALK.replace('KEY_PLACEHOLDER', JSON.stringify(key)))
    if (again === null || read === null) continue
    read.seen = Math.max(read.seen, again.seen)
    read.shrink = Math.max(read.shrink, again.shrink)
    for (const list of ['out', 'stretched']) {
      const have = new Set(read[list].map((x) => x.text + '@' + x.h))
      for (const x of again[list] ?? []) {
        if (!have.has(x.text + '@' + x.h)) read[list].push(x)
      }
    }
  }
  if (read === null) {
    console.log(`  SKIP  ${key}：这一格没画出来（深链没生效或面板名不是节点名）`)
  } else {
    reached.push(key)
    totalLabels += read.seen
    totalShrink += read.shrink
    for (const x of read.out) offenders.push(`${key}/${x.text}(${x.h}<${x.floor},字${x.want})`)
    for (const x of read.stretched ?? []) stretched.push(`${key}/${x.text}(${x.h}>${x.floor}+8,字${x.want},估宽${x.est}/盒${x.boxW})`)
    console.log(`  READ  ${key}: Label ${read.seen} 颗，SHRINK ${read.shrink} 颗，被压小 ${read.out.length} 颗，疑似被放大 ${(read.stretched ?? []).length} 颗`)
  }
  // 每格落一张图：这一族改的是"盒高 + 对齐"，判据全绿也可能把字挪位，必须目视
  await page.screenshot({ path: path.join(OUT, `${key}.png`) })
  await page.close()
}
console.log(`  截图目录：${OUT}`)
// 自检放在**遍历之后**：改写计数是按"服务出去的字节"累加的，goto 刚返回就断言会在
// 首个资源还没记完时误抛（2026-09-21 实测：连跑到第 4 次抛"一个字都没换到"，红得莫名其妙）。
preview.assertRewritten()

console.log('\n=== 被盒子压小的行 ===')
if (offenders.length === 0) console.log('  （无）')
for (const line of offenders) console.log('  ' + line)

/**
 * `--calibrate`：把"落地尺寸 == 设定尺寸"的那个盒高**逐字号量出来**，用来检验 `oneLineFloorHeight` 的外推边界。
 *
 * <p>下限 `max(字号+14, 30)` 是台账 #366 在 14~20 号字上量出来的，而基线里有 9/10 号的小字（内城建筑角标）——
 * 小字号上照 30 去钉可能反向**放大**，那就是判据自己造的假红。所以先量曲线再决定要不要动那两行。
 * 做法：每个字号挑一颗 SHRINK Label，从 `字号+2` 逐档抬盒高（每档等两帧），报出落地尺寸；
 * 落地尺寸第一次等于设定值的那一档就是它的"自然一行高"（#366 的曲线上没有平台段，只有交点）。
 */
if (process.argv.includes("--calibrate")) {
  const CAL = `(async () => {
    const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.children.find((c) => c.name === KEY_PLACEHOLDER)
    if (panel === undefined) return null
    const twoFrames = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
    const bySize = new Map()
    const collect = (n) => {
      const lb = n.getComponent('cc.Label')
      if (lb !== null && lb !== undefined && (lb.string ?? '').length > 0 && lb.overflow === 2
          && n.activeInHierarchy && !bySize.has(lb.fontSize)) bySize.set(lb.fontSize, n)
      for (const c of n.children) collect(c)
    }
    collect(panel)
    const out = []
    for (const [size, node] of [...bySize.entries()].sort((a, b) => a[0] - b[0])) {
      const t = node.getComponent('cc.UITransform')
      const lb = node.getComponent('cc.Label')
      const saved = { w: t.width, h: t.height }
      const curve = []
      let cross = -1
      // 起点不能是「字号+2」：交点实测是个与字号无关的常数（约 27），大字号从 28 起扫会直接跳过它
      // （26 号字上一轮就返回 -1）。步长必须是 1：落地尺寸是整数，步长 2 会跳过交点。
      for (let h = 20; h <= size + 40; h += 1) {
        t.setContentSize(saved.w, h)
        await twoFrames()
        const a = lb.actualFontSize ?? -1
        curve.push(h + ":" + a)
        // 交点 = 落地尺寸回到**设定字号**的那一档（不是 1.5×：那是 NONE 模式下的光栅尺寸口径）
        if (cross < 0 && Math.abs(a - size) < 0.51) cross = h
      }
      t.setContentSize(saved.w, saved.h)
      await twoFrames()
      out.push({ size, cross, text: (lb.string ?? "").slice(0, 6), curve: curve.join(" ") })
    }
    return out
  })()`
  for (const key of KEYS) {
    const page = await context.newPage()
    const url = new URL(`${preview.origin}/`)
    url.searchParams.set('panel', key)
    await page.goto(url.toString(), { waitUntil: 'networkidle' })
    await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
      null, { timeout: 60_000 })
    await page.waitForTimeout(1500)
    const rows = await page.evaluate(CAL.replace("KEY_PLACEHOLDER", JSON.stringify(key)))
    for (const r of rows ?? []) {
      console.log(`  CAL ${key}/${r.text} 字号${r.size} 交点盒高=${r.cross}  |  ${r.curve}`)
    }
    await page.close()
  }
  await browser.close()
  await preview.close()
  process.exit(0)
}
// 修完一批之后用 `--print-baseline` 重生成上面那张表，别手抄
if (stretched.length > 0) {
  console.log('\n=== 疑似被盒子放大的行（只报不改，等逐屏判断：见台账 #377） ===')
  for (const line of stretched) console.log('  ' + line)
}
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
