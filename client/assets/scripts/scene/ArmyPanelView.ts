/**
 * 职责：军队与医院面板 —— 20 个兵种的兵力/训练、医院伤兵与容量警告（B05 §二、§1.5，验收 7）。
 * 依赖：cc（渲染）、game/army/ArmyPanel（展示数据组装，已单测）、core/Countdown、scene/NodePool。
 *
 * <p><b>倒计时每秒本地刷新，不轮询服务端</b>：协议在 UnitView.remainingSeconds 上明写了这条。
 * 刷新方式是拿原响应重新组装一遍面板（与城建面板同一套做法）：到点那一刻
 * 「训练中 N」要变成「已完成，可收取」、按钮要从「训练」切成「收取」，
 * 只改倒计时文本会留下一个状态不一致的中间态。
 *
 * <p><b>本场景不做任何判定</b>（铁律 2）：能不能训、队列够不够、资源足不足由服务端裁定。
 * 训练时长的预估用的是服务端下发的 trainTimeSec，文案上写「约」——
 * 中间若有加速或联盟帮助，预估与实际会不符，而那是服务端说了算的事。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：.scene / .prefab 资产、兵种图标、
 * 训练数量的输入控件（占位期只有 ×1 / ×100 两个快捷档）、长列表的 ScrollView。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, sys, view } from 'cc'
import { buildArmyPanel, estimateTrainMs } from '../game/army/ArmyPanel'
import type { TrainMemory } from '../game/army/AutoTrain'
import { formatCountdown } from '../core/Countdown'
import type { ArmyPanelView as ArmyPanelData, UnitRow } from '../game/army/ArmyPanel'
import type { ArmyListResp, UnitType } from '../net/generated/ArmyProtocol'
import { applyCommandButton, applyIconSprite, unitIconKey } from './ArtCatalog'
import { NodePool } from './NodePool'
import { applySystemUiFont, keepOneLine } from './UiFont'
import { truncatedNotice } from '../game/ui/TruncatedList'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性。美术方向常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_LOCKED = new Color(34, 31, 28, 255)
const COLOR_ROW_TRAINING = new Color(64, 48, 30, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_WARNING = new Color(200, 60, 40, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 62
const ROW_GAP = 5
/**
 * 表头高度（导航条之上留给"带兵/医院/页签/两个按钮/自动续训那一条"的那一块）。
 * 从 150 加到 176 是为了给自动续训的状态行腾一行（B25-S2d）。
 */
const HEADER_HEIGHT = 176
const PADDING = 16
/**
 * 屏幕底部要给导航条让出的高度 = 8（下边距）+ 52（`PanelNav.BAR_HEIGHT`）+ 8（安全间隙）。
 *
 * <p>这两个数是**量出来的**，不是抄的：设计分辨率 960×640、`PanelNav` 把导航条摆在
 * `y ∈ [-h/2+8, -h/2+8+52]`，而面板画的是整屏矩形，所以行区一旦越界就会被导航条盖住。
 */
const BOTTOM_RESERVED = 68
/**
 * 池子开多大。一屏画几行是**按实际可视高度算**的（见 render），所以这里给的是池子容量：
 * 按类型分页后每页最多 5 个阶级，容量留到 6 就够；画不下的行会被数出来并告诉玩家（"另有 N 项未显示"）。
 */
const ROW_POOL_SIZE = 6
/**
 * 训练数量的两个快捷档。
 *
 * <p>这不是游戏数值而是输入控件的快捷方式（真正的数量由玩家在编辑器补的输入框里给）：
 * ×1 用来确认单价与耗时，×100 用来批量。协议明写「时间 = 单位时间 × count，批量不等于加速」，
 * 所以两档的耗时预估差 100 倍是对的，面板要如实显示。
 */
const TRAIN_ONCE = 1
const TRAIN_BULK = 100

const TYPE_TABS: ReadonlyArray<{ type: UnitType | null; text: string }> = [
  { type: null, text: '全部' },
  { type: 'INFANTRY', text: '步兵' },
  { type: 'CAVALRY', text: '骑兵' },
  { type: 'ARCHER', text: '弓手' },
  { type: 'SIEGE', text: '攻城' },
]

