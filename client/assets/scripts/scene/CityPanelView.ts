/**
 * 职责：城建面板 —— 6×6 城内网格、建筑详情、升级、加速、收割（B03 §2/§3/§4）。
 * 依赖：cc（渲染）、game/city/CityPanel（展示数据组装，已单测）。
 *
 * <p>平面列表回答不了“我的城现在长什么样”，所以建筑按服务端下发的 gridX/gridY 放置。
 * 本场景仍然不做任何数值判断：升级、加速、收割全部由现有回调交给 AppRoot 和服务端裁定。
 */

import {
  _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, Sprite, UITransform, Vec3, sys, view,
} from 'cc'
import {
  CITY_GRID_HEIGHT, CITY_GRID_WIDTH, buildCityGrid, buildCityPanel, collectMessage, errorText,
} from '../game/city/CityPanel'
import type { BuildingRow, CityGrid, CityPanelView as CityPanelData } from '../game/city/CityPanel'
import { buildBuildChoices } from '../game/session/Choices'
import type {
  CityCollectResp, CityListResp, ErrorDetail, SpeedUpSource,
} from '../net/generated/CityProtocol'
import { ChoiceOverlay } from './ChoiceOverlay'
import {
  applyAnyIconSprite, applyCommandButton, applyIconSprite, applySimpleSprite,
  applySlicedSprite, applyTiledSprite, buildingIconKey, ensureFamily, familyFrame,
} from './ArtCatalog'
import { buildingArtKey, PANEL_FRAME_BAND } from '../game/art/ArtFamilies'
import { applySystemUiFont } from './UiFont'
import {
  DISTRICT_TINT_RGB, SCENE_RUNTIME_HEIGHT, SCENE_RUNTIME_WIDTH,
  SCENE_STAGE_HEIGHT, SCENE_STAGE_WIDTH, projectSceneLayout,
} from '../game/city/CitySceneAnchors'
import type { ProjectedPlate, SceneDistrict, SceneLayout } from '../game/city/CitySceneAnchors'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
/**
 * 建筑正稿的**分离描边**（同一张图放大一圈垫在下面）。
 *
 * <p>为什么需要它而不是换台基色：15 类正稿按通道均值量，仓库对 idle 台基 `(58,46,36)`
 * 只差 **4.1**、兵营 5.8、铁矿 6.0 —— 四栋暗房子压在自己的暗底座上糊成一片。
 * 但采石场很亮、仓库很暗分处两端，**没有任何一档暗色台基能同时拉开两者**
 * （把台基限制在面板暗色系里搜过，最优也只有 21.2，仍不到 25）。
 * 所以分离靠**边缘**而不是靠底色 —— 这也是同类 SLG 的通用做法。
 *
 * <p>取暖石亮色 + 半透明：它同时要对上"很亮的采石场"和"很暗的仓库"，
 * 而它自己压在三种台基上的最差对比是 100+（见 `tools` 里那条读数），
 * 不会把问题从"房子对底座"挪成"描边对底座"。
 */
const COLOR_ART_RIM = new Color(238, 222, 188, 150)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_WARNING = new Color(200, 96, 64, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)

/**
 * 五区地皮色。色值真源在 `CitySceneAnchors.DISTRICT_TINT_RGB`（引擎无关层）。
 * 运行时只画大块地势，不再逐格铺方块；区只决定环境色，不否决建筑。
 */
const GROUND_BY_DISTRICT = Object.fromEntries(
  (Object.keys(DISTRICT_TINT_RGB) as SceneDistrict[])
    .map((district) => [district, new Color(...DISTRICT_TINT_RGB[district], 28)]),
) as Readonly<Record<SceneDistrict, Color>>
const CONTENT_WIDTH_FALLBACK = SCENE_RUNTIME_WIDTH
const CONTENT_HEIGHT_FALLBACK = SCENE_RUNTIME_HEIGHT
const ROAD_COLOR = new Color(76, 66, 51, 225)
const PLAZA_COLOR = new Color(111, 99, 78, 225)
const WALL_SHADOW = new Color(18, 14, 12, 150)
const COTTAGE_WALL = new Color(76, 63, 49, 235)
const COTTAGE_ROOF = new Color(42, 45, 49, 245)
const TREE_COLOR = new Color(35, 55, 34, 220)
const PREVIEW_TINT = new Color(238, 226, 204, 222)

/**
 * 未建建筑的规划预览只决定“这座城看起来哪里像什么”，不参与 placement：
 * key 是 building.json 的 configId，值时 [gridX, gridY, iconScale]。
 * 真正的 placement 仍由服务器的 gridX/gridY 决定；一旦该 configId 已建，预览消失。
 */
const BUILDING_PREVIEWS: Readonly<Record<string, readonly [number, number, number]>> = {
  lumber_camp: [1, 1, 0.92],
  quarry: [0, 3, 0.92],
  farm: [0, 5, 0.96],
  iron_mine: [1, 3, 0.92],
  warehouse: [4, 3, 0.96],
  barracks: [1, 2, 0.98],
  stable: [1, 4, 0.95],
  archery_range: [0, 4, 0.96],
  siege_workshop: [0, 1, 0.95],
  drill_ground: [1, 5, 0.92],
  hospital: [4, 1, 0.94],
  wall: [2, 5, 0.90],
  academy: [3, 2, 0.98],
  embassy: [4, 2, 0.96],
}

function buildingIconSize(configId: string, plateWidth: number): number {
  const factors: Readonly<Record<string, number>> = {
    main_city: 2.30,
    wall: 1.25,
    warehouse: 1.68,
    barracks: 1.72,
    stable: 1.68,
    academy: 1.72,
    embassy: 1.68,
    farm: 1.72,
    drill_ground: 1.76,
  }
  const upper = configId === 'main_city' ? 300 : 185
  return Math.max(76, Math.min(upper, plateWidth * (factors[configId] ?? 1.66)))
}

function paintCottage(graphics: Graphics, x: number, y: number,
                      width: number, height: number): void {
  graphics.fillColor = COTTAGE_WALL
  graphics.rect(x - width / 2, y, width, height * 0.52)
  graphics.fill()
  graphics.fillColor = COTTAGE_ROOF
  graphics.moveTo(x - width * 0.58, y + height * 0.52)
  graphics.lineTo(x, y + height)
  graphics.lineTo(x + width * 0.58, y + height * 0.52)
  graphics.close()
  graphics.fill()
}

function paintTree(graphics: Graphics, x: number, y: number, size: number): void {
  graphics.fillColor = TREE_COLOR
  graphics.circle(x, y + size * 0.45, size * 0.55)
  graphics.circle(x - size * 0.35, y + size * 0.2, size * 0.42)
  graphics.circle(x + size * 0.35, y + size * 0.2, size * 0.42)
  graphics.fill()
}

