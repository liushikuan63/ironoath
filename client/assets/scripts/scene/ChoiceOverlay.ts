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
  /** 面板宽：行的色带与两个标签都按它排，写死 700 会让窄面板溢出 */
  private readonly width: number
  private page = 0
  private onPick: ((id: string) => void) | null = null

  constructor(parent: Node, title: string, width = 760) {
    this.width = width
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
    // 行是从 NodePool 里 acquire 出来的：宿主每次渲染都把行重新 addChild 到父节点末尾，
    // 而弹层建得比它们早 —— 于是"后加的压在弹层上面"。抬层不能只靠 `show()`（渲染发生在
    // 它之后，背包实测），也不能靠八个宿主各自记得抬一次（漏一个就是一个玩家可见缺陷）。
    // 谁往父节点后面加东西，就把自己的序号顶回末位；弹层没显示时不动。
    parent.on('child-added', (node: Node) => {
      if (node !== this.node && this.node.active) {
        this.raise()
      }
    }, this)
    this.node.active = false
  }

  show(options: readonly ChoiceOption[], onPick: (id: string) => void): void {
    // 显示前先抬到父节点最后：弹层在各面板 `onLoad` 就建好了，而列表行是每次渲染才
    // addChild 的 —— 加得晚就压在菜单上面。背包那格实测：道具行（含「使用」键）横盖住
    // 「选择加速目标」的标题，读数全绿而玩家看到的是半截字。放在这里而不是每个调用方
    // 各喊一次，是因为八个使用者都有同一条时序，漏一个就是一个玩家可见缺陷。
    this.raise()
    this.options = Array.from(options)
    this.page = 0
    this.onPick = onPick
    this.node.active = true
    this.renderPage()
  }

  /**
   * 抬到父节点最后。两个时机自己会抬：`show()` 打开时，以及父节点后面又长了别的子节点时
   * （见构造函数里那条 `child-added`）—— 宿主不需要记得抬，也不该各自记一次。
   */
  private raise(): void {
    const parent = this.node.parent
    if (parent === null || parent === undefined) {
      return
    }
    // 不能用 `parent.addChild(this.node)`：3.8.7 里"已经是这个父节点的子节点"时它是空操作
    // （实测：父节点 children 为 A,B，再 addChild(A) 仍是 A,B）。只有 setSiblingIndex 真挪位置，
    // 所以这条抬层此前一直静默失效，背包的弹层被道具行压住才把它暴露出来。
    this.node.setSiblingIndex(parent.children.length - 1)
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
    const bandWidth = this.width - 40
    node.addComponent(UITransform).setContentSize(new Size(bandWidth, 52))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-bandWidth / 2, -26, bandWidth, 52, 6)
    graphics.fill()
    const title = this.addLabelTo(node, 0, 8, 17, COLOR_TEXT)
    const detail = this.addLabelTo(node, 0, -12, 13, COLOR_DIM)
    // 标签要自己有宽度：addLabelTo 挂的 UITransform 是默认的 100×100，而 overflow=SHRINK
    // 是按盒子排的 —— 不设就会把一句 17 号的标题挤成两三行（军队那格实测：
    // 「取消这一口训练」被拆成两行，与下面那条的说明叠在一起）
    title.node.getComponent(UITransform)?.setContentSize(new Size(bandWidth - 24, 24))
    detail.node.getComponent(UITransform)?.setContentSize(new Size(bandWidth - 24, 18))
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
