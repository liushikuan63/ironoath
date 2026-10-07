/**
 * 职责：V19「未成年付费提示上屏」与「立即购买/× 死按钮」修复的**运行时**验收（真产物 + 真点击 + 真截图）。
 * 用法：`BACKEND_ORIGIN=http://localhost:8355 MN_PROBE_PORT=8366 node tools/verify-minor-notice.mjs`
 *      ⚠️ 变量名是这一份独有的（`MN_PROBE_PORT`）；后端变量必须显式传，静默回退 8080 会打到别人的活后端。
 *
 * <p><b>为什么要桩两读一写</b>：弹窗该不该弹由服务端频控决定（`/gift/popup`），dev 上造"这一刻正好要弹"
 * 要真触发一次建造完成；下单 `POST /pay/order` 在 web 环境里拿不到真渠道（`invokePayment` 回 unsupported）。
 * 这两样都不是本格的判据，所以桩掉；**本格要验的是视图有没有把玩家的点击交出去、
 * 以及服务端那一列 `minorNotice` 有没有真的长到屏上** —— 那两条只能靠真点击 + 真产物证。
 *
 * <p><b>四相</b>：
 *  A 哨兵提示 ⇒ 点「立即购买」⇒ 屏上出现逐字相同的串 + POST 恰好 1 次（哨兵本身就是植入取证：
 *    视图少拼一行、或把提示写死成别的话，这一相必红）
 *  B 对照组 ⇒ `minorNotice: null` ⇒ 屏上不许出现「额度」二字（客户端不许自造文案）
 *  C 负向 ⇒ 屏上不出现 `gift_stuck_supply` 这类内部编号（#255/#268 那一族）
 *  D 关闭 ⇒ 点「×」⇒ 面板自己收起（`onClose` 原先也只是个声明着没人调的死字段）
 */
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = 'client/build/web-mobile'
const BACKEND = process.env.BACKEND_ORIGIN ?? ''
const PORT = Number(process.env.MN_PROBE_PORT ?? 8366)
const SHOT_DIR = 'tmp/minor-notice'
const NOTICE = 'PROBE-哨兵：本月未成年消费额度还剩 30 元。'
const PRODUCT_ID = 'gift_stuck_supply'

if (!BACKEND) {
  console.error('[minor-notice][前置] 没有传 BACKEND_ORIGIN —— 不猜端口，退了。')
  process.exit(2)
}
if (!existsSync(path.resolve(process.cwd(), ROOT, 'index.html'))) {
  console.error(`[minor-notice][前置] 产物不存在：${ROOT}（先跑 build-webmobile.sh）`)
  process.exit(2)
}
mkdirSync(path.resolve(process.cwd(), SHOT_DIR), { recursive: true })

/** 每一相跑一个全新的页面，桩响应的 minorNotice 由各相自己给。 */
async function runPhase(browser, preview, { notice, label }) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const deviceId = `minor-notice-${label}-${Date.now()}`
  await context.addInitScript((value) => localStorage.setItem('ironoath.deviceId', value), deviceId)

  const createOrderPosts = []
  const cors = (request) => ({
    'access-control-allow-origin': request.headers()['origin'] ?? '*',
    'access-control-allow-headers': '*',
    'access-control-allow-methods': 'GET,POST,OPTIONS',
  })
  const fulfill = async (route, data) => route.fulfill({
    status: 200,
    headers: { ...cors(route.request()), 'content-type': 'application/json' },
    body: JSON.stringify({ code: 0, msg: '成功', data, serverNow: Date.now() }),
  })

  // 桩必须挂在 goto 之前：深链进内城就发请求，晚挂等于这一相读到空态
  await context.route('**/gift/popup**', async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors(route.request()) })
      return
    }
    await fulfill(route, {
      popup: true,
      giftId: 'gift_probe_only',
      productId: PRODUCT_ID,
      productName: '探针贺礼',
      offerExpireAt: Date.now() + 30 * 60_000,
      cooldownSec: 0,
      // serverNow 是契约里的 **required**（`pay.schema.json` GiftPopupResp）：
      // 漏了它倒计时会算成 `offerExpireAt - undefined = NaN`，屏上印「剩 NaN:NaN」——
      // 第一版就是这么错的（截图目视才发现，11 条判据全绿看不见遮挡与 NaN）。
      serverNow: Date.now(),
    })
  })
  await context.route('**/pay/order**', async (route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors(request) })
      return
    }
    if (request.method() === 'POST') {
      createOrderPosts.push(request.postData() ?? '')
      await fulfill(route, {
        orderId: 'order-probe-1',
        minorNotice: notice,
        payParams: {
          mode: 'game', offerId: 'offer-probe', buyQuantity: '600',
          env: '1', currencyType: 'CNY', signature: null,
        },
      })
      return
    }
    // 查单：web 环境走不到这一支（拉起支付先回 unsupported），真走到了就当"还没发货"
    await fulfill(route, { status: 'PENDING', rewards: [], retryQueued: null })
  })

  const page = await context.newPage()
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', 'city')
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director.getScene() !== null)

  const appeared = await page.waitForFunction(() => {
    let hit = false
    const visit = (node) => {
      if (hit) return
      if (node.name === 'giftPopup' && node.activeInHierarchy === true) hit = true
      for (const child of node.children) visit(child)
    }
    visit(window.cc.director.getScene())
    return hit
  }, null, { timeout: 25_000 }).then(() => true).catch(() => false)

  // 真点「立即购买」：按节点名找壳，事件对象必须带上（处理函数读 _event.type，不传会抛并被吞掉）
  const tapped = await page.evaluate(() => {
    let target = null
    const visit = (node) => {
      if (target !== null) return
      if (node.name === 'buy' && node.activeInHierarchy === true) { target = node; return }
      for (const child of node.children) visit(child)
    }
    visit(window.cc.director.getScene())
    if (target === null) return false
    target.emit('touch-start', { type: 'touch-start', target })
    return true
  })
  await page.waitForFunction(() => {
    let text = ''
    const visit = (node) => {
      if (node.name === 'giftPopup') {
        const walk = (n) => {
          const label = n.getComponent && n.getComponent('cc.Label')
          if (label !== null && label.string !== '' && n.activeInHierarchy) text += '|' + label.string
          for (const c of n.children) walk(c)
        }
        walk(node)
      }
      for (const child of node.children) visit(child)
    }
    visit(window.cc.director.getScene())
    return text.includes('PROBE-哨兵') || text.includes('不支持支付') || text.includes('环境')
  }, null, { timeout: 8_000 }).catch(() => undefined)

  const shot = path.join(SHOT_DIR, `${label}.png`)
  await page.screenshot({ path: shot })

  const texts = await page.evaluate(() => {
    const out = []
    const visit = (node, shown) => {
      const on = shown && node.activeInHierarchy === true
      const label = node.getComponent && node.getComponent('cc.Label')
      if (on && label !== null && label.string !== '') out.push(label.string)
      for (const child of node.children) visit(child, on)
    }
    visit(window.cc.director.getScene(), true)
    return out
  })

  // D 相顺带验关闭键：同一页面里点「×」，面板必须自己收起
  const closed = texts.length > 0 ? await page.evaluate(() => {
    let target = null
    const visit = (node) => {
      if (target !== null) return
      if (node.name === 'close' && node.activeInHierarchy === true) { target = node; return }
      for (const child of node.children) visit(child)
    }
    visit(window.cc.director.getScene())
    if (target === null) return 'no-close-node'
    target.emit('touch-start', { type: 'touch-start', target })
    let popup = null
    const find = (node) => {
      if (popup !== null) return
      if (node.name === 'giftPopup') { popup = node; return }
      for (const child of node.children) find(child)
    }
    find(window.cc.director.getScene())
    return popup === null ? 'no-popup' : String(popup.activeInHierarchy)
  }) : 'skipped'
  await page.waitForTimeout(300)
  const stillActive = await page.evaluate(() => {
    let popup = null
    const find = (node) => {
      if (popup !== null) return
      if (node.name === 'giftPopup') { popup = node; return }
      for (const child of node.children) find(child)
    }
    find(window.cc.director.getScene())
    return popup === null ? null : popup.activeInHierarchy
  })

  await context.close()
  return { appeared, tapped, texts, shot, errors, posts: createOrderPosts.length, closed, stillActive }
}

