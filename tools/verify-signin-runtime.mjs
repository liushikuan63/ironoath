/** 签到真实HTTP/构建页面验收。独立dev新号，不操作原生验收账号；弱网相明确植入丢失成功回执。 */
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideBoard } from './lib/guide-overlay.mjs'
import { clickNodeViaCocos } from './lib/cocos-click.mjs'

const BACKEND = process.env.SIGNIN_BACKEND
if (!BACKEND) { console.error('缺 SIGNIN_BACKEND，禁止静默连接其它后端'); process.exit(2) }
const PORT = Number(process.env.SIGNIN_PORT ?? 8351)
const OUT = process.env.SIGNIN_OUT ?? 'tmp/signin-runtime'
assert.notEqual(PORT, Number(new URL(BACKEND).port))
console.log(`[signin] 后端 ${BACKEND} · 预览端口 ${PORT}`)
fs.mkdirSync(OUT, { recursive: true })
const run = `signin-${Date.now()}`
const request = async (path, playerId, body) => (await fetch(`${BACKEND}${path}`, {
  method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(playerId ? { 'X-Player-Id': playerId } : {}) },
  ...(body ? { body: JSON.stringify(body) } : {}),
})).json()
const init = async suffix => {
  const device = `${run}-${suffix}`
  const result = await request('/player/init', null, { deviceId: device, requestId: `${device}-init`, nickName: '签到验收', wxCode: '', clientTime: Date.now() })
  assert.equal(result.code, 0)
  return { device, uid: result.data.playerId }
}
const list = async uid => {
  const result = await request('/activity/list', uid)
  assert.equal(result.code, 0)
  return result.data
}
const gold = async uid => {
  const result = await request('/city/list', uid)
  assert.equal(result.code, 0)
  return result.data.resources.GOLD.current
}
const readings = []
const check = (name, condition) => { assert.ok(condition, name); readings.push(name) }
const browser = await chromium.launch({ headless: true })
const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
const errors = []
const clicks = []
const click = async (page, uuid) => {
  const result = await clickNodeViaCocos(page, { uuid, within: 'quest' })
  assert.equal(result.clicked, true, `引擎坐标自检失败：${result.reason}`)
  clicks.push({ node: result.node, verified: result.verified, camera: result.camera, dpr: result.dpr })
}

// 可见组件和点击坐标均来自真实场景；通过Playwright鼠标点击画布，未发射touch-start事件。
function screen() {
  const cc = window.cc, scene = cc.director.getScene()
  if (!scene) return null
  let panel
  const find = n => { if (n.name === 'quest' && n.activeInHierarchy) panel = n; n.children.forEach(find) }
  find(scene)
  if (!panel) return null
  const texts = [], buttons = {}, rows = []
  const walk = n => {
    if (!n.activeInHierarchy) return
    const label = n.getComponent('cc.Label'); if (label?.string) texts.push(label.string)
    if (['Tab-signin', 'Tab-activity', 'NextPageButton'].includes(n.name)) buttons[n.name] = n.uuid
    if (n.name === 'ActivityRow') {
      const title = n.getChildByName('Title')?.getComponent('cc.Label')?.string
      const detail = n.getChildByName('Detail')?.getComponent('cc.Label')?.string
      const state = n.getChildByName('Status')?.getComponent('cc.Label')?.string
      const claim = n.getChildByName('ClaimButton')
      rows.push({ title, detail, state, claim: claim?.activeInHierarchy ? claim.uuid : null })
    }
    n.children.forEach(walk)
  }
  walk(panel)
  return { texts, buttons, rows }
}

