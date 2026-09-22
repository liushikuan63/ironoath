/**
 * 职责：背包与资源产出明细面板（B04 §2/§3/§4，验收 1、5、6、11）。
 * 依赖：cc（渲染）、game/bag/BagPanel（展示数据组装，已单测）、scene/NodePool。
 *
 * <p><b>两个页签：资源明细与背包</b>。资源明细是 B04 §2 点名的「转化关键 UI」——
 * 玩家在这里看到的每一行加成都必须能对上总数，否则他会认为产量被偷偷改了。
 *
 * <p><b>本场景不做任何判定</b>（铁律 2）：道具顺序照搬服务端的 sortKey，
 * 名字照搬配置表下发的中文，能不能用由服务端裁定。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：.scene / .prefab 资产、长列表的 ScrollView、
 * 加速道具的目标选择弹窗（B04 §4）、道具图标。列表 item 已按 B07 §4 池化。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { buildBagPanel, buildResourcePanel, chestReceiptText } from '../game/bag/BagPanel'
import {
  clampPage, contentPerPage, pageCount, pageNotice, pageWindow,
} from '../game/ui/PanelPaging'
import type { BagItemRow, BagPanelView as BagPanelData, ResourcePanelView, ResourceRow } from '../game/bag/BagPanel'
import type { ChoiceOption, SpeedupChoice } from '../game/session/Choices'
import type { BagListResp, OpenBatchResp, ResourceDetailResp } from '../net/generated/BagProtocol'
import { applyAnyIconSprite, applyCommandButton, ensureFamily, resourceIconKey } from './ArtCatalog'
import { itemArtKeyForConfig } from '../game/art/ArtFamilies'
import { ChoiceOverlay } from './ChoiceOverlay'
import { NodePool } from './NodePool'
import { applySystemUiFont, capWidth } from './UiFont'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性。美术方向常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_ALT = new Color(46, 38, 31, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_WARNING = new Color(200, 96, 64, 255)
const COLOR_PERCENT = new Color(120, 176, 96, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 46
const ROW_GAP = 4
const HEADER_HEIGHT = 134
/** 类型页签那一条：整条可用宽、单颗高、颗间距 */
const PAGE_STRIP_WIDTH = 640
const PAGE_HEIGHT = 30
const PAGE_GAP = 6
const PADDING = 16
/** 一屏最多画几行。资源明细页每行还会展开若干条加成，所以留得比背包页少 */
const MAX_VISIBLE_ROWS = 7

type Tab = 'resource' | 'bag'

@ccclass('BagPanelView')
export class BagPanelView extends Component {
  private resources: ResourcePanelView | null = null
  private bag: BagPanelData | null = null
  private tab: Tab = 'resource'
  /** 背包页里当前选中的道具类型页签；null 表示第一页 */
  private bagPageType: string | null = null

  private rowPool: NodePool | null = null
  private readonly drawnRows: Node[] = []
  private headerLabel: Label | null = null
  /** 当前页（0 起）。换道具类型页签与换 背包/资源 都归零（两份数据集不同）。 */
  private page = 0
  private prevPageButton: Node | null = null
  private nextPageButton: Node | null = null
  private prevPageCaption: Label | null = null
  private nextPageCaption: Label | null = null
  private canPrev = false
  private canNext = false
  private warningLabel: Label | null = null
  /** 类型页签那一条的容器（里面每类一颗） */
  private pageHolder: Node | null = null
  private readonly tabLabels = new Map<Tab, Label>()
  private readonly tabButtons = new Map<Tab, Node>()
  /**
   * onLoad 之前的挂载数据。节点由 PanelNav 创建且初始未激活 —— 未激活不会跑 onLoad，
   * 而登录后的预拉数据此时已经到了，先存下来、激活时消费（与 CityPanelView 的 pending 同一条）。
   */
  private pendingResources: ResourceDetailResp | null = null
  private pendingBag: BagListResp | null = null
  private targetPicker: ChoiceOverlay | null = null
  /** 宝箱那一行的「开几个」。与加速目标是两个弹层：标题不同，且同时只可能开着一个 */
  private openPicker: ChoiceOverlay | null = null
  /** 最近一次开箱的回执。切页签或换类型页就丢掉（那时它已经从"刚才那一下"变成过期信息） */
  private receipt: string | null = null
  /** 玩家自己点过页签没有。点过之后，资源明细的再挂载就不许把面板切回那一页 */
  private tabChosen = false

