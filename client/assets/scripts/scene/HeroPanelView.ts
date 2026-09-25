/**
 * 职责：武将面板 —— 五条养成线、编队加成与乘区明细（B06 §2/§4、硬约束 1）。
 * 依赖：cc（渲染）、game/hero/HeroPanel（展示数据组装，已单测）、scene/NodePool。
 *
 * <p><b>本场景不做任何数值计算</b>（铁律 2）：属性、加成、战力、带兵上限全部照服务端下发的原样显示。
 * 定点加成由 HeroPanel 用 FixedPoint.percentText 格式化，本场景连除法都不做。
 *
 * <p><b>乘区必须显示出来</b>（B06 硬约束 1）：每行加成前面都带 [武将] / [缘分] / [装备套装] 标签。
 * 不标乘区，玩家侧的「为什么我这么强」与开发侧的「数值为什么算错」都无从查起。
 *
 * <p><b>触到乘区上限要明说</b>：HeroBonus.capped 为 true 时面板顶部给红色提示。
 * 不提示的话玩家会把资源一直投进去而看不到任何变化，那会被理解成数值造假。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：.scene / .prefab 资产、武将立绘、编队拖拽换位、
 * 装备与经验书的选择弹窗、长列表的 ScrollView。占位期用 Graphics 色块 + Label，item 已池化。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { buildHeroPanel } from '../game/hero/HeroPanel'
import type { HeroPanelView as HeroPanelData, HeroRow, LineupPanel } from '../game/hero/HeroPanel'
import type { HeroListResp } from '../net/generated/HeroProtocol'
import { applyAnyIconSprite, applyCommandButton, ensureFamily, rarityIconKey } from './ArtCatalog'
import { heroPortraitKey } from '../game/art/ArtFamilies'
import { NodePool } from './NodePool'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性。美术方向常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_ALT = new Color(46, 38, 31, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_WARNING = new Color(200, 96, 64, 255)
const COLOR_STAR = new Color(232, 190, 92, 255)
/** 稀有度配色，与抽卡公示面板同一套，玩家扫一眼就知道档位 */
const COLOR_SSR = new Color(232, 190, 92, 255)
const COLOR_SR = new Color(168, 122, 196, 255)
const COLOR_R = new Color(96, 140, 196, 255)
const COLOR_N = new Color(140, 134, 124, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 96
const ROW_GAP = 5
const HEADER_HEIGHT = 96
const PADDING = 16
/** 一屏最多画几行。武将行比其它面板高（要放下五条养成线），所以留得少 */
const MAX_VISIBLE_ROWS = 5

type Tab = 'heroes' | 'lineups'

/** 行内按钮对应的养成动作。 */
type HeroAction = 'levelUp' | 'starUp' | 'awaken' | 'skillUp' | 'equip' | 'armory'

@ccclass('HeroPanelView')
export class HeroPanelView extends Component {
  private panel: HeroPanelData | null = null
  private tab: Tab = 'heroes'
  private pending: HeroListResp | null = null

  private rowPool: NodePool | null = null
  private readonly drawnRows: Node[] = []
  /** 行节点 → 它当前代表的武将 id。池化节点复用时每次渲染都要重新登记 */
  private readonly rowHeroes = new Map<Node, string>()
  private headerLabel: Label | null = null
  /** 当前页签一行都没有时的那句话；有行时必须藏着（有行还印「暂无」等于自己打自己） */
  private emptyLabel: Label | null = null
  private cappedLabel: Label | null = null
  private readonly tabLabels = new Map<Tab, Label>()
  private readonly tabButtons = new Map<Tab, Node>()

  /**
   * 点某个养成按钮。具体消耗什么、能不能升由服务端裁定；
   * 需要的材料选择（经验书、装备、碎片）由外层弹窗给出，本场景拿不到背包数据。
   */
  onHeroAction: ((heroId: string, action: HeroAction) => void) | null = null
  /**
   * 点某一套编队的行，要改阵容。
   *
   * <p>只交出 presetIndex，不交具体槽位：「拖拽换位」需要真正的手势识别与编辑器资产，
   * 占位期先给一个「打开这套编队的编辑面板」的入口，具体怎么换由外层实现
   * （B06 §4：每队 3 名 = 主将 + 2 副将，可编 3 套）。
   */
  onLineupEdit: ((presetIndex: number) => void) | null = null

  /**
   * 点页眉那颗「合成」—— 打开碎片合成弹层。
   *
   * <p><b>为什么它在页眉而不在武将行上</b>：合成得到的是玩家**还没有**的那个武将，
   * 而行上每一个都是已有的（服务端对已拥有直接拒绝）。行上的五个按钮答的是
   * "这名武将怎么继续养"，这一颗答的是"我还能得到谁"。
   */
  onCompose: (() => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.rowPool = new NodePool(this.node, () => this.createRow(), MAX_VISIBLE_ROWS)
    this.buildHeader(size.height)
    // 立绘族按需拉取：先画一帧稀有度图标，立绘到了补一帧；加载失败就停在图标上
    ensureFamily('hero').then((loaded) => {
      if (loaded > 0 && this.isValid) {
        this.render()
      }
    })
    if (this.pending !== null) {
      const pending = this.pending
      this.pending = null
      this.attach(pending)
    }
  }

  override onDestroy(): void {
    this.rowPool?.destroy()
    this.rowPool = null
    this.drawnRows.length = 0
    this.rowHeroes.clear()
    this.tabLabels.clear()
    this.tabButtons.clear()
    this.onHeroAction = null
    this.onLineupEdit = null
    this.onCompose = null
  }

  /** 装载武将列表（GET /hero/list）。 */
  attach(resp: HeroListResp): void {
    if (this.rowPool === null) {
      this.pending = resp
      return
    }
    this.panel = buildHeroPanel(resp)
    this.render()
  }

  switchTab(tab: Tab): void {
    if (this.tab === tab) {
      return
    }
    this.tab = tab
    this.render()
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
    const top = height / 2 - PADDING
    this.headerLabel = this.addLabel(this.node, 'Header', 0, top - 20, COLOR_COPPER_GOLD, 20)
    // 空态那一行落在**第一行该在的那个 y**（与 renderRows 同一套 topY 算法），不是随手挑的数
    this.emptyLabel = this.addLabel(this.node, 'Empty', 0,
      top - HEADER_HEIGHT - ROW_HEIGHT / 2, COLOR_TEXT_DIM, 16)
    this.emptyLabel.string = ''
    this.emptyLabel.node.active = false
    this.cappedLabel = this.addLabel(this.node, 'CappedHint', 0, top - 46, COLOR_WARNING, 14)

    const tabs: Array<{ tab: Tab; text: string; x: number }> = [
      { tab: 'heroes', text: '武将', x: -60 },
      { tab: 'lineups', text: '编队', x: 60 },
    ]
    for (const item of tabs) {
      const node = new Node(`Tab_${item.tab}`)
      node.layer = this.node.layer
      this.node.addChild(node)
      node.setPosition(new Vec3(item.x, top - 74, 0))
      node.addComponent(UITransform).setContentSize(new Size(110, 34))
      if (!applyCommandButton(node, item.tab === this.tab ? 'hover' : 'normal', 110, 34)) {
        const graphics = node.addComponent(Graphics)
        graphics.fillColor = COLOR_PANEL
        graphics.strokeColor = COLOR_COPPER_GOLD
        graphics.lineWidth = 1
        graphics.roundRect(-55, -17, 110, 34, 5)
        graphics.fill()
        graphics.stroke()
      }
      const label = this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT, 16)
      label.string = item.text
      this.tabLabels.set(item.tab, label)
      this.tabButtons.set(item.tab, node)
      const tab = item.tab
      node.on('touch-start', (_event: EventTouch) => this.switchTab(tab), this)
    }

    // 页眉右侧那颗「合成」：V03-d 第六条线（碎片换一名未拥有的武将）的入口。
    // 放页眉而不是行上，理由见 {@link onCompose}。
    const compose = new Node('ComposeEntry')
    compose.layer = this.node.layer
    this.node.addChild(compose)
    compose.setPosition(new Vec3(PANEL_WIDTH / 2 - 55, top - 74, 0))
    compose.addComponent(UITransform).setContentSize(new Size(94, 34))
    if (!applyCommandButton(compose, 'normal', 94, 34)) {
      const graphics = compose.addComponent(Graphics)
      graphics.fillColor = COLOR_PANEL
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 1
      graphics.roundRect(-47, -17, 94, 34, 5)
      graphics.fill()
      graphics.stroke()
    }
    const composeLabel = this.addLabel(compose, 'Caption', 0, 0, COLOR_TEXT, 16)
    composeLabel.string = '碎片合成'
    compose.on('touch-start', (_event: EventTouch) => this.onCompose?.(), this)
  }

  private createRow(): Node {
    const node = new Node('HeroRow')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
    graphics.fill()

    const lines: Array<{ name: string; y: number; size: number; color: Color }> = [
      { name: 'Line1', y: 32, size: 18, color: COLOR_TEXT },
      { name: 'Line2', y: 10, size: 14, color: COLOR_TEXT_DIM },
      { name: 'Line3', y: -8, size: 14, color: COLOR_TEXT_DIM },
      { name: 'Line4', y: -26, size: 13, color: COLOR_TEXT_DIM },
    ]
    for (const line of lines) {
      const label = this.addLabel(node, line.name, -PANEL_WIDTH / 2 + PADDING + 48,
        line.y, line.color, line.size)
      label.horizontalAlign = Label.HorizontalAlign.LEFT
      label.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
      label.node.getComponent(UITransform)?.setContentSize(new Size(430, line.size * 1.5))
      label.overflow = Label.Overflow.SHRINK
    }

    const actions: Array<{ name: string; text: string; x: number; action: HeroAction }> = [
      // 「装备库」是 V03-b-S1 的入口：装备穿在武将身上，"我有哪些装备、要不要强化"就在这一页问
      // （不进底栏导航：它是武将页的二级页）。其余四个动作的落地另记在队列里 —— 今天它们只打日志。
      { name: 'ArmoryButton', text: '装备库', x: PANEL_WIDTH / 2 - 228, action: 'armory' },
      { name: 'LevelUpButton', text: '升级', x: PANEL_WIDTH / 2 - 176, action: 'levelUp' },
      { name: 'StarUpButton', text: '升星', x: PANEL_WIDTH / 2 - 124, action: 'starUp' },
      { name: 'AwakenButton', text: '觉醒', x: PANEL_WIDTH / 2 - 72, action: 'awaken' },
      { name: 'SkillButton', text: '技能', x: PANEL_WIDTH / 2 - 20, action: 'skillUp' },
    ]
    for (const button of actions) {
      const buttonNode = new Node(button.name)
      buttonNode.layer = node.layer
      node.addChild(buttonNode)
      buttonNode.setPosition(new Vec3(button.x, -34, 0))
      buttonNode.addComponent(UITransform).setContentSize(new Size(48, 26))
      if (!applyCommandButton(buttonNode, 'normal', 48, 26)) {
        const buttonGraphics = buttonNode.addComponent(Graphics)
        buttonGraphics.fillColor = COLOR_PANEL
        buttonGraphics.strokeColor = COLOR_COPPER_GOLD
        buttonGraphics.lineWidth = 1
        buttonGraphics.roundRect(-24, -13, 48, 26, 4)
        buttonGraphics.fill()
        buttonGraphics.stroke()
      }
      const caption = this.addLabel(buttonNode, 'Caption', 0, 0, COLOR_TEXT, 12)
      caption.string = button.text
      const action = button.action
      buttonNode.on('touch-start', (_event: EventTouch) => {
        const heroId = this.rowHeroes.get(node)
        if (heroId !== undefined) {
          this.onHeroAction?.(heroId, action)
        }
      }, this)
    }
    const icon = new Node('Icon')
    icon.layer = node.layer
    node.addChild(icon)
    icon.setPosition(new Vec3(-PANEL_WIDTH / 2 + PADDING + 24, 0, 0))
    icon.addComponent(UITransform).setContentSize(new Size(54, 54))
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
    const panel = this.panel
    const pool = this.rowPool
    if (panel === null || pool === null) {
      return
    }
    for (const [tab, label] of this.tabLabels) {
      label.color = tab === this.tab ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
      const button = this.tabButtons.get(tab)
      if (button !== undefined) {
        applyCommandButton(button, tab === this.tab ? 'hover' : 'normal', 110, 34)
      }
    }
    if (this.headerLabel !== null) {
      this.headerLabel.string = this.tab === 'heroes'
        ? `武将 ${panel.heroes.length} 名 · ${panel.troopCapText}`
        : `编队 ${panel.lineups.length} 套 · ${panel.troopCapText}`
    }
    // 任意一套编队触到乘区上限就提示：继续堆养成不会再变强，
    // 不说的话玩家会把资源一直投进去而看不到变化（那会被理解成数值造假）
    const capped = panel.lineups.find((lineup) => lineup.capped)
    if (this.cappedLabel !== null) {
      this.cappedLabel.string = capped?.cappedHint ?? ''
    }

    const drafts = this.tab === 'heroes' ? heroDrafts(panel.heroes) : lineupDrafts(panel.lineups)
    // 零行要说句话：武将页/编队页都出现过"整块空白 + 表头只写总数"的样子，
    // 玩家分不清"确实没有"与"面板坏了"（MarchPanelView:77 同一形状，句式照它）
    if (this.emptyLabel !== null) {
      this.emptyLabel.string = this.tab === 'heroes' ? '暂无武将' : '暂无编队'
      this.emptyLabel.node.active = drafts.length === 0
    }
    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    pool.releaseAll(this.drawnRows)
    this.drawnRows.length = 0
    this.rowHeroes.clear()

    drafts.slice(0, MAX_VISIBLE_ROWS).forEach((draft, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnRows.push(node)
      this.renderRow(node, draft, index)
    })
  }

  private renderRow(node: Node, draft: RowDraft, index: number): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      graphics.fillColor = index % 2 === 0 ? COLOR_ROW : COLOR_ROW_ALT
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
      graphics.fill()
    }
    for (let line = 0; line < 4; line++) {
      const label = node.children[line]?.getComponent(Label)
      if (label === undefined || label === null) {
        continue
      }
      label.string = draft.lines[line] ?? ''
      label.color = line === 0 ? draft.titleColor : COLOR_TEXT_DIM
    }
    // 前四个子节点是文本行，后面是养成按钮。编队页不给养成按钮（改阵容走整行点击）。
    // 按名字挑而不是 slice(4, 8)：多一个按钮就漏一个 —— 「技能」那颗在编队页上
    // 一直亮着（点它没反应，因为 rowHeroes 里没有这一行），加「装备库」时没人发现。
    for (const button of node.children.filter((child) => child.name.endsWith('Button'))) {
      button.active = draft.heroId !== null
    }
    const icon = node.getChildByName('Icon')
    if (icon !== null) {
      icon.active = draft.iconKey !== null
        && applyAnyIconSprite(icon, draft.iconKey, 52, 52)
    }
    // 整行点击也要先 off 再 on：节点来自池子，上一条数据留下的回调会指向别的武将/编队，
    // 这类 bug 的表现是「点了第 3 套编队却打开了第 1 套」，而且只在切页时出现，极难复现
    node.off('touch-start')
    if (draft.heroId !== null) {
      this.rowHeroes.set(node, draft.heroId)
    } else if (draft.presetIndex !== null) {
      const presetIndex = draft.presetIndex
      node.on('touch-start', (_event: EventTouch) => this.onLineupEdit?.(presetIndex), this)
    }
  }
}

