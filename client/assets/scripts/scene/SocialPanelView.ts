/**
 * 职责：社交面板 —— 小队 / 联盟 / 互助 / 事件四个页签（B10 §1~§5，验收 1/3/6/7/12）。
 * 依赖：cc（渲染）、game/social/SocialPanel（展示数据组装，已单测）、scene/NodePool。
 *
 * <p><b>本场景不做任何判定</b>（铁律 2、B10 禁止项：不要把权限判断硬编码在代码里）：
 * 按钮灰不灰由服务端下发的权限列表决定，人数上限、红点数、过期与否全部照搬服务端结论。
 *
 * <p><b>验收 1（小队不被稀释）在表现层的落点</b>：加入联盟后小队页签<b>不隐藏、不合并</b>，
 * 而是多一行「本小队是联盟分队，聊天 / 互助 / 集结功能全部保留」。
 * 把小队页签藏进联盟页签是最省事的做法，也正是 B10 明令禁止的那件事 ——
 * 熟人圈子一旦被大组织吃掉，玩家就失去了最紧密的那层关系。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：.scene / .prefab 资产、聊天输入框与频道切换、
 * 长列表 ScrollView、正式美术与红点图标。占位期用 Graphics 色块 + Label，item 已池化。
 */

import { _decorator, Color, Component, EditBox, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, sys, view } from 'cc'
import { buildSocialPanel, canDo, channelText } from '../game/social/SocialPanel'
import type {
  AllianceSection, EventRow, HelpRow, SocialMemberRow, SocialPanelView as SocialData,
} from '../game/social/SocialPanel'
import { CHAT_CHANNELS, CHAT_INPUT_MAX_LENGTH, chatMessageText } from '../game/social/ChatPanel'
import { ChoiceOverlay } from './ChoiceOverlay'
import type { ChatActionChoice } from '../game/session/Choices'
import type { ChatPanelData } from '../game/social/ChatPanel'
import type { AllianceMember, ChatChannel, HelpRequestView, SocialSummaryResp } from '../net/generated/SocialProtocol'
import { ClientReddotTree } from '../game/reddot/ReddotTree'
import { applyCommandButton } from './ArtCatalog'
import { NodePool } from './NodePool'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性。美术方向常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_ALT = new Color(46, 38, 31, 255)
const COLOR_ROW_DISABLED = new Color(32, 29, 27, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_WARNING = new Color(200, 96, 64, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)
/** 红点色。B10 验收 6 要求「一键帮助全部，红点清零」，所以红点必须显眼 */
const COLOR_RED_DOT = new Color(214, 60, 50, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 52
const ROW_GAP = 5
const HEADER_HEIGHT = 104
const PADDING = 16
/** 一屏最多画几行。150 人的联盟名单要靠 ScrollView（编辑器资产） */
const MAX_VISIBLE_ROWS = 8
/** 聊天页签要多留两条横条（频道切换 + 输入行），所以一屏少画三行（实测面板可视高 540） */
const CHAT_VISIBLE_ROWS = 5
/** 聊天页签里消息行整体下移的高度：上面要给频道切换条让位（页签行底 159 / 频道条 122~154） */
const CHAT_TOP_OFFSET = 32
const CHANNEL_BUTTON_WIDTH = 92
const CHANNEL_BUTTON_HEIGHT = 32
const SEND_BUTTON_WIDTH = 92
const SEND_BUTTON_HEIGHT = 36
/**
 * 输入行底边距面板底边的距离。
 *
 * <p>不能贴底：底部导航条（`NavBar`，y=-236、高 52）压在最上面，
 * 量出来的自由区到 -210 为止。72 让输入行落在 -198 之上，与导航留 12px 缝。
 */
const CHAT_INPUT_BOTTOM = 72

type Tab = 'squad' | 'alliance' | 'help' | 'events' | 'chat'

const TABS: ReadonlyArray<{ tab: Tab; text: string; reddotKey: string | null }> = [
  { tab: 'squad', text: '小队', reddotKey: null },
  { tab: 'alliance', text: '联盟', reddotKey: 'social/invite' },
  { tab: 'help', text: '互助', reddotKey: 'social/help' },
  { tab: 'events', text: '事件', reddotKey: 'social/events' },
  // 聊天页签（B22 §五 裁决①：进社交面板的页签，导航 13 项不再加）。
  // 角标读的是既有的事件叶：私信未读本身就是一条未读事件，两处各算一次就会漂移
  { tab: 'chat', text: '聊天', reddotKey: 'social/events' },
]

/** 一行要画的内容。四个页签共用同一套节点结构。 */
interface RowDraft {
  readonly title: string
  readonly titleColor: Color
  readonly detail: string
  readonly value: string
  /** 按钮文本；null 表示这一行没有按钮 */
  readonly actionText: string | null
  readonly actionEnabled: boolean
  readonly actionId: string | null
  readonly actionKind: RowAction
}

type RowAction = 'none' | 'kick' | 'help' | 'helpAll' | 'event' | 'donate' | 'chatPeer' | 'report'
  | 'chatMenu' | 'blocks' | 'friend'

@ccclass('SocialPanelView')
export class SocialPanelView extends Component {
  private data: SocialData | null = null
  /**
   * 我在各层级拥有的权限码，来自 GET /social/permissions。
   *
   * <p>验收 4 要求权限完全配置化，客户端据此置灰按钮。
   * 没有它的话，无权的人点下去只会收到一个 SOCIAL_PERMISSION_DENIED 报错 ——
   * 而「置灰而不是隐藏」正是本项目对不可用功能的统一做法。
   */
  private permissions: readonly string[] = []
  /** 联盟成员缓存。汇总接口不下发它，只有 /alliance/sync 的 diff 会更新它 */
  private readonly allianceMembers: AllianceMember[] = []
  /** 重建面板所需的上一次原始输入。diff 到达时要用它们重新组装，而不是去改已组装好的 data */
  private lastResp: SocialSummaryResp | null = null
  private lastHelps: HelpRequestView[] = []
  private lastOffsetMs = 0
  private tab: Tab = 'squad'
  private pending: {
    resp: SocialSummaryResp; helps: HelpRequestView[]; members: AllianceMember[]; offsetMs: number
  } | null = null

  private rowPool: NodePool | null = null
  private readonly drawnRows: Node[] = []
  private readonly rowActionIds = new Map<Node, string>()
  private readonly tabLabels = new Map<Tab, Label>()
  private readonly tabButtons = new Map<Tab, Node>()
  private readonly tabDots = new Map<Tab, Graphics>()
  private headerLabel: Label | null = null
  private hintLabel: Label | null = null
  /** 与服务端整树下发保持同一个实例；每次刷新后只重画角标。 */
  private reddot: ClientReddotTree | null = null

  // ---------- 聊天页签（B22 §一 1） ----------

  /** 聊天页签的数据。由组合根每次状态变化后整份递过来（面板不自己拼） */
  private chatData: ChatPanelData | null = null
  /** 上一次见到的成功发送计数：变了说明「刚才那条发出去了」，于是清空输入框 */
  private chatSentSeq = -1
  /** 频道切换条 + 输入行的整块容器：只在聊天页签里显示 */
  private chatControls: Node | null = null
  /** 消息行上的动作选择器（举报原因 / 拉黑）：与出战阵容同一个组件 */
  private chatActionPicker: ChoiceOverlay | null = null
  private readonly chatChannelButtons = new Map<ChatChannel, { node: Node, label: Label }>()
  private chatInput: EditBox | null = null
  private sendButton: Node | null = null

  /**
   * 点某一行的动作按钮。权限已由服务端下发的列表裁决过，这里只把 id 与页签归属交出去；
   * 踢人需要知道从小队还是联盟发起，组合根不猜当前组织。
   */
  onRowAction: ((kind: RowAction, id: string, from: 'squad' | 'alliance') => void) | null = null
  /** 点「一键帮助全部」（验收 6）。count 是服务端算好的可帮助条数 */
  onHelpAll: ((count: number) => void) | null = null
  /** 点某个捐献档位 */
  onDonate: ((tier: number) => void) | null = null

  /** 进聊天页签（首次画之前先拉一次历史） */
  onChatEnter: (() => void) | null = null
  /** 切频道（世界/联盟/小队/私聊） */
  onChatChannel: ((channel: ChatChannel) => void) | null = null
  /** 点开一个私聊会话 */
  onChatOpenPeer: ((peerId: string) => void) | null = null
  /** 点发送。文本从输入框读，这里只交出去 */
  onChatSend: ((text: string) => void) | null = null
  /** 点开一条「分享了战报」的消息：把战报 id 交给编排层去拉回放（B22 §一 2） */
  onChatOpenReport: ((reportId: string) => void) | null = null
  /** 点别人发的那条消息上的动作按钮（B22 §一 3：举报 / 拉黑） */
  onChatAction: ((senderId: string, messageId: string) => void) | null = null
  /** 点私聊列表顶部那条「黑名单」（解除拉黑的唯一入口） */
  onChatManageBlocks: (() => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.rowPool = new NodePool(this.node, () => this.createRow(), MAX_VISIBLE_ROWS)
    this.buildHeader(size.height)
    this.buildChatControls(size.height)
    if (this.pending !== null) {
      const pending = this.pending
      this.pending = null
      this.attach(pending.resp, pending.helps, pending.members, pending.offsetMs)
    }
  }

  override onDestroy(): void {
    this.rowPool?.destroy()
    this.rowPool = null
    this.drawnRows.length = 0
    this.rowActionIds.clear()
    this.permissions = []
    this.allianceMembers.length = 0
    this.lastResp = null
    this.lastHelps = []
    this.tabLabels.clear()
    this.tabButtons.clear()
    this.tabDots.clear()
    this.reddot = null
    this.onRowAction = null
    this.onHelpAll = null
    this.onDonate = null
    this.chatData = null
    this.chatControls = null
    this.chatInput = null
    this.sendButton = null
    this.chatChannelButtons.clear()
    this.onChatEnter = null
    this.onChatChannel = null
    this.onChatOpenPeer = null
    this.onChatSend = null
    this.onChatOpenReport = null
    this.onChatAction = null
    this.onChatManageBlocks = null
    this.chatActionPicker?.hide()
    this.chatActionPicker = null
  }

  /**
   * 装载社交汇总。
   *
   * @param offsetMs 服务端时刻 - 本地时刻（core/TimeSync），用于把服务端时间戳换成倒计时
   */
  attach(resp: SocialSummaryResp, helpRequests: readonly HelpRequestView[],
         allianceMembers: readonly AllianceMember[], offsetMs: number): void {
    if (this.rowPool === null) {
      this.pending = { resp, helps: [...helpRequests], members: [...allianceMembers], offsetMs }
      return
    }
    this.allianceMembers.length = 0
    this.allianceMembers.push(...allianceMembers)
    this.lastResp = resp
    this.lastHelps = [...helpRequests]
    this.lastOffsetMs = offsetMs
    this.rebuild()
  }

  /**
   * 装载一次 /alliance/sync 的结果（验收 10：只下发 diff）。
   *
   * <p>客户端把 changed 合进缓存、把 removed 从缓存里摘掉，
   * 然后用合并后的全量列表重画。合并是客户端的职责，
   * 但「谁变了」完全由服务端说了算 —— 本方法不比较任何字段。
   */
  applyAllianceDiff(changed: readonly AllianceMember[], removedIds: readonly string[]): void {
    for (const member of changed) {
      const index = this.allianceMembers.findIndex((item) => item.id === member.id)
      if (index >= 0) {
        this.allianceMembers[index] = member
      } else {
        this.allianceMembers.push(member)
      }
    }
    for (const id of removedIds) {
      const index = this.allianceMembers.findIndex((item) => item.id === id)
      if (index >= 0) {
        this.allianceMembers.splice(index, 1)
      }
    }
    this.rebuild()
  }

  /** 用缓存里的成员列表重新组装一次面板。没有原始响应时什么也不做。 */
  private rebuild(): void {
    if (this.lastResp === null) {
      return
    }
    this.data = buildSocialPanel(this.lastResp, this.lastHelps, this.allianceMembers,
      this.lastOffsetMs, sys.now())
    this.render()
  }

  /** 装载权限列表（GET /social/permissions）。小队与联盟的权限码合在一起传入即可。 */
  attachPermissions(permissions: readonly string[]): void {
    this.permissions = [...permissions]
    this.render()
  }

  /**
   * 装载聊天页签的数据（B22 §一 1）。组合根每次状态变化后整份递过来。
   *
   * <p>这里**不回拉任何东西**：频道、会话、消息、提示行全是组合根算好的结论。
   * 面板自己判断"要不要再拉一次"就会与组合根各记一份状态，
   * 之后必然出现"徽标说 2 条、列表只画 1 条"这类两处不同步。
   */
  attachChat(data: ChatPanelData): void {
    this.chatData = data
    if (data.sentSeq !== this.chatSentSeq) {
      this.chatSentSeq = data.sentSeq
      // 只有发送成功才会推进 sentSeq：失败时玩家打的字要留着（重打一遍是最烦人的失败）
      if (this.chatInput !== null) {
        this.chatInput.string = ''
      }
    }
    if (this.tab === 'chat') {
      this.render()
    }
  }

  /**
   * 绑定服务端权威红点树。
   *
   * <p>社交摘要里的 `redDots` 仍用于文案与动作计数，但页签是否亮只读红点树：
   * 两个来源各画一次会让“导航亮了、页签没亮”这类漂移永远修不干净。
   */
  attachReddot(tree: ClientReddotTree): void {
    this.reddot = tree
    this.renderTabs()
    if (this.hintLabel !== null) {
      this.hintLabel.color = this.socialReddotLit() ? COLOR_WARNING : COLOR_TEXT_DIM
    }
  }

  switchTab(tab: Tab): void {
    if (this.tab === tab) {
      return
    }
    this.tab = tab
    if (tab === 'chat') {
      // 进聊天页签拉一次（世界/联盟/小队三条没有推送，离开又回来的消息只能靠这一拉补上）
      this.onChatEnter?.()
    }
    this.render()
  }

  // ---------- 搭建 ----------

  private buildBackground(width: number, height: number): void {
    const node = new Node('Background')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(width, height))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
  }

  private buildHeader(height: number): void {
    const top = height / 2 - PADDING
    this.headerLabel = this.addLabel(this.node, 'Header', 0, top - 20, COLOR_COPPER_GOLD, 20)
    this.hintLabel = this.addLabel(this.node, 'Hint', 0, top - 46, COLOR_TEXT_DIM, 14)

    const tabWidth = 92
    const startX = -(TABS.length - 1) * tabWidth / 2
    TABS.forEach((item, index) => {
      const node = new Node(`Tab_${item.tab}`)
      node.layer = this.node.layer
      this.node.addChild(node)
      node.setPosition(new Vec3(startX + index * tabWidth, top - 78, 0))
      node.addComponent(UITransform).setContentSize(new Size(tabWidth - 6, 34))
      if (!applyCommandButton(node, item.tab === this.tab ? 'hover' : 'normal',
        tabWidth - 6, 34)) {
        const graphics = node.addComponent(Graphics)
        graphics.fillColor = COLOR_PANEL
        graphics.strokeColor = COLOR_COPPER_GOLD
        graphics.lineWidth = 1
        graphics.roundRect(-(tabWidth - 6) / 2, -17, tabWidth - 6, 34, 5)
        graphics.fill()
        graphics.stroke()
      }
      const label = this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT, 16)
      label.string = item.text
      this.tabLabels.set(item.tab, label)
      this.tabButtons.set(item.tab, node)

      // 红点画在页签右上角。B10 验收 6 要求「红点清零」，所以它必须可见且能被清掉
      const dot = new Node('RedDot')
      dot.layer = node.layer
      node.addChild(dot)
      dot.setPosition(new Vec3((tabWidth - 6) / 2 - 8, 12, 0))
      dot.addComponent(UITransform).setContentSize(new Size(14, 14))
      const dotGraphics = dot.addComponent(Graphics)
      dotGraphics.fillColor = COLOR_RED_DOT
      dotGraphics.rect(-7, -7, 14, 14)
      dotGraphics.fill()
      dot.active = false
      this.tabDots.set(item.tab, dotGraphics)

      const tab = item.tab
      node.on('touch-start', (_event: EventTouch) => this.switchTab(tab), this)
    })
  }

  private createRow(): Node {
    const node = new Node('SocialRow')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
    graphics.fill()

    const title = this.addLabel(node, 'Title', -PANEL_WIDTH / 2 + PADDING, 11, COLOR_TEXT, 17)
    title.horizontalAlign = Label.HorizontalAlign.LEFT
    title.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    title.node.getComponent(UITransform)?.setContentSize(new Size(380, 24))
    title.overflow = Label.Overflow.SHRINK
    const detail = this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING, -11, COLOR_TEXT_DIM, 13)
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    detail.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    detail.node.getComponent(UITransform)?.setContentSize(new Size(380, 20))
    detail.overflow = Label.Overflow.SHRINK
    const value = this.addLabel(node, 'Value', PANEL_WIDTH / 2 - 120, 0, COLOR_COPPER_GOLD, 15)
    value.horizontalAlign = Label.HorizontalAlign.RIGHT
    value.node.getComponent(UITransform)?.setAnchorPoint(1, 0.5)

    const action = new Node('ActionButton')
    action.layer = node.layer
    node.addChild(action)
    action.setPosition(new Vec3(PANEL_WIDTH / 2 - 44, 0, 0))
    action.addComponent(UITransform).setContentSize(new Size(72, 30))
    if (!applyCommandButton(action, 'normal', 72, 30)) {
      const actionGraphics = action.addComponent(Graphics)
      actionGraphics.fillColor = COLOR_PANEL
      actionGraphics.strokeColor = COLOR_COPPER_GOLD
      actionGraphics.lineWidth = 1
      actionGraphics.roundRect(-36, -15, 72, 30, 4)
      actionGraphics.fill()
      actionGraphics.stroke()
    }
    const caption = this.addLabel(action, 'Caption', 0, 0, COLOR_TEXT, 13)
    caption.string = ''
    return node
  }

  /**
   * 聊天页签的频道切换条与输入行（B22 §一 1）。
   *
   * <p>两块一起建、一起显隐：**输入框是整块面板里唯一会吃掉键盘的东西**，
   * 在别的页签上还留着它，玩家在城建页里就会莫名其妙地弹出键盘。
   *
   * <p>占位期用运行时构造的 `EditBox`（不依赖编辑器资产）：它自己会补一个
   * TEXT_LABEL / PLACEHOLDER_LABEL 子节点，背景由我们自己画一块色块 ——
   * 正式输入框与键盘行为仍要在编辑器/真机里过一遍。
   */
  private buildChatControls(height: number): void {
    const container = new Node('ChatControls')
    container.layer = this.node.layer
    this.node.addChild(container)
    container.active = false
    this.chatControls = container

    // 频道切换条：页签行（159~193）之下、消息行（122 起）之上
    const top = height / 2 - PADDING
    const barY = top - HEADER_HEIGHT - ROW_HEIGHT / 2 + 14
    const startX = -(CHAT_CHANNELS.length - 1) * CHANNEL_BUTTON_WIDTH / 2
    CHAT_CHANNELS.forEach((channel, index) => {
      const node = new Node(`Channel_${channel.key}`)
      node.layer = container.layer
      container.addChild(node)
      node.setPosition(new Vec3(startX + index * CHANNEL_BUTTON_WIDTH, barY, 0))
      node.addComponent(UITransform).setContentSize(new Size(CHANNEL_BUTTON_WIDTH - 6,
        CHANNEL_BUTTON_HEIGHT))
      if (!applyCommandButton(node, 'normal', CHANNEL_BUTTON_WIDTH - 6, CHANNEL_BUTTON_HEIGHT)) {
        const graphics = node.addComponent(Graphics)
        graphics.fillColor = COLOR_PANEL
        graphics.strokeColor = COLOR_COPPER_GOLD
        graphics.lineWidth = 1
        graphics.roundRect(-(CHANNEL_BUTTON_WIDTH - 6) / 2, -CHANNEL_BUTTON_HEIGHT / 2,
          CHANNEL_BUTTON_WIDTH - 6, CHANNEL_BUTTON_HEIGHT, 5)
        graphics.fill()
        graphics.stroke()
      }
      const label = this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT_DIM, 15)
      label.string = channel.text
      this.chatChannelButtons.set(channel.key, { node, label })
      node.on('touch-start', (_event: EventTouch) => this.onChatChannel?.(channel.key), this)
    })

    // 输入行：屏幕底部，左输入框右发送
    const inputY = -height / 2 + CHAT_INPUT_BOTTOM + SEND_BUTTON_HEIGHT / 2
    const inputWidth = PANEL_WIDTH - SEND_BUTTON_WIDTH - 32
    const background = new Node('ChatInputBg')
    background.layer = container.layer
    container.addChild(background)
    background.setPosition(new Vec3(-(SEND_BUTTON_WIDTH + 16) / 2, inputY, 0))
    background.addComponent(UITransform).setContentSize(new Size(inputWidth, SEND_BUTTON_HEIGHT))
    const backgroundGraphics = background.addComponent(Graphics)
    backgroundGraphics.fillColor = COLOR_PANEL
    backgroundGraphics.strokeColor = COLOR_COPPER_GOLD
    backgroundGraphics.lineWidth = 1
    backgroundGraphics.roundRect(-inputWidth / 2, -SEND_BUTTON_HEIGHT / 2, inputWidth,
      SEND_BUTTON_HEIGHT, 5)
    backgroundGraphics.fill()
    backgroundGraphics.stroke()

    const input = new Node('ChatInput')
    input.layer = container.layer
    container.addChild(input)
    input.setPosition(new Vec3(-(SEND_BUTTON_WIDTH + 16) / 2, inputY, 0))
    input.addComponent(UITransform).setContentSize(new Size(inputWidth - 16, SEND_BUTTON_HEIGHT))
    const box = input.addComponent(EditBox)
    box.placeholder = '说点什么…'
    box.inputMode = EditBox.InputMode.SINGLE_LINE
    // **必须显式设置**：真实实现的默认上限是 20 个字符，不设就会把玩家的半句话静默截断
    box.maxLength = CHAT_INPUT_MAX_LENGTH
    if (box.textLabel !== null) {
      applySystemUiFont(box.textLabel)
      box.textLabel.fontSize = 16
      box.textLabel.color = COLOR_TEXT
      box.textLabel.overflow = Label.Overflow.SHRINK
    }
    if (box.placeholderLabel !== null) {
      applySystemUiFont(box.placeholderLabel)
      box.placeholderLabel.fontSize = 16
      box.placeholderLabel.color = COLOR_TEXT_DIM
    }
    // 键盘上的"发送/回车"与右侧按钮走同一条路：单行输入里回车就是发送
    input.on(EditBox.EventType.EDITING_RETURN, () => this.sendChatDraft(), this)
    this.chatInput = box

    const send = new Node('ChatSend')
    send.layer = container.layer
    container.addChild(send)
    send.setPosition(new Vec3((PANEL_WIDTH - SEND_BUTTON_WIDTH) / 2 - 8, inputY, 0))
    send.addComponent(UITransform).setContentSize(new Size(SEND_BUTTON_WIDTH, SEND_BUTTON_HEIGHT))
    if (!applyCommandButton(send, 'normal', SEND_BUTTON_WIDTH, SEND_BUTTON_HEIGHT)) {
      const graphics = send.addComponent(Graphics)
      graphics.fillColor = COLOR_PANEL
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 1
      graphics.roundRect(-SEND_BUTTON_WIDTH / 2, -SEND_BUTTON_HEIGHT / 2, SEND_BUTTON_WIDTH,
        SEND_BUTTON_HEIGHT, 5)
      graphics.fill()
      graphics.stroke()
    }
    const sendLabel = this.addLabel(send, 'Caption', 0, 0, COLOR_TEXT, 16)
    sendLabel.string = '发送'
    send.on('touch-start', (_event: EventTouch) => this.sendChatDraft(), this)
    this.sendButton = send
  }

  /**
   * 消息行上的动作选择器（B22 §一 3）。选项由编排层给出（它才知道我拉黑过谁），这里只画和回调。
   */
  showChatActionPicker(options: readonly ChatActionChoice[],
                       onPick: (choice: ChatActionChoice) => void): void {
    if (this.chatActionPicker === null) {
      this.chatActionPicker = new ChoiceOverlay(this.node, '这条消息', 620)
    }
    this.chatActionPicker.show(options, (id) => {
      const choice = options.find((option) => option.id === id)
      if (choice !== undefined) {
        onPick(choice)
      }
    })
  }

  /** 行里带的 id 是 messageId，而举报要连发信人一起交出去 —— 从最近一次聊天数据里查即可。 */
  private senderIdOf(messageId: string): string {
    for (const row of this.chatData?.messages ?? []) {
      if (row.messageId === messageId) {
        return row.senderId
      }
    }
    return ''
  }


  /** 把输入框里的文字交出去。**不清空**：清不清由组合根说了算（失败要留着让玩家重试）。 */
  private sendChatDraft(): void {
    this.onChatSend?.(this.chatInput?.string ?? '')
  }

  private addLabel(parent: Node, name: string, x: number, y: number, color: Color, fontSize: number): Label {
    const node = new Node(name)
    node.layer = parent.layer
    parent.addChild(node)
    node.addComponent(UITransform)
    node.setPosition(new Vec3(x, y, 0))
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = ''
    label.color = color
    label.fontSize = fontSize
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    return label
  }

  // ---------- 渲染 ----------

  private render(): void {
    const data = this.data
    const pool = this.rowPool
    if (data === null || pool === null) {
      return
    }
    this.renderTabs()
    this.renderChatControls()

    if (this.tab === 'chat') {
      const chat = this.chatData
      if (this.headerLabel !== null) {
        this.headerLabel.string = chat === null ? '聊天' : `聊天 · ${channelText(chat.channel)}`
      }
      if (this.hintLabel !== null) {
        // 失败原因（限流、没资格）就画在这一行上：面板里没有别的地方能承载它
        this.hintLabel.string = chat?.hintText ?? '聊天加载中…'
        this.hintLabel.color = chat?.hintIsWarning === true ? COLOR_WARNING : COLOR_TEXT_DIM
      }
      // 只画**最近**这一屏：消息按时间升序，而聊天要看的永远是"最新的几条" ——
      // 直接 slice(0, n) 会画最早的那几条，症状是"自己刚发的消息不出现"（世界频道一热闹就必现）。
      // 探针 tools/verify-chat-runtime.mjs 逮到过这一条：别人的历史一多，新消息就掉出窗口
      const drafts = chat === null ? [] : chatDrafts(chat)
      this.drawRows(drafts.slice(-CHAT_VISIBLE_ROWS), CHAT_VISIBLE_ROWS, CHAT_TOP_OFFSET)
      return
    }

    const drafts = this.draftsFor(data)
    if (this.headerLabel !== null) {
      this.headerLabel.string = this.headerText(data)
    }
    if (this.hintLabel !== null) {
      this.hintLabel.string = this.hintText(data)
      this.hintLabel.color = this.socialReddotLit() ? COLOR_WARNING : COLOR_TEXT_DIM
    }
    this.drawRows(drafts, MAX_VISIBLE_ROWS, 0)
  }

  /** 池化地画一批行。`topOffset` 给聊天页签上面的频道条让出位置。 */
  private drawRows(drafts: readonly RowDraft[], limit: number, topOffset: number): void {
    const pool = this.rowPool
    if (pool === null) {
      return
    }
    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2 - topOffset
    pool.releaseAll(this.drawnRows)
    this.drawnRows.length = 0
    this.rowActionIds.clear()

    drafts.slice(0, limit).forEach((draft, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnRows.push(node)
      this.renderRow(node, draft, index)
    })
  }

  /**
   * 频道切换条与输入组的显隐与状态。
   *
   * <p>未读数直接写在按钮上（B22 验收 2：私聊页签亮红点 + 未读数 +1）——
   * 红点是"有没有"，里那一层要的是"有几条"。
   */
  private renderChatControls(): void {
    const visible = this.tab === 'chat'
    if (this.chatControls !== null) {
      this.chatControls.active = visible
    }
    if (!visible) {
      return
    }
    const data = this.chatData
    for (const channel of CHAT_CHANNELS) {
      const item = this.chatChannelButtons.get(channel.key)
      if (item === undefined) {
        continue
      }
      const tab = data?.channelTabs.find(candidate => candidate.key === channel.key)
      const selected = tab?.selected === true
      const unread = tab?.unread ?? 0
      item.label.string = unread > 0 ? `${channel.text}（${unread}）` : channel.text
      item.label.color = selected ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
      applyCommandButton(item.node, selected ? 'hover' : 'normal', CHANNEL_BUTTON_WIDTH - 6,
        CHANNEL_BUTTON_HEIGHT)
    }
    if (this.sendButton !== null) {
      // 私聊没选对象时发送是灰的：那一次请求在结构上就缺 toPlayerId
      applyCommandButton(this.sendButton, data?.canSend === true ? 'normal' : 'disabled',
        SEND_BUTTON_WIDTH, SEND_BUTTON_HEIGHT)
    }
  }

  private renderTabs(): void {
    for (const [tab, label] of this.tabLabels) {
      label.color = tab === this.tab ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
      const button = this.tabButtons.get(tab)
      if (button !== undefined) {
        applyCommandButton(button, tab === this.tab ? 'hover' : 'normal', 86, 34)
      }
    }
    for (const item of TABS) {
      this.setDot(item.tab,
        this.reddot !== null && item.reddotKey !== null && this.reddot.isLit(item.reddotKey))
    }
  }

  private socialReddotLit(): boolean {
    return this.reddot?.isLit('social') === true
  }

  private setDot(tab: Tab, visible: boolean): void {
    const dot = this.tabDots.get(tab)
    if (dot !== undefined) {
      dot.node.active = visible
    }
  }

  private headerText(data: SocialData): string {
    switch (this.tab) {
      case 'squad':
        return data.squad.joined ? data.squad.title : '小队'
      case 'alliance':
        return data.alliance.joined ? data.alliance.title : '联盟'
      case 'help':
        return `互助 · ${data.helpRemainingText}`
      case 'events':
        return `社交事件 ${data.events.length} 条`
      case 'chat':
        // 聊天页签的表头在 render 里单独给（它要的是频道名，不是社交区块）
        return '聊天'
    }
  }

  private hintText(data: SocialData): string {
    switch (this.tab) {
      case 'squad':
        // 验收 1：分队身份要与小队身份并列显示，而不是把小队藏进联盟
        return data.squad.subSquadText ?? data.squad.capHint ?? ''
      case 'alliance': {
        const parts = [data.alliance.fundText, data.alliance.contributionText, data.alliance.donateText]
        return parts.filter((part) => part.length > 0).join(' · ')
      }
      case 'help':
        // 验收 6：一键帮助要跳过已帮过的，所以按钮上写的是「实际会帮到几条」
        return data.helpAllCount > 0
          ? `一键帮助全部（${data.helpAllCount} 条，已跳过帮过的）`
          : '没有可帮助的请求'
      case 'events':
        return '过期事件已置灰：三小时前的求援已经支援不上了'
      case 'chat':
        return ''
    }
  }

  private draftsFor(data: SocialData): RowDraft[] {
    switch (this.tab) {
      case 'squad':
        return memberDrafts(data.squad.members, canDo(this.permissions, 'KICK_MEMBER'))
      case 'alliance':
        return allianceDrafts(data.alliance, this.permissions)
      case 'help':
        return helpDrafts(data.helpRows)
      case 'events':
        return eventDrafts(data.events)
      case 'chat':
        // 聊天页签的行来自聊天数据（chatDrafts），这里不会再被调到
        return []
    }
  }

  private renderRow(node: Node, draft: RowDraft, index: number): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      graphics.fillColor = draft.actionEnabled || draft.actionText === null
        ? (index % 2 === 0 ? COLOR_ROW : COLOR_ROW_ALT)
        : COLOR_ROW_DISABLED
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
      graphics.fill()
    }
    const title = node.children[0]?.getComponent(Label)
    const detail = node.children[1]?.getComponent(Label)
    const value = node.children[2]?.getComponent(Label)
    if (title !== undefined && title !== null) {
      title.string = draft.title
      title.color = draft.titleColor
    }
    if (detail !== undefined && detail !== null) {
      detail.string = draft.detail
    }
    if (value !== undefined && value !== null) {
      value.string = draft.value
    }

    const button = node.children[3]
    if (button === undefined) {
      return
    }
    button.off('touch-start')
    const visible = draft.actionText !== null
    button.active = visible
    if (!visible) {
      return
    }
    applyCommandButton(button, draft.actionEnabled ? 'normal' : 'disabled', 72, 30)
    const caption = button.children[0]?.getComponent(Label)
    if (caption !== undefined && caption !== null) {
      caption.string = draft.actionText
      // 置灰而不是隐藏：权限不足时玩家要看得见这个功能存在，
      // 否则他会以为整个游戏没有这个玩法
      caption.color = draft.actionEnabled ? COLOR_TEXT : COLOR_TEXT_DIM
    }
    if (!draft.actionEnabled || draft.actionId === null) {
      return
    }
    const id = draft.actionId
    const kind = draft.actionKind
    this.rowActionIds.set(node, id)
    button.on('touch-start', (_event: EventTouch) => {
      if (kind === 'chatPeer' || kind === 'friend') {
        this.onChatOpenPeer?.(id)
        return
      }
      if (kind === 'report') {
        this.onChatOpenReport?.(id)
        return
      }
      if (kind === 'chatMenu') {
        this.onChatAction?.(this.senderIdOf(id), id)
        return
      }
      if (kind === 'blocks') {
        this.onChatManageBlocks?.()
        return
      }
      if (kind === 'helpAll') {
        this.onHelpAll?.(this.data?.helpAllCount ?? 0)
        return
      }
      if (kind === 'donate') {
        this.onDonate?.(Number(id))
        return
      }
      this.onRowAction?.(kind, id, this.tab === 'alliance' ? 'alliance' : 'squad')
    }, this)
  }
}

