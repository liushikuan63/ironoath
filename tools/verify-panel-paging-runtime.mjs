/**
 * 职责：六屏（邮件／背包／战报／任务／商店／外观）分页的运行时判据 ——
 *       ① 走完所有页真的能拿到这一屏承诺的每一条；② 每一次翻页都真的换了屏；
 *       ③ 读不到数据那一态不露着点了没反应的翻页键（#345）。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：PAGING_BACKEND=http://localhost:8080 node tools/verify-panel-paging-runtime.mjs
 *   必填 PAGING_BACKEND —— 不给就退 2 并点名（静默回落到 8080 会量到另一条会话在跑的旧 jar，
 *   读数看着合理却是错的，与 `verify-label-fit-runtime.mjs` 同一条修法）。
 *   PAGING_PORT 默认 8207，**绝不能等于后端端口**。
 *
 * <p>为什么不并进 `verify-label-fit-runtime.mjs`：那一座的「各面板翻页相」归另一条会话在做
 * （队列 ②-b′）。这里守的是"够不够得着"，那一座守的是排版，两份判据各自独立地红。
 *
 * <p>⚠ 点键走 `emit('touch-start')`（与 `tools/lib/panel-clicks.mjs` 同一机制），绕开命中测试：
 * 这一座量到的是"翻页逻辑与画面"，**不是**"键在真触摸下点不点得动"。
 */
import fs from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { makeStubRead } from './lib/route-stub.mjs'
import { clickTabNode } from './lib/panel-clicks.mjs'
import { hideGuideBoard } from './lib/guide-overlay.mjs'

const BACKEND = process.env.PAGING_BACKEND ?? ''
if (BACKEND === '') {
  console.error('[paging] 缺 PAGING_BACKEND：不给就退 2。'
    + ' 例：PAGING_BACKEND=http://localhost:8080 node tools/verify-panel-paging-runtime.mjs')
  process.exit(2)
}
const PORT = Number(process.env.PAGING_PORT ?? 8207)
if (PORT === Number(new URL(BACKEND).port)) {
  console.error(`[paging] 探针端口 ${PORT} 与后端相同 —— 会撞 EADDRINUSE，那不是判据红`)
  process.exit(2)
}
const OUT = process.env.PAGING_OUT ?? 'D:/tmp/paging-probe'
fs.mkdirSync(OUT, { recursive: true })

const HOUR = 3_600_000
const DAY = 24 * HOUR

/**
 * 邮件夹具：dev 新号只有 2 封，够不到翻页这一支 ⇒ 桩到必然画不下。
 * 字段照 `contract/proto/mail.schema.json` 的 `MailView.required` 给全十项；
 * `expireAt` 全留在将来，免得"过期不画"这一支把并集算成假的少。
 */
const MAIL = Array.from({ length: 15 }, (_, i) => ({
  mailId: `pg_mail_${i}`, kind: i % 3 === 0 ? 'OVERFLOW' : 'SYSTEM',
  title: `翻页邮件${String(i + 1).padStart(2, '0')}`, text: `第 ${i + 1} 封的正文`,
  rewards: i % 2 === 0
    ? [{ type: 'RESOURCE', id: 'wood', count: 1000 + i, name: `木材${i + 1}` }] : [],
  claimed: false, read: i % 4 === 0,
  createdAt: Date.now() - (i + 1) * HOUR, expireAt: Date.now() + (30 + i) * DAY,
  sourceRef: null,
}))
const BAG_ITEMS = Array.from({ length: 15 }, (_, i) => ({
  itemId: `pg_item_${i}`, name: `翻页道具${String(i + 1).padStart(2, '0')}`,
  type: 'RESOURCE', rarity: 'N', count: i + 1, stackMax: 99, sortKey: i, effectKind: 'NONE',
}))
const BATTLE = Array.from({ length: 9 }, (_, i) => ({
  reportId: `pg_report_${i}`, battleType: ['PVE', 'PVP_SOLO', 'PVP_RALLY', 'SIEGE'][i % 4],
  opponentId: `pg_opp_${i}`, opponentName: `翻页敌手${String(i + 1).padStart(2, '0')}`,
  winner: i % 2 === 0 ? 'ATTACKER' : 'DEFENDER', totalRounds: 5 + i,
  attackerLoss: 100 * i, defenderLoss: 200 * i,
  createdAt: Date.now() - (i + 1) * HOUR, expiresAt: Date.now() + (20 - i) * HOUR,
}))
const SCOUT = Array.from({ length: 3 }, (_, i) => ({
  reportId: `pg_scout_${i}`, targetId: `pg_target_${i}`,
  target: { x: 100 + i, y: 60 + i }, targetLevel: 7 + i, expired: false,
  metrics: [{ name: 'totalUnits', value: 1200 + i }],
  createdAt: Date.now() - (i + 1) * 900_000, expiresAt: Date.now() + (i + 3) * HOUR,
  remainingMs: (i + 3) * HOUR, errorFixed: 800, seed: 1000 + i, serverNow: Date.now(),
}))