function paintTent(graphics: Graphics, x: number, y: number,
                   width: number, height: number): void {
  graphics.fillColor = new Color(88, 56, 42, 230)
  graphics.rect(x - width / 2, y, width, height * 0.42)
  graphics.fill()
  graphics.fillColor = new Color(122, 48, 42, 238)
  graphics.moveTo(x - width * 0.62, y + height * 0.42)
  graphics.lineTo(x, y + height)
  graphics.lineTo(x + width * 0.62, y + height * 0.42)
  graphics.close()
  graphics.fill()
}

function paintRoad(graphics: Graphics, points: readonly (readonly [number, number])[],
                   width: number): void {
  const first = points[0]
  if (first === undefined) {
    return
  }
  graphics.strokeColor = ROAD_COLOR
  graphics.lineWidth = width
  graphics.moveTo(first[0], first[1])
  for (const point of points.slice(1)) {
    graphics.lineTo(point[0], point[1])
  }
  graphics.stroke()
}
/**
 * 36 格地皮投影进**整块可视区**（不再是卡片里的一个子矩形）。
 *
 * <p>为什么必须满屏：底图是铺满全屏的，而锚点原先只铺在 1240×560 的卡片内容区里，
 * 于是屏幕中部的热区永远对不上画面上真实建筑的位置（审计 §2.3）。
 * 现在两者都吃 `view.getVisibleSize()`，热区才能跟着底图走。
 */
const BASE_SCENE_LAYOUT = projectSceneLayout(SCENE_STAGE_WIDTH, SCENE_STAGE_HEIGHT, 6)

/**
 * 面板框的四角尺寸：**切分几何的唯一真源是 `ui/generated/ui/panel-kingdom-v1.png.meta` 的 border***，
 * 这里的常量只是把它交给布局用（两者由 `tests/ArtFamilies.test.ts` 对账，不一致就红）。
 */
const FRAME_BAND = PANEL_FRAME_BAND
/**
 * 顶部信息条的渐变高度。
 *
 * <p>从 128 压到 104：`p0x` 那行锚点最低在 y≈18%（物理 162），而 128 设计（物理 192）
 * 会把城郊生产带的建筑压在渐变底下 —— 实测伐木场建在那一带时几乎整栋被吞掉。
 * 压到 104 后渐变止于物理 156，那条带能露出来。
 */
const HEADER_HEIGHT = 104
const ACTION_HEIGHT = 76
const ACTION_BUTTON_WIDTH = 82
const ACTION_BUTTON_HEIGHT = 32
/**
 * 底部导航条（PanelNav）占的高度（设计分辨率下的估算）。
 * 选择栏与提示文案要落在它上方 —— 否则会像审计里那条"底部导航盖住城门"一样互相压。
 */
const NAV_BAR_HEIGHT = 76

type RowAction = 'build' | 'upgrade' | 'speedAd' | 'speedGold' | 'collect' | 'pause' | 'resume'

interface GridTileRefs {
  readonly node: Node
  readonly graphics: Graphics
  /** 浅色描边：画在 `icon` 底下、同一张图放大一圈。见 COLOR_ART_RIM 为什么存在。 */
  readonly iconRim: Node
  readonly icon: Node
  readonly levelLabel: Label
  /**
   * 建筑名。#257 曾把它和状态一起收进选择栏，理由是"脚印只有 24~45 高塞不下三行字" ——
   * 三行确实塞不下，但**一行**塞得下，而收进选择栏换来两个代价：
   * ① 36 格再也看不出"哪栋是哪栋"，要点一下才知道；② 显示名这条链路（#255 整格在修的东西）
   *    变成"必须点中才可见"，量具点不动格子时就完全无法验收 —— 本轮就是这么暴露的。
   * 状态仍留在选择栏（它本来就有描边 / 进度条 / 暂停点三种非文字表达）。
   */
  readonly nameLabel: Label
  /** 这一格投影后的落点与尺寸；视图照抄，不再自己算坐标。 */
  readonly plate: ProjectedPlate
}

@ccclass('CityPanelView')
export class CityPanelView extends Component {
  private resp: CityListResp | null = null
  private panel: CityPanelData | null = null
  private offsetMs = 0
  private lastRenderedSecond = -1
  private pending: CityListResp | null = null

  private card: Node | null = null
  private backgroundNode: Node | null = null
  /** 上一次布局用的视口尺寸；与当前不符就整棵重建（resize 不重排是审计 §5.2 的硬缺陷）。 */
  private lastViewWidth = 0
  private lastViewHeight = 0
  private referenceStage = false
  private frameGraphics: Graphics | null = null
  /** 城景内容区 = 整块可视区；onLoad 时按 `view.getVisibleSize()` 定，回退值见常量。 */
  private contentWidth = CONTENT_WIDTH_FALLBACK
  private contentHeight = CONTENT_HEIGHT_FALLBACK
  /** 36 格落点；与 `contentWidth/Height` 同源，参考图模式下它就是建筑与热区的唯一位置来源。 */
  private sceneLayout: SceneLayout =
    projectSceneLayout(CONTENT_WIDTH_FALLBACK, CONTENT_HEIGHT_FALLBACK, 0)
  /** 程序化装饰层的缩放（只在没有参考图时用）。 */
  private decorScale = 1
  private headerLabel: Label | null = null
  private queueLabel: Label | null = null
  private readonly resourceLabels: Label[] = []
  private messageLabel: Label | null = null

  private readonly gridTiles: GridTileRefs[] = []
  private readonly previewNodes = new Map<string, Node>()
  private selectedId: string | null = null
  private buildMode = false
  private selectedTitle: Label | null = null
  private selectedStatus: Label | null = null
  private selectionBar: Node | null = null
  private selectionBarBackground: Node | null = null
  private readonly actionButtons = new Map<Node, RowAction>()
  private buildPicker: ChoiceOverlay | null = null

