/** 二级选择器共用的轻量弹层：分页选项、确认一次、可取消。 */

import { Color, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3 } from 'cc'
import type { ChoiceOption } from '../game/session/Choices'
import { applySystemUiFont } from './UiFont'

const COLOR_MASK = new Color(12, 10, 9, 238)
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

  constructor(parent: Node, title: string, width = 760) {
    this.node = new Node('ChoiceOverlay')
    this.node.layer = parent.layer
    parent.addChild(this.node)
    this.node.addComponent(UITransform).setContentSize(new Size(width, 430))
    this.node.on('touch-start', (_event: EventTouch) => {
      // 吞掉遮罩点击，防止透传到下面的网格或列表。
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

  show(options: readonly ChoiceOption[], onPick: (id: string) => void): void {
    this.options = Array.from(options)
    this.page = 0
    this.onPick = onPick
    this.node.active = true
    this.renderPage()
  }

  hide(): void {
    this.node.active = false
    this.options = []
    this.onPick = null
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