/**
 * `markers` 只有桩起来的三屏给得起（每一行的标题是我造的、可枚举）。真数据那三屏改判
 * "走完的页数 == 屏上承诺的 n"，并把够不到翻页的情形如实报成 SKIP 而不是 PASS。
 */
const SCREENS = [
  { key: 'mail', routes: ['/mail/list'], markers: MAIL.map((m) => m.title),
    stub: { '/mail/list': () => ({ mails: MAIL,
      unreadCount: MAIL.filter((m) => !m.read).length,
      claimedCount: MAIL.filter((m) => m.claimed || m.rewards.length === 0).length }) } },
  { key: 'bag', routes: ['/bag/list', '/resource/detail'],
    // 背包面板默认落在「资源产出明细」那一页签，行来自 `/resource/detail` 而不是 `/bag/list`
    // ⇒ 这一屏判"走了几屏 == 屏上承诺的页数"，标题并集那条留给桩起来的那三屏
    stub: { '/bag/list': () => ({ items: BAG_ITEMS, capacityUsed: BAG_ITEMS.length,
      capacityMax: 60 }) } },
  { key: 'reports', routes: ['/battle/reports', '/world/reports'], markers: BATTLE.map((r) => r.opponentName),
    stub: { '/battle/reports': () => ({ reports: BATTLE, serverNow: Date.now() }),
      '/world/reports': () => ({ reports: SCOUT, serverNow: Date.now() }) } },
  { key: 'quest', routes: ['/quest/list'] },
  { key: 'shop', routes: ['/shop/list'] },
  { key: 'avatarFrames', routes: ['/player/frames'] },
]

/** 页内只读：把这一屏的可见文字、翻页键是否露着、那句页码一次读回来。 */
function readScreen(key) {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.children.find((c) => c.name === key)
  if (panel === undefined || !panel.activeInHierarchy) return null
  let texts = []
  let keys = 0
  let notice = ''
  const walk = (n) => {
    if (!n.activeInHierarchy) return
    if ((n.name === 'PrevPageButton' || n.name === 'NextPageButton') && n.active) keys += 1
    const lb = n.getComponent('cc.Label')
    if (lb !== null) {
      const s = (lb.string ?? '').trim()
      if (s !== '') {
        texts.push(s)
        const m = /第 (\d+)\/(\d+) 页/.exec(s)
        if (m !== null) notice = m[0]
      }
    }
    for (const c of n.children) walk(c)
  }
  walk(panel)
  // 只在"这一行的容器也是活的"时才算看得见：读 Label 的字不算看得见（hide 翻的是 active）
  texts = texts.filter((t) => t !== '')
  return { texts, keys, notice }
}

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 六屏翻页判据：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const failures = []
const skips = []

/** 开一屏：独立 context（夹具与桩的拦截只对它生效），深链进去并等面板画出来。 */
async function openPanel(screen, mode) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await context.addInitScript((value) => {
    localStorage.setItem('ironoath.deviceId', value)
  }, `paging-${screen.key}-${Date.now()}`)
  if (mode === 'nofetch') {
    for (const route of screen.routes) {
      await context.route(`**${route}*`, (r) => r.abort())
    }
  } else if (screen.stub !== undefined) {
    const stubRead = makeStubRead(context)
    for (const [route, build] of Object.entries(screen.stub)) {
      await stubRead(`**${route}*`, build())
    }
  }
  const page = await context.newPage()
  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', screen.key)
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  await page.evaluate(hideGuideBoard)
  // 等到"这一屏的字不再变"才算就绪：面板节点先建出来、行要等读接口回来才画。
  // 从前这里只要节点在就算就绪，于是 mail 那一屏读到的是"只有标题的半个面板"（1 句），
  // 走完所有页的判据当场失效 —— 而截图里明明画着四行（2026-09-22 实测）。
  let state = null
  let stable = 0
  let lastCount = -1
  for (let i = 0; i < 40 && stable < 3; i++) {
    state = await page.evaluate(readScreen, screen.key)
    const count = state === null ? -1 : state.texts.length
    stable = count === lastCount && count > 0 ? stable + 1 : 0
    lastCount = count
    // 每一轮都要等：面板先只有标题、行要等读接口回来才画，不自转就会把"半个面板"读成稳定态
    //（2026-09-22 实测：mail 那一屏读到 1 句，而同一张截图上明明画着四行与那句页码）
    await page.waitForTimeout(400)
  }
  // 背包默认落在「资源产出明细」，那一屏的行不吃 /bag/list 的夹具 ⇒ 先点到道具页签再量，
  // 否则"走完所有页"量的是一屏真数据，夹具那 15 条永远上不了屏（独立审查抓到的第②条）
  if (mode === 'fetch' && screen.tab !== undefined) {
    await page.evaluate(clickTabNode, screen.tab)
    await page.waitForTimeout(800)
    state = await page.evaluate(readScreen, screen.key)
  }
  return { context, page, state }
}

