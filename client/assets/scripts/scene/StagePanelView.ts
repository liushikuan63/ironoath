/**
 * 职责：关卡面板 —— 章节关卡列表、挑战结算、批量扫荡结算（B09 §三/§6，验收 1、9）。
 * 依赖：cc（渲染）、game/stage/StagePanel（展示数据组装，已单测）、scene/NodePool。
 *
 * <p><b>本场景不做任何判定</b>（铁律 2）：能不能打由服务端的 {@code unlocked} 决定，
 * 星级用服务端下发的总数，体力够不够由服务端结算时裁定。场景只把结论画出来。
 *
 * <p><b>未解锁的关卡必须显示原因</b>：一个灰掉的关卡不说明为什么，玩家会以为是 bug
 * （B08 的同一条纪律：绝不静默失败）。lockedReason 原样展示，一个字不改。
 *
 * <p><b>扫荡最多 10 次只发 1 次请求</b>（验收 9）：本场景的「扫荡 ×10」按钮只调一次
 * {@link StagePanelView#onSweep}，由适配层发一个 count=10 的请求。
 * 逐次发 10 个请求的话，每一次都要走幂等、加锁、结算，弱网下会有几次超时，
 * 玩家看到的是「扫荡了 7 次」这种无法解释的结果。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：.scene / .prefab 资产、长列表的 ScrollView、
 * 出战阵容与武将的选择界面、正式美术。列表 item 已按 B07 §4 池化。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import {
  buildChallengeSummary, buildStageList, buildSweepSummary,
} from '../game/stage/StagePanel'
import type { StageListView, StageRow } from '../game/stage/StagePanel'
import { buildStaminaBoard } from '../game/stage/StaminaBoard'
import type { StaminaBoardView } from '../game/stage/StaminaBoard'
import type { StaminaBuyResp, StaminaResp } from '../net/generated/Protocol'
import {
  clampPage, contentPerPage, pageCount, pageNotice, pageWindow,
} from '../game/ui/PanelPaging'
import type { LineupChoice } from '../game/session/Choices'
import type { ChallengeStageResp, StageListResp, SweepResp } from '../net/generated/StageProtocol'
import { ChoiceOverlay } from './ChoiceOverlay'
import { NodePool } from './NodePool'
import { applySystemUiFont, oneLineFloorHeight } from './UiFont'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性。美术方向常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(20, 17, 15, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_LOCKED = new Color(32, 29, 27, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_STAR = new Color(232, 190, 92, 255)
const COLOR_WARNING = new Color(200, 96, 64, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 74
const ROW_GAP = 6
/** 体力那一条的高度（两行左对齐文字 + 右边一颗键） */
const BAND_HEIGHT = 60
/** 顶部占位 = 表头那一行 + 与体力条的间距 + 体力条 + 它下面的间距 */
const HEADER_HEIGHT = 22 + 20 + BAND_HEIGHT + 20
/** 体力条里两行文字的可用宽：右边还要留给「买体力」那颗键 */
const BAND_TEXT_WIDTH = 520
const PADDING = 16
/** 一屏最多画几行。超出的要靠 ScrollView（编辑器里补），占位期截断显示并说明 */
const MAX_VISIBLE_ROWS = 7
/** 摘要出现时行区留给文字的右边界：再往右是星级与按钮那一列 */
const ROW_TEXT_WIDTH = PANEL_WIDTH - PADDING * 2 - 150
/** 结算摘要那块框的高度：它占掉底部，行区就要按剩下的空间收 */
const SUMMARY_HEIGHT = 240
/**
 * 屏幕底部要给导航条让出的高度 = 8（下边距）+ 52（`PanelNav.BAR_HEIGHT`）+ 8（安全间隙）。
 * 与 `ArmyPanelView` 里那个同名常量是同一次量出来的（设计分辨率 960×640，面板画的是整屏矩形，
 * 越界的内容会被导航条盖住 —— "画了但玩家看不见"）。
 */
