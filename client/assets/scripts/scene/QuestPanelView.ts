/**
 * 职责：任务面板 —— 任务列表、领奖、以及「三选一送将」的选择弹窗（B12 §1，收口清单 #98/#99）。
 * 依赖：cc（渲染）、game/quest/QuestPanel（展示数据组装与选择流程，已单测）、scene/NodePool。
 *
 * <p><b>本场景不做任何判定</b>（铁律 2）：能不能领（`claimable`）、可不可以做（`locked`）、
 * 有没有候选（`heroChoices`）全部来自服务端的 `QuestView`。这与 StagePanelView 同一条纪律。
 *
 * <p><b>三选一是「两步」而不是「一个按钮」</b>：带候选的任务不选就领会被服务端拒
 * （它刻意不替玩家默认挑一个 —— 那会让三选一变成系统内定）。所以点击路径是
 * 「点领取 → 若 intent 是 choose 则弹选择 → 选定后调 onClaim(questId, heroId)」，
 * 这段流程本身在 {@link claimIntentOf} / {@link chosenClaimReq} 里，
 * 本场景只负责把弹窗画出来并把玩家的选择递出去。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：.scene / .prefab 资产、长列表的 ScrollView、
 * 正式美术。列表 item 已按 B07 §4 池化。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, Sprite, UITransform, Vec2, Vec3, view } from 'cc'
import { buildQuestList, candidateLabel, candidatePreview, chosenClaimReq, claimIntentOf } from '../game/quest/QuestPanel'
import {
  clampPage, contentPerPage, pageCount, pageNotice, pageWindow,
} from '../game/ui/PanelPaging'
import type { ClaimIntent, HeroChoicePrompt, QuestListView, QuestRow } from '../game/quest/QuestPanel'
import type { QuestListResp } from '../net/generated/QuestProtocol'
import type { ActivityListResp } from '../net/generated/ActivityProtocol'
import { buildActivityList } from '../game/activity/ActivityPanel'
import type { ActivityListView, ActivityRow } from '../game/activity/ActivityPanel'
import { NodePool } from './NodePool'
import { applyAnyIconSprite, applySlicedSprite, ensureFamily } from './ArtCatalog'
import { activityIconKey, heroPortraitKey, PANEL_IRON_INSET } from '../game/art/ArtFamilies'
import { applySystemUiFont } from './UiFont'
import { applyDialogButton, createDialogScroll, dialogContentRect, paintCompactDialogFrame } from './DialogStyle'
import { dialogLayout } from '../game/ui/DialogLayout'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性（与 StagePanelView 同一套）。美术方向常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(20, 17, 15, 238)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_DIM = new Color(32, 29, 27, 255)
const COLOR_COPPER_GOLD = new Color(218, 179, 107, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(185, 174, 151, 255)
const COLOR_CLAIMABLE = new Color(218, 179, 107, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 76
const ROW_GAP = 8
/**
 * 顶部带的高度：表头 + 页签行。64 时表头（22 号）与页签行（14 号）的字形带只差 21px，
 * 小于 (22+14)/2+4 = 23 —— 页签底板还会压住表名下沿（横扫的"字形相碰"那一维量出来的，见台账 #388）。
 */
const HEADER_HEIGHT = 76
/** 到账回执最多两行，显示时为它留出独立净区。 */
const RECEIPT_HEIGHT = 40
const PADDING = 16
/** 底部导航条留出来的高度（与军队/商店/战令同一口径）：多画的行会被它盖住。 */
const BOTTOM_RESERVED = 68
/** 一屏最多画几行。超出的要靠 ScrollView（编辑器里补），占位期截断显示并说明 */
const MAX_VISIBLE_ROWS = 8
/**
 * 行左侧那一列图标位（26 宽图标 + 8 间隙）。**三个页签共用同一个文本起点**：
 * 只有活动页真的画图，任务/成就页留空 —— 否则切页签时整列文字会横向跳 34px。
 */
const ROW_ICON_COLUMN = 34
/** 三选一弹窗里每个候选按钮的高度与间距 */
const PROMPT_TITLE_HEIGHT = 28
const PROMPT_HINT_HEIGHT = 22

/**
 * 三个页签。顺序即从左到右。
 *
 * <p><b>活动为什么不进导航条</b>（B17 §五④ 的裁决）：导航已有 13 项，第 14 项会把底栏压薄；
 * 而活动与任务语义相邻（都是"有目标可做"的清单）。成就是任务的一种 questType，天然属于这里。
 */
const TABS = [
  { key: 'quest', label: '任务' },
  { key: 'signin', label: '签到' },
  { key: 'activity', label: '活动' },
  { key: 'achievement', label: '成就' },
  { key: 'firstkill', label: '首杀图鉴' },
] as const
type TabKey = typeof TABS[number]['key']
const TAB_HEIGHT = 34

@ccclass('QuestPanelView')
export class QuestPanelView extends Component {
  private list: QuestListView | null = null
  private pendingList: QuestListResp | null = null
  /** 活动页数据。null = 还没拉到（页签切过去时显示提示，不假装"没有活动"） */
  private activity: ActivityListView | null = null
  private pendingActivity: { resp: ActivityListResp, nowMs: number } | null = null
  /** 当前页签。默认任务页 —— 玩家点开这一格最常看的是任务 */
  private tab: TabKey = 'quest'
  private activityPool: NodePool | null = null
  private readonly drawnActivity: Node[] = []
  /** 页签按钮 → 它的 key。点击派发靠它，不靠下标（下标在加页签时会错位） */
  private readonly tabNodes = new Map<Node, TabKey>()
  private readonly tabLabels = new Map<Node, Label>()
  /** 领奖回执那一行（「已领取：金币 ×500」）：玩家想知道的是刚才领到了什么 */
  private receiptLabel: Label | null = null