  onUpgrade: ((configId: string, gridX?: number, gridY?: number) => void) | null = null
  onSpeedUp: ((buildingId: string, source: SpeedUpSource) => void) | null = null
  onCollect: ((buildingId: string | null) => void) | null = null
  /** 暂停/恢复升级（B03 §2）。两个回调分开：面板不做"当前该发哪个"的判断，状态由服务端说了算。 */
  onPause: ((buildingId: string) => void) | null = null
  onResume: ((buildingId: string) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.setup(size.width, size.height)
    this.lastViewWidth = size.width
    this.lastViewHeight = size.height
    // 建筑正稿族按需拉取：先画一帧图集小图标，正稿到了补一帧；拉不到就停在图集上。
    // 不进启动预载。主城 512px、其余 256px 共约 396KB，放在 resources 分包，
    // 换掉旧 128px 是为了让自由城景里主堡像地标，而不是一个缩略图标。
    ensureFamily('building').then((loaded) => {
      if (loaded > 0 && this.isValid) {
        this.syncPreviews()
        this.render()
      }
    })
    this.renderGrid(buildCityGrid([]))
    this.renderSelection(null)

    if (this.pending !== null) {
      const pending = this.pending
      this.pending = null
      this.attach(pending, this.offsetMs)
    }
  }

  /**
   * 按一块可视区把面板整棵搭起来。抽成方法是为了 resize 时能原样重建 ——
   * 内容区尺寸、锚点投影、背景底图、顶部渐变、选择栏位置全都依赖它，
   * 散在 `onLoad` 里就没法重跑（姊妹审计 §5.2 记的硬缺陷）。
   */
  private setup(width: number, height: number): void {
    this.contentWidth = width
    this.contentHeight = height
    this.sceneLayout = projectSceneLayout(width, height, 0)
    this.decorScale = this.sceneLayout.scale / BASE_SCENE_LAYOUT.scale
    this.buildBackground(width, height)
    const card = new Node('Card')
    card.layer = this.node.layer
    this.node.addChild(card)
    card.addComponent(UITransform).setContentSize(new Size(width, height))
    this.card = card
    this.buildCard()
    this.layoutCard()
    this.buildPicker = new ChoiceOverlay(card, '选择要建造的建筑', width - 24)
  }

  /** 拆掉 `setup` 建的一切并清空引用；不碰 `resp`/`panel`，它们由调用方决定去留。 */
  private teardown(): void {
    this.buildPicker?.hide()
    this.buildPicker = null
    this.backgroundNode?.destroy()
    this.backgroundNode = null
    this.card?.destroy()
    this.card = null
    this.gridTiles.length = 0
    this.previewNodes.clear()
    this.actionButtons.clear()
    this.resourceLabels.length = 0
    this.headerLabel = null
    this.queueLabel = null
    this.messageLabel = null
    this.selectedTitle = null
    this.selectedStatus = null
    this.selectionBar = null
    this.selectionBarBackground = null
    this.frameGraphics = null
    this.referenceStage = false
  }

  /**
   * 视口尺寸变了就按新尺寸整棵重建。
   *
   * <p>为什么在 `update` 里查而不是监听事件：Cocos 3.8 的 resize 事件在不同平台名字不一，
   * 而这里本来就有逐帧回调，比较两个数字比赌事件名可靠。比较放在秒级早退**之前**，
   * 否则 resize 要等下一次重绘才生效。
   */
  private relayoutIfResized(): void {
    const size = view.getVisibleSize()
    if (size.width === this.lastViewWidth && size.height === this.lastViewHeight) {
      return
    }
    this.lastViewWidth = size.width
    this.lastViewHeight = size.height
    this.teardown()
    this.setup(size.width, size.height)
    // 重建成默认帧再按现有数据补一帧：选中态由 `render` 依 `selectedId` 自动恢复，
    // 因为 `render` 只保留仍在 `panel.rows` 里的选中项。
    this.renderGrid(buildCityGrid([]))
    this.renderSelection(null)
    this.lastRenderedSecond = -1
    if (this.resp !== null) {
      this.panel = buildCityPanel(this.resp, this.offsetMs, sys.now())
      this.render()
    }
  }

  override onDestroy(): void {
    this.gridTiles.length = 0
    this.previewNodes.clear()
    this.actionButtons.clear()
    this.buildPicker?.hide()
    this.buildPicker = null
    this.onUpgrade = null
    this.onSpeedUp = null
    this.onCollect = null
  }

  attach(resp: CityListResp, offsetMs: number): void {
    this.offsetMs = offsetMs
    if (this.card === null) {
      this.pending = resp
      return
    }
    this.resp = resp
    this.buildMode = false
    this.panel = buildCityPanel(resp, offsetMs, sys.now())
    this.lastRenderedSecond = -1
    this.render()
  }

  updateOffset(offsetMs: number): void {
    this.offsetMs = offsetMs
  }

  attachCollect(resp: CityCollectResp): void {
    const message = collectMessage(resp)
    if (message === null) {
      return
    }
    this.showMessage(message.text, message.kind === 'done' ? COLOR_GOOD : COLOR_TEXT_DIM)
  }

  showError(detail: ErrorDetail | null, fallback: string): void {
    this.showMessage(errorText(detail, fallback), COLOR_WARNING)
  }

  override update(): void {
    // 放在秒级早退之前：resize 要立刻生效，不能等下一次整帧重绘
    this.relayoutIfResized()
    const resp = this.resp
    if (resp === null) {
      return
    }
    const now = sys.now()
    const second = Math.floor(now / 1000)
    if (second === this.lastRenderedSecond) {
      return
    }
    this.panel = buildCityPanel(resp, this.offsetMs, now)
    this.render()
  }

  private buildBackground(width: number, height: number): void {
    const node = new Node('Background')
    node.layer = this.node.layer
    this.node.addChild(node)
    this.backgroundNode = node
    node.addComponent(UITransform).setContentSize(new Size(width, height))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()

    const reference = new Node('CityReferenceScene')
    reference.layer = node.layer
    node.addChild(reference)
    reference.addComponent(UITransform).setContentSize(new Size(width, height))
    if (applySimpleSprite(reference, 'city.scene.reference', width, height)) {
      this.referenceStage = true
      return
    }

    // 参考图是满屏城景：地表先铺满全屏，远山再压在上半部，城内只叠道路与建筑。
    const ground = new Node('CityGround')
    ground.layer = node.layer
    node.addChild(ground)
    ground.addComponent(UITransform).setContentSize(new Size(width, height))
    applyTiledSprite(ground, 'city.ground', width, height)
    const groundSprite = ground.getComponent(Sprite)
    if (groundSprite !== null) {
      groundSprite.color = new Color(205, 198, 178, 205)
    }

    const ridgeWidth = width * 1.02
    const ridgeHeight = ridgeWidth * 525 / 1344
    const ridge = new Node('CityRidge')
    ridge.layer = node.layer
    node.addChild(ridge)
    ridge.setPosition(new Vec3(0, height / 2 - ridgeHeight / 2 + 72, 0))
    applySimpleSprite(ridge, 'city.ridge', ridgeWidth, ridgeHeight)
    const ridgeSprite = ridge.getComponent(Sprite)
    if (ridgeSprite !== null) {
      ridgeSprite.color = new Color(255, 255, 255, 142)
    }
  }

