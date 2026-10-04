#!/usr/bin/env node
/**
 * 职责：把「聊天历史真的有入口」钉成能失败的判据（V15 真分页；B22 §一 1 的历史那半）。
 * 依赖：node、playwright、**已启动的 dev 服务端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 *   BACKEND_ORIGIN=http://localhost:8080 node tools/verify-chat-paging.mjs
 * 可选：CHAT_PAGING_PORT（默认 8235）、CHAT_PAGING_OUT（默认 tmp/chat-paging）
 *
 * <p><b>为什么单独一份而不是塞进 verify-chat-runtime.mjs</b>：那一份是"收发与推送"的端到端，
 * 已经 544 行；这一份盯的是**历史入口**，两者会各自演进。同族先例：国库那两格也分了
 * `verify-nation.mjs` / `verify-nation-s2.mjs` / `verify-nation-live.mjs`。
 *
 * <p><b>两场，因为游标分支在真后端上测不起</b>：
 * <ol>
 *   <li><b>真后端</b>：造 12 条世界消息 ⇒ 打开聊天 ⇒ 核对"第 0 页是最新那 5 条"
 *       （V15 之前只画最新 5 条、更旧的**永远拿不到**）、翻页键的方向与灰态、翻页时**不额外发请求**。</li>
 *   <li><b>路由夹具</b>：真后端上客户端首次就拉 200 条（`CHAT_LOCAL_HISTORY_MAX`），
 *       要撞到游标得先有 200+ 条历史、再点 40 次「更早」—— 那太贵。
 *       夹具让首次只回 8 条且 `hasMore: true`，于是翻到最旧页只需一次点击，
 *       而**"第二次请求真的带了 `beforeMessageId`"**这件事才验得到。</li>
 * </ol>
 */
import { mkdirSync, existsSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { makeStubRead } from './lib/route-stub.mjs'

const OUT = process.env.CHAT_PAGING_OUT ?? path.resolve(process.cwd(), 'tmp/chat-paging')
mkdirSync(OUT, { recursive: true })
const PORT = Number(process.env.CHAT_PAGING_PORT ?? 8235)
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-chat-paging] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端')
  process.exit(2)
})()
const ROOT = path.resolve(process.env.CHAT_PAGING_ROOT ?? 'client/build/web-mobile')

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
const checkThat = (msg, actual) => {
  if (actual) {
    ok(msg)
  } else {
    bad(msg)
  }
}

const RUN = `${Date.now() % 1000000}`

async function postJson(url, body, playerId) {
  const headers = { 'Content-Type': 'application/json' }
  if (playerId !== undefined) headers['X-Player-Id'] = playerId
  const response = await fetch(`${BACKEND}${url}`, { method: 'POST', headers, body: JSON.stringify(body) })
  return response.json()
}

