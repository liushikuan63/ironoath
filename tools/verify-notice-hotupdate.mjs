/**
 * 职责：**运营公告热更**的运行时验收（收口清单 #2287 ⑤ / V26-2b，goal 判据 ④ 的后半）。
 * 依赖：playwright + tools/lib/preview-server.mjs + **自己起的一个后端**。
 *
 * <p><b>它要证明的那一件事</b>：**服务端改了公告文案，玩家再次打开公告就看到新文案，不用发版**。
 * 光证明「端点能通」不够 —— 那条路没有热更。
 *
 * <p><b>为什么自己起后端</b>：夹具必须是一份**带数据**的 notice 表（窗口内 + 窗口外各一行），
 * 而 `POST /ops/config/reload` 是**从磁盘重读** ⇒ 后端得读一份**独立的 config 目录**。
 * 后端是 Spring Boot 起的（`java -jar ... --server.port=... --ironoath.ops.token=...`），
 * 所以再传一个 `--ironoath.config-dir=<目录>` 即可（`GameProperties.configDir` 的 relaxed binding）。
 * ⇒ 夹具写在临时目录里，**零污染**：不动仓库里的 contract/config，也不改共享脚本。
 *
 * <p><b>四条判据</b>：
 * <ol>
 *   <li>窗口内那行<b>出现在屏上</b>（服务端算好了窗口才下发）</li>
 *   <li>窗口外那行<b>不在屏上</b>（过期的不许发给玩家）</li>
 *   <li>改夹具文案 → reload → 客户端再点一次 ⇒ 屏上是<b>新文案</b>（这一条就是热更本身）</li>
 *   <li>页面零错误</li>
 * </ol>
 */
import { chromium } from 'playwright'
import { spawn } from 'node:child_process'
import { cpSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'

const OUT = process.env.NOTICE_HOTUPDATE_OUT ?? path.resolve(process.cwd(), 'tmp/notice-hotupdate')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.NOTICE_HOTUPDATE_PORT ?? 8223)
const BACKEND_PORT = Number(process.env.NOTICE_HOTUPDATE_BACKEND_PORT ?? 8233)
const OPS_TOKEN = 'notice-hotupdate-local'
const JDK = process.env.IRONOATH_JDK ?? 'D:/Java/jdk/microsoft-jdk-17/bin/java.exe'
const JAR = process.env.GAME_WEB_JAR ?? 'server/game-web/target/game-web.jar'
const ARTIFACT = 'client/build/web-mobile'
const CONFIG_DIR = path.join(os.tmpdir(), 'ironoath-notice-hotupdate-config')

const LIVE_TITLE = '热更探针 · 窗口内'
const LIVE_BODY_V1 = '第一版文案（夹具注入）'
const LIVE_BODY_V2 = '第二版文案（热更之后）'
const EXPIRED_TITLE = '热更探针 · 窗口外'

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

/** 造一份带夹具的 notice 表：窗口内一行（不限）+ 窗口外一行（已过期）。 */
function writeNoticeTable(body) {
  const file = path.join(CONFIG_DIR, 'notice.json')
  const table = {
    table: 'notice',
    version: body === LIVE_BODY_V1 ? 1 : 2,
    comment: 'V26 热更探针夹具（临时目录，不入库）',
    fieldTypes: {
      id: 'STRING_KEY', title: 'STRING', body: 'STRING',
      startAt: 'LONG_NONNEG', endAt: 'LONG_NONNEG', priority: 'LONG_NONNEG', why: 'STRING',
    },
    rows: [
      { id: 'notice_live', title: LIVE_TITLE, body, startAt: 0, endAt: 0, priority: 10, why: '窗口内' },
      { id: 'notice_expired', title: EXPIRED_TITLE, body: '过期样例，不该发给玩家', startAt: 0, endAt: 1, priority: 5, why: '窗口外' },
    ],
  }
  writeFileSync(file, JSON.stringify(table, null, 2) + '\n', 'utf8')
}

/** 起后端（自己的 config 目录 + 自己的端口 + 自己的令牌）。输出落文件，便于排障。 */
function startBackend() {
  const logFd = openSync(path.join(OUT, 'backend.log'), 'a')
  const child = spawn(JDK, [
    `-Djava.io.tmpdir=${path.join(CONFIG_DIR, 'tmp')}`,
    '-jar', JAR,
    `--server.port=${BACKEND_PORT}`,
    `--ironoath.ops.token=${OPS_TOKEN}`,
    `--ironoath.config-dir=${CONFIG_DIR}`,
  ], { stdio: ['ignore', logFd, logFd], windowsHide: true })
  return child
}

/**
 * 探活：轮询运维读数端点（要令牌），拿到 200 就是就绪。
 *
 * <p>为什么用 `GET /ops/pay/debt` 而**不是** `/ops/app/version`：**app/version 是 POST**
 * （`OpsController.java:318`）⇒ 用 GET 探活会拿到 405，探针就会一直等到超时然后
 * 「看起来是后端没起来」，而真实原因是探活用错了方法 —— 这一条自己踩过。
 */
