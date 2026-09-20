/**
 * 职责：研究页的表现层（V03-a-S1 读侧）。
 * 依赖：cc（渲染）、game/tech/TechPanel（数据组装）。
 *
 * <p>铁律 2：本文件是纯表现层 —— 只读传入的视图，不发请求、不判"能不能研究"。
 * 「可研究 / 为什么不能」两列在编排层（{@code buildTechPanel}）就已经定好了，这里只画。
 *
 * <p><b>锚点显式给</b>：Label 的框按文字长度自己长，默认中心锚点会让左/右对齐的每一行
 * 随句子长短漂（收口清单 #199 与 #262 是同一个根因的两次），所以这里一律先 setAnchorPoint。
 *
 * <p>占位美术用 Graphics 画纯色块（与战力页同一套做法）：正式美术到位只换绘制部分。
 */
import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3, view } from 'cc'
import type { TechPanelView as TechViewData, TechRow } from '../game/tech/TechPanel'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

const COLOR_SCRIM = new Color(0, 0, 0, 170)
const COLOR_BACKGROUND = new Color(24, 20, 18, 255)
const COLOR_ROW = new Color(40, 33, 28, 255)
const COLOR_ROW_ALT = new Color(48, 39, 33, 255)
const COLOR_ROW_LOCKED = new Color(32, 29, 26, 255)
const COLOR_BUTTON = new Color(62, 44, 26, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_HINT = new Color(120, 168, 196, 255)

const CARD_WIDTH = 560
const CARD_HEIGHT = 600
/**
 * 行高按"11 项全部画得下"定的（实测：38 会挤掉最后一项，而这一页**没有滚动**，
 * 挤掉就等于那一项玩家永远够不到）。表里现在 11 行，再加行就得配翻页 —— 到那时改这里并让探针跟着改。
 */
const ROW_HEIGHT = 34
const GROUP_HEIGHT = 20
const PADDING = 16
/** 底部留给关闭按钮的高度：行画到这儿就停，剩下的如实报"还有几项"（不硬塞、不静默截断）。 */
const BOTTOM_RESERVED = 56

@ccclass('TechPanelView')
export class TechPanelView extends Component {

  private readonly rows: Node[] = []
  private viewData: TechViewData | null = null
  /** 玩家点了某一行的「研究」。发不发、能不能发由外层按服务端那份 `canResearch` 判 */
  onResearch: ((techId: string) => void) | null = null

  /** 下发一份视图即显示。**每次都重画**：队列剩余时间会走，复用旧值会显示过期数字。 */
  render(view: TechViewData): void {
    this.viewData = view
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

    let y = CARD_HEIGHT / 2 - PADDING - 10
    y = this.drawTitle(y)
    y = this.drawQueue(y)
    y = this.drawRows(y)
    this.drawClose()
  }

  /** 遮罩：这一页会盖在内城/地图/战斗回放几种完全不同的场景上，靠背景透出来读不清。 */
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

  private drawTitle(y: number): number {
    const view = this.viewData
    this.label('研 究', COLOR_COPPER_GOLD, 26, -CARD_WIDTH / 2 + PADDING, y - 12, 'left')
    if (view !== null && view.academyText !== '') {
      this.label(view.academyText, COLOR_TEXT_DIM, 18, CARD_WIDTH / 2 - PADDING, y - 12, 'right')
    }
    return y - 34
  }

  /** 队列行：正在研究哪一项、还剩多久。没有在研项时**不占这一行**（留白比一句"空闲"省地方）。 */
  private drawQueue(y: number): number {
    const text = this.viewData?.queueText ?? null
    if (text === null) {
      return y
    }
    this.label(text, COLOR_HINT, 18, -CARD_WIDTH / 2 + PADDING, y - 10, 'left')
    return y - 26
  }

  /**
   * 科技行。按学派的**顺序变化**插一条分组标题（不重排行 —— 顺序是服务端给的）。
   *
   * 放不下的行不画，但**如实说还有几项**：静默截断会让玩家以为科技树只有这么长。
   */
  private drawRows(startY: number): number {
    const view = this.viewData
    if (view === null || view.rows.length === 0) {
      this.label(view?.noticeText ?? '正在载入…', COLOR_HINT, 18, 0, startY - 12, 'center')
      return startY - 30
    }
    const floor = -CARD_HEIGHT / 2 + BOTTOM_RESERVED
    let y = startY
    let school = ''
    let drawn = 0
    for (const row of view.rows) {
      const needsGroup = row.schoolText !== school
      const height = ROW_HEIGHT + (needsGroup ? GROUP_HEIGHT : 0)
      if (y - height < floor) {
        break
      }
      if (needsGroup) {
        school = row.schoolText
        this.label(school, COLOR_COPPER_GOLD, 16, -CARD_WIDTH / 2 + PADDING, y - 8, 'left')
        y -= GROUP_HEIGHT
      }
      this.drawRow(row, y, drawn)
      y -= ROW_HEIGHT
      drawn += 1
    }
    const hidden = view.rows.length - drawn
    if (hidden > 0) {
      this.label(`还有 ${hidden} 项未显示（面板放不下，先研究前面的）`,
        COLOR_TEXT_DIM, 15, 0, floor + 14, 'center')
    }
    if (view.noticeText !== null && drawn > 0) {
      this.label(view.noticeText, COLOR_HINT, 15, -CARD_WIDTH / 2 + PADDING, floor + 14, 'left')
    }
    return y
  }

  private drawRow(row: TechRow, y: number, index: number): void {
    const usable = CARD_WIDTH - PADDING * 2
    const node = new Node(`tech-${row.techId}`)
    this.node.addChild(node)
    const transform = node.addComponent(UITransform)
    transform.setContentSize(usable, ROW_HEIGHT - 4)
    node.setPosition(new Vec3(0, y, 0))

    const graphics = node.addComponent(Graphics)
    graphics.fillColor = row.canResearch ? (index % 2 === 0 ? COLOR_ROW : COLOR_ROW_ALT) : COLOR_ROW_LOCKED
    graphics.rect(-usable / 2, -ROW_HEIGHT + 2, usable, ROW_HEIGHT - 4)
    graphics.fill()
    this.rows.push(node)

    const left = -usable / 2 + 10
    const right = usable / 2 - 10
    this.label(row.name, row.canResearch ? COLOR_TEXT : COLOR_TEXT_DIM, 18, left, y - 10, 'left')
    this.label(row.levelText, COLOR_TEXT_DIM, 14, left + 150, y - 10, 'left')
    this.label(row.costText, COLOR_TEXT_DIM, 14, right, y - 10, 'right')
    const second = row.effectText === null
      ? (row.timeText === null ? '' : `耗时 ${row.timeText}`)
      : `${row.effectText}${row.timeText === null ? '' : ` · 耗时 ${row.timeText}`}`
    this.label(second, COLOR_TEXT_DIM, 14, left, y - 25, 'left')
    if (row.canResearch) {
      // 「可研究」以前只是一句字 —— 玩家看得见这一行能做，但点下去什么都没有发生。
      // 现在它是一颗键，按下去发 POST /tech/research
      this.drawResearch(row.techId, right, y - 25)
    } else if (row.reasonText !== null) {
      this.label(row.reasonText, COLOR_TEXT_DIM, 15, right, y - 25, 'right')
    }
  }

  /** 行上的「研究」键。命名带 techId，运行时探针才按得到具体那一行。 */
  private drawResearch(techId: string, right: number, y: number): void {
    const node = new Node(`research-${techId}`)
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(76, 24)
    node.setPosition(new Vec3(right - 38, y, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BUTTON
    graphics.rect(-38, -12, 76, 24)
    graphics.fill()
    node.on('touch-start', () => this.onResearch?.(techId))
    this.rows.push(node)
    this.label('研究', COLOR_COPPER_GOLD, 15, right - 38, y, 'center')
  }

  private drawClose(): void {
    const bottom = -CARD_HEIGHT / 2 + 28
    const node = new Node('close')
    this.node.addChild(node)
    const transform = node.addComponent(UITransform)
    transform.setContentSize(160, 36)
    node.setPosition(new Vec3(0, bottom, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BUTTON
    graphics.rect(-80, -18, 160, 36)
    graphics.fill()
    node.on('touch-start', () => this.hide())
    this.rows.push(node)
    this.label('关闭', COLOR_COPPER_GOLD, 18, 0, bottom, 'center')
  }

  /** 造一个左右对齐靠得住的 Label：**锚点先按对齐方式定**，再摆位置。 */
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
    this.onResearch = null
  }
}
