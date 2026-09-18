#!/usr/bin/env node
/**
 * 职责：把「聊天页签真的能收发、私聊推送真的点亮未读」从人工项变成能失败的判据
 *       （B22 §一 1；验收 1、2、3）。
 * 依赖：node、playwright、**已启动的 dev 服务端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 *   BACKEND_ORIGIN=http://localhost:8155 node tools/verify-chat-runtime.mjs
 *
 * <p><b>为什么在浏览器里量小游戏的界面</b>：面板、频道按钮与输入框是同一份代码
 * （`scene/SocialPanelView.ts` + 运行时构造的 `EditBox`），两个平台唯一的差别是运行时。
 * 小游戏那边没有可编程点击通道，而"界面上有没有画出东西、点下去会不会真的发出去"
 * 在浏览器里量一次是同一个答案。
 *
 * <p><b>三格都是端到端，且各带一条"只在面板里成立就不算数"的对照</b>：
 * <ol>
 *   <li>未入盟看联盟频道 ⇒ 提示行给出**服务端的**拒绝理由（客户端不预判资格）</li>
 *   <li>真键盘输入 + 点发送 ⇒ 面板画出这条消息，**且后端 `/chat/list` 也读得到它**
 *       （只断言面板会假绿在"本地拼了一条"上）</li>
 *   <li>另一个玩家发私聊 ⇒ 推送到达后私聊按钮出现未读、会话列表出现该会话，
 *       打开后未读清零（这三步此前整条是死的：`serverPush` 零订阅）</li>
 * </ol>
 *
 * <p><b>输入走的是真实键盘</b>：`EditBox.setFocus()` 之后由 playwright 逐字敲进
 * 引擎自己创建的 DOM 输入框 —— 不是往组件里塞字符串，因为"输入框接线断了"正是要拦的那类缺陷。
 */
import { existsSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'

const ROOT = path.resolve('client/build/web-mobile')
const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const PORT = Number(process.env.CHAT_PORT ?? 8094)
const SHOT = process.env.CHAT_SHOT ?? 'D:/tmp/chat-panel.png'

const failures = []
const lines = []

function verdict(ok, label, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}  ${detail}`)
  if (!ok) {
    failures.push(label)
  }
}

/**
 * 在页面里装一组小助手（场景图遍历）。
 *
 * <p>为什么装到 `window` 上而不是每次 evaluate 传函数：playwright 序列化的是函数**源码**，
 * 函数之间互相调用时被调的那个不会跟着过去（`findNode is not defined` 就是踩过的样子）。
 * 装一次、后面按名字调，顺便让每条判据都能被单独重跑。
 */
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
      if (label !== null && label !== undefined && label.string !== '') {
        out.push(label.string)
      }
      for (const child of node.children) walk(child, live)
    }
    const root = findNode(name)
    if (root !== null) {
      walk(root, true)
    }
    return out
  }
  window.__chat = {
    findNode,
    textsUnder,
    tapNode: (name) => {
      const node = findNode(name)
      if (node === null) {
        return 'no-node:' + name
      }
      node.emit('touch-start')
      return 'ok'
    },
    /** 点某一行行内的动作按钮（行内第 4 个子节点，见 SocialPanelView.createRow）。 */
    tapRowAction: (matchText) => {
      const scene = window.cc.director.getScene()
      const rows = []
      const stack = [...scene.children]
      while (stack.length > 0) {
        const node = stack.pop()
        if (node.name === 'SocialRow') {
          rows.push(node)
        }
        for (const child of node.children) stack.push(child)
      }
      for (const row of rows) {
        const texts = []
        const walk = (node, inside) => {
          const live = inside && node.activeInHierarchy !== false
          const label = live && node.getComponent ? node.getComponent('cc.Label') : null
          if (label !== null && label !== undefined && label.string !== '') {
            texts.push(label.string)
          }
          for (const child of node.children) walk(child, live)
        }
        walk(row, true)
        if (!texts.some((text) => text.includes(matchText))) {
          continue
        }
        const button = row.children[3]
        if (button === undefined || button.active !== true) {
          return 'row-found-without-button'
        }
        button.emit('touch-start')
        return 'ok'
      }
      return 'no-row:' + matchText
    },
    focusInput: () => {
      const node = findNode('ChatInput')
      if (node === null) {
        return 'no-node'
      }
      const box = node.getComponent('cc.EditBox')
      if (box === null || box === undefined) {
        return 'no-editbox'
      }
      box.setFocus()
      return 'ok'
    },
    inputString: () => {
      const node = findNode('ChatInput')
      const box = node === null ? null : node.getComponent('cc.EditBox')
      return box === null || box === undefined ? '' : String(box.string)
    },
    /** 选择器（ChoiceOverlay）里当前显示的选项标题。 */
    choiceTitles: () => {
      const scene = window.cc.director.getScene()
      const titles = []
      const stack = [...scene.children]
      while (stack.length > 0) {
        const node = stack.pop()
        if (node.name.startsWith('Choice-') && node.activeInHierarchy) {
          const texts = []
          for (const child of node.children) {
            const label = child.getComponent ? child.getComponent('cc.Label') : null
            if (label !== null && label !== undefined && label.string !== '') {
              texts.push(label.string)
            }
          }
          if (texts.length > 0) {
            titles.push(texts[0])
          }
        }
        for (const child of node.children) stack.push(child)
      }
      return titles
    },
    /** 点选择器里标题包含某段文字的那一项。 */
    tapChoice: (contains) => {
      const scene = window.cc.director.getScene()
      const stack = [...scene.children]
      while (stack.length > 0) {
        const node = stack.pop()
        if (node.name.startsWith('Choice-') && node.activeInHierarchy) {
          const texts = []
          for (const child of node.children) {
            const label = child.getComponent ? child.getComponent('cc.Label') : null
            if (label !== null && label !== undefined && label.string !== '') {
              texts.push(label.string)
            }
          }
          if (texts.some((text) => text.includes(contains))) {
            node.emit('touch-start')
            return 'ok'
          }
        }
        for (const child of node.children) stack.push(child)
      }
      return 'no-choice:' + contains
    },
    chatControlsActive: () => {
      const node = findNode('ChatControls')
      return node !== null && node.active === true
    },
  }
}

async function postJson(url, body, playerId) {
  const headers = { 'Content-Type': 'application/json' }
  if (playerId !== undefined) {
    headers['X-Player-Id'] = playerId
  }
  const response = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) })
  return { status: response.status, root: await response.json() }
}

async function main() {
  if (!existsSync(path.join(ROOT, 'index.html'))) {
    console.error(`没有 web 产物：${ROOT}/index.html 不在。先跑一次 web-mobile 构建。`)
    process.exit(2)
  }
  const probe = await fetch(`${BACKEND}/player/init`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ requestId: `chat-probe-${Date.now()}`, deviceId: `probe-${Date.now()}`,
      nickName: '探针', clientTime: Date.now() }),
  }).catch(() => null)
  if (probe === null) {
    console.error(`后端连不上：${BACKEND}（先跑 bash scripts/dev.sh）`)
    process.exit(2)
  }
  const warmup = await probe.json()
  if (warmup.code !== 0) {
    console.error(`后端 /player/init 回 ${warmup.code}：${warmup.msg}`)
    process.exit(2)
  }
  // 对照组（HTTP 层）：不存在路径必须 404，否则下面的"读到消息"可能来自某个兜底处理器
  const bogus = await fetch(`${BACKEND}/chat/no-such-endpoint`, { method: 'POST' })
  verdict(bogus.status === 404, '对照组：不存在的路径回 404', `status=${bogus.status}`)

  const preview = await startPreviewServer({ root: ROOT, backend: BACKEND, port: PORT })
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
  const page = await context.newPage()

  let boot = null
  /** 观到的请求 URL（用来证"点了那条分享真的会去拉回放"，而不只是画了个按钮）。 */
  const requestedUrls = []
  page.on('request', (request) => requestedUrls.push(request.url()))
  page.on('console', (msg) => {
    const text = msg.text()
    if (text.startsWith('[boot] ')) {
      try {
        boot = JSON.parse(text.slice('[boot] '.length))
      } catch {
        // 不是结构化的那条 [boot]：忽略，判据只要那一条 JSON
      }
    }
  })

  await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  const bootDeadline = Date.now() + 45_000
  while (boot === null && Date.now() < bootDeadline) {
    await page.waitForTimeout(500)
  }
  preview.assertRewritten()
  if (boot === null) {
    await browser.close()
    await preview.close()
    console.error('\n=== 判定中止：没捕获到 [boot] 自检行，后面每格读数都会是假的 ===')
    process.exit(1)
  }
  const playerId = typeof boot.playerId === 'string' ? boot.playerId : ''
  verdict(boot.started === true && playerId !== '', '启动跑通且拿到 playerId',
    `platform=${boot.platform} playerId=${playerId === '' ? '—' : playerId}`)

  await page.evaluate(installHelpers)
  const textsOf = () => page.evaluate(() => window.__chat.textsUnder('social'))
  const tap = (name) => page.evaluate((nodeName) => window.__chat.tapNode(nodeName), name)
  const waitFor = async (predicate, timeoutMs = 20_000) => {
    const deadline = Date.now() + timeoutMs
    let last = []
    while (Date.now() < deadline) {
      last = await textsOf()
      if (predicate(last)) {
        return last
      }
      await page.waitForTimeout(300)
    }
    return last
  }

  // ---- 1) 聊天页签画出来了：四个频道按钮 ----
  const tabTap = await tap('Tab_chat')
  const chatTexts = await waitFor(list => list.includes('世界') && list.includes('私聊'))
  verdict(tabTap === 'ok' && chatTexts.includes('世界') && chatTexts.includes('联盟')
    && chatTexts.includes('小队') && chatTexts.includes('私聊'),
  '聊天页签切过去后画出四个频道按钮', `tap=${tabTap} 文本 ${chatTexts.length} 条`)

  const controlsVisible = await page.evaluate(() => window.__chat.chatControlsActive())
  verdict(controlsVisible, '频道条与输入行只在聊天页签里显示', `active=${controlsVisible}`)

  // ---- 2) 未入盟看联盟频道：服务端的拒绝理由进提示行（客户端不预判资格） ----
  await tap('Channel_ALLIANCE')
  const allianceTexts = await waitFor(list => list.some(text => text.includes('没有在该频道发言的资格')))
  verdict(allianceTexts.some(text => text.includes('没有在该频道发言的资格')),
    '未入盟看联盟频道：提示行给的是服务端的拒绝理由',
    `文本 → ${allianceTexts.slice(0, 4).join(' | ').slice(0, 100)}`)

  // ---- 3) 世界频道：真键盘输入 + 点发送 ⇒ 面板画出来、后端也读得到 ----
  await tap('Channel_WORLD')
  await waitFor(list => list.some(text => text.includes('世界频道')))
  const focus = await page.evaluate(() => window.__chat.focusInput())
  await page.waitForTimeout(500)
  const content = `验证消息-${Date.now() % 100000}`
  await page.keyboard.type(content, { delay: 30 })
  const typed = await page.evaluate(() => window.__chat.inputString())
  verdict(focus === 'ok' && typed === content,
    '输入框真的吃到了键盘输入（不是往组件里塞字符串）',
    `focus=${focus} 组件里读到 "${typed}"`)

  await tap('ChatSend')
  const sentTexts = await waitFor(list => list.some(text => text.includes(content)))
  verdict(sentTexts.some(text => text.includes(content)), '发送后面板里画出了这条消息',
    `文本 ${sentTexts.length} 条`)
  // 成功才清：失败时玩家打的字要留着（那半条由 node 用例钉住，这里只钉浏览器这一路真的清了）
  const cleared = await page.evaluate(() => window.__chat.inputString())
  verdict(cleared === '', '发送成功后输入框被清空', `组件里读到 "${cleared}"`)

  const listBack = await postJson(`${BACKEND}/chat/list`,
    { channel: 'WORLD', toPlayerId: null, beforeMessageId: null, limit: 20 }, playerId)
  const inBackend = listBack.root.code === 0
    && (listBack.root.data.messages ?? []).some(m => m.content === content)
  verdict(inBackend, '后端 /chat/list 里也有这条消息（面板画出来不算数，得真落库）',
    `code=${listBack.root.code} 条数=${(listBack.root.data?.messages ?? []).length}`)

  // ---- 4) 另一个玩家发私聊 ⇒ 推送点亮未读、会话列表出现、打开后清零 ----
  const other = await postJson(`${BACKEND}/player/init`, {
    requestId: `chat-probe-p2-${Date.now()}`, deviceId: `probe-p2-${Date.now()}`,
    nickName: '推送验证者', clientTime: Date.now(),
  })
  const senderId = other.root.data?.playerId ?? ''
  verdict(senderId !== '', '造出第二个玩家（私聊推送的发送方）', `playerId=${senderId}`)
  const privateText = `私聊验证-${Date.now() % 100000}`
  const sent = await postJson(`${BACKEND}/chat/send`, {
    requestId: `chat-probe-send-${Date.now()}`, channel: 'PRIVATE',
    content: privateText, toPlayerId: playerId,
  }, senderId)
  verdict(sent.root.code === 0, '第二个玩家把私聊发给探针账号', `code=${sent.root.code}`)

  const unreadTexts = await waitFor(list => list.some(text => text.includes('私聊（')),
    15_000)
  verdict(unreadTexts.some(text => text.includes('私聊（')),
    '推送到达后私聊按钮出现未读数（此前 serverPush 零订阅，这一格永远是 0）',
    `文本 → ${unreadTexts.filter(t => t.includes('私聊')).join(' | ')}`)

  await tap('Channel_PRIVATE')
  const conversationTexts = await waitFor(list => list.some(text => text.includes('给你发来一条私信')))
  verdict(conversationTexts.some(text => text.includes('给你发来一条私信')),
    '私聊频道画出了会话列表（还没学到昵称时用事件标题，打开一次后才是昵称）',
    `文本 → ${conversationTexts.slice(0, 5).join(' | ').slice(0, 120)}`)

  const openTap = await page.evaluate(() => window.__chat.tapRowAction('给你发来一条私信'))
  const openedTexts = await waitFor(list => list.some(text => text.includes(privateText)))
  verdict(openTap === 'ok' && openedTexts.some(text => text.includes(privateText)),
    '点「打开」后拉到了那条私聊正文，且未读清零',
    `tap=${openTap} 私聊按钮=${openedTexts.filter(t => t.startsWith('私聊')).join('、')}`)
  verdict(!openedTexts.some(text => text === '私聊（1）'),
    '打开会话后未读归零（走的是 /social/ackEvents 那本账）',
    `私聊相关文本 → ${openedTexts.filter(t => t.startsWith('私聊')).join('、')}`)

  // ---- 5) 分享入口的客户端半边：同形消息长按钮，点它会去拉回放 ----
  // 真实分享要从回放页发起，而探针账号是全新号（没有兵力 ⇒ 打不了任何一关 ⇒ 拿不到战报；
  // 给它开一个发兵/发战报的入口正是被禁止的作弊端点）。所以这里验的是**消费侧**：
  // 让别人发一条同形的分享消息，看那条消息在面板里长不长「打开」、点下去会不会真去拉回放。
  const fakeReportId = 'battle_probe_missing'
  const forged = await postJson(`${BACKEND}/chat/send`, {
    requestId: `chat-probe-share-${Date.now()}`, channel: 'WORLD',
    content: `分享验证 [report:${fakeReportId}]`, toPlayerId: null,
  }, senderId)
  verdict(forged.root.code === 0, '对照夹具：另一个玩家发一条同形的分享消息', `code=${forged.root.code}`)
  await tap('Channel_WORLD')
  const worldTexts = await waitFor(list => list.some(text => text.includes('分享了战报')
    || text.includes('分享验证')))
  verdict(worldTexts.some(text => text.includes('分享验证')),
    '同形消息画进了世界频道（正文里的 [report:] 标记不显示给人看）',
    `文本 → ${worldTexts.filter(t => t.includes('分享')).join(' | ').slice(0, 100)}`)
  const openReportTap = await page.evaluate(() => window.__chat.tapRowAction('分享验证'))
  await page.waitForTimeout(1500)
  const pulled = requestedUrls.some(url => url.includes('/battle/report') && url.includes(fakeReportId))
  verdict(openReportTap === 'ok' && pulled,
    '点那条消息的「打开」真的去拉了回放（假 id 回 5005 是预期的：这只验接线）',
    `tap=${openReportTap} 请求数=${requestedUrls.filter(u => u.includes('/battle/report')).length}`)

  // ---- 6) 举报与拉黑（B22 §一 3）：从聊天行走完整条链，最后从黑名单入口撤销 ----
  const blockText = `拉黑验证-${Date.now() % 100000}`
  await postJson(`${BACKEND}/chat/send`, {
    requestId: `chat-probe-block-${Date.now()}`, channel: 'WORLD', content: blockText,
    toPlayerId: null,
  }, senderId)
  await tap('Channel_WORLD')
  await waitFor(list => list.some(text => text.includes(blockText)))

  const menuTap = await page.evaluate(() => window.__chat.tapRowAction('拉黑验证'))
  // 选择器是下一帧才画出来的：点完立刻读会拿到空列表（这一条踩过一次）
  await page.waitForTimeout(700)
  const menuTitles = await page.evaluate(() => window.__chat.choiceTitles())
  verdict(menuTap === 'ok' && menuTitles.length === 4
    && menuTitles.some(t => t.includes('举报：辱骂')) && menuTitles.some(t => t.includes('举报：刷屏'))
    && menuTitles.some(t => t.includes('举报：疑似作弊')) && menuTitles.some(t => t.includes('举报：其他')),
  '消息行的按钮弹出一层选择器：第一页是四种举报原因（拉黑在第 2 页，与选择器 4 项/页一致）',
  `tap=${menuTap} 选项=${menuTitles.join(' / ').slice(0, 90)}`)

  await page.evaluate(() => window.__chat.tapChoice('举报：刷屏'))
  await page.waitForTimeout(400)
  const reported = await waitFor(list => list.some(text => text.includes('举报已受理')))
  verdict(reported.some(text => text.includes('举报已受理')),
    '选了原因后回执写在提示行上（只说记下了，不说会不会封）',
    `文本 → ${reported.filter(t => t.includes('举报')).join(' | ').slice(0, 80)}`)

  await page.evaluate(() => window.__chat.tapRowAction('拉黑验证'))
  await page.waitForTimeout(700)
  // 选择器一页放 4 项（OPTIONS_PER_PAGE），拉黑在第 2 页 —— 与玩家走的路径一致：翻一页再点
  await page.evaluate(() => window.__chat.tapNode('ChoiceNext'))
  await page.waitForTimeout(500)
  const blockTap = await page.evaluate(() => window.__chat.tapChoice('拉黑'))
  await page.waitForTimeout(1200)
  const afterBlock = await page.evaluate(() => window.__chat.textsUnder('social'))
  verdict(blockTap === 'ok' && !afterBlock.some(text => text.includes(blockText)),
    '拉黑之后那条消息立刻从我的频道里消失（本地缓存整份丢掉重拉）',
    `tap=${blockTap} 文本 ${afterBlock.length} 条`)
  const myWorld = await postJson(`${BACKEND}/chat/list`,
    { channel: 'WORLD', toPlayerId: null, beforeMessageId: null, limit: 50 }, playerId)
  verdict(!(myWorld.root.data.messages ?? []).some(m => m.content === blockText),
    '服务端 /chat/list 也不回他的消息（面板消失不等于服务端过滤）',
    `条数=${(myWorld.root.data.messages ?? []).length}`)
  const otherWorld = await postJson(`${BACKEND}/chat/list`,
    { channel: 'WORLD', toPlayerId: null, beforeMessageId: null, limit: 50 }, senderId)
  verdict((otherWorld.root.data.messages ?? []).some(m => m.content === blockText),
    '对照组：他自己还看得到那条（拉黑是观察者过滤，不是删消息）',
    `条数=${(otherWorld.root.data.messages ?? []).length}`)

  await tap('Channel_PRIVATE')
  await waitFor(list => list.some(text => text.includes('黑名单')))
  const manageTap = await page.evaluate(() => window.__chat.tapRowAction('黑名单'))
  await page.waitForTimeout(700)
  const manageTitles = await page.evaluate(() => window.__chat.choiceTitles())
  await page.evaluate(() => window.__chat.tapChoice('取消拉黑'))
  await page.waitForTimeout(1200)
  await tap('Channel_WORLD')
  const restored = await waitFor(list => list.some(text => text.includes(blockText)))
  verdict(manageTap === 'ok' && restored.some(text => text.includes(blockText)),
    '黑名单入口能撤销拉黑，那条消息又回来了（拉黑是可逆的，入口够得着）',
    `tap=${manageTap} 选项=${manageTitles.join(' / ').slice(0, 60)}`)

  writeFileSync(SHOT, await page.screenshot({ fullPage: false }))
  lines.push(`PASS  截图落盘  ${SHOT}`)

  await browser.close()
  await preview.close()

  console.log('\n=== 聊天运行时判定（B22 §一 1）===')
  for (const line of lines) {
    console.log(line)
  }
  if (failures.length > 0) {
    console.error(`\n共 ${failures.length} 条失败：${failures.join('；')}`)
    process.exit(1)
  }
  console.log('\n全部通过。')
}

main().catch((error) => {
  console.error('[chat-runtime] 探针自身出错：', error)
  process.exit(2)
})