const BOTTOM_RESERVED = 68
/**
 * 扫荡按钮的两个次数。上下界来自协议对 SweepReq.count 的约束（1~10，超过直接拒绝）：
 * 「1」是单次确认，「10」是上限一键。这不是游戏数值，是协议允许的输入范围的两个端点。
 */
const SWEEP_ONCE = 1
const SWEEP_MAX = 10

/** 行内按钮对应的动作。 */
type RowAction = 'challenge' | 'sweep1' | 'sweep10'

@ccclass('StagePanelView')
export class StagePanelView extends Component {
  private list: StageListView | null = null
  private pendingList: StageListResp | null = null

  private rowPool: NodePool | null = null
  /** 行内按钮 → 它是哪个动作。池化节点复用时这层对应关系不变，所以建节点时登记一次就够 */
  private readonly buttonKinds = new Map<Node, RowAction>()
  private readonly drawnRows: Node[] = []
  private headerLabel: Label | null = null
  private overflowLabel: Label | null = null
  /** 当前页（0 起）。`attach` 拿到新一份列表时归零，翻页只改它。 */
  private page = 0
  /** 两颗翻页键：整行画完再按 `pages > 1` 决定露不露，灰掉的那一侧不吃点击。 */
  private prevPageButton: Node | null = null
  private nextPageButton: Node | null = null
  private prevPageCaption: Label | null = null
  private nextPageCaption: Label | null = null
  private canPrev = false
  private canNext = false
  private summaryLabel: Label | null = null
  private summaryPanel: Node | null = null
  private lineupPicker: ChoiceOverlay | null = null
  /** 体力那一屏。null = `/stamina` 还没到 —— 此时表头继续用它自己那份 `体力 N` */
  private stamina: StaminaBoardView | null = null
  private pendingStamina: StaminaResp | null = null
  private pendingStaminaGold: number | null = null
  private staminaLabel: Label | null = null
  private buyLabel: Label | null = null
  private buyButton: Node | null = null
  private buyCaption: Label | null = null
  private staminaBand: Node | null = null

  /** 玩家点了某一关的「挑战」。派兵阵容由外层选择后再发请求，本场景不管阵容 */
  onChallenge: ((stageId: string) => void) | null = null
  /** 玩家点了「扫荡」。count 只会是 SWEEP_ONCE 或 SWEEP_MAX */
  onSweep: ((stageId: string, count: number) => void) | null = null
  /** 玩家点了「买体力」。买不买得动由外层按 `/stamina` 那份判定，本场景只画和回调 */
  onBuyStamina: (() => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.rowPool = new NodePool(this.node, () => this.createRow(), MAX_VISIBLE_ROWS)
    this.buildHeader(size.height)
    this.buildStaminaBand(size.width, size.height)
    this.buildSummary(size.width, size.height)
    this.lineupPicker = new ChoiceOverlay(this.node, '选择出战阵容', 760)
    if (this.pendingList !== null) {
      const pending = this.pendingList
      this.pendingList = null
      this.attach(pending)
    }
    if (this.pendingStamina !== null) {
      const pending = this.pendingStamina
      const gold = this.pendingStaminaGold
      this.pendingStamina = null
      this.attachStamina(pending, gold)
    }
  }

  override onDestroy(): void {
    this.rowPool?.destroy()
    this.rowPool = null
    this.drawnRows.length = 0
    this.buttonKinds.clear()
    this.lineupPicker?.hide()
    this.lineupPicker = null
    this.onChallenge = null
    this.onSweep = null
    this.onBuyStamina = null
    this.buyButton = null
  }

  /**
   * 适配层把「这件事现在做不了」送到这条带上。
   *
   * <p>以前这类话只进 console.warn（#356 实测：玩家按「挑战」得到的是完全无声，
   * 而"没有编队"这件事只有武将面板说得出声）。刻意不发请求、不自己判断能不能做 ——
   * 原因文本由 `AppRoot` 的三条真分支给出，本场景只负责让它看得见。
   */
  showBlocked(message: string): void {
    this.showSummary([message], COLOR_WARNING)
  }

