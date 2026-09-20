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
import { kindTitle, kindToggleLabel } from '../game/social/RallyCompose'
import type { MarchComposeView } from '../game/session/AppRoot'
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
/**
 * 460 → 528：三态化之后编成那一行下面还要多一行「集结人数 / 准备时长」，
 * 而兵种那五行是核心信息不能为它让位（缩到四行就等于第五个兵种永远派不出去）。
 * 按"扩展空间而不是藏内容"处理，多出来的高度全部给底部那一叠。
 *
 * <p>底部四件（参数行 / 合计 / 提示 / 三颗键）的位置在这里逐个点名，而不是继续写
 * `-PANEL_HEIGHT/2 + 28` 那种"跟着面板底边走"的算式 —— 加高会连带把三颗键往下推，
 * 而它们下面就是导航条。探针的「弹层底部三颗键都没压到导航条」钉的是这件事。
 */
const PANEL_HEIGHT = 528
/** 底部四件各自的落点（面板内坐标，半面板高 264） */
const BUTTON_Y = -222
const NOTICE_Y = -186
const TOTAL_Y = -158
/** 集结参数那一行的中心：行高 44，所以它占 [-144,-100]，上面留 42 给兵种行的最后一格 */
const RALLY_ROW_Y = -122
/** 底部导航条吃掉的高度：下边距 8 + 条高 52（`PanelNav` 的条占 y ∈ [-h/2+8, -h/2+60]） */
const NAV_CLEARANCE = 8 + 52
/**
 * 面板要整块落在导航条之上。浏览器窗口是 1.6:1 时 `view.getVisibleSize()` 给的是
 * **960×600 而不是设计的 960×640**（按宽适配，高度被裁），540 高的面板居中就会
 * 有 2px 沉到导航条上（探针实测最低键 -242 vs 条上沿 -240）。
 * 所以必要时把整块面板往上抬，而不是把内容再挤扁 —— 上面还有的是空间。
 *
 * <p><b>必须在构造函数里现算</b>：模块顶层求值时画布还没适配完，那时读到的可视高
 * 与遮罩用的不是同一个数（实测抬起量算成 38，面板反而顶出可视区 8px）。
 */
function panelLift(visibleHeight: number): number {
  return Math.max(0, PANEL_HEIGHT / 2 - (visibleHeight / 2 - NAV_CLEARANCE))
}
const ROW_HEIGHT = 44
/** 行条宽（面板左右各内缩 24）。行内四件事的落点全部从它推，不各写一个魔数 */
const ROW_WIDTH = PANEL_WIDTH - 48
/**
 * 兵种名**左对齐**贴行条左内缩。原先它是中心对齐、却放在同一个左内缩点上
 * ⇒ 文字以那个点为中心向两边铺开，实测左边缘在世界 -411 而面板左边在 -310，
 * 101px 名字直接画到面板外面（截图上名字越过了圆角边框）。
 */