async function waitBackend(timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${BACKEND_PORT}/ops/pay/debt?limit=1`, {
        headers: { 'X-Ops-Token': OPS_TOKEN },
      })
      if (res.ok) {
        return true
      }
    } catch {
      // 后端还没起来 ⇒ 继续等
    }
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  return false
}

/** 页面里读：设置页/公告模式的节点与文字。 */
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

/** 按节点名点一下（`emit` 与真人按下走同一个回调）。 */
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

// ---------- 准备夹具 config 目录 ----------
rmSync(CONFIG_DIR, { recursive: true, force: true })
mkdirSync(CONFIG_DIR, { recursive: true })
cpSync('contract/config', CONFIG_DIR, { recursive: true })
mkdirSync(path.join(CONFIG_DIR, 'tmp'), { recursive: true })
writeNoticeTable(LIVE_BODY_V1)

const backend = startBackend()
let preview = null
let browser = null
// ⚠️ `page` 必须声明在 try 之外：`clickByName` 是模块顶层的箭头函数，
// 它闭包里引用的 `page` 必须是同一个绑定 ⇒ 声明在 try 块里的话，
// 顶层函数看不到它（ReferenceError: page is not defined）—— 这一条自己踩过。
let page = null
try {
  console.log(`[verify-notice-hotupdate] 等后端就绪（端口 ${BACKEND_PORT}，config 目录 ${CONFIG_DIR}）…`)
  if (!await waitBackend(90_000)) {
    console.error('[verify-notice-hotupdate] 后端没就绪（90 秒）—— 本份探针不成立，先查 jar 与端口')
    process.exit(2)
  }
  console.log('[verify-notice-hotupdate] 后端就绪')

  const backendOrigin = `http://127.0.0.1:${BACKEND_PORT}`
  preview = await startPreviewServer({ root: ARTIFACT, backend: backendOrigin, port: PORT })
  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `notice-hot-${Date.now()}`)
  page = await context.newPage()
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

  // ① 点「公告」⇒ 屏上出现窗口内那行
  const clicked = await clickByName('row-notice', 'touch-end')
  await page.waitForTimeout(1200)
  const first = await page.evaluate(readNotice)
  check('点得动「公告」那一行', clicked, true)
  check('切到公告模式了', first.rowBack, true)
  const firstText = (first.texts ?? []).join('｜')
  console.log(`  第一次的屏上文字：${firstText}`)
  check('窗口内那行在屏上', firstText.includes(LIVE_TITLE), true)
  check('第一版文案在屏上', firstText.includes(LIVE_BODY_V1), true)
  check('窗口外那行不在屏上（过期的不许发给玩家）', firstText.includes(EXPIRED_TITLE), false)
  await page.screenshot({ path: path.join(OUT, 'before-hotupdate.png') })

  // ② 改夹具文案 + reload（服务端从磁盘重读）⇒ 这就是热更
  writeNoticeTable(LIVE_BODY_V2)
  const reload = await fetch(`http://127.0.0.1:${BACKEND_PORT}/ops/config/reload?actor=notice-probe`, {
    method: 'POST',
    headers: { 'X-Ops-Token': OPS_TOKEN },
  })
  const reloadBody = await reload.text()
  check('reload 成功（运维令牌过）', reload.ok, true)
  console.log(`  reload 响应：${reloadBody.slice(0, 160)}`)

  // ③ 客户端再点一次 ⇒ 屏上是新文案
  const back = await clickByName('row-notice-back', 'touch-end')
  await page.waitForTimeout(700)
  const reopened = await clickByName('row-notice', 'touch-end')
  await page.waitForTimeout(1200)
  const second = await page.evaluate(readNotice)
  const secondText = (second.texts ?? []).join('｜')
  console.log(`  第二次的屏上文字：${secondText}`)
  check('回到设置页后还能再点开（收得掉）', back, true)
  check('再点得开', reopened, true)
  check('屏上是**新**文案（热更生效，不用发版）', secondText.includes(LIVE_BODY_V2), true)
  check('旧文案不在屏上了', secondText.includes(LIVE_BODY_V1), false)
  await page.screenshot({ path: path.join(OUT, 'after-hotupdate.png') })

  check('页面零错误', errors.join(' | ') || '无', '无')
} finally {
  if (browser) {
    await browser.close()
  }
  if (preview) {
    await preview.close()
  }
  backend.kill()
  rmSync(CONFIG_DIR, { recursive: true, force: true })
}

console.log(`  截图：${path.join(OUT, 'before-hotupdate.png')} 与 ${path.join(OUT, 'after-hotupdate.png')}`)
console.log(`\n=== 公告热更运行时验收：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)
