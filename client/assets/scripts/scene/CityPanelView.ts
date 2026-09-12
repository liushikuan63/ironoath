/**
 * 职责：城建面板 —— 建筑列表、升级队列、加速、收割（B03 §2/§3/§4，验收 3、7、10）。
 * 依赖：cc（渲染）、game/city/CityPanel（展示数据组装，已单测）、scene/NodePool。
 *
 * <p><b>倒计时每秒本地刷新，不重新请求服务端</b>（B03 §4）：finishAt 是服务端时间戳，
 * 本地用 TimeSync 的偏移换算即可。刷新只在「整秒变了」的时候做 ——
 * 倒计时本来就只显示到秒，每帧重排是纯粹的浪费。
 *
 * <p><b>刷新的做法是拿原响应重新组装一遍面板，而不是就地改倒计时文本</b>：
 * 到点的那一刻，行的状态文本、可点按钮集合（升级 → 收割）、面板顶部的收割提示都要一起变。
 * 只改倒计时会留下一个「显示 0 秒但收割按钮还没出现」的中间态，
 * 而就地打补丁意味着把这些联动关系散落在场景各处。重新组装一次只涉及十几栋建筑，
 * 一秒一次，代价可以忽略，正确性却是白拿的。
 *
 * <p><b>本场景不做任何判定</b>（铁律 2）：能不能升级由服务端裁定，
 * 被拒绝时它带上 ErrorDetail，本场景用 {@link CityPanelView#showError} 拼成
 * 「需要 木材 12000，当前 3400」而不是笼统的「条件不足」（B03 §2）。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：.scene / .prefab 资产、城内网格布局与建筑美术、
 * 正式图标。占位期用 Graphics 色块 + Label，列表 item 已按 B07 §4 池化。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, sys, view } from 'cc'
import { buildCityPanel, errorText, outputText } from '../game/city/CityPanel'
import type { BuildingRow, CityPanelView as CityPanelData } from '../game/city/CityPanel'
import type {
  CityCollectResp, CityListResp, ErrorDetail, SpeedUpSource,
} from '../net/generated/CityProtocol'
import { NodePool } from './NodePool'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性。美术方向常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_UPGRADING = new Color(64, 48, 30, 255)
const COLOR_ROW_DONE = new Color(48, 66, 42, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_WARNING = new Color(200, 96, 64, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 66
const ROW_GAP = 6
const HEADER_HEIGHT = 128
const PADDING = 16
/** 一屏最多画几行。建筑数量由配置表决定，超出的要靠 ScrollView（编辑器资产） */
const MAX_VISIBLE_ROWS = 6
/** 卡片外沿与视口的最小间距：缩放时留出它，避免贴边 */
const PANEL_MARGIN = 12
/** 卡片宽度：内容宽（PANEL_WIDTH）加上左右内边距 */
const CARD_WIDTH = PANEL_WIDTH + PADDING * 2
/**
 * 卡片高度：标题/队列/资源两行/收割按钮（HEADER_HEIGHT）+ 满行区 + 底部消息条。
 * <b>固定值而不是按数据算</b>：行数随数据变，卡片高度跟着跳会让整块面板忽大忽小；
 * 固定高度 + 外层整体缩放，才是"看起来像一个面板"的做法。
 */
const CARD_HEIGHT = HEADER_HEIGHT + MAX_VISIBLE_ROWS * (ROW_HEIGHT + ROW_GAP) + 64
/** 缩放上限：宽屏下允许放大到接近铺满，字更大也更好点；再大就会显得笨重 */
const MAX_SCALE = 1.6

/** 行内按钮对应的动作。 */
type RowAction = 'upgrade' | 'speedAd' | 'speedGold' | 'collect'

@ccclass('CityPanelView')
export class CityPanelView extends Component {
  /** 原始响应。留着它才能每秒重新组装面板（理由见文件头） */
  private resp: CityListResp | null = null
  private panel: CityPanelData | null = null
  private offsetMs = 0
  /** 上次渲染时的整秒值。只有它变了才重排，避免每帧重建 */
  private lastRenderedSecond = -1
  private pending: CityListResp | null = null