@ccclass('ArmyPanelView')
export class ArmyPanelView extends Component {
  private resp: ArmyListResp | null = null
  private panel: ArmyPanelData | null = null
  /** 编排层递进来的「上一次成功训练」；只用于自动续训那一句提示（B25-S2d） */
  private trainMemory: TrainMemory | null = null
  private offsetMs = 0
  private filter: UnitType | null = null
  private lastRenderedSecond = -1
  private pending: ArmyListResp | null = null

  private rowPool: NodePool | null = null
  private readonly drawnRows: Node[] = []
  private readonly trainButtons = new Map<Node, UnitRow | null>()
  private readonly tabButtons = new Map<string, Node>()
  private headerLabel: Label | null = null
  /** 筛选后一行都不剩时的那句话：没有它，玩家看到的是一整块空白（与 MarchPanelView 同族） */
  private emptyLabel: Label | null = null
  private hospitalLabel: Label | null = null
  private warningLabel: Label | null = null
  private autoTrainButton: Node | null = null
  private autoTrainCaption: Label | null = null
  private autoTrainStatus: Label | null = null

  /** 点「训练」。数量只可能是 TRAIN_ONCE 或 TRAIN_BULK（正式输入控件属编辑器资产） */
  onTrain: ((unitId: string, count: number) => void) | null = null
  /** 点「治疗」。治哪些伤兵由服务端裁定，本场景只表达意图 */
  onTreat: (() => void) | null = null
  /** 点行上的「队列」（B26 S15）：这一口在练什么、能不能取消，全由编排层判，这里只回抛 unitId */
  onQueue: ((unitId: string) => void) | null = null
  /** 点「自动续训 / 停止自动」。能不能开、续的是哪一批由编排层判定（B25-S2d） */
  onToggleAutoTrain: (() => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.rowPool = new NodePool(this.node, () => this.createRow(), ROW_POOL_SIZE)
    this.buildHeader(size.height)
    if (this.pending !== null) {
      const pending = this.pending
      this.pending = null
      this.attach(pending, this.offsetMs)
    }
  }

  override onDestroy(): void {
    this.rowPool?.destroy()
    this.rowPool = null
    this.drawnRows.length = 0
    this.trainButtons.clear()
    this.tabButtons.clear()
    this.onTrain = null
    this.onTreat = null
  }

  /**
   * 装载军队列表。
   *
   * @param offsetMs 服务端时刻 - 本地时刻，来源 core/TimeSync。<b>不能省</b>：
   *                 直接拿本地 Date 与 finishAt 相减，玩家把手机时间调快就能让训练立刻完成（铁律 5）
   * @param trainMemory 客户端记住的上一次成功训练（B25-S2d）。只影响「自动续训现在能不能开」那一句提示；
   *                    策略本身（开着吗、还剩几批、为什么停）全部来自 resp
   */
  attach(resp: ArmyListResp, offsetMs: number, trainMemory: TrainMemory | null = null): void {
    this.offsetMs = offsetMs
    this.trainMemory = trainMemory
    if (this.rowPool === null) {
      this.pending = resp
      return
    }
    this.resp = resp
    this.panel = buildArmyPanel(resp, offsetMs, sys.now(), this.trainMemory)
    this.lastRenderedSecond = -1
    this.render()
  }

  updateOffset(offsetMs: number): void {
    this.offsetMs = offsetMs
  }

  /** 按兵种过滤。null 表示全部。过滤只是显示分组，不改变服务端给的顺序。 */
  setFilter(type: UnitType | null): void {
    if (this.filter === type) {
      return
    }
    this.filter = type
    this.render()
  }

