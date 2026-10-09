/**
 * 职责：武将「用碎片合成一名未拥有的武将」弹层的表现层（V03-d 第六条养成线）。
 * 依赖：cc（渲染）、game/hero/HeroCompose（数据组装）。
 *
 * <p>铁律 2：本文件只读传入的视图，点击只喊一声（够不够、发不发都由编排层与纯逻辑定）。
 * 与觉醒弹层刻意**不共用一个组件**：那两层选的是"给哪个已有武将花哪种材料"，
 * 这一层选的是"花掉哪一档碎片去换一个还没有的武将"，抬头还要多一行的钱包 —— 合成一个组件
 * 就得在每个绘制分支上挂 mode，两条线各自改动时互相绊住。
 *
 * <p>碎片不够的那一行**照样画出来**（灰字 + 还差几片），而不是藏掉：藏掉的语义是"这个武将不存在"，
 * 玩家会以为养成线断了。
 *
 * <p>行数按**可视高度**现算、卡片高度按实际行数算：写死行数会把第 N 行挤到屏外
 * （#262 的科技页、#273 的觉醒弹层都是截图才发现的），而 12 名武将全未拥有时正好越界。
 */
import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3, view } from 'cc'
import type { HeroComposeRow, HeroComposeView } from '../game/hero/HeroCompose'
import { applySystemUiFont } from './UiFont'
import { DIALOG_SCRIM, finishLegacyDialog, applyDialogButton } from './DialogStyle'

const { ccclass } = _decorator