  /** 玩家重新进入这一页时清掉摘要带。
   *
   * <p>刻意**不放在 {@link attach} 里**：一次写操作的投递顺序是「回执 → 刷新列表」，
   * 放在 attach 里等于让这次操作自己把刚说的那句话抹掉（「买体力」那条到账回执今天就是这么没的，
   * 而它一直是接上的）。摘要带讲的是「刚那一把怎么样」，所以它该由「玩家离开了这一页」结束，
   * 而不是由任何一次列表刷新结束。
   */
  override onEnable(): void {
    if (this.summaryPanel !== null && this.summaryPanel.active) {
      this.hideSummary()
    }
  }

  /** 装载关卡列表。行顺序照搬服务端，本场景不排序、不过滤。 */
  attach(resp: StageListResp): void {
    if (this.rowPool === null) {
      this.pendingList = resp
      return
    }
    this.list = buildStageList(resp)
    // 故意**不**归零页号：`AppRoot` 在挑战/扫荡/买体力之后都会 refresh('stage')，归零会把玩家
    // 刚打过的那一关从屏上弹走（他正要看结果）。列表变短时 `render()` 里的 `clampPage` 会夹回
    // 最后一页 —— 与 `SocialPanelView` 那条"不重置、只 clamp"同一口径。
    this.render()
  }

  /**
   * 装载体力那一屏。`/stamina` 是个会写库的读（惰性恢复在这里推进），
   * 所以它比 `/stage/list` 那份 `stamina` 更新 —— 到手后表头就不再印自己那个数，
   * 屏幕上同一时刻只有一个「体力 N」。
   */
  attachStamina(resp: StaminaResp, gold: number | null): void {
    if (this.rowPool === null) {
      this.pendingStamina = resp
      this.pendingStaminaGold = gold
      return
    }
    this.stamina = buildStaminaBoard(resp, gold)
    this.render()
    // 两次读谁先到都要能画：关卡列表没到时 `render()` 会早退，这一条不能跟着不画
    this.renderStamina()
  }

  /** 一次购买的回执。到账与扣币都照服务端说的念，本场景不算。 */
  attachStaminaBuy(resp: StaminaBuyResp): void {
    this.showSummary([
      `到账 ${resp.granted} 体力 · 扣 ${resp.costGold} 金币`,
      `今日已购 ${resp.boughtToday} 次`,
    ], COLOR_GOOD)
  }

  /** 装载一次挑战的结算。 */
  attachChallenge(resp: ChallengeStageResp): void {
    const summary = buildChallengeSummary(resp)
    const lines: string[] = [
      `历史最好 ${summary.starsText} · ${summary.earnedText}`,
      summary.conditionText,
      summary.staminaText,
    ]
    if (summary.refunded) {
      // B09 验收 1：失败不扣体力。这条必须写出来，否则玩家会以为体力被偷扣了
      lines.push('本次未通关，体力已退回')
    }
    if (summary.newBest) {
      lines.push('★ 新纪录')
    }
    if (summary.rewardLines.length > 0) {
      lines.push(`获得 ${summary.rewardLines.join(' · ')}`)
    }
    if (summary.lossLines.length > 0) {
      // 逐阶级列出：只给总数的话玩家看不出掉的是 T1 还是 T5，而两者代价差一个数量级
      lines.push(`损失 ${summary.lossLines.join(' · ')}`)
    }
    this.showSummary(lines, summary.newBest ? COLOR_STAR : COLOR_TEXT)
  }

