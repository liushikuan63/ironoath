/**
 * 职责：抽卡记录覆盖层 —— 把「最近 N 次抽取」画给玩家（B15 §三 合规要求：概率公示之外还要查得到自己的记录）。
 * 依赖：cc（渲染）、game/gacha/GachaHistory（数据组装，已单测）。
 *
 * <p>铁律 2：本文件只读传入的视图，点击只喊一声。一页几条、怎么分页、
 * 「刚刚 / N 分钟前」怎么算全在 `game/gacha/GachaHistory.ts` 里，这里不重算一遍。
 *
 * <p>为什么要一屏覆盖层而不是把记录塞进招募面板：50 条记录排不进招募面板剩余的行区，
 * 而"只显示最近 5 条 + 另有 N 条未显示"正是 #450~#452 那一族被收掉的形状
 * （话说诚实了，但下面的东西永远拿不到）。与「概率公示」同一种承载方式（都是招募面板打开的一屏）。
 *
 * <p>遮罩要**吞掉自己的触摸**：整屏已经压暗了，点暗处却穿透打到底下的卡池行上，
 * 玩家会在一个"看不见"的面板上抽卡（#403 那一族）。
 */
import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import type { GachaHistoryView as GachaHistoryData } from '../game/gacha/GachaHistory'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