const COLOR_SCRIM = DIALOG_SCRIM
const COLOR_BACKGROUND = new Color(24, 20, 18, 255)
const COLOR_ROW = new Color(40, 33, 28, 255)
const COLOR_ROW_SELECTED = new Color(62, 44, 26, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_HINT = new Color(120, 168, 196, 255)

const CARD_WIDTH = 560
/**
 * 行高与底板高照觉醒弹层那一版已目视过的比例（56 / 50）：17 号字的**盒子**实测高 29（不是 23，
 * Cocos 给 Label 的 contentSize 按字体的行距算），底板半高 18 时字的上沿会溢出 1.5px ——
 * 截图上就是名字被底板顶边切掉一截，而"中心在板内"的读数判据抓不到这件事。
 */
const ROW_HEIGHT = 56
const PLATE_HEIGHT = 50
/** 抬头标题基线到第一行钱包的落距 */
const PURSE_DROP = 52
const PURSE_LINE = 20
/** 最后一行钱包到第一行底板之间留的空气：截图抓到第二行钱包被第一行底板切掉半截 */
const ROWS_CLEAR = 24
const BUTTON_BAND = 56
const BUTTON_HEIGHT = 36
const PADDING = 16

@ccclass('ComposePickOverlay')
export class ComposePickOverlay extends Component {

  private readonly nodes: Node[] = []
  private data: HeroComposeView | null = null

  /** 选中某个武将（只有可用的行会喊这一声）。 */
  onPick: ((heroId: string) => void) | null = null
  /** 确认合成（没选中可用的武将时按钮画灰、不吃触摸）。 */
  onConfirm: (() => void) | null = null
  /** 取消：关掉弹层，什么都不发。 */
  onCancel: (() => void) | null = null

  render(data: HeroComposeView, purse: readonly string[]): void {
    this.data = data
    this.purse = purse
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
    finishLegacyDialog(this.node, this.nodes)
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
   * 抬头占掉的高度（标题 + 钱包那几行 + 第一行底板前的空气），半行高之外算进行栈之前。
   * 钱包行数**由实际条数决定**：写死 44 的那一版把第二行钱包压到了第一行底板底下（截图抓到的）。
   */
  private headerHeight(): number {
    return PADDING + 10 + PURSE_DROP + this.purseLines().length * PURSE_LINE + ROWS_CLEAR + ROW_HEIGHT / 2
  }

  /** 卡片高度按**实际行数**算（截断后剩下的那些行），空态也占一行 —— 说明文字要有地方落。 */
  private cardHeight(): number {
    return this.headerHeight() + Math.max(this.shown().length, 1) * ROW_HEIGHT + BUTTON_BAND
  }

  /** 滚动区承载完整名单，确认取消固定在净区底边。 */
  private shown(): readonly HeroComposeRow[] {
    const rows = this.data?.rows ?? []
    // 净区内滚动承载完整名单，不把超过一屏的武将藏起来。
    return rows
  }

  private drawRows(): void {
    const data = this.data
    if (data === null) {
      return
    }
    const half = this.cardHeight() / 2
    const top = half - PADDING - 10
    this.label('碎片合成武将', COLOR_COPPER_GOLD, 24, -CARD_WIDTH / 2 + PADDING, top - 12, 'left')
    this.label(data.summaryText, COLOR_TEXT_DIM, 15, CARD_WIDTH / 2 - PADDING, top - 12, 'right')

    // 钱包那几行：这一屏要说"够不够"，先让玩家看见自己有什么（数据来自 fragmentTexts 那份真源）
    const purse = this.purseLines()
    let y = top - 40
    for (const line of purse) {
      this.label(line, COLOR_HINT, 13, -CARD_WIDTH / 2 + PADDING, y, 'left')
      y -= PURSE_LINE
    }

    const startY = top - PURSE_DROP - purse.length * PURSE_LINE - ROWS_CLEAR
    const rows = this.shown()
    if (rows.length === 0) {
      this.label(data.emptyText ?? '', COLOR_HINT, 18, 0, startY, 'center')
      return
    }
    const width = CARD_WIDTH - PADDING * 2
    let rowY = startY
    for (const row of rows) {
      this.drawRow(row, width, rowY)
      rowY -= ROW_HEIGHT
    }
    const hidden = data.rows.length - rows.length
    if (hidden > 0) {
      this.label(`另有 ${hidden} 名未列出（这一屏画不下）`, COLOR_TEXT_DIM, 13,
        -width / 2 + 12, rowY, 'left')
    }
  }

  /** 四档钱包排成两行两列；不足四档时按实际条数排。 */
  private purseLines(): string[] {
    const texts = this.purse
    if (texts.length === 0) {
      return []
    }
    const half = Math.ceil(texts.length / 2)
    const lines = [texts.slice(0, half).join(' · ')]
    if (texts.length > half) {
      lines.push(texts.slice(half).join(' · '))
    }
    return lines
  }

  /** 抬头那几行钱包文本：与视图一起下发，来源是 `HeroPanel.fragmentTexts` 那份真源。 */
  private purse: readonly string[] = []

  private drawRow(row: HeroComposeRow, width: number, y: number): void {
    const selected = this.data?.selectedHeroId === row.heroId
    const graphics = this.surface(`compose-${row.heroId}`, 0, y, width, PLATE_HEIGHT)
    graphics.fillColor = selected ? COLOR_ROW_SELECTED : COLOR_ROW
    graphics.rect(-width / 2, -PLATE_HEIGHT / 2, width, PLATE_HEIGHT)
    graphics.fill()
    if (row.usable) {
      // 不可用的那行不吃触摸：它的原因已经写在行上了，点它只会有"按了没反应"
      graphics.node.on('touch-end', () => this.onPick?.(row.heroId))
    }

    // 两行字都落在自己那块底板里（±PLATE_HEIGHT/2）：压到下一行就会被下一行的底板盖住
    const left = -width / 2 + 12
    this.label(row.name, row.usable ? COLOR_TEXT : COLOR_TEXT_DIM, 17, left, y + 8, 'left')
    this.label(row.detailText, COLOR_TEXT_DIM, 13, left, y - 11, 'left')
    this.label(selected ? '已选' : '', COLOR_COPPER_GOLD, 16, width / 2 - 12, y - 1, 'right')
  }

  private drawButtons(): void {
    const bottom = -this.cardHeight() / 2 + 28
    const canSend = this.data?.canSend ?? false
    this.button('cancel', '取消', -120, bottom, true, () => {
      this.hide()
      this.onCancel?.()
    })
    this.button('confirm', this.data?.sendText ?? '先选一名武将', 120, bottom, canSend, () => {
      // 按确认即收起：意图已经表达出来了；失败理由走统一上报口，重开是干净的一份
      this.hide()
      this.onConfirm?.()
    })
  }

  /** 底部按钮：灰掉时**不吃触摸**（点了也不会发请求），与觉醒弹层同一条纪律。 */
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
    applyDialogButton(graphics.node, enabled, width, BUTTON_HEIGHT)
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
