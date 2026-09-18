/**
 * 职责：聊天页签的展示数据组装（B22 §一 1）—— 四个频道、私聊会话列表、未读计数、
 *      本地保留上限。引擎无关，可在 node:test 里直接驱动。
 * 依赖：生成的协议类型。
 *
 * <p><b>判定仍然一条都不做</b>（与 SocialPanel 同一条纪律）：频道资格（未入盟不能发联盟频道）
 * 由服务端 `requireChannelKey` 裁决，客户端只把失败原因翻成可读文本（{@link chatFailureText}）。
 * 本地先拦一次的代价是要在客户端存一份"我有没有联盟"的副本，副本过期时玩家会被自己的客户端
 * 挡住一次本来合法的发言 —— 而这类失败两边都不会报错。
 *
 * <p><b>未读的唯一来源是服务端事件</b>（C23）：私聊推送与离线补偿共用同一个对象，
 * `relatedId` 就是发信人 id、`title` 里带昵称。客户端按发信人分组、数条数，
 * 打开会话时走 `/social/ackEvents` 消账；不自己记第二本"读过没有"的账 ——
 * 两本账一旦不同步，症状是红点永远亮着或永远不亮，而两边都没有报错。
 *
 * <p>**事件不带正文是刻意设计**（收口清单 #23）：推送只是"有人找你"的信标，
 * 正文的家是 `/chat/list`。所以本模块只拿事件做未读账，一个字的正文都不从事件里取。
 */

import { channelText } from './SocialPanel'
import type { ChatChannel, ChatMessageView, SocialEventView } from '../../net/generated/SocialProtocol'

/**
 * 客户端每个频道本地保留的历史条数上限。
 *
 * <p>来源：`global.json` 的 `CHAT_LOCAL_HISTORY_MAX`。那张表的 `why` 已经把口径写清楚：
 * 200 条约 12KB、四个频道合计不到 50KB，超出丢最旧 —— 聊天记录不是账目，
 * 旧消息的价值随时间迅速衰减。客户端镜像这个数是因为拉取时要用它当 `limit`
 * （服务端仍会再夹一次，所以镜像过期不会越界，只会少拉）。
 */
export const CHAT_LOCAL_HISTORY_MAX = 200

/**
 * 输入框的字符上限。**必须显式设置**：`EditBox` 的默认上限是 20 个字符，
 * 不设置就等于把玩家的半句话静默截断；服务端今天只有"非空 + 内容送检"，没有长度上限。
 */
export const CHAT_INPUT_MAX_LENGTH = 200

/** 服务端错误码（`ErrorCode.java`）。只用它决定文案语气，不做任何分支判定。 */
export const SOCIAL_CHAT_RATE_LIMITED = 10044

/** 四个频道与展示名。顺序即页签顺序：世界在最前（陌生人能被发现），私聊在最后（最私密的一层）。 */
export const CHAT_CHANNELS: ReadonlyArray<{ readonly key: ChatChannel; readonly text: string }> = [
  { key: 'WORLD', text: '世界' },
  { key: 'ALLIANCE', text: '联盟' },
  { key: 'SQUAD', text: '小队' },
  { key: 'PRIVATE', text: '私聊' },
]

/** 本地历史的分桶键。私聊按会话（双方 id 拼），其余按频道。 */
export function chatKey(channel: ChatChannel, peerId: string | null): string {
  return channel === 'PRIVATE' ? `PRIVATE:${peerId ?? ''}` : channel
}

/** 一条消息行。 */
export interface ChatMessageRow {
  readonly messageId: string
  /** 自己发的写「我」—— 每一行都重复一遍自己的昵称是噪音 */
  readonly author: string
  readonly mine: boolean
  readonly content: string
  /** 「12:07」；不是今天的补上「9-17 12:07」 */
  readonly timeText: string
}

/** 一个私聊会话。 */
export interface ChatConversationRow {
  readonly peerId: string
  /** 已知昵称；还不知道时退回事件标题（「X 给你发来一条私信」），打开一次后就会变成昵称 */
  readonly label: string
  readonly unread: number
  readonly timeText: string
}

/** 组装聊天页签所需的全部输入。 */
export interface ChatViewInput {
  readonly channel: ChatChannel
  readonly peerId: string | null
  readonly messages: readonly ChatMessageView[]
  /** 未读社交事件（`/social/summary` 的 events + 推送追加的那些） */
  readonly events: readonly SocialEventView[]
  readonly myPlayerId: string | null
  /** 已学到的昵称。没有它时会话行只能显示事件标题 */
  readonly peerNames: ReadonlyMap<string, string>
  readonly offsetMs: number
  readonly localNow: number
  /**
   * 成功发送的累计次数。面板只看它有没有变：变了就说明"刚才那条发出去了"，于是清空输入框。
   * 失败时这个数不动，玩家打的字留着（重打一遍是最烦人的那种失败）。
   */
  readonly sentSeq: number
  /** 上一次失败的可读原因（限流、没资格…）。成功一次或切换频道后由调用方清空 */
  readonly notice: string | null
}

