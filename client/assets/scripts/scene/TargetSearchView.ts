/**
 * 职责：目标搜索面板 —— 展示 POST /world/searchTargets 的结果（B08 §8、验收 12）。
 * 依赖：cc（渲染）、game/power/PowerPanel#buildTargetRows（行组装，已单测）、scene/NodePool。
 *
 * <p><b>本场景一行判定都不做</b>（铁律 2、B08 禁止项：不要在客户端做战力校验）。
 * 列表的<b>顺序、可选性、圈层范围</b>全部由服务端决定：buildTargetRows 明确「不排序、不过滤、
 * 不去重」，本场景 likewise 只是把行画出来。删掉这个文件，游戏逻辑不受任何影响。
 *
 * <p><b>护盾目标必须画出来，只是标灰</b>：服务端已经把护盾目标排除在候选池之外，
 * 但如果某条已下发的目标在玩家看列表期间套上了盾，客户端要做的是「标出来」而不是「删掉」——
 * 删掉就是客户端在做过滤，而过滤口径只能有一份（在服务端）。
 *
 * <p><b>响应里没有距离数值字段</b>（验收 12）：只有 distanceBand。所以面板上永远画不出
 * 「3.2 小时可达」这种数字，能画的只有「近 / 中 / 远」。这不是本场景的克制，是协议就没给。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：.scene / .prefab 资产、承载长列表的 ScrollView、
 * 半径滑块、正式美术。列表 item 已按 B07 §4 池化。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { buildTargetRows, formatPower } from '../game/power/PowerPanel'
import { clampPage, contentPerPage, pageCount, pageNotice, pageWindow } from '../game/ui/PanelPaging'
import { truncatedNotice } from '../game/ui/TruncatedList'
import type { TargetRow } from '../game/power/PowerPanel'
import type { SearchTargetsResp } from '../net/generated/WorldProtocol'
import { NodePool } from './NodePool'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性。美术方向常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(20, 17, 15, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_SHIELDED = new Color(36, 33, 31, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
/** 战力倍率高于 1 表示对方比我强，用暗红提醒；低于 1 用铜金。颜色只是提示，判定仍在服务端 */
const COLOR_RATIO_HARD = new Color(200, 96, 64, 255)
const COLOR_TYRANNY = new Color(214, 90, 90, 255)

const PANEL_WIDTH = 660
const ROW_HEIGHT = 52
const ROW_GAP = 6
const HEADER_HEIGHT = 96
const PADDING = 16
/** 一屏最多画几行。这是池的预热量与容量的上界，真实容量由 {@link rowCapacity} 按可视高现算 */
const MAX_VISIBLE_ROWS = 8
/** 控件条（半径 −/+、搜索、翻页）的高度。行区要从它的下沿之外开始，否则第一行盖住按钮 */
const CONTROL_HEIGHT = 40
/**
 * 控件条中心到顶边的距离。**只在这里算一次**：原先按钮用
 * `height/2 - PADDING - HEADER_HEIGHT + 8`、行区用 `height/2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT/2`，
 * 两条独立算式在 960×540 下实测重叠 12px（截图里第一行把三颗按钮的下半截吃掉了）。
 */
const CONTROL_CENTER_FROM_TOP = PADDING + HEADER_HEIGHT - 8
/** 第一行中心到顶边的距离 = 控件条下沿 + 一行间距 + 半行高 */
const FIRST_ROW_FROM_TOP = CONTROL_CENTER_FROM_TOP + CONTROL_HEIGHT / 2 + ROW_GAP + ROW_HEIGHT / 2
/** 行区下界 = 底部导航条上沿再留 8px（与 `ArmyPanelView`、`SocialPanelView` 同一个口径） */
const NAV_BAR_HEIGHT = 52
const NAV_BAR_BOTTOM = 8
const NAV_SAFE_GAP = 8

@ccclass('TargetSearchView')
export class TargetSearchView extends Component {
  private rows: readonly TargetRow[] = []
  private response: SearchTargetsResp | null = null
  private pending: SearchTargetsResp | null = null
  /**
   * 当前搜索半径（格）。只用于回传给服务端，本场景不拿它算任何东西。
   *
   * <p>半径的取值范围必须由适配层通过 {@link TargetSearchView#setRadiusBounds} 注入：
   * 上界来源 global.SEARCH_MAX_RADIUS，下界是服务端 TargetSearchService 用的地板值 1
   * （服务端把请求夹成 max(1, min(radius, SEARCH_MAX_RADIUS))，越界只截断并记日志、不拒绝）。
   * 没注入时 min=max=0，加减按钮就是一个静默的 no-op —— 这是刻意的：
   * 铁律 1 不允许在场景里写死搜索半径，宁可按钮暂时不灵，
   * 也不要在表现层留一个和配置表对不上的数字。
   */
  private radius = 0
  private radiusMin = 0
  private radiusMax = 0
  private radiusStep = 1