try {
  // 项目网页入口已有900px宽度保护层；390px竖屏会被该层拦截，不能当作签到按钮故障。
  for (const [index, viewport] of [{ width: 1440, height: 900 }, { width: 960, height: 540 }].entries()) {
    const account = await init(`ui-${index}`), before = await gold(account.uid), initial = await list(account.uid)
    const signs = initial.activities.filter(r => r.category === 'SIGN_IN')
    check(`视口${index}：五项签到配置下发，每日入口20金币`, signs.length === 5 && signs[0].rewards[0].count === 20)
    check(`视口${index}：读列表不自动发金币`, await gold(account.uid) === before)
    const context = await browser.newContext({ viewport })
    await context.addInitScript(device => localStorage.setItem('ironoath.deviceId', device), account.device)
    const page = await context.newPage()
    page.on('pageerror', e => errors.push(e.message))
    if (index === 1) await context.route('**/activity/claim', async route => {
      const response = await route.fetch()
      const result = await response.json()
      assert.equal(result.code, 0, '丢失回执相必须先确认真实后端已接受')
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 1002, msg: '领取回执中断，请刷新查看', detail: null, data: null, serverNow: Date.now() }) })
    })
    await page.goto(`${preview.origin}/?panel=quest`, { waitUntil: 'networkidle' })
    await page.waitForFunction(() => window.cc?.director?.getScene(), null, { timeout: 60000 })
    await page.evaluate(hideGuideBoard)
    await page.waitForFunction(() => window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('quest')?.activeInHierarchy)
    let state = await page.evaluate(screen)
    assert.ok(state?.buttons['Tab-signin'])
    fs.writeFileSync(`${OUT}/signin-${index}-tab-geometry.json`, JSON.stringify(state, null, 2))
    await page.screenshot({ path: `${OUT}/signin-${index}-initial.png` })
    await click(page, state.buttons['Tab-signin'])
    await page.waitForFunction(fn => { const s = (0, eval)(`(${fn})`)(); return s?.rows.some(r => r.claim) }, screen.toString())
    state = await page.evaluate(screen)
    const daily = state.rows.find(r => r.claim)
    check(`视口${index}：真实签到行显示配置金额与04:00`, daily.title.includes('20') && daily.detail.includes('04:00'))
    await page.screenshot({ path: `${OUT}/signin-${index}-before.png` })
    // 截图等待期间异步绘图可能重用行池：按同一服务端标题重取当前可点键，
    // 不沿用已变为灰行的旧uuid；此步只重新读节点，没有再次领取。
    const currentDaily = (await page.evaluate(screen)).rows.find(r => r.title === daily.title && r.claim)
    assert.ok(currentDaily?.claim, '截图后当前签到行仍须可领取')
    await click(page, currentDaily.claim)
    await page.waitForFunction(fn => { const s = (0, eval)(`(${fn})`)(); return s?.rows.length > 0 && s.rows.every(r => !r.claim) }, screen.toString())
    check(`视口${index}：点击后金币只增加20`, await gold(account.uid) === before + 20)
    check(`视口${index}：领取后权威列表无签到可领红点`, (await list(account.uid)).activities.filter(r => r.category === 'SIGN_IN').every(r => r.state !== 'CLAIMABLE'))
    await page.screenshot({ path: `${OUT}/signin-${index}-after.png` })
    await page.reload({ waitUntil: 'networkidle' })
    await page.waitForFunction(() => window.cc?.director?.getScene()?.getChildByName('Canvas')?.getChildByName('Game')?.getChildByName('quest')?.activeInHierarchy, null, { timeout: 60000 })
    await page.evaluate(hideGuideBoard)
    state = await page.evaluate(screen)
    await click(page, state.buttons['Tab-signin'])
    await page.waitForTimeout(400)
    check(`视口${index}：重新登录/刷新不再发钱`, await gold(account.uid) === before + 20)
    const names = new Set()
    for (let i = 0; i < 8; i++) {
      state = await page.evaluate(screen)
      state.rows.forEach(r => signs.forEach(s => { if (r.title?.includes(s.name)) names.add(s.name) }))
      const next = state.buttons.NextPageButton
      if (!next || names.size === signs.length) break
      await click(page, next)
      await page.waitForTimeout(150)
    }
    check(`视口${index}：翻页可见全部五项签到`, names.size === signs.length)
    check(`视口${index}：可见文字不包含内部签到标识`, !state.texts.some(t => /signin_|SIGN_IN/.test(t)))
    await context.close()
  }
  const accounts = await Promise.all([init('http-a'), init('http-b')])
  const balances = await Promise.all(accounts.map(a => gold(a.uid)))
  for (const [i, a] of accounts.entries()) {
    const reply = await request('/activity/claim', a.uid, { activityId: 'signin_daily', requestId: `${run}-shared` })
    check(`账号${i}：同请求ID在不同账号都可正常接受`, reply.code === 0)
    check(`账号${i}：共享请求ID后各自仅入账20`, await gold(a.uid) === balances[i] + 20)
  }
  const concurrent = await Promise.all([init('concurrent-a'), init('concurrent-b')])
  for (const [i, a] of concurrent.entries()) {
    const before = await gold(a.uid)
    const replies = await Promise.all(Array.from({ length: 8 }, (_, n) => request('/activity/claim', a.uid, { activityId: 'signin_daily', requestId: `${run}-${i}-${n}` })))
    check(`账号${i}：八次并发只接受一次`, replies.filter(r => r.code === 0).length === 1)
    check(`账号${i}：并发后仅入账20`, await gold(a.uid) === before + 20)
  }
  check('两个账号共享同请求ID仍各领一次，账号之间不串幂等键', (await gold(accounts[0].uid)) === balances[0] + 20 && (await gold(accounts[1].uid)) === balances[1] + 20)
  check('页面无未捕获异常', errors.length === 0)
  fs.writeFileSync(`${OUT}/readings.actual.json`, JSON.stringify({ backend: BACKEND, readings, errors, clicks, viewports: ['1440x900', '960x540'], skipped: 0, realDaysElapsed: 0, portrait: '390px web viewport is blocked by the existing 900px width guard; native phone not verified', boundaryTests: 'JUnit fixture dates, not actual elapsed weeks', responseLoss: 'real backend accepts, browser success response replaced with failure fixture' }, null, 2))
  console.log(`[signin] PASS ${readings.length} · SKIP 0 · 原生实机/真实跨周未验证`)
} finally {
  await browser.close()
  await preview.close()
}
