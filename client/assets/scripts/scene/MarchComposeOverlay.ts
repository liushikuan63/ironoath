/**
 * 职责：出征**编成**弹层的表现层（B25-S1 首次出征入口）。照 `ChoiceOverlay` 那一套写法：
 * 普通类 + 一个父节点 + Graphics/Label 现画，不依赖编辑器资产。
 *
 * <p><b>本文件不做任何判定</b>：兵种能不能带、数量夹到多少、能不能提交，全部来自编排层下发的
 * {@link MarchComposeView}（它由 `game/world/MarchCompose.ts` 那份纯逻辑算出来）。
 * 这里只做两件表现层的事：把行画出来、把点击意图回抛（`onPick`）。
 *
 * <p><b>刻意不自动勾选全军</b>（裁决④(b)）：每行初始都是 0，玩家自己加。默认倾巢而在打野时
 * 亏掉家底，是"减负"最容易办成的一件事——办成了会比不减负更糟。
 *
 * <p><b>行是"当前选中/可用"两个数并排</b>：只显示"可用"玩家不知道自己已经选了多少；
 * 只显示"选中"又看不出还能加多少。两个数并排，点 ＋ 时玩家眼睛不用来回找。
 */

import { Color, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3 } from 'cc'
import type { MarchComposeView } from '../game/session/AppRoot'
import { applySystemUiFont } from './UiFont'

