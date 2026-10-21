/**
 * 职责：运营公告的**运行时**验收（收口清单 #2287 ⑤ / V26-2b）。
 * 依赖：playwright + tools/lib/preview-server.mjs + 一个活后端（dev 约定 http://localhost:8199）。
 *
 * <p><b>它盯的六件事</b>：
 * <ol>
 *   <li>设置页有「公告」那一行（入口要一级可见，提审按 §二 5/8/9 查）</li>
 *   <li>那一行<b>点得动</b>（行的监听挂在 `touch-end` 上 ⇒ 走 AppRoot.openNotice ⇒ 真的发了请求）</li>
 *   <li>点开之后切到<b>公告模式</b>（判据是 `row-notice-back` 这个「返回设置」行出现）</li>
 *   <li>切模式是<b>重画</b>而不是叠加（设置页那行 `row-notice` 在公告模式下**不再存在**）</li>
 *   <li>屏上<b>不出现内部字段</b>（`why`、表行 id 之类）</li>
 *   <li>页面零错误</li>
 * </ol>
 *
 * <p><b>为什么判据全部与表内容无关</b>：notice 表<b>出厂是空的</b>（合规：不把未获确认的活动权益
 * 或过期样例发给玩家），运营填真文案后表里才会有东西 ⇒ 判据若钉在「屏上出现某条公告」上，
 * 运营一填文案这条判据就红 ⇒ 判据必须钉在**结构**上（入口在不在、点不点得动、切不切模式）。
 *
 * <p><b>「看到内容」与「热更后看到新文案」不在这一份里</b>：那需要一份**带夹具的 notice 表**
 * （窗口内 + 窗口外各一行），而 reload 端点是从磁盘重读 ⇒ 得让后端用一份独立的 config 目录
 * （`ironoath.config-dir` 可用环境变量覆盖）⇒ 那是另一份探针/另一格的事。这一份先证明
 * 「入口 → 点得动 → 切模式 → 空态不画空白」这条链在真后端上真的通。
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const OUT = process.env.NOTICE_RUNTIME_OUT ?? path.resolve(process.cwd(), 'tmp/notice-runtime')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.NOTICE_RUNTIME_PORT ?? 8222)
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-notice] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
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

/**
 * 页面里读：设置页/公告模式的节点与文字。
 *
 * <p>读 `settings` 子树（两种模式都在这一个节点下画）⇒ 模式切换用**节点名**判，
 * 不用文字判 ⇒ 「公告」这两个字在设置页那行与公告模式标题里都出现，用文字判会混淆。
 */
function readNotice() {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  const settings = game.getChildByName('settings')
  if (!settings) {
    return { settingsMissing: true, rowNotice: false, rowBack: false, texts: [] }
  }
  const texts = []
  let rowNotice = false
  let rowBack = false
  const walk = (n) => {
    if (n.name === 'row-notice') rowNotice = true
    if (n.name === 'row-notice-back') rowBack = true
    const label = n.getComponent('cc.Label')
    if (label && typeof label.string === 'string' && label.string.length > 0) {
      texts.push(label.string)
    }
    for (const child of n.children) walk(child)
  }
  walk(settings)
  return { settingsMissing: false, rowNotice, rowBack, texts }
}

/**
 * 按节点名点一下（`emit` 与真人按下走同一个回调）。
 *
 * <p>用**字符串形式**而不是 `page.evaluate(fn, ...)`：后者只接受一个参数，而且闭包里的助手函数
 * 序列化不过去（只有回调源码会被送进页面）—— 本仓其它探针也是这个写法。
 */
const clickByName = (name, event) => page.evaluate(`(() => {
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas').getChildByName('Game')
  let found = null
  const walk = (n) => {
    if (found) return
    if (n.name === '${name}' && n.activeInHierarchy) { found = n; return }
    for (const child of n.children) walk(child)
  }
  walk(game)
  if (!found) return false
  found.emit('${event}')
  return true
})()`)

const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `notice-${Date.now()}`)
const page = await context.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()) })

await page.goto(`${preview.origin}/?panel=settings`, { waitUntil: 'networkidle' })
preview.assertRewritten()
await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
  null, { timeout: 60_000 })
await page.waitForTimeout(3500)
await hideGuideOverlay(page)
await page.waitForTimeout(500)

const before = await page.evaluate(readNotice)
check('设置页挂上了场景', before.settingsMissing, false)
check('设置页有「公告」那一行', before.rowNotice, true)
check('点之前不在公告模式（没有「返回设置」行）', before.rowBack, false)
console.log(`  设置页文字：${(before.texts ?? []).join(' ｜ ')}`)

// 行的监听挂在 `touch-end` 上（`SettingsPanelView.drawActionRow`），不是 touch-start
const clicked = await clickByName('row-notice', 'touch-end')
await page.waitForTimeout(1200)
const after = await page.evaluate(readNotice)
check('点得动那一行', clicked, true)
check('点之后切到公告模式（「返回设置」行出现）', after.rowBack, true)
check('切模式是重画而不是叠加（设置页那行不再存在）', after.rowNotice, false)

const shown = (after.texts ?? []).join('｜')
console.log(`  公告模式文字：${shown}`)
check('屏上不出现内部字段（why / 表行 id 痕迹）', /why|notice_|popup_stuck_supply|notice_welcome/.test(shown), false)
await page.screenshot({ path: path.join(OUT, 'notice.png') })

// 点「返回设置」能收回设置页
const back = await clickByName('row-notice-back', 'touch-end')
await page.waitForTimeout(800)
const backView = await page.evaluate(readNotice)
check('点「返回设置」收得掉', back, true)
check('收回后回到设置页（那行回来了）', backView.rowNotice, true)
check('收回后不在公告模式了', backView.rowBack, false)
await page.screenshot({ path: path.join(OUT, 'notice-back.png') })

check('页面零错误', errors.join(' | ') || '无', '无')

await browser.close()
await preview.close()
console.log(`  截图：${path.join(OUT, 'notice.png')} 与 ${path.join(OUT, 'notice-back.png')}`)
console.log(`\n=== 公告运行时验收：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)
