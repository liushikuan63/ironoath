/**
 * 职责：商店面板 —— 四个币种页签、货架行与兑换（B24 S-b）。
 * 依赖：cc（渲染）、game/shop/ShopPanel（展示数据组装，已单测）、scene/ArtCatalog、scene/UiFont。
 *
 * <p><b>本场景不做任何判定</b>（铁律 2）：能不能兑换、还差几级、本期限购剩几个、余额够不够，
 * 全部来自服务端下发的那份货架（`purchasable` / `lockReason` / `remaining`）。
 * 本文件只做两件表现层的事：把行画出来、把点击意图回抛（`onTab` / `onBuy`）。
 *
 * <p><b>锁定行也要画出来并带原因</b>：等级不够的商品显示成「主城 5 级解锁」而不是消失 ——
 * 让玩家知道有这个东西，正是解锁类门槛存在的意义；而一个没有文案的灰按钮会被当成坏了。
 *
 * <p><b>行数按实测可视高度算</b>（与军队面板同一条纪律）：写死行数会在矮窗口里把最后一行
 * 压在底部导航条下面 —— 画了但玩家看不见，比少画一行更难发现；画不下的数量在表头说出来。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { canBuy, SHOP_TABS, shopRowStateText } from '../game/shop/ShopPanel'
import type { ShopRow } from '../game/shop/ShopPanel'
import type { ShopView } from '../game/session/AppRoot'
import type { ShopCurrency } from '../net/generated/ShopProtocol'
import { applySystemUiFont } from './UiFont'
import { truncatedNotice } from '../game/ui/TruncatedList'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_LOCKED = new Color(34, 31, 28, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)
const COLOR_WARNING = new Color(200, 60, 40, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 62
const ROW_GAP = 5
const HEADER_HEIGHT = 150
const PADDING = 16
const TAB_WIDTH = 110
/**
 * 池子容量。一屏画几行按实测可视高度算（见 render），这里给的是上限 ——
 * 一个币种的货架通常十几行，一次画不下就靠表头那句「另有 N 项未显示」交代。
 */
const ROW_POOL_SIZE = 6
/** 屏幕底部要给导航条让出的高度（8 下边距 + 52 导航条 + 8 安全间隙，与军队面板同一个数）。 */
const BOTTOM_RESERVED = 68

@ccclass('ShopPanelView')
export class ShopPanelView extends Component {
  private panel: ShopView | null = null
  private readonly tabNodes = new Map<ShopCurrency, Node>()
  private headerLabel: Label | null = null
  private balanceLabel: Label | null = null
  private noticeLabel: Label | null = null
  private readonly rowNodes: Node[] = []
  private readonly rowName: Label[] = []
  private readonly rowPrice: Label[] = []
  private readonly rowLimit: Label[] = []
  private readonly rowState: Label[] = []
  private readonly rowButton: Node[] = []
  private readonly rowButtonCaption: Label[] = []
  /** 每一行当前对应哪一个 rowId（池化节点复用时行数据会变，点击要知道点的是谁）。 */
  private readonly rowIds: Array<string | null> = []

  /** 切页签：由编排层去拉那一页（四个币种的账本各是各的）。 */
  onTab: ((currency: ShopCurrency) => void) | null = null
  /** 兑换一行。可不可兑换由服务端说了算，编排层会在本地先挡一次并说明原因。 */
  onBuy: ((rowId: string) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.buildHeader(size.height)
    this.node.on('touch-start', (_event: EventTouch) => {
      /* 面板本身不吃触摸，这一句只是防止事件穿透到地图 */
    }, this)
  }

  override onDestroy(): void {
    this.tabNodes.clear()
    this.rowNodes.length = 0
    this.onTab = null
    this.onBuy = null
  }