  /**
   * 装载一次批量扫荡的结算。
   *
   * @param requested 客户端请求的次数。响应里没有这个字段，
   *                  而没有它就无法解释「为什么只扫了 7 次」
   */
  attachSweep(resp: SweepResp, requested: number): void {
    const summary = buildSweepSummary(resp, requested)
    const lines: string[] = [summary.executedText, summary.staminaText]
    if (summary.shortfallText !== null) {
      lines.push(summary.shortfallText)
    }
    if (summary.rewardLines.length > 0) {
      lines.push(`合计 ${summary.rewardLines.join(' · ')}`)
    }
    lines.push(`战报 ${summary.reportIds.length} 份（每次扫荡都是一场独立战斗）`)
    this.showSummary(lines, summary.shortfallText === null ? COLOR_GOOD : COLOR_WARNING)
  }

  /** AppRoot 选好阵容候选后交给本面板画出来；选择结果只回调一次。 */
  showLineupPicker(options: readonly LineupChoice[],
                   onPick: (choice: LineupChoice) => void): void {
    this.lineupPicker?.show(options, (id) => {
      const choice = options.find((option) => option.id === id)
      if (choice !== undefined) {
        onPick(choice)
      }
    })
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
    this.headerLabel = this.addLabel(this.node, 'Header', 0, height / 2 - PADDING - 22, COLOR_COPPER_GOLD, 22)
    this.overflowLabel = this.addLabel(this.node, 'Overflow', 0,
      height / 2 - PADDING - HEADER_HEIGHT - MAX_VISIBLE_ROWS * (ROW_HEIGHT + ROW_GAP) - 14,
      COLOR_TEXT_DIM, 14)
    // 两颗翻页键与那句页码同一行，摆在两端：中间那句短（「第 1/7 页 · 共 47 关」），
    // 面板宽 680，两侧各留 92 给键，不会与页码相碰（与关卡行里那三颗 64×28 的键同一写法）
    this.prevPageButton = this.buildPagerButton('PrevPageButton', -PANEL_WIDTH / 2 + 46)
    this.nextPageButton = this.buildPagerButton('NextPageButton', PANEL_WIDTH / 2 - 46)
  }