  private rowPool: NodePool | null = null
  /** 行节点 → 它当前代表哪条任务。池化复用后靠它把点击派回正确的 questId */
  private readonly rowQuestIds = new Map<Node, string>()
  private readonly drawnRows: Node[] = []
  private headerLabel: Label | null = null
  private overflowLabel: Label | null = null
  /** 当前页（0 起）。四个页签共用一格：换页签归零（`switchTab`），重新 attach 只 clamp（`render`）。 */
  private page = 0
  private prevPageButton: Node | null = null
  private nextPageButton: Node | null = null
  private prevPageCaption: Label | null = null
  private nextPageCaption: Label | null = null
  private canPrev = false
  private canNext = false

  private promptPanel: Node | null = null
  private promptTitle: Label | null = null
  private promptHint: Label | null = null
  private promptScroll: ReturnType<typeof createDialogScroll> | null = null
  private promptCancel: Node | null = null
  private promptConfirm: Node | null = null
  private selectedHeroId: string | null = null
  /** 弹窗里每个候选按钮 → 它的 heroId。每次开弹窗重建，避免残留上一条任务的候选 */
  private readonly optionHeroIds = new Map<Node, string>()
  private prompt: HeroChoicePrompt | null = null
  private promptLayoutKey = ''
  private viewportLayoutKey = ''

  /** 玩家要领某条任务的奖励。`heroChoice` 为 null 表示这条任务没有候选（直接领） */
  onClaim: ((questId: string, heroChoice: string | null) => void) | null = null
  /** 玩家要领某条活动的奖励。windowKey 由服务端判，这里只递 activityId */
  onClaimActivity: ((activityId: string) => void) | null = null
  onActivityOpened: (() => void) | null = null
  /** 三选一真实开关交给装配层，让引导提示让出选择操作，遮罩仍由Guide管理。 */
  onPromptVisibility: ((visible: boolean) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.rowPool = new NodePool(this.node, () => this.createRow(), MAX_VISIBLE_ROWS)
    this.activityPool = new NodePool(this.node, () => this.createActivityRow(), MAX_VISIBLE_ROWS)
    this.buildHeader(size.height)
    this.buildTabs(size.height)
    this.buildPrompt()
    if (this.pendingList !== null) {
      const pending = this.pendingList
      this.pendingList = null
      this.attach(pending)
    }
    if (this.pendingActivity !== null) {
      const pending = this.pendingActivity
      this.pendingActivity = null
      this.attachActivity(pending.resp, pending.nowMs)
    }
    // 活动族图不进启动预载（ArtCatalog 的族加载纪律）：面板打开时拉一次，
    // 到货后重画当前页；拉不到就保持 Graphics 占位，行不会空出来。
    ensureFamily('activity').then((loaded) => {
      if (loaded > 0 && this.isValid) {
        this.render()
      }
    })
    ensureFamily('hero').then((loaded) => {
      if (loaded > 0 && this.isValid && this.promptPanel?.active) this.layoutPrompt()
    })
  }

  override onDestroy(): void {
    this.hidePrompt()
    this.onPromptVisibility = null
    this.rowPool?.destroy()
    this.rowPool = null
    this.activityPool?.destroy()
    this.activityPool = null
    this.drawnActivity.length = 0
    this.tabNodes.clear()
    this.tabLabels.clear()
    this.onClaimActivity = null
    this.onActivityOpened = null
    this.drawnRows.length = 0
    this.rowQuestIds.clear()
    this.optionHeroIds.clear()
    this.onClaim = null
  }

  override update(): void {
    const size = view.getVisibleSize(), area = dialogContentRect(this.node)
    const viewportKey = `${size.width}:${size.height}:${area.x}:${area.y}:${area.width}:${area.height}`
    if (viewportKey !== this.viewportLayoutKey) {
      this.viewportLayoutKey = viewportKey
      this.node.getComponent(UITransform)?.setContentSize(size.width, size.height)
      const background = this.node.getChildByName('Background')
      background?.getComponent(UITransform)?.setContentSize(size.width, size.height)
      const graphics = background?.getComponent(Graphics)
      if (graphics !== null && graphics !== undefined) {
        graphics.clear()
        graphics.fillColor = COLOR_BACKGROUND
        graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
        graphics.fill()
      }
      const top = Math.min(size.height / 2, area.y + area.height)
      const centerX = area.x + area.width / 2
      this.headerLabel?.node.setPosition(new Vec3(centerX, top - PADDING - 20, 0))
      this.receiptLabel?.node.setPosition(new Vec3(centerX, top - PADDING - HEADER_HEIGHT - RECEIPT_HEIGHT / 2, 0))
      const tabWidth = PANEL_WIDTH / TABS.length
      let index = 0
      for (const [tab] of this.tabNodes) {
        tab.setPosition(new Vec3(centerX - PANEL_WIDTH / 2 + tabWidth * (index++ + 0.5),
          top - PADDING - HEADER_HEIGHT + TAB_HEIGHT / 2 + 6, 0))
      }
      // 同一几何只重画一次；复用现有页签监听与选择窗，不触发关闭或领奖。
      this.render()
    }
    if (this.promptPanel === null || !this.promptPanel.active || this.prompt === null) return
    // NodePool会把后来取出的Rows追加到Quest末尾；弹窗开着时仍须守住层级。
    this.keepPromptAboveRows()
    const key = `${size.width}:${size.height}:${area.x}:${area.y}:${area.width}:${area.height}`
    if (key !== this.promptLayoutKey) this.layoutPrompt()
  }

  /** 装载任务列表。行顺序照搬服务端（它按章节/类型稳定排序）。 */
  attach(resp: QuestListResp): void {
    if (this.rowPool === null) {
      this.pendingList = resp
      return
    }
    this.list = buildQuestList(resp)
    // 与 #354 那条「回执被自己触发的刷新抹掉」同形状，但这一处是良性的，判据两条：
    // 三选一是玩家点「领取」时按 intent 现算出来的（`handleClaimClick`），不来自任何写回执；
    // 登录预拉、进入任务页与 `claimQuest` 都会刷新这里；切回面板时读取权威进度，领奖后关闭已消费的选择弹窗。
    this.hidePrompt()
    this.render()
  }