  private buildCard(): void {
    const card = this.card
    if (card === null) {
      return
    }
    const frame = new Node('CardFrame')
    frame.layer = card.layer
    card.addChild(frame)
    frame.addComponent(UITransform).setContentSize(new Size(this.contentWidth, this.contentHeight))
    frame.active = false
    this.frameGraphics = null

    // **先建城景，再把 HUD 叠上去**。反过来的话，玩家在画面上方盖的楼会把自己的资源数字挡掉 ——
    // 实测伐木场建在 y≈21% 时正好压住「铁矿 2000/10000」那一行。
    // Cocos 按子节点次序绘制，所以这里的添加顺序就是层级。
    this.buildGrid(card)
    this.buildActionBar(card)

    // 顶部信息条的底板：**分段渐变而不是一块硬黑边**。
    // 审计里"主堡塔尖被顶部黑条切断"那条就是硬边造成的 —— 分段递增透明度后底边融进城景，
    // 仍然给资源文字足够的对比底，但不再把天空整个压掉。
    const topShade = new Node('TopShade')
    topShade.layer = card.layer
    card.addChild(topShade)
    topShade.addComponent(UITransform).setContentSize(new Size(this.contentWidth, HEADER_HEIGHT))
    const shade = topShade.addComponent(Graphics)
    const steps = 5
    const bandHeight = HEADER_HEIGHT / steps
    for (let i = 0; i < steps; i++) {
      shade.fillColor = new Color(14, 11, 9, 196 - i * 34)
      shade.rect(-this.contentWidth / 2,
        this.contentHeight / 2 - bandHeight * (i + 1), this.contentWidth, bandHeight + 1)
      shade.fill()
    }

    const top = this.contentHeight / 2 - 18
    this.headerLabel = this.addLabel(card, 'Header', 0, top - 14, COLOR_COPPER_GOLD, 22)
    this.queueLabel = this.addLabel(card, 'Queue', 0, top - 38, COLOR_TEXT, 16)

    // 资源条只占左侧一块，不横跨整屏 —— 满屏城景下横跨会把城压成一条缝。
    const columnWidth = Math.min(210, this.contentWidth / 4)
    for (let row = 0; row < 2; row++) {
      for (let column = 0; column < 3; column++) {
        // 左对齐 + 限定列宽：数值位数由服务端算，不可控（"1000000" 和 "200" 同栏）。
        this.resourceLabels.push(this.addLabel(
          card, `Resource-${row}-${column}`,
          -this.contentWidth / 2 + 20 + columnWidth * column, top - 62 - row * 18,
          COLOR_TEXT_DIM, 14, true, columnWidth - 8))
      }
    }

    this.messageLabel = this.addLabel(card, 'Message', 0,
      -this.contentHeight / 2 + NAV_BAR_HEIGHT + ACTION_HEIGHT + 26, COLOR_WARNING, 15)
  }

  private buildGrid(parent: Node): void {
    const grid = new Node('CityGrid')
    grid.layer = parent.layer
    parent.addChild(grid)
    grid.addComponent(UITransform).setContentSize(new Size(this.contentWidth, this.contentHeight))
    // 满屏：内容区与可视区重合，锚点落点才是底图上建筑的真实屏幕位置
    grid.setPosition(new Vec3(0, 0, 0))
    this.buildGround(grid)
    this.buildPreviews(grid)
    // 按格位排序后再挂：Cocos 按子节点次序绘制，靠城门（y 大）的格子要后画才压得住前面的
    const ordered = [...this.sceneLayout.plates]
      .sort((a, b) => (a.gridY * CITY_GRID_WIDTH + a.gridX) - (b.gridY * CITY_GRID_WIDTH + b.gridX))
    for (const plate of ordered) {
      const index = plate.gridY * CITY_GRID_WIDTH + plate.gridX
      const tile = new Node(`Grid-${index}`)
      tile.layer = grid.layer
      grid.addChild(tile)
      tile.setPosition(new Vec3(plate.x, plate.y, 0))
      // 命中盒只包住基座附近；透明屋顶不抢相邻建筑的点击。
      tile.addComponent(UITransform).setContentSize(
        new Size(Math.max(46, plate.width), Math.max(42, plate.height)))
      const graphics = tile.addComponent(Graphics)
      // 描边先挂、正稿后挂 ⇒ 同一父节点下描边在正稿之后绘制不到它上面去（Cocos 按子节点次序画）
      const iconRim = new Node('BuildingRim')
      iconRim.layer = tile.layer
      tile.addChild(iconRim)
      iconRim.setPosition(new Vec3(0, -2, 0))
      const rimBox = iconRim.addComponent(UITransform)
      rimBox.setAnchorPoint(0.5, 0)
      const icon = new Node('BuildingIcon')
      icon.layer = tile.layer
      tile.addChild(icon)
      // 图标底边就是锚点：建筑向上长，名字压在基座下，场景不再显示矩形脚印。
      icon.setPosition(new Vec3(0, 0, 0))
      const iconBox = icon.addComponent(UITransform)
      iconBox.setAnchorPoint(0.5, 0)
      iconBox.setContentSize(new Size(plate.width, plate.width))
      const levelLabel = this.addLabel(tile, 'Level', 0, 0, COLOR_TEXT_DIM, 10)
      levelLabel.node.getComponent(UITransform)?.setContentSize(new Size(20, 14))
      levelLabel.overflow = Label.Overflow.SHRINK
      const nameLabel = this.addLabel(tile, 'Name', 0, -12, COLOR_TEXT, 10)
      nameLabel.node.getComponent(UITransform)?.setContentSize(new Size(plate.width, 12))
      nameLabel.overflow = Label.Overflow.SHRINK
      this.gridTiles.push({ node: tile, graphics, iconRim, icon, levelLabel, nameLabel, plate })
    }
  }

  private buildPreviews(grid: Node): void {
    if (this.referenceStage) {
      return
    }
    for (const [configId, [gridX, gridY]] of Object.entries(BUILDING_PREVIEWS)) {
      const plate = this.sceneLayout.plates.find((entry) =>
        entry.gridX === gridX && entry.gridY === gridY)
      if (plate === undefined) {
        continue
      }
      const node = new Node(`Plan-${configId}`)
      node.layer = grid.layer
      grid.addChild(node)
      node.setPosition(new Vec3(plate.x, plate.y - 2, 0))
      const box = node.addComponent(UITransform)
      box.setAnchorPoint(0.5, 0)
      node.active = false
      this.previewNodes.set(configId, node)
    }
  }