/** 一行要画的内容。武将页与编队页共用同一套节点结构，只是各字段含义不同。 */
interface RowDraft {
  readonly lines: readonly string[]
  readonly titleColor: Color
  readonly iconKey: string | null
  /** 武将行才有；编队行为 null，据此隐藏养成按钮 */
  readonly heroId: string | null
  /** 编队主行才有；点整行时把它交出去。缘分附注行为 null（点它不该打开编辑面板） */
  readonly presetIndex: number | null
}

function heroDrafts(heroes: readonly HeroRow[]): RowDraft[] {
  return heroes.map((hero): RowDraft => ({
    lines: [
      `${hero.rarity} ${hero.name} · ${hero.levelText} · ${hero.starText} · ${hero.awakenText}`,
      `${hero.mainSkillText}｜${hero.subSkillText}`,
      `${hero.attrText} · ${hero.powerText}`,
      [hero.equipTexts.join(' '), hero.bondText, hero.expText]
        .filter((part): part is string => part !== null && part.length > 0)
        .join(' · '),
    ],
    titleColor: rarityColor(hero.rarity),
    // 有立绘用立绘，没有退回稀有度图标 —— 编队/抽卡以后都吃这一条映射
    iconKey: heroPortraitKey(hero.heroId) ?? rarityIconKey(hero.rarity),
    heroId: hero.heroId,
    presetIndex: null,
  }))
}