  /**
   * 装载活动页数据。**与任务分开走**：活动是懒拉的（首次切到活动页才拉，见 GameBootstrap），
   * 所以它可能比任务晚到，视图必须能"先记住再画"（与任务的 pending 同一条手法）。
   */
  attachActivity(resp: ActivityListResp, nowMs: number): void {
    if (this.activityPool === null) {
      this.pendingActivity = { resp, nowMs }
      return
    }
    this.activity = buildActivityList(resp, nowMs)
    if (this.tab === 'activity' || this.tab === 'signin') {
      this.render()
    }
  }

  /** 领奖回执：「已领取：金币 ×500、一小时训练令 ×2」。空字符串清掉那一行。 */
  showReceipt(text: string): void {
    if (this.receiptLabel !== null) {
      this.receiptLabel.string = text
      this.render()
    }
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
    this.headerLabel = this.addLabel(this.node, 'Header', 0, height / 2 - PADDING - 20, COLOR_COPPER_GOLD, 22)
    this.receiptLabel = this.addLabel(this.node, 'Receipt', 0,
      height / 2 - PADDING - HEADER_HEIGHT - RECEIPT_HEIGHT / 2, COLOR_CLAIMABLE, 14)
    this.receiptLabel.node.getComponent(UITransform)?.setContentSize(PANEL_WIDTH - PADDING * 2, RECEIPT_HEIGHT)
    this.receiptLabel.lineHeight = 20
    this.receiptLabel.overflow = Label.Overflow.CLAMP
    this.receiptLabel.enableWrapText = true
    this.overflowLabel = this.addLabel(this.node, 'Overflow', 0,
      height / 2 - PADDING - HEADER_HEIGHT - MAX_VISIBLE_ROWS * (ROW_HEIGHT + ROW_GAP) - 12,
      COLOR_TEXT_DIM, 14)
    // 两颗翻页键与那句页码同一行、摆在两端（中间那句「第 1/7 页 · 共 47 条」约 210px，
    // 面板 680 宽、键占到 ±(262~326)，横向不碰）。y 由 render 跟着最后一行走。
    this.prevPageButton = this.buildPagerButton('PrevPageButton', -PANEL_WIDTH / 2 + 46)
    this.nextPageButton = this.buildPagerButton('NextPageButton', PANEL_WIDTH / 2 - 46)
  }

  /** 一颗 64×28 的翻页键，照本文件行上「领取」那颗的画法。 */
  private buildPagerButton(name: string, x: number): Node {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(64, 28))
    node.setPosition(new Vec3(x, 0, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 1
    graphics.roundRect(-32, -14, 64, 28, 4)
    graphics.fill()
    graphics.stroke()
    const caption = this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT, 13)
    caption.string = name === 'PrevPageButton' ? '上一页' : '下一页'
    // 建出来先收着：render() 在两份列表都没到时早退，不先收就会在"数据没到 / 读失败"
    // 那一态露出两颗点了没反应的键（#345 口径，#449 与 #450 各修过一次同一族）
    node.active = false
    if (name === 'PrevPageButton') {
      this.prevPageCaption = caption
      node.on('touch-start', () => this.turnPage(-1), this)
    } else {
      this.nextPageCaption = caption
      node.on('touch-start', () => this.turnPage(1), this)
    }
    return node
  }

  /**
   * 翻一页。灰掉的那一侧直接不吃：`clampPage` 也会把越界的页号夹回来，
   * 但"点了什么反应都没有"正是 #345 那条口径要挡的观感，所以在入口处就判。
   */
  private turnPage(delta: number): void {
    if (delta < 0 && !this.canPrev) return
    if (delta > 0 && !this.canNext) return
    this.page += delta
    this.render()
  }

  /** 页码与两颗键跟着这一屏最后画出的那行走；只有一页时整对收掉（#345）。 */
  private paintPager(total: number, pages: number, rowY: number): void {
    const area = dialogContentRect(this.node)
    const centerX = area.x + area.width / 2
    const paged = pages > 1
    this.canPrev = this.page > 0
    this.canNext = this.page < pages - 1
    if (this.overflowLabel !== null) {
      this.overflowLabel.string = paged ? `${pageNotice(this.page, pages)} · 共 ${total} 条` : ''
      this.overflowLabel.node.setPosition(new Vec3(centerX, rowY, 0))
    }
    for (const [button, caption, usable] of [
      [this.prevPageButton, this.prevPageCaption, this.canPrev],
      [this.nextPageButton, this.nextPageCaption, this.canNext],
    ] as Array<[Node | null, Label | null, boolean]>) {
      if (button === null || caption === null) continue
      button.active = paged
      button.setPosition(new Vec3(centerX + (button === this.prevPageButton ? -1 : 1) * (PANEL_WIDTH / 2 - 46), rowY, 0))
      caption.color = usable ? COLOR_TEXT : COLOR_TEXT_DIM
      applyDialogButton(button, usable, 80, 32)
    }
  }

  private createRow(): Node {
    const node = new Node('QuestRow')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
    graphics.fill()

    const title = this.addLabel(node, 'Title', -PANEL_WIDTH / 2 + PADDING + ROW_ICON_COLUMN, 12, COLOR_TEXT, 19)
    title.node.getComponent(UITransform)?.setContentSize(PANEL_WIDTH - 280, 22)
    title.lineHeight = 22
    title.overflow = Label.Overflow.SHRINK
    title.enableWrapText = false
    title.horizontalAlign = Label.HorizontalAlign.LEFT
    title.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    const detail = this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING + ROW_ICON_COLUMN, -14, COLOR_TEXT_DIM, 14)
    detail.node.getComponent(UITransform)?.setContentSize(PANEL_WIDTH - 280, 30)
    detail.fontSize = 13
    detail.lineHeight = 15
    detail.overflow = Label.Overflow.SHRINK
    detail.enableWrapText = true
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    detail.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    const status = this.addLabel(node, 'Status', PANEL_WIDTH / 2 - 160, 0, COLOR_TEXT, 15)
    status.node.getComponent(UITransform)?.setContentSize(96, 24)
    status.lineHeight = 20
    status.overflow = Label.Overflow.SHRINK
    status.enableWrapText = false
    status.horizontalAlign = Label.HorizontalAlign.RIGHT