  private syncPreviews(): void {
    if (this.referenceStage) {
      for (const node of this.previewNodes.values()) {
        node.active = false
      }
      return
    }
    const built = new Set(this.panel?.rows.map((row) => row.configId) ?? [])
    const planned = new Set(this.panel?.buildOptions.map((option) => option.configId) ?? [])
    for (const [configId, node] of this.previewNodes) {
      const ref = BUILDING_PREVIEWS[configId]
      const artKey = buildingArtKey(configId)
      const plate = ref === undefined ? undefined : this.sceneLayout.plates.find((entry) =>
        entry.gridX === ref[0] && entry.gridY === ref[1])
      if (ref === undefined || plate === undefined || artKey === null
          || built.has(configId) || !planned.has(configId)) {
        node.active = false
        continue
      }
      const size = buildingIconSize(configId, plate.width) * ref[2] * 1.18
      const shown = applyAnyIconSprite(node, artKey, size, size)
      const sprite = node.getComponent(Sprite)
      if (sprite !== null) {
        sprite.color = PREVIEW_TINT
      }
      node.active = shown
    }
  }

  /**
   * 城界地基：同类 SLG 的城界语言 —— 先有"地皮"，建筑才是"盖在上面"。
   * 之前格子直接浮在面板底色上，读起来像表格而不像一座城。
   */
  private buildGround(grid: Node): void {
    const ground = new Node('Ground')
    ground.layer = grid.layer
    grid.addChild(ground)
    ground.addComponent(UITransform)
    if (this.referenceStage) {
      return
    }
    ground.setScale(new Vec3(this.decorScale, this.decorScale, 1))
    const graphics = ground.addComponent(Graphics)
    const byDistrict = new Map<SceneDistrict, ProjectedPlate[]>()
    for (const plate of this.sceneLayout.plates) {
      const list = byDistrict.get(plate.district) ?? []
      list.push(plate)
      byDistrict.set(plate.district, list)
    }
    for (const [district, plates] of byDistrict) {
      const cx = plates.reduce((sum, p) => sum + p.x, 0) / plates.length
      const cy = plates.reduce((sum, p) => sum + p.y, 0) / plates.length
      const minX = Math.min(...plates.map((p) => p.x - p.width / 2))
      const maxX = Math.max(...plates.map((p) => p.x + p.width / 2))
      const minY = Math.min(...plates.map((p) => p.y - p.height))
      const maxY = Math.max(...plates.map((p) => p.y + p.height))
      graphics.fillColor = GROUND_BY_DISTRICT[district]
      graphics.ellipse(cx, cy, (maxX - minX) * 0.32 + 24,
        (maxY - minY) * 0.32 + 22)
      graphics.fill()
    }

    // 主轴和支路就是“像城”的第一语言，不能再靠格线暗示地块。
    paintRoad(graphics, [
      [-18, 196], [-6, 140], [12, 88], [-2, 42], [4, -8], [-8, -76], [8, -142], [0, -184],
    ], 28)
    paintRoad(graphics, [[0, 32], [-105, 30], [-190, 4], [-270, -48]], 12)
    paintRoad(graphics, [[0, 32], [105, 30], [190, 4], [270, -48]], 12)
    paintRoad(graphics, [[0, -52], [-100, -78], [-185, -126], [-250, -166]], 11)
    paintRoad(graphics, [[0, -52], [100, -78], [185, -126], [250, -166]], 11)
    graphics.fillColor = PLAZA_COLOR
    graphics.ellipse(0, 8, 82, 40)
    graphics.fill()
    graphics.strokeColor = new Color(174, 154, 117, 175)
    graphics.lineWidth = 4
    graphics.ellipse(0, 8, 82, 40)
    graphics.stroke()
    graphics.fillColor = new Color(143, 129, 101, 245)
    graphics.ellipse(0, 8, 54, 26)
    graphics.fill()
    graphics.fillColor = new Color(182, 171, 145, 250)
    graphics.circle(0, 12, 13)
    graphics.fill()
    graphics.fillColor = new Color(68, 65, 59, 245)
    graphics.circle(0, 12, 6)
    graphics.fill()
    for (const [x, y, w, h] of [
      [-105, -8, 34, 22], [-70, -42, 30, 20], [66, -42, 31, 21],
      [102, -8, 34, 22], [-88, 40, 30, 20], [84, 40, 31, 21],
    ] as const) {
      paintTent(graphics, x, y, w, h)
    }

    // 非功能民居只负责把街巷串成一座城，点击与存档都不消费它们。
    for (const [x, y, w, h] of [
      [-318, 138, 32, 24], [-276, 112, 38, 27], [-244, 74, 31, 23],
      [-320, 34, 35, 25], [-278, 4, 30, 22], [-226, -24, 36, 26],
      [282, 138, 35, 25], [322, 110, 31, 23], [251, 76, 38, 27],
      [318, 38, 33, 24], [278, 4, 30, 22], [232, -26, 36, 26],
      [-292, -102, 34, 24], [-246, -130, 31, 22],
      [258, -104, 34, 24], [302, -132, 31, 22],
    ] as const) {
      paintCottage(graphics, x, y, w, h)
    }
    for (const [x, y, size] of [
      [-360, 166, 24], [-340, 74, 20], [-350, -48, 22],
      [342, 164, 25], [356, 70, 21], [344, -48, 22],
      [-205, 170, 18], [210, 170, 18],
    ] as const) {
      paintTree(graphics, x, y, size)
    }

    // 底部城门是主轴终点。两侧各留一段短墙，把城门框住但不遮住民居。
    const westWall = new Node('CityWallWest')
    westWall.layer = grid.layer
    grid.addChild(westWall)
    westWall.setPosition(new Vec3(-252 * this.decorScale, -128 * this.decorScale, 0))
    westWall.angle = 7
    applySimpleSprite(westWall, 'city.wall', 238 * this.decorScale, 96 * this.decorScale)
    const eastWall = new Node('CityWallEast')
    eastWall.layer = grid.layer
    grid.addChild(eastWall)
    eastWall.setPosition(new Vec3(252 * this.decorScale, -128 * this.decorScale, 0))
    eastWall.angle = -7
    applySimpleSprite(eastWall, 'city.wall', 238 * this.decorScale, 96 * this.decorScale)

    const gate = new Node('CityGateWall')
    gate.layer = grid.layer
    grid.addChild(gate)
    gate.setPosition(new Vec3(0, -this.contentHeight / 2 + 82 * this.decorScale, 0))
    applySimpleSprite(gate, 'city.wall', 300 * this.decorScale, 121 * this.decorScale)
    graphics.fillColor = WALL_SHADOW
    graphics.ellipse(0, -this.contentHeight / 2 + 76 * this.decorScale,
      160 * this.decorScale, 18 * this.decorScale)
    graphics.fill()
  }