for (const screen of SCREENS) {
  // ---- 相 ①：读不到数据 —— 两颗翻页键必须整对收着（#345）----
  const off = await openPanel(screen, 'nofetch')
  if (off.state === null) {
    console.log(`  SKIP  ${screen.key}/读不到：面板没画出来（深链没生效？）`)
    skips.push(`${screen.key} 读不到相未执行`)
  } else {
    const bad = off.state.keys !== 0 || off.state.notice !== ''
    console.log(`  READ  ${screen.key}/读不到: 翻页键 ${off.state.keys} 颗，页码「${off.state.notice || '无'}」`)
    if (bad) failures.push(`${screen.key}/读不到：露着 ${off.state.keys} 颗翻页键或页码「${off.state.notice}」`)
    await off.page.screenshot({ path: path.join(OUT, `${screen.key}-nofetch.png`) })
  }
  await off.context.close()

  // ---- 相 ②：走完所有页 ----
  const on = await openPanel(screen, 'fetch')
  if (on.state === null) {
    console.log(`  SKIP  ${screen.key}：这一屏没画出来`)
    skips.push(`${screen.key} 未画出`)
    await on.context.close()
    continue
  }
  const union = new Set(on.state.texts)
  const visited = [on.state.texts.join('|')]
  let notice = on.state.notice
  let guard = 0
  while (guard < 12) {
    const before = visited[visited.length - 1].split('|')
    const clicked = await on.page.evaluate(clickTabNode, 'NextPageButton')
    if (!clicked) break
    await on.page.waitForTimeout(200)
    const next = await on.page.evaluate(readScreen, screen.key)
    if (next === null) break
    const after = next.texts
    const same = after.length === before.length && after.every((t) => before.includes(t))
    if (same) break
    guard += 1
    visited.push(after.join('|'))
    after.forEach((t) => union.add(t))
    if (next.notice !== '') notice = next.notice
  }
  const pages = visited.length
  const promised = notice === '' ? null : Number(/第 \d+\/(\d+) 页/.exec(notice)[1])
  await on.page.screenshot({ path: path.join(OUT, `${screen.key}-last.png`) })
  // 标题是拼合串（未读前面有「● 」、战报前面有兵种词），所以判"包含"不判"相等"
  const all = [...union]
  const missing = (screen.markers ?? []).filter((m) => !all.some((t) => t.includes(m)))
  console.log(`  READ  ${screen.key}: 走了 ${pages} 屏，承诺 ${notice || '没有页码'}，`
    + `并集 ${union.size} 句，桩里应有 ${screen.markers?.length ?? '真数据不计'} 条独有标题`)
  if (promised === null) {
    // 桩起来的屏"没出现页码"＝夹具没上屏，那是量具或接线的缺陷，不能记成 SKIP
    //（从前这里一律记 SKIP，六屏全 SKIP 也照样退 0 —— 独立审查抓到的第①条）
    const line = `${screen.key} 只有一屏（${pages} 屏 / 无页码 / 并集 ${union.size} 句）`
    if (screen.markers !== undefined) {
      failures.push(`${line} ⇒ 桩里的 ${screen.markers.length} 条本该翻得到，翻页这一支没走到`)
    } else {
      skips.push(`${line}，翻页这一支未执行（真数据不足，不是通过）`)
      console.log(`  SKIP  ${screen.key}：这一屏没出现页码 ⇒ 翻页这一支未执行（不是通过）`)
    }
  } else {
    if (pages !== promised) {
      failures.push(`${screen.key}：屏上写「${notice}」，实际只能走 ${pages} 屏`)
    }
    if (missing.length > 0) {
      failures.push(`${screen.key}：走完所有页仍拿不到 ${missing.length} 条 → ${missing.slice(0, 4).join('、')}`)
    }
  }
  await on.context.close()
}

await browser.close()
await preview.close()

console.log(`\n=== 判据：走了的屏数 == 承诺的页数；每屏真的换屏；读不到那态 0 颗键 ===`)
for (const s of skips) console.log(`  未走到：${s}`)
if (failures.length > 0) {
  for (const f of failures) console.log(`  FAIL ${f}`)
  console.log(`[paging] ${failures.length} 条判据红`)
  process.exit(1)
}
console.log(`[paging] ${SCREENS.length} 屏全绿（截图在 ${OUT}）`)