// ---------- 各页签的行组装 ----------

/**
 * 聊天页签的行。
 *
 * <p>两种形态：私聊未选对象时画**会话列表**（按对象分组 + 未读计数），其余画**消息**。
 * 消息正文放在 detail 槽而不是 title：它是长文本，而 title 是大字号，
 * 长消息在 title 里会被 SHRINK 缩到看不清（多行气泡与 ScrollView 属编辑器资产那一批）。
 */
function chatDrafts(data: ChatPanelData): RowDraft[] {
  if (data.mode === 'conversations') {
    const rows = data.conversations.map((row): RowDraft => ({
      title: row.label,
      titleColor: row.unread > 0 ? COLOR_TEXT : COLOR_TEXT_DIM,
      detail: row.unread > 0 ? `未读 ${row.unread} 条` : '已读',
      value: row.timeText,
      actionText: '打开',
      actionEnabled: true,
      actionId: row.peerId,
      actionKind: 'chatPeer',
    }))
    for (const friend of data.friends) {
      // 关注列表是**发起**私聊的入口：会话列表只有"别人先找过我"的那些人
      rows.push({
        title: friend.online ? `${friend.name} · 在线` : friend.name,
        titleColor: friend.online ? COLOR_GOOD : COLOR_TEXT,
        detail: `关注的人 · ${friend.presenceText}`,
        value: '',
        actionText: '私聊',
        actionEnabled: true,
        actionId: friend.peerId,
        actionKind: 'friend',
      })
    }
    if (data.blockedCount > 0) {
      // 解除拉黑的唯一入口：拉黑之后那个人的消息就看不见了，"再点他一条消息"是够不着的
      rows.unshift({
        title: `黑名单（${data.blockedCount}）`,
        titleColor: COLOR_TEXT_DIM,
        detail: '点开可以取消拉黑',
        value: '',
        actionText: '管理',
        actionEnabled: true,
        actionId: 'blocks',
        actionKind: 'blocks',
      })
    }
    if (rows.length === 0) {
      return [infoRow(data.emptyText)]
    }
    return rows
  }
  if (data.messages.length === 0) {
    return [infoRow(data.emptyText)]
  }
  return data.messages.map((message): RowDraft => ({
    title: message.author,
    // 自己的消息用铜金：一眼能分出"我说的"和"别人说的"，而这一行没有气泡可用
    titleColor: message.mine ? COLOR_COPPER_GOLD : COLOR_TEXT,
    // 分享战报的正文里带一段给代码看的标记（[report:id]），显示时摘掉
    detail: chatMessageText(message),
    value: message.timeText,
    // 别人发的消息统一带一个动作按钮：分享战报的那条是「打开」，其余是「举报」（拉黑在它旁边一层选择里）
    actionText: message.reportId !== null ? '打开' : (message.mine ? null : '举报'),
    actionEnabled: true,
    actionId: message.reportId !== null ? message.reportId : message.messageId,
    actionKind: message.reportId !== null ? 'report' : (message.mine ? 'none' : 'chatMenu'),
  }))
}