function lineupDrafts(lineups: readonly LineupPanel[]): RowDraft[] {
  const out: RowDraft[] = []
  for (const lineup of lineups) {
    out.push({
      lines: [
        `${lineup.presetText} · ${lineup.commandText}`,
        lineup.slotTexts.join(' '),
        `${lineup.atkText}｜${lineup.defText}｜${lineup.skillText}`,
        // 乘区标签必须保留：B06 硬约束 1 要求每个加成都标明落在哪个乘区
        lineup.breakdownLines.join(' '),
      ],
      titleColor: lineup.emptySlots > 0 ? COLOR_WARNING : COLOR_STAR,
      iconKey: null,
      heroId: null,
      presetIndex: lineup.presetIndex,
    })
    if (lineup.bondTexts.length > 0) {
      out.push({
        lines: [`    已激活缘分：${lineup.bondTexts.join(' · ')}`, '', '', ''],
        titleColor: COLOR_TEXT_DIM,
        iconKey: null,
        heroId: null,
        presetIndex: null,
      })
    }
  }
  return out
}

function rarityColor(rarity: string): Color {
  switch (rarity) {
    case 'SSR': return COLOR_SSR
    case 'SR': return COLOR_SR
    case 'R': return COLOR_R
    case 'N': return COLOR_N
    default: return COLOR_TEXT
  }
}