  /** 一颗 64×28 的翻页键：照本文件里关卡行那三颗动作键的画法，绑定一次、按 `canPrev/canNext` 决定吃不吃点击。 */
  private buildPagerButton(name: string, x: number): Node {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(64, 28))
    node.setPosition(new Vec3(x, -200, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 1
    graphics.roundRect(-32, -14, 64, 28, 4)
    graphics.fill()
    graphics.stroke()
    const caption = this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT, 13)
    caption.string = name === 'PrevPageButton' ? '上一页' : '下一页'
    // 建出来先收着：`render()` 在 `list === null` 时早退，不先收的话列表没到/读失败那一态
    // 会露着两颗点了没反应的键（#345 口径；#449 在目标搜索刚修过同一族，别在新面板上长回来）
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
   * 翻一页。灰掉的那一侧直接不吃：`render()` 里 `clampPage` 会把越界的页号夹回来，
   * 但"点了什么反应都没有"正是 #345 定的那条口径要挡的观感，所以在入口处就判。
   */
  private turnPage(delta: number): void {
    if (delta < 0 && !this.canPrev) return
    if (delta > 0 && !this.canNext) return
    this.page += delta
    this.render()
  }

  /**
   * 体力那一条：左边「体力 84/120 · 每小时 +5 · 3 分 12 秒后 +1」，右边价格与今日已购 + 一颗「买体力」。
   *
   * <p>没读到 `/stamina` 之前整条藏起来（`active=false`）—— 摆一条空带子比不摆更糟，
   * 玩家会以为体力就是没有。此时表头继续印它自己那份 `体力 N`。
   */
  private buildStaminaBand(width: number, height: number): void {
    const band = new Node('StaminaBand')
    band.layer = this.node.layer
    this.node.addChild(band)
    const innerW = width - PADDING * 2
    band.setPosition(new Vec3(0, height / 2 - PADDING - 22 - 20 - BAND_HEIGHT / 2, 0))
    band.addComponent(UITransform).setContentSize(new Size(innerW, BAND_HEIGHT))
    const graphics = band.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.roundRect(-innerW / 2, -BAND_HEIGHT / 2, innerW, BAND_HEIGHT, 6)
    graphics.fill()

    // 两行都左对齐、上下排：一行放"体力与恢复"，一行放"价格 / 今日已购 / 金币"。
    // 先前那版把第二行右对齐放在同一水平线上，两条长文案在中间撞成一坨（截图抓到）
    this.staminaLabel = this.addLabel(band, 'StaminaText', -innerW / 2 + PADDING, 14, COLOR_TEXT, 17)
    this.staminaLabel.horizontalAlign = Label.HorizontalAlign.LEFT
    this.sizeLeft(this.staminaLabel, BAND_TEXT_WIDTH)
    this.buyLabel = this.addLabel(band, 'BuyText', -innerW / 2 + PADDING, -14, COLOR_COPPER_GOLD, 15)
    this.buyLabel.horizontalAlign = Label.HorizontalAlign.LEFT
    this.sizeLeft(this.buyLabel, BAND_TEXT_WIDTH)
    this.buyButton = new Node('BuyStaminaButton')
    this.buyButton.layer = band.layer
    band.addChild(this.buyButton)
    this.buyButton.setPosition(new Vec3(innerW / 2 - 66, 0, 0))
    this.buyButton.addComponent(UITransform).setContentSize(new Size(104, 30))
    const buttonGraphics = this.buyButton.addComponent(Graphics)
    buttonGraphics.fillColor = COLOR_ROW
    buttonGraphics.strokeColor = COLOR_COPPER_GOLD
    buttonGraphics.lineWidth = 1
    buttonGraphics.roundRect(-52, -15, 104, 30, 4)
    buttonGraphics.fill()
    buttonGraphics.stroke()
    const caption = this.addLabel(this.buyButton, 'Caption', 0, 0, COLOR_TEXT, 15)
    caption.string = '买体力'
    this.buyCaption = caption
    this.buyButton.on('touch-start', (_event: EventTouch) => this.onBuyStamina?.(), this)
    this.staminaBand = band
    band.active = false
  }

  private buildSummary(width: number, height: number): void {
    const panel = new Node('SummaryPanel')
    panel.layer = this.node.layer
    this.node.addChild(panel)
    panel.setPosition(new Vec3(0, -height / 2 + BOTTOM_RESERVED + SUMMARY_HEIGHT / 2, 0))
    panel.addComponent(UITransform).setContentSize(new Size(width - PADDING * 2, SUMMARY_HEIGHT))
    const graphics = panel.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 2
    graphics.roundRect(-(width - PADDING * 2) / 2, -SUMMARY_HEIGHT / 2,
      width - PADDING * 2, SUMMARY_HEIGHT, 8)
    graphics.fill()
    graphics.stroke()
    this.summaryPanel = panel

    this.summaryLabel = this.addLabel(panel, 'SummaryText', 0, 0, COLOR_TEXT, 17)
    // SHRINK 是按盒子排的，而 `addLabel` 挂的 UITransform 是默认的 100×100 —— 这段摘要是
    // 多行文本（结算 / 体力 / 差额 / 奖励 / 损失），100 宽会把每一行再挤成两三行。
    // 盒子按摘要面板的内框给，与军队/背包/战报那三处「标签没盒子」同族（#316、#319、#320）。
    this.summaryLabel.node.getComponent(UITransform)?.setContentSize(
      new Size(width - PADDING * 4, SUMMARY_HEIGHT - PADDING * 2))
    this.summaryLabel.horizontalAlign = Label.HorizontalAlign.LEFT
    this.summaryLabel.verticalAlign = Label.VerticalAlign.CENTER
    this.summaryLabel.overflow = Label.Overflow.SHRINK
    panel.active = false
  }

  private createRow(): Node {
    const node = new Node('StageRow')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
    graphics.fill()

    const title = this.addLabel(node, 'Title', -PANEL_WIDTH / 2 + PADDING, 16, COLOR_TEXT, 19)
    title.horizontalAlign = Label.HorizontalAlign.LEFT
    const detail = this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING, -6, COLOR_TEXT_DIM, 14)
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    const extra = this.addLabel(node, 'Extra', -PANEL_WIDTH / 2 + PADDING, -24, COLOR_WARNING, 13)
    extra.horizontalAlign = Label.HorizontalAlign.LEFT
    this.sizeLeft(title, ROW_TEXT_WIDTH)
    this.sizeLeft(detail, ROW_TEXT_WIDTH)
    this.sizeLeft(extra, ROW_TEXT_WIDTH)
    const stars = this.addLabel(node, 'Stars', PANEL_WIDTH / 2 - 96, 16, COLOR_STAR, 20)
    stars.horizontalAlign = Label.HorizontalAlign.RIGHT

    // 三个按钮：挑战、扫荡×1、扫荡×10
    const buttons: Array<{ name: string; text: string; x: number; kind: RowAction }> = [
      { name: 'ChallengeButton', text: '挑战', x: PANEL_WIDTH / 2 - 180, kind: 'challenge' },
      { name: 'Sweep1Button', text: '扫荡×1', x: PANEL_WIDTH / 2 - 110, kind: 'sweep1' },
      { name: 'Sweep10Button', text: '扫荡×10', x: PANEL_WIDTH / 2 - 36, kind: 'sweep10' },
    ]
    for (const button of buttons) {
      const buttonNode = new Node(button.name)
      buttonNode.layer = node.layer
      node.addChild(buttonNode)
      buttonNode.setPosition(new Vec3(button.x, -20, 0))
      buttonNode.addComponent(UITransform).setContentSize(new Size(64, 28))
      const buttonGraphics = buttonNode.addComponent(Graphics)
      buttonGraphics.fillColor = COLOR_PANEL
      buttonGraphics.strokeColor = COLOR_COPPER_GOLD
      buttonGraphics.lineWidth = 1
      buttonGraphics.roundRect(-32, -14, 64, 28, 4)
      buttonGraphics.fill()
      buttonGraphics.stroke()
      const caption = this.addLabel(buttonNode, 'Caption', 0, 0, COLOR_TEXT, 13)
      caption.string = button.text
      this.buttonKinds.set(buttonNode, button.kind)
    }
    return node
  }