  private rowPool: NodePool | null = null
  private readonly drawnRows: Node[] = []
  /** 当前页（0 起）。新搜索结果到达时归零，翻页只改它。 */
  private page = 0
  private prevPageLabel: Label | null = null
  private nextPageLabel: Label | null = null
  private headerLabel: Label | null = null
  private bandLabel: Label | null = null
  private overflowLabel: Label | null = null

  /**
   * 玩家点某一行的回调。由外层（网络适配层）接线：拿到 targetId 后去派兵。
   *
   * <p>刻意用回调而不是在场景里直接发请求：场景不碰传输层，
   * 换传输实现（微信 / 浏览器 / 编辑器预览）不用动表现层。
   */
  onTargetSelected: ((targetId: string) => void) | null = null
  /** 玩家改了搜索半径后要求重新搜索。同样只是表达意图，不自己发请求。 */
  onSearchRequested: ((radius: number) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.rowPool = new NodePool(this.node, () => this.createRow(), MAX_VISIBLE_ROWS)
    this.buildHeader(size.height)
    if (this.pending !== null) {
      const pending = this.pending
      this.pending = null
      this.attach(pending)
    }
  }

  override onDestroy(): void {
    // 池必须显式销毁：列表 item 是运行时创建的，面板关掉后不清就会一直挂在内存里
    this.rowPool?.destroy()
    this.rowPool = null
    this.drawnRows.length = 0
    this.onTargetSelected = null
    this.onSearchRequested = null
  }

  /** 装载一次搜索结果。行顺序照搬服务端，本场景不重排。 */
  attach(resp: SearchTargetsResp): void {
    if (this.rowPool === null) {
      this.pending = resp
      return
    }
    this.response = resp
    this.rows = buildTargetRows(resp)
    // 新一次搜索回到第一页：停在第 3 页等一份只有 1 个目标的结果，表现是"搜索没结果"
    this.page = 0
    this.render()
  }

  /** 设置搜索半径的初始值与步进范围。三个数都来自 global.json，由调用方注入。 */
  setRadiusBounds(radius: number, min: number, max: number): void {
    this.radius = clamp(radius, min, max)
    this.radiusMin = min
    this.radiusMax = max
    this.radiusStep = Math.max(1, Math.ceil((max - min) / 10))
  }

  // ---------- 搭建 ----------

