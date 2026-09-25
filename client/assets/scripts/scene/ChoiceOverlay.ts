/** 二级选择器共用的轻量弹层：分页选项、确认一次、可取消。 */

import { Color, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3 } from 'cc'
import type { ChoiceOption } from '../game/session/Choices'
import { applySystemUiFont } from './UiFont'

const COLOR_MASK = new Color(12, 10, 9, 238)
/**
 * 面板背后的全屏压暗层。
 *
 * <p>为什么要有它：面板自身的底色与城景/列表底一样是暗褐，只靠一块圆角矩形和背景"粘连"在一起，
 * 边界读不出来（审计 §2.5 的视觉评审原话：「面板自身边界非常模糊，与背后同样暗沉的城景严重粘连」）。
 * 压暗层同时把"这一层是模态"讲清楚 —— 它下面的东西这一刻不能点。
 */
const COLOR_SCRIM = new Color(8, 6, 5, 150)
const COLOR_PANEL = new Color(43, 36, 29, 255)
const COLOR_ROW = new Color(59, 48, 38, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_DIM = new Color(150, 140, 124, 255)
const COLOR_GOLD = new Color(184, 134, 11, 255)
const OPTIONS_PER_PAGE = 4

export class ChoiceOverlay {
  private readonly node: Node
  private readonly titleLabel: Label
  private readonly pageLabel: Label
  private readonly optionNodes: Node[] = []
  private readonly optionTitleLabels: Label[] = []
  private readonly optionDetailLabels: Label[] = []
  private options: ChoiceOption[] = []
  private page = 0
  private onPick: ((id: string) => void) | null = null
  private onHide: (() => void) | null = null

  constructor(parent: Node, title: string, width = 760) {
    this.node = new Node('ChoiceOverlay')
    this.node.layer = parent.layer
    parent.addChild(this.node)
    this.node.addComponent(UITransform).setContentSize(new Size(width, 430))
    this.node.on('touch-start', (_event: EventTouch) => {
      // 吞掉遮罩点击，防止透传到下面的网格或列表。
    }, this)

    // 全屏压暗层：作为**第一个子节点**挂进来，于是它画在所有内容之下、
    // 又随 `this.node.active` 一起开关。尺寸给得远大于任何面板，覆盖整个可视区。
    const scrim = new Node('ChoiceScrim')
    scrim.layer = parent.layer
    this.node.addChild(scrim)
    scrim.addComponent(UITransform).setContentSize(new Size(4000, 4000))
    const scrimGraphics = scrim.addComponent(Graphics)
    scrimGraphics.fillColor = COLOR_SCRIM
    scrimGraphics.rect(-2000, -2000, 4000, 4000)
    scrimGraphics.fill()
    scrim.on('touch-start', (_event: EventTouch) => {
      // 压暗层自己也要吞点击：它比 `this.node` 的命中盒大，不吞就会漏到下层
    }, this)

    const background = this.node.addComponent(Graphics)
    background.fillColor = COLOR_MASK
    background.roundRect(-width / 2, -215, width, 430, 10)
    background.fill()

    this.titleLabel = this.addLabel(0, 178, 22, COLOR_GOLD)
    this.titleLabel.string = title
    this.pageLabel = this.addLabel(0, 144, 15, COLOR_DIM)

    for (let index = 0; index < OPTIONS_PER_PAGE; index++) {
      const row = this.createRow(index)
      this.optionNodes.push(row.node)
      this.optionTitleLabels.push(row.title)
      this.optionDetailLabels.push(row.detail)
    }

    this.createCommandButton('ChoicePrev', '上一页', -150, -170, () => {
      if (this.page > 0) {
        this.page--
        this.renderPage()
      }
    })
    this.createCommandButton('ChoiceNext', '下一页', 0, -170, () => {
      if (this.page + 1 < this.pageCount()) {
        this.page++
        this.renderPage()
      }
    })
    this.createCommandButton('ChoiceCancel', '取消', 150, -170, () => this.hide())
    this.node.active = false
  }

  show(options: readonly ChoiceOption[], onPick: (id: string) => void,
       onHide?: () => void): void {
    this.options = Array.from(options)
    this.page = 0
    this.onPick = onPick
    this.onHide = onHide ?? null
    this.node.active = true
    this.renderPage()
  }

  hide(): void {
    this.node.active = false
    this.options = []
    this.onPick = null
    // `hide` 是唯一的关闭出口（取消、选中、以及面板被拆时都走它），
    // 所以把"关掉了"这件事挂在它上面，调用方不必在每条路径上各恢复一次状态。
    const callback = this.onHide
    this.onHide = null
    callback?.()
  }

  private createRow(index: number): { node: Node, title: Label, detail: Label } {
    const node = new Node(`Choice-${index}`)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.setPosition(new Vec3(0, 92 - index * 60, 0))
    node.addComponent(UITransform).setContentSize(new Size(700, 52))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-350, -26, 700, 52, 6)
    graphics.fill()
    const title = this.addLabelTo(node, 0, 8, 17, COLOR_TEXT)
    const detail = this.addLabelTo(node, 0, -12, 13, COLOR_DIM)
    // 必须显式给宽度：`addLabelTo` 只 `addComponent(UITransform)`，contentSize 停在默认的 100×100，
    // 而 `Overflow.SHRINK` 正是照这个宽度去缩字号的 —— 长文案被压成一团、还与相邻文字叠在一起。
    // 现场症状：选择器每行显示成「资源 伐木场 城」三截重叠（审计 §5.3 的"信息辨识度低"里最重的一条）。
    title.node.getComponent(UITransform)?.setContentSize(new Size(660, 26))
    detail.node.getComponent(UITransform)?.setContentSize(new Size(660, 22))
    title.overflow = Label.Overflow.SHRINK
    detail.overflow = Label.Overflow.SHRINK
    return { node, title, detail }
  }

  private createCommandButton(name: string, text: string, x: number, y: number,
                              onTap: () => void): void {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.setPosition(new Vec3(x, y, 0))
    node.addComponent(UITransform).setContentSize(new Size(120, 38))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.strokeColor = COLOR_GOLD
    graphics.lineWidth = 1
    graphics.roundRect(-60, -19, 120, 38, 6)
    graphics.fill()
    graphics.stroke()
    const label = this.addLabelTo(node, 0, 0, 15, COLOR_TEXT)
    label.string = text
    node.on('touch-start', (_event: EventTouch) => onTap(), this)
  }

  private renderPage(): void {
    const pages = this.pageCount()
    this.pageLabel.string = pages <= 1 ? `${this.options.length} 个可选目标` : `第 ${this.page + 1}/${pages} 页`
    const pageOptions = this.options.slice(
      this.page * OPTIONS_PER_PAGE, (this.page + 1) * OPTIONS_PER_PAGE)
    for (let index = 0; index < OPTIONS_PER_PAGE; index++) {
      const row = this.optionNodes[index]
      const option = pageOptions[index]
      if (row === undefined) {
        continue
      }
      row.active = option !== undefined
      row.off('touch-start')
      if (option === undefined) {
        continue
      }
      const title = this.optionTitleLabels[index]
      const detail = this.optionDetailLabels[index]
      if (title !== undefined) {
        title.string = option.label
      }
      if (detail !== undefined) {
        detail.string = option.detail
      }
      row.on('touch-start', (_event: EventTouch) => {
        const callback = this.onPick
        this.hide()
        callback?.(option.id)
      }, this)
    }
  }

  private pageCount(): number {
    return Math.max(1, Math.ceil(this.options.length / OPTIONS_PER_PAGE))
  }

  private addLabel(x: number, y: number, size: number, color: Color): Label {
    return this.addLabelTo(this.node, x, y, size, color)
  }

  private addLabelTo(parent: Node, x: number, y: number, size: number, color: Color): Label {
    const node = new Node('Label')
    node.layer = parent.layer
    parent.addChild(node)
    node.setPosition(new Vec3(x, y, 0))
    node.addComponent(UITransform)
    const label = applySystemUiFont(node.addComponent(Label))
    label.color = color
    label.fontSize = size
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    return label
  }
}