const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
const browser = await chromium.launch({ headless: true })
const withNotice = await runPhase(browser, preview, { notice: NOTICE, label: 'with-notice' })
const withoutNotice = await runPhase(browser, preview, { notice: null, label: 'without-notice' })
await browser.close()
await preview.close()

const failures = []
const say = (ok, text) => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${text}`)
  if (!ok) failures.push(text)
}

// A 相：哨兵必须逐字出现在屏上（提示没拼进去 / 被写成别的话都会红）
say(withNotice.appeared, '弹窗出现（/gift/popup 桩生效且视图画得出来）')
say(withNotice.tapped, '「立即购买」按节点名找到并且触摸分发出去了')
say(withNotice.posts === 1, `点一下真发出 1 次 POST /pay/order，实际 ${withNotice.posts} 次`)
say(withNotice.texts.some((text) => text.includes(NOTICE)),
  `屏上有服务端那一列的原句，实际文本：${withNotice.texts.slice(0, 8).join(' / ')}`)
say(withNotice.closed === 'false' || withNotice.stillActive === false,
  `点「×」后面板收起（返回 ${withNotice.closed}、复查 active=${withNotice.stillActive}）`)

// B 相（对照组）：null 提示 ⇒ 一行都不许多出来
say(withoutNotice.posts === 1, `对照组也发出了 1 次请求（提示为空不影响下单），实际 ${withoutNotice.posts} 次`)
say(!withoutNotice.texts.some((text) => text.includes('额度')),
  '对照组屏上不许出现「额度」—— 成年与"年龄未知"是服务端的事，客户端不许自造文案')

// C 相（负向）：内部编号不许上屏（两相都判）
for (const [name, phase] of [['A', withNotice], ['B', withoutNotice]]) {
  const leaked = phase.texts.filter((text) => /gift_|order-probe|PROBE_ONLY|productId/.test(text)
    && !text.includes(NOTICE))
  say(leaked.length === 0, `${name} 相屏上没有内部编号${leaked.length ? `：${leaked.join('、')}` : ''}`)
  say(phase.errors.length === 0, `${name} 相零未捕获错误${phase.errors.length ? `：${phase.errors[0]}` : ''}`)
  // NaN/undefined 上屏是**截图才看得见、判据看不见**的那一族（本格第一跑就是这样抓到的：
  // 夹具漏了契约 required 的 serverNow ⇒ 倒计时印成「剩 NaN:NaN」，而 11 条判据当时全绿）
  const junk = phase.texts.filter((text) => /NaN|undefined|\[object /.test(text))
  say(junk.length === 0, `${name} 相屏上不出现 NaN/undefined/object${junk.length ? `：${junk.join('、')}` : ''}`)
}

console.log(`[minor-notice] 截图：${withNotice.shot} / ${withoutNotice.shot}`)
if (failures.length > 0) {
  console.error(`[minor-notice] 判据失败 ${failures.length} 条`)
  process.exitCode = 1
} else {
  console.log('[minor-notice] 全绿：点击交得出去、服务端那一列长到了屏上、空提示不多一行、没有裸编号')
  process.exitCode = 0
}
