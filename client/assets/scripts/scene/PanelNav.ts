/**
 * 职责：面板导航层 —— 建出各面板节点、只激活当前一个、画底部导航条。
 * 依赖：cc（渲染）、scene/*View（被导航的面板）。
 *
 * <p><b>为什么需要它</b>：11 个面板都是<b>全屏自绘</b>（各自画满屏背景 + 内容），
 * 同时挂在同一个节点上会互相覆盖 —— 之前只能一次看一个，等于其余面板没有入口。
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
import { ShopPanelView } from './ShopPanelView'
import { AvatarFramePanelView } from './AvatarFramePanelView'
import { TargetSearchView } from './TargetSearchView'
import { RecruitPanelView } from './RecruitPanelView'
import { QuestPanelView } from './QuestPanelView'
import { BattlePassPanelView } from './BattlePassPanelView'
import { LevelRewardPanelView } from './LevelRewardPanelView'
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
 * 面板清单（**当前 18 项**，2026-10-08 现数：原 17 项 + 等级奖励）。**顺序就是面板的定义顺序**；
 * 导航条上从左到右只画常驻那几格（见 {@link MORE_KEYS}），其余进「更多」抽屉。
 *
 * <p>**这份注释里的计数是一个已经漂移过两次的读数**（阶段 2 写"九个系统"、阶段 5/7 之后
 * 陆续加到 17，而注释与 `CC开发全流程.md` 阶段总览都停在旧数）。改这一行时请顺手改
 * `CC开发全流程.md` 阶段总览/3.1/7 三处 —— 判据：`grep -c "{ key: '" 本文件` 必须等于注释里的数字。
 *
 * <p>⚠ **这条判据本身差一格**（2026-10-08 现数）：上面那句 `grep -c` 的匹配串在本注释里也出现一次，
 * 所以字面读数恒为「行数 + 1」（18 行 ⇒ grep 19）。注释里的数写的是**真实行数**，
 * 用它核对时要么 `grep -c` 再减 1，要么只数 `PANELS` 那一段（`sed -n '/^const PANELS/,/^]/p' | grep -c "{ key: '"`）。
 *
 * <p>地图（WorldMap）是倒数第二项：它带镜头与拖拽输入，且依赖 enterWorld 初始化过的世界模型
 * （AppRoot.start 里已经拉过），所以挂上就能用，不需要额外的装配。
 * 设置放最后：客服与退款入口要一级可见（上线检查清单 §二 8/9）。
 */
