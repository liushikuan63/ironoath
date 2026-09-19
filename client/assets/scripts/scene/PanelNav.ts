/**
 * 职责：面板导航层 —— 建出各面板节点、只激活当前一个、画底部导航条。
 * 依赖：cc（渲染）、scene/*View（被导航的面板）。
 *
 * <p><b>为什么需要它</b>：11 个面板都是<b>全屏自绘</b>（各自画满屏背景 + 内容），
 * 同时挂在同一个节点上会互相覆盖 —— 之前只能一次看一个，等于九个面板没有入口。
 * 本类把每个面板放进自己的子节点，用 {@code active} 切换：
 * <ul>
 *   <li>未激活的节点<b>不会执行 onLoad</b>，也就不会画背景、不占渲染 —— 不是"画了再藏起来"</li>
 *   <li>数据由 AppRoot 在登录后一次性预拉，各面板的 attach 都有 pending 机制；
 *       节点首次激活时消费 pending，所以切换是即时的，不需要再等网络</li>
 * </ul>
 *
 * <p><b>不参与 node:test</b>：本文件 import 'cc'，与其它 scene/ 下的文件同一条边界
 * （DEVELOPMENT.md §八）。可判定的部分（面板清单的 key/label/顺序）随游戏逻辑层一起演进，
 * 这里只做"把组件挂到节点上、切 active"。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { playSfx } from './AudioService'
import { CityPanelView } from './CityPanelView'
import { ArmyPanelView } from './ArmyPanelView'
import { HeroPanelView } from './HeroPanelView'
import { BagPanelView } from './BagPanelView'
import { StagePanelView } from './StagePanelView'
import { SocialPanelView } from './SocialPanelView'
import { PowerPanelView } from './PowerPanelView'
import { TargetSearchView } from './TargetSearchView'
import { QuestPanelView } from './QuestPanelView'
import { MailPanelView } from './MailPanelView'
import { BattleReportPanelView } from './BattleReportPanelView'
import { WorldMap } from './WorldMap'
import { SettingsPanelView } from './SettingsPanelView'
import { applyNavTab } from './ArtCatalog'
import { ClientReddotTree } from '../game/reddot/ReddotTree'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

/** 配色与各面板保持一致（铜金 + 暗红）。 */
const COLOR_BAR = new Color(32, 26, 21, 240)
const COLOR_ACTIVE = new Color(184, 134, 11, 255)
const COLOR_IDLE = new Color(52, 43, 35, 240)
const COLOR_TEXT_ACTIVE = new Color(26, 19, 16, 255)
/**
 * 画了页签图时选中态的字色。页签常态的中心是近黑皮革、选中态是深红，两种都撑不起深色字；
 * "选中 = 亮铜底 + 深色字"只是 Graphics 兜底那条路径的前提。
 * 这条不是预防性设计：换成页签图之前，导航格用的是缩到 63×44 的按钮九宫格，
 * 选中格深色字压在深色底上，「内城」两个字实际看不见（相对亮度 0.079，收口清单 #213 截图）。
 */
const COLOR_TEXT_ACTIVE_ON_ART = new Color(255, 205, 92, 255)
const COLOR_TEXT_IDLE = new Color(200, 186, 160, 255)
const COLOR_RED_DOT = new Color(214, 60, 50, 255)

const BAR_HEIGHT = 52

interface PanelDef {
  readonly key: string
  readonly label: string
  readonly view: new () => Component
  /** 导航角标绑定的红点路径；null 表示这个入口目前没有服务端叶子。 */
  readonly reddotKey: string | null
}

/**
 * 面板清单。**顺序就是导航条从左到右的顺序**：
 * 内城 → 军队 → 武将 → 背包 → 关卡 → 任务 → 社交 → 战力 → 搜索 → 地图。
 * 地图（WorldMap）是最后一项：它带镜头与拖拽输入，且依赖 enterWorld 初始化过的世界模型
 * （AppRoot.start 里已经拉过），所以挂上就能用，不需要额外的装配。
 */
const PANELS: readonly PanelDef[] = [
  { key: 'city', label: '内城', view: CityPanelView, reddotKey: 'city' },
  { key: 'army', label: '军队', view: ArmyPanelView, reddotKey: null },
  { key: 'hero', label: '武将', view: HeroPanelView, reddotKey: null },
  { key: 'bag', label: '背包', view: BagPanelView, reddotKey: null },
  { key: 'stage', label: '关卡', view: StagePanelView, reddotKey: null },
  // 战报紧跟关卡：都是「打完之后回来看」的入口，且它的数据在 dev 里真的会有（打野就产生战报）。
  { key: 'reports', label: '战报', view: BattleReportPanelView, reddotKey: null },
  { key: 'quest', label: '任务', view: QuestPanelView, reddotKey: null },
  // 邮件紧跟任务：两者都是「每天进来清一次」的入口，而它的角标绑在服务端 mail/unread 叶子上
  // （B12 §4：红点判据只有一处，客户端不参与算）。
  { key: 'mail', label: '邮件', view: MailPanelView, reddotKey: 'mail' },
  { key: 'social', label: '社交', view: SocialPanelView, reddotKey: 'social' },
  { key: 'power', label: '战力', view: PowerPanelView, reddotKey: null },
  { key: 'targets', label: '搜索', view: TargetSearchView, reddotKey: null },
  // 地图放最后：它是唯一带镜头与拖拽的面板，数据流（viewport/marches 订阅）也与其余面板不同。
  // enterWorld 在登录时已由 AppRoot 拉过，这里挂上即能渲染。
  { key: 'world', label: '地图', view: WorldMap, reddotKey: null },
  // 设置放最后：客服与退款入口要「一级可见」（上线检查清单 §二 8/9），
  // 而导航条就是本作唯一的一级入口 —— 放进某个面板里就等于二级。
  { key: 'settings', label: '设置', view: SettingsPanelView, reddotKey: null },
]