  /**
   * 左对齐的行标签必须自己有盒子：`addLabel` 挂的 UITransform 是默认 100×100 且锚点在中心，
   * 于是文字的起点跑到行的左边界外面 —— 实测标题「Chapter_01 · 第 1 关」的开头被屏幕裁掉。
   * 与军队 / 背包 / 战报 / 编成弹层那几处「标签没盒子」同族（#316、#319、#320、#321）。
   */
  private sizeLeft(label: Label, width: number): void {
    const transform = label.node.getComponent(UITransform)
    if (transform === null) {
      return
    }
    transform.setAnchorPoint(0, 0.5)
    // 高度不再让调用方各写一个数（原来 24/22/24/20/18 五处，全低于一行字的实测下限，
    // 于是 SHRINK 常态性把字压小 —— 台账 #367 点名关卡 14 行）。宽度才是这里要限的东西。
    transform.setContentSize(new Size(width, oneLineFloorHeight()))
    label.overflow = Label.Overflow.SHRINK
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
    const list = this.list
    const pool = this.rowPool
    if (list === null || pool === null) {
      return
    }
    if (this.headerLabel !== null) {
      // 体力那一条到手后，表头不再印自己那份 `体力 N`：同屏两个"体力"迟早有一个是旧的，
      // 而 `/stamina` 是会写库的读、比 `/stage/list` 那份新 —— 留新的那个。
      const staminaText = this.stamina === null ? ` · ${list.staminaText}` : ''
      this.headerLabel.string = list.lockedCountText === null
        ? `关卡 ${list.rows.length} 个${staminaText}`
        : `关卡 ${list.rows.length} 个${staminaText} · ${list.lockedCountText}`
    }
    this.renderStamina()

    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    pool.releaseAll(this.drawnRows)
    this.drawnRows.length = 0

    const total = list.rows.length
    // 共几页、夹到哪一页、切哪一段必须用同一个 perPage（`PanelPaging` 那条原话）：
    // 从前这里手抄了一遍 `capacity - 1`，于是"另有 N 关未显示"成了终局 —— 那 46 关玩家永远拿不到
    const perPage = contentPerPage(total, this.rowCapacity(topY))
    const pages = pageCount(total, perPage)
    this.page = clampPage(this.page, total, perPage)
    const slice = pageWindow(total, this.page, perPage)
    const visible = list.rows.slice(slice.start, slice.end)
    visible.forEach((row, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnRows.push(node)
      this.renderRow(node, row)
    })

    const paged = pages > 1
    const rowBottom = topY - visible.length * (ROW_HEIGHT + ROW_GAP) - 14
    this.canPrev = this.page > 0
    this.canNext = this.page < pages - 1
    if (this.overflowLabel !== null) {
      // 只有一页时留空：所有关都已在屏上，那句「另有 N 关未显示」在接了分页之后分母恒为 0
      this.overflowLabel.string = paged ? `${pageNotice(this.page, pages)} · 共 ${total} 关` : ''
      // 通知跟着行区最后一行走：容量是按摘要与窗口高度算出来的，钉在「7 行下面」
      // 就会飘到摘要框里 —— 玩家看到的是"关卡只有这两关"，而实际是被截断的 48 关
      this.overflowLabel.node.setPosition(new Vec3(0, rowBottom, 0))
    }
    // 只有一页时两颗键整对收掉（#345 口径：不留点了没反应的键）；多页时不可翻的那一侧按灰
    for (const [button, caption, usable] of [
      [this.prevPageButton, this.prevPageCaption, this.canPrev],
      [this.nextPageButton, this.nextPageCaption, this.canNext],
    ] as Array<[Node | null, Label | null, boolean]>) {
      if (button === null || caption === null) continue
      button.active = paged
      button.setPosition(new Vec3(button.position.x, rowBottom + 1, 0))
      caption.color = usable ? COLOR_TEXT : COLOR_TEXT_DIM
    }
  }

