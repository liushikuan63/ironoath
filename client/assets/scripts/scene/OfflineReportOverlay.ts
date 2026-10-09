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
import { applySlicedSprite } from './ArtCatalog'
import { PANEL_IRON_INSET } from '../game/art/ArtFamilies'
import type { OfflineReportPopup } from '../game/session/AppRoot'
import { applySystemUiFont } from './UiFont'
import { DIALOG_SCRIM, applyDialogButton, fitExistingDialog } from './DialogStyle'

const COLOR_MASK = DIALOG_SCRIM
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_DIM = new Color(150, 140, 124, 255)
const COLOR_GOLD = new Color(184, 134, 11, 255)
/**
 * 羊皮纸内衬上的**墨色**，不是可选审美：`panel-parchment-v1` 中心净区实测均色 `(142,124,93)`
 * （相对亮度 L=0.210），拿 WCAG 对比度逐个算过 —— 金字 1.24:1、白字 3.14:1、面板深 3.92:1、
 * 暗红 3.08:1 全不过 4.5:1，只有铁墨 `(22,18,16)` 给 **4.60:1**。
 * ⇒ 规格 §一 第 2 条"纸面比正文暗一档、靠深字 tokens 保对比度"在这里落地成这一个常量；
 *   次级文字不再换色（换浅一点就掉出 4.5），只靠字号分层。
 */
const COLOR_INK = new Color(22, 18, 16, 255)

const PANEL_WIDTH = 620
const ROW_HEIGHT = 46
/** 最多画几条。条目类别只有四类（资源/建筑/战斗/社交），四条之外不会再长出来 —— 纯逻辑那边数过。 */
const VISIBLE_ROWS = 4
/**
 * 面板高 = 上下铜边（A 档 border 实测 72×2）+ 内容 276（标题 30 + 四条行 184 + 两处间距 20 + 按钮 42）。
 *
 * <p>为什么从 304 抬到 420：border 从 36 抬到 72 之后，304 高的净区只剩 160px，
 * 装不下"标题 + 4 行 46 + 按钮"这一套（会压进铜边）。规格 §七 Q6 预判的正是这件事，
 * 裁决给的退路是**抬高面板**而不是把 border 调回去（调回去等于重新让切分线穿过角帽）。
 */
const PANEL_HEIGHT = PANEL_IRON_INSET.top + PANEL_IRON_INSET.bottom + 276

export class OfflineReportOverlay {
  private readonly node: Node
  private readonly titleLabel: Label
  private readonly rowNodes: Node[] = []
  private readonly rowTextLabels: Label[] = []
  private readonly rowDetailLabels: Label[] = []
  private view: OfflineReportPopup | null = null
  private readonly layoutDialog: () => void

  /** 点某一条：编排层据此跳页面（并记一次埋点）。 */
  onJump: ((jump: string) => void) | null = null
  /** 点「知道了」：收起，并记住这一批已展示（同一批不再弹）。 */
  onDismiss: (() => void) | null = null