/** 页面里装一组小助手（与服务端既有的聊天探针同一套写法，避免第二份实现分叉）。 */
function installHelpers() {
  const findNode = (name) => {
    const scene = window.cc.director.getScene()
    const stack = [...scene.children]
    while (stack.length > 0) {
      const node = stack.pop()
      if (node.name === name) {
        return node
      }
      for (const child of node.children) stack.push(child)
    }
    return null
  }
  const textsUnder = (name) => {
    const out = []
    const walk = (node, inside) => {
      const live = inside && node.activeInHierarchy !== false
      const label = live && node.getComponent ? node.getComponent('cc.Label') : null
      if (label !== null && label !== undefined && label.string !== '') out.push(label.string)
      for (const child of node.children) walk(child, live)
    }
    const root = findNode(name)
    if (root !== null) walk(root, true)
    return out
  }
  /** 所有 SocialRow 的文字（按屏上顺序）。翻页行的两颗键也在里面。 */
  const rows = () => {
    const scene = window.cc.director.getScene()
    const found = []
    const stack = [...scene.children]
    while (stack.length > 0) {
      const node = stack.pop()
      if (node.name === 'SocialRow' && node.activeInHierarchy !== false) found.push(node)
      for (const child of node.children) stack.push(child)
    }
    return found.map((row) => {
      const texts = []
      const buttons = []
      const walk = (node, inside) => {
        const live = inside && node.activeInHierarchy !== false
        const label = live && node.getComponent ? node.getComponent('cc.Label') : null
        if (label !== null && label !== undefined && label.string !== '') texts.push(label.string)
        for (const child of node.children) walk(child, live)
      }
      walk(row, true)
      // 行内第 4/5/6 个子节点是动作键（见 SocialPanelView.createRow）
      for (const index of [3, 4, 5]) {
        const button = row.children[index]
        if (button === undefined || button === null || button.activeInHierarchy !== true) continue
        // 「亮不亮」的判据是它有没有挂 touch-start：灰键按设计不吃触摸
        buttons.push(button.hasEventListener('touch-start') === true)
      }
      return { texts, buttons }
    })
  }
  window.__chatp = {
    findNode,
    textsUnder,
    rows,
    /** 点翻页行上的第 n 颗键（0 = 左键「更早」，1 = 右键「更新」）。 */
    tapPager: (index) => {
      const scene = window.cc.director.getScene()
      const stack = [...scene.children]
      while (stack.length > 0) {
        const node = stack.pop()
        if (node.name === 'SocialRow' && node.activeInHierarchy !== false) {
          const texts = []
          const walk = (n, inside) => {
            const live = inside && n.activeInHierarchy !== false
            const label = live && n.getComponent ? n.getComponent('cc.Label') : null
            if (label !== null && label !== undefined && label.string !== '') texts.push(label.string)
            for (const child of n.children) walk(child, live)
          }
          walk(node, true)
          // 翻页行的标记：文案里有「更早」（消息行不会有这个词）
          if (texts.some((text) => text.includes('更早'))) {
            // 行内第 4 个子节点是第一颗键、第 5 个是第二颗（见 SocialPanelView.createRow / renderRow）
            const target = node.children[3 + index]
            if (target === undefined || target === null || target.activeInHierarchy !== true) {
              return 'no-button:' + index
            }
            // 灰键按设计**不吃触摸** ⇒ 点不动本身就是"它是灰的"的判据
            if (target.hasEventListener('touch-start') !== true) return 'disabled:' + index
            target.emit('touch-start')
            return 'ok'
          }
        }
        for (const child of node.children) stack.push(child)
      }
      return 'no-pager'
    },
  }
}

/** 打开聊天页签并切到世界频道。 */
async function openWorldChat(page) {
  await page.evaluate(() => {
    const node = window.__chatp.findNode('Tab_chat')
    if (node !== null) node.emit('touch-start')
  })
  await page.waitForTimeout(1200)
  await page.evaluate(() => {
    const node = window.__chatp.findNode('Channel_WORLD')
    if (node !== null) node.emit('touch-start')
  })
  await page.waitForTimeout(1800)
}

const browser = await chromium.launch({ headless: true })

