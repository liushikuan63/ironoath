#!/usr/bin/env node
/**
 * 职责：验武将面板"一行都没有"时**说的是人话**，而不是留一整块空白。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：BACKEND_ORIGIN=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 HERO_PROBE_PORT（默认 8191，同机并发时换一个）
 *   BACKEND_ORIGIN=http://localhost:8199 HERO_PROBE_PORT=8191 node tools/verify-hero-empty-runtime.mjs
 *
 * <p><b>为什么单独跑这一份</b>：#344~#346 那一族（"集合为空 → 界面静默什么都不画"）横扫出四条，
 * 其中武将/战令两条当时**没有任何量具打开过那两个面板** —— 改了拿不出"真跑 + 截图"的证据，
 * 所以那两格停在队列里。这份量具就是那块的钥匙：先有尺子，再动视图。
 *
 * <p><b>目前只验到"零行相"</b>：真后端的新设备本来就没有武将 ⇒ `Empty` 必须出现且写着「暂无武将」。
 * <b>另一半（有行 ⇒ 必须藏）还没验</b>：`/hero/list` 夹具没让面板画起来（读数 `header=""`），
 * 那种状态下这条断言会静默通过，所以先不写 —— 见文件里相位 B 那段。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

// 必须显式给后端：静默回落到 http://localhost:8080 等于"打到另一台机器上读数"，
// 而读数错得像产品缺陷（2026-09-21 实测：变量名传错时一份量具红了 13 条，客户端与夹具都没错）。
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[hero-empty] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.HERO_PROBE_PORT ?? 8191)
const OUT = path.resolve(process.cwd(), 'client/build/hero-verify')
mkdirSync(OUT, { recursive: true })

let pass = 0
let fail = 0
const check = (msg, actual, expected) => {
  if (actual === expected) { pass += 1; console.log(`  PASS  ${msg}（${String(actual)}）`) }
  else { fail += 1; console.log(`  FAIL  ${msg}：期望 ${String(expected)}，实际 ${JSON.stringify(actual)}`) }
}
const checkTrue = (msg, actual) => check(msg, actual, true)

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 武将面板空态验收：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const cors = (request) => ({
  'access-control-allow-origin': request.headers()['origin'] ?? '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
})
const READ = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.getChildByName('hero')
  if (panel === undefined || panel === null) return null
  let rows = 0
  let liveRows = 0
  let empty = null
  let header = ''
  const walk = (n) => {
    if (n.name === 'HeroRow') { rows += 1; if (n.activeInHierarchy !== false) liveRows += 1 }
    if (n.name === 'Empty') empty = n
    if (n.name === 'Header') header = n.getComponent('cc.Label')?.string ?? ''
    for (const c of n.children) walk(c)
  }
  walk(panel)
  return {
    rows, liveRows, header,
    emptyFound: empty !== null,
    emptyActive: empty === null ? null : empty.active === true,
    emptyText: empty === null ? null : (empty.getComponent('cc.Label')?.string ?? ''),
  }
})()`

/** 有行相：`/hero/list` 换成一行武将（字段照 contract/proto/hero.schema.json 的必填集） */
const stubHeroList = async (context) => {
  await context.route('**/hero/list*', async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors(route.request()) })
      return
    }
    const hero = {
      heroId: 'hero_probe_guanyu', name: '关羽', rarity: 'SSR', level: 40, exp: 1200, expToNext: 800,
      maxLevel: 60, star: 3, maxStar: 5, awaken: 0, maxAwaken: 3,
      mainSkillId: 'skill_guanyu_main', mainSkillName: '武圣激将', mainSkillLevel: 3,
      subSkillId: 'skill_guanyu_sub', subSkillName: '偃月蓄势', subSkillLevel: 1, maxSkillLevel: 10,
      equips: [], baseAttrs: { might: 96, command: 92, wisdom: 75 },
      finalAttrs: { might: 96, command: 92, wisdom: 75 }, power: 12345, bondWith: null,
    }
    const lineups = [0, 1, 2].map((presetIndex) => ({
      presetIndex, main: null, sub1: null, sub2: null, activeBonds: [],
      bonus: { atkFixed: 0, defFixed: 0, skillFixed: 0, commandValue: 0, capped: false, breakdown: [] },
    }))
    const body = JSON.stringify({
      code: 0, msg: '成功', serverNow: Date.now(),
      data: {
        heroes: [hero], lineups,
        fragments: [], troopCap: 1000, troopsInUse: 0,
        serverNow: Date.now(),
      },
    })
    await route.fulfill({
      status: 200,
      headers: { ...cors(route.request()), 'content-type': 'application/json' },
      body,
    })
  })
}