  constructor(parent: Node, width = PANEL_WIDTH) {
    const height = PANEL_HEIGHT
    this.node = new Node('OfflineReport')
    this.node.layer = parent.layer
    parent.addChild(this.node)
    this.node.addComponent(UITransform).setContentSize(new Size(width, height))
    // 吞掉遮罩点击：不吞的话点空白处会穿到下面的面板上（等于在背后乱点）
    this.node.on('touch-start', (_event: EventTouch) => {
      /* 只吞不处理 */
    }, this)
    const scrim = new Node('scrim')
    scrim.layer = parent.layer
    scrim.addComponent(UITransform).setContentSize(4000, 4000)
    scrim.on('touch-start', () => {}, this)
    this.node.addChild(scrim)
    const background = scrim.addComponent(Graphics)
    background.fillColor = COLOR_MASK
    background.rect(-2000, -2000, 4000, 4000)
    background.fill()

    // 底板必须与这层遮罩底色**分节点**：`applySlicedSprite` 会把它所挂节点上的 Graphics
    // clear + 停用（#806 拆过的雷 —— 共用一个 Graphics 时换贴图会把整块底色一起清空）。
    // 长文汇总走羊皮纸内衬（规格 §一 第 2 条），所以这一屏的文字全部换成 COLOR_INK。
    const plate = new Node('plate')
    plate.layer = this.node.layer
    plate.addComponent(UITransform).setContentSize(new Size(width, height))
    applySlicedSprite(plate, 'ui.panel.parchment', width, height)
    this.node.addChild(plate)

    // 内容一律从 inset 推，不写死：铜边占掉的上下各 72px 要显式还给文字。
    const contentTop = height / 2 - PANEL_IRON_INSET.top
    this.titleLabel = this.addLabel(0, contentTop - 18, 21, COLOR_INK)
    this.titleLabel.string = '自上次登录以来'

    for (let index = 0; index < VISIBLE_ROWS; index++) {
      const row = this.createRow(index, height)
      this.rowNodes.push(row.node)
      this.rowTextLabels.push(row.text)
      this.rowDetailLabels.push(row.detail)
    }

    // 按钮也留在净区内（原先 -height/2 + 30 落在下铜边里，1:1 截图上它就是压在角帽上）。
    const dismiss = this.createButton('离线汇总知道了', 0,
      -height / 2 + PANEL_IRON_INSET.bottom + 26, () => this.onDismiss?.())
    this.layoutDialog = fitExistingDialog(this.node, plate,
      [this.titleLabel.node, ...this.rowNodes], [dismiss], 'ui.panel.parchment', width, height)
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
    this.layoutDialog()
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

  /**
   * 收起这一屏（两条路径共用：点「知道了」与点条目跳页 —— #767 之前两条都断了，弹层会盖着屏）。
   *
   * <p><b>「同一批不再弹」不归这里管</b>：指纹在投递那一刻就被 `AppRoot.deliverOfflineReport` 记下
   * （弹了就记，与玩家点不点无关）。这里只管显示层的收起，别在这里再记一遍。
   */
  hide(): void {
    this.node.active = false
  }

  private createRow(index: number, height: number): { node: Node; text: Label; detail: Label } {
    const node = new Node(`offlineRow${index}`)
    this.node.addChild(node)
    // 行起点从净区推：contentTop(138) - 46 起，逐行下移一个行高 ⇒ 四行落在 92/46/0/-46，
    // 最后一行下沿 -69 与按钮上沿 -90 之间留 21px，谁都不压铜边。
    const y = height / 2 - PANEL_IRON_INSET.top - 46 - index * ROW_HEIGHT
    node.setPosition(new Vec3(0, y, 0))
    // 行宽必须从**净区**推，不能按面板宽减一个固定数：A 档 border 抬到 80 之后，
    // 原先的 PANEL_WIDTH - 48 = 572 比净区 460 宽出 112px ⇒ 条行铺进铜边、行末的「查看 ›」被切掉
    // （1:1 裁切截图抓到的，见规格 §4.9）。
    const rowW = PANEL_WIDTH - PANEL_IRON_INSET.left - PANEL_IRON_INSET.right
    node.addComponent(UITransform).setContentSize(new Size(rowW, ROW_HEIGHT))
    // B 档条行贴在羊皮纸上 = "军令状贴在文书上"。行内文字仍是浅色（浅字压深行 4.5:1 以上），
    // 深色墨只给直接落在纸面上的标题 —— 两套底色各用各的字色，不拿一个常量糊两层。
    const band = new Node('band')
    band.layer = node.layer
    band.addComponent(UITransform).setContentSize(new Size(rowW, ROW_HEIGHT))
    node.addChild(band)
    if (!applySlicedSprite(band, 'ui.plate.band', rowW, ROW_HEIGHT)) {
      const graphics = band.addComponent(Graphics)
      graphics.fillColor = COLOR_ROW
      graphics.roundRect(-rowW / 2, -ROW_HEIGHT / 2, rowW, ROW_HEIGHT, 6)
      graphics.fill()
    }

    const text = this.childLabel(node, -rowW / 2 + 16, 9, 17, COLOR_TEXT)
    text.horizontalAlign = Label.HorizontalAlign.LEFT
    text.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    // 标签盒宽必须跟着**行宽**走：原先写 PANEL_WIDTH - 100 = 520，而行宽已收到净区 460 ⇒
    // 盒子比它所在的条行还宽 60px，SHRINK 会按这个假宽度排版、长文案压到「查看 ›」上（同族第三处）。
    text.node.getComponent(UITransform)?.setContentSize(new Size(rowW - 100, 22))
    text.overflow = Label.Overflow.SHRINK
    const detail = this.childLabel(node, -rowW / 2 + 16, -10, 14, COLOR_DIM)
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    detail.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    detail.node.getComponent(UITransform)?.setContentSize(new Size(rowW - 100, 20))
    detail.overflow = Label.Overflow.SHRINK

    // 「查看 ›」是这一行的可点提示：没有它，玩家不会知道这一行能点
    const hint = this.childLabel(node, rowW / 2 - 26, 0, 15, COLOR_GOLD)
    hint.string = '查看 ›'
    node.on('touch-end', (_event: EventTouch) => {
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
    applyDialogButton(node, true, 180, 38)
    label.color = COLOR_TEXT
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