  /**
   * 点「使用」。needsTarget 为 true 时（加速类道具）由外层弹出目标选择再发请求
   * （B04 §4：加速类道具必须给 targetId）—— 目标列表在城建/军队数据里，本场景拿不到。
   */
  onUseItem: ((itemId: string, needsTarget: boolean) => void) | null = null
  /** 点「出售」。价格由服务端算好下发，本场景不参与定价 */

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.rowPool = new NodePool(this.node, () => this.createRow(), MAX_VISIBLE_ROWS)
    this.buildHeader(size.height)
    this.buildBagPages(size.height)
    this.targetPicker = new ChoiceOverlay(this.node, '选择加速目标', 760)
    this.openPicker = new ChoiceOverlay(this.node, '开几个', 520)
    // 道具/装备两族图按需拉取；先画一帧 Graphics 占位，图到了再补一帧 —— 加载失败就停在占位上
    Promise.all([ensureFamily('item'), ensureFamily('equip')]).then(() => {
      if (this.isValid) {
        this.render()
      }
    })
    this.render()
    // 消费挂载前的数据。顺序与 AppRoot.refresh 一致（bag 在前、resources 在后），
    // 保证 tab 仍由 resources 决定 —— 两处顺序不一致会让面板开在错误的页签上
    const pendingBag = this.pendingBag
    const pendingResources = this.pendingResources
    this.pendingBag = null
    this.pendingResources = null
    if (pendingBag !== null) {
      this.attachBag(pendingBag)
    }
    if (pendingResources !== null) {
      this.attachResources(pendingResources)
    }
  }

  override onDestroy(): void {
    this.rowPool?.destroy()
    this.rowPool = null
    this.drawnRows.length = 0
    this.tabLabels.clear()
    this.tabButtons.clear()
    this.targetPicker?.hide()
    this.targetPicker = null
    this.openPicker?.hide()
    this.openPicker = null
    this.onUseItem = null
  }

  /** 装载资源产出明细（GET /resource/detail）。 */
  attachResources(resp: ResourceDetailResp): void {
    if (this.rowPool === null) {
      this.pendingResources = resp
      return
    }
    this.resources = buildResourcePanel(resp)
    // 只在玩家**还没自己选过页签**时把面板开到资源明细那页（登录后第一次挂载就是给首屏用的）。
    // 原来这里无条件 `tab = 'resource'`：开一次箱会重拉资源，玩家就被从「背包」页甩回「资源明细」页
    if (!this.tabChosen) {
      this.tab = 'resource'
    }
    this.render()
  }

  /** 装载背包（GET /bag/list）。 */
  attachBag(resp: BagListResp): void {
    if (this.rowPool === null) {
      this.pendingBag = resp
      return
    }
    this.bag = buildBagPanel(resp)
    // 原来选中的类型页可能在新数据里已经空了（道具用完了），此时退回第一页
    if (this.bagPageType !== null && !this.bag.pages.some((page) => page.type === this.bagPageType)) {
      this.bagPageType = null
    }
    this.render()
  }

  switchTab(tab: Tab): void {
    if (this.tab === tab) {
      return
    }
    this.tab = tab
    this.tabChosen = true
    this.page = 0
    this.receipt = null
    this.render()
  }

  /** AppRoot 选好候选后交给本面板画出来；选择结果只回调一次。 */
  showTargetPicker(options: readonly SpeedupChoice[], onPick: (targetId: string) => void): void {
    const targetById = new Map(options.map((option) => [option.id, option.targetId]))
    this.targetPicker?.show(options, (id) => {
      const targetId = targetById.get(id)
      if (targetId !== undefined) {
        onPick(targetId)
      }
    })
  }

  /** 宝箱的「开几个」选择器：选项由编排层按手里有几个给，本面板只画与回抛。 */
  showChestOpenPicker(options: readonly ChoiceOption[], onPick: (id: string) => void): void {
    this.openPicker?.show(options, onPick)
  }

  /**
   * 一次开箱的回执写在同一块说明行的位置上（那里本来就是"这一屏有件事要说"）。
   * 颜色换成绿：同一行既报"背包已满"也报"开出了什么"时，玩家靠颜色分得清是好是坏。
   */
  showChestReceipt(resp: OpenBatchResp): void {
    this.receipt = chestReceiptText(resp)
    if (this.warningLabel !== null) {
      // 同一行既报"背包已满"也报"开出了什么"时，玩家靠颜色分得清是好是坏
      this.warningLabel.color = COLOR_PERCENT
    }
    this.render()
  }

  // ---------- 搭建 ----------

  /**
   * 道具类型页签（加速 / 宝箱 / 材料…）。**代码里必须自己建**：
   * `selectBagPage` 原先只写着"由编辑器的页签控件调用"，而 Web 产物里那个控件不存在，
   * 于是玩家只看得到第一类道具的行 —— 其余类型"看得见数量、够不着行"（#326 现跑抓到）。
   */
  private buildBagPages(height: number): void {
    const holder = new Node('BagPages')
    holder.layer = this.node.layer
    this.node.addChild(holder)
    holder.setPosition(new Vec3(0, height / 2 - PADDING - 114, 0))
    holder.addComponent(UITransform).setContentSize(new Size(PAGE_STRIP_WIDTH, PAGE_HEIGHT))
    holder.active = false
    this.pageHolder = holder
  }

  /** 按当前那一页的类型集合重排页签。类型最多五种，所以不池化。 */
  private renderBagPages(): void {
    const holder = this.pageHolder
    const pages = this.bag?.pages ?? []
    if (holder === null) {
      return
    }
    // 先一份份拆掉再重建（`children` 是引擎内部那个数组的只读视图，边遍历边删会跳项）
    for (const child of holder.children.slice()) {
      child.destroy()
    }
    // 只有一类道具时不占这一条：一排只有一个键的页签比没有更让人以为"只有这一页"
    holder.active = this.tab === 'bag' && pages.length > 1
    if (!holder.active) {
      return
    }
    const width = Math.min(112, (PAGE_STRIP_WIDTH - (pages.length - 1) * PAGE_GAP) / pages.length)
    const total = pages.length * width + (pages.length - 1) * PAGE_GAP
    pages.forEach((page, index) => {
      const node = new Node(`Page_${page.type}`)
      node.layer = holder.layer
      holder.addChild(node)
      node.setPosition(new Vec3(-total / 2 + width / 2 + index * (width + PAGE_GAP), 0, 0))
      node.addComponent(UITransform).setContentSize(new Size(width, PAGE_HEIGHT))
      const selected = page.type === this.bagPageType
      if (!applyCommandButton(node, selected ? 'hover' : 'normal', width, PAGE_HEIGHT)) {
        const graphics = node.addComponent(Graphics)
        graphics.fillColor = COLOR_PANEL
        graphics.strokeColor = COLOR_COPPER_GOLD
        graphics.lineWidth = 1
        graphics.roundRect(-width / 2, -PAGE_HEIGHT / 2, width, PAGE_HEIGHT, 5)
        graphics.fill()
        graphics.stroke()
      }
      const label = this.addLabel(node, 'Caption', 0, 0,
        selected ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM, 15)
      // 名字自带数量：玩家不用点进去就知道这一类有几个（`items.length` 是服务端那份的行数）
      label.string = `${page.typeText} ${page.items.length}`
      label.overflow = Label.Overflow.SHRINK
      node.getComponent(UITransform)?.setContentSize(new Size(width, PAGE_HEIGHT))
      node.on('touch-start', (_event: EventTouch) => this.selectBagPage(page.type), this)
    })
  }

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
    this.headerLabel = this.addLabel(this.node, 'Header', 0, top - 20, COLOR_COPPER_GOLD, 22)
    this.warningLabel = this.addLabel(this.node, 'Warning', 0, top - 48, COLOR_WARNING, 15)
    // 两颗翻页键与那句页码同一行、摆在行区下方那一格，y 由 render() 跟着最后一行走
    this.prevPageButton = this.buildPagerButton('PrevPageButton', -PANEL_WIDTH / 2 + 46)
    this.nextPageButton = this.buildPagerButton('NextPageButton', PANEL_WIDTH / 2 - 46)

    const tabs: Array<{ tab: Tab; text: string; x: number }> = [
      { tab: 'resource', text: '资源明细', x: -60 },
      { tab: 'bag', text: '背包', x: 60 },
    ]
    for (const item of tabs) {
      const node = new Node(`Tab_${item.tab}`)
      node.layer = this.node.layer
      this.node.addChild(node)
      node.setPosition(new Vec3(item.x, top - 76, 0))
      node.addComponent(UITransform).setContentSize(new Size(110, 34))
      if (!applyCommandButton(node, item.tab === this.tab ? 'hover' : 'normal', 110, 34)) {
        const graphics = node.addComponent(Graphics)
        graphics.fillColor = COLOR_PANEL
        graphics.strokeColor = COLOR_COPPER_GOLD
        graphics.lineWidth = 1
        graphics.roundRect(-55, -17, 110, 34, 5)
        graphics.fill()
        graphics.stroke()
      }
      const label = this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT, 16)
      label.string = item.text
      this.tabLabels.set(item.tab, label)
      this.tabButtons.set(item.tab, node)
      const tab = item.tab
      node.on('touch-start', (_event: EventTouch) => this.switchTab(tab), this)
    }
  }

  /** 一颗 64×28 的翻页键，照本文件行上那颗动作键的画法。 */
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
    // 建出来先收着：不先收会在"背包还没到"那一态露出两颗点了没反应的键
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
   * 翻一页。灰掉的那一侧直接不吃：`clampPage` 也会把越界页号夹回来，
   * 但"点了没反应"正是 #345 那条口径要挡的观感。
   */
  private turnPage(delta: number): void {
    if (delta < 0 && !this.canPrev) return
    if (delta > 0 && !this.canNext) return
    this.page += delta
    this.render()
  }

  /**
   * 页码那一格与两颗键的位置。
   *
   * <p>那句页码借用说明行（`warningLabel`）：那一行本来就是"开箱回执 > 满仓 > 另有 N 项"
   * 三用的，回执优先；被它占时只剩两颗键自证"还能翻"（键上的「上一页/下一页」就是提示）。
   */
  private paintPager(pages: number, total: number, warning: string, rowY: number): void {
    const paged = pages > 1
    this.canPrev = this.page > 0
    this.canNext = this.page < pages - 1
    if (this.warningLabel !== null) {
      // 说明行被开箱回执占着时**并列**写页码，不是不写：只写回执会让玩家以为这一屏就是全部
      this.warningLabel.string = !paged ? warning
        : (warning === '' ? `${pageNotice(this.page, pages)} · 共 ${total} 项`
          : `${warning} · ${pageNotice(this.page, pages)}`)
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
    const node = new Node('Row')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
    graphics.fill()

    const title = this.addLabel(node, 'Title', -PANEL_WIDTH / 2 + PADDING + 36, 9, COLOR_TEXT, 17)
    title.horizontalAlign = Label.HorizontalAlign.LEFT
    title.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    capWidth(title, 270)
    const detail = this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING + 36,
      -11, COLOR_TEXT_DIM, 13)
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    detail.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    capWidth(detail, 270)
    const value = this.addLabel(node, 'Value', PANEL_WIDTH / 2 - 120, 0, COLOR_COPPER_GOLD, 16)
    value.horizontalAlign = Label.HorizontalAlign.RIGHT
    value.node.getComponent(UITransform)?.setAnchorPoint(1, 0.5)

    // 只有"使用"一个按钮：出售整条撤下（2026-09-13 撤按钮，2026-09-19 B24 裁决④ 连表列一起删），
    // 所以它居中到原来的两个按钮之间，而不是留一个空位假装那边还有东西
    const buttons: Array<{ name: string; text: string; x: number }> = [
      { name: 'UseButton', text: '使用', x: PANEL_WIDTH / 2 - 48 },
    ]
    for (const button of buttons) {
      const buttonNode = new Node(button.name)
      buttonNode.layer = node.layer
      node.addChild(buttonNode)
      buttonNode.setPosition(new Vec3(button.x, 0, 0))
      buttonNode.addComponent(UITransform).setContentSize(new Size(46, 26))
      if (!applyCommandButton(buttonNode, 'normal', 46, 26)) {
        const buttonGraphics = buttonNode.addComponent(Graphics)
        buttonGraphics.fillColor = COLOR_PANEL
        buttonGraphics.strokeColor = COLOR_COPPER_GOLD
        buttonGraphics.lineWidth = 1
        buttonGraphics.roundRect(-23, -13, 46, 26, 4)
        buttonGraphics.fill()
        buttonGraphics.stroke()
      }
      const caption = this.addLabel(buttonNode, 'Caption', 0, 0, COLOR_TEXT, 12)
      caption.string = button.text
    }
    const icon = new Node('Icon')
    icon.layer = node.layer
    node.addChild(icon)
    icon.setPosition(new Vec3(-PANEL_WIDTH / 2 + PADDING + 13, 0, 0))
    icon.addComponent(UITransform).setContentSize(new Size(26, 26))
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
    const pool = this.rowPool
    if (pool === null) {
      return
    }
    for (const [tab, label] of this.tabLabels) {
      label.color = tab === this.tab ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
      const button = this.tabButtons.get(tab)
      if (button !== undefined) {
        applyCommandButton(button, tab === this.tab ? 'hover' : 'normal', 110, 34)
      }
    }
    pool.releaseAll(this.drawnRows)
    this.drawnRows.length = 0

    const rows = this.tab === 'resource' ? this.resourceRows() : this.bagRows()
    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    // 行位这一屏是写死的 MAX_VISIBLE_ROWS（上方那一行给了道具类型页签，按可视高算会把页签吃掉），
    // 分页沿用同一容量：从前装不下就在说明行写一句「另有 N 项未显示」，那些道具玩家永远拿不到（#307）
    const total = rows.length
    const perPage = contentPerPage(total, MAX_VISIBLE_ROWS)
    const pages = pageCount(total, perPage)
    this.page = clampPage(this.page, total, perPage)
    const slice = pageWindow(total, this.page, perPage)
    const visible = rows.slice(slice.start, slice.end)
    visible.forEach((row, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnRows.push(node)
      this.renderRow(node, row, index)
    })

    if (this.headerLabel !== null) {
      this.headerLabel.string = this.tab === 'resource' ? '资源产出明细' : (this.bag?.capacityText ?? '背包')
    }
    this.paintPager(pages, total, this.warningText(), topY - visible.length * (ROW_HEIGHT + ROW_GAP) - 8)
    this.renderBagPages()
  }

  private warningText(): string {
    // 开箱回执优先占这一行：`render()` 每次都会重算说明行，直接写 Label 会被下一次渲染抹掉
    // （实测：开完 5 个箱，那行字变成了资源页的「STAMINA 已满仓」）
    if (this.receipt !== null && this.tab === 'bag') {
      return this.receipt
    }
    if (this.tab === 'resource') {
      const full = this.resources?.fullWarning ?? null
      if (full !== null) {
        return full
      }
      const mismatch = this.resources?.rows.filter((row) => !row.sumMatches) ?? []
      // 明细之和与实际产量对不上是服务端的不变量被破坏了。必须喊出来：
      // 玩家自己拿计算器加一遍就能发现，悄悄显示等于默认数值造假（B04 验收 5）
      return mismatch.length === 0
        ? ''
        : `${mismatch.map((row) => row.type).join('、')} 的产出明细与总产量不符，已上报`
    }
    if (this.bag?.capacityFull === true) {
      return '背包已满，再获得道具可能无法入包'
    }
    // 「另有 N 项」那一支交给页码：它原来的分母是**所有类别**的总件数（`bag.pages` 跨类求和），
    // 而这一屏只画选中的那一类，且行位是 MAX_VISIBLE_ROWS —— 那句话把别的类别也算成了"没画下"
    return ''
  }

  private renderRow(node: Node, row: RowDraft, index: number): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      graphics.fillColor = index % 2 === 0 ? COLOR_ROW : COLOR_ROW_ALT
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
      graphics.fill()
    }
    const title = node.children[0]?.getComponent(Label)
    const detail = node.children[1]?.getComponent(Label)
    const value = node.children[2]?.getComponent(Label)
    const icon = node.getChildByName('Icon')
    if (title !== undefined && title !== null) {
      title.string = row.title
      title.color = row.titleColor
    }
    if (detail !== undefined && detail !== null) {
      detail.string = row.detail
      detail.color = row.detailColor
    }
    if (value !== undefined && value !== null) {
      value.string = row.value
    }
    if (icon !== null) {
      icon.active = row.iconKey !== null
        && applyAnyIconSprite(icon, row.iconKey, 26, 26)
    }
    // 前三个子节点是文本，后一个是使用按钮 —— 只有背包页的道具行才显示
    const useButton = node.children[3]
    if (useButton !== undefined) {
      useButton.active = row.itemId !== null
      useButton.off('touch-start')
      if (row.itemId !== null) {
        const itemId = row.itemId
        const needsTarget = row.needsTarget
        useButton.on('touch-start', (_event: EventTouch) => {
          this.onUseItem?.(itemId, needsTarget)
        }, this)
      }
    }
  }

  /**
   * 资源明细页的行。
   *
   * <p>每种资源占「1 行汇总 + N 行加成明细」，明细紧跟在汇总下面并缩进显示。
   * 加成行必须逐条列出：B04 §2 把它称作「转化关键 UI」——
   * 玩家要能看出「这 720 里有多少来自建筑、多少来自科技」，才知道下一步该投哪边。
   */
  private resourceRows(): RowDraft[] {
    const panel = this.resources
    if (panel === null) {
      return []
    }
    const out: RowDraft[] = []
    for (const resource of panel.rows) {
      out.push(resourceSummaryDraft(resource))
      for (const line of resource.lines) {
        out.push({
          title: `    ${line.source}`,
          titleColor: line.isPercent ? COLOR_PERCENT : COLOR_TEXT_DIM,
          detail: '',
          detailColor: COLOR_TEXT_DIM,
          value: line.text,
          iconKey: null,
          itemId: null,
          needsTarget: false,
        })
      }
      out.push({
        title: '    合计',
        // 明细之和与实际产量不符时必须标红：这是服务端不变量被破坏的信号
        titleColor: resource.sumMatches ? COLOR_TEXT_DIM : COLOR_WARNING,
        detail: resource.sumMatches ? '' : '与上面的每小时产量不一致',
        detailColor: COLOR_WARNING,
        value: resource.sumText,
        iconKey: null,
        itemId: null,
        needsTarget: false,
      })
    }
    return out
  }

  /** 背包页的行：当前类型页签下的道具，顺序照搬服务端的 sortKey 升序。 */
  private bagRows(): RowDraft[] {
    const bag = this.bag
    if (bag === null) {
      return []
    }
    const page = this.bagPageType === null
      ? bag.pages[0]
      : bag.pages.find((item) => item.type === this.bagPageType)
    if (page === undefined) {
      return []
    }
    return page.items.map((item: BagItemRow): RowDraft => ({
      title: item.title,
      titleColor: COLOR_TEXT,
      detail: [item.stackText, item.obtainText]
        .filter((part): part is string => part !== null)
        .join(' · '),
      detailColor: COLOR_TEXT_DIM,
      value: item.rarityText,
      // 行 id → 族图键（映射表在 ArtFamilies，认不出的行继续用 Graphics 占位）
      iconKey: itemArtKeyForConfig(item.itemId),
      itemId: item.itemId,
      needsTarget: item.needsTarget,
    }))
  }

  /** 切换到背包里的某个类型页。由编辑器的页签控件调用。 */
  selectBagPage(type: string | null): void {
    this.bagPageType = type
    // 换道具类别 = 换一份列表，页号留着会停在错位的地方
    this.page = 0
    this.receipt = null
    this.render()
  }
}