@ccclass('PanelNav')
export class PanelNav extends Component {

  private currentKey = ''
  /** 导航条每格的宽度（建条时算好，高亮时复用它，不去读 UITransform 的属性名） */
  private columnWidth = 100
  private readonly panelNodes = new Map<string, Node>()
  private readonly buttonNodes = new Map<string, Node>()
  private readonly buttonLabels = new Map<string, Label>()
  private readonly navDots = new Map<string, Node>()
  private reddot: ClientReddotTree | null = null

  /** 切换面板时的回调。数据侧由 GameBootstrap 决定要不要补拉，导航层不碰网络。 */
  onShow: ((key: string) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    for (const def of PANELS) {
      const node = new Node(def.key)
      node.layer = this.node.layer
      // 先置为未激活再挂组件：Cocos 不会给未激活节点跑 onLoad，
      // 于是九个面板不会在开局一起画满屏背景（也省掉九份节点池）
      node.active = false
      this.node.addChild(node)
      // **必须有 UITransform 且铺满屏**：触摸命中是按节点的 UITransform 矩形算的。
      // 少这一层时，地图的拖拽（监听在本节点上）完全收不到事件 —— 表现是"地图能看不能拖"。
      node.addComponent(UITransform).setContentSize(new Size(size.width, size.height))
      node.addComponent(def.view)
      this.panelNodes.set(def.key, node)
    }
    this.buildBar()
    // 初始面板：URL 里的 ?panel=<key> 优先（Web 调试/深链用），否则第一个面板
    this.show(this.initialPanelFromUrl() ?? PANELS[0]?.key ?? 'city')
  }

  /**
   * 从 `?panel=<key>` 读初始面板。
   *
   * <p>存在的理由有两个：① 调试时能直接打开某个面板，不用先点导航（"我要看地图"这件事
   * 不该依赖点击成功）；② Web 端将来做深链分享时，这一处就是入口。
   * 小游戏没有 `location`，取不到就回退默认面板 —— 不抛错、不猜。
   */
  private initialPanelFromUrl(): string | null {
    if (typeof location === 'undefined') {
      return null
    }
    try {
      const key = new URLSearchParams(location.search).get('panel')
      return key !== null && this.panelNodes.has(key) ? key : null
    } catch (error) {
      console.warn('[PanelNav] 解析 ?panel 失败，使用默认面板', error)
      return null
    }
  }

  /** 显示某个面板。未知 key 直接忽略（不猜、也不静默切到第一个）。 */
  show(key: string): void {
    if (!this.panelNodes.has(key)) {
      console.warn(`[PanelNav] 未知面板：${key}`)
      return
    }
    if (this.currentKey === key) {
      this.onShow?.(key)
      return
    }
    // 换页有声：这是唯一一处"玩家主动换了屏"的事实，音效层挂在这里，
    // 二十个视图不需要知道音频存在
    playSfx('nav')
    for (const [panelKey, node] of this.panelNodes) {
      node.active = panelKey === key
    }
    this.currentKey = key
    this.highlight()
    this.onShow?.(key)
  }

  /** 当前面板 key（GameBootstrap 判断要不要补拉数据时用）。 */
  current(): string {
    return this.currentKey
  }

  /**
   * 面板的可用区域（本节点本地坐标，左下角 + 宽高）：**全屏减去底部导航条那一条**。
   *
   * <p>存在的唯一理由是引导遮罩要"挖洞"（B18 验收 6）：洞必须是玩家这一步真能操作的那块，
   * 而"哪些格子属于导航条"这件事只有本类知道 —— 把条高抄到引导层去，就是同一个几何两个家，
   * 将来改条高的人只会改这里，于是引导挡住的是内容、放过的是导航。
   *
   * <p>未知 key 返回 null：引导层据此退回"整屏都能点"而不是猜一个矩形。
   */
  contentRectFor(key: string | null): { x: number, y: number, width: number, height: number } | null {
    if (key === null || !this.panelNodes.has(key)) {
      return null
    }
    const size = view.getVisibleSize()
    // 导航条中心在 -h/2 + BAR_HEIGHT/2 + 8 ⇒ 它占的是 y ∈ [-h/2+8, -h/2+8+BAR_HEIGHT]
    const bottom = -size.height / 2 + 8 + BAR_HEIGHT
    return { x: -size.width / 2, y: bottom, width: size.width, height: size.height / 2 - bottom }
  }