/** 一行不可点的说明（空列表）。面板没有别的空态展示位。 */
function infoRow(text: string): RowDraft {
  return {
    title: text, titleColor: COLOR_TEXT_DIM, detail: '', value: '',
    actionText: null, actionEnabled: false, actionId: null, actionKind: 'none',
  }
}

function memberDrafts(members: readonly SocialMemberRow[], mayKick: boolean): RowDraft[] {
  return members.map((member): RowDraft => ({
    title: `${member.name} · ${member.roleText}`,
    // 不活跃的成员标灰：盟主/队长据此决定要不要补人，
    // 而一个挂名不上线的成员提供不了任何庇护（B10 关键设计点 2）
    titleColor: member.inactive ? COLOR_TEXT_DIM : COLOR_TEXT,
    detail: [member.powerText, member.activeText, member.squadText, member.contributionText]
      .filter((part): part is string => part !== null && part.length > 0)
      .join(' · '),
    value: '',
    actionText: '踢出',
    // 能不能踢由服务端下发的权限列表裁决（验收 4）。
    // 置灰而不是隐藏：看不见「踢人」这个功能存在，玩家会以为游戏根本没有它
    actionEnabled: mayKick,
    actionId: member.id,
    actionKind: 'kick',
  }))
}

function allianceDrafts(alliance: AllianceSection, permissions: readonly string[]): RowDraft[] {
  const out: RowDraft[] = []
  if (!alliance.joined) {
    return out
  }
  out.push({
    title: `${alliance.levelText} · ${alliance.memberText}`,
    titleColor: COLOR_COPPER_GOLD,
    detail: [alliance.territoryText, alliance.myRoleText, alliance.expandText]
      .filter((part): part is string => part !== null && part.length > 0)
      .join(' · '),
    value: alliance.fundText,
    actionText: null,
    actionEnabled: false,
    actionId: null,
    actionKind: 'none',
  })
  // 捐献三档：档位用完了就不摆按钮，摆了点了只会看到报错
  const tierNames = ['免费捐献', '资源捐献', '金币捐献']
  const mayDonate = canDo(permissions, 'DONATE')
  alliance.donateTiersAvailable.forEach((tier) => {
    out.push({
      title: tierNames[tier] ?? `捐献档位 ${tier}`,
      titleColor: COLOR_TEXT,
      detail: '资金与贡献值同步增加（验收 8）',
      value: '',
      actionText: '捐献',
      actionEnabled: mayDonate,
      actionId: String(tier),
      actionKind: 'donate',
    })
  })
  const mayKick = canDo(permissions, 'KICK_MEMBER')
  for (const member of alliance.members) {
    out.push(...memberDrafts([member], mayKick))
  }
  return out
}