const COLOR_MASK = new Color(0, 0, 0, 190)
const COLOR_CARD = new Color(28, 23, 20, 255)
const COLOR_ROW = new Color(40, 33, 28, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_DIM = new Color(150, 140, 124, 255)
const COLOR_GOLD = new Color(184, 134, 11, 255)
const COLOR_HINT = new Color(120, 168, 196, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 44
const BUTTON_HEIGHT = 32
const PADDING = 16
/** 顶部留给 HUD 顶栏的高度（与招募面板同一份口径）。 */
const TOP_RESERVE = 24
const TITLE_TO_ROWS = 70
const FOOTER_GAP = 20

@ccclass('GachaHistoryView')
export class GachaHistoryView extends Component {
  private data: GachaHistoryData | null = null
  private pending: GachaHistoryData | null = null
  private readonly nodes: Node[] = []
  /** 遮罩建好了没有：没建好之前收到的那份视图先存着（`attach` 可能早于 `onLoad`）。 */
  private built = false

  /** 关掉这一屏（AppRoot 负责把节点设为 inactive）。 */
  onClose: (() => void) | null = null
  /** 翻到上一页 / 下一页。页码越界由数据层夹回，这里只喊一声目标页。 */
  onPrev: (() => void) | null = null
  onNext: (() => void) | null = null

  override onLoad(): void {
    this.buildMask()
    this.built = true
    if (this.pending !== null) {
      const pending = this.pending
      this.pending = null
      this.attach(pending)
    }
  }

  override onDestroy(): void {
    this.built = false
    this.nodes.length = 0
    this.onClose = null
    this.onPrev = null
    this.onNext = null
  }

  /** 装载整块视图（由 `AppRoot.deliverGachaHistory` 下发，每次翻页都重发一份完整的）。 */
  attach(data: GachaHistoryData): void {
    this.data = data
    if (!this.built || !this.isValid) {
      this.pending = data
      return
    }
    this.redraw()
  }

  private buildMask(): void {
    const size = view.getVisibleSize()
    const node = new Node('GachaHistoryMask')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(size.width, size.height))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_MASK
    graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
    graphics.fill()
    // 吞掉自己的触摸：这一屏是模态的，暗处不该穿透到招募面板的卡池行上
    node.on('touch-start', (_event: EventTouch) => { /* 刻意什么都不做 */ }, this)
  }

  private redraw(): void {
    for (const node of this.nodes) {
      node.destroy()
    }
    this.nodes.length = 0
    const data = this.data
    if (data === null) {
      return
    }
    const size = view.getVisibleSize()
    const top = size.height / 2 - TOP_RESERVE

    const cardHeight = size.height - TOP_RESERVE * 2
    const card = this.surface('Card', 0, 0, PANEL_WIDTH, cardHeight)
    card.fillColor = COLOR_CARD
    card.rect(-PANEL_WIDTH / 2, -cardHeight / 2, PANEL_WIDTH, cardHeight)
    card.fill()

    this.label('抽取记录', COLOR_GOLD, 22, -PANEL_WIDTH / 2 + PADDING, top - 18, 'left')
    this.label(data.retentionText, COLOR_HINT, 14, -PANEL_WIDTH / 2 + PADDING, top - 46, 'left')
    this.button('CloseButton', '关闭', PANEL_WIDTH / 2 - PADDING - 38, top - 18, 76, true,
      () => this.onClose?.())

    if (data.emptyText !== null) {
      this.label(data.emptyText, COLOR_DIM, 15, -PANEL_WIDTH / 2 + PADDING, top - TITLE_TO_ROWS - 24, 'left')
    } else {
      data.rows.forEach((row, index) => {
        const y = top - TITLE_TO_ROWS - ROW_HEIGHT * (index + 1) + ROW_HEIGHT / 2
        const width = PANEL_WIDTH - PADDING * 2
        const plate = this.surface(`HistoryRow-${row.key}`, 0, y, width, ROW_HEIGHT - 6)
        plate.fillColor = COLOR_ROW
        plate.rect(-width / 2, -(ROW_HEIGHT - 6) / 2, width, ROW_HEIGHT - 6)
        plate.fill()
        const left = -width / 2 + 12
        this.label(row.heroName, COLOR_TEXT, 16, left, y + 8, 'left')
        this.label(`${row.poolName} · ${row.timeText}`, COLOR_DIM, 13, left, y - 10, 'left')
        if (row.pityText !== null) {
          this.label(row.pityText, COLOR_GOLD, 14, width / 2 - 12, y, 'right')
        }
      })
    }

    const footerY = top - TITLE_TO_ROWS - ROW_HEIGHT * 8 - FOOTER_GAP
    if (data.pages > 1) {
      this.button('PrevPage', '上一页', -PANEL_WIDTH / 2 + PADDING + 55, footerY, 110,
        data.page > 0, () => this.onPrev?.())
      this.button('NextPage', '下一页', PANEL_WIDTH / 2 - PADDING - 55, footerY, 110,
        data.page < data.pages - 1, () => this.onNext?.())
      this.label(data.pageNotice, COLOR_TEXT, 15, 0, footerY, 'center')
      this.label(`共 ${data.total} 条`, COLOR_DIM, 13, 0, footerY - 22, 'center')
    } else {
      this.label(`共 ${data.total} 条 · ${data.pageNotice}`, COLOR_DIM, 14, 0, footerY, 'center')
    }
  }

  /** 按钮：灰掉时**不吃触摸**（点了也不会翻页），与招募面板同一份做法。 */
  private button(name: string, text: string, x: number, y: number, width: number, enabled: boolean,
    onClick: () => void): void {
    const graphics = this.surface(name, x, y, width, BUTTON_HEIGHT)
    graphics.fillColor = enabled ? COLOR_ROW : COLOR_CARD
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 6)
    graphics.fill()
    graphics.strokeColor = enabled ? COLOR_GOLD : COLOR_DIM
    graphics.lineWidth = 1
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 6)
    graphics.stroke()
    if (enabled) {
      graphics.node.on('touch-start', onClick)
    }
    this.label(text, enabled ? COLOR_GOLD : COLOR_DIM, 15, x, y, 'center')
  }

  /** 造一块画布子节点：位置、**尺寸**与登记一次做完（默认 100×100 会让触摸区域错位）。 */
  private surface(name: string, x: number, y: number, width: number, height: number): Graphics {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(width, height))
    node.setPosition(new Vec3(x, y, 0))
    this.nodes.push(node)
    return node.addComponent(Graphics)
  }

  /** 造一个 Label：**锚点先按对齐方式定**再摆位置（默认中心锚点会让左对齐的边界参差）。 */
  private label(text: string, color: Color, size: number, x: number, y: number,
    align: 'left' | 'right' | 'center'): void {
    if (text === '') {
      return
    }
    const node = new Node('label')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setAnchorPoint(
      align === 'left' ? 0 : align === 'right' ? 1 : 0.5, 0.5)
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = text
    label.color = color
    label.fontSize = size
    label.lineHeight = size + 6
    label.horizontalAlign = align === 'left'
      ? Label.HorizontalAlign.LEFT
      : align === 'right' ? Label.HorizontalAlign.RIGHT : Label.HorizontalAlign.CENTER
    node.setPosition(new Vec3(x, y, 0))
    this.nodes.push(node)
  }
}
