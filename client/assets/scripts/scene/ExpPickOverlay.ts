/**
 * 职责：武将升级"喂经验道具"弹层的表现层（V03-d；口径＝逐件选数量）。
 * 依赖：cc（渲染）、game/hero/ExpPick（数据组装）。
 *
 * <p>铁律 2：本文件是纯表现层 —— 只读传入的视图，点击只喊一声（加减与确认都交给编排层）。
 * 候选怎么筛、计数怎么夹、全 0 能不能发，全部在 `ExpPick.ts` 里定好了。
 *
 * <p>锚点先定再摆位、行逐条 setPosition（#199 / #262 / 池化行那几次的教训）。
 */
import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3, view } from 'cc'
import type { ExpPickView } from '../game/hero/ExpPick'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

const COLOR_SCRIM = new Color(0, 0, 0, 170)
const COLOR_BACKGROUND = new Color(24, 20, 18, 255)
const COLOR_ROW = new Color(40, 33, 28, 255)
const COLOR_ROW_ALT = new Color(48, 39, 33, 255)
const COLOR_BUTTON = new Color(62, 44, 26, 255)
const COLOR_BUTTON_OFF = new Color(42, 37, 32, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_HINT = new Color(120, 168, 196, 255)

const CARD_WIDTH = 560
const CARD_HEIGHT = 520
const ROW_HEIGHT = 44
const PADDING = 16

@ccclass('ExpPickOverlay')
export class ExpPickOverlay extends Component {

  private readonly rows: Node[] = []
  private data: ExpPickView | null = null
  /** 标题里那个武将的名字（编排层给；给不出时留空） */
  private heroName = ''

  /** 加减一件：`delta` 是 +1 / -1，夹取与重画由编排层与下一次 render 负责。 */
  onBump: ((itemId: string, delta: number) => void) | null = null
  /** 确认喂（全 0 时视图把按钮画灰并不吃触摸）。 */
  onConfirm: (() => void) | null = null
  /** 取消（关掉弹层，什么都不发）。 */
  onCancel: (() => void) | null = null

  render(data: ExpPickView, heroName: string): void {
    this.data = data
    this.heroName = heroName
    this.node.active = true
    this.redraw()
  }

  hide(): void {
    this.node.active = false
  }

  private redraw(): void {
    this.clearRows()
    this.drawScrim()
    this.drawCard()
    this.drawTitle()
    this.drawRows()
    this.drawButtons()
  }

  private drawScrim(): void {
    const size = view.getVisibleSize()
    const node = new Node('scrim')
    this.node.addChild(node)
    node.addComponent(UITransform)
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_SCRIM
    graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
    graphics.fill()
    this.rows.push(node)
  }

  private drawCard(): void {
    const node = new Node('card')
    this.node.addChild(node)
    node.addComponent(UITransform)
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-CARD_WIDTH / 2, -CARD_HEIGHT / 2, CARD_WIDTH, CARD_HEIGHT)
    graphics.fill()
    this.rows.push(node)
  }

  private drawTitle(): void {
    const y = CARD_HEIGHT / 2 - PADDING - 10
    const name = this.heroName === '' ? '武将' : this.heroName
    this.label(`给 ${name} 喂经验`, COLOR_COPPER_GOLD, 24, -CARD_WIDTH / 2 + PADDING, y - 12, 'left')
    const total = this.data?.totalPicked ?? 0
    this.label(`已选 ${total} 本`, COLOR_TEXT_DIM, 16, CARD_WIDTH / 2 - PADDING, y - 12, 'right')
  }

  private drawRows(): void {
    const data = this.data
    const startY = CARD_HEIGHT / 2 - PADDING - 52
    if (data === null || data.rows.length === 0) {
      this.label(data?.emptyText ?? '正在载入…', COLOR_HINT, 18, 0, startY - 16, 'center')
      return
    }
    const usable = CARD_WIDTH - PADDING * 2
    let y = startY
    data.rows.forEach((row, index) => {
      const node = new Node(`pick-${row.itemId}`)
      this.node.addChild(node)
      node.addComponent(UITransform).setContentSize(usable, ROW_HEIGHT - 4)
      node.setPosition(new Vec3(0, y, 0))
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = index % 2 === 0 ? COLOR_ROW : COLOR_ROW_ALT
      graphics.rect(-usable / 2, -ROW_HEIGHT + 2, usable, ROW_HEIGHT - 4)
      graphics.fill()
      this.rows.push(node)

      const left = -usable / 2 + 10
      this.label(row.name, COLOR_TEXT, 18, left, y - 12, 'left')
      this.label(`持有 ${row.held}`, COLOR_TEXT_DIM, 14, left, y - 30, 'left')
      // 右侧：− 数量 +（数量单独一行显示，免得玩家以为点的是删掉）
      const right = usable / 2 - 10
      this.button('minus', '−', right - 120, y - 12, true, () => this.onBump?.(row.itemId, -1))
      this.label(`${row.picked}`, row.picked > 0 ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM,
        20, right - 72, y - 12, 'center')
      this.button('plus', '＋', right - 24, y - 12, row.picked < row.held,
        () => this.onBump?.(row.itemId, 1))
      y -= ROW_HEIGHT
    })
  }

  private drawButtons(): void {
    const bottom = -CARD_HEIGHT / 2 + 28
    const canSend = this.data?.canSend ?? false
    this.button('cancel', '取消', -120, bottom, true, () => {
      this.hide()
      this.onCancel?.()
    })
    this.button('confirm', canSend ? '确认喂' : '先选道具', 120, bottom, canSend, () => {
      // 按确认即收起：玩家的意图已经表达出来了；失败时理由走统一上报口，重开是干净的一份
      this.hide()
      this.onConfirm?.()
    })
  }

  /** 小按钮：灰掉时**不吃触摸**（点了也不会发请求），与翻页按钮同一条纪律。 */
  private button(name: string, text: string, x: number, y: number, enabled: boolean,
    onClick: () => void): void {
    const width = name === 'cancel' || name === 'confirm' ? 180 : 44
    const height = name === 'cancel' || name === 'confirm' ? 36 : 28
    const node = new Node(name)
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(width, height)
    node.setPosition(new Vec3(x, y, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = enabled ? COLOR_BUTTON : COLOR_BUTTON_OFF
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
    if (enabled) {
      node.on('touch-start', onClick)
    }
    this.rows.push(node)
    this.label(text, enabled ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM,
      name === 'cancel' || name === 'confirm' ? 18 : 20, x, y, 'center')
  }

  /** 造一个左右/居中靠得住的 Label：**锚点先按对齐方式定**，再摆位置。 */
  private label(text: string, color: Color, size: number, x: number, y: number,
    align: 'left' | 'right' | 'center'): Label {
    const node = new Node('label')
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
    this.rows.push(node)
    return label
  }

  private clearRows(): void {
    for (const row of this.rows) {
      row.destroy()
    }
    this.rows.length = 0
  }

  override onDestroy(): void {
    this.clearRows()
  }
}