// ═══════════════ 第 1 场：真后端 ═══════════════
{
  if (!existsSync(path.join(ROOT, 'index.html'))) {
    console.error(`没有 web 产物：${ROOT}/index.html 不在。先跑一次 web-mobile 构建。`)
    process.exit(2)
  }
  const deviceId = `chat-paging-${RUN}`
  const warm = await postJson('/player/init', {
    requestId: `chat-paging-init-${RUN}`, deviceId, nickName: '分页探针', clientTime: Date.now(),
  })
  if (warm.code !== 0) {
    console.error(`建号失败：${warm.code} ${warm.msg}`)
    process.exit(2)
  }
  const playerId = warm.data.playerId
  console.log(`  探针号：${playerId}`)

  // 造 12 条世界消息（唯一标签，便于断言"第 1 页只该有最后那几条"）
  const contents = Array.from({ length: 12 }, (_v, i) => `分页${RUN}-${String(i).padStart(2, '0')}`)
  for (const content of contents) {
    const sent = await postJson('/chat/send', { requestId: `chat-paging-send-${RUN}-${content}`, channel: 'WORLD', content }, playerId)
    if (sent.code !== 0) {
      console.error(`造消息失败：${sent.code} ${sent.msg} ${sent.detail ?? ''}`)
      process.exit(2)
    }
  }
  console.log(`  已造 ${contents.length} 条世界消息`)

  const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  // **注入同一个 deviceId**：真后端按 deviceId 建档，注入错的话面板打开的是别人的号
  await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), deviceId)
  const page = await context.newPage()
  const errors = []
  const chatListCalls = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()) })
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/chat/list')) {
      chatListCalls.push(request.postData() ?? '')
    }
  })
  try {
    await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
    preview.assertRewritten()
    await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
      null, { timeout: 60_000 })
    await page.waitForTimeout(3500)
    await hideGuideOverlay(page)
    await page.waitForTimeout(400)
    await page.evaluate(installHelpers)
    await openWorldChat(page)

    const first = await page.evaluate(() => window.__chatp.rows())
    const firstText = first.flatMap((row) => row.texts).join(' | ')
    console.log(`  第 1 页：${firstText.slice(0, 260)}`)
    const seededOnFirst = contents.filter((content) => firstText.includes(content))
    // **注意 rows() 的遍历顺序不是屏上顺序**（树遍历用的是栈），所以下面一律按**集合**判，不按先后。
    // 每页 4 条而不是 5：装不下时为翻页行让出一格（与其它页签共用 contentPerPage）
    check('打开聊天落在最新那一页（只画最后 4 条）', seededOnFirst.length, 4)
    checkThat('最后一条（最新的）在屏上', firstText.includes(contents[11]))
    checkThat('最早那条**不在**第 1 页上（它在很后面那几页）', !firstText.includes(contents[0]))
    checkThat('有翻页行且写明了页码', /第 1\/\d+ 页（第 1 页最新）/.test(firstText))
    checkThat('翻页行把「每页几条」也报出来了（让位会改掉这个数）', /每页 4 条/.test(firstText))
    checkThat('翻页行给的是「更早」与「更新」', firstText.includes('更早') && firstText.includes('更新'))

    // 翻页行的两颗键：第 0 页时「更新」必须灰（灰 = 不吃触摸）
    const pagerRow = first.find((row) => row.texts.some((text) => text.includes('更早'))) ?? null
    checkThat('找得到翻页行', pagerRow !== null)
    check('最新页上「更早」是亮的', pagerRow?.buttons[0], true)
    check('最新页上「更新」是灰的（没有更新的了）', pagerRow?.buttons[1], false)
    await page.screenshot({ path: path.join(OUT, '01-newest-page.png') })

    const callsBefore = chatListCalls.length
    const tapOlder = await page.evaluate(() => window.__chatp.tapPager(0))
    await page.waitForTimeout(1200)
    check('点得动「更早」', tapOlder, 'ok')
    const second = await page.evaluate(() => window.__chatp.rows())
    const secondText = second.flatMap((row) => row.texts).join(' | ')
    console.log(`  第 2 页：${secondText.slice(0, 260)}`)
    checkThat('第 2 页写出了页码', /第 2\/\d+ 页（第 1 页最新）/.test(secondText))
    // 第 1 页是 contents[8..11]，第 2 页是 contents[4..7]（每页 4 条）
    checkThat('第 2 页上是更旧的那几条', secondText.includes(contents[7]) && secondText.includes(contents[4]))
    checkThat('第 2 页上看不到最新的那条', !secondText.includes(contents[11]))
    checkThat('第 2 页上看不到第 1 页的内容', !secondText.includes(contents[8]))
    check('本地窗口内翻页**不发请求**（那一枪是多余的往返）', chatListCalls.length, callsBefore)
    await page.screenshot({ path: path.join(OUT, '02-older-page.png') })

    const tapNewer = await page.evaluate(() => window.__chatp.tapPager(1))
    await page.waitForTimeout(1200)
    check('点得动「更新」', tapNewer, 'ok')
    const back = await page.evaluate(() => window.__chatp.rows())
    const backText = back.flatMap((row) => row.texts).join(' | ')
    checkThat('回到最新页：最新那条又在屏上', backText.includes(contents[11]))
    checkThat('回到最新页：页码回到第 1 页', /第 1\/\d+ 页/.test(backText))
    check('来回翻也不发请求', chatListCalls.length, callsBefore)
    checkThat('屏上不出现内部 id（消息 id 不是内容）', !/\bm\d{3,}/.test(backText))
    // 输入框的占位串必须是中文那一条，**不是引擎默认的 `label`**。
    // 判据要查**整个面板**的标签（`textsUnder`），不能查 `rows()` —— 后者只看 SocialRow，
    // 根本看不到输入框（第一版就是查错了作用域：断言必红，而屏上其实是对的）。
    const allLabels = await page.evaluate(() => window.__chatp.textsUnder('social'))
    checkThat('输入框占位文案是「说点什么…」', allLabels.some((text) => text.includes('说点什么…')))
    check('面板上没有任何标签是引擎默认串 label', allLabels.includes('label'), false)
    check('真后端场：零页面错误', errors.join(' | ') || '无', '无')
  } finally {
    await page.close()
    await context.close()
    await preview.close()
  }
}

