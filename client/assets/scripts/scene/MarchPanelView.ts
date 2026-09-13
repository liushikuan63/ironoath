/**
 * 职责：世界地图上的行军列表面板 —— 展示在外的队伍，提供召回/收取入口。
 * 依赖：cc（渲染）、game/world/MarchPanel（展示数据与动作资格）。
 *
 * <p>这是场景层组件，不参与 node:test；所有可判定的文案与动作资格都在
 * `game/world/MarchPanel.ts`，这里只负责节点、布局与点击转发。
 */

import { Color, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3 } from 'cc'
import type { MarchPanelAction, MarchPanelRow } from '../game/world/MarchPanel'
import { applyCommandButton, applySimpleSprite, applySlicedSprite, hasArt } from './ArtCatalog'
import { applySystemUiFont } from './UiFont'

const COLOR_BACKDROP = new Color(6, 5, 4, 190)
const COLOR_PANEL = new Color(36, 30, 25, 250)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_ALT = new Color(46, 38, 31, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_DISABLED = new Color(32, 29, 27, 255)

const PANEL_WIDTH = 720
const PANEL_HEIGHT = 356
const ROW_WIDTH = PANEL_WIDTH - 28
const ROW_HEIGHT = 68
const ROW_GAP = 8
const MAX_ROWS = 3
const ACTION_WIDTH = 84
const ACTION_HEIGHT = 34

interface RowRefs {
  readonly node: Node
  readonly title: Label
  readonly detail: Label
  readonly remaining: Label
  readonly action: Node
  readonly actionCaption: Label
  marchId: string
  actionKind: MarchPanelAction | null
}

export class MarchPanelView {
  private readonly backdrop: Node
  private readonly panel: Node
  private readonly header: Label
  private readonly empty: Label
  private readonly rows: RowRefs[] = []
  private visible = false

  onAction: ((action: MarchPanelAction, marchId: string) => void) | null = null
  onClose: (() => void) | null = null

  constructor(parent: Node, width: number, height: number) {
    this.backdrop = this.createBackdrop(parent, width, height)
    this.panel = this.createPanel(parent)
    this.header = this.addLabel(this.panel, 'Header', 0, PANEL_HEIGHT / 2 - 28,
      COLOR_COPPER_GOLD, 19)
    this.empty = this.addLabel(this.panel, 'Empty', 0, -8, COLOR_TEXT_DIM, 16)
    this.buildCloseButton()
    for (let index = 0; index < MAX_ROWS; index++) {
      this.rows.push(this.buildRow(index))
    }
    this.backdrop.active = false
    this.panel.active = false
  }

  get isVisible(): boolean {
    return this.visible
  }

  toggle(): void {
    if (this.visible) {
      this.hide()
    } else {
      this.show()
    }
  }

  show(): void {
    this.visible = true
    this.backdrop.active = true
    this.panel.active = true
  }

  hide(): void {
    this.visible = false
    this.backdrop.active = false
    this.panel.active = false
  }

  render(rows: readonly MarchPanelRow[], requesting: ReadonlySet<string>): void {
    this.header.string = `行军队伍 ${rows.length}`
    this.empty.node.active = rows.length === 0
    this.rows.forEach((refs, index) => {
      const row = rows[index]
      refs.node.active = row !== undefined
      if (row === undefined) {
        refs.marchId = ''
        refs.actionKind = null
        refs.action.off('touch-start')
        return
      }
      const pending = requesting.has(row.marchId)
      refs.marchId = row.marchId
      refs.actionKind = row.action
      refs.title.string = row.title
      refs.detail.string = row.detail
      refs.remaining.string = pending ? '处理中…' : row.remainingText
      refs.remaining.color = pending ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM

      const hasAction = row.action !== null && row.actionText !== null
      refs.action.active = hasAction
      refs.action.off('touch-start')
      if (!applySimpleSprite(refs.action,
        pending ? 'ui.button.command.disabled' : 'ui.button.command',
        ACTION_WIDTH, ACTION_HEIGHT)) {
        const graphics = refs.action.getComponent(Graphics) ?? refs.action.addComponent(Graphics)
        graphics.clear()
        graphics.fillColor = pending ? COLOR_DISABLED : COLOR_ROW_ALT
        graphics.strokeColor = pending ? COLOR_TEXT_DIM : COLOR_COPPER_GOLD
        graphics.lineWidth = 1
        graphics.roundRect(-ACTION_WIDTH / 2, -ACTION_HEIGHT / 2,
          ACTION_WIDTH, ACTION_HEIGHT, 5)
        graphics.fill()
        graphics.stroke()
      }
      if (!hasAction) {
        return
      }
      refs.actionCaption.string = pending ? '处理中' : row.actionText ?? ''
      refs.actionCaption.color = pending ? COLOR_TEXT_DIM : COLOR_TEXT
      refs.action.on('touch-start', (_event: EventTouch) => {
        if (refs.marchId.length === 0 || refs.actionKind === null || pending) {
          return
        }
        this.onAction?.(refs.actionKind, refs.marchId)
      }, this)
    })
  }

  destroy(): void {
    this.onAction = null
    this.onClose = null
    this.rows.length = 0
    this.backdrop.destroy()
    this.panel.destroy()
  }

  private createBackdrop(parent: Node, width: number, height: number): Node {
    const node = new Node('MarchBackdrop')
    node.layer = parent.layer
    parent.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(width, height))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKDROP
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
    node.on('touch-start', (_event: EventTouch) => this.hide(), this)
    return node
  }

  private createPanel(parent: Node): Node {
    const node = new Node('MarchPanel')
    node.layer = parent.layer
    parent.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, PANEL_HEIGHT))
    if (!applySlicedSprite(node, 'ui.panel.kingdom', PANEL_WIDTH, PANEL_HEIGHT)) {
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = COLOR_PANEL
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 2
      graphics.roundRect(-PANEL_WIDTH / 2, -PANEL_HEIGHT / 2, PANEL_WIDTH, PANEL_HEIGHT, 10)
      graphics.fill()
      graphics.stroke()
    }
    return node
  }

  private buildCloseButton(): void {
    const node = new Node('CloseButton')
    node.layer = this.panel.layer
    this.panel.addChild(node)
    node.setPosition(new Vec3(PANEL_WIDTH / 2 - 48, PANEL_HEIGHT / 2 - 28, 0))
    node.addComponent(UITransform).setContentSize(new Size(64, 30))
    if (!applyCommandButton(node, 'normal', 64, 30)) {
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = COLOR_ROW_ALT
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 1
      graphics.roundRect(-32, -15, 64, 30, 5)
      graphics.fill()
      graphics.stroke()
    }
    this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT, 13).string = '关闭'
    node.on('touch-start', (_event: EventTouch) => this.onClose?.(), this)
  }

  private buildRow(index: number): RowRefs {
    const node = new Node(`MarchRow-${index}`)
    node.layer = this.panel.layer
    this.panel.addChild(node)
    const y = PANEL_HEIGHT / 2 - 70 - index * (ROW_HEIGHT + ROW_GAP)
    node.setPosition(new Vec3(0, y, 0))
    node.addComponent(UITransform).setContentSize(new Size(ROW_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = index % 2 === 0 ? COLOR_ROW : COLOR_ROW_ALT
    graphics.roundRect(-ROW_WIDTH / 2, -ROW_HEIGHT / 2, ROW_WIDTH, ROW_HEIGHT, 6)
    graphics.fill()

    const title = this.addLabel(node, 'Title', -ROW_WIDTH / 2 + 14, 14, COLOR_TEXT, 16, true, 400)
    const detail = this.addLabel(node, 'Detail', -ROW_WIDTH / 2 + 14, -13, COLOR_TEXT_DIM, 12, true, 430)
    const remaining = this.addLabel(node, 'Remaining', ROW_WIDTH / 2 - 132, 12,
      COLOR_TEXT_DIM, 13)
    const action = new Node('Action')
    action.layer = node.layer
    node.addChild(action)
    action.setPosition(new Vec3(ROW_WIDTH / 2 - 52, -6, 0))
    action.addComponent(UITransform).setContentSize(new Size(ACTION_WIDTH, ACTION_HEIGHT))
    if (!hasArt('ui.button.command')) {
      action.addComponent(Graphics)
    }
    const actionCaption = this.addLabel(action, 'Caption', 0, 0, COLOR_TEXT, 13)
    action.active = false
    return { node, title, detail, remaining, action, actionCaption, marchId: '', actionKind: null }
  }

  private addLabel(parent: Node, name: string, x: number, y: number, color: Color,
                   fontSize: number, leftAligned = false, maxWidth = 0): Label {
    const node = new Node(name)
    node.layer = parent.layer
    parent.addChild(node)
    const transform = node.addComponent(UITransform)
    node.setPosition(new Vec3(x, y, 0))
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = ''
    label.color = color
    label.fontSize = fontSize
    label.horizontalAlign = leftAligned ? Label.HorizontalAlign.LEFT : Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    if (leftAligned) {
      transform.setAnchorPoint(0, 0.5)
    }
    if (maxWidth > 0) {
      transform.setContentSize(new Size(maxWidth, fontSize * 1.7))
      label.overflow = Label.Overflow.SHRINK
    }
    return label
  }
}
