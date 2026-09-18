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
  chatKey, chatMessageText, chatUnreadCount, CHAT_LOCAL_HISTORY_MAX, mergeChatHistory,
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
    blockedCount: 0, friends: [],
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
    peerNames: new Map(), offsetMs: 0, localNow: 0, sentSeq: 0, blockedCount: 0, friends: [],
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
    sentSeq: 0, notice: null, friends: [],
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
