/**
 * 职责：等级奖励面板（收口清单 #829 裁决③「新建可见入口」）—— 逐级奖励、已领/待领/未达三态与「领取」键。
 * 依赖：cc（渲染）、game/levelReward/LevelRewardPanel（展示数据组装，已单测）、scene/UiFont。
 *
 * <p><b>本场景不做任何判定</b>（铁律 2）：哪一级领过、哪一级还差几级、这一行能不能点，
 * 全部来自服务端下发的那一份状态；本文件只把行画出来、把点击意图回抛。
 *
 * <p><b>不可领的那颗按钮是灰的，原因写在行里**：「已领取」与「主城 14 级，还差 17 级」是两句
 * 完全不同的话（前者不用再点，后者告诉他下一步去升主城），所以不把三态压成一个「不可领」。
 *
 * <p><b>行数按实测可视高度算</b>（与战令/商店/军队同一条纪律）：写死行数会在矮窗口里把最后一行
 * 压在底部导航条下面；画不下的等级由表头那句「第 a–b 级 / 共 40 级」交代，
 * 而窗口起点跟着「接下来该领哪一级」走（见 `LevelRewardPanel.windowStartOf`）。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { buildLevelRewardPanel } from '../game/levelReward/LevelRewardPanel'
import type { LevelRewardPanelData, LevelRewardView } from '../game/levelReward/LevelRewardPanel'
import { applySystemUiFont, capWidth, keepOneLine } from './UiFont'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_DIM = new Color(34, 31, 28, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)
const COLOR_BUTTON_OFF = new Color(64, 55, 46, 255)

const PANEL_WIDTH = 700
const ROW_HEIGHT = 66
const ROW_GAP = 6
/** 标题 + 那一行汇总 + 提示行合计占掉的高度（三行的 y 间距见 buildHeader）。 */
const HEADER_HEIGHT = 128
const PADDING = 16
const ROW_POOL_SIZE = 6
/** 屏幕底部要给导航条让出的高度（与其它面板同一个数：8 + 52 + 8）。 */
const BOTTOM_RESERVED = 68
const BUTTON_WIDTH = 84
const BUTTON_HEIGHT = 30

@ccclass('LevelRewardPanelView')
export class LevelRewardPanelView extends Component {
  private data: LevelRewardPanelData | null = null
  private headerLabel: Label | null = null
  private summaryLabel: Label | null = null
  private noticeLabel: Label | null = null
  private readonly rowNodes: Node[] = []
  private readonly rowNames: Label[] = []
  private readonly rowRewards: Label[] = []
  private readonly rowStates: Label[] = []
  private readonly buttons: Node[] = []
  private readonly captions: Label[] = []
  /** 每一行此刻对应哪一级、能不能点（点击时要知道点的是谁，而不是再去查一遍）。 */
  private readonly rowLevels: Array<number | null> = []
  private readonly rowClaimable: boolean[] = []

  /** 领取某一级的奖励。能不能领由服务端说了算，编排层会再挡一次并说明原因。 */
  onClaim: ((level: number) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.buildHeader(size.height)
    this.node.on('touch-start', (_event: EventTouch) => {
      /* 面板本身不吃触摸，这一句只是防止事件穿透到地图 */
    }, this)
  }

  override onDestroy(): void {
    this.rowNodes.length = 0
    this.onClaim = null
  }

  /** 装载整块视图（编排层递来的原始响应 + 上一次领取的结果行，本文件不改其中任何判定）。 */
  attach(data: LevelRewardPanelData): void {
    this.data = data
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
    this.headerLabel = this.addLabel('Header', 0, top - 20, COLOR_COPPER_GOLD, 20)
    this.summaryLabel = this.addLabel('Summary', 0, top - 50, COLOR_TEXT, 17)
    // 三行都是居中的固定 y（间距只有 30），而 Label 会按文本把盒子撑高 —— 撑到两行就叠在邻居身上
    // （#363 在军队表头量出过同一形状）。关掉换行：盒子不可能撑成两行，字形也完全不碰。
    keepOneLine(this.headerLabel, 20)
    keepOneLine(this.summaryLabel, 17)
    // 这一行两用：列表还没拉回来时的那句话，或上一次领取的结果
    this.noticeLabel = this.addLabel('Notice', 0, top - 78, COLOR_TEXT_DIM, 14)
    keepOneLine(this.noticeLabel, 14)

    for (let index = 0; index < ROW_POOL_SIZE; index++) {
      const row = this.createRow(index)
      this.rowNodes.push(row.node)
      this.rowNames.push(row.name)
      this.rowRewards.push(row.reward)
      this.rowStates.push(row.state)
      this.buttons.push(row.button)
      this.captions.push(row.caption)
      this.rowLevels.push(null)
      this.rowClaimable.push(false)
    }
  }