  override update(): void {
    const resp = this.resp
    if (resp === null) {
      return
    }
    const now = sys.now()
    const second = Math.floor(now / 1000)
    if (second === this.lastRenderedSecond) {
      return
    }
    this.panel = buildArmyPanel(resp, this.offsetMs, now, this.trainMemory)
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
    // 空态那一行落在**第一行该在的位置**（同一套 topY 算法），不是随便挑的一个 y
    this.emptyLabel = this.addLabel(this.node, 'Empty', 0,
      top - HEADER_HEIGHT - ROW_HEIGHT / 2, COLOR_TEXT_DIM, 16)
    this.emptyLabel.string = ''
    this.emptyLabel.node.active = false
    // 限成一行：过去这里写成「SHRINK + 猜的盒高 26」，而 SHRINK 是拿**缩放字形**去服从盒子的
    // —— 页内实测这一行设定 20 号字落地只有 17（战令表头同形，五行 17/13/10/10/9，台账 #366
    // 附了盒高→落地字号的迁移曲线）。关掉换行才是不碰字号地把"就一行"说出来。
    // 这一行现在会带上「另有 N 项未显示」，不限一行就会顶出面板（#221 同族的排版溢出），
    // 顶出去由 `verify-march-runtime.mjs` 的宽度判据兜住。
    keepOneLine(this.headerLabel, 20)
    this.hospitalLabel = this.addLabel(this.node, 'Hospital', 0, top - 48, COLOR_TEXT, 17)
    this.warningLabel = this.addLabel(this.node, 'Warning', 0, top - 74, COLOR_WARNING, 15)
    // 同一条 #22x 教训：这两行同样会带长文案（医院行拼「另有 N 项未显示」、警告行是整句话）。
    // ⚠ 限宽**不等于限一行**：#362 实测这两行的盒子会被 Label 按文本重算成 50 高，
    //   而两行中心距只有 26px ⇒ 警告行一换行就压到医院行（那条垂直重叠记台账 #362 未做①）。
    keepOneLine(this.hospitalLabel, 17)
    keepOneLine(this.warningLabel, 15)

    // 兵种页签。20 个兵种（4 类型 × 5 阶级）一屏放不下，按类型分页
    const tabWidth = 76
    const startX = -(TYPE_TABS.length - 1) * tabWidth / 2
    TYPE_TABS.forEach((tab, index) => {
      const node = new Node(`Tab_${tab.text}`)
      node.layer = this.node.layer
      this.node.addChild(node)
      node.setPosition(new Vec3(startX + index * tabWidth, top - 104, 0))
      node.addComponent(UITransform).setContentSize(new Size(tabWidth - 6, 32))
      if (!applyCommandButton(node, tab.type === this.filter ? 'hover' : 'normal',
        tabWidth - 6, 32)) {
        const graphics = node.addComponent(Graphics)
        graphics.fillColor = COLOR_PANEL
        graphics.strokeColor = COLOR_COPPER_GOLD
        graphics.lineWidth = 1
        graphics.roundRect(-(tabWidth - 6) / 2, -16, tabWidth - 6, 32, 5)
        graphics.fill()
        graphics.stroke()
      }
      this.tabButtons.set(tab.type ?? 'ALL', node)
      const label = this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT, 15)
      label.string = tab.text
      const type = tab.type
      node.on('touch-start', (_event: EventTouch) => this.setFilter(type), this)
    })

    const treat = new Node('TreatButton')
    treat.layer = this.node.layer
    this.node.addChild(treat)
    treat.setPosition(new Vec3(PANEL_WIDTH / 2 - 60, top - 138, 0))
    treat.addComponent(UITransform).setContentSize(new Size(110, 34))
    if (!applyCommandButton(treat, 'normal', 110, 34)) {
      const treatGraphics = treat.addComponent(Graphics)
      treatGraphics.fillColor = COLOR_PANEL
      treatGraphics.strokeColor = COLOR_GOOD
      treatGraphics.lineWidth = 2
      treatGraphics.roundRect(-55, -17, 110, 34, 5)
      treatGraphics.fill()
      treatGraphics.stroke()
    }
    const treatCaption = this.addLabel(treat, 'Caption', 0, 0, COLOR_TEXT, 15)
    treatCaption.string = '治疗伤兵'
    treat.on('touch-start', (_event: EventTouch) => this.onTreat?.(), this)

    // 「自动续训」放在治疗按钮的对面（同一行）：两个都是"整支军队"的动作，
    // 和上面那排页签（筛选）与下面那排行内按钮（单个兵种）都不同类
    const autoTrain = new Node('AutoTrainButton')
    autoTrain.layer = this.node.layer
    this.node.addChild(autoTrain)
    autoTrain.setPosition(new Vec3(-PANEL_WIDTH / 2 + 60, top - 138, 0))
    autoTrain.addComponent(UITransform).setContentSize(new Size(110, 34))
    if (!applyCommandButton(autoTrain, 'normal', 110, 34)) {
      const autoGraphics = autoTrain.addComponent(Graphics)
      autoGraphics.fillColor = COLOR_PANEL
      autoGraphics.strokeColor = COLOR_COPPER_GOLD
      autoGraphics.lineWidth = 2
      autoGraphics.roundRect(-55, -17, 110, 34, 5)
      autoGraphics.fill()
      autoGraphics.stroke()
    }
    this.autoTrainCaption = this.addLabel(autoTrain, 'Caption', 0, 0, COLOR_TEXT, 14)
    autoTrain.on('touch-start', (_event: EventTouch) => this.onToggleAutoTrain?.(), this)
    this.autoTrainButton = autoTrain