/** 聊天页签的展示数据。 */
export interface ChatPanelData {
  readonly channel: ChatChannel
  readonly channelTabs: ReadonlyArray<{
    readonly key: ChatChannel
    readonly text: string
    readonly selected: boolean
    readonly unread: number
  }>
  /** 私聊且未选中会话 ⇒ 'conversations'（画会话列表），其余 ⇒ 'messages' */
  readonly mode: 'messages' | 'conversations'
  readonly messages: readonly ChatMessageRow[]
  readonly conversations: readonly ChatConversationRow[]
  readonly peerId: string | null
  readonly peerLabel: string | null
  /** 原样带过去给面板比对（见 {@link ChatViewInput.sentSeq}） */
  readonly sentSeq: number
  /** 输入行提示 */
  readonly hintText: string
  /** true ⇒ 按告警色画（失败原因），false ⇒ 常规说明 */
  readonly hintIsWarning: boolean
  readonly canSend: boolean
  readonly emptyText: string
}

/** 合并一段历史：按 messageId 去重、按时间升序、超出上限丢最旧。 */
export function mergeChatHistory(existing: readonly ChatMessageView[],
                                 incoming: readonly ChatMessageView[],
                                 cap: number = CHAT_LOCAL_HISTORY_MAX): ChatMessageView[] {
  const byId = new Map<string, ChatMessageView>()
  for (const message of existing) {
    byId.set(message.messageId, message)
  }
  // 新到的覆盖旧的：同一条消息重复下发时以最新一次为准（服务端是唯一写方，两者应当相同）
  for (const message of incoming) {
    byId.set(message.messageId, message)
  }
  // Array.from 而不是 [...byId.values()]：Cocos 的转译（SWC loose）把 iterable 的 spread 编成
  // `[].concat(map.values())`，运行时得到的是"装着迭代器的一元数组" —— 于是排序/映射全都对着
  // 迭代器做，症状是面板整个画不出来（而 node:test 用 tsc 编译、真展开，测试全绿）。
  // 同一条纪律见 `game/store/Store.ts`（那里踩过一次 Set 的同一个坑）
  const all = Array.from(byId.values()).sort((a, b) =>
    a.sentAt - b.sentAt || a.messageId.localeCompare(b.messageId))
  return all.length <= cap ? all : all.slice(all.length - cap)
}

/** 未读的私聊事件。`relatedId` 就是发信人 id（C23 的两个调用点都这么写）。 */
export function unreadPrivateMessages(events: readonly SocialEventView[]): SocialEventView[] {
  return events.filter((event) => event.type === 'PRIVATE_MESSAGE' && event.relatedId !== null)
}

/**
 * 未读私信的条数。
 *
 * <p>服务端对同一个发信人只留一条未读（3 小时窗口内连发不叠加），所以这个数
 * 基本等于「有几个会话在等你回话」；但过期的旧未读仍计在内 —— 判据与红点叶
 * `social/events` 完全一致（那个叶也是"有任何未读事件就亮"），
 * 两边口径不同的话会立刻表现成"红点亮着而计数为零"。
 *
 * <p>自己发给自己的事件服务端根本不发（`notifyPrivateMessage` 第一行就挡了），
 * 这里仍然按 `myPlayerId` 滤一次：徽标数必须等于会话列表里各行之和，
 * 两条路径各一套口径就会在某个边缘上分叉（分叉的样子是徽标 4、点进去 3 个会话）。
 */
export function chatUnreadCount(events: readonly SocialEventView[], myPlayerId: string | null): number {
  return unreadPrivateMessages(events).filter((event) => event.relatedId !== myPlayerId).length
}

/** 打开某个私聊会话时要消账的事件 id：只消这一个发信人的，别把别人的未读一起清了。 */
export function ackablePrivateEventIds(events: readonly SocialEventView[], peerId: string): string[] {
  return unreadPrivateMessages(events)
    .filter((event) => event.relatedId === peerId)
    .map((event) => event.eventId)
}

/**
 * 会话列表：按发信人分组、数未读、按最近一条排序。
 *
 * <p>昵称优先用已学到的（打开过一次会话就从消息里学到了），学不到时**退回事件标题**
 * 而不是解析标题里的昵称 —— 标题是给人读的句子（「X 给你发来一条私信」），
 * 按分隔符切一遍就等于把展示文案当协议用，文案一改就静默错位。
 */