  private rowPool: NodePool | null = null
  private readonly drawnRows: Node[] = []
  private readonly buttonKinds = new Map<Node, RowAction>()
  /** 承载全部内容的卡片节点：内部坐标固定，适配交给外层缩放 */
  private card: Node | null = null
  /** 卡片当前高度（随实际建筑数变化），行区坐标与缩放都依赖它 */
  private cardHeight = CARD_HEIGHT
  private frameGraphics: Graphics | null = null
  private collectAllNode: Node | null = null
  private headerLabel: Label | null = null
  private queueLabel: Label | null = null
  /** 资源两行三列，共 6 个（与 resource 表行数一致） */
  private readonly resourceLabels: Label[] = []
  private messageLabel: Label | null = null

  /** 点「升级」。派工与扣资源都由服务端裁定 */
  onUpgrade: ((configId: string) => void) | null = null
  /** 点「加速」。source 区分免费广告与付费金币，供服务端埋点与防刷（B03 §3） */
  onSpeedUp: ((buildingId: string, source: SpeedUpSource) => void) | null = null
  /** 点「收割」。buildingId 为 null 表示收割全部已到点的建筑 */
  onCollect: ((buildingId: string | null) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    const card = new Node('Card')
    card.layer = this.node.layer
    this.node.addChild(card)
    card.addComponent(UITransform).setContentSize(new Size(CARD_WIDTH, CARD_HEIGHT))
    this.card = card
    // 整块面板按视口等比缩放（上限 1.0）：卡片内部布局固定，外层负责适配。
    // 背景不参与缩放（它本来就铺满整屏），所以缩放的是 Card 而不是本节点。
    const scale = Math.min(MAX_SCALE,
      (size.width - 2 * PANEL_MARGIN) / CARD_WIDTH,
      (size.height - 2 * PANEL_MARGIN) / CARD_HEIGHT)
    card.setScale(new Vec3(scale, scale, 1))
    this.rowPool = new NodePool(card, () => this.createRow(), MAX_VISIBLE_ROWS)
    this.buildCard()
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
    this.buttonKinds.clear()
    this.onUpgrade = null
    this.onSpeedUp = null
    this.onCollect = null
  }

  /**
   * 装载城建列表。
   *
   * @param offsetMs 服务端时刻 - 本地时刻，来源 core/TimeSync。
   *                 <b>不能省</b>：直接拿本地 Date 与 finishAt 相减，
   *                 玩家把手机时间调快一小时就能让升级立刻完成（铁律 5）
   */
  attach(resp: CityListResp, offsetMs: number): void {
    this.offsetMs = offsetMs
    if (this.rowPool === null) {
      this.pending = resp
      return
    }
    this.resp = resp
    this.panel = buildCityPanel(resp, offsetMs, sys.now())
    this.lastRenderedSecond = -1
    this.render(true)
  }

  /** 时间偏移更新（每个 HTTP 响应都带 serverNow，等于免费校准一次）。 */
  updateOffset(offsetMs: number): void {
    this.offsetMs = offsetMs
  }

  /** 收割响应：播升级动效的入口，同时把补结算的产出说出来。 */
  attachCollect(resp: CityCollectResp): void {
    const output = outputText(resp.output)
    const names = resp.collected.map((building) => `${building.configId} Lv${building.level}`).join(' · ')
    this.showMessage(`升级完成 ${names}${output === null ? '' : ` · ${output}`}`, COLOR_GOOD)
  }