  private buildActionBar(parent: Node): void {
    const bar = new Node('SelectionBar')
    bar.layer = parent.layer
    parent.addChild(bar)
    this.selectionBar = bar
    bar.addComponent(UITransform).setContentSize(new Size(this.contentWidth, ACTION_HEIGHT))
    // 落在底部导航条之上：满屏城景后不能再按卡片高度算，否则会被导航压住
    bar.setPosition(new Vec3(0, -this.contentHeight / 2 + NAV_BAR_HEIGHT + ACTION_HEIGHT / 2, 0))
    const background = new Node('SelectionBarBackground')
    background.layer = bar.layer
    bar.addChild(background)
    background.addComponent(UITransform).setContentSize(new Size(this.contentWidth, ACTION_HEIGHT))
    this.selectionBarBackground = background
    const graphics = background.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 1
    graphics.roundRect(-this.contentWidth / 2, -ACTION_HEIGHT / 2,
      this.contentWidth, ACTION_HEIGHT, 8)
    graphics.fill()
    graphics.stroke()

    const left = -this.contentWidth / 2 + 12
    this.selectedTitle = this.addLabel(bar, 'SelectedTitle', left, 16, COLOR_COPPER_GOLD, 16, true, 220)
    this.selectedStatus = this.addLabel(bar, 'SelectedStatus', left, -8, COLOR_TEXT_DIM, 13, true, 220)
    this.selectedStatus.overflow = Label.Overflow.SHRINK

    this.createActionButton(bar, 'DetailBuildButton', '建造', 0, 'build')
    this.createActionButton(bar, 'DetailUpgradeButton', '升级', -12, 'upgrade')
    this.createActionButton(bar, 'DetailCollectButton', '收割', -12, 'collect')
    this.createActionButton(bar, 'DetailSpeedAdButton', '广告加速', 78, 'speedAd')
    this.createActionButton(bar, 'DetailSpeedGoldButton', '金币加速', 168, 'speedGold')
    // 暂停/恢复共用同一个 x：同一时刻只可能显示一个（升级中才可暂停、已暂停才能恢复），
    // 与「升级 / 收割」共用 -12 是同一个做法。
    this.createActionButton(bar, 'DetailPauseButton', '暂停', 258, 'pause')
    this.createActionButton(bar, 'DetailResumeButton', '恢复', 258, 'resume')

    const collectAll = new Node('CollectAllButton')
    collectAll.layer = parent.layer
    parent.addChild(collectAll)
    // 贴右上角，避开顶部渐变的文字带
    collectAll.setPosition(new Vec3(
      this.contentWidth / 2 - FRAME_BAND - 76, this.contentHeight / 2 - FRAME_BAND - 4, 0))
    collectAll.addComponent(UITransform).setContentSize(new Size(132, 34))
    if (!applyCommandButton(collectAll, 'normal', 132, 34)) {
      const collectGraphics = collectAll.addComponent(Graphics)
      collectGraphics.fillColor = COLOR_PANEL
      collectGraphics.strokeColor = COLOR_COPPER_GOLD
      collectGraphics.lineWidth = 2
      collectGraphics.roundRect(-66, -17, 132, 34, 6)
      collectGraphics.fill()
      collectGraphics.stroke()
    }
    this.addLabel(collectAll, 'Caption', 0, 0, COLOR_TEXT, 15).string = '一键收割'
    collectAll.on('touch-start', (_event: EventTouch) => this.onCollect?.(null), this)
  }

  private createActionButton(parent: Node, name: string, text: string, x: number,
                             kind: RowAction): void {
    const button = new Node(name)
    button.layer = parent.layer
    parent.addChild(button)
    button.setPosition(new Vec3(x, -20, 0))
    button.addComponent(UITransform).setContentSize(new Size(ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT))
    if (!applyCommandButton(button, 'normal', ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT)) {
      const graphics = button.addComponent(Graphics)
      graphics.fillColor = COLOR_PANEL
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 1
      graphics.roundRect(-ACTION_BUTTON_WIDTH / 2, -ACTION_BUTTON_HEIGHT / 2,
        ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT, 5)
      graphics.fill()
      graphics.stroke()
    }
    const caption = this.addLabel(button, 'Caption', 0, 0, COLOR_TEXT, 12)
    caption.string = text
    caption.overflow = Label.Overflow.SHRINK
    button.active = false
    this.actionButtons.set(button, kind)
  }

  private layoutCard(): void {
    const card = this.card
    if (card === null) {
      return
    }
    card.getComponent(UITransform)?.setContentSize(new Size(this.contentWidth, this.contentHeight))
    const frame = this.frameGraphics
    if (frame !== null) {
      frame.clear()
      frame.fillColor = COLOR_PANEL
      frame.strokeColor = COLOR_COPPER_GOLD
      frame.lineWidth = 2
      frame.roundRect(-this.contentWidth / 2, -this.contentHeight / 2,
        this.contentWidth, this.contentHeight, 10)
      frame.fill()
      frame.stroke()
      applySlicedSprite(frame.node, 'ui.panel.kingdom', this.contentWidth, this.contentHeight)
    }
    // 城景铺满整块可视区：既不缩放也不偏移。
    // 旧版按卡片尺寸缩到 0.56 并上移 48px —— 那正是热区只覆盖屏幕中部的另一半原因。
    card.setScale(new Vec3(1, 1, 1))
    card.setPosition(new Vec3(0, 0, 0))
  }

  private render(): void {
    const panel = this.panel
    if (panel === null) {
      return
    }
    this.lastRenderedSecond = Math.floor(sys.now() / 1000)
    const grid = buildCityGrid(panel.rows)

    const selected = this.buildMode ? null
      : panel.rows.find((row) => row.id === this.selectedId) ?? null
    this.selectedId = selected?.id ?? null

    const placedCount = grid.cells.reduce((count, cell) => count + (cell === null ? 0 : 1), 0)
    const unplacedText = grid.unplaced.length === 0 ? '' : ` · ${grid.unplaced.length} 栋坐标异常`
    if (this.headerLabel !== null) {
      this.headerLabel.string = panel.collectHint ?? `内城 · 建筑 ${placedCount}/${CITY_GRID_WIDTH * CITY_GRID_HEIGHT}`
      this.headerLabel.color = panel.collectHint === null ? COLOR_COPPER_GOLD : COLOR_GOOD
    }
    if (this.queueLabel !== null) {
      const queue = panel.queueExpandText === null
        ? panel.queueText
        : `${panel.queueText} · ${panel.queueExpandText}`
      this.queueLabel.string = `${queue}${unplacedText}`
    }
    panel.resourceLines.forEach((line, index) => {
      const label = this.resourceLabels[index]
      if (label !== undefined) {
        label.string = line
      }
    })

    this.syncPreviews()
    this.renderGrid(grid)
    this.renderSelection(selected)
  }