    // 状态行：「重步兵 ×50 · 还剩 2 批」/ 停止原因 / 还没有可续的那一批。
    // 它读的是服务端下发的那份策略 —— 自动续训的账单是持续的，玩家必须能一眼看到它现在在做什么
    this.autoTrainStatus = this.addLabel(this.node, 'AutoTrainStatus', 0, top - 164, COLOR_TEXT_DIM, 14)
    this.autoTrainStatus.node.getComponent(UITransform)
      ?.setContentSize(new Size(PANEL_WIDTH - 2 * PADDING, 20))
    this.autoTrainStatus.overflow = Label.Overflow.SHRINK
  }

  private createRow(): Node {
    const node = new Node('UnitRow')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
    graphics.fill()

    const title = this.addLabel(node, 'Title', -PANEL_WIDTH / 2 + PADDING + 42,
      16, COLOR_TEXT, 18)
    title.horizontalAlign = Label.HorizontalAlign.LEFT
    title.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    title.node.getComponent(UITransform)?.setContentSize(new Size(330, 26))
    title.overflow = Label.Overflow.SHRINK
    const detail = this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING + 42,
      -4, COLOR_TEXT_DIM, 14)
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    detail.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    detail.node.getComponent(UITransform)?.setContentSize(new Size(330, 22))
    detail.overflow = Label.Overflow.SHRINK
    const countdown = this.addLabel(node, 'Countdown', -PANEL_WIDTH / 2 + PADDING + 42,
      -22, COLOR_COPPER_GOLD, 13)
    countdown.horizontalAlign = Label.HorizontalAlign.LEFT
    countdown.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    countdown.node.getComponent(UITransform)?.setContentSize(new Size(330, 20))
    countdown.overflow = Label.Overflow.SHRINK

    const buttons: Array<{ name: string; text: string; x: number; count: number | null }> = [
      // 「队列」排在两颗训练键左边：它管的是**已经在练的那一口**（取消），与"再练多少"不同类。
      // 三颗键各占 78，字那三行限到 330 宽，盒子互不重叠（量具按盒子量，不按眼睛）
      { name: 'QueueButton', text: '队列', x: PANEL_WIDTH / 2 - 212, count: null },
      { name: 'TrainOnceButton', text: '训练×1', x: PANEL_WIDTH / 2 - 128, count: TRAIN_ONCE },
      { name: 'TrainBulkButton', text: '训练×100', x: PANEL_WIDTH / 2 - 44, count: TRAIN_BULK },
    ]
    for (const button of buttons) {
      const buttonNode = new Node(button.name)
      buttonNode.layer = node.layer
      node.addChild(buttonNode)
      buttonNode.setPosition(new Vec3(button.x, -14, 0))
      buttonNode.addComponent(UITransform).setContentSize(new Size(78, 28))
      if (!applyCommandButton(buttonNode, 'normal', 78, 28)) {
        const buttonGraphics = buttonNode.addComponent(Graphics)
        buttonGraphics.fillColor = COLOR_PANEL
        buttonGraphics.strokeColor = COLOR_COPPER_GOLD
        buttonGraphics.lineWidth = 1
        buttonGraphics.roundRect(-39, -14, 78, 28, 4)
        buttonGraphics.fill()
        buttonGraphics.stroke()
      }
      const caption = this.addLabel(buttonNode, 'Caption', 0, 0, COLOR_TEXT, 12)
      caption.string = button.text
      // 池化节点复用时行数据会变，所以每次渲染都重新登记；这里先占位
      this.trainButtons.set(buttonNode, null)
      buttonNode.on('touch-start', (_event: EventTouch) => {
        const row = this.trainButtons.get(buttonNode)
        if (row === undefined || row === null) {
          return
        }
        if (button.count === null) {
          this.onQueue?.(row.unitId)
        } else {
          this.onTrain?.(row.unitId, button.count)
        }
      }, this)
    }
    const icon = new Node('Icon')
    icon.layer = node.layer
    node.addChild(icon)
    icon.setPosition(new Vec3(-PANEL_WIDTH / 2 + PADDING + 22, 0, 0))
    icon.addComponent(UITransform).setContentSize(new Size(36, 36))
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
    const panel = this.panel
    const pool = this.rowPool
    if (panel === null || pool === null) {
      return
    }
    this.lastRenderedSecond = Math.floor(sys.now() / 1000)
    for (const [key, node] of this.tabButtons) {
      applyCommandButton(node, key === (this.filter ?? 'ALL') ? 'hover' : 'normal', 70, 32)
    }

    // 行区排布先算出来：表头那一行要顺带说「还有几项没画下」，所以它得先知道画得下几行
    const rows = panel.rows.filter((row) => this.matchesFilter(row))
    // 零行必须说句话：按兵种筛到没有时，行区是一片空白，而表头讲的是兵力与队列 ——
    // 玩家分不清"这一类确实没有兵"与"面板没加载出来"（MarchPanelView:77 同一形状）。
    if (this.emptyLabel !== null) {
      // 没筛 = 页签停在「全部」，此时 `filter` 是 null（`UnitType | null`，没有 'ALL' 这个值）
      const all = this.filter === null
      this.emptyLabel.string = all ? '暂无部队' : '暂无这一类部队'
      this.emptyLabel.node.active = rows.length === 0
    }
    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2

    // 一屏画几行**按实际可视高度算**，不是一个写死的数：
    //   ① 可视高度随窗口/机型变（设计分辨率 960×640 只是基准，等宽适配下窗口越扁可视高度越小）；
    //   ② 多画的行会被底部导航条盖住 —— 那是"画了但玩家看不见"，比少画一行更难发现。
    // 写死 7 的那一版在 1280×720 下把第 5 行压掉了一半（B25-S2d 的探针量到最低行底边 -319 < 导航条上沿 -210）。
    const navTop = -size.height / 2 + BOTTOM_RESERVED
    const usable = topY + ROW_HEIGHT / 2 - navTop
    const maxRows = Math.max(1, Math.floor(usable / (ROW_HEIGHT + ROW_GAP)))
    const drawn = Math.min(rows.length, maxRows)

    if (this.headerLabel !== null) {
      // 「还有几项没画下」挂在这一行（本面板的汇总行）：只显示 4 行时玩家得知道下面还有，
      // 而不是以为这个类型就只有这几种兵。分隔符得自己补 —— 那一句是给独立一行用的
      const truncated = truncatedNotice('项', rows.length - drawn)
      this.headerLabel.string = `${panel.troopCapText} · ${panel.queueText}`
        + (truncated === '' ? '' : ` · ${truncated}`)
      this.headerLabel.color = panel.troopCapFull ? COLOR_WARNING : COLOR_COPPER_GOLD
    }
    if (this.hospitalLabel !== null) {
      const hospital = panel.hospital
      const parts = [hospital.capacityText]
      if (hospital.treatingText !== null) {
        parts.push(hospital.treatingText)
      }
      if (hospital.countdownText !== null) {
        parts.push(hospital.countdownText)
      }
      parts.push(hospital.treatCostRatioText)
      this.hospitalLabel.string = parts.join(' · ')
    }
    if (this.warningLabel !== null) {
      const hospital = panel.hospital
      this.warningLabel.string = hospital.warningText ?? ''
      // capacity == 0 是最严重的一档：所有伤兵都会直接死亡（B05 §1.5）
      this.warningLabel.color = hospital.noCapacity ? COLOR_WARNING : COLOR_GOOD
    }

    // 自动续训那一条：按钮说开还是关，状态行说它此刻在做什么 / 为什么停了 / 现在还开不了
    const autoTrain = panel.autoTrain
    if (this.autoTrainCaption !== null) {
      this.autoTrainCaption.string = autoTrain.caption
      this.autoTrainCaption.color = autoTrain.enabled ? COLOR_GOOD : COLOR_TEXT
    }
    if (this.autoTrainButton !== null) {
      applyCommandButton(this.autoTrainButton, autoTrain.enabled ? 'hover' : 'normal', 110, 34)
    }
    if (this.autoTrainStatus !== null) {
      // 三选一：正在续的那一批 > 停下来的原因 > 还不能开的原因。都为空就是空行
      this.autoTrainStatus.string = autoTrain.runningText ?? autoTrain.stopText
        ?? autoTrain.blockedReason ?? ''
      this.autoTrainStatus.color = autoTrain.runningText !== null ? COLOR_COPPER_GOLD
        : autoTrain.stopText !== null ? COLOR_WARNING : COLOR_TEXT_DIM
    }

    pool.releaseAll(this.drawnRows)
    this.drawnRows.length = 0
    rows.slice(0, drawn).forEach((row, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnRows.push(node)
      this.renderRow(node, row)
    })
  }

  /** 按兵种过滤。只是显示分组，不改变服务端给的顺序。 */
  private matchesFilter(row: UnitRow): boolean {
    return this.filter === null || row.unitType === this.filter
  }

  private renderRow(node: Node, row: UnitRow): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      graphics.fillColor = !row.unlocked ? COLOR_ROW_LOCKED
        : row.trainingText !== null ? COLOR_ROW_TRAINING : COLOR_ROW
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
      graphics.fill()
    }
    const title = node.children[0]?.getComponent(Label)
    const detail = node.children[1]?.getComponent(Label)
    const countdown = node.children[2]?.getComponent(Label)
    const icon = node.getChildByName('Icon')

    if (title !== undefined && title !== null) {
      // 名字自带档位（表里就是「重步兵 T2」）：客户端不再拼 `T${tier}` 前缀，
      // 否则 T2~T5 会印成「T2 重步兵 T2」而 T1 又不会 —— 显示名归服务端（收口清单里"档位名统一"那条）
      title.string = row.name
      title.color = row.unlocked ? COLOR_TEXT : COLOR_TEXT_DIM
    }
    if (detail !== undefined && detail !== null) {
      const parts = [row.countText]
      if (row.woundedText !== null) {
        parts.push(row.woundedText)
      }
      if (row.trainingText !== null) {
        parts.push(row.trainingText)
      }
      // 未解锁原因原样透传：一个灰掉的兵种不说明为什么，玩家会以为是 bug
      if (!row.unlocked && row.unlockHint !== null) {
        parts.push(row.unlockHint)
      }
      detail.string = parts.join(' · ')
      detail.color = row.unlocked ? COLOR_TEXT_DIM : COLOR_WARNING
    }
    if (countdown !== undefined && countdown !== null) {
      if (row.trainingText !== null && row.countdownText !== null) {
        // 顺手给出批量训练的耗时预估：trainTimeSec 是服务端下发的单价，
        // 下发它就是为了这个预估不必让客户端去读 unit 表。文案写「约」——
        // 中间若有加速或联盟帮助，实际时长由服务端说了算
        const estimate = formatCountdown(estimateTrainMs(row.trainTimeSec, TRAIN_BULK), '已完成')
        countdown.string = `${row.countdownText} · 单个 ${row.trainTimeSec} 秒 · 约 ${estimate}/100 个`
        countdown.color = COLOR_COPPER_GOLD
      } else {
        countdown.string = `训练消耗 ${row.trainCostText}`
        countdown.color = COLOR_TEXT_DIM
      }
    }
    if (icon !== null) {
      icon.active = applyIconSprite(icon, unitIconKey(row.unitType), 34, 34)
    }

    // 未解锁的兵种不给训练按钮：留着可点的按钮却只会被服务端拒绝，比灰掉更糟。
    // 按名字挑而不是按下标：行里加了第三颗键之后，`children.slice(3, 5)` 会把「队列」
    // 当成训练键（#291 那一格就是被这种按下标挑法坑过一次）
    const rowButtons = ['QueueButton', 'TrainOnceButton', 'TrainBulkButton']
      .map((name) => node.getChildByName(name))
      .filter((it): it is Node => it !== null)
    for (const button of rowButtons) {
      const usable = button.name === 'QueueButton'
        // 只有真的一口在练时才给「队列」：没有可取消的东西，点下去就是一张空菜单
        ? row.trainingText !== null
        : row.unlocked
      this.trainButtons.set(button, usable ? row : null)
      button.active = usable
    }
  }
}