function helpDrafts(rows: readonly HelpRow[]): RowDraft[] {
  const out: RowDraft[] = rows.map((row): RowDraft => ({
    title: row.title,
    titleColor: row.alreadyHelped ? COLOR_TEXT_DIM : COLOR_TEXT,
    detail: `${row.detail} · ${row.countdownText}`,
    value: '',
    actionText: '帮助',
    // 已帮过的置灰：一键帮助会跳过它，单独点也不该重复消耗每日额度
    actionEnabled: !row.alreadyHelped,
    actionId: row.requestId,
    actionKind: 'help' as RowAction,
  }))
  // 「一键帮助全部」固定在最后一行（验收 6）
  out.push({
    title: '一键帮助全部',
    titleColor: COLOR_GOOD,
    detail: '跳过已帮过的，用满今日剩余额度',
    value: '',
    actionText: '全部',
    actionEnabled: out.some((row) => row.actionEnabled),
    actionId: 'all',
    actionKind: 'helpAll',
  })
  return out
}

function eventDrafts(events: readonly EventRow[]): RowDraft[] {
  return events.map((event): RowDraft => ({
    title: event.title,
    // 验收 12：过期事件置灰且不可跳转。三小时前的求援点进去只会看到一片废墟
    titleColor: event.expired ? COLOR_TEXT_DIM : COLOR_WARNING,
    detail: [event.body, event.timeText, event.coordText]
      .filter((part): part is string => part !== null && part.length > 0)
      .join(' · '),
    value: '',
    actionText: event.expired ? null : '前往',
    actionEnabled: !event.expired,
    actionId: event.eventId,
    actionKind: 'event' as RowAction,
  }))
}
