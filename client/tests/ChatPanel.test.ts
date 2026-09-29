/**
 * 职责：ChatPanel 的单测 —— B22 §一 1 的四条要求（四频道 / 私聊会话列表与未读计数 /
 * 本地 200 条上限 / 限流给可读提示）。
 * 依赖：node:test / node:assert。
 *
 * <p>重点盯四条：
 * <ol>
 *   <li><b>未读只认服务端事件</b>：`relatedId` 是发信人、按人分组计数，
 *       不自己造"读过没有"的第二本账</li>
 *   <li><b>不预判频道资格</b>：未入盟也能把消息发出去，失败原因由服务端给 ——
 *       客户端存一份"我有没有联盟"的副本，副本过期时会挡住一次合法发言</li>
 *   <li><b>本地历史有上限且丢最旧</b>：200 条是 global 的口径，不是随手写的数</li>
 *   <li><b>限流要有可读提示</b>：服务端的 detail 直接甩给玩家像报错</li>
 * </ol>
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ackablePrivateEventIds, buildChatPanel, buildChatMessages, chatConversations, chatFailureText,
  chatContentPerPage, chatKey, chatMessageText, chatPageCount, chatPageOf, chatUnreadCount, CHAT_FETCH_OLDER,
  CHAT_LOCAL_HISTORY_MAX, CHAT_PAGE_ROWS, mergeChatHistory,
  parseSharedReport, SOCIAL_CHAT_RATE_LIMITED,
} from '../assets/scripts/game/social/ChatPanel'
import type { ChatMessageView, SocialEventView } from '../assets/scripts/net/generated/SocialProtocol'

function message(messageId: string, sentAt: number, overrides: Partial<ChatMessageView> = {}): ChatMessageView {
  return {
    messageId, channel: 'WORLD', senderId: 'other', senderName: '张三', content: `内容${messageId}`,
    sentAt, ...overrides,
  }
}

function privateEvent(eventId: string, peerId: string, occurredAt: number,
                      overrides: Partial<SocialEventView> = {}): SocialEventView {
  return {
    eventId, type: 'PRIVATE_MESSAGE', title: `${peerId} 给你发来一条私信`, body: null, coord: null,
    relatedId: peerId, occurredAt, expired: false, ...overrides,
  }
}

test('历史合并：同一条消息重复下发只留一份，顺序按时间升序', () => {
  const merged = mergeChatHistory(
    [message('m2', 200), message('m1', 100)],
    [message('m1', 100), message('m3', 300)],
  )
  assert.deepEqual(merged.map(m => m.messageId), ['m1', 'm2', 'm3'])
})

test('历史合并：超出上限丢最旧，保留的恰好是最近 cap 条', () => {
  const incoming = Array.from({ length: 12 }, (_, i) => message(`m${i}`, 1000 + i))
  const merged = mergeChatHistory([], incoming, 5)
  assert.deepEqual(merged.map(m => m.messageId), ['m7', 'm8', 'm9', 'm10', 'm11'])
  // 默认上限就是 global 里那个数（200），不是随手写的
  assert.equal(CHAT_LOCAL_HISTORY_MAX, 200)
  assert.equal(mergeChatHistory([], incoming).length, 12)
})

test('私聊会话：按发信人分组计数，按最近一条排序，自己/非私聊事件不计入', () => {
  const events: SocialEventView[] = [
    privateEvent('e1', 'p1', 100),
    privateEvent('e2', 'p2', 300),
    privateEvent('e3', 'p1', 200),
    // 非私聊事件：本来就不该出现在聊天页签里
    { ...privateEvent('e4', 'p3', 400), type: 'MEMBER_ATTACKED' },
    // 自己给自己？服务端不发，客户端也不该把它数成一个会话
    privateEvent('e5', 'me', 500),
  ]
  const rows = chatConversations(events, new Map([['p2', '李四']]), 'me', 0, 350)
  assert.deepEqual(rows.map(r => r.peerId), ['p2', 'p1'])
  assert.equal(rows[0]?.label, '李四')
  assert.equal(rows[0]?.unread, 1)
  assert.equal(rows[1]?.unread, 2)
  // 学不到昵称的会话退回事件标题，而不是把标题切一半当名字
  assert.equal(rows[1]?.label, 'p1 给你发来一条私信')
  // 徽标数必须等于各行之和：自己那条不计，非私聊事件不计
  assert.equal(chatUnreadCount(events, 'me'), 3)
})

test('未读口径与红点叶一致：过期未读仍计入（否则红点亮着而计数为零）', () => {
  const events = [privateEvent('e1', 'p1', 100, { expired: true })]
  assert.equal(chatUnreadCount(events, 'me'), 1)
})

test('消账只消当前会话那一份：别把别人的未读一起清了', () => {
  const events = [privateEvent('e1', 'p1', 100), privateEvent('e2', 'p2', 200)]
  assert.deepEqual(ackablePrivateEventIds(events, 'p2'), ['e2'])
  assert.deepEqual(ackablePrivateEventIds(events, 'p9'), [])
})

test('消息行：自己发的写「我」，其余用服务端昵称；不是今天的补日期', () => {
  const day = 24 * 3600 * 1000
  const now = new Date(2026, 8, 18, 12, 0, 0).getTime() // 本地时区，与 clockText 同一套换算
  const rows = buildChatMessages([
    message('m1', now, { senderId: 'me', senderName: '我自己' }),
    message('m2', now - day, { senderId: 'other', senderName: '张三' }),
  ], 'me', now)
  assert.equal(rows[0]?.author, '我')
  assert.equal(rows[0]?.mine, true)
  assert.match(rows[0]?.timeText ?? '', /^12:00$/)
  assert.equal(rows[1]?.author, '张三')
  assert.equal(rows[1]?.mine, false)
  assert.match(rows[1]?.timeText ?? '', /^9-17 12:00$/)
})

test('失败文案：限流加一句「慢一点」，其余原样透出服务端的 detail', () => {
  assert.equal(chatFailureText(SOCIAL_CHAT_RATE_LIMITED, '同一句话 8 秒后才能再发', '限流'),
    '慢一点：同一句话 8 秒后才能再发')
  // detail 缺失时退回 msg，不能出现「慢一点：undefined」
  assert.equal(chatFailureText(SOCIAL_CHAT_RATE_LIMITED, null, '限流'),
    '慢一点：限流')
  assert.equal(chatFailureText(10046, '你没有在该频道发言的资格（未加入对应组织）', '没资格'),
    '你没有在该频道发言的资格（未加入对应组织）')
})

test('页面数据：私聊未选会话画会话列表，选中后画消息，页签角标只挂在私聊', () => {
  const events = [privateEvent('e1', 'p1', 100)]
  const base = {
    messages: [message('m1', 100)], events, myPlayerId: 'me',
    peerNames: new Map<string, string>(), offsetMs: 0, localNow: 200, sentSeq: 0, notice: null,
    blockedCount: 0, friends: [], page: 0, hasMoreOlder: false,
  }
  const list = buildChatPanel({ ...base, channel: 'PRIVATE', peerId: null })
  assert.equal(list.mode, 'conversations')
  assert.equal(list.messages.length, 0)
  assert.equal(list.conversations.length, 1)
  assert.equal(list.canSend, false, '私聊没选对象时结构上就发不出去（缺 toPlayerId）')
  assert.equal(list.hintText, '从会话列表选一位再说话')
  assert.equal(list.channelTabs.find(t => t.key === 'PRIVATE')?.unread, 1)
  assert.equal(list.channelTabs.find(t => t.key === 'WORLD')?.unread, 0)

  const opened = buildChatPanel({ ...base, channel: 'PRIVATE', peerId: 'p1' })
  assert.equal(opened.mode, 'messages')
  assert.equal(opened.messages.length, 1)
  assert.equal(opened.canSend, true)

  // 未入盟也照样能发：资格由服务端裁决，客户端不预判（预判的代价是挡住合法发言）
  const world = buildChatPanel({ ...base, channel: 'WORLD', peerId: null })
  assert.equal(world.canSend, true)
  assert.equal(world.hintText, '世界频道 · 所有人可见')
})

test('页面数据：notice 进提示行且按告警色画（失败原因是玩家唯一看得见的落点）', () => {
  const data = buildChatPanel({
    channel: 'SQUAD', peerId: null, messages: [], events: [], myPlayerId: 'me',
    peerNames: new Map(), offsetMs: 0, localNow: 0, sentSeq: 0, blockedCount: 0, friends: [], page: 0, hasMoreOlder: false,
    notice: '慢一点：同一句话 8 秒后才能再发',
  })
  assert.equal(data.hintIsWarning, true)
  assert.equal(data.hintText, '慢一点：同一句话 8 秒后才能再发')
})

test('分享战报的消息：认出结尾的结构化标记，显示时把它摘掉', () => {
  const shared = '分享了战报：叛军斥候 [report:battle_abc123]'
  assert.equal(parseSharedReport(shared), 'battle_abc123')
  assert.equal(chatMessageText(buildChatMessages([
    message('m1', 0, { content: shared }),
  ], 'me', 0)[0]!), '分享了战报：叛军斥候')

  // 普通发言不长按钮：没有标记就是一条平常的消息
  assert.equal(parseSharedReport('大家晚上好'), null)
  assert.equal(parseSharedReport('看这个 [report:] 好不好'), null)
  // 标记必须完整占一个词 —— 句子中间夹一个同形串不算分享（点开会去拉一份不存在的战报）
  assert.equal(parseSharedReport('我写了[report:abc]这样一句话'), null)
  assert.equal(parseSharedReport('[report:abc]'), 'abc')
  assert.equal(parseSharedReport('分享 [report:abc]'), 'abc')
})

test('黑名单入口：私聊列表顶部多一行（拉黑后那条消息够不着，解除只能从这里进）', () => {
  const base = {
    channel: 'PRIVATE' as const, peerId: null, messages: [], events: [],
    myPlayerId: 'me', peerNames: new Map<string, string>(), offsetMs: 0, localNow: 0,
    sentSeq: 0, notice: null, friends: [], page: 0, hasMoreOlder: false,
  }
  const empty = buildChatPanel({ ...base, blockedCount: 0 })
  assert.equal(empty.conversations.some(row => row.peerId === 'blocks'), false,
    '一个人都没拉黑时不摆这一行')
  const withBlocked = buildChatPanel({
    ...base,
    blockedCount: 2,
    events: [privateEvent('e1', 'p1', 100)],
  })
  assert.equal(withBlocked.blockedCount, 2, '面板照着计数画「黑名单（2）」')
})

test('关注列表：进私聊页就能看到，带在线状态（会话列表只有别人先找过我的那些人）', () => {
  const data = buildChatPanel({
    channel: 'PRIVATE', peerId: null, messages: [], events: [], myPlayerId: 'me',
    peerNames: new Map(), offsetMs: 0, localNow: 1000, sentSeq: 0, notice: null, blockedCount: 0,
    page: 0, hasMoreOlder: false,
    friends: [
      { playerId: 'f1', name: '老王', online: true, lastSeenAt: 1000 },
      { playerId: 'f2', name: '小李', online: false, lastSeenAt: 1000 - 3 * 3600_000 },
    ],
  })
  assert.deepEqual(data.friends.map(f => f.name), ['老王', '小李'])
  assert.equal(data.friends[0]?.presenceText, '在线')
  assert.equal(data.friends[1]?.presenceText, '3 小时前')
})

test('本地历史按会话分桶：私聊的键要带对象，否则两人的会话会互相覆盖', () => {
  assert.equal(chatKey('WORLD', null), 'WORLD')
  assert.equal(chatKey('PRIVATE', 'p1'), 'PRIVATE:p1')
  assert.notEqual(chatKey('PRIVATE', 'p1'), chatKey('PRIVATE', 'p2'))
})

// ---------- V15：聊天真分页 ----------

function msg(index: number): ChatMessageView {
  return {
    messageId: `m${String(index).padStart(3, '0')}`,
    channel: 'WORLD', senderId: 'other', senderName: '别人', content: `第 ${index} 条`,
    sentAt: 1_000 + index,
  } as ChatMessageView
}

function ramp(count: number): ChatMessageView[] {
  return Array.from({ length: count }, (_v, i) => msg(i))
}

test('聊天分页：第 0 页是**最新**一页（与其它页签相反）', () => {
  const all = ramp(12)
  // 容量 5，而 12 条装不下 ⇒ **为翻页行让出一格**，每页 4 条（与其它页签共用 contentPerPage）
  const perPage = chatContentPerPage(12)
  assert.equal(perPage, 4, '装不下时要为翻页行让位：5 条消息 + 1 行翻页会压到输入行上')
  const newest = chatPageOf(all, 'me', 2_000, 0, false)
  assert.equal(newest.page, 0)
  assert.equal(newest.pages, Math.ceil(12 / perPage))
  assert.equal(newest.rows.length, perPage)
  // 最新页必须是**最后** 4 条，不是最前 4 条 —— 正序分页会让"自己刚发的消息不出现"
  assert.equal(newest.rows[newest.rows.length - 1]?.messageId, 'm011')
  assert.equal(newest.rows[0]?.messageId, 'm008')
  assert.equal(newest.canNewer, false, '第 0 页就是最新，没有更新的')
  assert.equal(newest.canOlder, true)
  assert.match(newest.noticeText, /第 1\/3 页（第 1 页最新）/)
  assert.match(newest.noticeText, /每页 4 条/, '每页几条会被"让位"改掉，所以要报出来')

  // 往更早翻一页
  const older = chatPageOf(all, 'me', 2_000, 1, false)
  assert.equal(older.rows[0]?.messageId, 'm004')
  assert.equal(older.rows[older.rows.length - 1]?.messageId, 'm007')
  assert.equal(older.canNewer, true)
  assert.equal(older.canOlder, true)
  assert.match(older.noticeText, /第 2\/3 页/)

  // 最旧那一页：本地到头、服务端也没了 ⇒ canOlder=false
  const oldest = chatPageOf(all, 'me', 2_000, 2, false)
  assert.equal(oldest.rows[0]?.messageId, 'm000')
  assert.equal(oldest.canOlder, false, '本地与服务端都到头了，那颗「更早」必须灰')
})

test('聊天分页：装得下时**不让位**（每页仍是 5 条，也没有翻页行）', () => {
  // 5 条正好装满容量 ⇒ 不画翻页行，5 条都画出来
  const exact = chatPageOf(ramp(5), 'me', 2_000, 0, false)
  assert.equal(chatContentPerPage(5), 5)
  assert.equal(exact.pages, 1)
  assert.equal(exact.rows.length, 5)
  assert.equal(exact.canOlder, false)
  assert.equal(exact.canNewer, false)
  assert.ok(!exact.noticeText.includes('每页'), '只有一页时不报每页几条')
})

test('聊天分页：本地到头但服务端还有 ⇒ 「更早」继续亮（点它去拉下一段）', () => {
  const all = ramp(6)   // 2 页
  const last = chatPageOf(all, 'me', 2_000, 1, true)
  assert.equal(last.page, 1)
  assert.equal(last.canOlder, true, 'hasMore=true 时最旧那一页的「更早」不许灰 —— 灰了就再也拉不到历史')
  const done = chatPageOf(all, 'me', 2_000, 1, false)
  assert.equal(done.canOlder, false)
})

test('聊天分页：页码越界夹回，不抛异常也不画空白页', () => {
  const all = ramp(12)
  // 负数与超大页码都要夹回范围内（翻页键会画灰，但数据层不能依赖视图的自觉）
  assert.equal(chatPageOf(all, 'me', 2_000, -5, false).page, 0)
  assert.equal(chatPageOf(all, 'me', 2_000, 99, false).page, 2)
  assert.ok(chatPageOf(all, 'me', 2_000, 99, false).rows.length > 0, '夹回来的那一页必须有内容')
  // 一条都没有时：1 页、0 行、页码 0（0 页会让"上一页"除零）
  const empty = chatPageOf([], 'me', 2_000, 0, false)
  assert.equal(empty.pages, 1)
  assert.equal(empty.rows.length, 0)
  assert.equal(empty.noticeText, '')
  assert.equal(empty.canOlder, false)
  assert.equal(empty.canNewer, false)
})

test('聊天分页：页内仍是升序（面板从上往下画），且与其它页签的页数口径一致', () => {
  const all = ramp(11)
  // **从最旧那一页往新页遍历**：第 0 页是最新，所以页码递增是倒着走时间轴
  // （第一版这里从 0 开始遍历，断言必红 —— 但那红的是测试的前提，不是代码）
  const pages = chatPageOf(all, 'me', 2_000, 0, false).pages
  let previous = -1
  for (let page = pages - 1; page >= 0; page -= 1) {
    const view = chatPageOf(all, 'me', 2_000, page, false)
    for (const row of view.rows) {
      const index = Number(row.messageId.slice(1))
      assert.ok(index > previous, `page=${page} 页内顺序错了：${row.messageId} 跟在 ${previous} 之后`)
      previous = index
    }
  }
  assert.equal(previous, 10, '翻完所有页必须恰好覆盖每一条，不重不漏')
  // 共几页与切哪一段必须用同一个 perPage（两处各算一遍就会翻出空白页）
  for (const count of [0, 1, 5, 6, 10, 11, 200]) {
    const total = chatPageCount(count)
    const lastPage = chatPageOf(ramp(count), 'me', 2_000, total - 1, false)
    assert.ok(count === 0 || lastPage.rows.length > 0, `count=${count} 的最后一页是空的（页数算错了）`)
  }
})

test('聊天分页：一屏装得下时不占翻页行（canOlder/canNewer 都为 false）', () => {
  const small = chatPageOf(ramp(3), 'me', 2_000, 0, false)
  assert.equal(small.pages, 1)
  assert.equal(small.canOlder, false)
  assert.equal(small.canNewer, false)
  assert.equal(small.rows.length, 3)
})

test('聊天分页：CHAT_PAGE_ROWS 与"向服务端一次要多少"是两个数，不许合并', () => {
  assert.equal(CHAT_PAGE_ROWS, 5, '一屏 5 条消息 + 1 行翻页 = 6 行，正好用满聊天页签的可视高')
  assert.equal(CHAT_FETCH_OLDER, 20, '往前翻一次补 20 条 = 4 屏，翻 4 次才发一枪')
  assert.notEqual(CHAT_PAGE_ROWS, CHAT_FETCH_OLDER)
  assert.ok(CHAT_FETCH_OLDER > CHAT_PAGE_ROWS, '一次要的必须多于一屏，否则每翻一页都发一次请求')
  assert.ok(CHAT_FETCH_OLDER <= CHAT_LOCAL_HISTORY_MAX, '一次要的不能超过本地缓存上限')
})