  private buildBackground(width: number, height: number): void {
    const node = new Node('Background')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(width, height))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
  }

  private buildHeader(height: number): void {
    this.headerLabel = this.addLabel(this.node, 'Header', 0, height / 2 - PADDING - 24, COLOR_COPPER_GOLD, 22)
    this.bandLabel = this.addLabel(this.node, 'Band', 0, height / 2 - PADDING - 56, COLOR_TEXT_DIM, 16)

    /**
     * 五颗按钮一条线排开：翻页在最外两侧，半径与搜索居中三颗。
     * 面板半宽 330，留 8 边距后可用 ±322；总宽 476 + 四个 42 的间隙正好铺满，
     * 再宽就出框 —— 所以这里不是随手挑的数。
     */
    const controls: Array<{ name: string; text: string; dx: number; w: number; size: number; onTap: () => void }> = [
      { name: 'PrevPageButton', text: '上一页', dx: -278, w: 88, size: 16, onTap: () => this.changePage(-1) },
      { name: 'RadiusDown', text: '半径 −', dx: -142, w: 100, size: 18, onTap: () => this.changeRadius(-1) },
      { name: 'RadiusUp', text: '半径 +', dx: 0, w: 100, size: 18, onTap: () => this.changeRadius(1) },
      { name: 'SearchButton', text: '搜索', dx: 142, w: 100, size: 18, onTap: () => this.requestSearch() },
      { name: 'NextPageButton', text: '下一页', dx: 278, w: 88, size: 16, onTap: () => this.changePage(1) },
    ]
    for (const control of controls) {
      const node = new Node(control.name)
      node.layer = this.node.layer
      this.node.addChild(node)
      node.setPosition(new Vec3(control.dx, height / 2 - CONTROL_CENTER_FROM_TOP, 0))
      node.addComponent(UITransform).setContentSize(new Size(control.w, CONTROL_HEIGHT))
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = COLOR_PANEL
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 2
      graphics.roundRect(-control.w / 2, -CONTROL_HEIGHT / 2, control.w, CONTROL_HEIGHT, 6)
      graphics.fill()
      graphics.stroke()
      node.on('touch-start', (_event: EventTouch) => control.onTap(), this)
      const caption = this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT, control.size)
      caption.string = control.text
      if (control.name === 'PrevPageButton') {
        this.prevPageLabel = caption
      }
      if (control.name === 'NextPageButton') {
        this.nextPageLabel = caption
      }
    }

    // 位置每次 render 现算（它要贴着本页最后一行的下沿，页码变了它就变了）
    this.overflowLabel = this.addLabel(this.node, 'Overflow', 0, 0, COLOR_TEXT_DIM, 14)
  }

  private createRow(): Node {
    const node = new Node('TargetRow')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
    graphics.fill()

    // 四段文本各自一个子节点：整行塞进一个 Label 就没法给倍率与暴虐标签单独上色
    const columns: Array<{ name: string; x: number; align: number; size: number }> = [
      { name: 'Name', x: -PANEL_WIDTH / 2 + PADDING, align: Label.HorizontalAlign.LEFT, size: 18 },
      { name: 'Ratio', x: -60, align: Label.HorizontalAlign.CENTER, size: 18 },
      { name: 'Meta', x: 110, align: Label.HorizontalAlign.CENTER, size: 15 },
      { name: 'Coord', x: PANEL_WIDTH / 2 - PADDING, align: Label.HorizontalAlign.RIGHT, size: 15 },
    ]
    for (const column of columns) {
      const label = this.addLabel(node, column.name, column.x, 0, COLOR_TEXT, column.size)
      label.horizontalAlign = column.align
    }
    return node
  }

  private addLabel(parent: Node, name: string, x: number, y: number, color: Color, fontSize: number): Label {
    const node = new Node(name)
    node.layer = parent.layer
    parent.addChild(node)
    node.addComponent(UITransform)
    node.setPosition(new Vec3(x, y, 0))
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = ''
    label.color = color
    label.fontSize = fontSize
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    return label
  }

  // ---------- 渲染 ----------

  private render(): void {
    const resp = this.response
    if (resp === null) {
      return
    }
    if (this.headerLabel !== null) {
      this.headerLabel.string = `可攻击目标 ${this.rows.length} 个 · 我的匹配战力 ${formatPower(resp.selfMatchPower)}`
    }
    if (this.bandLabel !== null) {
      // 圈层区间照服务端给的原样显示。玩家看到「为什么只有这些」时，这两个数就是答案
      this.bandLabel.string = `圈层范围 ${formatPower(resp.bandLower)} ~ ${formatPower(resp.bandUpper)}`
        + ` · 搜索半径 ${this.radius} 格`
    }

    const size = view.getVisibleSize()
    const pool = this.rowPool
    if (pool === null) {
      return
    }

    // 先把上一帧的行全部归还，再按新数据取：列表长度每次都可能变，
    // 逐个 diff 的收益抵不过它的复杂度，而行数被可视高封顶
    pool.releaseAll(this.drawnRows)
    this.drawnRows.length = 0

    const total = this.rows.length
    const capacity = this.rowCapacity()
    const perPage = contentPerPage(total, capacity)
    const pages = pageCount(total, perPage)
    this.page = clampPage(this.page, total, perPage)
    const slot = pageWindow(total, this.page, perPage)
    const visible = this.rows.slice(slot.start, slot.end)

    const topY = size.height / 2 - FIRST_ROW_FROM_TOP
    visible.forEach((row, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnRows.push(node)
      this.renderRow(node, row)
    })

    if (this.overflowLabel !== null) {
      // 贴着本页最后一行的下沿，而不是按"最多八行"算：写死八行时这行字在 960×540
      // 下落在 y=-318，屏幕下边界是 -270，玩家从来没见过它
      this.overflowLabel.node.setPosition(
        new Vec3(0, topY - visible.length * (ROW_HEIGHT + ROW_GAP), 0),
      )
      this.overflowLabel.string = pages > 1
        ? `${pageNotice(this.page, pages)} · 共 ${total} 个`
        : truncatedNotice('个目标', total - visible.length)
    }
    this.paintPageButtons(pages)
  }

  /** 不可翻的那一侧按灰，别让玩家点了没反应。 */
  private paintPageButtons(pages: number): void {
    if (this.prevPageLabel !== null) {
      const usable = pages > 1 && this.page > 0
      this.prevPageLabel.color = usable ? COLOR_TEXT : COLOR_TEXT_DIM
    }
    if (this.nextPageLabel !== null) {
      const usable = pages > 1 && this.page < pages - 1
      this.nextPageLabel.color = usable ? COLOR_TEXT : COLOR_TEXT_DIM
    }
  }

  /**
   * 本页画得下几行。**按可视高现算，不写死**：写死 8 行时第 6 行起就压到底部导航条上
   * （本仓库在军队页、编队弹层、社交面板各踩过一次，同一条教训）。
   */
  private rowCapacity(): number {
    const size = view.getVisibleSize()
    const firstRowBottom = size.height / 2 - FIRST_ROW_FROM_TOP - ROW_HEIGHT / 2
    const bottom = -size.height / 2 + NAV_BAR_BOTTOM + NAV_BAR_HEIGHT + NAV_SAFE_GAP
    const room = firstRowBottom - bottom
    return Math.max(1, Math.min(MAX_VISIBLE_ROWS, Math.floor(room / (ROW_HEIGHT + ROW_GAP)) + 1))
  }

  private renderRow(node: Node, row: TargetRow): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      // 护盾行标灰而不是删掉：删掉就是客户端在做过滤，而过滤口径只能在服务端
      graphics.fillColor = row.shielded ? COLOR_ROW_SHIELDED : COLOR_ROW
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
      graphics.fill()
    }
    const name = node.children[0]?.getComponent(Label)
    const ratio = node.children[1]?.getComponent(Label)
    const meta = node.children[2]?.getComponent(Label)
    const coord = node.children[3]?.getComponent(Label)

    if (name !== undefined && name !== null) {
      name.string = row.shielded ? `${row.name}（护盾）` : row.name
      name.color = row.shielded ? COLOR_TEXT_DIM : COLOR_TEXT
    }
    if (ratio !== undefined && ratio !== null) {
      ratio.string = row.ratioText
      ratio.color = ratioColor(row.ratioText, row.shielded)
    }
    if (meta !== undefined && meta !== null) {
      // 暴虐标签单独拼进去：高于平民档意味着打他有围剿加成，这是「给弱者制造反击靶子」的入口
      const parts = [row.distanceText, row.resourceText]
      if (row.tyrannyText !== null) {
        parts.push(row.tyrannyText)
      }
      meta.string = parts.join(' · ')
      meta.color = row.tyrannyText === null ? COLOR_TEXT_DIM : COLOR_TYRANNY
    }
    if (coord !== undefined && coord !== null) {
      coord.string = row.coordText
      coord.color = COLOR_TEXT_DIM
    }

    // 整行可点。点击只是把 targetId 交出去，能不能打由服务端裁定
    node.off('touch-start')
    node.on('touch-start', (_event: EventTouch) => {
      this.onTargetSelected?.(row.id)
    }, this)
  }

  // ---------- 半径控制 ----------

  private changeRadius(direction: number): void {
    this.radius = clamp(this.radius + direction * this.radiusStep, this.radiusMin, this.radiusMax)
    // 半径变了要重画表头（上面写着当前半径），但不自动发起搜索 ——
    // 玩家可能还在调，每调一格就请求一次会打爆服务端
    if (this.response !== null) {
      this.render()
    }
  }

  private requestSearch(): void {
    this.onSearchRequested?.(this.radius)
  }

  private changePage(direction: number): void {
    if (this.response === null) {
      return
    }
    const capacity = this.rowCapacity()
    this.page = clampPage(this.page + direction, this.rows.length, contentPerPage(this.rows.length, capacity))
    this.render()
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/**
 * 倍率文本 → 颜色。
 *
 * <p>只认文本开头的「≥1.0」这种形态，不做数值解析：ratioText 是 formatRatio 的产物，
 * 形如 "×1.24"。这里判断的是「第一个数字字符是不是 0」—— 0.x 表示对方比我弱。
 * 刻意不 parseFloat 出数字来比大小：那等于在客户端重算一遍强弱判定，
 * 而判定口径只能在服务端（B08 禁止项）。颜色只是视觉提示，不影响任何行为。
 */
function ratioColor(ratioText: string, shielded: boolean): Color {
  if (shielded) {
    return COLOR_TEXT_DIM
  }
  const firstDigit = ratioText.replace(/[^\d]/g, '').charAt(0)
  return firstDigit === '0' ? COLOR_COPPER_GOLD : COLOR_RATIO_HARD
}