const PANELS: readonly PanelDef[] = [
  { key: 'city', label: '内城', view: CityPanelView, reddotKey: 'city' },
  { key: 'army', label: '军队', view: ArmyPanelView, reddotKey: null },
  { key: 'hero', label: '武将', view: HeroPanelView, reddotKey: null },
  // 招募紧跟武将：抽出来的人就在隔壁那一页看 —— "抽到了什么去哪确认"只隔一次点击
  { key: 'gacha', label: '招募', view: RecruitPanelView, reddotKey: null },
  { key: 'bag', label: '背包', view: BagPanelView, reddotKey: null },
  { key: 'stage', label: '关卡', view: StagePanelView, reddotKey: null },
  // 战报紧跟关卡：都是「打完之后回来看」的入口，且它的数据在 dev 里真的会有（打野就产生战报）。
  { key: 'reports', label: '战报', view: BattleReportPanelView, reddotKey: null },
  { key: 'quest', label: '任务', view: QuestPanelView, reddotKey: null },
  // 战令紧跟任务（B24 S-d-e）：它的积分只来自任务与活动 —— 两个入口挨着，
  // 「分从哪来」就不需要在界面上解释一遍
  { key: 'battlePass', label: '战令', view: BattlePassPanelView, reddotKey: null },
  // 等级奖励紧跟战令：两者都是「升到/攒到之后回来点一下」的领取型入口，挨着放玩家不用找第二遍。
  // 标签只用两个字 —— 抽屉那一格宽 52.9px 量级，四字标签会贴上格边（本文件 MORE_KEYS 的注释记过这条）。
  { key: 'levelReward', label: '等级', view: LevelRewardPanelView, reddotKey: 'levelReward' },
  // 邮件紧跟任务：两者都是「每天进来清一次」的入口，而它的角标绑在服务端 mail/unread 叶子上
  // （B12 §4：红点判据只有一处，客户端不参与算）。
  { key: 'mail', label: '邮件', view: MailPanelView, reddotKey: 'mail' },
  { key: 'social', label: '社交', view: SocialPanelView, reddotKey: 'social' },
  { key: 'power', label: '战力', view: PowerPanelView, reddotKey: null },
  // 商店紧跟战力（B24 S-b）：都是"点开看一眼、顺手做一件事"的常驻入口；它不占首屏，
  // 玩家点开这一格时由 onShow 拉第一次（与邮件同一条纪律）
  { key: 'shop', label: '商店', view: ShopPanelView, reddotKey: null },
  // 外观紧跟商店（B24 块③）：框是在商店里换来的，佩戴入口放在它隔壁 ——
  // "买完在哪里戴"是这个功能唯一的坑，两个入口挨着就不需要教
  { key: 'avatarFrames', label: '外观', view: AvatarFramePanelView, reddotKey: null },
  { key: 'targets', label: '搜索', view: TargetSearchView, reddotKey: null },
  // 地图放最后：它是唯一带镜头与拖拽的面板，数据流（viewport/marches 订阅）也与其余面板不同。
  // enterWorld 在登录时已由 AppRoot 拉过，这里挂上即能渲染。
  { key: 'world', label: '地图', view: WorldMap, reddotKey: null },
  // 设置放最后：客服与退款入口要「一级可见」（上线检查清单 §二 8/9），
  // 而导航条就是本作唯一的一级入口 —— 放进某个面板里就等于二级。
  { key: 'settings', label: '设置', view: SettingsPanelView, reddotKey: null },
]

/**
 * 收进「更多」抽屉的入口（**这里的顺序就是抽屉里从左到右、从上到下的顺序**）。
 * 常驻条因此只剩 7 格 + 「更多」：17 格挤在 900px 里时每格只有 52.9px，
 * 两个字的标签（fontSize 18 = 36px）贴上格边，页签图的端帽也被压得读不出造型（用户 2026-09-26 指出）。
 *
 * <p>谁**必须**留在条上，是三条硬约束定的，不是审美：
 * <ul>
 *   <li>设置 —— 客服与退款要「一级可见」（上线检查清单 §二 8/9），而导航条是本作唯一的一级入口；</li>
 *   <li>邮件 —— 每天进来清一次的入口，且角标绑在服务端 mail/unread 叶子上（B12 §4）；</li>
 *   <li>内城 / 军队 / 武将 / 任务 / 地图 —— 主线循环：引导七步与主线任务在这五格之间来回，
 *       多一次"先展开抽屉"就是把新手引导的每一步都加长一截。</li>
 * </ul>
 * 抽屉里那 11 项都是"点开看一眼、顺手做一件事"的常驻功能，收起来不影响主线推进；
 * 它们各自的红点仍在（抽屉格子上照画），并在「更多」上合并成一个总点，收起时也看得见。
 *
 * <p>面板清单仍只有 {@link PANELS} 一份出处：这里只列 key，标签 / 视图 / 红点路径都从它取。
 * 写错一个 key 不会静默少一格 —— `tools/verify-art-runtime.mjs` 的导航格数判据按
 * "条上 + 抽屉里的格子合计 = PANELS 行数" 断言，漏一个就红。
 */
const MORE_KEYS: readonly string[] = [
  'gacha', 'bag', 'stage', 'reports', 'battlePass', 'levelReward',
  'social', 'power', 'shop', 'avatarFrames', 'targets',
]

