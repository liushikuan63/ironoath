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

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, sys, view } from 'cc'
import { buildSocialPanel, canDo } from '../game/social/SocialPanel'
import type {
  AllianceSection, EventRow, HelpRow, SocialMemberRow, SocialPanelView as SocialData,
} from '../game/social/SocialPanel'
import type { AllianceMember, HelpRequestView, SocialSummaryResp } from '../net/generated/SocialProtocol'
import { NodePool } from './NodePool'

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

type Tab = 'squad' | 'alliance' | 'help' | 'events'

const TABS: ReadonlyArray<{ tab: Tab; text: string }> = [
  { tab: 'squad', text: '小队' },
  { tab: 'alliance', text: '联盟' },
  { tab: 'help', text: '互助' },
  { tab: 'events', text: '事件' },
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

type RowAction = 'none' | 'kick' | 'help' | 'helpAll' | 'event' | 'donate'

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
  private readonly tabDots = new Map<Tab, Graphics>()
  private headerLabel: Label | null = null
  private hintLabel: Label | null = null

  /** 点某一行的动作按钮。权限已由服务端下发的列表裁决过，这里只把 id 交出去 */
  onRowAction: ((kind: RowAction, id: string) => void) | null = null
  /** 点「一键帮助全部」（验收 6）。count 是服务端算好的可帮助条数 */
  onHelpAll: ((count: number) => void) | null = null
  /** 点某个捐献档位 */
  onDonate: ((tier: number) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.rowPool = new NodePool(this.node, () => this.createRow(), MAX_VISIBLE_ROWS)
    this.buildHeader(size.height)
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
    this.tabDots.clear()
    this.onRowAction = null
    this.onHelpAll = null
    this.onDonate = null
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

  switchTab(tab: Tab): void {
    if (this.tab === tab) {
      return
    }
    this.tab = tab
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
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = COLOR_PANEL
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 1
      graphics.roundRect(-(tabWidth - 6) / 2, -17, tabWidth - 6, 34, 5)
      graphics.fill()
      graphics.stroke()
      const label = this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT, 16)
      label.string = item.text
      this.tabLabels.set(item.tab, label)

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
    const detail = this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING, -11, COLOR_TEXT_DIM, 13)
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    const value = this.addLabel(node, 'Value', PANEL_WIDTH / 2 - 120, 0, COLOR_COPPER_GOLD, 15)
    value.horizontalAlign = Label.HorizontalAlign.RIGHT

    const action = new Node('ActionButton')
    action.layer = node.layer
    node.addChild(action)
    action.setPosition(new Vec3(PANEL_WIDTH / 2 - 44, 0, 0))
    action.addComponent(UITransform).setContentSize(new Size(72, 30))
    const actionGraphics = action.addComponent(Graphics)
    actionGraphics.fillColor = COLOR_PANEL
    actionGraphics.strokeColor = COLOR_COPPER_GOLD
    actionGraphics.lineWidth = 1
    actionGraphics.roundRect(-36, -15, 72, 30, 4)
    actionGraphics.fill()
    actionGraphics.stroke()
    const caption = this.addLabel(action, 'Caption', 0, 0, COLOR_TEXT, 13)
    caption.string = ''
    return node
  }

  private addLabel(parent: Node, name: string, x: number, y: number, color: Color, fontSize: number): Label {
    const node = new Node(name)
    node.layer = parent.layer
    parent.addChild(node)
    node.addComponent(UITransform)
    node.setPosition(new Vec3(x, y, 0))
    const label = node.addComponent(Label)
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
    this.renderTabs(data)

    const drafts = this.draftsFor(data)
    if (this.headerLabel !== null) {
      this.headerLabel.string = this.headerText(data)
    }
    if (this.hintLabel !== null) {
      this.hintLabel.string = this.hintText(data)
      this.hintLabel.color = data.redDots.any ? COLOR_WARNING : COLOR_TEXT_DIM
    }

    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    pool.releaseAll(this.drawnRows)
    this.drawnRows.length = 0
    this.rowActionIds.clear()

    drafts.slice(0, MAX_VISIBLE_ROWS).forEach((draft, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnRows.push(node)
      this.renderRow(node, draft, index)
    })
  }

  private renderTabs(data: SocialData): void {
    for (const [tab, label] of this.tabLabels) {
      label.color = tab === this.tab ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
    }
    // 红点数由服务端给出，客户端不自己数列表（B10 验收 6）
    this.setDot('help', data.redDots.helps > 0)
    this.setDot('events', data.redDots.unreadEvents > 0)
    this.setDot('alliance', data.redDots.invites > 0)
    this.setDot('squad', false)
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
      if (kind === 'helpAll') {
        this.onHelpAll?.(this.data?.helpAllCount ?? 0)
        return
      }
      if (kind === 'donate') {
        this.onDonate?.(Number(id))
        return
      }
      this.onRowAction?.(kind, id)
    }, this)
  }
}

// ---------- 各页签的行组装 ----------

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
