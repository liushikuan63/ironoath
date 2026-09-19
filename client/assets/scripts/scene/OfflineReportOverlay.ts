/**
 * 职责：「自上次登录以来」那一屏汇总的表现层（B25-S3）。照 `MarchComposeOverlay` / `ChoiceOverlay`
 * 的写法：普通类 + 一个父节点 + Graphics/Label 现画，不依赖编辑器资产。
 *
 * <p><b>本文件不做任何判定</b>：弹不弹、列哪几条、跳哪一页，全部来自编排层下发的
 * {@link OfflineReportPopup}（由 `game/offline/OfflineReport.ts` 那份纯逻辑算出来）。
 *
 * <p><b>标题是「自上次登录以来」而不是「离线收益」</b>（裁决①(a) 的原文）：这个边界包含玩家上次
 * 在线的那段时间，称它"离线"就是把在线期间的账也算进离线。
 *
 * <p><b>每条可点、点完就跳</b>：汇总的价值全在"点进去能看到那个数"；点不动的一条会被当成装饰。
 * 跳转意图回抛给编排层（它才知道导航在哪），本类不碰 PanelNav。
 */

import { Color, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3 } from 'cc'
import type { OfflineReportPopup } from '../game/session/AppRoot'
import { applySystemUiFont } from './UiFont'

const COLOR_MASK = new Color(12, 10, 9, 232)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_DIM = new Color(150, 140, 124, 255)
const COLOR_GOLD = new Color(184, 134, 11, 255)

const PANEL_WIDTH = 620
const ROW_HEIGHT = 46
/** 最多画几条。条目类别只有四类（资源/建筑/战斗/社交），四条之外不会再长出来 —— 纯逻辑那边数过。 */
const VISIBLE_ROWS = 4

export class OfflineReportOverlay {
  private readonly node: Node
  private readonly titleLabel: Label
  private readonly rowNodes: Node[] = []
  private readonly rowTextLabels: Label[] = []
  private readonly rowDetailLabels: Label[] = []
  private view: OfflineReportPopup | null = null

  /** 点某一条：编排层据此跳页面（并记一次埋点）。 */
  onJump: ((jump: string) => void) | null = null
  /** 点「知道了」：收起，并记住这一批已展示（同一批不再弹）。 */
  onDismiss: (() => void) | null = null

  constructor(parent: Node, width = PANEL_WIDTH) {
    const height = 120 + VISIBLE_ROWS * ROW_HEIGHT
    this.node = new Node('OfflineReport')
    this.node.layer = parent.layer
    parent.addChild(this.node)
    this.node.addComponent(UITransform).setContentSize(new Size(width, height))
    // 吞掉遮罩点击：不吞的话点空白处会穿到下面的面板上（等于在背后乱点）
    this.node.on('touch-start', (_event: EventTouch) => {
      /* 只吞不处理 */
    }, this)
    const background = this.node.addComponent(Graphics)
    background.fillColor = COLOR_MASK
    background.roundRect(-width / 2, -height / 2, width, height, 10)
    background.fill()

    this.titleLabel = this.addLabel(0, height / 2 - 30, 21, COLOR_GOLD)
    this.titleLabel.string = '自上次登录以来'

    for (let index = 0; index < VISIBLE_ROWS; index++) {
      const row = this.createRow(index, height)
      this.rowNodes.push(row.node)
      this.rowTextLabels.push(row.text)
      this.rowDetailLabels.push(row.detail)
    }

    this.createButton('离线汇总知道了', 0, -height / 2 + 30, () => this.onDismiss?.())
    this.node.active = false
  }

  /** 渲染一屏汇总。`items` 为空 ⇒ 整块收起（编排层不会在这时候叫它，双保险）。 */
  render(view: OfflineReportPopup): void {
    this.view = view
    if (view.items.length === 0) {
      this.node.active = false
      return
    }
    this.node.active = true
    this.rowNodes.forEach((row, index) => {
      const item = view.items[index]
      row.active = item !== undefined
      if (item === undefined) {
        return
      }
      this.rowTextLabels[index]!.string = item.text
      this.rowDetailLabels[index]!.string = item.detail ?? ''
      this.rowDetailLabels[index]!.color = item.detail === null ? COLOR_DIM : COLOR_DIM
    })
  }

  private createRow(index: number, height: number): { node: Node; text: Label; detail: Label } {
    const node = new Node(`offlineRow${index}`)
    this.node.addChild(node)
    const y = height / 2 - 66 - index * ROW_HEIGHT
    node.setPosition(new Vec3(0, y, 0))
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH - 48, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-(PANEL_WIDTH - 48) / 2, -ROW_HEIGHT / 2, PANEL_WIDTH - 48, ROW_HEIGHT, 6)
    graphics.fill()

    const text = this.childLabel(node, -(PANEL_WIDTH - 48) / 2 + 16, 9, 17, COLOR_TEXT)
    text.horizontalAlign = Label.HorizontalAlign.LEFT
    text.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    text.node.getComponent(UITransform)?.setContentSize(new Size(PANEL_WIDTH - 100, 22))
    text.overflow = Label.Overflow.SHRINK
    const detail = this.childLabel(node, -(PANEL_WIDTH - 48) / 2 + 16, -10, 14, COLOR_DIM)
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    detail.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    detail.node.getComponent(UITransform)?.setContentSize(new Size(PANEL_WIDTH - 100, 20))
    detail.overflow = Label.Overflow.SHRINK

    // 「查看 ›」是这一行的可点提示：没有它，玩家不会知道这一行能点
    const hint = this.childLabel(node, (PANEL_WIDTH - 48) / 2 - 26, 0, 15, COLOR_GOLD)
    hint.string = '查看 ›'
    node.on('touch-start', (_event: EventTouch) => {
      const item = this.view?.items[index]
      if (item !== undefined) {
        this.onJump?.(item.jump)
      }
    }, this)
    return { node, text, detail }
  }

  private createButton(name: string, x: number, y: number, onTap: () => void): Node {
    const node = new Node(name)
    this.node.addChild(node)
    node.setPosition(new Vec3(x, y, 0))
    node.addComponent(UITransform).setContentSize(new Size(180, 38))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_GOLD
    graphics.roundRect(-90, -19, 180, 38, 6)
    graphics.fill()
    const label = this.childLabel(node, 0, 0, 17, COLOR_MASK)
    label.string = '知道了'
    node.on('touch-start', (_event: EventTouch) => onTap(), this)
    return node
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
    label.verticalAlign = Label.VerticalAlign.CENTER
    return label
  }
}