/** 常驻条上的格子（按 PANELS 的顺序）。 */
const BAR_PANELS: readonly PanelDef[] = PANELS.filter((def) => !MORE_KEYS.includes(def.key))
/** 抽屉里的格子（按 MORE_KEYS 的顺序）。 */
const MORE_PANELS: readonly PanelDef[] = MORE_KEYS
  .map((key) => PANELS.find((def) => def.key === key) ?? null)
  .filter((def): def is PanelDef => def !== null)

/** 抽屉的几何：5 列，行数按 `MORE_PANELS.length` 现算（11 项 = 3 行），格子高度与导航格一致，省得两套手感。 */
const MORE_COLUMNS = 5
const MORE_GAP = 8
const MORE_PADDING = 10
/** 抽屉那一格自己的 key。它不是面板，所以 `show()` 认不得它 —— 点击走 {@link PanelNav.toggleMore}。 */
const MORE_KEY = 'more'
const MORE_LABEL = '更多'

@ccclass('PanelNav')
export class PanelNav extends Component {

  private currentKey = ''
  /** 每格实际画多宽：条上与抽屉里不一样，画页签图时要按各自的宽度画 */
  private readonly cellWidths = new Map<string, number>()
  private readonly panelNodes = new Map<string, Node>()
  private readonly buttonNodes = new Map<string, Node>()
  private readonly buttonLabels = new Map<string, Label>()
  private readonly navDots = new Map<string, Node>()
  private moreLayer: Node | null = null
  private moreOpen = false
  private reddot: ClientReddotTree | null = null
  private visibleWidth = 0
  private visibleHeight = 0