  private renderGrid(grid: CityGrid): void {
    this.gridTiles.forEach((tile) => {
      // 按格位取格子，不按数组下标：下标一旦和锚点表的顺序耦合，改投影就会改玩法
      const index = tile.plate.gridY * CITY_GRID_WIDTH + tile.plate.gridX
      const row = grid.cells[index] ?? null
      tile.node.off('touch-start')
      this.paintTile(tile, row)
      if (row === null) {
        const panel = this.panel
        if (this.buildMode && panel !== null && panel.buildOptions.length > 0) {
          const gridX = tile.plate.gridX
          const gridY = tile.plate.gridY
          tile.node.on('touch-start', (_event: EventTouch) => {
            this.openBuildPicker(gridX, gridY)
          }, this)
        }
        return
      }
      tile.node.on('touch-start', (_event: EventTouch) => {
        this.buildMode = false
        this.selectedId = row.id
        this.renderSelection(row)
      }, this)
    })
  }

  private openBuildPicker(gridX: number, gridY: number): void {
    const options = buildBuildChoices(this.resp)
    if (options.length === 0) {
      this.showMessage('没有可建造的建筑', COLOR_TEXT_DIM)
      return
    }
    // 选择器是模态：底部选择栏让位。否则两层提示叠在画面上（审计 §2.5 第三条），
    // 而且选择栏那行文字说的是"点空地"，此刻已经点过了。
    const bar = this.selectionBar
    if (bar !== null) {
      bar.active = false
    }
    this.buildPicker?.show(options, (configId) => {
      this.buildMode = false
      this.onUpgrade?.(configId, gridX, gridY)
    }, () => {
      // 关闭时恢复。比对 `this.selectionBar` 而不是只看闭包里的 `bar`：
      // 面板若在此期间被 resize 重建过，旧节点已经不该再被点亮。
      if (bar !== null && this.selectionBar === bar) {
        bar.active = true
      }
    })
  }

  private paintTile(tile: GridTileRefs, row: BuildingRow | null): void {
    const selected = row !== null && row.id === this.selectedId
    const graphics = tile.graphics
    graphics.clear()
    if (row === null) {
      tile.levelLabel.string = ''
      tile.nameLabel.string = ''
      tile.icon.active = false
      tile.iconRim.active = false
      // 默认城景没有“空格子”；只有进入建造模式才显示可落点。
      if (this.buildMode) {
        graphics.fillColor = new Color(184, 134, 11, 46)
        graphics.ellipse(0, 0, 24, 11)
        graphics.fill()
        graphics.strokeColor = new Color(214, 178, 90, 210)
        graphics.lineWidth = 2
        graphics.ellipse(0, 0, 24, 11)
        graphics.stroke()
        graphics.moveTo(-7, 0)
        graphics.lineTo(7, 0)
        graphics.moveTo(0, -7)
        graphics.lineTo(0, 7)
        graphics.stroke()
      }
      return
    }

    const iconSide = buildingIconSize(row.configId, tile.plate.width)
    // 选中/可收取用地面光环，不再给整栋楼套一个矩形卡片。
    if (selected || row.collectable) {
      graphics.fillColor = row.collectable
        ? new Color(120, 176, 96, 58) : new Color(184, 134, 11, 48)
      graphics.ellipse(0, 0, iconSide * 0.54, iconSide * 0.16)
      graphics.fill()
      graphics.strokeColor = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
      graphics.lineWidth = 2
      graphics.ellipse(0, 0, iconSide * 0.54, iconSide * 0.16)
      graphics.stroke()
    }

    // 底图上已经画着主堡（它也是新号默认就有的唯一建筑），再叠一层正稿就是重影；
    // 其余 14 类在底图上已被抹成空地，**必须叠正稿**才能"建了才看得见" —— 这也是
    // A18 那 15 张正稿真正被用上的地方（审计 §2.2 记的就是它们先前一格都没渲染）。
    const onBase = this.referenceStage && row.configId === 'main_city'
    // 等级徽章：普通建筑贴在自己的图标右上；**主堡在底图上时改成基座下方居中** ——
    // main_city 的 iconSide 是 300（2.3 倍脚印），badgeY=246 会把徽章推到画面上边之外
    // （主堡基座离顶只有约 121px），等级和名字就都看不见了。
    //
    // 下移到 -62/-44 是为了**避开顶部信息带**：基座离画面顶只有约 121 物理像素，
    // 而资源条正好占着那一条，标签贴太近就会与「体力 100/100」那一行互相压住。
    const badgeX = onBase ? 0 : iconSide * 0.43
    const badgeY = onBase ? -62 : iconSide * 0.82
    const showIdentity = true
    if (onBase) {
      // 主堡的等级与名字要底衬：城景是亮暗交错的厚涂，纯文字压在上面读不出来
      // （审计 §5.3「信息辨识度极低」指的就是这一类）。底衬同时把标签与堡体分开。
      graphics.fillColor = new Color(14, 11, 9, 220)
      const plateW = Math.max(104, tile.plate.width * 1.15)
      graphics.roundRect(-plateW / 2, -78, plateW, 42, 7)
      graphics.fill()
    }
    tile.levelLabel.fontSize = onBase ? 15 : 10
    tile.levelLabel.string = `Lv${row.level}`
    tile.levelLabel.node.active = showIdentity
    if (showIdentity) {
      graphics.fillColor = new Color(16, 13, 11, 235)
      graphics.circle(badgeX, badgeY, 9)
      graphics.fill()
      graphics.strokeColor = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
      graphics.lineWidth = 1
      graphics.circle(badgeX, badgeY, 9)
      graphics.stroke()
      tile.levelLabel.color = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
      tile.levelLabel.node.setPosition(new Vec3(badgeX, badgeY, 0))
      tile.levelLabel.getComponent(UITransform)?.setContentSize(new Size(20, 14))
    }

    // 暂停没有文字可写了（名字与状态都收进下面的选择栏），所以给它一枚实心琥珀点。
    // 少这一个记号就等于"暂停与升级中在城景里长得一样"，而玩家下一步要做的两件事不同。
    if (row.paused) {
      graphics.fillColor = COLOR_WARNING
      graphics.circle(-badgeX, badgeY, 5)
      graphics.fill()
    }

    tile.nameLabel.fontSize = onBase ? 15 : 10
    tile.nameLabel.string = row.name
    tile.nameLabel.node.active = showIdentity
    tile.nameLabel.color = row.collectable ? COLOR_GOOD : COLOR_TEXT
    tile.nameLabel.node.setPosition(new Vec3(0, onBase ? -44 : -13, 0))
    tile.nameLabel.getComponent(UITransform)
      ?.setContentSize(new Size(Math.max(74, (onBase ? tile.plate.width : iconSide) * 0.9),
        onBase ? 20 : 13))
    if (onBase) {
      tile.icon.active = false
      tile.iconRim.active = false
    } else {
      const artKey = buildingArtKey(row.configId)
      let iconVisible = artKey !== null && applyAnyIconSprite(tile.icon, artKey, iconSide, iconSide)
      if (!iconVisible) {
        // 没有正稿、或族图这一次没拉到：退回图集小图标，而不是留一个空格子
        iconVisible = applyIconSprite(tile.icon, buildingIconKey(row.configId), iconSide, iconSide)
      }
      tile.icon.active = iconVisible
      // 描边只配正稿：图集小图标本来就带一圈浅色描边，再垫一层会变成两圈糊边。
      // 同一张图放大 12%、垫在正稿底下、着暖石亮色 ⇒ 分离来自边缘而不是底色。
      const rimFrame = artKey === null ? null : familyFrame(artKey)
      if (rimFrame === null || !iconVisible) {
        tile.iconRim.active = false
      } else {
        const rim = tile.iconRim.getComponent(Sprite) ?? tile.iconRim.addComponent(Sprite)
        rim.spriteFrame = rimFrame
        rim.type = Sprite.Type.SIMPLE
        rim.sizeMode = Sprite.SizeMode.CUSTOM
        rim.color = selected ? COLOR_ART_RIM : new Color(238, 222, 188, 72)
        rim.enabled = true
        tile.iconRim.getComponent(UITransform)
          ?.setContentSize(new Size(iconSide * 1.12, iconSide * 1.12))
        tile.iconRim.active = true
      }
    }

    if (row.upgrading) {
      const ratio = row.collectable ? 1 : Math.min(1, Math.max(0, Number.parseInt(row.progressText ?? '0', 10) / 100))
      const barWidth = Math.max(48, iconSide * 0.72)
      graphics.fillColor = COLOR_PANEL
      graphics.rect(-barWidth / 2, -23, barWidth, 4)
      graphics.fill()
      graphics.fillColor = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
      graphics.rect(-barWidth / 2, -23, barWidth * ratio, 4)
      graphics.fill()
    }
  }