const errors = []
const url = new URL(`${preview.origin}/`)
url.searchParams.set('panel', 'hero')

// ---------- 相位 A：真后端的新设备本来就没有武将 —— 空态必须出现 ----------
const ctxA = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await ctxA.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `hero-empty-${Date.now()}`)
const pageA = await ctxA.newPage()
pageA.on('pageerror', (e) => errors.push(`A:${e.message}`))
await pageA.goto(url.toString(), { waitUntil: 'networkidle' })
// 自检：产物里那两处写死的后端地址有没有真的被改写成本轮要打的那棵。
// 漏了这一句，传错变量名就是"打到另一台机器上读数"，红得像是产品缺陷（台账 #371）。
preview.assertRewritten()
await pageA.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
let readA = null
for (let i = 0; i < 30; i += 1) {
  await pageA.waitForTimeout(500)
  readA = await pageA.evaluate(READ)
  if (readA?.emptyFound === true) break
}
check('武将面板找得到、`Empty` 那一行在树里', readA?.emptyFound, true)
check('新设备没有武将 ⇒ 行数是 0（这一条不成立，下面两条就是空判）', readA?.rows, 0)
check('零行时空态那一行必须显示出来', readA?.emptyActive, true)
check('写着「暂无武将」而不是空白或 id', readA?.emptyText, '暂无武将')
await pageA.screenshot({ path: path.join(OUT, 'hero-empty.png') })
console.log(`  截图：${path.join(OUT, 'hero-empty.png')}`)
await ctxA.close()

// ---------- 相位 B：给一行武将 —— 空态必须藏起来 ----------
// 第一版这份夹具缺 `fragments` / `troopCap` / `troopsInUse` / `activeBonds`，还把 `equips`
// 写成了 `equipped` ⇒ 面板整块没画（`header` 是空串），那句"空态被藏起来"**静默判绿**。
// 教训写在这：**"读数为零"不等于"这一相没数据"，要先证明面板画过**（用 header 非空当证据）。
const ctxB = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await ctxB.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `hero-full-${Date.now()}`)
await stubHeroList(ctxB)
const pageB = await ctxB.newPage()
pageB.on('pageerror', (e) => errors.push(`B:${e.message}`))
await pageB.goto(url.toString(), { waitUntil: 'networkidle' })
await pageB.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)
let readB = null
for (let i = 0; i < 30; i += 1) {
  await pageB.waitForTimeout(500)
  readB = await pageB.evaluate(READ)
  if ((readB?.rows ?? 0) > 0) break
}
console.log(`  相位 B 读数：rows=${readB?.rows} liveRows=${readB?.liveRows} header=${JSON.stringify(readB?.header)}`)
check('夹具那行武将真的画出来了（还要 header 非空，证明这一相面板真的渲染过）',
  (readB?.rows ?? 0) > 0 && (readB?.header ?? '') !== '', true)
check('有行时空态必须藏起来（有行还印「暂无武将」是自打嘴巴）', readB?.emptyActive, false)
await pageB.screenshot({ path: path.join(OUT, 'hero-one-row.png') })
console.log(`  截图：${path.join(OUT, 'hero-one-row.png')}`)

check('两相跑完零页面级 error', errors.length, 0)
if (errors.length > 0) for (const e of errors.slice(0, 3)) console.log(`    error: ${e.slice(0, 160)}`)

await browser.close()
preview.close?.()
console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项 ===`)
process.exit(fail === 0 ? 0 : 1)
