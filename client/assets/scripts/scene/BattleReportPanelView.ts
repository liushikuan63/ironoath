/**
 * 职责：战报面板 —— 列表 + 点开一场进回放（B12 §3，台账第 21 条）。
 * 依赖：cc（渲染）、game/battle/BattleReportPanel（组装，已单测）、scene/BattlePlaybackView、scene/NodePool。
 *
 * <p><b>本场景不判胜负、不算时长</b>（铁律 2）：`won` 与 `totalRounds` 来自服务端简报，
 * 回放参数来自 `BattleReportResp.playback`（表里那两个数）。这里只画与递意图。
 *
 * <p><b>回放视图是面板的孩子，不是导航条的第 N 格</b>：`BattlePlaybackView` 自己建整屏背景，
 * 挂成同级会让玩家在同一格里看到两层背景；挂进来之后「列表 ← → 回放」是同一个面板内的两步，
 * 也就是 B12 §3「列表分页，详情可重放」的形状。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：长列表 ScrollView（超一屏目前截断并写明）、正式美术。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { buildReportList } from '../game/battle/BattleReportPanel'
import type { ReportListView, ReportRow } from '../game/battle/BattleReportPanel'
import { BattlePlaybackView } from './BattlePlaybackView'
import type { BattleReportListResp, BattleResultView } from '../net/generated/BattleProtocol'
import type { PlaybackOptions } from '../game/battle/BattlePlayback'
import { NodePool } from './NodePool'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(20, 17, 15, 238)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_LOST = new Color(38, 30, 30, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_WIN = new Color(120, 176, 96, 255)
const COLOR_LOSE = new Color(196, 92, 78, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 60
const ROW_GAP = 6
const PADDING = 16
const HEADER_HEIGHT = 64
const MAX_VISIBLE_ROWS = 8

@ccclass('BattleReportPanelView')
export class BattleReportPanelView extends Component {
  private list: ReportListView | null = null
  private pendingList: BattleReportListResp | null = null
  private pendingNow = 0

  private rowPool: NodePool | null = null
  private readonly drawnRows: Node[] = []
  private headerLabel: Label | null = null
  private emptyLabel: Label | null = null
  private overflowLabel: Label | null = null
  private backLabel: Label | null = null
  private listNode: Node | null = null
  private playbackNode: Node | null = null
  private playback: BattlePlaybackView | null = null
  /** 回放中…占位：详情请求在路上时先说一声，免得玩家以为点下去没反应 */
  private statusLabel: Label | null = null

  /** 玩家要点开哪一场（外层去拉详情，参数在详情响应里）。 */
  onReplayRequested: ((reportId: string) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.rowPool = new NodePool(this.node, () => this.createRow(), MAX_VISIBLE_ROWS)
    this.buildHeader(size.height)
    if (this.pendingList !== null) {
      const pending = this.pendingList
      this.pendingList = null
      this.attach(pending, this.pendingNow)
    }
  }

  override onDestroy(): void {
    this.rowPool?.destroy()
    this.rowPool = null
    this.drawnRows.length = 0
    this.onReplayRequested = null
    this.playback = null
  }

  /** 装载列表。行序照搬服务端（它按 createdAt 倒序，最新的在前）。 */
  attach(resp: BattleReportListResp, now: number): void {
    if (this.rowPool === null) {
      this.pendingList = resp
      this.pendingNow = now
      return
    }
    this.showList()
    this.list = buildReportList(resp, now)
    this.render()
  }

  /**
   * 把一场战果交给回放视图。回放组件是**第一次用到时才挂**的：
   * 它 onLoad 会建整屏背景与节点，没放过战报就不该有这些开销。
   */
  showReplay(result: BattleResultView, options: PlaybackOptions): void {
    if (this.playback === null) {
      const host = new Node('Playback')
      host.layer = this.node.layer
      this.node.addChild(host)
      host.addComponent(UITransform).setContentSize(new Size(view.getVisibleSize().width,
        view.getVisibleSize().height))
      // 面板的节点树里各子节点是未激活状态，回放要自己显示出来才跑 onLoad
      host.active = true
      this.playbackNode = host
      this.playback = host.addComponent(BattlePlaybackView)
    }
    if (this.listNode !== null) {
      this.listNode.active = false
    }
    if (this.backLabel !== null) {
      this.backLabel.string = '← 返回战报'
    }
    if (this.statusLabel !== null) {
      this.statusLabel.string = ''
    }
    this.playback.attach(result, options)
  }

  /** 详情请求还在路上：先给一句话，别让玩家对着列表以为刚才没点上。 */
  showReplayLoading(): void {
    if (this.statusLabel !== null) {
      this.statusLabel.string = '正在取这场战报的回放数据…'
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
    const list = new Node('List')
    list.layer = this.node.layer
    this.node.addChild(list)
    this.listNode = list

    this.headerLabel = this.addLabel(list, 'Header', 0, height / 2 - PADDING - 20, COLOR_COPPER_GOLD, 22)
    this.emptyLabel = this.addLabel(list, 'Empty', 0, 40, COLOR_TEXT_DIM, 17)
    this.overflowLabel = this.addLabel(list, 'Overflow', 0,
      -height / 2 + PADDING + 18, COLOR_TEXT_DIM, 14)
    this.statusLabel = this.addLabel(this.node, 'Status', 0, 0, COLOR_TEXT_DIM, 16)
    this.backLabel = this.addLabel(this.node, 'Back', -PANEL_WIDTH / 2 + 56,
      height / 2 - PADDING - 20, COLOR_TEXT, 16)
    this.backLabel.string = ''
    const back = this.backLabel.node
    back.on('touch-start', (_event: EventTouch) => this.showList())
  }

  private showList(): void {
    if (this.listNode !== null) {
      this.listNode.active = true
    }
    if (this.playbackNode !== null) {
      this.playbackNode.active = false
    }
    if (this.backLabel !== null) {
      this.backLabel.string = ''
    }
    if (this.statusLabel !== null) {
      this.statusLabel.string = ''
    }
  }

  private createRow(): Node {
    const node = new Node('ReportRow')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    node.addComponent(Graphics)
    this.addLabel(node, 'Title', -PANEL_WIDTH / 2 + PADDING, 12, COLOR_TEXT, 18).horizontalAlign
      = Label.HorizontalAlign.LEFT
    this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING, -12, COLOR_TEXT_DIM, 14).horizontalAlign
      = Label.HorizontalAlign.LEFT
    this.addLabel(node, 'Outcome', PANEL_WIDTH / 2 - PADDING, 0, COLOR_TEXT, 18).horizontalAlign
      = Label.HorizontalAlign.RIGHT
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
    const height = view.getVisibleSize().height
    if (this.headerLabel !== null) {
      this.headerLabel.string = list.headerText
    }
    if (this.emptyLabel !== null) {
      this.emptyLabel.string = list.emptyText
    }

    pool.releaseAll(this.drawnRows)
    this.drawnRows.length = 0
    const topY = height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
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
        ? `另有 ${hidden} 场未显示（长列表需要 ScrollView，属编辑器资产）`
        : ''
    }
  }

  private renderRow(node: Node, row: ReportRow): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      // 赢的行亮一点、输的行压暗：一眼扫得出哪几场要复盘
      graphics.fillColor = row.won ? COLOR_ROW : COLOR_ROW_LOST
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
      graphics.fill()
    }
    const title = node.children[0]?.getComponent(Label)
    const detail = node.children[1]?.getComponent(Label)
    const outcome = node.children[2]?.getComponent(Label)
    if (title !== undefined && title !== null) {
      title.string = row.title
    }
    if (detail !== undefined && detail !== null) {
      detail.string = `${row.roundsText} · ${row.lossText} · ${row.expiresIn}过期`
    }
    if (outcome !== undefined && outcome !== null) {
      outcome.string = row.outcomeText
      outcome.color = row.won ? COLOR_WIN : COLOR_LOSE
    }
    node.off('touch-start')
    node.on('touch-start', (_event: EventTouch) => {
      this.showReplayLoading()
      this.onReplayRequested?.(row.reportId)
    })
  }
}