  /**
   * 显示一次操作失败的原因（B03 §2）。
   *
   * <p><b>绝不显示笼统的「条件不足」</b>：服务端已经把 need 与 current 都算好了，
   * 拼成「需要 木材 12000，当前 3400」玩家才知道该去做什么。
   */
  showError(detail: ErrorDetail | null, fallback: string): void {
    this.showMessage(errorText(detail, fallback), COLOR_WARNING)
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
    // 每秒重新组装一次面板：到点时状态文本、按钮集合、顶部收割提示要一起变
    this.panel = buildCityPanel(resp, this.offsetMs, now)
    this.render(false)
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

  /** 卡片内容：坐标全部相对卡片中心（卡片整体由 onLoad 缩放并居中）。 */
  private buildCard(): void {
    const card = this.card
    if (card === null) {
      return
    }
    // 卡片底板先画（第一个子节点 ⇒ 在最底层）：没有它，内容会直接散在整屏黑底上，
    // 看起来"没对齐、也没有画面" —— 面板需要一个能看见的边界
    const frame = new Node('CardFrame')
    frame.layer = card.layer
    card.addChild(frame)
    frame.addComponent(UITransform).setContentSize(new Size(CARD_WIDTH, CARD_HEIGHT))
    const frameGraphics = frame.addComponent(Graphics)
    this.frameGraphics = frameGraphics

    const top = CARD_HEIGHT / 2 - PADDING
    this.headerLabel = this.addLabel(card, 'Header', 0, top - 22, COLOR_COPPER_GOLD, 22)
    this.queueLabel = this.addLabel(card, 'Queue', 0, top - 54, COLOR_TEXT, 17)
    // 资源排成两行三列：一行六项在宽屏下会顶到卡片外、被裁掉一半，
    // 网格是唯一在任意宽度下都对齐的排法（列宽 = 内容宽 / 3）
    const columnWidth = PANEL_WIDTH / 3
    for (let row = 0; row < 2; row++) {
      for (let column = 0; column < 3; column++) {
        const x = -PANEL_WIDTH / 2 + columnWidth * (column + 0.5)
        const y = top - 86 - row * 22
        this.resourceLabels.push(this.addLabel(
          card, `Resource-${row}-${column}`, x, y, COLOR_TEXT_DIM, 15))
      }
    }
    this.messageLabel = this.addLabel(card, 'Message', 0, -CARD_HEIGHT / 2 + 20, COLOR_WARNING, 16)

    const collectAll = new Node('CollectAllButton')
    collectAll.layer = card.layer
    card.addChild(collectAll)
    this.collectAllNode = collectAll
    collectAll.addComponent(UITransform).setContentSize(new Size(140, 36))
    const graphics = collectAll.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 2
    graphics.roundRect(-70, -18, 140, 36, 6)
    graphics.fill()
    graphics.stroke()
    const caption = this.addLabel(collectAll, 'Caption', 0, 0, COLOR_TEXT, 16)
    caption.string = '一键收割'
    collectAll.on('touch-start', (_event: EventTouch) => {
      // null = 收割全部已到点的建筑（协议里 buildingId 为空就是这个含义）
      this.onCollect?.(null)
    }, this)
    this.layoutCard(1)
  }

  /**
   * 按实际建筑数重排卡片。
   *
   * <p>卡片高度写死会留下一大片空白（只有一座主城时尤其明显，看起来"什么都没有"），
   * 所以高度随行数变化、最少一行；缩放也跟着重算，保证放大到铺满又不越界。
   */
  private layoutCard(rowCount: number): void {
    const card = this.card
    if (card === null) {
      return
    }
    const visibleRows = Math.max(1, Math.min(rowCount, MAX_VISIBLE_ROWS))
    const cardHeight = HEADER_HEIGHT + visibleRows * (ROW_HEIGHT + ROW_GAP) + 64
    this.cardHeight = cardHeight
    card.getComponent(UITransform)?.setContentSize(new Size(CARD_WIDTH, cardHeight))

    const frame = this.frameGraphics
    if (frame !== null) {
      frame.clear()
      frame.fillColor = COLOR_PANEL
      frame.strokeColor = COLOR_COPPER_GOLD
      frame.lineWidth = 2
      frame.roundRect(-CARD_WIDTH / 2, -cardHeight / 2, CARD_WIDTH, cardHeight, 10)
      frame.fill()
      frame.stroke()
    }

    const top = cardHeight / 2 - PADDING
    this.headerLabel?.node.setPosition(new Vec3(0, top - 22, 0))
    this.queueLabel?.node.setPosition(new Vec3(0, top - 54, 0))
    const columnWidth = PANEL_WIDTH / 3
    this.resourceLabels.forEach((label, index) => {
      const row = Math.floor(index / 3)
      const column = index % 3
      label.node.setPosition(new Vec3(
        -PANEL_WIDTH / 2 + columnWidth * (column + 0.5), top - 86 - row * 22, 0))
    })
    this.messageLabel?.node.setPosition(new Vec3(0, -cardHeight / 2 + 20, 0))
    this.collectAllNode?.setPosition(new Vec3(PANEL_WIDTH / 2 - 80, top - 22, 0))

    const size = view.getVisibleSize()
    const scale = Math.min(MAX_SCALE,
      (size.width - 2 * PANEL_MARGIN) / CARD_WIDTH,
      (size.height - 2 * PANEL_MARGIN) / cardHeight)
    card.setScale(new Vec3(scale, scale, 1))
  }
  private createRow(): Node {
    const node = new Node('BuildingRow')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
    graphics.fill()

    const textWidth = PANEL_WIDTH - 300
    const title = this.addLabel(node, 'Title', -PANEL_WIDTH / 2 + PADDING, 16, COLOR_TEXT, 19, true, textWidth)
    const status = this.addLabel(node, 'Status', -PANEL_WIDTH / 2 + PADDING, -6, COLOR_TEXT_DIM, 15, true, textWidth)
    const countdown = this.addLabel(node, 'Countdown', -PANEL_WIDTH / 2 + PADDING, -24,
      COLOR_COPPER_GOLD, 14, true, textWidth)

    // 「收割」与「升级」共用同一个位置：两者互斥（升级中不能升级，到点只需收割），
    // 叠在一起既省一个按钮位，也让玩家的动作在同一处形成肌肉记忆
    const buttons: Array<{ name: string; text: string; x: number; kind: RowAction }> = [
      { name: 'UpgradeButton', text: '升级', x: PANEL_WIDTH / 2 - 210, kind: 'upgrade' },
      { name: 'CollectButton', text: '收割', x: PANEL_WIDTH / 2 - 210, kind: 'collect' },
      { name: 'SpeedAdButton', text: '广告加速', x: PANEL_WIDTH / 2 - 132, kind: 'speedAd' },
      { name: 'SpeedGoldButton', text: '金币加速', x: PANEL_WIDTH / 2 - 54, kind: 'speedGold' },
    ]
    for (const button of buttons) {
      const buttonNode = new Node(button.name)
      buttonNode.layer = node.layer
      node.addChild(buttonNode)
      buttonNode.setPosition(new Vec3(button.x, -14, 0))
      buttonNode.addComponent(UITransform).setContentSize(new Size(72, 28))
      const buttonGraphics = buttonNode.addComponent(Graphics)
      buttonGraphics.fillColor = COLOR_PANEL
      buttonGraphics.strokeColor = COLOR_COPPER_GOLD
      buttonGraphics.lineWidth = 1
      buttonGraphics.roundRect(-36, -14, 72, 28, 4)
      buttonGraphics.fill()
      buttonGraphics.stroke()
      const caption = this.addLabel(buttonNode, 'Caption', 0, 0, COLOR_TEXT, 13)
      caption.string = button.text
      this.buttonKinds.set(buttonNode, button.kind)
    }
    return node
  }

  /**
   * 建一个文本节点。
   *
   * @param leftAligned 左对齐。**必须配合左锚点**：Label 在 Overflow.NONE 下由文本自身
   *                    决定 contentSize，此时 horizontalAlign 不生效、文本以节点中心排布，
   *                    表现就是「整行文字从卡片里向外溢出」（本轮踩过的坑）。
   * @param maxWidth  大于 0 时固定宽度并裁剪，防止长文本压到右侧按钮上
   */
  private addLabel(parent: Node, name: string, x: number, y: number, color: Color, fontSize: number,
                   leftAligned = false, maxWidth = 0): Label {
    const node = new Node(name)
    node.layer = parent.layer
    parent.addChild(node)
    const transform = node.addComponent(UITransform)
    node.setPosition(new Vec3(x, y, 0))
    const label = node.addComponent(Label)
    label.string = ''
    label.color = color
    label.fontSize = fontSize
    label.horizontalAlign = leftAligned ? Label.HorizontalAlign.LEFT : Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    if (leftAligned) {
      transform.setAnchorPoint(0, 0.5)
    }
    if (maxWidth > 0) {
      transform.setContentSize(new Size(maxWidth, fontSize * 1.6))
      label.overflow = Label.Overflow.CLAMP
    }
    return label
  }

  // ---------- 渲染 ----------

  /**
   * @param rebuild true 表示行集合可能变了（新数据到达），需要重新取还节点；
   *                false 表示只是秒针走了一格，节点结构不动，只刷内容
   */
  private render(rebuild: boolean): void {
    const panel = this.panel
    const pool = this.rowPool
    if (panel === null || pool === null) {
      return
    }
    this.lastRenderedSecond = Math.floor(sys.now() / 1000)

    if (this.headerLabel !== null) {
      this.headerLabel.string = panel.collectHint ?? '内城'
      this.headerLabel.color = panel.collectHint === null ? COLOR_COPPER_GOLD : COLOR_GOOD
    }
    if (this.queueLabel !== null) {
      this.queueLabel.string = panel.queueExpandText === null
        ? panel.queueText
        : `${panel.queueText} · ${panel.queueExpandText}`
    }
    panel.resourceLines.forEach((line, index) => {
      const label = this.resourceLabels[index]
      if (label !== undefined) {
        label.string = line
      }
    })

    const visible = panel.rows.slice(0, MAX_VISIBLE_ROWS)
    if (rebuild) {
      pool.releaseAll(this.drawnRows)
      this.drawnRows.length = 0
      this.layoutCard(visible.length)
      const topY = this.cardHeight / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
      visible.forEach((_row, index) => {
        const node = pool.acquire()
        node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
        this.drawnRows.push(node)
      })
    }
    visible.forEach((row, index) => {
      const node = this.drawnRows[index]
      if (node !== undefined) {
        this.renderRow(node, row)
      }
    })
  }

  private renderRow(node: Node, row: BuildingRow): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      graphics.fillColor = row.collectable ? COLOR_ROW_DONE : (row.upgrading ? COLOR_ROW_UPGRADING : COLOR_ROW)
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
      graphics.fill()
    }
    const title = node.children[0]?.getComponent(Label)
    const status = node.children[1]?.getComponent(Label)
    const countdown = node.children[2]?.getComponent(Label)
    if (title !== undefined && title !== null) {
      title.string = row.helpText === null ? row.title : `${row.title} · ${row.helpText}`
    }
    if (status !== undefined && status !== null) {
      status.string = row.progressText === null ? row.statusText : `${row.statusText} · ${row.progressText}`
      status.color = row.paused ? COLOR_WARNING : COLOR_TEXT_DIM
    }
    if (countdown !== undefined && countdown !== null) {
      countdown.string = row.countdownText ?? ''
      countdown.color = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
    }
    this.wireRowButtons(node, row)
  }

  /**
   * 给行内按钮接线。
   *
   * <p>每次都先 off 再 on：行节点来自池子，上一条数据留下的回调如果不清掉，
   * 点新的行会作用到旧建筑上 —— 这类 bug 只在快速滚动时出现，极难复现。
   * <p>不该出现的按钮直接隐藏而不是留着可点却什么都不做：
   * 玩家会一直点，然后认为是卡了（与关卡面板同一条纪律）。
   */
  private wireRowButtons(node: Node, row: BuildingRow): void {
    for (const button of node.children) {
      const kind = this.buttonKinds.get(button)
      if (kind === undefined) {
        continue   // 文本子节点
      }
      button.off('touch-start')
      const visible = kind === 'collect' ? row.collectable
        : kind === 'upgrade' ? !row.upgrading && !row.collectable && !row.paused
          : row.upgrading && !row.collectable
      button.active = visible
      if (!visible) {
        continue
      }
      button.on('touch-start', (_event: EventTouch) => {
        switch (kind) {
          case 'upgrade':
            this.onUpgrade?.(row.configId)
            return
          case 'speedAd':
            this.onSpeedUp?.(row.id, 'AD')
            return
          case 'speedGold':
            this.onSpeedUp?.(row.id, 'GOLD')
            return
          case 'collect':
            this.onCollect?.(row.id)
            return
        }
      }, this)
    }
  }

  private showMessage(text: string, color: Color): void {
    if (this.messageLabel === null) {
      return
    }
    this.messageLabel.string = text
    this.messageLabel.color = color
  }
}