// ═══════════════ 第 2 场：游标分支（路由夹具） ═══════════════
{
  const NOW = 1_800_000_000_000
  const older = Array.from({ length: 5 }, (_v, i) => ({
    messageId: `old${i}`, channel: 'WORLD', senderId: 'other', senderName: '旧人',
    content: `更旧的一段-${i}`, sentAt: NOW - 600_000 + i * 1000,
  }))
  const newest = Array.from({ length: 8 }, (_v, i) => ({
    messageId: `new${i}`, channel: 'WORLD', senderId: 'other', senderName: '新人',
    content: `最新的一段-${i}`, sentAt: NOW - 8000 + i * 1000,
  }))
  const cursorCalls = []

  const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT + 1 })
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), `chat-paging-stub-${RUN}`)
  const stub = makeStubRead(context)
  // 首次：8 条 + hasMore=true；带游标：5 条更旧的 + hasMore=false
  context.route('**/chat/list', async (route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') {
      await route.fulfill({
        status: 204,
        headers: {
          'access-control-allow-origin': request.headers()['origin'] ?? '*',
          'access-control-allow-headers': '*',
          'access-control-allow-methods': 'GET,POST,OPTIONS',
        },
      })
      return
    }
    let body = {}
    try { body = JSON.parse(request.postData() ?? '{}') } catch { body = {} }
    cursorCalls.push(body)
    const payload = body.beforeMessageId === null || body.beforeMessageId === undefined
      ? { messages: newest, hasMore: true, serverNow: NOW }
      : { messages: older, hasMore: false, serverNow: NOW }
    await route.fulfill({
      status: 200,
      headers: {
        'access-control-allow-origin': request.headers()['origin'] ?? '*',
        'access-control-allow-headers': '*',
        'content-type': 'application/json',
      },
      body: JSON.stringify({ code: 0, msg: '成功', data: payload, serverNow: NOW }),
    })
  })
  stub('**/social/summary', () => ({
    squad: null, alliance: null, nationId: null, pendingInvites: 0, pendingHelps: 0,
    helpRemainingToday: 5, events: [], serverNow: NOW,
  }))
  stub('**/social/permissions*', () => ({ permissions: [], serverNow: NOW }))
  stub('**/social/helpRequests*', () => ({ requests: [], serverNow: NOW }))
  stub('**/player/init', () => ({
    playerId: 'stub-me', authToken: 'stub-token', serverNow: NOW,
    profile: { playerId: 'stub-me', nickName: '夹具', avatarId: 1, createdAt: NOW, lastLoginAt: NOW },
    cityLevel: 1,
    resources: {
      WOOD: { current: 5000, cap: 20000, protectedAmount: 4000, perHour: 200, lastSettle: NOW },
      STONE: { current: 5000, cap: 20000, protectedAmount: 4000, perHour: 200, lastSettle: NOW },
      IRON: { current: 2000, cap: 10000, protectedAmount: 2000, perHour: 100, lastSettle: NOW },
      GRAIN: { current: 8000, cap: 30000, protectedAmount: 6000, perHour: 400, lastSettle: NOW },
      GOLD: { current: 200, cap: 1000000, protectedAmount: 0, perHour: 0, lastSettle: NOW },
      STAMINA: { current: 100, cap: 100, protectedAmount: 0, perHour: 10, lastSettle: NOW },
    },
    power: { displayPower: 100, matchPower: 100, peakPower: 100 },
    protectUntil: NOW + 86_400_000, offlineReport: null,
  }))
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  try {
    await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
    await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
      null, { timeout: 60_000 })
    await page.waitForTimeout(3500)
    await hideGuideOverlay(page)
    await page.waitForTimeout(400)
    await page.evaluate(installHelpers)
    await openWorldChat(page)

    const first = await page.evaluate(() => window.__chatp.rows())
    const firstText = first.flatMap((row) => row.texts).join(' | ')
    console.log(`  夹具第 1 页：${firstText.slice(0, 200)}`)
    checkThat('夹具首屏是最新那 8 条里的最后一页', firstText.includes('最新的一段-7'))
    // **进聊天页签本身就会拉一次**（`onChatEnter` → `openChat`），切到世界频道再拉一次 ——
    // 所以这里不能断言"恰好 1 次"，只能断言"没有游标"（游标只该出现在往前翻到底时）
    check('首屏那几次请求都没有游标（游标只在往前翻到底时出现）',
      cursorCalls.every((body) => (body.beforeMessageId ?? null) === null), true)
    const afterOpen = cursorCalls.length
    checkThat('首屏至少拉了一次（拿到了 history）', afterOpen >= 1)

    // 8 条 / 每页 5 ⇒ 2 页；翻到第 2 页（本地最旧页）后 hasMore=true ⇒ 「更早」仍亮
    const toOlder = await page.evaluate(() => window.__chatp.tapPager(0))
    check('点得动「更早」（到本地最旧那一页）', toOlder, 'ok')
    await page.waitForTimeout(1200)
    const second = await page.evaluate(() => window.__chatp.rows())
    const secondText = second.flatMap((row) => row.texts).join(' | ')
    const pager = second.find((row) => row.texts.some((text) => text.includes('更早'))) ?? null
    checkThat('最旧那一页的「更早」仍然亮着（hasMore=true）', pager?.buttons[0] === true)

    const callsBefore = cursorCalls.length
    const beyond = await page.evaluate(() => window.__chatp.tapPager(0))
    await page.waitForTimeout(1500)
    check('点得动「更早」（越过本地窗口）', beyond, 'ok')
    check('越过本地窗口后**真的发了**一次 /chat/list', cursorCalls.length, callsBefore + 1)
    const cursor = cursorCalls[cursorCalls.length - 1] ?? {}
    check('那一次带的游标 = 手里最旧那条的 messageId', cursor.beforeMessageId, 'new0')
    check('那一次要的条数 = CHAT_FETCH_OLDER（不是首次那个 200）', cursor.limit, 20)
    const third = await page.evaluate(() => window.__chatp.rows())
    const thirdText = third.flatMap((row) => row.texts).join(' | ')
    console.log(`  夹具补到的那一段：${thirdText.slice(0, 200)}`)
    // 补进来 5 条后共 13 条 ⇒ 4 页（每页 4 条），落点是第 3 页 = items[1..4] = 更旧的一段-1..-4
    checkThat('补到的更旧那一段出现在屏上', thirdText.includes('更旧的一段-4') && thirdText.includes('更旧的一段-1'))
    checkThat('页码继续往更早走（不是停在原地）', /第 3\/4 页/.test(thirdText))
    const midPager = third.find((row) => row.texts.some((text) => text.includes('更早'))) ?? null
    checkThat('本地还有更旧的页 ⇒「更早」仍然亮（不是一补到就灰）', midPager?.buttons[0] === true)
    await page.screenshot({ path: path.join(OUT, '03-cursor-append.png') })

    // 再往更早翻一页到**本地最旧那一页**：这时 hasMore=false ⇒ 才该转灰
    const last = await page.evaluate(() => window.__chatp.tapPager(0))
    await page.waitForTimeout(1200)
    check('点得动「更早」（到本地最旧那一页）', last, 'ok')
    const lastRows = await page.evaluate(() => window.__chatp.rows())
    const lastText = lastRows.flatMap((row) => row.texts).join(' | ')
    checkThat('最旧那一页上是那一段里最旧的', lastText.includes('更旧的一段-0'))
    const lastPager = lastRows.find((row) => row.texts.some((text) => text.includes('更早'))) ?? null
    checkThat('服务端与本地都到头了 ⇒「更早」转灰', lastPager?.buttons[0] === false)
    checkThat('最旧那一页上「更新」还亮着（能往回走）', lastPager?.buttons[1] === true)
    await page.screenshot({ path: path.join(OUT, '04-oldest-page.png') })
    check('夹具场：零页面错误', errors.join(' | ') || '无', '无')
    // 全场**只有一次**带游标的请求（== 只发了一次"补更旧"那一枪）
    check('全场只有一次带游标的请求', cursorCalls.filter((body) => (body.beforeMessageId ?? null) !== null).length, 1)
  } finally {
    await page.close()
    await context.close()
    await preview.close()
  }
}

await browser.close()
console.log(`  截图：${OUT}`)
console.log(`\n=== 聊天历史分页运行时验收：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)