/** 一行要画的内容。资源页与背包页共用同一套节点结构，只是各字段含义不同。 */
interface RowDraft {
  readonly title: string
  readonly titleColor: Color
  readonly detail: string
  readonly detailColor: Color
  readonly value: string
  /** `icon:`（图集）或族键（`item:`/`equip:`）；null 表示这一行没有图，用 Graphics 占位 */
  readonly iconKey: string | null
  /** 道具行才有；资源行为 null，据此隐藏使用/出售按钮 */
  readonly itemId: string | null
  readonly needsTarget: boolean
}

function resourceSummaryDraft(resource: ResourceRow): RowDraft {
  const details: string[] = [resource.perHourText]
  if (resource.protectedText !== null) {
    // 受保护量是 B04 验收 6 的可见出口：不显示的话玩家无法理解「为什么被打掉的是这个数」
    details.push(resource.protectedText)
  }
  if (resource.fullText !== null) {
    details.push(resource.fullText)
  }
  return {
    title: resource.type,
    titleColor: resource.full ? COLOR_WARNING : COLOR_COPPER_GOLD,
    detail: details.join(' · '),
    detailColor: resource.full ? COLOR_WARNING : COLOR_TEXT_DIM,
    value: resource.currentText,
    iconKey: resourceIconKey(resource.type),
    itemId: null,
    needsTarget: false,
  }
}
