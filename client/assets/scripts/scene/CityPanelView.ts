/**
 * 职责：城建面板 —— 6×6 城内网格、建筑详情、升级、加速、收割（B03 §2/§3/§4）。
 * 依赖：cc（渲染）、game/city/CityPanel（展示数据组装，已单测）。
 *
 * <p>平面列表回答不了“我的城现在长什么样”，所以建筑按服务端下发的 gridX/gridY 放置。
 * 本场景仍然不做任何数值判断：升级、加速、收割全部由现有回调交给 AppRoot 和服务端裁定。
 */

import {
  _decorator, Color, Component, EventMouse, EventTouch, Graphics, Label, LabelOutline, Node, Size, Sprite,
  UITransform, Vec3, sys, view,
} from 'cc'
import {
  CITY_GRID_HEIGHT, CITY_GRID_WIDTH, buildCityGrid, buildCityPanel, cancelMessage, collectMessage,
} from '../game/city/CityPanel'
import type { BuildingRow, CityGrid, CityPanelView as CityPanelData } from '../game/city/CityPanel'
import { buildBuildChoices } from '../game/session/Choices'
import type {
  CityCancelResp, CityCollectResp, CityListResp, SpeedUpSource,
} from '../net/generated/CityProtocol'
import { ChoiceOverlay } from './ChoiceOverlay'
import {
  applyAnyIconSprite, applyCommandButton, applyIconSprite, applySimpleSprite,
  applySlicedSprite, applyTiledSprite, buildingIconKey, ensureFamily, familyFrame,
} from './ArtCatalog'
import { buildingArtKey, PANEL_FRAME_BAND } from '../game/art/ArtFamilies'
import { applySystemUiFont, capWidth, oneLineFloorHeight } from './UiFont'
import {
  DISTRICT_TINT_RGB, SCENE_RUNTIME_HEIGHT, SCENE_RUNTIME_WIDTH,
  SCENE_STAGE_HEIGHT, SCENE_STAGE_WIDTH, projectSceneLayout, scenePlatesBackToFront,
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
/** 正稿乘色：把绿底抠图的亮草垫压进底图 sepia 色域，见 paintTile 里用它的那段。 */
const COLOR_ART_GRADE = new Color(208, 192, 160, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_WARNING = new Color(200, 96, 64, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)
/**
 * 建筑名 / 等级字的描边色。城景是亮暗交错的厚涂，小字不描边就与城墙同亮度、几乎读不出来
 * （2026-09-26 的 1:1 裁剪目视：「伐木场」三个字压在城墙上时只剩轮廓可猜）。
 * 取近黑的暖色而不是纯黑：纯黑描边在亮山脊上会现出一圈硬边，反而更"贴"。
 */
const COLOR_LABEL_OUTLINE = new Color(12, 9, 7, 210)

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
/** 右上角「一键收割」与左下角「学院 · 研究」共用的键宽（origin 线的实测值）。 */
const CORNER_KEY_WIDTH = 132

/**
 * 内城镜头（用户 2026-09-26：「内城地图要可以放大缩小，默认为放大，只显示主城周围建筑」）。
 *
 * <p>**为什么默认是放大而不是"整城尽收"**：36 格铺满 960×600 时，一格只有 ~150 物理像素宽，
 * 建筑正稿缩到那个尺寸就读不出造型，玩家也分不清哪栋是哪栋（审计 §5.3「信息辨识度极低」）。
 * 默认 1.8 倍并对准主堡，一屏只剩主堡周围那几栋 —— 每栋都大到能认出，
 * 想看全城就缩小（下限 1.0 = 改动前那一屏，一寸不多留）。
 *
 * <p>**缩放的是"城景舞台"这一个容器**（底图 + 36 格），HUD（资源条 / 选择栏 / 提示）不跟着缩：
 * 读数与按钮的尺寸是排版契约，缩放了就会与量具钉的几何分家。
 */
const CITY_ZOOM_MIN = 1
const CITY_ZOOM_MAX = 2.4
const CITY_ZOOM_DEFAULT = 1.8
const CITY_ZOOM_STEP = 0.3
const ZOOM_BUTTON_WIDTH = 40
const ZOOM_BUTTON_HEIGHT = 34

type RowAction = 'build' | 'upgrade' | 'speedAd' | 'speedGold' | 'collect' | 'pause' | 'resume' | 'cancel'

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
  /** 城景舞台：底图 + 36 格都在这个容器里。缩放与平移只动它，HUD 不跟着缩。 */
  private stage: Node | null = null
  /** 当前缩放倍数，以及镜头对准的那个点（舞台本地坐标）。 */
  private zoom = CITY_ZOOM_DEFAULT
  private focusX = 0
  private focusY = 0
  /** 玩家自己动过镜头（缩放 / 拖动）之后就不再按数据自动回中 —— 否则每次刷新都把玩家拽回主堡。 */
  private viewAdjusted = false
  /** 上一次按哪个倍数画的标注；倍数变了要重画（标注按 1/zoom 画，见 `paintTile`）。 */
  private lastPaintedZoom = 0
  /** 单指拖动与双指捏合的上一次触点（屏幕像素；差值除以 zoom 才是舞台上的位移）。 */
  private lastPointerX = 0
  private lastPointerY = 0
  private pinchDistance = 0
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
  /** 资源行数超过那 6 颗固定 Label 时，多出来的部分在这里显式说一句，不静默丢 */
  private resourceOverflowLabel: Label | null = null
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
  /**
   * 点资源条上的「体力」那一行（B09 §5）。
   *
   * <p>为什么挂在**那一行**而不是别处：体力是资源条上的一员（配置表把它做成资源行），
   * 玩家看到「体力 87/100」的第一反应就是点它 —— 而在此之前那一行**点了什么都不会发生**
   * （`/stamina` 与 `/stamina/buy` 一处调用都没有）。
   */
  onStamina: (() => void) | null = null
  /** 暂停/恢复升级（B03 §2）。两个回调分开：面板不做"当前该发哪个"的判断，状态由服务端说了算。 */
  onPause: ((buildingId: string) => void) | null = null
  onResume: ((buildingId: string) => void) | null = null
  /** 取消升级（B03 §2 的另一半，返还 60%）。返还额由服务端算，这里只负责把意图发出去。 */
  /** 玩家点了「取消」这一行的建造。返还多少由服务端算，本场景只把回执念出来 */
  onCancelBuild: ((buildingId: string) => void) | null = null
  /** 卡片左下角那颗「学院 · 研究」：打开全局研究页（V03-a-S1 的读侧 + #323 的写侧都在那一页） */
  onOpenTech: (() => void) | null = null

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
    // 舞台是 Background 的子节点，随它一起销毁；这里只断引用，免得留着指已销毁的节点
    this.stage = null
    this.card?.destroy()
    this.card = null
    this.gridTiles.length = 0
    this.previewNodes.clear()
    this.actionButtons.clear()
    this.resourceLabels.length = 0
    this.resourceOverflowLabel = null
    this.headerLabel = null
    this.queueLabel = null
    this.messageLabel = null
    this.selectedTitle = null
    this.selectedStatus = null
    this.selectionBar = null
    this.selectionBarBackground = null
    this.frameGraphics = null
    this.referenceStage = false
    this.lastPaintedZoom = 0
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
    this.onStamina = null
    this.onPause = null
    this.onResume = null
    this.onCancelBuild = null
    this.onOpenTech = null
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

  /** 取消建造的回执：退回来多少，照服务端给的数念。 */
  attachCancel(resp: CityCancelResp): void {
    this.showMessage(cancelMessage(resp), COLOR_GOOD)
  }

  attachCollect(resp: CityCollectResp): void {
    const message = collectMessage(resp)
    if (message === null) {
      return
    }
    this.showMessage(message.text, message.kind === 'done' ? COLOR_GOOD : COLOR_TEXT_DIM)
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

    // 城景舞台：底图与 36 格都挂在这一层，缩放与拖动只动它。
    // 放在 Background **里面**（而不是与它平级）有两个理由：① 深色底不跟着缩，缩到 1 倍以下时
    //    四周露出来的是它，读起来像"一幅画放在桌上"而不是"图被裁了"；
    //    ② `teardown` 销毁 Background 就把它一起带走，不会留下半棵孤儿树。
    const stage = new Node('CityStage')
    stage.layer = node.layer
    node.addChild(stage)
    // 必须有铺满屏的 UITransform：触摸命中按它算，少了这层就收不到拖动与捏合
    stage.addComponent(UITransform).setContentSize(new Size(width, height))
    this.stage = stage
    this.wireStageInput(stage)

    const reference = new Node('CityReferenceScene')
    reference.layer = stage.layer
    stage.addChild(reference)
    reference.addComponent(UITransform).setContentSize(new Size(width, height))
    if (applySimpleSprite(reference, 'city.scene.reference', width, height)) {
      this.referenceStage = true
      this.applyStageTransform()
      return
    }

    // 参考图是满屏城景：地表先铺满全屏，远山再压在上半部，城内只叠道路与建筑。
    const ground = new Node('CityGround')
    ground.layer = stage.layer
    stage.addChild(ground)
    ground.addComponent(UITransform).setContentSize(new Size(width, height))
    applyTiledSprite(ground, 'city.ground', width, height)
    const groundSprite = ground.getComponent(Sprite)
    if (groundSprite !== null) {
      groundSprite.color = new Color(205, 198, 178, 205)
    }

    const ridgeWidth = width * 1.02
    const ridgeHeight = ridgeWidth * 525 / 1344
    const ridge = new Node('CityRidge')
    ridge.layer = stage.layer
    stage.addChild(ridge)
    ridge.setPosition(new Vec3(0, height / 2 - ridgeHeight / 2 + 72, 0))
    applySimpleSprite(ridge, 'city.ridge', ridgeWidth, ridgeHeight)
    const ridgeSprite = ridge.getComponent(Sprite)
    if (ridgeSprite !== null) {
      ridgeSprite.color = new Color(255, 255, 255, 142)
    }
    this.applyStageTransform()
  }

  // ---------- 内城镜头 ----------

  /**
   * 舞台上的三种输入：滚轮缩放（Web）、双指捏合（真机）、单指拖动平移。
   *
   * <p>**为什么单指拖动会和"点中一栋楼"同时发生**：格子的选中挂在 `touch-start` 上
   * （全仓量具都按这个事件名点格子，换事件名等于把所有探针的点击一起弄哑），
   * 所以手指落下的那一刻选中就已经发生了，之后移动才被判成拖动。
   * 代价是"从一栋楼上起手的拖动会顺手选中它" —— 选中只点亮底部详情条、不发任何写请求，
   * 比"为了不误选而把拖动做不出来"便宜。
   */
  private wireStageInput(stage: Node): void {
    // 事件名用字面量而不是 `Node.EventType.*`：与 WorldMap 同一口径，
    // 也让 headless 类型桩不必为一张常量表负责（桩里缺的是常量表，不是引擎能力）。
    stage.on('mouse-wheel', (event: EventMouse) => {
      this.viewAdjusted = true
      this.zoomBy(event.getScrollY() > 0 ? CITY_ZOOM_STEP : -CITY_ZOOM_STEP)
    }, this)
    stage.on('touch-start', (event: EventTouch) => {
      if (event.getAllTouches().length >= 2) {
        this.pinchDistance = this.touchDistance(event)
        return
      }
      const point = event.getUILocation()
      this.lastPointerX = point.x
      this.lastPointerY = point.y
    }, this)
    stage.on('touch-move', (event: EventTouch) => {
      if (event.getAllTouches().length >= 2) {
        const distance = this.touchDistance(event)
        if (this.pinchDistance > 0 && distance > 0) {
          this.viewAdjusted = true
          this.zoomTo(this.zoom * (distance / this.pinchDistance))
        }
        this.pinchDistance = distance
        return
      }
      const point = event.getUILocation()
      const dx = point.x - this.lastPointerX
      const dy = point.y - this.lastPointerY
      this.lastPointerX = point.x
      this.lastPointerY = point.y
      if (dx === 0 && dy === 0) {
        return
      }
      this.viewAdjusted = true
      // 舞台放大了 zoom 倍：手指在屏幕上走 dx，镜头对准的那一点只要走 dx/zoom
      this.setFocus(this.focusX - dx / this.zoom, this.focusY - dy / this.zoom)
    }, this)
    stage.on('touch-end', (event: EventTouch) => this.endPinch(event), this)
    stage.on('touch-cancel', (event: EventTouch) => this.endPinch(event), this)
  }

  /**
   * 双指抬起一根时，剩下那根的"上一次位置"还停在两指时代 —— 直接拿它算 dx 会得到一个
   * 巨大的位移，镜头瞬移（WorldMap 的同族缺陷注释记的正是这个形状）。
   * 把单指拖动的起点重置到剩下那根的当前位置，瞬移就没有了。
   */
  private endPinch(event: EventTouch): void {
    const remaining = event.getAllTouches()
    if (remaining.length === 1) {
      const point = remaining[0]?.getUILocation()
      if (point !== undefined) {
        this.lastPointerX = point.x
        this.lastPointerY = point.y
      }
    }
    this.pinchDistance = 0
  }

  private touchDistance(event: EventTouch): number {
    const touches = event.getAllTouches()
    const first = touches[0]?.getUILocation()
    const second = touches[1]?.getUILocation()
    if (first === undefined || second === undefined) {
      return 0
    }
    return Math.hypot(first.x - second.x, first.y - second.y)
  }

  private zoomBy(delta: number): void {
    this.zoomTo(this.zoom + delta)
  }

  private zoomTo(target: number): void {
    this.zoom = Math.min(CITY_ZOOM_MAX, Math.max(CITY_ZOOM_MIN, target))
    this.applyStageTransform()
  }

  private setFocus(x: number, y: number): void {
    // focus 自己也要夹在当前倍数的可平移范围内：zoom=1 时舞台被夹在居中，若 focus 不夹，
    // 玩家在 1 倍下的空拖会让它无限累积，之后一按「+」镜头就瞬移到累积出的极值。
    const maxX = this.contentWidth * (this.zoom - 1) / (2 * this.zoom)
    const maxY = this.contentHeight * (this.zoom - 1) / (2 * this.zoom)
    this.focusX = Math.max(-maxX, Math.min(maxX, x))
    this.focusY = Math.max(-maxY, Math.min(maxY, y))
    this.applyStageTransform()
  }

  /**
   * 把 `focus` 那一点搬到屏幕中心，再夹回"底图必须铺满视口"的范围里。
   *
   * <p>夹取上限是 `content*(zoom-1)/2`：再往外移就会露出底图之外的深色底。
   * zoom=1 时上限为 0 ⇒ 只能居中，也就是改动前那一屏。
   */
  private applyStageTransform(): void {
    const stage = this.stage
    if (stage === null) {
      return
    }
    stage.setScale(this.zoom, this.zoom, 1)
    const maxX = this.contentWidth * (this.zoom - 1) / 2
    const maxY = this.contentHeight * (this.zoom - 1) / 2
    const x = Math.max(-maxX, Math.min(maxX, -this.focusX * this.zoom))
    const y = Math.max(-maxY, Math.min(maxY, -this.focusY * this.zoom))
    stage.setPosition(new Vec3(x, y, 0))
    if (this.lastPaintedZoom !== this.zoom) {
      this.lastPaintedZoom = this.zoom
      this.repaintTiles()
    }
  }

  /**
   * 只重画 36 格的 Graphics 与标注（不重绑监听、不动选中态）。
   * 缩放改倍数时标注要按新的 1/zoom 重画；比重跑 `render` 便宜，也不打断玩家正在看的选择栏。
   */
  private repaintTiles(): void {
    const panel = this.panel
    if (panel === null) {
      return
    }
    const grid = buildCityGrid(panel.rows)
    this.gridTiles.forEach((tile) => {
      const index = tile.plate.gridY * CITY_GRID_WIDTH + tile.plate.gridX
      this.paintTile(tile, grid.cells[index] ?? null)
    })
  }

  /** 右下角两颗缩放键。滚轮只在 Web 上有、捏合在真机上容易和拖动打架，键是那条兜底路径。 */
  private buildZoomControls(parent: Node): void {
    const x = this.contentWidth / 2 - FRAME_BAND - ZOOM_BUTTON_WIDTH / 2 - 6
    // 2026-10-04：**缩放键挪出网格占用区**（裁决 callId 7e1d1eae-47d1-4480-84f8-e0ab2bce152c 选项一）。
    // 原来 `lowerY = -contentHeight/2 + NAV_BAR_HEIGHT + ACTION_HEIGHT + 26`
    // —— 那个值比网格底边**还高 26px**，两颗键整个落在网格里。
    // 而缩放键与格子在**同一个 stage**（父链 `ZoomOutButton → Card → city`，
    // `city` 就是被 `applyStageTransform` 平移缩放的那层）⇒ **相对位置固定**，
    // 于是右下角那一格被**永久**压在「−」键下面、玩家永远点不到。
    // 实测（`verify-city-multi-types` 的 `[hud-blocked]` 读数）：36 格里 1 格被压住，
    // 就是 `Grid-35` 被 `ZoomOutButton` 压住。
    // ⇒ 改放进**动作条带**（`-contentHeight/2 + NAV_BAR_HEIGHT` 起、高 `ACTION_HEIGHT`），
    // 那一带在网格之外，两颗键竖排正好放得下（34×2 + 4 间距 = 72 ≤ 76）。
    const bandCenterY = -this.contentHeight / 2 + NAV_BAR_HEIGHT + ACTION_HEIGHT / 2
    const stackGap = 4
    this.createZoomButton(parent, 'ZoomOutButton', '-', x, bandCenterY - (ZOOM_BUTTON_HEIGHT + stackGap) / 2)
    this.createZoomButton(parent, 'ZoomInButton', '+', x, bandCenterY + (ZOOM_BUTTON_HEIGHT + stackGap) / 2)
  }

  private createZoomButton(parent: Node, name: string, caption: string, x: number, y: number): void {
    const button = new Node(name)
    button.layer = parent.layer
    parent.addChild(button)
    button.setPosition(new Vec3(x, y, 0))
    button.addComponent(UITransform).setContentSize(new Size(ZOOM_BUTTON_WIDTH, ZOOM_BUTTON_HEIGHT))
    if (!applyCommandButton(button, 'normal', ZOOM_BUTTON_WIDTH, ZOOM_BUTTON_HEIGHT)) {
      const graphics = button.addComponent(Graphics)
      graphics.fillColor = COLOR_PANEL
      graphics.strokeColor = COLOR_COPPER_GOLD
      graphics.lineWidth = 1
      graphics.roundRect(-ZOOM_BUTTON_WIDTH / 2, -ZOOM_BUTTON_HEIGHT / 2,
        ZOOM_BUTTON_WIDTH, ZOOM_BUTTON_HEIGHT, 5)
      graphics.fill()
      graphics.stroke()
    }
    const label = this.addLabel(button, 'Caption', 0, 0, COLOR_TEXT, 20)
    label.string = caption
    button.on('touch-start', (_event: EventTouch) => {
      this.viewAdjusted = true
      this.zoomBy(name === 'ZoomInButton' ? CITY_ZOOM_STEP : -CITY_ZOOM_STEP)
    }, this)
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
    // 36 格挂在**舞台**上（跟着缩放），HUD 挂在卡片上（不跟着缩）：读数与按钮的尺寸是排版契约。
    const stage = this.stage
    this.buildGrid(stage ?? card)
    this.buildActionBar(card)
    this.buildZoomControls(card)

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
    // 居中排也要限宽：队列行最长那句会随服务端给的数变长，右端顶进右上角
    // 「一键收割」里（origin 线 #335 实测压住 29px）—— 让位给键，长句交给 SHRINK。
    const cornerX = this.contentWidth / 2 - FRAME_BAND - 76
    const headerCap = this.contentWidth / 2 + cornerX - CORNER_KEY_WIDTH / 2 - 8
    this.headerLabel = this.addLabel(card, 'Header', 0, top - 14, COLOR_COPPER_GOLD, 22, false, headerCap)
    this.queueLabel = this.addLabel(card, 'Queue', 0, top - 38, COLOR_TEXT, 16, false, headerCap)

    // 资源条只占左上角一块，不横跨整屏 —— 满屏城景下横跨会把城压成一条缝，
    // 而两列三行的底板右缘停在画面中线以左，不会盖住主堡（实测三列会盖住）。
    const columnWidth = Math.min(165, this.contentWidth / 5.4)
    const blockLeft = -this.contentWidth / 2 + 14
    const blockTop = top - 52
    const rowStep = 20
    const rows = 3
    const columns = 2
    const blockWidth = columnWidth * columns + 12
    const blockHeight = rowStep * rows + 12
    // 整块资源区要一块实底：顶部渐变到第二行已经淡得托不住字
    // （实测「木材 5000/20000」糊在亮山脊上读不出），六行字的对比度
    // 不该取决于底下那一段城景画的是山还是墙。
    const plate = new Node('ResourcePlate')
    plate.layer = card.layer
    card.addChild(plate)
    plate.addComponent(UITransform).setContentSize(new Size(blockWidth, blockHeight))
    plate.setPosition(new Vec3(blockLeft + blockWidth / 2, blockTop - blockHeight / 2 - 2, 0))
    const plateGraphics = plate.addComponent(Graphics)
    plateGraphics.fillColor = new Color(14, 11, 9, 172)
    plateGraphics.roundRect(-blockWidth / 2, -blockHeight / 2, blockWidth, blockHeight, 6)
    plateGraphics.fill()
    for (let row = 0; row < rows; row++) {
      for (let column = 0; column < columns; column++) {
        // 左对齐 + 限定列宽：数值位数由服务端算，不可控（"1000000" 和 "200" 同栏）。
        this.resourceLabels.push(this.addLabel(
          card, `Resource-${row}-${column}`,
          blockLeft + 8 + columnWidth * column, blockTop - 8 - row * rowStep,
          COLOR_TEXT, 15, true, columnWidth - 10))
      }
    }
    this.resourceOverflowLabel = this.addLabel(
      card, 'ResourceOverflow', blockLeft + 8, blockTop - 8 - rows * rowStep,
      COLOR_TEXT_DIM, 12, true, blockWidth * 2)

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
    // Cocos 按子节点次序绘制。落地越靠画面下方越晚画；服务器格位只负责取数据。
    const ordered = scenePlatesBackToFront(this.sceneLayout.plates)
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
      this.outlineFor(levelLabel)
      // 这颗字**画在半径 9 的徽章圆盘里**（`drawTileBadge` 把位置钉到圆心、盒子钉到 20×14），
      // 抬到 27 的地板就会让字长出圆盘。它是 27 地板的一条有意例外，留在横扫基线里。
      levelLabel.node.getComponent(UITransform)?.setContentSize(new Size(20, 14))
      levelLabel.overflow = Label.Overflow.SHRINK
      const nameLabel = this.addLabel(tile, 'Name', 0, -12, COLOR_TEXT, 10)
      this.outlineFor(nameLabel)
      nameLabel.node.getComponent(UITransform)?.setContentSize(new Size(plate.width, 12))
      nameLabel.overflow = Label.Overflow.SHRINK
      this.gridTiles.push({ node: tile, graphics, iconRim, icon, levelLabel, nameLabel, plate })
    }
  }

  /** 给城景里的小字加一圈深色描边：见 {@link COLOR_LABEL_OUTLINE} 为什么存在。 */
  private outlineFor(label: Label): void {
    const outline = label.node.addComponent(LabelOutline)
    outline.color = COLOR_LABEL_OUTLINE
    outline.width = 2
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
    // 按钮簇整体靠右：左半给名字与状态，右半给动作，
    // 否则满屏城景下选择栏右半永远是一条空带（实测空约 45% 宽）。
    const clusterLeft = this.contentWidth / 2 - 12 - 430
    const textCap = Math.max(160, Math.min(300, clusterLeft - left - 8))
    this.selectedTitle = this.addLabel(bar, 'SelectedTitle', left, 16, COLOR_COPPER_GOLD, 17, true, textCap)
    this.selectedStatus = this.addLabel(bar, 'SelectedStatus', left, -8, COLOR_TEXT_DIM, 14, true, textCap)
    this.selectedStatus.overflow = Label.Overflow.SHRINK

    this.createActionButton(bar, 'DetailBuildButton', '建造', clusterLeft, 'build')
    this.createActionButton(bar, 'DetailUpgradeButton', '升级', clusterLeft - 12, 'upgrade')
    this.createActionButton(bar, 'DetailCollectButton', '收割', clusterLeft - 12, 'collect')
    this.createActionButton(bar, 'DetailSpeedAdButton', '广告加速', clusterLeft + 78, 'speedAd')
    this.createActionButton(bar, 'DetailSpeedGoldButton', '金币加速', clusterLeft + 168, 'speedGold')
    // 暂停/恢复共用同一个 x：同一时刻只可能显示一个（升级中才可暂停、已暂停才能恢复），
    // 与「升级 / 收割」共用 -12 是同一个做法。
    this.createActionButton(bar, 'DetailPauseButton', '暂停', clusterLeft + 258, 'pause')
    this.createActionButton(bar, 'DetailResumeButton', '恢复', clusterLeft + 258, 'resume')
    this.createActionButton(bar, 'DetailCancelButton', '取消', clusterLeft + 348, 'cancel')

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
      collectGraphics.roundRect(-CORNER_KEY_WIDTH / 2, -17, CORNER_KEY_WIDTH, 34, 6)
      collectGraphics.fill()
      collectGraphics.stroke()
    }
    this.addLabel(collectAll, 'Caption', 0, 0, COLOR_TEXT, 15).string = '一键收割'
    collectAll.on('touch-start', (_event: EventTouch) => this.onCollect?.(null), this)

    // 研究页是**全局一页一队列**，不是某个建筑的属性 ⇒ 不挂在学院行上（挂上去会带来两个够不着的
    // 时刻：没选中学院、学院正在升级把按钮条占满）。学院等级仍是服务端的真门槛
    // （TECH_ACADEMY_REQUIRED 会把原因原样送回来）。
    //
    // 落点是**内容区左下、选择栏上沿之上**：顶部那一行放不下第二颗常驻键
    // （标题 + 队列行 + 一键收割已经排满），而选择栏左半是名字/状态文字、
    // 右半是动作键簇，左下这条缝两者都不占。
    const tech = new Node('TechOpenButton')
    tech.layer = parent.layer
    parent.addChild(tech)
    tech.setPosition(new Vec3(
      -this.contentWidth / 2 + FRAME_BAND + 76,
      -this.contentHeight / 2 + NAV_BAR_HEIGHT + ACTION_HEIGHT + 17, 0))
    tech.addComponent(UITransform).setContentSize(new Size(CORNER_KEY_WIDTH, 34))
    if (!applyCommandButton(tech, 'normal', CORNER_KEY_WIDTH, 34)) {
      const techGraphics = tech.addComponent(Graphics)
      techGraphics.fillColor = COLOR_PANEL
      techGraphics.strokeColor = COLOR_COPPER_GOLD
      techGraphics.lineWidth = 2
      techGraphics.roundRect(-CORNER_KEY_WIDTH / 2, -17, CORNER_KEY_WIDTH, 34, 6)
      techGraphics.fill()
      techGraphics.stroke()
    }
    this.addLabel(tech, 'Caption', 0, 0, COLOR_TEXT, 15).string = '学院 · 研究'
    tech.on('touch-start', (_event: EventTouch) => this.onOpenTech?.(), this)
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
        // 「体力」那一行可点：打开体力详情。按**文本前缀**认它，不写死行列号 ——
        // 资源条的排列来自服务端下发的资源表，写死第 5 格这种事会在改表那天静默失效。
        const node = label.node
        node.off('touch-start')
        if (line.startsWith('体力')) {
          node.on('touch-start', () => this.onStamina?.(), this)
        }
      }
    })
    // 尾部要清：`resources` 是服务端按玩家状态拼的 map（`CityAppService.toResourceMap`），
    // 键数不固定 —— 只写不清的话，条数一变短，后面那几颗就还留着**上一次的旧数值**，
    // 玩家读到的是"我还有 8000 石头"，而那个数属于上一帧。
    for (let index = panel.resourceLines.length; index < this.resourceLabels.length; index++) {
      const stale = this.resourceLabels[index]
      if (stale !== undefined && stale.string !== '') {
        stale.string = ''
      }
    }
    // 比槽位多的那一截不能静默丢掉（丢了就是"资源少了一种"却没有任何地方说），
    // 但也绝不因此撑破头部那一块 —— 交给 `resourceOverflowLabel` 显式说一句还有几项没画。
    if (this.resourceOverflowLabel !== null) {
      const hidden = panel.resourceLines.length - this.resourceLabels.length
      this.resourceOverflowLabel.string = hidden > 0 ? `另有 ${hidden} 项资源未显示` : ''
    }

    this.syncPreviews()
    this.renderGrid(grid)
    this.renderSelection(selected)
  }

  private renderGrid(grid: CityGrid): void {
    // 主堡在哪一格由服务端下发（gridX/gridY），所以默认对焦点要现读，不能写死坐标。
    let keepX = Number.NaN
    let keepY = Number.NaN
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
      if (row.configId === 'main_city') {
        keepX = tile.plate.x
        keepY = tile.plate.y
      }
      tile.node.on('touch-start', (_event: EventTouch) => {
        this.buildMode = false
        this.selectedId = row.id
        this.renderSelection(row)
      }, this)
    })
    // 这一遍是按当前倍数画的，记下来：`applyStageTransform` 靠它判断要不要重画标注
    this.lastPaintedZoom = this.zoom
    // 默认镜头对准主堡的**基座**：放大到 1.8 倍时屏幕上只剩主堡周围那几栋，
    // 而对准基座（而不是堡体中心）刚好让城堡整个落在画面里、上方还留出天空。
    // 玩家自己缩放过或拖动过就不再抢镜头 —— 否则每次数据刷新都把人拽回主堡。
    if (!this.viewAdjusted && !Number.isNaN(keepX)) {
      this.setFocus(keepX, keepY)
    }
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
    /**
     * **标注不跟世界一起缩**：等级牌 / 名字 / 进度条是 UI，字号该停在设计尺寸上
     * （与全游戏其它文字同一把尺：设计 px × 设备比）。跟着舞台缩的话，默认 1.8 倍下
     * 主城的深色名牌会变成 187×76 的大黑块压在城堡门上、字号比任何 HUD 文字都大一号
     * （2026-09-26 截图实测）。做法是几何一律乘 `u = 1/zoom` 画、Label 节点再 `setScale(u)`：
     * 位置乘 u 后被父级的 zoom 乘回来（落点不变），尺寸乘 u 再乘 zoom 等于设计尺寸。
     * 地面光环与正稿**不**乘 u —— 它们是画里的东西，该跟着世界走。
     */
    const u = 1 / this.zoom
    // 底图上已经画着主堡（它也是新号默认就有的唯一建筑），再叠一层正稿就是重影；
    // 其余 14 类在底图上已被抹成空地，**必须叠正稿**才能"建了才看得见" —— 这也是
    // A18 那 15 张正稿真正被用上的地方（审计 §2.2 记的就是它们先前一格都没渲染）。
    const onBase = this.referenceStage && row.configId === 'main_city'
    // **未建成的楼不画**：取消首次放置之后，实例会留在 Lv0 + 空闲（服务端没有"移除建筑"的口子），
    // 那种格子如果照画正稿，玩家会看到一栋自己从没建成的楼（2026-09-22 取消功能上线后实测到）。
    // 口径与 build-many 那条判据一致：升级中 / 已暂停 / 待收割 / 已建成 才算这格有楼。
    const built = row.level > 0 || row.upgrading || row.paused || row.collectable

    /**
     * 「这栋楼点得动」的三级地面语言（用户 2026-09-26：要凸显可操作建筑，但不能破坏自然感）。
     *
     * <p>三级都画在**地面**上，一律不给楼体套框 —— 套框（矩形卡片 / 整栋描边）就是把厚涂底图
     * 上的建筑变成"贴在画上的 UI"，正是自然感的杀手：
     * <ol>
     *   <li><b>常置台座</b>：凡建成的楼，脚下都有一枚极淡的暖色椭圆（alpha 26 填充 / 46 描边）。
     *       读作"这块地踩实了、是有主的"，而不是"这是个按钮"；</li>
     *   <li><b>有事可做</b>：可收割 ⇒ 换成绿色光环（alpha 58 + 2px 描边），一眼看出哪栋能收；</li>
     *   <li><b>选中</b>：金色光环 + 正稿底下那圈 rim 提到 alpha 150（见下面的 `rim.color`）。</li>
     * </ol>
     * 椭圆宽深读同一块地皮，不随楼体贴图放大而侵入邻地。颜色取自画面自己的暖调（214,186,132 /
     * 铜金 184,134,11），不用饱和原色 —— 所以它像地上的光，不像叠上去的图形。
     *
     * <p>主堡（`onBase`）不走一级台座：它是画在底图里的，脚下那一片是画好的城门石阶，
     * 再叠一枚椭圆就是往画上抹一块斑。它的可操作提示由等级牌与名字底衬承担（下面那两块）。
     */
    if (!onBase && (selected || row.collectable)) {
      graphics.fillColor = row.collectable
        ? new Color(120, 176, 96, 58) : new Color(184, 134, 11, 48)
      graphics.ellipse(0, 0, tile.plate.width / 2, tile.plate.height / 2)
      graphics.fill()
      graphics.strokeColor = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
      graphics.lineWidth = 2
      graphics.ellipse(0, 0, tile.plate.width / 2, tile.plate.height / 2)
      graphics.stroke()
    } else if (built && !onBase) {
      graphics.fillColor = new Color(214, 186, 132, 26)
      graphics.ellipse(0, 0, tile.plate.width / 2, tile.plate.height / 2)
      graphics.fill()
      graphics.strokeColor = new Color(214, 186, 132, 46)
      graphics.lineWidth = 1
      graphics.ellipse(0, 0, tile.plate.width / 2, tile.plate.height / 2)
      graphics.stroke()
    }

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
      graphics.roundRect(-plateW / 2 * u, -78 * u, plateW * u, 42 * u, 7 * u)
      graphics.fill()
      // 参考底图主堡的锚点在塔楼上，地面椭圆会悬在空中；选中提示改在自己的名牌上。
      if (selected || row.collectable) {
        graphics.strokeColor = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
        graphics.lineWidth = 2 * u
        graphics.roundRect(-plateW / 2 * u, -78 * u, plateW * u, 42 * u, 7 * u)
        graphics.stroke()
      }
    }
    tile.levelLabel.fontSize = onBase ? 16 : 12
    tile.levelLabel.string = `Lv${row.level}`
    tile.levelLabel.node.active = showIdentity
    if (showIdentity) {
      graphics.fillColor = new Color(16, 13, 11, 235)
      graphics.circle(badgeX * u, badgeY * u, (onBase ? 10 : 11) * u)
      graphics.fill()
      graphics.strokeColor = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
      graphics.lineWidth = 1 * u
      graphics.circle(badgeX * u, badgeY * u, (onBase ? 10 : 11) * u)
      graphics.stroke()
      tile.levelLabel.color = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
      tile.levelLabel.node.setPosition(new Vec3(badgeX * u, badgeY * u, 0))
      tile.levelLabel.node.setScale(u, u, 1)
      tile.levelLabel.getComponent(UITransform)?.setContentSize(new Size(26, 18))
    }

    // 暂停没有文字可写了（名字与状态都收进下面的选择栏），所以给它一枚实心琥珀点。
    // 少这一个记号就等于"暂停与升级中在城景里长得一样"，而玩家下一步要做的两件事不同。
    if (row.paused) {
      graphics.fillColor = COLOR_WARNING
      graphics.circle(-badgeX * u, badgeY * u, 5 * u)
      graphics.fill()
    }

    tile.nameLabel.fontSize = onBase ? 16 : 12
    tile.nameLabel.string = row.name
    tile.nameLabel.node.active = showIdentity
    tile.nameLabel.color = row.collectable ? COLOR_GOOD : COLOR_TEXT
    tile.nameLabel.node.setPosition(new Vec3(0, (onBase ? -44 : -15) * u, 0))
    tile.nameLabel.node.setScale(u, u, 1)
    capWidth(tile.nameLabel, Math.max(84, (onBase ? tile.plate.width : iconSide) * 0.9))
    if (onBase) {
      tile.icon.active = false
      tile.iconRim.active = false
    } else {
      const artKey = buildingArtKey(row.configId)
      let iconVisible = built && artKey !== null && applyAnyIconSprite(tile.icon, artKey, iconSide, iconSide)
      if (!iconVisible && built) {
        // 没有正稿、或族图这一次没拉到：退回图集小图标，而不是留一个空格子
        iconVisible = applyIconSprite(tile.icon, buildingIconKey(row.configId), iconSide, iconSide)
      }
      tile.icon.active = iconVisible
      // 正稿是绿底抠图来的，自带一块亮黄绿草垫；直接叠在sepia厚涂的底图上
      // 会像贴了一张别的游戏的贴纸。乘一层暖灰把草垫压进底图的色域
      // （整栋一起变暖，与画面光向一致），分离感来自边缘描边而不是色差。
      if (iconVisible) {
        const iconSprite = tile.icon.getComponent(Sprite)
        if (iconSprite !== null) {
          iconSprite.color = COLOR_ART_GRADE
        }
      }
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
      graphics.rect(-barWidth / 2 * u, -23 * u, barWidth * u, 4 * u)
      graphics.fill()
      graphics.fillColor = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
      graphics.rect(-barWidth / 2 * u, -23 * u, barWidth * ratio * u, 4 * u)
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
                // 取消：升级中与已暂停都能取消（服务端口径 isUpgrading = UPGRADING 或 PAUSED），
                // 但到点待收割的那一格不给取消 —— 那时候收下来就是收益，取消等于白干。
                : kind === 'cancel' ? (row.upgrading || row.paused) && !row.collectable
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
          case 'cancel':
            this.onCancelBuild?.(row.id)
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
      // 盒高用量出来的下限：`字号 × 1.6` 在 16 号字上只有 26，而 SHRINK 会把字形压到 26/30
      // —— 那一行本来就是常态性小一号（台账 #366/#367 实测，`tools/verify-label-fit-runtime.mjs` 会点名）
      transform.setContentSize(new Size(maxWidth, oneLineFloorHeight()))
      // SHRINK 而不是 CLAMP：这里装的是资源数值，裁掉尾数会读成另一个数（10000 变 1000），
      // 字变小至少还是那个值。
      label.overflow = Label.Overflow.SHRINK
    }
    return label
  }

  /**
   * 适配层把「这件事现在做不了」写到那条文案带上（#356/#357：以前只进 console.warn）。
   *
   * <p>与 #355 删掉的那颗 `showError` 不是一回事：那颗是**视图自己另开的一条错误通路**，
   * 会绕过 `AppRoot.say` 那个既显示又上报的收口点；这一颗是**收口点下面的落点**，
   * 只有适配层会调它，本场景不判断任何"能不能"。
   */
  showBlocked(message: string): void {
    this.showMessage(message, COLOR_WARNING)
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
