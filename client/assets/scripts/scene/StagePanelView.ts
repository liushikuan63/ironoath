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
import type { LineupChoice } from '../game/session/Choices'
import type { ChallengeStageResp, StageListResp, SweepResp } from '../net/generated/StageProtocol'
import { ChoiceOverlay } from './ChoiceOverlay'
import { NodePool } from './NodePool'
import { applySystemUiFont } from './UiFont'

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
const HEADER_HEIGHT = 72
const PADDING = 16
/** 一屏最多画几行。超出的要靠 ScrollView（编辑器里补），占位期截断显示并说明 */
const MAX_VISIBLE_ROWS = 7
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
  private summaryLabel: Label | null = null
  private summaryPanel: Node | null = null
  private lineupPicker: ChoiceOverlay | null = null

  /** 玩家点了某一关的「挑战」。派兵阵容由外层选择后再发请求，本场景不管阵容 */
  onChallenge: ((stageId: string) => void) | null = null
  /** 玩家点了「扫荡」。count 只会是 SWEEP_ONCE 或 SWEEP_MAX */
  onSweep: ((stageId: string, count: number) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.rowPool = new NodePool(this.node, () => this.createRow(), MAX_VISIBLE_ROWS)
    this.buildHeader(size.height)
    this.buildSummary(size.width, size.height)
    this.lineupPicker = new ChoiceOverlay(this.node, '选择出战阵容', 760)
    if (this.pendingList !== null) {
      const pending = this.pendingList
      this.pendingList = null
      this.attach(pending)
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
  }

  /** 装载关卡列表。行顺序照搬服务端，本场景不排序、不过滤。 */
  attach(resp: StageListResp): void {
    if (this.rowPool === null) {
      this.pendingList = resp
      return
    }
    this.list = buildStageList(resp)
    this.hideSummary()
    this.render()
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
  }

  private buildSummary(width: number, height: number): void {
    const panel = new Node('SummaryPanel')
    panel.layer = this.node.layer
    this.node.addChild(panel)
    panel.setPosition(new Vec3(0, -height / 2 + 150, 0))
    panel.addComponent(UITransform).setContentSize(new Size(width - PADDING * 2, 240))
    const graphics = panel.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 2
    graphics.roundRect(-(width - PADDING * 2) / 2, -120, width - PADDING * 2, 240, 8)
    graphics.fill()
    graphics.stroke()
    this.summaryPanel = panel

    this.summaryLabel = this.addLabel(panel, 'SummaryText', 0, 0, COLOR_TEXT, 17)
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
      this.headerLabel.string = list.lockedCountText === null
        ? `关卡 ${list.rows.length} 个 · ${list.staminaText}`
        : `关卡 ${list.rows.length} 个 · ${list.staminaText} · ${list.lockedCountText}`
    }

    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    pool.releaseAll(this.drawnRows)
    this.drawnRows.length = 0

    const visible = list.rows.slice(0, MAX_VISIBLE_ROWS)
    visible.forEach((row, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnRows.push(node)
      this.renderRow(node, row)
    })

    if (this.overflowLabel !== null) {
      const hidden = list.rows.length - visible.length
      this.overflowLabel.string = hidden > 0
        ? `另有 ${hidden} 关未显示（长列表需要 ScrollView，属编辑器资产）`
        : ''
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
  }

  private hideSummary(): void {
    if (this.summaryPanel !== null) {
      this.summaryPanel.active = false
    }
  }
}