export function chatConversations(events: readonly SocialEventView[],
                                 peerNames: ReadonlyMap<string, string>,
                                 myPlayerId: string | null,
                                 offsetMs: number,
                                 localNow: number): ChatConversationRow[] {
  const nowServer = localNow + offsetMs
  const byPeer = new Map<string, { unread: number, lastAt: number, title: string }>()
  for (const event of unreadPrivateMessages(events)) {
    const peerId = event.relatedId
    if (peerId === null || peerId === myPlayerId) {
      continue
    }
    const current = byPeer.get(peerId)
    if (current === undefined) {
      byPeer.set(peerId, { unread: 1, lastAt: event.occurredAt, title: event.title })
    } else {
      current.unread += 1
      current.lastAt = Math.max(current.lastAt, event.occurredAt)
    }
  }
  // 同上：entries() 是迭代器，必须走 Array.from（spread 会被编成 [].concat，拿不到元素）
  return Array.from(byPeer.entries())
    .sort((a, b) => b[1].lastAt - a[1].lastAt || a[0].localeCompare(b[0]))
    .map(([peerId, item]): ChatConversationRow => ({
      peerId,
      label: peerNames.get(peerId) ?? item.title,
      unread: item.unread,
      timeText: clockText(item.lastAt, nowServer),
    }))
}

/** 消息行。 */
export function buildChatMessages(messages: readonly ChatMessageView[], myPlayerId: string | null,
                                  nowServer: number): ChatMessageRow[] {
  return messages.map((message): ChatMessageRow => ({
    messageId: message.messageId,
    author: message.senderId === myPlayerId ? '我' : message.senderName,
    mine: message.senderId === myPlayerId,
    content: message.content,
    timeText: clockText(message.sentAt, nowServer),
  }))
}

/**
 * 失败原因的可读文本。
 *
 * <p>限流是唯一需要加工语气的一类：服务端的 detail 是「同一句话 X 秒后才能再发」，
 * 原样甩出去像报错，而 B22 要的是"慢一点"这样的提示。其余错误原样透出 ——
 * 服务端的 detail 已经把"哪一件事、下一步怎么办"写清楚了，再包一层只会丢信息。
 */
export function chatFailureText(code: number, detail: string | null, msg: string): string {
  const base = detail !== null && detail.trim() !== '' ? detail : msg
  return code === SOCIAL_CHAT_RATE_LIMITED ? `慢一点：${base}` : base
}

/** 组装聊天页签。 */
export function buildChatPanel(input: ChatViewInput): ChatPanelData {
  const conversations = chatConversations(input.events, input.peerNames, input.myPlayerId,
    input.offsetMs, input.localNow)
  const unreadTotal = chatUnreadCount(input.events, input.myPlayerId)
  const channelTabs = CHAT_CHANNELS.map((item) => ({
    key: item.key,
    text: item.text,
    selected: item.key === input.channel,
    // 只有私聊有推送与未读账（C23）：另外三个频道没有推送来源，
    // 数一个客户端不知道的数只会得到一个永远不变的假徽标
    unread: item.key === 'PRIVATE' ? unreadTotal : 0,
  }))
  const conversationsMode = input.channel === 'PRIVATE' && input.peerId === null
  const peerLabel = input.peerId === null ? null : (input.peerNames.get(input.peerId) ?? null)
  return {
    channel: input.channel,
    channelTabs,
    mode: conversationsMode ? 'conversations' : 'messages',
    messages: conversationsMode
      ? []
      : buildChatMessages(input.messages, input.myPlayerId, input.localNow + input.offsetMs),
    conversations,
    peerId: input.peerId,
    peerLabel,
    sentSeq: input.sentSeq,
    hintText: input.notice ?? idleHint(input.channel, peerLabel),
    hintIsWarning: input.notice !== null,
    // 私聊没选对象时发送在结构上就缺一个必需字段（service 端的 requireChannelKey 会回 10046），
    // 这里不让它发出去；其余频道的资格由服务端裁决，客户端不预判
    canSend: input.channel !== 'PRIVATE' || input.peerId !== null,
    emptyText: conversationsMode
      ? '还没有人给你发过私信'
      : `还没有${channelText(input.channel)}频道的消息`,
  }
}

function idleHint(channel: ChatChannel, peerLabel: string | null): string {
  switch (channel) {
    case 'PRIVATE':
      return peerLabel === null ? '从会话列表选一位再说话' : `与 ${peerLabel} 私聊中`
    case 'WORLD':
      return '世界频道 · 所有人可见'
    case 'ALLIANCE':
      return '联盟频道 · 仅本盟可见'
    case 'SQUAD':
      return '小队频道 · 仅本队可见'
    default:
      return channelText(channel)
  }
}

/**
 * 时刻文本。用设备本地时区渲染服务端时间戳 —— 玩家读的是自己的钟，
 * 与「战报几点打的」同一套换算；不是今天的补上日期，否则"12:07"分不清是哪天。
 */
function clockText(at: number, nowServer: number): string {
  const date = new Date(at)
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  const sameDay = new Date(nowServer).toDateString() === date.toDateString()
  return sameDay ? `${hh}:${mm}` : `${date.getMonth() + 1}-${date.getDate()} ${hh}:${mm}`
}