    // 一个「领取」按钮：不可领时隐藏（灰着却能点只会让玩家一直点然后以为卡了）
    const button = new Node('ClaimButton')
    button.layer = node.layer
    node.addChild(button)
    button.setPosition(new Vec3(PANEL_WIDTH / 2 - 62, 0, 0))
    button.addComponent(UITransform).setContentSize(new Size(92, 36))
    const buttonGraphics = button.addComponent(Graphics)
    buttonGraphics.fillColor = COLOR_PANEL
    buttonGraphics.strokeColor = COLOR_COPPER_GOLD
    buttonGraphics.lineWidth = 1
    buttonGraphics.roundRect(-38, -16, 76, 32, 4)
    buttonGraphics.fill()
    buttonGraphics.stroke()
    const caption = this.addLabel(button, 'Caption', 0, 0, COLOR_TEXT, 15)
    caption.string = '领取'
    applyDialogButton(button, true, 92, 36, true)
    return node
  }

  /** 三选一弹窗：一层面板 + 一行提示 + N 个候选按钮。默认隐藏，点「领取」且确有候选时才开。 */
  private buildPrompt(): void {
    const panel = new Node('HeroChoicePanel')
    panel.layer = this.node.layer
    this.node.addChild(panel)
    panel.addComponent(UITransform)
    // 空白吞触摸；候选正文滚动，取消与确认固定在框内，短屏也有完整退出入口。
    panel.on('touch-start', (_event: EventTouch) => undefined)
    // Sprite独占底板节点，不能与Graphics争Cocos登记的首个UIRenderer。
    const plate = new Node('DialogFrameArt')
    plate.layer = panel.layer
    panel.addChild(plate)
    plate.addComponent(UITransform)
    this.promptPanel = panel

    this.promptTitle = this.addLabel(panel, 'PromptTitle', 0, 0, COLOR_COPPER_GOLD, 20)
    this.promptHint = this.addLabel(panel, 'PromptHint', 0, 0, COLOR_TEXT, 16)
    this.promptHint.string = '滑动比较武将资料，选中后确认领取（只能选一次）'
    this.promptScroll = createDialogScroll(panel, 'HeroChoiceViewport', 1, 1, 0, 0)
    this.promptCancel = new Node('HeroChoiceCancel')
    this.promptConfirm = new Node('HeroChoiceConfirm')
    for (const button of [this.promptCancel, this.promptConfirm]) {
      button.layer = panel.layer
      panel.addChild(button)
      button.addComponent(UITransform)
      button.addComponent(Graphics)
      this.addLabel(button, 'Caption', 0, 0, COLOR_TEXT, 18)
    }
    this.promptCancel.on('touch-end', () => this.hidePrompt())
    this.promptCancel.getChildByName('Caption')!.getComponent(Label)!.string = '取消'
    panel.active = false
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

  /**
   * 页签条：贴在标题下面一排。**触摸命中按 UITransform 矩形算**，所以按钮各自要有 UITransform
   * （少这一层时点了没反应，而代码看起来一切正常）。
   */
  private buildTabs(height: number): void {
    const y = height / 2 - PADDING - HEADER_HEIGHT + TAB_HEIGHT / 2 + 6
    const width = PANEL_WIDTH / TABS.length
    TABS.forEach((tab, index) => {
      const node = new Node(`Tab-${tab.key}`)
      node.layer = this.node.layer
      this.node.addChild(node)
      node.setPosition(new Vec3(-PANEL_WIDTH / 2 + width * (index + 0.5), y, 0))
      node.addComponent(UITransform).setContentSize(new Size(width - 8, TAB_HEIGHT))
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = COLOR_PANEL
      graphics.roundRect(-(width - 8) / 2, -TAB_HEIGHT / 2, width - 8, TAB_HEIGHT, 4)
      graphics.fill()
      const label = this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT_DIM, 16)
      label.string = tab.label
      this.tabNodes.set(node, tab.key)
      this.tabLabels.set(node, label)
      node.on('touch-start', (_event: EventTouch) => this.switchTab(tab.key))
    })
    this.renderTabs()
  }

  /** 切换页签：只改状态与高亮，数据由外层决定要不要补拉（本场景不碰网络）。 */
  private switchTab(key: TabKey): void {
    if (this.tab === key) {
      return
    }
    this.tab = key
    if (key === 'activity' || key === 'signin') this.onActivityOpened?.()
    // 三个页签是三份不同长度的列表，留着上一个页签的页号会停在错位的位置上
    //（与 `attach` 相反：那里只 clamp，领奖后重新 attach 不该把玩家弹回第一页）
    this.page = 0
    this.hidePrompt()
    this.render()
  }

  /** 当前页签高亮。每一帧重画一次而不是记住"上一个是谁"—— 少一处状态就少一种不同步。 */
  private renderTabs(): void {
    for (const [node, key] of this.tabNodes) {
      const label = this.tabLabels.get(node)
      if (label !== undefined) {
        const active = key === this.tab
        label.color = active ? COLOR_COPPER_GOLD : COLOR_TEXT
        applyDialogButton(node, true, PANEL_WIDTH / TABS.length - 8, TAB_HEIGHT, active)
      }
    }
  }

  // ---------- 渲染 ----------

  /**
   * 这一屏没东西可翻：两颗键整对收掉。
   *
   * <p>为什么单独一个方法：三个页签共用一格行位与一对键，而 `render` 在两份列表还没到时是
   * 早退的 —— 从"活动有 12 条、正翻在第 2 页"切到数据未到的任务页，早退会让上一屏留下的那颗
   * 亮键继续留在屏上，点了什么都不发生（#345 定的口径）。
   */
  private hidePager(): void {
    this.canPrev = false
    this.canNext = false
    if (this.prevPageButton !== null) {
      this.prevPageButton.active = false
    }
    if (this.nextPageButton !== null) {
      this.nextPageButton.active = false
    }
  }

  private render(): void {
    this.renderTabs()
    // 两个池都先归还：切页签时上一页的行必须消失（各页只归还自己那一半的话，
    // 从活动页切到任务/成就页会留下一屏活动行 —— 2026-09-16 由面板量具抓到）
    this.rowPool?.releaseAll(this.drawnRows)
    this.drawnRows.length = 0
    this.activityPool?.releaseAll(this.drawnActivity)
    this.drawnActivity.length = 0
    if (this.tab === 'activity' || this.tab === 'signin') {
      this.renderActivity()
      return
    }
    const list = this.list
    const pool = this.rowPool
    if (list === null || pool === null) {
      this.hidePager()
      return
    }
    // 任务页不含成就、成就页只看成就（两个页签的数据来自同一份响应，只是过滤不同）
    const rows = list.rows.filter(row => this.tab === 'achievement'
      ? row.type === 'ACHIEVEMENT'
      : this.tab === 'firstkill' ? row.type === 'FIRST_KILL'
      : row.type !== 'ACHIEVEMENT' && row.type !== 'FIRST_KILL')
    const rowHeight = this.tab === 'firstkill' ? 80 : ROW_HEIGHT
    const size = view.getVisibleSize()
    const area = dialogContentRect(this.node)
    const receiptSpace = this.receiptLabel?.string ? RECEIPT_HEIGHT + ROW_GAP : 0
    const topY = Math.min(size.height / 2, area.y + area.height) - PADDING - HEADER_HEIGHT - receiptSpace - rowHeight / 2
    // 行数按实测可视高度算，不写死：① 可视高度随窗口/机型变；② 多画的行会被底部导航条盖住，
    // 那是"画了但玩家看不见"，比少画一行更难发现（表头带抬到 76 之后这一屏更矮了一档）。
    const navTop = Math.max(-size.height / 2 + BOTTOM_RESERVED, area.y)
    const capacity = Math.max(1, Math.floor((topY + rowHeight / 2 - navTop) / (rowHeight + ROW_GAP)))
    // 共几页、夹到哪一页、切哪一段用同一个 perPage（`PanelPaging` 那条原话）：从前这里
    // slice(0, maxRows) 一刀切、再把「另有 N 条」拼到表头，剩下的任务一条也够不着（#307）。
    const total = rows.length
    const perPage = contentPerPage(total, capacity)
    const pages = pageCount(total, perPage)
    this.page = clampPage(this.page, total, perPage)
    const slice = pageWindow(total, this.page, perPage)
    const visible = rows.slice(slice.start, slice.end)
    if (this.headerLabel !== null) {
      const scope = this.tab === 'achievement' ? '成就' : this.tab === 'firstkill' ? '首杀图鉴' : '任务'
      const claimable = this.tab === 'quest' ? list.claimableText : null
      this.headerLabel.string = `${scope} ${total} 条`
        + (claimable === null ? '' : ` · ${claimable}`)
    }
    visible.forEach((row, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(area.x + area.width / 2, topY - index * (rowHeight + ROW_GAP), 0))
      this.drawnRows.push(node)
      this.renderRow(node, row)
    })
    // 页码与两颗键排在本屏让出来的那一格上（装不下时 `contentPerPage` 少画一行）
    this.paintPager(total, pages, topY - visible.length * (rowHeight + ROW_GAP) - 12)
  }

  /**
   * 活动页。**只消费服务端视图**（铁律 2）：能不能领看 state，进度与剩余时间都是下发的。
   * 没拉到数据时显示"正在加载"而不是"暂无活动" —— 后者会让玩家以为活动下线了。
   */
  private renderActivity(): void {
    const pool = this.activityPool
    if (pool === null) {
      return
    }
    const data = this.activity
    const scope = this.tab === 'signin' ? '签到' : '活动'
    const size = view.getVisibleSize()
    const area = dialogContentRect(this.node)
    const receiptSpace = this.receiptLabel?.string ? RECEIPT_HEIGHT + ROW_GAP : 0
    const topY = Math.min(size.height / 2, area.y + area.height) - PADDING - HEADER_HEIGHT - receiptSpace - ROW_HEIGHT / 2
    if (data === null) {
      if (this.headerLabel !== null) {
        this.headerLabel.string = `${scope} 加载中`
      }
      if (this.overflowLabel !== null) {
        this.overflowLabel.string = '正在拉取活动列表…'
      }
      this.hidePager()
      return
    }
    // 与任务那一页同一口径：行数按实测可视高度算，装不下就翻页而不是只说"另有几条没画下"
    const navTop = Math.max(-size.height / 2 + BOTTOM_RESERVED, area.y)
    const capacity = Math.max(1, Math.floor((topY + ROW_HEIGHT / 2 - navTop) / (ROW_HEIGHT + ROW_GAP)))
    const rows = data.rows.filter(row => this.tab === 'signin' ? row.category === 'SIGN_IN' : row.category !== 'SIGN_IN')
    const total = rows.length
    const perPage = contentPerPage(total, capacity)
    const pages = pageCount(total, perPage)
    this.page = clampPage(this.page, total, perPage)
    const slice = pageWindow(total, this.page, perPage)
    const visible = rows.slice(slice.start, slice.end)
    if (this.headerLabel !== null) {
      const claimable = rows.filter(row => row.claimable).length
      this.headerLabel.string = `${scope} ${total} 条` + (claimable > 0 ? ` · ${claimable} 个奖励可领取` : '')
    }
    visible.forEach((row, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(area.x + area.width / 2, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnActivity.push(node)
      this.renderActivityRow(node, row)
    })
    this.paintPager(total, pages, topY - visible.length * (ROW_HEIGHT + ROW_GAP) - 12)
  }

  private renderActivityRow(node: Node, row: ActivityRow): void {
    this.paintRowSurface(node, ROW_HEIGHT)
    const title = node.getChildByName('Title')?.getComponent(Label)
    const detail = node.getChildByName('Detail')?.getComponent(Label)
    const status = node.getChildByName('Status')?.getComponent(Label)
    if (title !== undefined && title !== null) {
      title.string = row.name
      title.color = COLOR_TEXT
    }
    if (detail !== undefined && detail !== null) {
      detail.string = `${row.progressText}${row.rewardText ? ` · ${row.rewardText}` : ''}\n${row.details ?? row.remainingText}`
    }
    if (status !== undefined && status !== null) {
      status.string = row.statusText
      status.color = row.claimable ? COLOR_CLAIMABLE : COLOR_TEXT_DIM
    }
    const button = node.getChildByName('ClaimButton')
    if (button !== null) {
      button.off('touch-start')
      button.active = row.claimable
      if (row.claimable) {
        button.on('touch-start', (_event: EventTouch) => this.onClaimActivity?.(row.activityId))
      }
    }
    const icon = node.getChildByName('Icon')
    if (icon !== null) {
      const iconKey = activityIconKey(row.activityId)
      icon.active = iconKey !== null && applyAnyIconSprite(icon, iconKey, 26, 26)
    }
  }

  /** 活动行的节点：活动图标 / 标题 / 进度+剩余 / 状态 / 领取按钮。与任务行同宽同高，视觉上是一条流水线。 */
  private createActivityRow(): Node {
    const node = new Node('ActivityRow')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
    graphics.fill()

    const title = this.addLabel(node, 'Title', -PANEL_WIDTH / 2 + PADDING + ROW_ICON_COLUMN, 12, COLOR_TEXT, 19)
    title.node.setPosition(new Vec3(-PANEL_WIDTH / 2 + PADDING + ROW_ICON_COLUMN, 15, 0))
    title.node.getComponent(UITransform)!.setContentSize(PANEL_WIDTH - 280, 22)
    title.lineHeight = 22
    title.overflow = Label.Overflow.SHRINK
    title.enableWrapText = false
    title.horizontalAlign = Label.HorizontalAlign.LEFT
    // 锚点必须挪到左中：addLabel 给的默认锚点是中心，文本框会以 x 为中轴向两边长，
    // 于是四五个字的标题会压到左边那一列图标上（真跑截图里看到的就是这个）。
    title.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    const detail = this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING + ROW_ICON_COLUMN, -14, COLOR_TEXT_DIM, 14)
    detail.node.getComponent(UITransform)!.setContentSize(PANEL_WIDTH - 280, 30)
    detail.fontSize = 13
    detail.lineHeight = 15
    detail.overflow = Label.Overflow.SHRINK
    detail.enableWrapText = true
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    detail.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    const status = this.addLabel(node, 'Status', PANEL_WIDTH / 2 - 160, 0, COLOR_TEXT, 15)
    status.node.getComponent(UITransform)?.setContentSize(96, 24)
    status.node.getComponent(UITransform)!.setContentSize(96, 24)
    status.lineHeight = 20
    status.overflow = Label.Overflow.SHRINK
    status.enableWrapText = false
    status.horizontalAlign = Label.HorizontalAlign.RIGHT

    const button = new Node('ClaimButton')
    button.layer = node.layer
    node.addChild(button)
    button.setPosition(new Vec3(PANEL_WIDTH / 2 - 62, 0, 0))
    button.addComponent(UITransform).setContentSize(new Size(92, 36))
    const buttonGraphics = button.addComponent(Graphics)
    buttonGraphics.fillColor = COLOR_PANEL
    buttonGraphics.strokeColor = COLOR_COPPER_GOLD
    buttonGraphics.lineWidth = 1
    buttonGraphics.roundRect(-38, -16, 76, 32, 4)
    buttonGraphics.fill()
    buttonGraphics.stroke()
    const caption = this.addLabel(button, 'Caption', 0, 0, COLOR_TEXT, 15)
    caption.string = '领取'
    applyDialogButton(button, true, 92, 36, true)
    // 具名读取不依赖装饰节点/图标的兄弟顺序。
    const icon = new Node('Icon')
    icon.layer = node.layer
    node.addChild(icon)
    icon.setPosition(new Vec3(-PANEL_WIDTH / 2 + PADDING + 13, 0, 0))
    icon.addComponent(UITransform).setContentSize(new Size(26, 26))
    return node
  }

  private renderRow(node: Node, row: QuestRow): void {
    const firstKill = row.type === 'FIRST_KILL'
    const height = firstKill ? 80 : ROW_HEIGHT
    node.getComponent(UITransform)?.setContentSize(PANEL_WIDTH, height)
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      // 可领的高亮、锁着的压暗：玩家扫一眼就该看出哪条能动
      graphics.fillColor = row.claimable ? COLOR_ROW : COLOR_ROW_DIM
      graphics.roundRect(-PANEL_WIDTH / 2, -height / 2, PANEL_WIDTH, height, 6)
      graphics.fill()
    }
    this.paintRowSurface(node, height)
    const title = node.getChildByName('Title')?.getComponent(Label)
    const detail = node.getChildByName('Detail')?.getComponent(Label)
    const status = node.getChildByName('Status')?.getComponent(Label)
    if (title !== undefined && title !== null) {
      title.node.setPosition(new Vec3(-PANEL_WIDTH / 2 + PADDING + ROW_ICON_COLUMN, firstKill ? 24 : 15, 0))
      title.string = row.title
      title.color = COLOR_TEXT
    }
    if (detail !== undefined && detail !== null) {
      const parts: string[] = [row.progressText, ...row.rewardLines]
      if (row.lockedHint !== null) {
        parts.push(row.lockedHint)
      }
      detail.node.setPosition(new Vec3(-PANEL_WIDTH / 2 + PADDING + ROW_ICON_COLUMN, firstKill ? -9 : -14, 0))
      detail.node.getComponent(UITransform)?.setContentSize(PANEL_WIDTH - 280, firstKill ? 40 : 30)
      detail.lineHeight = firstKill ? 18 : 15
      detail.string = parts.join(' · ') + (row.firstVictoryText ? `\n${row.firstVictoryText}` : '')
    }
    if (status !== undefined && status !== null) {
      status.string = row.statusText
      status.color = row.claimable ? COLOR_CLAIMABLE : COLOR_TEXT_DIM
    }

    const button = node.getChildByName('ClaimButton')
    if (button !== null) {
      button.off('touch-start')
      button.active = row.claimable
      this.rowQuestIds.set(node, row.questId)
      if (row.claimable) {
        button.on('touch-start', (_event: EventTouch) => this.handleClaimClick(row))
      }
    }
  }

  private paintRowSurface(node: Node, height: number): void {
    let surface = node.getChildByName('RowSurface')
    if (surface === null) {
      surface = new Node('RowSurface')
      surface.layer = node.layer
      node.addChild(surface)
      surface.addComponent(UITransform)
      surface.setSiblingIndex(0)
    }
    const loaded = applySlicedSprite(surface, 'ui.plate.band', PANEL_WIDTH, height)
    surface.active = loaded
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) graphics.enabled = !loaded
    const sprite = surface.getComponent(Sprite)
    if (sprite !== null) sprite.enabled = loaded
  }

  /** 点「领取」：按 intent 分流（直接领 / 先弹三选一 / 什么都不做）。 */
  private handleClaimClick(row: QuestRow): void {
    const intent: ClaimIntent = claimIntentOf(row, row.title)
    switch (intent.kind) {
      case 'claim':
        this.onClaim?.(intent.questId, intent.heroChoice)
        return
      case 'choose':
        this.showPrompt(intent.prompt)
        return
      case 'noop':
        // 状态在点下去之前变了（别处刚领过、或刚被锁）：按钮下次渲染就会自己消失。
        // 这里不弹提示 —— 一条注定失败的操作不值得打断玩家
        return
    }
  }

  // ---------- 三选一弹窗 ----------

  private showPrompt(prompt: HeroChoicePrompt): void {
    const panel = this.promptPanel, wrapped = this.promptScroll
    if (panel === null || wrapped === null || this.promptTitle === null) return
    this.prompt = prompt
    this.selectedHeroId = null
    this.promptTitle.string = prompt.title
    for (const option of Array.from(this.optionHeroIds.keys())) {
      option.removeFromParent()
      option.destroy()
    }
    this.optionHeroIds.clear()
    for (const choice of prompt.candidates) {
      const option = new Node('HeroOption_' + choice.heroId)
      option.layer = panel.layer
      wrapped.content.addChild(option)
      option.addComponent(UITransform)
      option.addComponent(Graphics)
      this.optionHeroIds.set(option, choice.heroId)
      const portrait = new Node('Portrait')
      portrait.layer = option.layer
      option.addChild(portrait)
      portrait.addComponent(UITransform)
      portrait.addComponent(Graphics)
      const portraitArt = new Node('PortraitArt')
      portraitArt.layer = portrait.layer
      portrait.addChild(portraitArt)
      portraitArt.addComponent(UITransform)
      this.addLabel(portrait, 'PortraitFallback', 0, 0, COLOR_TEXT, 14).string = '暂无头像'
      for (const [name, font] of [['Name', 20], ['Attributes', 16], ['Feature', 16],
        ['MainSkill', 16], ['SubSkill', 16]] as const) this.addLabel(option, name, 0, 0, COLOR_TEXT, font)
      // 按下只作为滚动起点，超过拖动阈值由ScrollView取消；抬起选择也不发奖。
      option.on('touch-end', () => {
        if (this.prompt === null || !this.promptPanel?.active) return
        this.selectedHeroId = choice.heroId
        this.layoutPrompt()
      })
    }
    this.layoutPrompt()
    wrapped.scroll.scrollToOffset(new Vec2(0, 0), 0)
    this.keepPromptAboveRows()
    panel.active = true
    this.onPromptVisibility?.(true)
  }

  private readonly onConfirmHero = (): void => {
    const prompt = this.prompt, selected = this.selectedHeroId
    if (prompt === null || selected === null || !this.promptPanel?.active) return
    const req = chosenClaimReq(prompt, selected)
    this.hidePrompt()
    this.onClaim?.(req.questId, req.heroChoice)
  }

  private keepPromptAboveRows(): void {
    const panel = this.promptPanel
    if (panel !== null && this.node.children[this.node.children.length - 1] !== panel) {
      panel.setSiblingIndex(this.node.children.length - 1)
    }
  }

  private layoutPrompt(): void {
    const panel = this.promptPanel, prompt = this.prompt, wrapped = this.promptScroll
    if (panel === null || prompt === null || wrapped === null || this.promptTitle === null
      || this.promptHint === null || this.promptCancel === null || this.promptConfirm === null) return
    const area = dialogContentRect(this.node), size = view.getVisibleSize()
    const contentWidth = PANEL_WIDTH - PADDING * 2 - PANEL_IRON_INSET.left - PANEL_IRON_INSET.right
    const headingHeight = PROMPT_TITLE_HEIGHT + 4 + PROMPT_HINT_HEIGHT + 8
    const footerHeight = 36, footerGap = 12, cardGap = 8
    const wanted = headingHeight + prompt.candidates.length * 200
      + Math.max(0, prompt.candidates.length - 1) * cardGap + footerHeight + footerGap
    // 使用宿主完整安全区；固定取消按钮替代被正文占用的旧页签关闭入口。
    const layout = dialogLayout(area, contentWidth, wanted)
    panel.getComponent(UITransform)!.setContentSize(layout.width, layout.height)
    panel.setPosition(new Vec3(area.x + area.width / 2, layout.centerY, 0))
    const plate = panel.getChildByName('DialogFrameArt')!
    plate.active = applySlicedSprite(plate, 'ui.panel.iron', layout.width, layout.height)
    const fallback = panel.getChildByName('DialogFrameVector')
    if (plate.active) { if (fallback !== null) fallback.active = false }
    else paintCompactDialogFrame(panel, layout.width, layout.height)
    let top = layout.innerTop - layout.centerY
    this.setPromptLabelBox(this.promptTitle, layout.innerWidth, PROMPT_TITLE_HEIGHT, 26)
    this.promptTitle.node.setPosition(new Vec3(0, top - PROMPT_TITLE_HEIGHT / 2, 0))
    top -= PROMPT_TITLE_HEIGHT + 4
    this.setPromptLabelBox(this.promptHint, layout.innerWidth, PROMPT_HINT_HEIGHT, 22)
    this.promptHint.node.setPosition(new Vec3(0, top - PROMPT_HINT_HEIGHT / 2, 0))
    top -= PROMPT_HINT_HEIGHT + 8
    const bottom = layout.innerBottom - layout.centerY
    const viewportHeight = Math.max(1, top - bottom - footerHeight - footerGap)
    const previousOffset = wrapped.scroll.getScrollOffset()
    wrapped.node.getComponent(UITransform)!.setContentSize(layout.innerWidth, viewportHeight)
    wrapped.node.setPosition(new Vec3(0, top - viewportHeight / 2, 0))
    const textWidth = Math.max(1, layout.innerWidth - 112 - PADDING)
    const textCenter = -layout.innerWidth / 2 + 112 + textWidth / 2
    let contentHeight = 0
    for (const choice of prompt.candidates) {
      const option = Array.from(this.optionHeroIds.entries()).find(([, id]) => id === choice.heroId)![0]
      const preview = candidatePreview(choice)
      const lines = [['Name', preview.name], ['Attributes', preview.attributes], ['Feature', preview.feature],
        ['MainSkill', preview.mainSkill], ['SubSkill', preview.subSkill]] as const
      let rowHeight = PADDING
      const measured: { label: Label; height: number }[] = []
      for (const [name, copy] of lines) {
        const label = option.getChildByName(name)!.getComponent(Label)!
        label.string = copy
        label.lineHeight = name === 'Name' ? 26 : 22
        label.horizontalAlign = Label.HorizontalAlign.LEFT
        label.verticalAlign = Label.VerticalAlign.CENTER
        label.overflow = Label.Overflow.RESIZE_HEIGHT
        label.enableWrapText = true
        const box = label.node.getComponent(UITransform)!
        box.setContentSize(textWidth, label.lineHeight)
        label.updateRenderData(true)
        const height = Math.max(label.lineHeight, box.height)
        measured.push({ label, height })
        rowHeight += height + 4
      }
      rowHeight = Math.max(128, rowHeight + PADDING - 4)
      option.setPosition(new Vec3(0, -contentHeight - rowHeight / 2, 0))
      applyDialogButton(option, true, layout.innerWidth, rowHeight, choice.heroId === this.selectedHeroId)
      const art = option.getChildByName('DialogButtonArt')?.getComponent(Sprite)
      if (art?.enabled !== true || art.spriteFrame === null) {
        const graphics = option.getComponent(Graphics)!
        graphics.enabled = true
        graphics.clear()
        graphics.fillColor = COLOR_ROW
        graphics.strokeColor = COLOR_COPPER_GOLD
        graphics.lineWidth = choice.heroId === this.selectedHeroId ? 3 : 1
        graphics.roundRect(-layout.innerWidth / 2, -rowHeight / 2, layout.innerWidth, rowHeight, 6)
        graphics.fill()
        graphics.stroke()
      }
      let y = rowHeight / 2 - PADDING
      for (const { label, height } of measured) {
        label.node.setPosition(new Vec3(textCenter, y - height / 2, 0))
        y -= height + 4
      }
      const portrait = option.getChildByName('Portrait')!
      portrait.setPosition(new Vec3(-layout.innerWidth / 2 + 60, rowHeight / 2 - PADDING - 44, 0))
      portrait.getComponent(UITransform)!.setContentSize(88, 88)
      const key = heroPortraitKey(choice.heroId)
      const portraitArt = portrait.getChildByName('PortraitArt')!
      const loaded = key !== null && applyAnyIconSprite(portraitArt, key, 88, 88)
      portraitArt.active = loaded
      portrait.getChildByName('PortraitFallback')!.active = !loaded
      this.setPromptLabelBox(portrait.getChildByName('PortraitFallback')!.getComponent(Label)!, 80, 28, 20)
      const graphics = portrait.getComponent(Graphics)!
      graphics.enabled = !loaded
      if (!loaded) {
        graphics.clear()
        graphics.fillColor = COLOR_PANEL
        graphics.strokeColor = COLOR_COPPER_GOLD
        graphics.lineWidth = 1
        graphics.rect(-44, -44, 88, 88)
        graphics.fill()
        graphics.stroke()
      }
      contentHeight += rowHeight + cardGap
    }
    contentHeight = Math.max(1, contentHeight - cardGap)
    wrapped.content.getComponent(UITransform)!.setContentSize(layout.innerWidth, contentHeight)
    wrapped.content.setPosition(new Vec3(0, viewportHeight / 2, 0))
    wrapped.scroll.stopAutoScroll()
    wrapped.scroll.scrollToOffset(new Vec2(0, Math.max(0,
      Math.min(previousOffset.y, contentHeight - viewportHeight))), 0)
    const buttonWidth = Math.min(220, (layout.innerWidth - 12) / 2)
    for (const [button, sign] of [[this.promptCancel, -1], [this.promptConfirm, 1]] as const) {
      button.setPosition(new Vec3(sign * (buttonWidth + 12) / 2, bottom + footerHeight / 2, 0))
      applyDialogButton(button, button === this.promptCancel || this.selectedHeroId !== null, buttonWidth, footerHeight)
      this.setPromptLabelBox(button.getChildByName('Caption')!.getComponent(Label)!, buttonWidth - 16, 28, 24)
    }
    this.promptConfirm.off('touch-end', this.onConfirmHero, this)
    const selected = prompt.candidates.find(choice => choice.heroId === this.selectedHeroId)
    this.promptConfirm.getChildByName('Caption')!.getComponent(Label)!.string = selected === undefined
      ? '选择后领取' : '领取 · ' + candidateLabel(selected)
    if (selected !== undefined) this.promptConfirm.on('touch-end', this.onConfirmHero, this)
    this.promptLayoutKey = [size.width, size.height, area.x, area.y, area.width, area.height].join(':')
  }

  private setPromptLabelBox(label: Label, width: number, height: number, lineHeight: number): void {
    label.node.getComponent(UITransform)!.setContentSize(width, height)
    label.lineHeight = lineHeight
    label.overflow = Label.Overflow.SHRINK
    label.enableWrapText = false
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
  }

  private hidePrompt(): void {
    this.prompt = null
    this.selectedHeroId = null
    if (this.promptPanel !== null) {
      this.promptPanel.active = false
    }
    this.onPromptVisibility?.(false)
  }

  /** 当前开着的弹窗（诊断与用例断言用）。 */
  get openPrompt(): HeroChoicePrompt | null {
    return this.prompt
  }
}
