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

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { buildQuestList, candidateLabel, chosenClaimReq, claimIntentOf } from '../game/quest/QuestPanel'
import {
  clampPage, contentPerPage, pageCount, pageNotice, pageWindow,
} from '../game/ui/PanelPaging'
import type { ClaimIntent, HeroChoicePrompt, QuestListView, QuestRow } from '../game/quest/QuestPanel'
import type { QuestListResp } from '../net/generated/QuestProtocol'
import type { ActivityListResp } from '../net/generated/ActivityProtocol'
import { buildActivityList } from '../game/activity/ActivityPanel'
import type { ActivityListView, ActivityRow } from '../game/activity/ActivityPanel'
import { NodePool } from './NodePool'
import { applyAnyIconSprite, ensureFamily } from './ArtCatalog'
import { activityIconKey } from '../game/art/ArtFamilies'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性（与 StagePanelView 同一套）。美术方向常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(20, 17, 15, 238)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_DIM = new Color(32, 29, 27, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_CLAIMABLE = new Color(120, 176, 96, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 62
const ROW_GAP = 6
/**
 * 顶部带的高度：表头 + 页签行。64 时表头（22 号）与页签行（14 号）的字形带只差 21px，
 * 小于 (22+14)/2+4 = 23 —— 页签底板还会压住表名下沿（横扫的"字形相碰"那一维量出来的，见台账 #388）。
 */
const HEADER_HEIGHT = 76
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
const OPTION_HEIGHT = 56
const OPTION_GAP = 8

/**
 * 三个页签。顺序即从左到右。
 *
 * <p><b>活动为什么不进导航条</b>（B17 §五④ 的裁决）：导航已有 13 项，第 14 项会把底栏压薄；
 * 而活动与任务语义相邻（都是"有目标可做"的清单）。成就是任务的一种 questType，天然属于这里。
 */
const TABS = [
  { key: 'quest', label: '任务' },
  { key: 'activity', label: '活动' },
  { key: 'achievement', label: '成就' },
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
  /** 当前页（0 起）。三个页签共用一格：换页签归零（`switchTab`），重新 attach 只 clamp（`render`）。 */
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
  /** 弹窗里每个候选按钮 → 它的 heroId。每次开弹窗重建，避免残留上一条任务的候选 */
  private readonly optionHeroIds = new Map<Node, string>()
  private prompt: HeroChoicePrompt | null = null

  /** 玩家要领某条任务的奖励。`heroChoice` 为 null 表示这条任务没有候选（直接领） */
  onClaim: ((questId: string, heroChoice: string | null) => void) | null = null
  /** 玩家要领某条活动的奖励。windowKey 由服务端判，这里只递 activityId */
  onClaimActivity: ((activityId: string) => void) | null = null

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
  }

  override onDestroy(): void {
    this.rowPool?.destroy()
    this.rowPool = null
    this.activityPool?.destroy()
    this.activityPool = null
    this.drawnActivity.length = 0
    this.tabNodes.clear()
    this.tabLabels.clear()
    this.onClaimActivity = null
    this.drawnRows.length = 0
    this.rowQuestIds.clear()
    this.optionHeroIds.clear()
    this.onClaim = null
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
    // 而能刷新到这里的地方只有 `claimQuest`（选完才发请求，此时关掉弹窗正是对的）与登录预拉（弹窗还不存在）。
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
    if (this.tab === 'activity') {
      this.render()
    }
  }

  /** 领奖回执：「已领取：金币 ×500、一小时训练令 ×2」。空字符串清掉那一行。 */
  showReceipt(text: string): void {
    if (this.receiptLabel !== null) {
      this.receiptLabel.string = text
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
      height / 2 - PADDING - HEADER_HEIGHT + 2, COLOR_CLAIMABLE, 14)
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
    const paged = pages > 1
    this.canPrev = this.page > 0
    this.canNext = this.page < pages - 1
    if (this.overflowLabel !== null) {
      this.overflowLabel.string = paged ? `${pageNotice(this.page, pages)} · 共 ${total} 条` : ''
      this.overflowLabel.node.setPosition(new Vec3(0, rowY, 0))
    }
    for (const [button, caption, usable] of [
      [this.prevPageButton, this.prevPageCaption, this.canPrev],
      [this.nextPageButton, this.nextPageCaption, this.canNext],
    ] as Array<[Node | null, Label | null, boolean]>) {
      if (button === null || caption === null) continue
      button.active = paged
      button.setPosition(new Vec3(button.position.x, rowY, 0))
      caption.color = usable ? COLOR_TEXT : COLOR_TEXT_DIM
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
    title.horizontalAlign = Label.HorizontalAlign.LEFT
    title.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    const detail = this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING + ROW_ICON_COLUMN, -14, COLOR_TEXT_DIM, 14)
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    detail.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    const status = this.addLabel(node, 'Status', PANEL_WIDTH / 2 - 150, 0, COLOR_TEXT, 15)
    status.horizontalAlign = Label.HorizontalAlign.RIGHT

    // 一个「领取」按钮：不可领时隐藏（灰着却能点只会让玩家一直点然后以为卡了）
    const button = new Node('ClaimButton')
    button.layer = node.layer
    node.addChild(button)
    button.setPosition(new Vec3(PANEL_WIDTH / 2 - 62, 0, 0))
    button.addComponent(UITransform).setContentSize(new Size(76, 32))
    const buttonGraphics = button.addComponent(Graphics)
    buttonGraphics.fillColor = COLOR_PANEL
    buttonGraphics.strokeColor = COLOR_COPPER_GOLD
    buttonGraphics.lineWidth = 1
    buttonGraphics.roundRect(-38, -16, 76, 32, 4)
    buttonGraphics.fill()
    buttonGraphics.stroke()
    const caption = this.addLabel(button, 'Caption', 0, 0, COLOR_TEXT, 15)
    caption.string = '领取'
    return node
  }

  /** 三选一弹窗：一层面板 + 一行提示 + N 个候选按钮。默认隐藏，点「领取」且确有候选时才开。 */
  private buildPrompt(): void {
    const panel = new Node('HeroChoicePanel')
    panel.layer = this.node.layer
    this.node.addChild(panel)
    const width = PANEL_WIDTH - PADDING * 2
    const height = HEADER_HEIGHT + MAX_VISIBLE_ROWS / 2 * (OPTION_HEIGHT + OPTION_GAP)
    panel.addComponent(UITransform).setContentSize(new Size(width, height))
    const graphics = panel.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 2
    graphics.roundRect(-width / 2, -height / 2, width, height, 8)
    graphics.fill()
    graphics.stroke()
    this.promptPanel = panel

    this.promptTitle = this.addLabel(panel, 'PromptTitle', 0, height / 2 - 26, COLOR_COPPER_GOLD, 20)
    this.promptHint = this.addLabel(panel, 'PromptHint', 0, height / 2 - 52, COLOR_TEXT_DIM, 14)
    this.promptHint.string = '选择一名武将作为奖励（只能选一次）'
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
        label.color = active ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
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
    if (this.tab === 'activity') {
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
      : row.type !== 'ACHIEVEMENT')
    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    // 行数按实测可视高度算，不写死：① 可视高度随窗口/机型变；② 多画的行会被底部导航条盖住，
    // 那是"画了但玩家看不见"，比少画一行更难发现（表头带抬到 76 之后这一屏更矮了一档）。
    const navTop = -size.height / 2 + BOTTOM_RESERVED
    const capacity = Math.max(1, Math.floor((topY + ROW_HEIGHT / 2 - navTop) / (ROW_HEIGHT + ROW_GAP)))
    // 共几页、夹到哪一页、切哪一段用同一个 perPage（`PanelPaging` 那条原话）：从前这里
    // slice(0, maxRows) 一刀切、再把「另有 N 条」拼到表头，剩下的任务一条也够不着（#307）。
    const total = rows.length
    const perPage = contentPerPage(total, capacity)
    const pages = pageCount(total, perPage)
    this.page = clampPage(this.page, total, perPage)
    const slice = pageWindow(total, this.page, perPage)
    const visible = rows.slice(slice.start, slice.end)
    if (this.headerLabel !== null) {
      const scope = this.tab === 'achievement' ? '成就' : '任务'
      const claimable = this.tab === 'achievement' ? null : list.claimableText
      this.headerLabel.string = `${scope} ${total} 条`
        + (claimable === null ? '' : ` · ${claimable}`)
    }
    visible.forEach((row, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnRows.push(node)
      this.renderRow(node, row)
    })
    // 页码与两颗键排在本屏让出来的那一格上（装不下时 `contentPerPage` 少画一行）
    this.paintPager(total, pages, topY - visible.length * (ROW_HEIGHT + ROW_GAP) - 12)
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
    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    if (data === null) {
      if (this.headerLabel !== null) {
        this.headerLabel.string = '活动 加载中'
      }
      if (this.overflowLabel !== null) {
        this.overflowLabel.string = '正在拉取活动列表…'
      }
      this.hidePager()
      return
    }
    // 与任务那一页同一口径：行数按实测可视高度算，装不下就翻页而不是只说"另有几条没画下"
    const navTop = -size.height / 2 + BOTTOM_RESERVED
    const capacity = Math.max(1, Math.floor((topY + ROW_HEIGHT / 2 - navTop) / (ROW_HEIGHT + ROW_GAP)))
    const total = data.rows.length
    const perPage = contentPerPage(total, capacity)
    const pages = pageCount(total, perPage)
    this.page = clampPage(this.page, total, perPage)
    const slice = pageWindow(total, this.page, perPage)
    const visible = data.rows.slice(slice.start, slice.end)
    if (this.headerLabel !== null) {
      this.headerLabel.string = `活动 ${total} 条`
        + (data.claimableText === null ? '' : ` · ${data.claimableText}`)
    }
    visible.forEach((row, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnActivity.push(node)
      this.renderActivityRow(node, row)
    })
    this.paintPager(total, pages, topY - visible.length * (ROW_HEIGHT + ROW_GAP) - 12)
  }

  private renderActivityRow(node: Node, row: ActivityRow): void {
    const title = node.children[0]?.getComponent(Label)
    const detail = node.children[1]?.getComponent(Label)
    const status = node.children[2]?.getComponent(Label)
    if (title !== undefined && title !== null) {
      title.string = row.name
      title.color = row.claimable ? COLOR_TEXT : COLOR_TEXT_DIM
    }
    if (detail !== undefined && detail !== null) {
      detail.string = `${row.progressText} · ${row.remainingText}`
    }
    if (status !== undefined && status !== null) {
      status.string = row.statusText
      status.color = row.claimable ? COLOR_CLAIMABLE : COLOR_TEXT_DIM
    }
    const button = node.children[3]
    if (button !== undefined) {
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
    title.horizontalAlign = Label.HorizontalAlign.LEFT
    // 锚点必须挪到左中：addLabel 给的默认锚点是中心，文本框会以 x 为中轴向两边长，
    // 于是四五个字的标题会压到左边那一列图标上（真跑截图里看到的就是这个）。
    title.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    const detail = this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING + ROW_ICON_COLUMN, -14, COLOR_TEXT_DIM, 14)
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    detail.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    const status = this.addLabel(node, 'Status', PANEL_WIDTH / 2 - 150, 0, COLOR_TEXT, 15)
    status.horizontalAlign = Label.HorizontalAlign.RIGHT

    const button = new Node('ClaimButton')
    button.layer = node.layer
    node.addChild(button)
    button.setPosition(new Vec3(PANEL_WIDTH / 2 - 62, 0, 0))
    button.addComponent(UITransform).setContentSize(new Size(76, 32))
    const buttonGraphics = button.addComponent(Graphics)
    buttonGraphics.fillColor = COLOR_PANEL
    buttonGraphics.strokeColor = COLOR_COPPER_GOLD
    buttonGraphics.lineWidth = 1
    buttonGraphics.roundRect(-38, -16, 76, 32, 4)
    buttonGraphics.fill()
    buttonGraphics.stroke()
    const caption = this.addLabel(button, 'Caption', 0, 0, COLOR_TEXT, 15)
    caption.string = '领取'
    // Icon 挂在最后：renderActivityRow 按 children 下标取文本，插在中间会错位。
    const icon = new Node('Icon')
    icon.layer = node.layer
    node.addChild(icon)
    icon.setPosition(new Vec3(-PANEL_WIDTH / 2 + PADDING + 13, 0, 0))
    icon.addComponent(UITransform).setContentSize(new Size(26, 26))
    return node
  }

  private renderRow(node: Node, row: QuestRow): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      // 可领的高亮、锁着的压暗：玩家扫一眼就该看出哪条能动
      graphics.fillColor = row.claimable ? COLOR_ROW : COLOR_ROW_DIM
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
      graphics.fill()
    }
    const title = node.children[0]?.getComponent(Label)
    const detail = node.children[1]?.getComponent(Label)
    const status = node.children[2]?.getComponent(Label)
    if (title !== undefined && title !== null) {
      title.string = row.title
      title.color = row.claimable ? COLOR_TEXT : COLOR_TEXT_DIM
    }
    if (detail !== undefined && detail !== null) {
      const parts: string[] = [row.progressText]
      if (row.lockedHint !== null) {
        parts.push(row.lockedHint)
      }
      detail.string = parts.join(' · ')
    }
    if (status !== undefined && status !== null) {
      status.string = row.statusText
      status.color = row.claimable ? COLOR_CLAIMABLE : COLOR_TEXT_DIM
    }

    const button = node.children[3]
    if (button !== undefined) {
      button.off('touch-start')
      button.active = row.claimable
      this.rowQuestIds.set(node, row.questId)
      if (row.claimable) {
        button.on('touch-start', (_event: EventTouch) => this.handleClaimClick(row))
      }
    }
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
    const panel = this.promptPanel
    if (panel === null || this.promptTitle === null) {
      return
    }
    this.prompt = prompt
    this.promptTitle.string = prompt.title
    // 候选按钮每次重建：数量随任务变，而残留的旧按钮会让玩家点到上一条任务的武将
    for (const child of [...panel.children]) {
      const heroId = this.optionHeroIds.get(child)
      if (heroId !== undefined) {
        child.removeFromParent()
        child.destroy()
      }
    }
    this.optionHeroIds.clear()

    const width = PANEL_WIDTH - PADDING * 4
    prompt.candidates.forEach((choice, index) => {
      const option = new Node(`HeroOption_${choice.heroId}`)
      option.layer = panel.layer
      panel.addChild(option)
      const y = panel.getComponent(UITransform)!.height / 2 - HEADER_HEIGHT - OPTION_HEIGHT / 2
        - index * (OPTION_HEIGHT + OPTION_GAP)
      option.setPosition(new Vec3(0, y, 0))
      option.addComponent(UITransform).setContentSize(new Size(width, OPTION_HEIGHT))
      const graphics = option.addComponent(Graphics)
      graphics.fillColor = COLOR_ROW
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 1
      graphics.roundRect(-width / 2, -OPTION_HEIGHT / 2, width, OPTION_HEIGHT, 6)
      graphics.fill()
      graphics.stroke()
      const label = this.addLabel(option, 'Name', 0, 0, COLOR_TEXT, 18)
      label.string = candidateLabel(choice)
      this.optionHeroIds.set(option, choice.heroId)
      option.on('touch-start', (_event: EventTouch) => {
        // 选中的必须在候选里：chosenClaimReq 会本地校验（不合法时抛出，
        // 而这里只画按钮，选不中候选意味着界面出了问题 —— 抛出比继续更诚实）
        const req = chosenClaimReq(prompt, choice.heroId)
        this.hidePrompt()
        this.onClaim?.(req.questId, req.heroChoice)
      })
    })
    panel.active = true
  }

  private hidePrompt(): void {
    this.prompt = null
    if (this.promptPanel !== null) {
      this.promptPanel.active = false
    }
  }

  /** 当前开着的弹窗（诊断与用例断言用）。 */
  get openPrompt(): HeroChoicePrompt | null {
    return this.prompt
  }
}
