/**
 * 职责：编队编辑弹层（B06 §4，`POST /hero/lineup` 的唯一入口）的表现层。
 * 依赖：cc（渲染）、game/hero/LineupEdit（数据组装，已单测）。
 *
 * <p>铁律 2：本文件只读传入的视图，点击只喊一声。哪一槽是谁、名单里谁灰、为什么灰、
 * 能不能保存，全在 `game/hero/LineupEdit.ts` 判，而那些数全来自 `/hero/list`。
 *
 * <p>三槽**一直画着**（空位写「空」）：藏掉空位的话玩家不知道自己少排了一个人，
 * 而少排一个人直接影响缘分与统率加成。
 *
 * <p>几何照已目视过的比例（行 56 / 板 50），行数按**可视高度**现算：
 * 名单可能比一屏长（名册十几人），装不下就少画几行并写明「另有 N 名未列出」。
 */
import { _decorator, Color, Component, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import type { LineupEditView, PickRow, SlotRow } from '../game/hero/LineupEdit'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

const COLOR_SCRIM = new Color(0, 0, 0, 170)
const COLOR_BACKGROUND = new Color(24, 20, 18, 255)
const COLOR_ROW = new Color(40, 33, 28, 255)
const COLOR_ROW_SELECTED = new Color(62, 44, 26, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_HINT = new Color(120, 168, 196, 255)
const COLOR_WARNING = new Color(200, 96, 64, 255)

const CARD_WIDTH = 560
const ROW_HEIGHT = 56
const PLATE_HEIGHT = 50
const TITLE_DROP = 40
const PADDING = 16
const BUTTON_BAND = 56
/**
 * 行栈与按钮带之间的空气。不加这一条时卡片高度公式与行的排布**恰好抵消**，
 * 算出来最后一块板的下沿与按钮上沿之间永远只剩 1px —— 截图上就是最后一行被切掉半截。
 */
const BUTTON_CLEAR = 32
const BUTTON_HEIGHT = 36
const SAFE_MARGIN = 40

@ccclass('LineupEditOverlay')
export class LineupEditOverlay extends Component {

  private readonly nodes: Node[] = []
  private data: LineupEditView | null = null

  /** 点某一槽 = 要换这一槽的人（名单由编排层下发）。 */
  onPickSlot: ((slot: SlotRow['slot']) => void) | null = null
  /** 选哪个武将（只有能点的那几行会喊）。 */
  onChoose: ((heroId: string) => void) | null = null
  /** 清空正在选的这一槽（服务端接受 null = 这一位没人）。 */
  onClear: (() => void) | null = null
  /** 保存这一队（不能保存时键画灰、不吃触摸）。 */
  onSave: (() => void) | null = null
  /** 取消：关掉编辑器，编辑中的槽位随之丢弃。 */
  onCancel: (() => void) | null = null

  render(data: LineupEditView): void {
    this.data = data
    this.node.active = true
    this.redraw()
  }

  hide(): void {
    this.node.active = false
  }

  private redraw(): void {
    this.clearNodes()
    const size = view.getVisibleSize()
    this.drawScrim(size.width, size.height)
    this.drawCard()
    this.drawBody()
    this.drawButtons()
  }

  private drawScrim(width: number, height: number): void {
    const graphics = this.surface('scrim', 0, 0, width, height)
    graphics.fillColor = COLOR_SCRIM
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
  }

  /** 能点的名额：三槽 + （在选时）名单 + 一行"另有 N 名"的余量。 */
  private shownPicks(): readonly PickRow[] {
    const data = this.data
    if (data === null || data.pickingSlot === null) {
      return []
    }
    return data.picks.slice(0, this.pickCapacity())
  }

  /** 名单画得下几行：由可视高度与"标题 + 三槽 + 按钮"占掉的高度现算，不写死。 */
  private pickCapacity(): number {
    const used = TITLE_DROP + 3 * ROW_HEIGHT + BUTTON_BAND + BUTTON_CLEAR + PADDING * 2 + SAFE_MARGIN * 2
    return Math.max(1, Math.floor((view.getVisibleSize().height - used) / ROW_HEIGHT))
  }

  private cardHeight(): number {
    const data = this.data
    const picks = this.shownPicks().length
    const hiddenNote = data !== null && data.picks.length > picks ? ROW_HEIGHT / 2 : 0
    return TITLE_DROP + 3 * ROW_HEIGHT + picks * ROW_HEIGHT + hiddenNote
      + BUTTON_BAND + BUTTON_CLEAR + PADDING
  }

  private drawCard(): void {
    const height = this.cardHeight()
    const graphics = this.surface('card', 0, 0, CARD_WIDTH, height)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-CARD_WIDTH / 2, -height / 2, CARD_WIDTH, height)
    graphics.fill()
  }

  private drawBody(): void {
    const data = this.data
    if (data === null) {
      return
    }
    const top = this.cardHeight() / 2 - PADDING
    this.label(`${data.presetText} · 编队编辑`, COLOR_COPPER_GOLD, 22,
      -CARD_WIDTH / 2 + PADDING, top - 14, 'left')
    this.label(data.pickingSlot === null ? '点一槽换人' : '选一名武将',
      COLOR_TEXT_DIM, 14, CARD_WIDTH / 2 - PADDING, top - 14, 'right')

    const width = CARD_WIDTH - PADDING * 2
    let y = top - TITLE_DROP
    for (const row of data.slots) {
      y -= ROW_HEIGHT
      this.drawSlotRow(row, width, y)
    }
    for (const pick of this.shownPicks()) {
      y -= ROW_HEIGHT
      this.drawPickRow(pick, width, y)
    }
    const hidden = data.picks.length - this.shownPicks().length
    if (hidden > 0) {
      y -= ROW_HEIGHT / 2
      this.label(`另有 ${hidden} 名未列出（这一屏画不下）`, COLOR_TEXT_DIM, 13,
        -width / 2 + 12, y, 'left')
    }
  }

  private drawSlotRow(row: SlotRow, width: number, y: number): void {
    const graphics = this.surface(`slot-${row.slot}`, 0, y, width, PLATE_HEIGHT)
    graphics.fillColor = row.picking ? COLOR_ROW_SELECTED : COLOR_ROW
    graphics.rect(-width / 2, -PLATE_HEIGHT / 2, width, PLATE_HEIGHT)
    graphics.fill()
    graphics.node.on('touch-start', () => this.onPickSlot?.(row.slot))

    const left = -width / 2 + 12
    this.label(row.label, COLOR_COPPER_GOLD, 15, left, y + 8, 'left')
    this.label(row.heroName ?? '空', row.empty ? COLOR_TEXT_DIM : COLOR_TEXT, 17, left, y - 11, 'left')
    this.label(row.picking ? '选人中' : '', COLOR_HINT, 14, width / 2 - 12, y - 1, 'right')
  }

  private drawPickRow(pick: PickRow, width: number, y: number): void {
    const graphics = this.surface(`pick-${pick.heroId}`, 0, y, width, PLATE_HEIGHT)
    graphics.fillColor = COLOR_ROW
    graphics.rect(-width / 2, -PLATE_HEIGHT / 2, width, PLATE_HEIGHT)
    graphics.fill()
    if (pick.usable) {
      // 不能点的那几行不吃触摸：原因已经写在行上了，点它只会有"按了没反应"
      graphics.node.on('touch-start', () => this.onChoose?.(pick.heroId))
    }
    const left = -width / 2 + 12
    this.label(pick.name, pick.usable ? COLOR_TEXT : COLOR_TEXT_DIM, 17, left, y + 8, 'left')
    // 第二行：战力 + （在别的队的标注）+ （不能点的原因）。标注不是拒绝：服务端允许一人在多队
    const detail = [pick.powerText, pick.note ?? '', pick.reason ?? ''].filter((s) => s !== '').join(' · ')
    this.label(detail, pick.usable ? COLOR_TEXT_DIM : COLOR_WARNING, 13, left, y - 11, 'left')
  }

  private drawButtons(): void {
    const data = this.data
    if (data === null) {
      return
    }
    const bottom = -this.cardHeight() / 2 + 28
    this.button('cancel', '取消', -CARD_WIDTH / 2 + PADDING + 90, bottom, true, () => {
      this.hide()
      this.onCancel?.()
    })
    if (data.pickingSlot !== null) {
      this.button('clearSlot', '清空这一槽', 0, bottom, true, () => {
        this.onClear?.()
      })
    }
    this.button('save', data.saveText, CARD_WIDTH / 2 - PADDING - 90, bottom, data.canSave, () => {
      this.hide()
      this.onSave?.()
    })
  }

  /** 按钮：灰掉时**不吃触摸**（点了也不会发请求）。底板必须画出来 —— 它是"能不能点"的唯一视觉信号。 */
  private button(name: string, text: string, x: number, y: number, enabled: boolean,
    onClick: () => void): void {
    const width = 180
    const graphics = this.surface(name, x, y, width, BUTTON_HEIGHT)
    graphics.fillColor = enabled ? COLOR_ROW_SELECTED : COLOR_ROW
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 6)
    graphics.fill()
    graphics.strokeColor = enabled ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
    graphics.lineWidth = 1
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 6)
    graphics.stroke()
    if (enabled) {
      graphics.node.on('touch-start', onClick)
    }
    this.label(text, enabled ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM, 15, x, y, 'center')
  }

  /** 造一块居中的画布子节点：位置、**尺寸**与登记一次做完（默认 100×100 会让触摸区域错位）。 */
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

  private clearNodes(): void {
    for (const node of this.nodes) {
      node.destroy()
    }
    this.nodes.length = 0
  }

  override onDestroy(): void {
    this.clearNodes()
    this.onPickSlot = null
    this.onChoose = null
    this.onClear = null
    this.onSave = null
    this.onCancel = null
  }
}
