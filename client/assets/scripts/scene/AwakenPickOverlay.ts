/**
 * 职责：武将觉醒「选一块觉醒石」弹层的表现层（V03-d 第二条养成线）。
 * 依赖：cc（渲染）、game/hero/AwakenPick（数据组装）。
 *
 * <p>铁律 2：本文件只读传入的视图，点击只喊一声（选哪块、能不能发都由编排层与纯逻辑定）。
 * 与升级弹层刻意**不共用一个组件**：那一层是"逐件加加减减 + 汇总"，这一层是"点一行选中 + 换标记"，
 * 合成一个组件就得在每个绘制分支上挂 `mode`，两条线各自改动时互相绊住。
 *
 * <p>不可用的那行**照样画出来**（灰字 + 原因），而不是藏掉：藏掉之后玩家会以为背包里没有这块石。
 * 锚点先定再摆位、行逐条 setPosition（#199 / #262 / 池化行那几次的教训）。
 */
import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3, view } from 'cc'
import type { AwakenPickView, AwakenRow } from '../game/hero/AwakenPick'
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
/** 底板比行高低一截：两行字必须都落在**自己那一行**的底板里，压到下一行就会被盖住（截图抓到的） */
const PLATE_HEIGHT = ROW_HEIGHT - 6
const TITLE_BAND = 78
const BUTTON_BAND = 62
const BUTTON_HEIGHT = 36
const PADDING = 16

@ccclass('AwakenPickOverlay')
export class AwakenPickOverlay extends Component {

  private readonly nodes: Node[] = []
  private data: AwakenPickView | null = null
  /** 标题里那个武将的名字（编排层给；给不出时留空） */
  private heroName = ''

  /** 选中某块石（只有可用的行会喊这一声）。 */
  onPick: ((itemId: string) => void) | null = null
  /** 确认觉醒（没选中可用的石时按钮画灰、不吃触摸）。 */
  onConfirm: (() => void) | null = null
  /** 取消：关掉弹层，什么都不发。 */
  onCancel: (() => void) | null = null

  render(data: AwakenPickView, heroName: string): void {
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

  /**
   * 卡片高度按**实际行数**算：写死高度要么留一截空白、要么把第 N 行挤到卡片外
   * （科技页那一屏就是靠截图才发现"第 11 项够不到"）。空态也占一行 —— 那行说明文字要有地方落。
   */
  private cardHeight(): number {
    const lines = Math.max(this.data?.rows.length ?? 0, 1)
    return TITLE_BAND + lines * ROW_HEIGHT + BUTTON_BAND
  }

  private drawRows(): void {
    const data = this.data
    if (data === null) {
      return
    }
    const half = this.cardHeight() / 2
    const top = half - PADDING - 10
    const name = this.heroName === '' ? '武将' : this.heroName
    this.label(`给 ${name} 觉醒`, COLOR_COPPER_GOLD, 24, -CARD_WIDTH / 2 + PADDING, top - 12, 'left')
    this.label(data.stageText, COLOR_TEXT_DIM, 15, CARD_WIDTH / 2 - PADDING, top - 12, 'right')

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

  private drawRow(row: AwakenRow, width: number, y: number): void {
    const selected = this.data?.selectedItemId === row.itemId
    const graphics = this.surface(`awaken-${row.itemId}`, 0, y, width, PLATE_HEIGHT)
    graphics.fillColor = selected ? COLOR_ROW_SELECTED : COLOR_ROW
    graphics.rect(-width / 2, -PLATE_HEIGHT / 2, width, PLATE_HEIGHT)
    graphics.fill()
    if (row.usable) {
      // 不可用的那行不吃触摸：它的原因已经写在行上了，点它只会有"按了没反应"
      graphics.node.on('touch-start', () => this.onPick?.(row.itemId))
    }

    // 两行字都落在自己那块底板里（±PLATE_HEIGHT/2）：压到下一行就会被下一行的底板盖住
    const left = -width / 2 + 12
    this.label(row.name, row.usable ? COLOR_TEXT : COLOR_TEXT_DIM, 19, left, y + 8, 'left')
    // 满阶那档没有"这块石为什么不行"可说（原因是整张卡那一行的"已达上限"），照常报余数
    this.label(row.reason ?? `持有 ${row.held}`, COLOR_TEXT_DIM, 14, left, y - 11, 'left')
    this.label(selected ? '已选' : '', COLOR_COPPER_GOLD, 17, width / 2 - 12, y - 2, 'right')
  }

  private drawButtons(): void {
    const bottom = -this.cardHeight() / 2 + 30
    const canSend = this.data?.canSend ?? false
    this.button('cancel', '取消', -120, bottom, true, () => {
      this.hide()
      this.onCancel?.()
    })
    this.button('confirm', this.data?.sendText ?? '先选觉醒石', 120, bottom, canSend, () => {
      // 按确认即收起：意图已经表达出来了；失败理由走统一上报口，重开是干净的一份
      this.hide()
      this.onConfirm?.()
    })
  }

  /** 底部按钮：灰掉时**不吃触摸**（点了也不会发请求），与升级弹层同一条纪律。 */
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

  /**
   * 造一块居中的画布子节点：位置、**尺寸**与登记一次做完，返回 Graphics。
   * 尺寸必须显式给 —— 新建节点的 UITransform 默认 100×100，触摸判定按它算，
   * 只画不定的话按钮会"看着小、点着大"（#242 那批池化行叠在 y=0 是同一族的反面教材）。
   */
  private surface(name: string, x: number, y: number, width: number, height: number): Graphics {
    const node = new Node(name)
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(width, height)
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
