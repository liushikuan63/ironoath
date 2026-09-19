/**
 * 职责：招募（抽卡）面板 —— 卡池页签、单抽/十抽、余额与限购、上一次抽到了什么（B06 §2）。
 * 依赖：cc（渲染）、game/gacha/GachaPanel（数据组装，已单测）。
 *
 * <p>铁律 2：本文件只读传入的视图，点击只喊一声。够不够抽、还剩几次、抽十次花多少，
 * 全在 `game/gacha/GachaPanel.ts` 里判，而那里的数全来自 `/gacha/pools` ——
 * 客户端没有 gacha 表，写死一个 150 就会在表改价之后对玩家说谎。
 *
 * <p>抽满的那一行**照样画出来**（灰字 + 「已抽满」），而不是藏掉：藏掉的语义是"没有这个池"。
 *
 * <p>布局按**可视高度**现算：导航条占掉底部一截，写死行数会把最后一行挤到屏外
 * （#262 科技页、#273 觉醒弹层都是截图才发现的）。
 */
import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import type { GachaPanelView, GachaPoolRow } from '../game/gacha/GachaPanel'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_ROW = new Color(40, 33, 28, 255)
const COLOR_ROW_SELECTED = new Color(62, 44, 26, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_HINT = new Color(120, 168, 196, 255)
const COLOR_WARNING = new Color(200, 96, 64, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 56
const PLATE_HEIGHT = 50
/** 结果那一格：两列排，十连正好五行 */
const RESULT_LINE = 24
const PADDING = 16
/** 底部导航条占掉的高度（`PanelNav` 的 BAR_HEIGHT 52 + 8 边距），面板不许压上去 */
const NAV_RESERVE = 60
/** 顶部留给 HUD 顶栏的高度 */
const TOP_RESERVE = 24
const BUTTON_HEIGHT = 36
/**
 * 按钮那一行与最后一行池之间的间距：两块板半高之和再加一点空气。
 * 写 `ROW_HEIGHT / 2`（28）时按钮字直接压在第三行池的第二行字上 —— 截图抓到的，读数全绿。
 */
const BUTTON_CLEAR = (PLATE_HEIGHT + BUTTON_HEIGHT) / 2 + 10

@ccclass('RecruitPanelView')
export class RecruitPanelView extends Component {
  private data: GachaPanelView | null = null
  private pending: GachaPanelView | null = null
  private readonly nodes: Node[] = []
  /** 背景建好了没有：没建好之前收到的那份视图先存着（`attach` 可能早于 `onLoad`） */
  private built = false

  /** 选哪个池（抽满的那行也喊这一声 —— 它灰的是两个键，不是整行）。 */
  onPickPool: ((poolId: string) => void) | null = null
  /** 抽一次 / 抽十次。灰掉的键不吃触摸（理由已经写在面板上）。 */
  onDraw: ((count: number) => void) | null = null
  /** 打开合规公示那一屏。 */
  onProbability: (() => void) | null = null

  override onLoad(): void {
    this.buildBackground()
    this.built = true
    if (this.pending !== null) {
      const pending = this.pending
      this.pending = null
      this.attach(pending)
    }
  }

  override onDestroy(): void {
    this.built = false
    this.nodes.length = 0
    this.onPickPool = null
    this.onDraw = null
    this.onProbability = null
  }

  /** 装载整块视图（由 `AppRoot.deliverGacha` 下发，每次状态变化都重发一份完整的）。 */
  attach(data: GachaPanelView): void {
    this.data = data
    if (!this.built || !this.isValid) {
      // 背景还没建（节点尚未激活）：先存着，onLoad 时再画 —— 与武将页同一份做法
      this.pending = data
      return
    }
    this.redraw()
  }

  private buildBackground(): void {
    const size = view.getVisibleSize()
    const node = new Node('Background')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(size.width, size.height))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
    graphics.fill()
  }

  private redraw(): void {
    for (const node of this.nodes) {
      node.destroy()
    }
    this.nodes.length = 0
    const data = this.data
    if (data === null) {
      return
    }
    const size = view.getVisibleSize()
    const top = size.height / 2 - TOP_RESERVE

    this.label('招募', COLOR_COPPER_GOLD, 24, -PANEL_WIDTH / 2 + PADDING, top - 16, 'left')
    this.label(data.balanceText === null ? '余额读取中' : `余额 ${data.balanceText}`,
      COLOR_HINT, 15, PANEL_WIDTH / 2 - PADDING, top - 16, 'right')
    // 上一次失败的理由写在标题下面那一行：抽卡是花钱的动作，"为什么没抽成"必须看得见
    this.label(data.notice ?? '', COLOR_WARNING, 14, -PANEL_WIDTH / 2 + PADDING, top - 42, 'left')

    let y = top - 42
    for (const row of data.rows) {
      y -= ROW_HEIGHT
      this.drawPoolRow(row, y)
    }

    y -= BUTTON_CLEAR
    this.drawButtons(y)

    const results = data.resultTexts
    if (results !== null && results.length > 0) {
      y -= 34
      this.label('上一次抽到', COLOR_COPPER_GOLD, 16, -PANEL_WIDTH / 2 + PADDING, y, 'left')
      const width = (PANEL_WIDTH - PADDING * 2) / 2
      // 行数按**导航条上沿**算：十连有 10 条，画过界的那几条会被底栏盖住（#262/#273 同一族，
      // 而这里的判据是"最后一行的落点还在底栏之上"，不是"我数了几行"）
      const limit = -size.height / 2 + NAV_RESERVE + 12
      const lines = Math.max(1, Math.ceil(results.length / 2))
      let drawn = lines
      while (drawn > 1 && y - RESULT_LINE * drawn > limit) {
        drawn -= 1
      }
      const shown = results.slice(0, drawn * 2)
      shown.forEach((text, index) => {
        const column = index % 2
        const line = Math.floor(index / 2)
        this.label(text, COLOR_TEXT, 14,
          -PANEL_WIDTH / 2 + PADDING + column * width, y - RESULT_LINE * (line + 1), 'left')
      })
      const hidden = results.length - shown.length
      if (hidden > 0) {
        this.label(`另有 ${hidden} 条未列出（这一屏画不下）`, COLOR_TEXT_DIM, 13,
          -PANEL_WIDTH / 2 + PADDING, y - RESULT_LINE * (drawn + 1), 'left')
      }
    }
  }

  private drawPoolRow(row: GachaPoolRow, y: number): void {
    const selected = this.data?.selectedPoolId === row.poolId
    const width = PANEL_WIDTH - PADDING * 2
    const graphics = this.surface(`pool-${row.poolId}`, 0, y, width, PLATE_HEIGHT)
    graphics.fillColor = selected ? COLOR_ROW_SELECTED : COLOR_ROW
    graphics.rect(-width / 2, -PLATE_HEIGHT / 2, width, PLATE_HEIGHT)
    graphics.fill()
    graphics.node.on('touch-start', (_event: EventTouch) => this.onPickPool?.(row.poolId), this)

    const left = -width / 2 + 12
    this.label(row.name, COLOR_TEXT, 17, left, y + 8, 'left')
    // 第二行：消耗 + 限购 + 为什么点不动。抽满的那行不藏，只灰
    const detail = row.onceReason === null && row.tenReason === null
      ? `${row.costText} · ${row.limitText}`
      : `${row.costText} · ${row.limitText} · ${row.onceReason ?? row.tenReason ?? ''}`
    this.label(detail, row.exhausted ? COLOR_WARNING : COLOR_TEXT_DIM, 13, left, y - 11, 'left')
    this.label(selected ? '已选' : '', COLOR_COPPER_GOLD, 15, width / 2 - 12, y - 1, 'right')
  }

  private drawButtons(y: number): void {
    const data = this.data
    if (data === null) {
      return
    }
    const single = data.selected?.canDrawOnce ?? false
    const ten = data.selected?.canDrawTen ?? false
    this.button('drawOnce', data.singleText, -PANEL_WIDTH / 2 + PADDING + 110, y, single,
      () => this.onDraw?.(1))
    this.button('drawTen', data.tenText, -PANEL_WIDTH / 2 + PADDING + 330, y, ten,
      () => this.onDraw?.(10))
    this.button('probability', '概率公示', PANEL_WIDTH / 2 - PADDING - 84, y, true,
      () => this.onProbability?.())
  }

  /** 按钮：灰掉时**不吃触摸**（点了也不会发请求），与觉醒/合成弹层同一条纪律。 */
  private button(name: string, text: string, x: number, y: number, enabled: boolean,
    onClick: () => void): void {
    const width = 200
    const graphics = this.surface(name, x, y, width, BUTTON_HEIGHT)
    // 这里刻意用 Graphics 而不是九宫格按钮图：截图上按钮"只剩字、没有底"，
    // 而按钮底是"这一下能不能点"的唯一视觉信号（灰/亮两种态都靠它承载）
    graphics.fillColor = enabled ? COLOR_ROW_SELECTED : COLOR_ROW
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 6)
    graphics.fill()
    graphics.strokeColor = enabled ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
    graphics.lineWidth = 1
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 6)
    graphics.stroke()
    if (enabled) {
      graphics.node.on('touch-start', onClick)
    }
    this.label(text, enabled ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM, 16, x, y, 'center')
  }

  /** 造一块居中的画布子节点：位置、**尺寸**与登记一次做完（默认 100×100 会让触摸区域错位）。 */
  private surface(name: string, x: number, y: number, width: number, height: number): Graphics {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(width, height))
    node.setPosition(new Vec3(x, y, 0))
    this.nodes.push(node)
    return node.addComponent(Graphics)
  }

  /** 造一个 Label：**锚点先按对齐方式定**再摆位置（默认中心锚点会让左对齐的边界参差）。 */
  private label(text: string, color: Color, size: number, x: number, y: number,
    align: 'left' | 'right' | 'center'): void {
    if (text === '') {
      return
    }
    const node = new Node('label')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setAnchorPoint(
      align === 'left' ? 0 : align === 'right' ? 1 : 0.5, 0.5)
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = text
    label.color = color
    label.fontSize = size
    label.lineHeight = size + 6
    label.horizontalAlign = align === 'left'
      ? Label.HorizontalAlign.LEFT
      : align === 'right' ? Label.HorizontalAlign.RIGHT : Label.HorizontalAlign.CENTER
    node.setPosition(new Vec3(x, y, 0))
    this.nodes.push(node)
  }
}
