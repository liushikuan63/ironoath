/**
 * 职责：商店面板 —— 四个币种货架与月卡页签、兑换和月卡购买/日包领取（B24 S-b）。
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

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, Sprite, UITransform, Vec3, view } from 'cc'
import { canBuy, SHOP_TABS, shopRowStateText } from '../game/shop/ShopPanel'
import type { ShopRow } from '../game/shop/ShopPanel'
import type { MonthlyCardRow } from '../game/shop/MonthlyCardPanel'
import type { MonthlyShopView, ShopView } from '../game/session/AppRoot'
import type { ShopCurrency } from '../net/generated/ShopProtocol'
import { applySystemUiFont, capWidth } from './UiFont'
import { applySlicedSprite } from './ArtCatalog'
import { applyDialogButton } from './DialogStyle'
import {
  clampPage, contentPerPage, pageCount, pageNotice, pageWindow,
} from '../game/ui/PanelPaging'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_LOCKED = new Color(34, 31, 28, 255)
const COLOR_COPPER_GOLD = new Color(218, 179, 107, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(185, 174, 151, 255)
const COLOR_GOOD = new Color(218, 179, 107, 255)
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
const MONTHLY_CARD_HEIGHT = 240
const MONTHLY_COMPACT_HEIGHT = 184
const MONTHLY_CARD_GAP = 12
const MONTHLY_CARD_POOL_SIZE = 3
const MONTHLY_HEADER_HEIGHT = 138
const MONTHLY_PAGER_HEIGHT = 32

interface MonthlyCardNodes {
  readonly node: Node
  readonly name: Label
  readonly price: Label
  readonly offer: Label
  readonly status: Label
  readonly expiry: Label
  readonly refresh: Label
  readonly claim: Node
  readonly claimCaption: Label
  readonly purchase: Node
  readonly purchaseCaption: Label
}

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
  /** 当前页（0 起）。换币种归零、同一币种重新拉取只 clamp（见 attach）。 */
  private page = 0
  /** 上一次 attach 是哪一页的账本，用来分辨"换页签"与"买完重拉同一页"。 */
  private lastCurrency: ShopCurrency | null = null
  private prevPageButton: Node | null = null
  private nextPageButton: Node | null = null
  private prevPageCaption: Label | null = null
  private nextPageCaption: Label | null = null
  private canPrev = false
  private canNext = false
  private monthlyMode = false
  private monthlyPanel: MonthlyShopView | null = null
  private monthlyTab: Node | null = null
  private monthlyRoot: Node | null = null
  private readonly monthlyNodes: MonthlyCardNodes[] = []
  private readonly monthlyRows: Array<MonthlyCardRow | null> = []

  /** 切页签：由编排层去拉那一页（四个币种的账本各是各的）。 */
  onTab: ((currency: ShopCurrency) => void) | null = null
  /** 兑换一行。可不可兑换由服务端说了算，编排层会在本地先挡一次并说明原因。 */
  onBuy: ((rowId: string) => void) | null = null
  onMonthlyTab: (() => void) | null = null
  onCardClaim: ((productId: string | null) => void) | null = null
  onCardPurchase: ((productId: string) => void) | null = null

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
    this.onMonthlyTab = null
    this.onCardClaim = null
    this.onCardPurchase = null
    this.monthlyNodes.length = 0
    this.monthlyRows.length = 0
  }

  /** 装载一整块商店视图（编排层组装好的，本文件不改其中任何判定）。 */
  attach(shopView: ShopView): void {
    // 换币种归零（四个账本是四份不同的货架），买完重拉同一页只 clamp：
    // 兑换后编排层会再 attach 一次，归零会把玩家刚买的那一行从屏上弹走
    if (this.monthlyMode || this.lastCurrency !== shopView.currency) {
      this.page = 0
      this.lastCurrency = shopView.currency
    }
    this.monthlyMode = false
    this.panel = shopView
    this.render()
  }

  /** 月卡只展示服务器组装的权益与资格，同一页领取刷新保留当前卡。 */
  attachMonthlyCards(panel: MonthlyShopView): void {
    if (!this.monthlyMode) this.page = 0
    this.monthlyMode = true
    this.monthlyPanel = panel
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
    capWidth(this.noticeLabel, PANEL_WIDTH - 2 * PADDING)
    // 两颗翻页键与那句页码同一行、摆在两端，y 由 render() 跟着最后一行走
    this.prevPageButton = this.buildPagerButton('PrevPageButton', -PANEL_WIDTH / 2 + 46)
    this.nextPageButton = this.buildPagerButton('NextPageButton', PANEL_WIDTH / 2 - 46)

    const startX = -SHOP_TABS.length * TAB_WIDTH / 2
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

    this.monthlyTab = new Node('Tab_MonthlyCards')
    this.monthlyTab.layer = this.node.layer
    this.node.addChild(this.monthlyTab)
    this.monthlyTab.setPosition(new Vec3(startX + SHOP_TABS.length * TAB_WIDTH, top - 112, 0))
    this.monthlyTab.addComponent(UITransform).setContentSize(new Size(TAB_WIDTH - 8, 34))
    this.monthlyTab.addComponent(Graphics)
    this.paintTab(this.monthlyTab, false)
    this.addLabel('Caption', 0, 0, COLOR_TEXT, 15, this.monthlyTab).string = '月卡'
    this.monthlyTab.on('touch-start', () => this.onMonthlyTab?.(), this)

    this.monthlyRoot = new Node('MonthlyCards')
    this.monthlyRoot.layer = this.node.layer
    this.node.addChild(this.monthlyRoot)
    this.monthlyRoot.active = false
    for (let index = 0; index < MONTHLY_CARD_POOL_SIZE; index++) {
      this.monthlyNodes.push(this.createMonthlyCard(index, this.monthlyRoot))
      this.monthlyRows.push(null)
    }

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

  /** 一颗 64×28 的翻页键，照本文件行上「兑换」那颗的画法。 */
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
    const caption = this.addLabel('Caption', 0, 0, COLOR_TEXT, 13, node)
    caption.string = name === 'PrevPageButton' ? '上一页' : '下一页'
    // 建出来先收着：不先收的话"货架还没到"那一态会露着两颗点了没反应的键
    //（#345 口径，#449 与 #450 各修过一次同一族）
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
   * 但"点了什么反应都没有"正是 #345 那条口径要挡的观感。
   */
  private turnPage(delta: number): void {
    if (delta < 0 && !this.canPrev) return
    if (delta > 0 && !this.canNext) return
    this.page += delta
    this.render()
  }

  /** 两颗键跟着这一屏让出来的那一格走；只有一页时整对收掉（#345）。 */
  private paintPager(pages: number, rowY: number): void {
    const paged = pages > 1
    this.canPrev = this.page > 0
    this.canNext = this.page < pages - 1
    for (const [button, caption, usable] of [
      [this.prevPageButton, this.prevPageCaption, this.canPrev],
      [this.nextPageButton, this.nextPageCaption, this.canNext],
    ] as Array<[Node | null, Label | null, boolean]>) {
      if (button === null || caption === null) continue
      button.active = paged
      button.setPosition(new Vec3(button.position.x, rowY, 0))
      caption.color = usable ? COLOR_TEXT : COLOR_TEXT_DIM
      applyDialogButton(button, usable, 64, 28)
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
    capWidth(name, 320)
    const price = this.addLabel('Price', -PANEL_WIDTH / 2 + PADDING + 12, -9, COLOR_COPPER_GOLD, 15, node)
    price.horizontalAlign = Label.HorizontalAlign.LEFT
    price.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    capWidth(price, 320)
    const limit = this.addLabel('Limit', 0, 6, COLOR_TEXT_DIM, 14, node)
    capWidth(limit, 230)
    const state = this.addLabel('State', 0, -14, COLOR_TEXT_DIM, 14, node)
    capWidth(state, 230)

    const button = new Node('BuyButton')
    button.layer = node.layer
    node.addChild(button)
    button.setPosition(new Vec3(PANEL_WIDTH / 2 - 58, 0, 0))
    button.addComponent(UITransform).setContentSize(new Size(92, 34))
    const buttonGraphics = button.addComponent(Graphics)
    this.paintButton(buttonGraphics, COLOR_GOOD)
    const buttonCaption = this.addLabel('Caption', 0, 0, COLOR_TEXT, 15, button)
    button.on('touch-start', (_event: EventTouch) => {
      const rowId = this.rowIds[index] ?? null
      if (rowId !== null) {
        this.onBuy?.(rowId)
      }
    }, this)
    return { node, name, price, limit, state, button, buttonCaption }
  }

  /** 状态仍由文字说明，材质统一为铁饰按钮。 */
  private paintButton(graphics: Graphics, fill: Color): void {
    graphics.clear()
    graphics.fillColor = fill
    graphics.roundRect(-46, -17, 92, 34, 5)
    graphics.fill()
  }

  private createMonthlyCard(index: number, parent: Node): MonthlyCardNodes {
    const node = new Node(`MonthlyCardRow_${index}`)
    node.layer = parent.layer
    parent.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, MONTHLY_CARD_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -MONTHLY_CARD_HEIGHT / 2, PANEL_WIDTH, MONTHLY_CARD_HEIGHT, 6)
    graphics.fill()
    const name = this.monthlyLabel(node, 'Name', -PANEL_WIDTH / 2 + PADDING, 100, 380, 26, 20, COLOR_COPPER_GOLD)
    const price = this.monthlyLabel(node, 'Price', 106, 100, 218, 26, 16, COLOR_TEXT)
    const offer = this.monthlyLabel(node, 'Offer', -PANEL_WIDTH / 2 + PADDING, 59, PANEL_WIDTH - 2 * PADDING, 48, 15, COLOR_TEXT, true)
    const status = this.monthlyLabel(node, 'Status', -PANEL_WIDTH / 2 + PADDING, 12, PANEL_WIDTH - 2 * PADDING, 42, 14, COLOR_GOOD, true)
    const expiry = this.monthlyLabel(node, 'Expiry', -PANEL_WIDTH / 2 + PADDING, -26, PANEL_WIDTH - 2 * PADDING, 20, 14, COLOR_TEXT_DIM)
    const refresh = this.monthlyLabel(node, 'Refresh', -PANEL_WIDTH / 2 + PADDING, -50, PANEL_WIDTH - 2 * PADDING, 20, 14, COLOR_TEXT_DIM)
    const claim = this.monthlyButton(node, 'ClaimButton', 150, () => {
      const row = this.monthlyRows[index]
      if (this.monthlyMode && row?.claimable) this.onCardClaim?.(row.productId)
    })
    const purchase = this.monthlyButton(node, 'PurchaseButton', 274, () => {
      const row = this.monthlyRows[index]
      if (this.monthlyMode && row?.purchasable && row.productId !== null) this.onCardPurchase?.(row.productId)
    })
    node.active = false
    return { node, name, price, offer, status, expiry, refresh, claim: claim.node, claimCaption: claim.caption,
      purchase: purchase.node, purchaseCaption: purchase.caption }
  }

  private monthlyLabel(parent: Node, name: string, x: number, y: number, width: number, height: number,
                       fontSize: number, color: Color, wrap = false): Label {
    const label = this.addLabel(name, x, y, color, fontSize, parent)
    this.placeMonthlyLabel(label, x, y, width, height, fontSize, wrap)
    return label
  }

  private placeMonthlyLabel(label: Label, x: number, y: number, width: number, height: number,
                            fontSize: number, wrap = false): void {
    label.node.setPosition(new Vec3(x, y, 0))
    const transform = label.node.getComponent(UITransform)
    transform?.setAnchorPoint(0, 0.5)
    transform?.setContentSize(new Size(width, height))
    label.horizontalAlign = Label.HorizontalAlign.LEFT
    label.fontSize = fontSize
    label.enableWrapText = wrap
    label.lineHeight = fontSize + 6
    label.overflow = Label.Overflow.SHRINK
  }

  /** 矮逻辑窗口改排版、时间并列；按钮保留至少32px高度，不缩放整卡。 */
  private layoutMonthlyCard(card: MonthlyCardNodes, compact: boolean): void {
    const height = compact ? MONTHLY_COMPACT_HEIGHT : MONTHLY_CARD_HEIGHT
    card.node.getComponent(UITransform)?.setContentSize(new Size(PANEL_WIDTH, height))
    const graphics = card.node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      graphics.fillColor = COLOR_ROW
      graphics.roundRect(-PANEL_WIDTH / 2, -height / 2, PANEL_WIDTH, height, 6)
      graphics.fill()
    }
    this.paintCardSurface(card.node, height)
    // gilt母版四边24px，内容与操作均在框带内侧；紧凑档不缩放整张卡。
    const left = -PANEL_WIDTH / 2 + 32
    this.placeMonthlyLabel(card.name, left, compact ? 56 : 82, 400, compact ? 22 : 26, compact ? 18 : 21)
    this.placeMonthlyLabel(card.price, 116, compact ? 56 : 82, 192, compact ? 22 : 26, compact ? 14 : 16)
    this.placeMonthlyLabel(card.offer, left, compact ? 26 : 40, 616, compact ? 30 : 44, compact ? 14 : 16, true)
    this.placeMonthlyLabel(card.status, left, compact ? -4 : -10, 616, compact ? 22 : 32, 14, true)
    this.placeMonthlyLabel(card.expiry, left, compact ? -26 : -42, 300, compact ? 18 : 20, 13)
    this.placeMonthlyLabel(card.refresh, 8, compact ? -26 : -42, 300, compact ? 18 : 20, 13)
    for (const button of [card.claim, card.purchase]) {
      button.setPosition(new Vec3(button === card.claim ? 138 : 260, compact ? -52 : -76, 0))
      button.getComponent(UITransform)?.setContentSize(new Size(108, compact ? 32 : 36))
    }
  }

  private paintCardSurface(node: Node, height: number): void {
    // Graphics和Sprite分节点，避免Cocos只登记首个UIRenderer使位图不显示。
    let art = node.getChildByName('CardSurface')
    if (art === null) {
      art = new Node('CardSurface')
      art.layer = node.layer
      node.addChild(art)
      art.addComponent(UITransform)
      art.setSiblingIndex(0)
    }
    const loaded = applySlicedSprite(art, 'ui.panel.gilt', PANEL_WIDTH, height)
    art.active = loaded
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) graphics.enabled = !loaded
    const sprite = art.getComponent(Sprite)
    if (sprite !== null) sprite.enabled = loaded
  }

  private monthlyButton(parent: Node, name: string, x: number, pressed: () => void): { node: Node; caption: Label } {
    const node = new Node(name)
    node.layer = parent.layer
    parent.addChild(node)
    node.setPosition(new Vec3(x, -91, 0))
    node.addComponent(UITransform).setContentSize(new Size(108, 34))
    node.addComponent(Graphics)
    const caption = this.monthlyLabel(node, 'Caption', -50, 0, 100, 22, 15, COLOR_TEXT)
    caption.horizontalAlign = Label.HorizontalAlign.CENTER
    node.on('touch-start', pressed, this)
    return { node, caption }
  }

  private paintMonthlyButton(node: Node, caption: Label, usable: boolean, text: string): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      graphics.fillColor = COLOR_PANEL
      const height = node.getComponent(UITransform)?.height ?? 34
      graphics.roundRect(-54, -height / 2, 108, height, 5)
      graphics.fill()
    }
    caption.string = text
    applyDialogButton(node, usable, 108, node.getComponent(UITransform)?.height ?? 34, usable)
    caption.color = usable ? COLOR_TEXT : COLOR_TEXT_DIM
  }

  private paintTab(node: Node, selected: boolean): void {
    const graphics = node.getComponent(Graphics)
    if (graphics === null) return
    graphics.clear()
    graphics.fillColor = selected ? COLOR_ROW : COLOR_PANEL
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = selected ? 2 : 1
    graphics.roundRect(-(TAB_WIDTH - 8) / 2, -17, TAB_WIDTH - 8, 34, 5)
    graphics.fill()
    graphics.stroke()
    applyDialogButton(node, true, TAB_WIDTH - 8, 34, selected)
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
    this.layoutHeader(this.monthlyMode)
    if (this.monthlyRoot !== null) this.monthlyRoot.active = this.monthlyMode
    if (this.monthlyTab !== null) this.paintTab(this.monthlyTab, this.monthlyMode)
    if (this.monthlyMode) {
      for (const node of this.tabNodes.values()) this.paintTab(node, false)
      this.renderMonthlyCards()
      return
    }
    if (this.prevPageCaption !== null) this.prevPageCaption.string = '上一页'
    if (this.nextPageCaption !== null) this.nextPageCaption.string = '下一页'
    const panel = this.panel
    if (panel === null) {
      return
    }
    for (const [currency, node] of this.tabNodes) {
      const graphics = node.getComponent(Graphics)
      if (graphics === null) {
        continue
      }
      this.paintTab(node, currency === panel.currency)
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
    // 容量取"按窗口算出来的行数"与"实际建出来的行数"里小的那一个：行池只有 `ROW_POOL_SIZE`
    // 行，从前按窗口算出 7 就把表头写成 7、把"另有 N 件"少算一件 —— 屏上只有 6 行（#244 那一族）
    const capacity = Math.max(1,
      Math.min(Math.floor(usable / (ROW_HEIGHT + ROW_GAP)), this.rowNodes.length))
    const total = panel.rows.length
    // 共几页、夹到哪一页、切哪一段用同一个 perPage（`PanelPaging` 那条原话）：从前这里
    // 只画第一屏再把剩下的写成「另有 N 件未显示」，那几件商品玩家永远买不到（#307）
    const perPage = contentPerPage(total, capacity)
    const pages = pageCount(total, perPage)
    this.page = clampPage(this.page, total, perPage)
    const slice = pageWindow(total, this.page, perPage)
    const rows = panel.rows.slice(slice.start, slice.end)
    const drawn = rows.length

    if (this.headerLabel !== null) {
      this.headerLabel.string = `货架 ${drawn}/${total} 件`
        + (panel.open ? '' : '（这一页暂未开放）')
        + (pages > 1 ? ` · ${pageNotice(this.page, pages)}` : '')
      this.headerLabel.color = panel.open ? COLOR_COPPER_GOLD : COLOR_WARNING
    }
    this.paintPager(pages, topY - drawn * (ROW_HEIGHT + ROW_GAP) - 8)

    this.rowNodes.forEach((node, index) => {
      const row: ShopRow | undefined = rows[index]
      node.active = index < drawn && row !== undefined
      if (row === undefined || index >= drawn) {
        this.rowIds[index] = null
        return
      }
      // 行必须**按序号往下排**：池化节点建出来都在 y=0，不摆就是所有行叠在同一处 ——
      // 表现是"表头说 4 件、屏幕上只看得见 1 件"，而单测、类型检查、构建全绿
      // （2026-09-19 由帧探针的截图发现，见收口清单 #244）
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
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
      applyDialogButton(this.rowButton[index]!, row.purchasable, 92, 34, row.purchasable)
      // 锁定行**按钮仍可点**：点了由编排层把服务端给的原因说出去（灰按钮什么都不做才是坏体验）
      this.rowButtonCaption[index]!.string = canBuy(row) ? '兑换' : '不可兑换'
      this.rowButtonCaption[index]!.color = row.purchasable ? COLOR_TEXT : COLOR_TEXT_DIM
    })
  }

  private renderMonthlyCards(): void {
    for (let index = 0; index < this.rowNodes.length; index++) {
      this.rowNodes[index]!.active = false
      this.rowIds[index] = null
    }
    const panel = this.monthlyPanel
    if (panel === null) return
    const size = view.getVisibleSize()
    const top = size.height / 2 - PADDING - MONTHLY_HEADER_HEIGHT
    const navTop = -size.height / 2 + BOTTOM_RESERVED
    const compact = top - navTop < MONTHLY_CARD_HEIGHT + MONTHLY_PAGER_HEIGHT
    const height = compact ? MONTHLY_COMPACT_HEIGHT : MONTHLY_CARD_HEIGHT
    // 翻页独立留出32px，旧卡与未来服务端扩展行都不能掉到导航条下。
    const capacity = Math.max(1, Math.min(MONTHLY_CARD_POOL_SIZE,
      Math.floor((top - navTop - MONTHLY_PAGER_HEIGHT + MONTHLY_CARD_GAP) / (height + MONTHLY_CARD_GAP))))
    const pages = pageCount(panel.rows.length, capacity)
    this.page = clampPage(this.page, panel.rows.length, capacity)
    const window = pageWindow(panel.rows.length, this.page, capacity)
    const rows = panel.rows.slice(window.start, window.end)
    if (this.headerLabel !== null) {
      this.headerLabel.string = `月卡 ${rows.length}/${panel.rows.length} 张`
        + (pages > 1 ? ` · ${pageNotice(this.page, pages)}` : '')
      this.headerLabel.color = COLOR_COPPER_GOLD
    }
    if (this.balanceLabel !== null) {
      this.balanceLabel.string = '日包需手动领取，有效期内可补领，以当前可领天数为准'
      this.balanceLabel.color = COLOR_TEXT
    }
    if (this.noticeLabel !== null) {
      this.noticeLabel.string = panel.notice ?? panel.noticeText ?? ''
      this.noticeLabel.color = COLOR_TEXT
    }
    if (this.prevPageCaption !== null) this.prevPageCaption.string = capacity > 1 ? '上一页' : '上一卡'
    if (this.nextPageCaption !== null) this.nextPageCaption.string = capacity > 1 ? '下一页' : '下一卡'
    this.paintPager(pages, top - rows.length * height - Math.max(0, rows.length - 1) * MONTHLY_CARD_GAP - 18)
    this.monthlyNodes.forEach((card, index) => {
      const row = rows[index] ?? null
      this.monthlyRows[index] = row
      card.node.active = row !== null
      if (row === null) return
      this.layoutMonthlyCard(card, compact)
      card.node.setPosition(new Vec3(0, top - height / 2 - index * (height + MONTHLY_CARD_GAP), 0))
      card.name.string = row.name
      card.price.string = row.priceText ?? '已购权益保留'
      card.offer.string = row.offerText
      card.status.string = row.statusText
      card.status.color = row.claimable ? COLOR_GOOD : COLOR_TEXT_DIM
      card.expiry.string = row.expiryText
      card.refresh.string = row.refreshText
      this.paintMonthlyButton(card.claim, card.claimCaption, row.claimable, row.claimable ? '领取日包' : '暂无可领')
      this.paintMonthlyButton(card.purchase, card.purchaseCaption, row.purchasable, row.purchasable ? '购买' : '当前停售')
    })
  }

  /** 两行支付/领取回执只占月卡自己的表头；返回普通货架还原旧槽位。 */
  private layoutHeader(monthly: boolean): void {
    const top = view.getVisibleSize().height / 2 - PADDING
    if (this.headerLabel !== null) {
      this.headerLabel.node.setPosition(new Vec3(0, top - (monthly ? 16 : 20), 0))
      this.headerLabel.lineHeight = monthly ? 26 : 40
      this.headerLabel.node.getComponent(UITransform)?.setContentSize(new Size(PANEL_WIDTH - 2 * PADDING, monthly ? 26 : 0))
    }
    if (this.balanceLabel !== null) {
      this.balanceLabel.node.setPosition(new Vec3(0, top - (monthly ? 43 : 50), 0))
      this.balanceLabel.fontSize = monthly ? 14 : 17
      this.balanceLabel.lineHeight = monthly ? 20 : 40
      this.balanceLabel.enableWrapText = !monthly
      this.balanceLabel.overflow = monthly ? Label.Overflow.SHRINK : Label.Overflow.NONE
      this.balanceLabel.node.getComponent(UITransform)?.setContentSize(new Size(PANEL_WIDTH - 2 * PADDING, monthly ? 20 : 0))
    }
    if (this.noticeLabel !== null) {
      this.noticeLabel.node.setPosition(new Vec3(0, top - (monthly ? 75 : 76), 0))
      this.noticeLabel.fontSize = monthly ? 14 : 15
      this.noticeLabel.lineHeight = monthly ? 20 : 40
      this.noticeLabel.enableWrapText = true
      this.noticeLabel.node.getComponent(UITransform)?.setContentSize(new Size(PANEL_WIDTH - 2 * PADDING, monthly ? 40 : 27))
    }
    const tabs = Array.from(this.tabNodes.values())
    if (this.monthlyTab !== null) tabs.push(this.monthlyTab)
    for (const node of tabs) node.setPosition(new Vec3(node.position.x, top - (monthly ? 115 : 112), 0))
  }
}