  private renderSelection(row: BuildingRow | null): void {
    // 默认帧也要展开：`wireActionButtons` 会把「建造」按钮点亮，底板却收起来的话，
    // 屏幕上就只剩一个孤立的悬空按钮（审计 §2.5 里视觉评审独立复现过这一条）。
    const hasBuildChoices = (this.panel?.buildOptions.length ?? 0) > 0
    const expanded = row !== null || this.buildMode || hasBuildChoices
    if (this.selectionBarBackground !== null) {
      this.selectionBarBackground.active = expanded
    }
    if (this.selectedTitle !== null) {
      this.selectedTitle.node.active = expanded
    }
    if (this.selectedStatus !== null) {
      this.selectedStatus.node.active = expanded
    }
    if (this.selectedTitle !== null) {
      this.selectedTitle.string = row?.title ?? (this.buildMode ? '建造 · 选择空地' : '点击建筑查看详情')
      this.selectedTitle.color = row?.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
    }
    if (this.selectedStatus !== null) {
      this.selectedStatus.string = row === null
        ? (this.buildMode ? '金色落点可以建造，点选后选择建筑' : '点建筑查看详情，或点「建造」放置新建筑')
        : selectionStatus(row)
      this.selectedStatus.color = row?.paused ? COLOR_WARNING : COLOR_TEXT_DIM
    }
    this.wireActionButtons(row)
  }

  private wireActionButtons(row: BuildingRow | null): void {
    for (const [button, kind] of Array.from(this.actionButtons)) {
      button.off('touch-start')
      const hasBuildChoices = (this.panel?.buildOptions.length ?? 0) > 0
      const visible = kind === 'build' ? row === null && hasBuildChoices
        : row !== null && (kind === 'collect' ? row.collectable
          : kind === 'upgrade' ? !row.upgrading && !row.collectable && !row.paused
            // 暂停：只有"正在升级且还没到点"才可暂停；已暂停的那一行给「恢复」
            : kind === 'pause' ? row.upgrading && !row.collectable && !row.paused
              : kind === 'resume' ? row.paused
                : row.upgrading && !row.collectable)
      button.active = visible
      if (!visible || (row === null && kind !== 'build')) {
        continue
      }
      button.on('touch-start', (_event: EventTouch) => {
        if (kind === 'build') {
          this.buildMode = true
          this.selectedId = null
          this.showMessage('请选择一块空地', COLOR_COPPER_GOLD)
          this.renderSelection(null)
          this.renderGrid(buildCityGrid(this.panel?.rows ?? []))
          return
        }
        if (row === null) {
          return
        }
        switch (kind) {
          case 'upgrade':
            this.onUpgrade?.(row.configId)
            return
          case 'speedAd':
            this.onSpeedUp?.(row.id, 'AD')
            return
          case 'speedGold':
            this.onSpeedUp?.(row.id, 'GOLD')
            return
          case 'collect':
            this.onCollect?.(row.id)
            return
          case 'pause':
            this.onPause?.(row.id)
            return
          case 'resume':
            this.onResume?.(row.id)
            return
        }
      }, this)
    }
  }

  private addLabel(parent: Node, name: string, x: number, y: number, color: Color, fontSize: number,
                   leftAligned = false, maxWidth = 0): Label {
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
      transform.setContentSize(new Size(maxWidth, fontSize * 1.6))
      // SHRINK 而不是 CLAMP：这里装的是资源数值，裁掉尾数会读成另一个数（10000 变 1000），
      // 字变小至少还是那个值。
      label.overflow = Label.Overflow.SHRINK
    }
    return label
  }

  private showMessage(text: string, color: Color): void {
    if (this.messageLabel === null) {
      return
    }
    this.messageLabel.string = text
    this.messageLabel.color = color
  }
}

function selectionStatus(row: BuildingRow): string {
  const parts = [row.statusText]
  if (row.progressText !== null) {
    parts.push(row.progressText)
  }
  if (row.countdownText !== null) {
    parts.push(row.countdownText)
  }
  if (row.helpText !== null) {
    parts.push(row.helpText)
  }
  return parts.join(' · ')
}