  /** 装载一整块商店视图（编排层组装好的，本文件不改其中任何判定）。 */
  attach(shopView: ShopView): void {
    this.panel = shopView
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
    this.balanceLabel = this.addLabel('Balance', 0, top - 50, COLOR_TEXT, 17)
    // 这一行两用：`open=false` 时的那句话，或上一次兑换的结果（临时提示）
    this.noticeLabel = this.addLabel('Notice', 0, top - 76, COLOR_TEXT_DIM, 15)
    this.noticeLabel.node.getComponent(UITransform)?.setContentSize(new Size(PANEL_WIDTH - 2 * PADDING, 20))
    this.noticeLabel.overflow = Label.Overflow.SHRINK

    const startX = -(SHOP_TABS.length - 1) * TAB_WIDTH / 2
    SHOP_TABS.forEach((tab, index) => {
      const node = new Node(`Tab_${tab.currency}`)
      node.layer = this.node.layer
      this.node.addChild(node)
      node.setPosition(new Vec3(startX + index * TAB_WIDTH, top - 112, 0))
      node.addComponent(UITransform).setContentSize(new Size(TAB_WIDTH - 8, 34))
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = COLOR_PANEL
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 1
      graphics.roundRect(-(TAB_WIDTH - 8) / 2, -17, TAB_WIDTH - 8, 34, 5)
      graphics.fill()
      graphics.stroke()
      this.addLabel('Caption', 0, 0, COLOR_TEXT, 15, node).string = tab.label
      const currency = tab.currency
      node.on('touch-start', (_event: EventTouch) => this.onTab?.(currency), this)
      this.tabNodes.set(currency, node)
    })

    for (let index = 0; index < ROW_POOL_SIZE; index++) {
      const row = this.createRow(index)
      this.rowNodes.push(row.node)
      this.rowName.push(row.name)
      this.rowPrice.push(row.price)
      this.rowLimit.push(row.limit)
      this.rowState.push(row.state)
      this.rowButton.push(row.button)
      this.rowButtonCaption.push(row.buttonCaption)
      this.rowIds.push(null)
    }
  }

  private createRow(index: number): {
    node: Node; name: Label; price: Label; limit: Label; state: Label
    button: Node; buttonCaption: Label
  } {
    const node = new Node('ShopRow')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
    graphics.fill()

    const name = this.addLabel('Name', -PANEL_WIDTH / 2 + PADDING + 12, 15, COLOR_TEXT, 18, node)
    name.horizontalAlign = Label.HorizontalAlign.LEFT
    name.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    name.node.getComponent(UITransform)?.setContentSize(new Size(320, 24))
    name.overflow = Label.Overflow.SHRINK
    const price = this.addLabel('Price', -PANEL_WIDTH / 2 + PADDING + 12, -9, COLOR_COPPER_GOLD, 15, node)
    price.horizontalAlign = Label.HorizontalAlign.LEFT
    price.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    price.node.getComponent(UITransform)?.setContentSize(new Size(320, 22))
    price.overflow = Label.Overflow.SHRINK
    const limit = this.addLabel('Limit', 0, 6, COLOR_TEXT_DIM, 14, node)
    limit.node.getComponent(UITransform)?.setContentSize(new Size(230, 20))
    limit.overflow = Label.Overflow.SHRINK
    const state = this.addLabel('State', 0, -14, COLOR_TEXT_DIM, 14, node)
    state.node.getComponent(UITransform)?.setContentSize(new Size(230, 20))
    state.overflow = Label.Overflow.SHRINK

    const button = new Node('BuyButton')
    button.layer = node.layer
    node.addChild(button)
    button.setPosition(new Vec3(PANEL_WIDTH / 2 - 58, 0, 0))
    button.addComponent(UITransform).setContentSize(new Size(92, 34))
    const buttonGraphics = button.addComponent(Graphics)
    this.paintButton(buttonGraphics, COLOR_GOOD)
    const buttonCaption = this.addLabel('Caption', 0, 0, COLOR_BACKGROUND, 15, button)
    button.on('touch-start', (_event: EventTouch) => {
      const rowId = this.rowIds[index] ?? null
      if (rowId !== null) {
        this.onBuy?.(rowId)
      }
    }, this)
    return { node, name, price, limit, state, button, buttonCaption }
  }

