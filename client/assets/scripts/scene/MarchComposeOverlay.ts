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

import { Color, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import type { MarchComposeView } from '../game/session/AppRoot'
import type { RallyField, RallyNumberRow, RallyScope, RallyScopeRow } from '../game/world/MarchCompose'
import { applySystemUiFont } from './UiFont'

const COLOR_MASK = new Color(12, 10, 9, 232)
/** 整屏遮罩色，与 `AwakenPickOverlay` / `ComposePickOverlay` 同一份参数 */
const COLOR_SCRIM = new Color(0, 0, 0, 170)
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
  private readonly toggleLabel!: Label
  /** 集结态下顶替第 5 行的那一条：层级两颗键 + 联盟那两个数的加减（B26 S14） */
  private readonly bandNode: Node
  private readonly bandChips: {
    scope: RallyScope; node: Node; label: Label; paint: (color: Color) => void
  }[] = []
  private readonly bandGroups: { caption: Label; value: Label; minus: Node; plus: Node }[] = []
  /** 两个数组当前各自管的是哪一行数字（渲染时按视图给的顺序贴上去） */
  private bandFields: RallyField[] = []
  private view: MarchComposeView | null = null

  /** 勾选意图（unitId 与它要变成的数量）；由编排层夹取后再回来重画。 */
  onPick: ((unitId: string, count: number) => void) | null = null
  onConfirm: (() => void) | null = null
  /** 在出征与发起集结之间来回切（B26 S12）。**换种类不是下命令**，所以它不打埋点 */
  onToggleMode: (() => void) | null = null
  /** 换召集范围（B26 S14）。与切种类同一条理由：按下这一下还没下命令，不打埋点 */
  onPickScope: ((scope: RallyScope) => void) | null = null
  /** 人数上限 / 等待时长加减一档（direction = ±1）。同样不发请求 */
  onAdjustNumber: ((field: RallyField, direction: number) => void) | null = null
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
    // 整屏遮罩，与 AwakenPick / ComposePick / Choice 三处同一惯例（COLOR_SCRIM 同色同参）。
    // 缺了它，弹层打开时底下的搜索面板照常亮着：标题「出征：某城」会和「半径 – 搜索」那行
    // 抢同一块像素，两层字叠在一起读不了（12:40 的 `compose-mode-*.png` 两张都拍到了）。
    // 只画不改命中区域：遮罩上点击该不该关闭弹层是 UX 口径，另记一格，不在这里顺手定。
    const screen = view.getVisibleSize()
    background.fillColor = COLOR_SCRIM
    background.rect(-screen.width / 2, -screen.height / 2, screen.width, screen.height)
    background.fill()
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

    // 集结那一条挤在最下面一行的位置上：编成面板的总高度与底部三颗键的坐标都不动，
    // 代价是集结态只能一眼看完 4 种兵（画不下的那一行由 totalText 边上那句话说明）。
    this.bandNode = new Node('rallyBand')
    this.node.addChild(this.bandNode)
    const bandY = PANEL_HEIGHT / 2 - 92 - (VISIBLE_ROWS - 1) * (ROW_HEIGHT + 4)
    this.bandNode.setPosition(new Vec3(0, bandY, 0))
    this.bandNode.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH - 48, ROW_HEIGHT))
    const bandBg = this.bandNode.addComponent(Graphics)
    bandBg.fillColor = COLOR_ROW
    bandBg.roundRect(-(PANEL_WIDTH - 48) / 2, -ROW_HEIGHT, PANEL_WIDTH - 48, ROW_HEIGHT, 6)
    bandBg.fill()
    const scopes: readonly RallyScope[] = ['SQUAD', 'ALLIANCE']
    for (let index = 0; index < scopes.length; index++) {
      const scope = scopes[index] as RallyScope
      const chip = this.bandButton(`层级-${scope}`, '', -252 + index * 66, -ROW_HEIGHT / 2, 62,
        () => this.onPickScope?.(scope))
      this.bandChips.push({ scope, ...chip })
    }
    // 版式按"整盒不重叠"排（截图抓到的第一版把 Cocos 默认的 label 字样留在了 −/＋ 上，
    // 而那颗字正好压在数字头上）：每组 表头 → − → 数 → ＋ 各占自己的格子。
    const groups = [
      { caption: -110, minus: -60, value: -8, plus: 44 },
      { caption: 100, minus: 150, value: 202, plus: 252 },
    ]
    for (let index = 0; index < groups.length; index++) {
      const box = groups[index] as { caption: number; minus: number; value: number; plus: number }
      const caption = this.childLabel(this.bandNode, box.caption, -ROW_HEIGHT / 2, 16, COLOR_DIM)
      const value = this.childLabel(this.bandNode, box.value, -ROW_HEIGHT / 2, 16, COLOR_GOLD)
      // 名字给死：量具按下标读会被后加的一行错位（同一族缺陷在 #291 抓到过一次）
      caption.node.name = `数-${index}-表头`
      value.node.name = `数-${index}-数`
      const minus = this.bandButton(`数-${index}-减`, '−', box.minus, -ROW_HEIGHT / 2, 38,
        () => this.tapNumber(index, -1))
      const plus = this.bandButton(`数-${index}-加`, '＋', box.plus, -ROW_HEIGHT / 2, 38,
        () => this.tapNumber(index, 1))
      this.bandGroups.push({ caption, value, minus: minus.node, plus: plus.node })
    }
    this.bandNode.active = false

    this.createButton('编成取消', '取消', -140, -PANEL_HEIGHT / 2 + 28, COLOR_ROW, COLOR_TEXT,
      () => this.onCancel?.())
    const confirm = this.createButton('编成出征', '出征', 140, -PANEL_HEIGHT / 2 + 28,
      COLOR_GOLD, COLOR_MASK, () => this.onConfirm?.())
    this.confirmNode = confirm.node
    this.confirmLabel = confirm.label
    // 中间那颗切种类：同一份兵、同一个目标，只是命令种类不同
    const toggle = this.createButton('编成种类', '改成集结', 0, -PANEL_HEIGHT / 2 + 28,
      COLOR_ROW, COLOR_TEXT, () => this.onToggleMode?.())
    this.toggleLabel = toggle.label

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
    const scopes = view.rallyScopes ?? []
    const numbers = view.rallyNumbers ?? []
    // 那一条只顶替最下面一行：兵力行少一眼能看完一种兵，
    // 但召集范围与那两个数没得选就发不出去 —— 两害相权取能发出去的那一个
    const visibleRows = scopes.length === 0 ? VISIBLE_ROWS : VISIBLE_ROWS - 1
    this.titleLabel.string = `${view.mode === 'RALLY' ? '集结' : '出征'}：${view.targetName}`
    this.coordLabel.string = `坐标 ${view.coordText}`
    const hidden = Math.max(0, view.compose.options.length - visibleRows)
    this.totalLabel.string = `共派 ${view.compose.totalText} 兵`
      + (hidden === 0 ? '' : ` · 另有 ${hidden} 种兵这一屏画不下`)
    this.noticeLabel.string = view.notice ?? ''
    this.noticeLabel.color = view.notice === null ? COLOR_DIM : COLOR_WARN

    const options = view.compose.options.slice(0, visibleRows)
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
    this.renderBand(scopes, numbers, view)

    // 提交态：确认键灰掉且不吃触摸（双击发两份是最容易被投诉的"自动"类缺陷）
    this.confirmLabel.string = view.submitting
      ? (view.mode === 'RALLY' ? '发起中…' : '出征中…')
      : (view.submitLabel ?? '出征')
    // 切种类那颗**永远可点**：被挡住时点它是要看那句原因的，
    // 置灰反而把原因一起藏了（玩家只会以为按钮坏了）
    this.toggleLabel.string = view.mode === 'RALLY' ? '改回出征' : '改成集结'
    this.confirmLabel.color = view.submitting ? COLOR_DIM : COLOR_MASK
    this.confirmNode.active = !view.submitting
  }


  /**
   * 画集结那一条：两颗层级键 + （只在联盟层）两个可调的数。
   * 出征态或「为加入集结编队」时整条收起 —— 加入别人的集结没有可设的范围。
   */
  private renderBand(scopes: readonly RallyScopeRow[], numbers: readonly RallyNumberRow[],
                     view: MarchComposeView): void {
    this.bandNode.active = scopes.length > 0
    if (scopes.length === 0) {
      return
    }
    for (const chip of this.bandChips) {
      const row = scopes.find(scope => scope.scope === chip.scope)
      chip.node.active = row !== undefined
      if (row === undefined) {
        continue
      }
      const selected = view.rallyScope === row.scope
      chip.label.string = row.label
      chip.label.color = selected ? COLOR_MASK : COLOR_TEXT
      chip.paint(selected ? COLOR_GOLD : COLOR_ROW)
    }
    this.bandFields = numbers.map(row => row.field)
    for (let index = 0; index < this.bandGroups.length; index++) {
      const group = this.bandGroups[index]!
      const row = numbers[index]
      const shown = row !== undefined
      group.caption.node.active = shown
      group.value.node.active = shown
      group.minus.active = shown && row.value > row.min
      group.plus.active = shown && row.value < row.max
      if (row !== undefined) {
        group.caption.string = row.caption
        group.value.string = row.text
      }
    }
  }

  /** 加减一档：把这一格当前管的是哪个字段交给编排层，表现层不认字段名以外的规则。 */
  private tapNumber(index: number, direction: number): void {
    const field = this.bandFields[index]
    if (field !== undefined) {
      this.onAdjustNumber?.(field, direction)
    }
  }

  /** 那一条上的小键：比页脚的键窄，选中态要能重画，所以把画笔一起交回去。 */
  private bandButton(name: string, text: string, x: number, y: number, width: number,
                     onTap: () => void): { node: Node; label: Label; paint: (color: Color) => void } {
    const node = new Node(name)
    this.bandNode.addChild(node)
    node.setPosition(new Vec3(x, y, 0))
    node.addComponent(UITransform).setContentSize(new Size(width, 30))
    const graphics = node.addComponent(Graphics)
    const label = this.childLabel(node, 0, 0, 16, COLOR_TEXT)
    // 必须显式给字：Label 组件的默认串是引擎写的 "label"，不给就会印在玩家屏幕上
    label.string = text
    const paint = (color: Color): void => {
      graphics.clear()
      graphics.fillColor = color
      graphics.roundRect(-width / 2, -15, width, 30, 5)
      graphics.fill()
    }
    paint(COLOR_PANEL)
    node.on('touch-start', (_event: EventTouch) => onTap(), this)
    return { node, label, paint }
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
