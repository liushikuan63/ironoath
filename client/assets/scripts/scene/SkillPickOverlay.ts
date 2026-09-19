/**
 * 职责：武将技能「选一本技能书」弹层的表现层（V03-d 最后一条养成线）。
 * 依赖：cc（渲染）、game/hero/SkillPick（数据组装）。
 *
 * <p>铁律 2：只读传入的视图，点击只喊一声（哪本点亮、升哪一路都由纯逻辑与编排层定）。
 * 行上的第二行写的是**那一路技能的等级变化**（`Lv3 → Lv4`），因为玩家真正在问的是
 * "这本用下去主技能会到几级"；不可用的那本照样画出来并写原因，不藏。
 *
 * <p>与觉醒弹层各自一份 chrome（Label 助手、遮罩、卡片）是跟着本仓库既有写法走的：
 * 场景层的每个 View 都自带这一套（`ExpPickOverlay` / `TechPanelView` / `EquipPanelView` 都是）。
 * 想收成一份公共 chrome 是另一格的事，别在加一条线的时候顺手改两条已验过的线。
 */
import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3, view } from 'cc'
import type { SkillPickView, SkillRow } from '../game/hero/SkillPick'
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

const CARD_WIDTH = 560
const ROW_HEIGHT = 56
/** 底板比行高低一截：两行字必须都落在**自己那一行**的底板里（觉醒弹层栽过的，见台账 #273） */
const PLATE_HEIGHT = ROW_HEIGHT - 6
const TITLE_BAND = 78
const BUTTON_BAND = 62
const BUTTON_HEIGHT = 36
const PADDING = 16

@ccclass('SkillPickOverlay')
export class SkillPickOverlay extends Component {

  private readonly nodes: Node[] = []
  private data: SkillPickView | null = null
  private heroName = ''

  /** 选中某本书（只有可用的行会喊这一声）。 */
  onPick: ((itemId: string) => void) | null = null
  /** 确认升技能（没选中可用的书时按钮画灰、不吃触摸）。 */
  onConfirm: (() => void) | null = null
  onCancel: (() => void) | null = null

  render(data: SkillPickView, heroName: string): void {
    this.data = data
    this.heroName = heroName
    this.node.active = true
    this.redraw()
  }

  hide(): void {
    this.node.active = false
  }

  private redraw(): void {
    this.clearNodes()
    this.drawScrim()
    this.drawCard()
    this.drawRows()
    this.drawButtons()
  }

  private drawScrim(): void {
    const size = view.getVisibleSize()
    const graphics = this.surface('scrim', 0, 0, size.width, size.height)
    graphics.fillColor = COLOR_SCRIM
    graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
    graphics.fill()
  }

  private drawCard(): void {
    const height = this.cardHeight()
    const graphics = this.surface('card', 0, 0, CARD_WIDTH, height)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-CARD_WIDTH / 2, -height / 2, CARD_WIDTH, height)
    graphics.fill()
  }

  /** 卡片高度按实际行数算（写死高度要么留空白、要么把第 N 行挤出去）。 */
  private cardHeight(): number {
    const lines = Math.max(this.data?.rows.length ?? 0, 1)
    return TITLE_BAND + lines * ROW_HEIGHT + BUTTON_BAND
  }

  private drawRows(): void {
    const data = this.data
    if (data === null) {
      return
    }
    const top = this.cardHeight() / 2 - PADDING - 10
    const name = this.heroName === '' ? '武将' : this.heroName
    this.label(`给 ${name} 升技能`, COLOR_COPPER_GOLD, 24, -CARD_WIDTH / 2 + PADDING, top - 12, 'left')
    this.label(`共 ${data.rows.length} 本技能书`, COLOR_TEXT_DIM, 15,
      CARD_WIDTH / 2 - PADDING, top - 12, 'right')

    const startY = top - 52
    if (data.rows.length === 0) {
      this.label(data.emptyText ?? '', COLOR_HINT, 18, 0, startY, 'center')
      return
    }
    const width = CARD_WIDTH - PADDING * 2
    let y = startY
    for (const row of data.rows) {
      this.drawRow(row, width, y)
      y -= ROW_HEIGHT
    }
  }

  private drawRow(row: SkillRow, width: number, y: number): void {
    const selected = this.data?.selectedItemId === row.itemId
    const graphics = this.surface(`skill-${row.itemId}`, 0, y, width, PLATE_HEIGHT)
    graphics.fillColor = selected ? COLOR_ROW_SELECTED : COLOR_ROW
    graphics.rect(-width / 2, -PLATE_HEIGHT / 2, width, PLATE_HEIGHT)
    graphics.fill()
    if (row.usable) {
      graphics.node.on('touch-start', () => this.onPick?.(row.itemId))
    }

    const left = -width / 2 + 12
    this.label(row.name, row.usable ? COLOR_TEXT : COLOR_TEXT_DIM, 19, left, y + 8, 'left')
    // 第二行：可用时报"升哪一路 + 等级变化 + 余数"，不可用时只报原因
    this.label(row.reason ?? `${row.slotText} ${row.levelText} · 持有 ${row.held}`,
      COLOR_TEXT_DIM, 14, left, y - 11, 'left')
    this.label(selected ? '已选' : '', COLOR_COPPER_GOLD, 17, width / 2 - 12, y - 2, 'right')
  }

  private drawButtons(): void {
    const bottom = -this.cardHeight() / 2 + 30
    const canSend = this.data?.canSend ?? false
    this.button('cancel', '取消', -120, bottom, true, () => {
      this.hide()
      this.onCancel?.()
    })
    this.button('confirm', this.data?.sendText ?? '先选技能书', 120, bottom, canSend, () => {
      // 按确认即收起：意图已经表达出来了；失败理由走统一上报口，重开是干净的一份
      this.hide()
      this.onConfirm?.()
    })
  }

  /** 底部按钮：灰掉时**不吃触摸**（点了也不会发请求），与升级 / 觉醒弹层同一条纪律。 */
  private button(name: string, text: string, x: number, y: number, enabled: boolean,
    onClick: () => void): void {
    const width = 180
    const graphics = this.surface(name, x, y, width, BUTTON_HEIGHT)
    graphics.fillColor = enabled ? COLOR_ROW_SELECTED : COLOR_ROW
    graphics.rect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT)
    graphics.fill()
    if (enabled) {
      graphics.node.on('touch-start', onClick)
    }
    this.label(text, enabled ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM, 18, x, y, 'center')
  }

  /** 造一块居中的画布子节点：位置、尺寸与登记一次做完（尺寸必须显式给，触摸判定按它算）。 */
  private surface(name: string, x: number, y: number, width: number, height: number): Graphics {
    const node = new Node(name)
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(width, height)
    node.setPosition(new Vec3(x, y, 0))
    this.nodes.push(node)
    return node.addComponent(Graphics)
  }

  /** 造一个 Label：**锚点先按对齐方式定**再摆位置。 */
  private label(text: string, color: Color, size: number, x: number, y: number,
    align: 'left' | 'right' | 'center'): void {
    if (text === '') {
      return
    }
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
  }
}