  /** 按钮底色：可兑换是绿的、锁定是灰的 —— 颜色之外还有 state 那一行文字说明原因。 */
  private paintButton(graphics: Graphics, fill: Color): void {
    graphics.clear()
    graphics.fillColor = fill
    graphics.roundRect(-46, -17, 92, 34, 5)
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
    const panel = this.panel
    if (panel === null) {
      return
    }
    for (const [currency, node] of this.tabNodes) {
      const graphics = node.getComponent(Graphics)
      if (graphics === null) {
        continue
      }
      graphics.clear()
      graphics.fillColor = currency === panel.currency ? COLOR_ROW : COLOR_PANEL
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = currency === panel.currency ? 2 : 1
      graphics.roundRect(-(TAB_WIDTH - 8) / 2, -17, TAB_WIDTH - 8, 34, 5)
      graphics.fill()
      graphics.stroke()
    }

    if (this.balanceLabel !== null) {
      // 余额为 null ⇒ 一个字都不写（协议：那是"这一页还没有出处"，不是"我有 0 个"）
      this.balanceLabel.string = panel.balanceText ?? ''
      this.balanceLabel.color = panel.balanceText === null ? COLOR_TEXT_DIM : COLOR_TEXT
    }
    if (this.noticeLabel !== null) {
      // 没开那一页的说明优先；否则显示上一次兑换的结果
      this.noticeLabel.string = panel.noticeText ?? panel.notice ?? ''
      this.noticeLabel.color = panel.noticeText !== null ? COLOR_WARNING : COLOR_GOOD
    }

    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    const navTop = -size.height / 2 + BOTTOM_RESERVED
    const usable = topY + ROW_HEIGHT / 2 - navTop
    const maxRows = Math.max(1, Math.floor(usable / (ROW_HEIGHT + ROW_GAP)))
    const drawn = Math.min(panel.rows.length, maxRows)

    if (this.headerLabel !== null) {
      this.headerLabel.string = `货架 ${drawn}/${panel.rows.length} 件`
        + (panel.open ? '' : '（这一页暂未开放）')
        + (panel.rows.length > drawn ? ` · ${truncatedNotice('件', panel.rows.length - drawn)}` : '')
      this.headerLabel.color = panel.open ? COLOR_COPPER_GOLD : COLOR_WARNING
    }

    this.rowNodes.forEach((node, index) => {
      const row: ShopRow | undefined = panel.rows[index]
      node.active = index < drawn && row !== undefined
      if (row === undefined || index >= drawn) {
        this.rowIds[index] = null
        return
      }
      this.rowIds[index] = row.rowId
      const graphics = node.getComponent(Graphics)
      if (graphics !== null) {
        graphics.clear()
        graphics.fillColor = row.purchasable ? COLOR_ROW : COLOR_ROW_LOCKED
        graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
        graphics.fill()
      }
      this.rowName[index]!.string = row.name
      this.rowName[index]!.color = row.purchasable ? COLOR_TEXT : COLOR_TEXT_DIM
      this.rowPrice[index]!.string = row.priceText
      this.rowLimit[index]!.string = row.limitText
      this.rowState[index]!.string = shopRowStateText(row)
      this.rowState[index]!.color = row.purchasable ? COLOR_GOOD : COLOR_TEXT_DIM
      const buttonGraphics = this.rowButton[index]!.getComponent(Graphics)
      if (buttonGraphics !== null) {
        this.paintButton(buttonGraphics, row.purchasable ? COLOR_GOOD : COLOR_PANEL)
      }
      // 锁定行**按钮仍可点**：点了由编排层把服务端给的原因说出去（灰按钮什么都不做才是坏体验）
      this.rowButtonCaption[index]!.string = canBuy(row) ? '兑换' : '不可兑换'
      this.rowButtonCaption[index]!.color = row.purchasable ? COLOR_BACKGROUND : COLOR_TEXT_DIM
    })
  }
}