  /** 切换面板时的回调。数据侧由 GameBootstrap 决定要不要补拉，导航层不碰网络。 */
  onShow: ((key: string) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    for (const def of PANELS) {
      const node = new Node(def.key)
      node.layer = this.node.layer
      // 先置为未激活再挂组件：Cocos 不会给未激活节点跑 onLoad，
      // 于是十七个面板不会在开局一起画满屏背景（也省掉十七份节点池）
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
      // 从抽屉里点了当前这一格：抽屉要收起来，否则玩家选完了屏上还挂着一块板
      if (this.moreOpen) {
        this.setMoreOpen(false)
      }
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
    if (this.moreOpen) {
      // 收起时会重画一次高亮（「更多」那格的选中态跟着 currentKey 走）
      this.setMoreOpen(false)
    } else {
      this.highlight()
    }
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
    this.visibleWidth = size.width
    this.visibleHeight = size.height
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

    // 格子数 = 常驻面板 + 「更多」自己那一格
    const columnWidth = width / (BAR_PANELS.length + 1)
    BAR_PANELS.forEach((def, index) => {
      this.createCell(bar, def.key, def.label,
        -width / 2 + columnWidth * (index + 0.5), 0, columnWidth - 6)
    })
    this.createCell(bar, MORE_KEY, MORE_LABEL,
      -width / 2 + columnWidth * (BAR_PANELS.length + 0.5), 0, columnWidth - 6)
    this.buildMoreLayer(width)
    this.highlight()
  }

  /**
   * 建一格导航。条上与抽屉里共用这一份构造 —— 页签图、字色、红点、点击手感四件事
   * 只有一处实现，抽屉里的格子不会变成"另一套按钮"。
   */
  private createCell(parent: Node, key: string, caption: string,
                     x: number, y: number, width: number): void {
    const height = BAR_HEIGHT - 8
    const button = new Node(`Nav-${key}`)
    button.layer = parent.layer
    parent.addChild(button)
    button.setPosition(new Vec3(x, y, 0))
    button.addComponent(UITransform).setContentSize(new Size(width, height))
    const labelNode = new Node('Caption')
    labelNode.layer = button.layer
    button.addChild(labelNode)
    labelNode.addComponent(UITransform)
    const label = applySystemUiFont(labelNode.addComponent(Label))
    label.string = caption
    label.fontSize = 18
    label.color = COLOR_TEXT_IDLE

    const dot = new Node('NavRedDot')
    dot.layer = button.layer
    button.addChild(dot)
    dot.setPosition(new Vec3(width / 2 - 9, height / 2 - 9, 0))
    dot.addComponent(UITransform).setContentSize(new Size(12, 12))
    const dotGraphics = dot.addComponent(Graphics)
    dotGraphics.fillColor = COLOR_RED_DOT
    dotGraphics.roundRect(-6, -6, 12, 12, 6)
    dotGraphics.fill()
    dot.active = false
    this.navDots.set(key, dot)

    button.on('touch-start', (_event: EventTouch) => {
      if (key === MORE_KEY) {
        this.toggleMore()
        return
      }
      this.show(key)
    }, this)
    this.buttonNodes.set(key, button)
    this.buttonLabels.set(key, label)
    this.cellWidths.set(key, width)
  }

  /**
   * 「更多」抽屉：常驻条之外的 10 个入口。
   *
   * <p>**为什么是抽屉而不是第二排常驻条**：两排条会把可视高度再吃掉 52px，
   * 而各面板的内容区是按"全屏减去一条导航"算的（{@link PanelNav.contentRectFor}）——
   * 加第二排等于把每个面板的内容一起压扁。抽屉只在开着时占地方。
   *
   * <p>**为什么背板不盖住导航条那一条**：盖上去会让"开着抽屉直接换一格"变成
   * "先点空白收起、再点那一格"，原本一下的事变成两下。
   */
  private buildMoreLayer(barWidth: number): void {
    const size = view.getVisibleSize()
    const cellHeight = BAR_HEIGHT - 8
    const rows = Math.max(1, Math.ceil(MORE_PANELS.length / MORE_COLUMNS))
    const cellWidth = (barWidth - MORE_PADDING * 2 - MORE_GAP * (MORE_COLUMNS - 1)) / MORE_COLUMNS
    const plateHeight = MORE_PADDING * 2 + rows * cellHeight + (rows - 1) * MORE_GAP
    const barStrip = 8 + BAR_HEIGHT

    const layer = new Node('NavMoreLayer')
    layer.layer = this.node.layer
    this.node.addChild(layer)
    layer.addComponent(UITransform).setContentSize(new Size(size.width, size.height))
    // 关着就整层不激活：既不画也不吃触摸（背板是全屏的，激活着会把面板的点击全吞掉）
    layer.active = false
    this.moreLayer = layer

    const backdropHeight = size.height - barStrip
    const backdrop = new Node('NavMoreBackdrop')
    backdrop.layer = layer.layer
    layer.addChild(backdrop)
    backdrop.addComponent(UITransform).setContentSize(new Size(size.width, backdropHeight))
    backdrop.setPosition(new Vec3(0, -size.height / 2 + barStrip + backdropHeight / 2, 0))
    backdrop.on('touch-start', (_event: EventTouch) => this.setMoreOpen(false), this)

    const tray = new Node('NavMoreTray')
    tray.layer = layer.layer
    layer.addChild(tray)
    tray.addComponent(UITransform).setContentSize(new Size(barWidth, plateHeight))
    tray.setPosition(new Vec3(0, -size.height / 2 + barStrip + 8 + plateHeight / 2, 0))
    const plate = new Node('TrayBackground')
    plate.layer = tray.layer
    tray.addChild(plate)
    plate.addComponent(UITransform).setContentSize(new Size(barWidth, plateHeight))
    const plateGraphics = plate.addComponent(Graphics)
    plateGraphics.fillColor = COLOR_BAR
    plateGraphics.roundRect(-barWidth / 2, -plateHeight / 2, barWidth, plateHeight, 8)
    plateGraphics.fill()
    plateGraphics.lineWidth = 2
    plateGraphics.strokeColor = COLOR_IDLE
    plateGraphics.roundRect(-barWidth / 2, -plateHeight / 2, barWidth, plateHeight, 8)
    plateGraphics.stroke()

    MORE_PANELS.forEach((def, index) => {
      const column = index % MORE_COLUMNS
      const row = Math.floor(index / MORE_COLUMNS)
      const x = -barWidth / 2 + MORE_PADDING + cellWidth / 2 + column * (cellWidth + MORE_GAP)
      const y = plateHeight / 2 - MORE_PADDING - cellHeight / 2 - row * (cellHeight + MORE_GAP)
      this.createCell(tray, def.key, def.label, x, y, cellWidth)
    })
  }

  private toggleMore(): void {
    this.setMoreOpen(!this.moreOpen)
  }

  private setMoreOpen(open: boolean): void {
    this.moreOpen = open
    if (this.moreLayer !== null) {
      this.moreLayer.active = open
    }
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
    // 抽屉收起时，里面那些入口的红点玩家看不见 ⇒ 在「更多」上合并成一个总点。
    // 判据仍只读服务端那棵树（B12 §4：客户端不参与算红点），这里只做"或"。
    const moreDot = this.navDots.get(MORE_KEY)
    if (moreDot !== undefined) {
      moreDot.active = tree !== null && MORE_PANELS
        .some((def) => def.reddotKey !== null && tree.isLit(def.reddotKey))
    }
  }

  /** 高亮当前项：当前用铜金底 + 深色字，其余保持暗底浅字。 */
  private highlight(): void {
    for (const def of PANELS) {
      this.paintCell(def.key, def.key === this.currentKey)
    }
    // 「更多」自己那一格：抽屉开着、或当前面板就在抽屉里 ⇒ 它代表"你现在在这儿"，要给选中态。
    // 否则从抽屉进了商店，条上七格全暗，玩家看不出自己在哪一屏。
    this.paintCell(MORE_KEY, this.moreOpen || MORE_KEYS.includes(this.currentKey))
  }

  private paintCell(key: string, active: boolean): void {
    const button = this.buttonNodes.get(key)
    const label = this.buttonLabels.get(key)
    if (button === undefined || label === undefined) {
      return
    }
    // 尺寸用建格子时算好的值：UITransform 的尺寸属性名在不同版本间变过（width/height
    // 与 contentSize），这里不依赖它
    const width = this.cellWidths.get(key) ?? 0
    const height = BAR_HEIGHT - 8
    const drawnWithArt = applyNavTab(button, active, width, height)
    // 字色要等"这格实际是什么底"定了再定：先设色再画图，选中态就是深色字压在深色底上
    label.color = drawnWithArt && active ? COLOR_TEXT_ACTIVE_ON_ART
      : (active ? COLOR_TEXT_ACTIVE : COLOR_TEXT_IDLE)
    if (drawnWithArt) {
      return
    }
    const graphics = button.getComponent(Graphics) ?? button.addComponent(Graphics)
    graphics.clear()
    graphics.fillColor = active ? COLOR_ACTIVE : COLOR_IDLE
    graphics.roundRect(-width / 2, -height / 2, width, height, 6)
    graphics.fill()
  }

  override update(): void {
    const size = view.getVisibleSize()
    if (size.width === this.visibleWidth && size.height === this.visibleHeight) return
    for (const node of this.panelNodes.values()) {
      node.getComponent(UITransform)?.setContentSize(new Size(size.width, size.height))
    }
    const open = this.moreOpen
    this.node.getChildByName('NavBar')?.destroy()
    this.moreLayer?.destroy()
    this.moreLayer = null
    this.buttonNodes.clear()
    this.buttonLabels.clear()
    this.navDots.clear()
    this.cellWidths.clear()
    this.buildBar()
    this.setMoreOpen(open)
    this.refreshNavDots()
  }

  override onDestroy(): void {
    this.panelNodes.clear()
    this.buttonNodes.clear()
    this.buttonLabels.clear()
    this.navDots.clear()
    this.cellWidths.clear()
    this.moreLayer = null
    this.reddot = null
    this.onShow = null
  }
}