  // ---------- 导航条 ----------

  private buildBar(): void {
    const size = view.getVisibleSize()
    const width = Math.min(size.width - 24, 900)
    const bar = new Node('NavBar')
    bar.layer = this.node.layer
    this.node.addChild(bar)
    bar.setPosition(new Vec3(0, -size.height / 2 + BAR_HEIGHT / 2 + 8, 0))
    bar.addComponent(UITransform).setContentSize(new Size(width, BAR_HEIGHT))
    const background = new Node('BarBackground')
    background.layer = bar.layer
    bar.addChild(background)
    background.addComponent(UITransform).setContentSize(new Size(width, BAR_HEIGHT))
    const graphics = background.addComponent(Graphics)
    graphics.fillColor = COLOR_BAR
    graphics.roundRect(-width / 2, -BAR_HEIGHT / 2, width, BAR_HEIGHT, 8)
    graphics.fill()

    const columnWidth = width / PANELS.length
    this.columnWidth = columnWidth
    PANELS.forEach((def, index) => {
      const x = -width / 2 + columnWidth * (index + 0.5)
      const button = new Node(`Nav-${def.key}`)
      button.layer = bar.layer
      bar.addChild(button)
      button.setPosition(new Vec3(x, 0, 0))
      button.addComponent(UITransform).setContentSize(new Size(columnWidth - 6, BAR_HEIGHT - 8))
      const labelNode = new Node('Caption')
      labelNode.layer = button.layer
      button.addChild(labelNode)
      labelNode.addComponent(UITransform)
      const label = applySystemUiFont(labelNode.addComponent(Label))
      label.string = def.label
      label.fontSize = 18
      label.color = COLOR_TEXT_IDLE

      const dot = new Node('NavRedDot')
      dot.layer = button.layer
      button.addChild(dot)
      dot.setPosition(new Vec3((columnWidth - 6) / 2 - 9, (BAR_HEIGHT - 8) / 2 - 9, 0))
      dot.addComponent(UITransform).setContentSize(new Size(12, 12))
      const dotGraphics = dot.addComponent(Graphics)
      dotGraphics.fillColor = COLOR_RED_DOT
      dotGraphics.roundRect(-6, -6, 12, 12, 6)
      dotGraphics.fill()
      dot.active = false
      this.navDots.set(def.key, dot)

      button.on('touch-start', (_event: EventTouch) => {
        this.show(def.key)
      }, this)
      this.buttonNodes.set(def.key, button)
      this.buttonLabels.set(def.key, label)
    })
    this.highlight()
  }

  /**
   * 绑定服务端权威红点树。
   *
   * <p>导航不按业务数据自行判断，只按面板定义里的路径读同一棵树；
   * 树每次整体替换后重新画一次，因此已经消失的红点不会残留。
   */
  attachReddot(tree: ClientReddotTree): void {
    this.reddot = tree
    this.refreshNavDots()
  }

  private refreshNavDots(): void {
    const tree = this.reddot
    for (const def of PANELS) {
      const dot = this.navDots.get(def.key)
      if (dot === undefined) {
        continue
      }
      dot.active = tree !== null && def.reddotKey !== null && tree.isLit(def.reddotKey)
    }
  }

  /** 高亮当前项：当前用铜金底 + 深色字，其余保持暗底浅字。 */
  private highlight(): void {
    for (const def of PANELS) {
      const button = this.buttonNodes.get(def.key)
      const label = this.buttonLabels.get(def.key)
      if (button === undefined || label === undefined) {
        continue
      }
      const active = def.key === this.currentKey
      const width = this.columnWidth - 6
      const height = BAR_HEIGHT - 8
      const drawnWithArt = applyNavTab(button, active, width, height)
      // 字色要等"这格实际是什么底"定了再定：先设色再画图，选中态就是深色字压在深色底上
      label.color = drawnWithArt && active ? COLOR_TEXT_ACTIVE_ON_ART
        : (active ? COLOR_TEXT_ACTIVE : COLOR_TEXT_IDLE)
      if (drawnWithArt) {
        continue
      }
      const graphics = button.getComponent(Graphics) ?? button.addComponent(Graphics)
      graphics.clear()
      graphics.fillColor = active ? COLOR_ACTIVE : COLOR_IDLE
      // 尺寸用建按钮时算好的值：UITransform 的尺寸属性名在不同版本间变过（width/height
      // 与 contentSize），这里不依赖它
      graphics.roundRect(-width / 2, -height / 2, width, height, 6)
      graphics.fill()
    }
  }

  override onDestroy(): void {
    this.panelNodes.clear()
    this.buttonNodes.clear()
    this.buttonLabels.clear()
    this.navDots.clear()
    this.reddot = null
    this.onShow = null
  }
}