  /**
   * 行区能画几行 —— 按几何算，不写死数字：设计高度会随窗口变，写死的行数在 640 高下
   * 会把最后几行推到导航条底下（画了但玩家看不见），在 900 高下又白留一截空位。
   * 下界取「导航条上沿」与「摘要框上沿（摘要显示时）」里更高的那个。
   */
  private rowCapacity(topY: number): number {
    const navTop = -view.getVisibleSize().height / 2 + BOTTOM_RESERVED
    let floor = navTop
    const panel = this.summaryPanel
    if (panel !== null && panel.active) {
      floor = Math.max(navTop, panel.position.y + SUMMARY_HEIGHT / 2 + ROW_GAP)
    }
    const fit = (topY - ROW_HEIGHT / 2 - floor) / (ROW_HEIGHT + ROW_GAP) + 1
    return Math.max(1, Math.min(MAX_VISIBLE_ROWS, Math.floor(fit)))
  }

  /**
   * 画体力那一条。单独一个方法而不是并进 `render()`：`/stamina` 与 `/stage/list` 是两次读，
   * 谁先到都要能把自己那半画出来（并进 `render()` 的话，关卡列表失败时体力那条永远不出现）。
   */
  private renderStamina(): void {
    const board = this.stamina
    if (this.staminaBand !== null) {
      this.staminaBand.active = board !== null
    }
    if (board === null || this.staminaLabel === null || this.buyLabel === null) {
      return
    }
    this.staminaLabel.string = `${board.valueText} · ${board.recoverText}`
    this.buyLabel.string = board.buyBlocked
      ? `${board.buyText} · ${board.buyBlockedReason ?? '现在买不了'}`
      : `${board.buyText} · ${board.goldText}`
    this.buyLabel.color = board.buyBlocked ? COLOR_TEXT_DIM : COLOR_COPPER_GOLD
    const caption = this.buyCaption
    if (caption !== null) {
      caption.color = board.buyBlocked ? COLOR_TEXT_DIM : COLOR_TEXT
    }
  }