  private createRow(index: number): {
    node: Node; name: Label; reward: Label; state: Label; button: Node; caption: Label
  } {
    const node = new Node('LevelRow')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
    graphics.fill()

    const name = this.addLabel('Name', -PANEL_WIDTH / 2 + PADDING, 14, COLOR_TEXT, 17, node)
    name.horizontalAlign = Label.HorizontalAlign.LEFT
    name.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    capWidth(name, 300)
    const reward = this.addLabel('Reward', -PANEL_WIDTH / 2 + PADDING, -13, COLOR_TEXT_DIM, 13, node)
    reward.horizontalAlign = Label.HorizontalAlign.LEFT
    reward.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    capWidth(reward, 330)
    const state = this.addLabel('State', PANEL_WIDTH / 2 - BUTTON_WIDTH - 200, 0, COLOR_TEXT_DIM, 14, node)
    state.horizontalAlign = Label.HorizontalAlign.RIGHT
    state.node.getComponent(UITransform)?.setAnchorPoint(1, 0.5)
    capWidth(state, 190)

    const button = this.createButton(node, index, PANEL_WIDTH / 2 - (BUTTON_WIDTH + PADDING) + BUTTON_WIDTH / 2)
    return { node, name, reward, state, button: button.node, caption: button.caption }
  }

  private createButton(parent: Node, index: number, x: number): { node: Node; caption: Label } {
    const button = new Node('ClaimButton')
    button.layer = parent.layer
    parent.addChild(button)
    button.setPosition(new Vec3(x, 0, 0))
    button.addComponent(UITransform).setContentSize(new Size(BUTTON_WIDTH, BUTTON_HEIGHT))
    const graphics = button.addComponent(Graphics)
    this.paintButton(graphics, COLOR_BUTTON_OFF)
    const caption = this.addLabel('Caption', 0, 0, COLOR_TEXT, 14, button)
    button.on('touch-start', (_event: EventTouch) => {
      const level = this.rowLevels[index] ?? null
      if (level !== null && this.rowClaimable[index] === true) {
        this.onClaim?.(level)
      }
    }, this)
    return { node: button, caption }
  }

  private paintButton(graphics: Graphics, fill: Color): void {
    graphics.clear()
    graphics.fillColor = fill
    graphics.roundRect(-BUTTON_WIDTH / 2, -BUTTON_HEIGHT / 2, BUTTON_WIDTH, BUTTON_HEIGHT, 5)
    graphics.fill()
  }

  private addLabel(name: string, x: number, y: number, color: Color, fontSize: number,
                   parent: Node = this.node): Label {
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
    if (data === null) {
      return
    }
    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    const navTop = -size.height / 2 + BOTTOM_RESERVED
    const usable = topY + ROW_HEIGHT / 2 - navTop
    const maxRows = Math.max(1, Math.floor(usable / (ROW_HEIGHT + ROW_GAP)))

    // 视图按实测高度算好窗口，再让纯逻辑层组装这一屏该画哪几级（起点由 windowStartOf 给）
    const view2: LevelRewardView = buildLevelRewardPanel(data.source, maxRows)

    if (this.headerLabel !== null) {
      this.headerLabel.string = view2.headerText
    }
    if (this.summaryLabel !== null) {
      this.summaryLabel.string = view2.summaryText
      this.summaryLabel.color = data.source.claimableCount > 0 ? COLOR_GOOD : COLOR_TEXT_DIM
    }
    if (this.noticeLabel !== null) {
      // 三级来源，按优先级：上一次操作的结果 > 空表提示 > 无话可说就留空
      this.noticeLabel.string = data.notice ?? view2.noticeText
      this.noticeLabel.color = data.notice !== null && data.notice !== undefined
        ? COLOR_GOOD : COLOR_TEXT_DIM
    }

    this.rowNodes.forEach((node, index) => {
      const row = view2.rows[index]
      node.active = row !== undefined
      if (row === undefined) {
        this.rowLevels[index] = null
        this.rowClaimable[index] = false
        return
      }
      // 行必须按序号往下排（池化节点建出来都在 y=0，不摆就是所有行叠在同一处 —— 商店面板踩过，见收口清单 #244）
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.rowLevels[index] = row.level
      this.rowClaimable[index] = row.claimable
      const graphics = node.getComponent(Graphics)
      if (graphics !== null) {
        graphics.clear()
        graphics.fillColor = row.locked ? COLOR_ROW_DIM : COLOR_ROW
        graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
        graphics.fill()
      }
      this.rowNames[index]!.string = row.nameText
      this.rowNames[index]!.color = row.locked ? COLOR_TEXT_DIM : COLOR_TEXT
      this.rowRewards[index]!.string = row.rewardText
      this.rowStates[index]!.string = row.stateText
      this.rowStates[index]!.color = row.claimable ? COLOR_GOOD : COLOR_TEXT_DIM
      this.paintButton(this.buttons[index]!.getComponent(Graphics)!,
        row.claimable ? COLOR_GOOD : COLOR_BUTTON_OFF)
      this.captions[index]!.string = row.claimed ? '已领' : (row.locked ? '未达' : '领取')
      this.captions[index]!.color = row.claimable ? COLOR_BACKGROUND : COLOR_TEXT_DIM
    })
  }
}