const ROW_NAME_X = -ROW_WIDTH / 2 + 14
/** 两颗步进键挪到行条右侧（原先在 -96/-50，正好压在长名字要占的那一段） */
const ROW_PLUS_X = ROW_WIDTH / 2 - 19 - 8
const ROW_MINUS_X = ROW_PLUS_X - 38 - 6
/** 数量右对齐，落在「−」的左边 */
const ROW_COUNT_X = ROW_MINUS_X - 19 - 12
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
  /** 集结参数那一行：人数与准备时长各一格 −/＋。界与默认值都由服务端下发，本类只显示。 */
  private readonly rallyRow: Node
  private rallyMembersLabel: Label | null = null
  private rallyPrepareLabel: Label | null = null
  private rallyMembersMinus: Node | null = null
  private rallyMembersPlus: Node | null = null
  private rallyPrepareMinus: Node | null = null
  private rallyPreparePlus: Node | null = null
  private view: MarchComposeView | null = null

  /** 勾选意图（unitId 与它要变成的数量）；由编排层夹取后再回来重画。 */
  onPick: ((unitId: string, count: number) => void) | null = null
  onConfirm: (() => void) | null = null
  /** 在出征 / 小队集结 / 联盟集结之间循环（B26 S12 两态 → S13b 三态）。**换种类不是下命令**，所以它不打埋点 */
  onToggleMode: (() => void) | null = null
  /** 调集结参数。**同理不是意图**：玩家还在试数，真正花代价的是确认键那一下 */
  onRallyAdjust: ((field: 'members' | 'prepare', direction: number) => void) | null = null
  onCancel: (() => void) | null = null

  constructor(parent: Node, width = PANEL_WIDTH) {
    this.node = new Node('MarchCompose')
    this.node.layer = parent.layer
    parent.addChild(this.node)
    const screen = view.getVisibleSize()
    const lift = panelLift(screen.height)
    // 命中区域 = **整屏**，不是面板那一块。原先按 620×PANEL_HEIGHT 设，遮罩画满了整屏而
    // 命中只有面板那么大 ⇒ 点暗处会穿透打到底下已经"看不见"的搜索行（整屏压暗却仍可点，
    // 是不一致）。口径已定：遮罩上点击**不关闭**弹层，所以这里只吞不处理。
    // 节点被抬起 lift，所以高度两侧各补 lift 才能仍然盖住整屏。
    this.node.addComponent(UITransform)
      .setContentSize(new Size(screen.width, screen.height + 2 * lift))
    this.node.on('touch-start', (_event: EventTouch) => {
      /* 只吞不处理：关闭走「编成取消」那颗键 */
    }, this)
    const background = this.node.addComponent(Graphics)
    // 整屏遮罩，与 AwakenPick / ComposePick / Choice 三处同一惯例（COLOR_SCRIM 同色同参）。
    // 缺了它，弹层打开时底下的搜索面板照常亮着：标题「出征：某城」会和「半径 – 搜索」那行
    // 抢同一块像素，两层字叠在一起读不了（12:40 的 `compose-mode-*.png` 两张都拍到了）。
    background.fillColor = COLOR_SCRIM
    // 遮罩画在**屏幕**上，而本节点已经按 lift 抬起来了 ⇒ 矩形要反向偏移，
    // 否则抬起多少，屏幕底下就漏掉多宽一条没遮住的搜索面板
    background.rect(-screen.width / 2, -screen.height / 2 - lift, screen.width, screen.height)
    background.fill()
    background.fillColor = COLOR_MASK
    background.roundRect(-width / 2, -PANEL_HEIGHT / 2, width, PANEL_HEIGHT, 10)
    background.fill()
    this.node.setPosition(new Vec3(0, lift, 0))

    this.titleLabel = this.addLabel(0, PANEL_HEIGHT / 2 - 28, 22, COLOR_GOLD)
    this.coordLabel = this.addLabel(0, PANEL_HEIGHT / 2 - 56, 15, COLOR_DIM)
    this.totalLabel = this.addLabel(0, TOTAL_Y, 17, COLOR_TEXT)
    this.noticeLabel = this.addLabel(0, NOTICE_Y, 15, COLOR_WARN)

    for (let index = 0; index < VISIBLE_ROWS; index++) {
      const row = this.createRow(index)
      this.rowNodes.push(row.node)
      this.rowNameLabels.push(row.name)
      this.rowCountLabels.push(row.count)
      this.rowMinusNodes.push(row.minus)
      this.rowPlusNodes.push(row.plus)
    }

    this.createButton('编成取消', '取消', -140, BUTTON_Y, COLOR_ROW, COLOR_TEXT,
      () => this.onCancel?.())
    const confirm = this.createButton('编成出征', '出征', 140, BUTTON_Y,
      COLOR_GOLD, COLOR_MASK, () => this.onConfirm?.())
    this.confirmNode = confirm.node
    this.confirmLabel = confirm.label
    // 中间那颗切种类：同一份兵、同一个目标，只是命令种类不同
    const toggle = this.createButton('编成种类', '改成集结', 0, BUTTON_Y,
      COLOR_ROW, COLOR_TEXT, () => this.onToggleMode?.())
    this.toggleLabel = toggle.label

    this.rallyRow = this.buildRallyRow()

    this.node.active = false
  }

  /**
   * 集结参数那一行（B26 S13b）。出征时整条不画 —— 出征没有"等人"这个维度，
   * 留两格灰着的加减号会让人以为出征也能设人数。
   */
  private buildRallyRow(): Node {
    const row = new Node('编成集结参数')
    this.node.addChild(row)
    row.setPosition(new Vec3(0, RALLY_ROW_Y, 0))
    /**
     * 两组各占半行：`−` 在最外、`＋` 在内侧，标签左对齐夹在中间。
     * 原先标签是中心对齐放在两颗键中间（-152 / +152），而它自己就有约 150px 宽
     * ⇒ 左右各压上一颗键。探针的"行内两两不相交"现在会一起管这一行。
     */
    const members = this.stepper(row, '集结人数', -262, -291, -19,
      () => this.onRallyAdjust?.('members', -1), () => this.onRallyAdjust?.('members', 1))
    const prepare = this.stepper(row, '准备时长', 48, 19, 291,
      () => this.onRallyAdjust?.('prepare', -1), () => this.onRallyAdjust?.('prepare', 1))
    this.rallyMembersLabel = members.label
    this.rallyMembersMinus = members.minus
    this.rallyMembersPlus = members.plus
    this.rallyPrepareLabel = prepare.label
    this.rallyPrepareMinus = prepare.minus
    this.rallyPreparePlus = prepare.plus
    return row
  }

  private stepper(parent: Node, name: string, labelX: number, minusX: number, plusX: number,
    onDown: () => void, onUp: () => void): { label: Label; minus: Node; plus: Node } {
    const label = this.childLabel(parent, labelX, 0, 17, COLOR_TEXT, 0)
    const minus = this.rowButton(parent, minusX, 0, '−', onDown, `${name}减`)
    const plus = this.rowButton(parent, plusX, 0, '＋', onUp, `${name}加`)
    return { label, minus, plus }
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
    this.titleLabel.string = `${kindTitle(view.kind ?? 'MARCH')}：${view.targetName}`
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
    const rallyMode = (view.kind ?? 'MARCH') !== 'MARCH'
    this.confirmLabel.string = view.submitting
      ? (rallyMode ? '发起中…' : '出征中…')
      : (view.submitLabel ?? '出征')
    // 切种类那颗**永远可点**：被挡住时点它是要看那句原因的，
    // 置灰反而把原因一起藏了（玩家只会以为按钮坏了）
    this.toggleLabel.string = kindToggleLabel(view.kind ?? 'MARCH')
    this.confirmLabel.color = view.submitting ? COLOR_DIM : COLOR_MASK
    this.confirmNode.active = !view.submitting
    this.paintRallyRow(view)
  }

  /** 集结参数那一行：出征或读口没到时整条收起，其余情况只显示服务端给的那两个数。 */
  private paintRallyRow(view: MarchComposeView): void {
    const rally = view.rally ?? null
    this.rallyRow.active = rally !== null
    if (rally === null) {
      return
    }
    this.rallyMembersLabel!.string = `集结人数 ${rally.members.value} 人`
    this.rallyPrepareLabel!.string = `准备 ${rally.prepare.value} 分钟`
    this.rallyMembersMinus!.active = rally.members.value > rally.members.min
    this.rallyMembersPlus!.active = rally.members.value < rally.members.max
    this.rallyPrepareMinus!.active = rally.prepare.value > rally.prepare.min
    this.rallyPreparePlus!.active = rally.prepare.value < rally.prepare.max
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

    const name = this.childLabel(node, ROW_NAME_X, -ROW_HEIGHT / 2, 17, COLOR_TEXT, 0)
    const count = this.childLabel(node, ROW_COUNT_X, -ROW_HEIGHT / 2, 17, COLOR_GOLD, 1)
    const minus = this.rowButton(node, ROW_MINUS_X, -ROW_HEIGHT / 2, '−', () => {
      const option = this.view?.compose.options[index]
      if (option !== undefined) {
        this.onPick?.(option.unitId, option.selected - STEP)
      }
    })
    const plus = this.rowButton(node, ROW_PLUS_X, -ROW_HEIGHT / 2, '＋', () => {
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

  private childLabel(parent: Node, x: number, y: number, fontSize: number, color: Color,
    anchorX = 0.5): Label {
    const node = new Node('label')
    parent.addChild(node)
    // 锚点必须一起改：只挪 x 不设 anchorX，文字盒仍会以节点为中心再推出去半个宽度
    node.addComponent(UITransform).setAnchorPoint(anchorX, 0.5)
    node.setPosition(new Vec3(x, y, 0))
    const label = applySystemUiFont(node.addComponent(Label))
    label.color = color
    label.fontSize = fontSize
    label.lineHeight = fontSize + 6
    label.horizontalAlign = anchorX === 0 ? Label.HorizontalAlign.LEFT
      : anchorX === 1 ? Label.HorizontalAlign.RIGHT : Label.HorizontalAlign.CENTER
    return label
  }

  private rowButton(parent: Node, x: number, y: number, text: string, onTap: () => void,
    name = `row-${text}`): Node {
    const node = new Node(name)
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