  private renderRow(node: Node, row: StageRow): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      graphics.fillColor = row.tappable ? COLOR_ROW : COLOR_ROW_LOCKED
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
      graphics.fill()
    }
    const title = node.children[0]?.getComponent(Label)
    const detail = node.children[1]?.getComponent(Label)
    const extra = node.children[2]?.getComponent(Label)
    const stars = node.children[3]?.getComponent(Label)

    if (title !== undefined && title !== null) {
      title.string = row.title
      title.color = row.tappable ? COLOR_TEXT : COLOR_TEXT_DIM
    }
    if (detail !== undefined && detail !== null) {
      detail.string = `${row.conditionText} · ${row.costText}`
    }
    if (extra !== undefined && extra !== null) {
      // 门槛与机制都要露出来：BOSS 机制在内核补齐前会被服务端拒绝，
      // 提前写明玩家才不会把那个报错当成 bug
      const parts: string[] = []
      if (row.restrictionText !== null) {
        parts.push(row.restrictionText)
      }
      if (row.mechanicText !== null) {
        parts.push(row.mechanicText)
      }
      if (!row.tappable && row.lockedReason !== null) {
        parts.push(row.lockedReason)
      }
      extra.string = parts.join(' · ')
    }
    if (stars !== undefined && stars !== null) {
      stars.string = row.starText
      stars.color = row.starText === '未挑战' ? COLOR_TEXT_DIM : COLOR_STAR
    }

    this.wireRowButtons(node, row)
  }

  /**
   * 给三个按钮接线。
   *
   * <p>每次都先 off 再 on：行节点来自池子，上一条数据留下的回调如果不清掉，
   * 点新的行会触发旧行的 stageId —— 这类 bug 的表现是「点了第 3 关却在打第 1 关」，
   * 而且只在快速滚动列表时出现，极难复现。
   */
  private wireRowButtons(node: Node, row: StageRow): void {
    for (const button of node.children) {
      const kind = this.buttonKinds.get(button)
      if (kind === undefined) {
        continue   // 文本子节点
      }
      button.off('touch-start')
      // 未解锁的关卡连按钮都不给：留着可点的按钮却什么都不发生，
      // 比灰掉更糟 —— 玩家会一直点，然后认为是卡了
      button.active = row.tappable
      if (!row.tappable) {
        continue
      }
      button.on('touch-start', (_event: EventTouch) => {
        switch (kind) {
          case 'challenge':
            this.onChallenge?.(row.stageId)
            return
          case 'sweep1':
            this.onSweep?.(row.stageId, SWEEP_ONCE)
            return
          case 'sweep10':
            // 一次请求带 count=10，绝不发 10 个请求（验收 9）
            this.onSweep?.(row.stageId, SWEEP_MAX)
            return
        }
      }, this)
    }
  }

  private showSummary(lines: readonly string[], color: Color): void {
    if (this.summaryLabel === null || this.summaryPanel === null) {
      return
    }
    this.summaryLabel.string = lines.join('\n')
    this.summaryLabel.color = color
    this.summaryPanel.active = true
    // 摘要占掉底部那一块，行区要立刻收进去 —— 不重排的话最后三行会压在摘要上，
    // 两层半透明文字叠在一起（2026-09-21 运行截图抓到的就是这一坨）
    this.render()
  }

  private hideSummary(): void {
    if (this.summaryPanel !== null) {
      this.summaryPanel.active = false
      this.render()
    }
  }
}