const COLOR_MASK = new Color(12, 10, 9, 232)
const COLOR_PANEL = new Color(43, 36, 29, 255)
const COLOR_ROW = new Color(59, 48, 38, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_DIM = new Color(150, 140, 124, 255)
const COLOR_GOLD = new Color(184, 134, 11, 255)
const COLOR_WARN = new Color(198, 90, 70, 255)

const PANEL_WIDTH = 620
const PANEL_HEIGHT = 460
const ROW_HEIGHT = 44
const VISIBLE_ROWS = 5
/** 一次点 ＋/− 走多少：10 是"来回点几下就能调到位"与"点一下不心疼"之间的取中值。 */
const STEP = 10

export class MarchComposeOverlay {
  private readonly node: Node
  private readonly titleLabel: Label
  private readonly coordLabel: Label
  private readonly noticeLabel: Label
  private readonly totalLabel: Label
  private readonly rowNodes: Node[] = []
  private readonly rowNameLabels: Label[] = []
  private readonly rowCountLabels: Label[] = []
  private readonly rowPlusNodes: Node[] = []
  private readonly rowMinusNodes: Node[] = []
  private readonly confirmNode: Node
  private readonly confirmLabel: Label
  private view: MarchComposeView | null = null

  /** 勾选意图（unitId 与它要变成的数量）；由编排层夹取后再回来重画。 */
  onPick: ((unitId: string, count: number) => void) | null = null
  onConfirm: (() => void) | null = null
  onCancel: (() => void) | null = null

  constructor(parent: Node, width = PANEL_WIDTH) {
    this.node = new Node('MarchCompose')
    this.node.layer = parent.layer
    parent.addChild(this.node)
    this.node.addComponent(UITransform).setContentSize(new Size(width, PANEL_HEIGHT))
    // 吞掉遮罩点击：否则点空白处会穿到下面的地图上（等于在地图上乱点）
    this.node.on('touch-start', (_event: EventTouch) => {
      /* 只吞不处理 */
    }, this)
    const background = this.node.addComponent(Graphics)
    background.fillColor = COLOR_MASK
    background.roundRect(-width / 2, -PANEL_HEIGHT / 2, width, PANEL_HEIGHT, 10)
    background.fill()

    this.titleLabel = this.addLabel(0, PANEL_HEIGHT / 2 - 28, 22, COLOR_GOLD)
    this.coordLabel = this.addLabel(0, PANEL_HEIGHT / 2 - 56, 15, COLOR_DIM)
    this.totalLabel = this.addLabel(0, -PANEL_HEIGHT / 2 + 92, 17, COLOR_TEXT)
    this.noticeLabel = this.addLabel(0, -PANEL_HEIGHT / 2 + 66, 15, COLOR_WARN)

    for (let index = 0; index < VISIBLE_ROWS; index++) {
      const row = this.createRow(index)
      this.rowNodes.push(row.node)
      this.rowNameLabels.push(row.name)
      this.rowCountLabels.push(row.count)
      this.rowMinusNodes.push(row.minus)
      this.rowPlusNodes.push(row.plus)
    }

    this.createButton('编成取消', '取消', -140, -PANEL_HEIGHT / 2 + 28, COLOR_ROW, COLOR_TEXT,
      () => this.onCancel?.())
    const confirm = this.createButton('编成出征', '出征', 140, -PANEL_HEIGHT / 2 + 28,
      COLOR_GOLD, COLOR_MASK, () => this.onConfirm?.())
    this.confirmNode = confirm.node
    this.confirmLabel = confirm.label

    this.node.active = false
  }

  /**
   * 渲染一块编成视图。`targetId` 为空 = 没有在编成 ⇒ 整块收起（而不是画一个空面板：
   * 空面板会让人以为"点了出征但没生效"）。
   */
  render(view: MarchComposeView): void {
    this.view = view
    if (view.targetId.length === 0) {
      this.node.active = false
      return
    }
    this.node.active = true
    this.titleLabel.string = `出征：${view.targetName}`
    this.coordLabel.string = `坐标 ${view.coordText}`
    this.totalLabel.string = `共派 ${view.compose.totalText} 兵`
    this.noticeLabel.string = view.notice ?? ''
    this.noticeLabel.color = view.notice === null ? COLOR_DIM : COLOR_WARN

    const options = view.compose.options.slice(0, VISIBLE_ROWS)
    this.rowNodes.forEach((row, index) => {
      const option = options[index]
      row.active = option !== undefined
      if (option === undefined) {
        return
      }
      this.rowNameLabels[index]!.string = option.unlocked
        ? option.name
        : `${option.name}（${option.unlockHint ?? '未解锁'}）`
      this.rowCountLabels[index]!.string = option.unlocked
        ? `${option.selected} / ${option.available}`
        : '不可出征'
      this.rowMinusNodes[index]!.active = option.unlocked && option.selected > 0
      this.rowPlusNodes[index]!.active = option.unlocked && option.selected < option.available
    })

    // 提交态：确认键灰掉且不吃触摸（双击发两份是最容易被投诉的"自动"类缺陷）
    this.confirmLabel.string = view.submitting ? '出征中…' : '出征'
    this.confirmLabel.color = view.submitting ? COLOR_DIM : COLOR_MASK
    this.confirmNode.active = !view.submitting
  }


  private createRow(index: number): { node: Node; name: Label; count: Label; plus: Node; minus: Node } {
    const node = new Node(`composeRow${index}`)
    this.node.addChild(node)
    const y = PANEL_HEIGHT / 2 - 92 - index * (ROW_HEIGHT + 4)
    node.setPosition(new Vec3(0, y, 0))
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH - 48, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-(PANEL_WIDTH - 48) / 2, -ROW_HEIGHT, PANEL_WIDTH - 48, ROW_HEIGHT, 6)
    graphics.fill()

    const name = this.childLabel(node, -(PANEL_WIDTH - 48) / 2 + 14, -ROW_HEIGHT / 2, 17, COLOR_TEXT)
    const count = this.childLabel(node, 0, -ROW_HEIGHT / 2, 17, COLOR_GOLD)
    const minus = this.rowButton(node, -(PANEL_WIDTH - 48) / 2 + 190, -ROW_HEIGHT / 2, '−', () => {
      const option = this.view?.compose.options[index]
      if (option !== undefined) {
        this.onPick?.(option.unitId, option.selected - STEP)
      }
    })
    const plus = this.rowButton(node, -(PANEL_WIDTH - 48) / 2 + 236, -ROW_HEIGHT / 2, '＋', () => {
      const option = this.view?.compose.options[index]
      if (option !== undefined) {
        this.onPick?.(option.unitId, option.selected + STEP)
      }
    })
    return { node, name, count, plus, minus }
  }

  private addLabel(x: number, y: number, fontSize: number, color: Color): Label {
    return this.childLabel(this.node, x, y, fontSize, color)
  }

  private childLabel(parent: Node, x: number, y: number, fontSize: number, color: Color): Label {
    const node = new Node('label')
    parent.addChild(node)
    node.addComponent(UITransform)
    node.setPosition(new Vec3(x, y, 0))
    const label = applySystemUiFont(node.addComponent(Label))
    label.color = color
    label.fontSize = fontSize
    label.lineHeight = fontSize + 6
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    return label
  }

  private rowButton(parent: Node, x: number, y: number, text: string, onTap: () => void): Node {
    const node = new Node(`row-${text}`)
    parent.addChild(node)
    node.setPosition(new Vec3(x, y, 0))
    node.addComponent(UITransform).setContentSize(new Size(38, 30))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.roundRect(-19, -15, 38, 30, 5)
    graphics.fill()
    const label = this.childLabel(node, 0, 0, 18, COLOR_TEXT)
    label.string = text
    node.on('touch-start', (_event: EventTouch) => onTap(), this)
    return node
  }

  private createButton(name: string, text: string, x: number, y: number, background: Color,
    foreground: Color, onTap: () => void): { node: Node; label: Label } {
    const node = new Node(name)
    this.node.addChild(node)
    node.setPosition(new Vec3(x, y, 0))
    node.addComponent(UITransform).setContentSize(new Size(180, 40))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = background
    graphics.roundRect(-90, -20, 180, 40, 8)
    graphics.fill()
    const label = this.childLabel(node, 0, 0, 19, foreground)
    label.string = text
    node.on('touch-start', (_event: EventTouch) => onTap(), this)
    return { node, label }
  }
}
