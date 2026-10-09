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
 * 压在底部导航条下面。分页共用 `PanelPaging`，第一页自动落在待领附近；
 * 领取刷新保留当前页，之前与之后的等级都能通过翻页查看。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, Sprite, UITransform, Vec3, view } from 'cc'
import { buildLevelRewardPanel, levelRewardLayout } from '../game/levelReward/LevelRewardPanel'
import type { LevelRewardPanelData, LevelRewardView } from '../game/levelReward/LevelRewardPanel'
import { pageNotice } from '../game/ui/PanelPaging'
import { BRONZE_GOLD, IRON_SURFACE } from '../game/ui/UiTokens'
import { applySystemUiFont, capWidth, keepOneLine } from './UiFont'
import { applyIronButton, applySlicedSprite } from './ArtCatalog'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(...IRON_SURFACE)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_DIM = new Color(34, 31, 28, 255)
const COLOR_COPPER_GOLD = new Color(...BRONZE_GOLD)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)
const COLOR_BUTTON_OFF = new Color(64, 55, 46, 255)

const PANEL_WIDTH = 700
const ROW_HEIGHT = 66
const ROW_GAP = 6
/** 标题 + 那一行汇总 + 提示行合计占掉的高度（三行的 y 间距见 buildHeader）。 */
const PADDING = 16
const ROW_POOL_SIZE = 6
/** 屏幕底部要给导航条让出的高度（与其它面板同一个数：8 + 52 + 8）。 */
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
  /** null 表示首开按待领等级定位；attach 写后重拉保留当前页。 */
  private page: number | null = null
  private currentView: LevelRewardView | null = null
  private pageLabel: Label | null = null
  private prevPageButton: Node | null = null
  private nextPageButton: Node | null = null
  private prevPageCaption: Label | null = null
  private nextPageCaption: Label | null = null
  private background: Node | null = null
  private visibleWidth = 0
  private visibleHeight = 0

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
    this.currentView = null
    this.onClaim = null
  }

  override update(): void {
    const size = view.getVisibleSize()
    if (size.width === this.visibleWidth && size.height === this.visibleHeight) return
    this.buildBackground(size.width, size.height)
    this.render()
  }

  /** 装载整块视图（编排层递来的原始响应 + 上一次领取的结果行，本文件不改其中任何判定）。 */
  attach(data: LevelRewardPanelData): void {
    this.data = data
    this.render()
  }

  // ---------- 搭建 ----------

  private buildBackground(width: number, height: number): void {
    const node = this.background ?? new Node('Background')
    if (this.background === null) {
      node.layer = this.node.layer
      this.node.addChild(node)
      node.addComponent(UITransform)
      node.addComponent(Graphics)
      this.background = node
    }
    this.visibleWidth = width
    this.visibleHeight = height
    node.getComponent(UITransform)!.setContentSize(new Size(width, height))
    const graphics = node.getComponent(Graphics)!
    graphics.clear()
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
    this.pageLabel = this.addLabel('PageNotice', 0, 0, COLOR_TEXT_DIM, 15)
    keepOneLine(this.pageLabel, 15)
    this.pageLabel.node.active = false
    this.prevPageButton = this.createPagerButton('PrevPageButton', -PANEL_WIDTH / 2 + 46)
    this.nextPageButton = this.createPagerButton('NextPageButton', PANEL_WIDTH / 2 - 46)
  }

  private createPagerButton(name: string, x: number): Node {
    const button = new Node(name)
    button.layer = this.node.layer
    this.node.addChild(button)
    button.setPosition(new Vec3(x, 0, 0))
    button.addComponent(UITransform).setContentSize(new Size(64, 28))
    button.addComponent(Graphics)
    const caption = this.addLabel('Caption', 0, 0, COLOR_TEXT, 13, button)
    keepOneLine(caption, 13)
    const previous = name === 'PrevPageButton'
    caption.string = previous ? '上一页' : '下一页'
    if (previous) {
      this.prevPageCaption = caption
    } else {
      this.nextPageCaption = caption
    }
    button.on('touch-start', () => this.turnPage(previous ? -1 : 1), this)
    button.active = false
    return button
  }

  private turnPage(delta: number): void {
    const current = this.currentView
    if (current === null || (delta < 0 ? !current.canPrev : !current.canNext)) {
      return
    }
    this.page = current.page + delta
    this.render()
  }

  private paintPager(current: LevelRewardView, topY: number, capacity: number): void {
    const paged = current.pages > 1
    // 极矮窗口只够一个内容槽位时，把分页排放进表头下方的既有留白。
    // 不再向下追加一槽，避免页码和按钮挤进底部导航。
    const y = capacity === 1
      ? topY + ROW_HEIGHT / 2 + ROW_GAP + 14
      : topY - current.perPage * (ROW_HEIGHT + ROW_GAP)
    if (this.pageLabel !== null) {
      this.pageLabel.node.active = paged
      this.pageLabel.string = paged ? pageNotice(current.page, current.pages) : ''
      this.pageLabel.node.setPosition(new Vec3(0, y, 0))
    }
    for (const [button, caption, usable] of [
      [this.prevPageButton, this.prevPageCaption, current.canPrev],
      [this.nextPageButton, this.nextPageCaption, current.canNext],
    ] as Array<[Node | null, Label | null, boolean]>) {
      if (button === null || caption === null) {
        continue
      }
      button.active = paged
      button.setPosition(new Vec3(button.position.x, y, 0))
      caption.color = usable ? COLOR_TEXT : COLOR_TEXT_DIM
      const graphics = button.getComponent(Graphics)!
      graphics.clear()
      graphics.fillColor = usable ? COLOR_ROW : COLOR_BUTTON_OFF
      graphics.strokeColor = usable ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
      graphics.lineWidth = 1
      graphics.roundRect(-32, -14, 64, 28, 4)
      graphics.fill()
      graphics.stroke()
      this.styleButton(button, usable, 64, 28)
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
    applySlicedSprite(node, 'ui.plate.band', PANEL_WIDTH, ROW_HEIGHT)
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
    this.styleButton(button, false, BUTTON_WIDTH, BUTTON_HEIGHT)
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

  private styleButton(node: Node, enabled: boolean, width: number, height: number): void {
    if (applyIronButton(node, enabled ? 'normal' : 'disabled', width, height)) {
      node.getComponent(Sprite)!.color = enabled ? Color.WHITE : new Color(128, 128, 128, 255)
    }
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
    const layout = levelRewardLayout(size.height, this.rowNodes.length)
    const { topY, capacity } = layout
    const top = size.height / 2 - PADDING
    this.headerLabel?.node.setPosition(new Vec3(0, top - (layout.compact ? 12 : 20), 0))
    this.summaryLabel?.node.setPosition(new Vec3(layout.compact ? -180 : 0,
      top - (layout.compact ? 40 : 50), 0))
    this.noticeLabel?.node.setPosition(new Vec3(layout.compact ? 180 : 0,
      top - (layout.compact ? 40 : 78), 0))
    if (this.summaryLabel !== null) capWidth(this.summaryLabel, layout.compact ? 330 : PANEL_WIDTH)
    if (this.noticeLabel !== null) capWidth(this.noticeLabel, layout.compact ? 340 : PANEL_WIDTH)

    // 视图按实测高度算好窗口，再让纯逻辑层组装这一屏该画哪几级（起点由 windowStartOf 给）
    const initial = buildLevelRewardPanel(data.source, capacity, this.page)
    if (this.currentView !== null && this.currentView.perPage !== initial.perPage) {
      this.page = Math.floor(this.currentView.windowStart / initial.perPage)
    }
    const view2: LevelRewardView = buildLevelRewardPanel(data.source, capacity, this.page)
    this.page = view2.page
    this.currentView = view2

    if (this.headerLabel !== null) {
      this.headerLabel.string = capacity > 0 ? view2.headerText : '窗口太矮，请调大窗口或转为竖屏查看'
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
      const row = capacity > 0 ? view2.rows[index] : undefined
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
      const rowSprite = node.getComponent(Sprite)
      if (rowSprite !== null) rowSprite.color = row.claimable ? Color.WHITE : new Color(175, 175, 175, 255)
      this.rowNames[index]!.color = row.locked ? COLOR_TEXT_DIM : COLOR_TEXT
      this.rowRewards[index]!.string = row.rewardText
      this.rowStates[index]!.string = row.stateText
      this.rowStates[index]!.color = row.claimable ? COLOR_GOOD : COLOR_TEXT_DIM
      this.paintButton(this.buttons[index]!.getComponent(Graphics)!,
        row.claimable ? COLOR_GOOD : COLOR_BUTTON_OFF)
      this.styleButton(this.buttons[index]!, row.claimable, BUTTON_WIDTH, BUTTON_HEIGHT)
      this.captions[index]!.string = row.claimed ? '已领' : (row.locked ? '未达' : '领取')
      this.captions[index]!.color = row.claimable ? COLOR_TEXT : COLOR_TEXT_DIM
    })
    this.paintPager(view2, topY, capacity)
    if (capacity === 0) {
      if (this.pageLabel !== null) this.pageLabel.node.active = false
      if (this.prevPageButton !== null) this.prevPageButton.active = false
      if (this.nextPageButton !== null) this.nextPageButton.active = false
    }
  }
}
