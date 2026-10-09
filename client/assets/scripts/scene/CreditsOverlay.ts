/**
 * 职责：开源许可与署名覆盖层 —— 把 `game/settings/Credits.ts` 的清单画出来（CC BY 3.0 的授权条件）。
 * 依赖：cc（渲染）、game/settings/Credits（数据与折行，已单测）。
 *
 * <p>铁律 2：本文件只画传入的数据，一个字都不自己写死 —— 署名内容改一个字就不是原作者给出的那份许可了，
 * 所以文本的唯一产地是 `Credits.ts`（它又逐字抄自 `art-src/ATTRIBUTION.md`）。
 *
 * <p>许可内容不依赖服务端；重开时按当前尺寸量取净区，长文完整进入可滚动窗口。
 */
import { _decorator, Color, Component, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { CREDITS, wrapCredit } from '../game/settings/Credits'
import type { CreditEntry } from '../game/settings/Credits'
import { applySystemUiFont } from './UiFont'
import { DIALOG_SCRIM, finishLegacyDialog, applyDialogButton } from './DialogStyle'
import { IRON_SURFACE } from '../game/ui/UiTokens'

const { ccclass } = _decorator

const COLOR_MASK = DIALOG_SCRIM
const COLOR_CARD = new Color(28, 23, 20, 255)
const COLOR_TEXT = new Color(...IRON_SURFACE)
const COLOR_DIM = COLOR_TEXT
const COLOR_GOLD = new Color(184, 134, 11, 255)

const CARD_WIDTH = 760
const PADDING = 24
/** 每条之间留的空白：条目之间不分隔线，靠间距分组（画线会让 3 条看着像 6 条） */
const ENTRY_GAP = 14
const LINE_HEIGHT = 21
const TITLE_HEIGHT = 30
const CLOSE_HEIGHT = 40
/**
 * 许可与备注那一行的折行宽度。
 *
 * <p>13 号字、中英混排：54 个字符大约落在卡片内宽（`CARD_WIDTH - 2 * PADDING` = 712）之内。
 * 写成一个实测过的字符数而不是"运行时量宽度再回写"，是因为引擎的 Label 尺寸要等到渲染才准，
 * 而这一页的内容是静态的 —— 首跑就是这一行顶出了卡片右沿约 8px。
 */
const NOTE_PER_LINE = 54

/** 许可与备注拼成一行：**唯一产地在这里**，界面各处不自己拼。 */
function licenseLine(entry: CreditEntry): string {
  return `${entry.license} · ${entry.note}`
}

@ccclass('CreditsOverlay')
export class CreditsOverlay extends Component {
  onClose: (() => void) | null = null
  private readonly nodes: Node[] = []

  override onLoad(): void {
    this.build()
  }

  override onDestroy(): void {
    this.nodes.length = 0
    this.onClose = null
  }

  show(): void {
    this.build()
    this.node.active = true
  }

  hide(): void {
    this.node.active = false
  }

  private build(): void {
    for (const node of this.nodes) {
      node.destroy()
    }
    this.nodes.length = 0
    const size = view.getVisibleSize()

    this.surface('CreditsMask', 0, 0, size.width, size.height, COLOR_MASK)
    // 卡片高度按**内容现算**：条目数变了（将来加素材）也不会把最后一条挤出屏幕
    const bodyLines = CREDITS.reduce((sum, entry) => sum + 2 + wrapCredit(entry.credit).length
      + wrapCredit(licenseLine(entry), NOTE_PER_LINE).length, 0)
    const cardHeight = PADDING * 2 + TITLE_HEIGHT + bodyLines * LINE_HEIGHT
      + CREDITS.length * ENTRY_GAP + CLOSE_HEIGHT + 16
    this.surface('CreditsCard', 0, 0, CARD_WIDTH, cardHeight, COLOR_CARD)

    const top = cardHeight / 2 - PADDING
    const left = -CARD_WIDTH / 2 + PADDING
    let y = top - 22
    this.label('开源许可与署名', COLOR_TEXT, 22, left, y, 'left')
    y -= TITLE_HEIGHT

    for (const entry of CREDITS) {
      this.label(entry.title, COLOR_TEXT, 17, left, y, 'left')
      y -= LINE_HEIGHT
      // 作者与来源逐字展示（**不折成缩写**）：这一行就是 CC BY 3.0 要求保留的那段原文
      for (const line of wrapCredit(entry.credit)) {
        this.label(line, COLOR_TEXT, 14, left, y, 'left')
        y -= LINE_HEIGHT
      }
      // 许可与备注**也要折**：它们是中英混排的长句，13 号字下按 54 字符折才落在卡片内宽之内。
      // 首跑截图里这一行顶出了卡片右沿约 8px —— 探针当时全绿，是眼睛抓到的；
      // 现在它同时是探针的一条结构判据（见 tools/verify-credits.mjs 的"没有一行超出卡片内宽"）
      for (const line of wrapCredit(licenseLine(entry), NOTE_PER_LINE)) {
        this.label(line, COLOR_DIM, 13, left, y, 'left')
        y -= LINE_HEIGHT
      }
      y -= ENTRY_GAP
    }

    this.button('CreditsCloseButton', '关闭', 0, y - 6, 160)
    finishLegacyDialog(this.node, this.nodes, 'ui.panel.parchment')
  }

  private button(name: string, text: string, x: number, y: number, width: number): void {
    const height = 34
    const graphics = this.surface(name, x, y, width, height, COLOR_CARD)
    graphics.strokeColor = COLOR_GOLD
    graphics.lineWidth = 1
    graphics.roundRect(-width / 2, -height / 2, width, height, 6)
    graphics.stroke()
    graphics.node.on('touch-start', () => this.onClose?.(), this)
    applyDialogButton(graphics.node, true, width, height)
    this.label(text, COLOR_GOLD, 17, x, y, 'center')
  }

  /** 造一块画布子节点：位置、**尺寸**与登记一次做完（默认 100×100 会让触摸区域错位）。 */
  private surface(name: string, x: number, y: number, width: number, height: number, fill: Color): Graphics {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(width, height))
    node.setPosition(new Vec3(x, y, 0))
    this.nodes.push(node)
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = fill
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
    return graphics
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
