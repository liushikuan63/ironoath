/**
 * 职责：世界大地图场景 —— 3×3 块的地面/迷雾、地图实体、行军插值动画、拖动与缩放（B07 §1/§3/§4）。
 * 依赖：cc（渲染）、game/world/WorldContext（只读）、scene/NodePool。
 *
 * <p><b>铁律 2：本文件是纯表现层</b>。它只读 WorldViewModel 组装好的渲染帧，
 * 不算距离、不算行军时间、不判断能不能打、不判断迷雾该不该解开 ——
 * 那些结论全部由服务端下发。删掉本文件，游戏逻辑不受任何影响。
 *
 * <p><b>三类节点全部池化</b>（B07 §4，验收 3）：地面块、地图实体、行军图标各一个 NodePool。
 * 拖动地图时实体不断进出视野，池化把「销毁 → 新建」换成「归还 → 复用」，
 * 节点总数被峰值实体数封顶，内存不会随拖动时长增长。
 *
 * <p>占位美术用 Graphics 画纯色块（与 MainCity 同一套做法）：B07 阶段没有地图美术产出，
 * 用色块可以让「拉块 → 画块 → 拖动 → 增量拉块」的全链路先跑通。
 * 换正式美术时只需替换 drawEntity / drawTile 里的绘制部分，池化与差集回收逻辑不用动。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>（代码交付不了，已记入缺口清单）：
 * 双指捏合缩放（需要 EventTouch.getAllTouches 与真正的手势识别）、.scene 资产与节点层级、
 * 坐标输入框与书签列表（需要 UI 组件与编辑）。本文件提供 {@link WorldMap#zoomIn} /
 * {@link WorldMap#zoomOut} / {@link WorldMap#focusHome} 三个公开方法作为它们的接入点。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, sys, view } from 'cc'
import { exileSnapshot, worldModel, worldRequester } from '../game/world/WorldContext'
import type { ExileSnapshot } from '../game/world/WorldContext'
import { exileCanRequest, exileLabel } from '../game/world/ExileAction'
import { baseCellPixels, cellPixels } from '../game/world/WorldZoom'
import {
  WORLD_BUTTON_HEIGHT, worldCaptionViewport, worldMapInputAllowed,
  worldMapTranslation, worldSceneLayout, worldTerrainTileScale, worldFogContours,
} from '../game/world/WorldSceneLayout'
import type { WorldViewModel } from '../game/world/WorldViewModel'
import type { WorldFrame, ChunkTile, MarchRender } from '../game/world/WorldViewModel'
import { buildMarchPanel } from '../game/world/MarchPanel'
import type { MarchPanelAction } from '../game/world/MarchPanel'
import type { WorldEntityType } from '../net/generated/WorldProtocol'
import { NodePool } from './NodePool'
import { MarchPanelView } from './MarchPanelView'
import {
  applyCommandButton, applySimpleSprite, applyTerrainSprite, applyTiledSprite, artFrame, terrainArtKey,
} from './ArtCatalog'
import type { ArtKey } from './ArtCatalog'
import { applySystemUiFont } from './UiFont'
import {
  CAPTION_PLATE_HEIGHT, captionBox, captionPlateWidth, entityCaption, pickVisibleCaptions,
} from '../game/world/WorldLabels'
import type { Unsubscribe } from '../core/EventBus'

const { ccclass } = _decorator

/**
 * 配色来自 B00「题材与调性」：铜金 + 暗红，写实厚重冷兵器乱世。
 * 这是美术方向常量而非游戏数值（铁律 1 约束的是时间/产量/攻击/掉落/冷却）。
 */
const COLOR_BACKGROUND = new Color(16, 14, 12, 255)
const COLOR_GROUND = new Color(52, 42, 33, 255)
const COLOR_GROUND_GRID = new Color(70, 57, 45, 255)
/** 不透明的冷灰战争迷雾。纹理只表现遮罩，不铺地形，也不绘制服务端隐藏的实体。 */
const COLOR_FOG = new Color(26, 28, 32, 255)
/** 请求已发出但响应还没回来。必须与迷雾区分：一个是网络慢，一个是没探索过 */
const COLOR_LOADING = new Color(30, 30, 34, 255)
const COLOR_CITY = new Color(139, 26, 26, 255)
const COLOR_MONSTER = new Color(96, 88, 78, 255)
const COLOR_RESOURCE = new Color(72, 104, 62, 255)
const COLOR_MARCH = new Color(184, 134, 11, 255)
const COLOR_BUILDING = new Color(58, 86, 120, 255)
/** 纠偏闪光（B07 验收 10）：位置被服务端校正时短暂提亮，让玩家知道「刚才那一下不是卡了」 */
const COLOR_CORRECTED = new Color(255, 236, 180, 255)
/** 名牌底板与边缘：比背景亮一档的暗牌 + 半截铜边，字浮在牌上 */
const COLOR_PLATE = new Color(14, 12, 10, 225)
const COLOR_PLATE_EDGE = new Color(120, 92, 40, 200)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)

/**
 * 缩放换算本体在 `game/world/WorldZoom.ts`（引擎无关、可单测）。
 * 这里只留一句为什么不在视图里写死：见那个模块的头注释。
 */

/** 实体色块的边长占一格的比例。留出缝隙才能看清格子边界，也避免相邻实体糊成一片。 */
const ENTITY_SIZE_RATIO = 0.72
const CITY_SIZE_RATIO = 1.5
const MARCH_SIZE_RATIO = 0.9

/** 流亡迁城的二次确认窗口：过了就得重新按两下。宁短勿长 —— 拖着确认状态去干别的再回来点到，正是误操作的样子。 */
const EXILE_CONFIRM_WINDOW_MS = 5_000
/** 双指间距相对本次手势起点扩大 / 缩小到这个比例时，缩放一档。 */
const PINCH_ZOOM_IN_RATIO = 1.25
const PINCH_ZOOM_OUT_RATIO = 0.8

/** 纠偏闪光持续的帧数。这是特效时长，不是游戏数值。 */
const CORRECTED_FLASH_FRAMES = 8

interface GesturePoint {
  readonly x: number
  readonly y: number
}

type PinchZoomAction = 'zoom-in' | 'zoom-out' | null

interface MarkerRefs {
  readonly spriteNode: Node
  readonly graphicsNode: Node
  readonly graphics: Graphics
  readonly label: Label
  /** 名牌底板：文字后面那块暗底铜边的圆角小牌（同类 SLG 的通用名牌语言） */
  readonly plate: Graphics
  /** 最近一次画出的本体尺寸：命中测试与选中环半径都读它，不再各处猜一个 */
  size: number
  /** 雾层参数没变就保留 Graphics，避免静态背景每帧清除重画。 */
  tilePaintSignature?: string
}

@ccclass('WorldMap')
export class WorldMap extends Component {
  /** 放大到城市档时由组合根切到现有内城面板；本场景不直接 loadScene。 */
  onEnterCity: (() => void) | null = null
  /** 空态那个「再次出征」按钮：转发给 GameBootstrap 去调编排层。 */
  onRepeatLastMarch: (() => void) | null = null

  private readonly refs = new Map<Node, MarkerRefs>()
  /** 已画出的实体：键 → 节点。每帧与渲染帧做差集，决定谁复用、谁归还 */
  private readonly drawnEntities = new Map<string, Node>()
  private readonly drawnMarches = new Map<string, Node>()
  private readonly drawnTiles = new Map<string, Node>()
  /** 纠偏闪光的剩余帧数：键 → 还剩几帧 */
  private readonly flash = new Map<string, number>()
  private readonly unsubscribes: Unsubscribe[] = []

  private mapLayer: Node | null = null
  private backdropNode: Node | null = null
  private backdropPainted = false
  private hudLayer: Node | null = null
  private toolbarNode: Node | null = null
  private sceneLayout = worldSceneLayout(960, 600)
  private tilePool: NodePool | null = null
  private entityPool: NodePool | null = null
  private marchPool: NodePool | null = null

  private coordLabel: Label | null = null
  private hintLabel: Label | null = null
  /** 流亡迁城按钮的文字。它要显示冷却倒计时，所以必须能被逐帧更新。 */
  private exileCaption: Label | null = null
  /** 上一次写进按钮的文字。相同就不再赋值 —— 每帧重设 Label 会触发文本重排。 */
  private lastExileCaption = ''
  /** 二次确认窗口的截止本地时刻（`sys.now()` 口径）。0 表示不在确认中。 */
  private exileConfirmUntil = 0
  /** 上一次看到的状态快照与看到它的本地时刻。用引用比较即可：状态只会整体被换成新对象。 */
  private lastExileSnapshot: ExileSnapshot | null = null
  private exileSnapshotAt = 0
  /** 一次迁城请求在途时锁住按钮，避免确认窗口被误当成“没点到”而重复发起。 */
  private exileRequesting = false
  private dragging = false
  /** 当前仍在屏幕上的触点；用于区分单指拖动与双指缩放。 */
  private readonly activeTouches = new Map<number, GesturePoint>()
  private pinchAnchorDistance: number | null = null
  /** 一次双指手势最多缩一档，避免一次张开直接从世界档跨进城市档。 */
  private pinchZoomTriggered = false
  /** 双指手势结束后，必须等所有手指抬起才允许重新拖动，防止抬起一根手指时瞬移。 */
  private suppressDragUntilRelease = false
  private panAccumX = 0
  private panAccumY = 0
  private marchesRequested = false
  private modelSubscription: Unsubscribe | null = null
  private lastHint = ''
  /** 行军动作的结果提示。它有自己的过期时间，不能被每帧的连接状态提示立刻覆盖。 */
  private actionHint = ''
  private actionHintUntil = 0
  /** 正在等待服务端响应的 marchId，防止弱网重复点击。 */
  private readonly marchRequesting = new Set<string>()
  private marchPanel: MarchPanelView | null = null
  private marchButtonCaption: Label | null = null
  private lastMarchButtonCaption = ''
  /**
   * 点选中的实体键（`type:id`）。同类 SLG 的通用交互：点地图单位 → 脚下出选中环。
   * 只做表现，不给任何操作入口 —— 行军/集结仍走 marchPanel，避免在这里长出第二套判定。
   */
  private selectedKey: string | null = null
  private selectionRing: Node | null = null
  /** 所有行军的轨迹线共用一张 Graphics，每帧重画（与实体同帧，不额外要数据） */
  private marchLines: Node | null = null
  /** 单指按下位置与时刻：位移小且快才判成"点击"，否则那是拖动的一部分 */
  private tapStart: { x: number; y: number; at: number } | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.sceneLayout = worldSceneLayout(size.width, size.height)
    this.buildBackground(size.width, size.height)
    this.mapLayer = this.buildLayer('MapLayer', size.width, size.height)
    this.hudLayer = this.buildLayer('HudLayer', size.width, size.height)
    this.tilePool = new NodePool(this.mapLayer, () => this.createMarker(), 9)
    this.entityPool = new NodePool(this.mapLayer, () => this.createMarker())
    this.marchPool = new NodePool(this.mapLayer, () => this.createMarker())
    this.marchLines = this.createOverlayGraphics('MarchLines')
    this.selectionRing = this.createOverlayGraphics('SelectionRing')
    this.buildHud(size.width, size.height)
    this.buildMarchPanel(size.width, size.height)
    this.bindInput()
    this.bindModel()
  }

  private buildMarchPanel(width: number, height: number): void {
    this.marchPanel = new MarchPanelView(this.hudLayer ?? this.node, width, height)
    this.marchPanel.onClose = () => {
      this.marchPanel?.hide()
      // 关闭用的这一下不能再落到地图拖动上，否则松手时地图会跟着跳一段。
      this.suppressDragUntilRelease = true
    }
    this.marchPanel.onAction = (action, marchId) => this.requestMarchAction(action, marchId)
    // 「再次出征」（B25-S1 裁决②(a)）：地图不认识编排层，只把意图转出去（同 onEnterCity 那条形状）
    this.marchPanel.onRepeat = () => this.onRepeatLastMarch?.()
  }

  /** 仅视口改变时重排操作条与弹窗；地图节点池、玩家焦点、行军状态均继续复用。 */
  private syncViewport(): void {
    const size = view.getVisibleSize()
    if (size.width === this.sceneLayout.width && size.height === this.sceneLayout.height) return
    this.sceneLayout = worldSceneLayout(size.width, size.height)
    this.node.getComponent(UITransform)?.setContentSize(size.width, size.height)
    this.mapLayer?.getComponent(UITransform)?.setContentSize(size.width, size.height)
    this.hudLayer?.getComponent(UITransform)?.setContentSize(size.width, size.height)
    const background = this.node.getChildByName('Background')
    background?.getComponent(UITransform)?.setContentSize(size.width, size.height)
    const graphics = background?.getComponent(Graphics)
    if (graphics !== null && graphics !== undefined) {
      graphics.clear()
      graphics.fillColor = COLOR_BACKGROUND
      graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
      graphics.fill()
    }
    this.backdropPainted = false
    this.toolbarNode?.removeFromParent()
    this.toolbarNode?.destroy()
    this.toolbarNode = null
    this.lastHint = ''
    this.lastExileCaption = ''
    this.lastMarchButtonCaption = ''
    this.buildHud(size.width, size.height)
    const panelVisible = this.marchPanel?.isVisible ?? false
    this.marchPanel?.destroy()
    this.buildMarchPanel(size.width, size.height)
    if (panelVisible) this.marchPanel?.show()
    this.resetGesture()
  }

  override onDestroy(): void {
    // 必须退订并销毁池：场景切换后订阅还在的话，下一次数据变化会往已销毁的节点上写字，
    // 表现为「切场景后偶发报错」，这类 bug 极难复现（B07 §4：chunk 卸载时正确释放）
    for (const unsubscribe of this.unsubscribes) {
      unsubscribe()
    }
    this.unsubscribes.length = 0
    this.modelSubscription?.()
    this.modelSubscription = null
    this.tilePool?.destroy()
    this.entityPool?.destroy()
    this.marchPool?.destroy()
    this.marchPanel?.destroy()
    this.tilePool = null
    this.entityPool = null
    this.marchPool = null
    this.marchPanel = null
    this.refs.clear()
    this.drawnEntities.clear()
    this.drawnMarches.clear()
    this.drawnTiles.clear()
    this.backdropNode = null
    this.backdropPainted = false
    this.flash.clear()
    this.activeTouches.clear()
    this.marchRequesting.clear()
    this.marchButtonCaption = null
    this.onEnterCity = null
  }

  override onDisable(): void {
    this.resetGesture()
  }

  override update(): void {
    this.syncViewport()
    const model = worldModel()
    if (model === null) {
      this.showHint('未连接世界服务')
      return
    }
    if (this.actionHintUntil !== 0 && sys.now() >= this.actionHintUntil) {
      this.actionHint = ''
      this.actionHintUntil = 0
    }
    this.showHint(this.actionHintUntil === 0 ? null : this.actionHint)
    this.refreshExileCaption()
    this.pumpRequests(model)
    this.render(model.frame(sys.now()), model)
  }

  // ---------- 对外接入点（编辑器里的手势与按钮调这些） ----------

  /** 放大一档。到顶（档位 2）时切换到城内场景（B07 §1）。 */
  zoomIn(): void {
    const model = worldModel()
    if (model === null) {
      return
    }
    const next = model.currentZoom() + 1
    if (next > 2) {
      return
    }
    if (next === 2) {
      // 当前工程只有 Boot 场景，内城是同一场景里的可切换面板；加载不存在的
      // MainCity 场景会让第二次「放大」直接报 1209，表现正是“缩放到城市档就坏了”。
      if (this.onEnterCity === null) {
        this.showHint('内城面板还没接入')
        return
      }
      this.resetGesture()
      this.onEnterCity()
      return
    }
    model.setZoom(next)
  }

  /** 缩小一档。 */
  zoomOut(): void {
    const model = worldModel()
    if (model === null) {
      return
    }
    const next = model.currentZoom() - 1
    if (next < 0) {
      return
    }
    model.setZoom(next)
  }

  /**
   * 把视野中心移到某个格子（坐标跳转、书签、一键回城都走这里）。
   *
   * <p>同时把拖动累积器清零：跳转后相机必须精确对准目标格，
   * 否则上一次的半格余量会让「跳到 (100,100)」实际停在 (100.4, 99.7)。
   * 越界坐标由模型自己夹到世界边界，本方法不做判定（铁律 2）。
   */
  focusCoord(x: number, y: number): void {
    const model = worldModel()
    if (model === null) {
      return
    }
    this.panAccumX = 0
    this.panAccumY = 0
    model.setCenter({ x, y })
  }

  /**
   * 接收家坐标（B07 §1）。家坐标的权威在 MarchListResp.home，本场景只记住最后一次收到的值，
   * 供「回城」按钮使用 —— 这样按钮不需要自己去拉数据，也不会在没数据时假装能跳。
   */
  focusHome(homeX: number, homeY: number): void {
    worldModel()?.setHome({ x: homeX, y: homeY })
    this.focusCoord(homeX, homeY)
  }

  /** 回城：从世界模型读取权威家坐标。 */
  private backHome(): void {
    const model = worldModel()
    if (model === null) {
      this.showHint('未连接世界服务')
      return
    }
    const home = model.home()
    this.focusCoord(home.x, home.y)
  }

  // ---------- 搭建 ----------

  /**
   * 建一个铺满整屏的层。
   *
   * <p><b>尺寸不能省</b>：触摸命中会沿着节点树做矩形测试，中间层没有 UITransform
   * （或尺寸为 0）时，它下面的按钮全部收不到事件 —— 表现是「按钮看得见、点不动」。
   * 地图拖拽之所以先坏后好，也是同一条。
   */
  private buildLayer(name: string, width: number, height: number): Node {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(width, height)
    return node
  }

  /** mapLayer 上的一张共用 Graphics 覆盖层（行军线、选中环这类"每帧重画"的装饰）。 */
  private createOverlayGraphics(name: string): Node {
    const node = new Node(name)
    node.layer = this.node.layer
    this.mapLayer?.addChild(node)
    node.addComponent(UITransform)
    node.addComponent(Graphics)
    node.active = false
    return node
  }

  private buildBackground(width: number, height: number): void {
    const background = new Node('Background')
    background.layer = this.node.layer
    this.node.addChild(background)
    const transform = background.addComponent(UITransform)
    transform.setContentSize(width, height)
    const graphics = background.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
    // 3×3块外仍有世界，但尚未收到数据；不透明雾底衬盖满画布，不能用假草地补洞。
    const backdrop = new Node('BackgroundTerrain')
    backdrop.layer = background.layer
    background.addChild(backdrop)
    backdrop.addComponent(UITransform)
    this.backdropNode = backdrop
  }

  /** 视野外一律是战争迷雾：底衬用迷雾色铺满视口，3×3 已加载块就是雾里的一块
   * 已探索区 —— 比露虚空（玩家读成"地图残缺"）或铺草地（把未探索伪装成已探索）都诚实。 */
  private paintBackdrop(): void {
    const backdrop = this.backdropNode
    if (backdrop === null || this.backdropPainted) {
      return
    }
    const size = view.getVisibleSize()
    backdrop.getComponent(UITransform)?.setContentSize(size.width, size.height)
    const graphics = backdrop.getComponent(Graphics) ?? backdrop.addComponent(Graphics)
    this.drawFog(graphics, size.width, size.height)
    this.backdropPainted = true
  }

  /** 全视口同一层迷雾：没有按 chunk 重复的椭圆/接缝，完整不透明底色不泄露地形。 */
  private drawFog(graphics: Graphics, width: number, height: number): void {
    graphics.clear()
    graphics.fillColor = COLOR_FOG
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
    for (const contour of worldFogContours(width, height)) {
      graphics.fillColor = new Color(45, 49, 55, contour.opacity)
      for (let index = 0; index < contour.points.length; index++) {
        const point = contour.points[index]!
        if (index === 0) graphics.moveTo(point.x, point.y)
        else graphics.lineTo(point.x, point.y)
      }
      graphics.close()
      graphics.fill()
    }
    graphics.fillColor = COLOR_FOG
  }

  /** 复用薄边按钮资源；操作条换行，坐标与提示留在独立状态行。 */
  private buildHud(width: number, height: number): void {
    const toolbar = new Node('WorldToolbar')
    toolbar.layer = this.node.layer
    const parent = this.hudLayer ?? this.node
    parent.addChild(toolbar)
    toolbar.addComponent(UITransform).setContentSize(width, height)
    this.toolbarNode = toolbar
    const graphics = toolbar.addComponent(Graphics)
    graphics.fillColor = new Color(20, 18, 17, 242)
    graphics.rect(-width / 2, height / 2 - this.sceneLayout.hudHeight, width, this.sceneLayout.hudHeight)
    graphics.fill()
    graphics.strokeColor = COLOR_PLATE_EDGE
    graphics.lineWidth = 1
    graphics.moveTo(-width / 2, height / 2 - this.sceneLayout.hudHeight)
    graphics.lineTo(width / 2, height / 2 - this.sceneLayout.hudHeight)
    graphics.stroke()

    const coordWidth = Math.min(230, width * 0.45)
    this.coordLabel = this.createHudLabel('CoordLabel', '', -width / 2 + 12, this.sceneLayout.statusY)
    this.coordLabel.horizontalAlign = Label.HorizontalAlign.LEFT
    this.coordLabel.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    this.coordLabel.node.getComponent(UITransform)?.setContentSize(coordWidth, 26)

    this.hintLabel = this.createHudLabel('HintLabel', '', width / 2 - 12, this.sceneLayout.statusY)
    this.hintLabel.horizontalAlign = Label.HorizontalAlign.RIGHT
    this.hintLabel.color = COLOR_TEXT_DIM
    this.hintLabel.fontSize = 16
    this.hintLabel.node.getComponent(UITransform)?.setContentSize(Math.max(1, width - coordWidth - 36), 26)

    const buttons: Array<{ name: string; text: string; onTap: () => void }> = [
      { name: 'ZoomInButton', text: '放大', onTap: () => this.zoomIn() },
      { name: 'ZoomOutButton', text: '缩小', onTap: () => this.zoomOut() },
      { name: 'HomeButton', text: '回城', onTap: () => this.backHome() },
    ]
    for (const button of buttons) {
      const position = this.sceneLayout.buttons.find((entry) => entry.name === button.name)!
      const node = this.createButton(button.name, button.text, position.x, position.y, position.width)
      node.on('touch-start', button.onTap, this)
    }

    // 流亡迁城的按钮文字要显示冷却倒计时与确认状态，所以不走上面那个固定文案的数组
    const exilePosition = this.sceneLayout.buttons.find((entry) => entry.name === 'ExileButton')!
    const exileNode = this.createButton('ExileButton', '流亡',
      exilePosition.x, exilePosition.y, exilePosition.width)
    this.exileCaption = exileNode.getChildByName('ExileButton_Caption')?.getComponent(Label) ?? null
    exileNode.on('touch-start', () => this.requestExile(), this)
    const marchPosition = this.sceneLayout.buttons.find((entry) => entry.name === 'MarchButton')!
    const marchNode = this.createButton('MarchButton', '行军',
      marchPosition.x, marchPosition.y, marchPosition.width)
    this.marchButtonCaption = marchNode.getChildByName('MarchButton_Caption')?.getComponent(Label) ?? null
    marchNode.on('touch-start', () => this.toggleMarchPanel(), this)
  }

  private createHudLabel(name: string, text: string, x: number, y: number): Label {
    const node = new Node(name)
    node.layer = this.node.layer
    if (this.toolbarNode !== null) {
      this.toolbarNode.addChild(node)
    } else {
      this.node.addChild(node)
    }
    const transform = node.addComponent(UITransform)
    transform.setContentSize(320, 28)
    transform.setAnchorPoint(1, 0.5)
    node.setPosition(new Vec3(x, y, 0))
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = text
    label.color = COLOR_TEXT
    label.fontSize = 16
    label.overflow = Label.Overflow.SHRINK
    label.verticalAlign = Label.VerticalAlign.CENTER
    return label
  }

  private createButton(name: string, text: string, x: number, y: number,
                       width: number): Node {
    const node = new Node(name)
    node.layer = this.node.layer
    if (this.toolbarNode !== null) {
      this.toolbarNode.addChild(node)
    } else {
      this.node.addChild(node)
    }
    node.setPosition(new Vec3(x, y, 0))
    const transform = node.addComponent(UITransform)
    transform.setContentSize(width, WORLD_BUTTON_HEIGHT)
    if (!applyCommandButton(node, 'normal', width, WORLD_BUTTON_HEIGHT)) {
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = COLOR_GROUND_GRID
      graphics.strokeColor = COLOR_MARCH
      graphics.lineWidth = 2
      graphics.roundRect(-width / 2, -WORLD_BUTTON_HEIGHT / 2, width, WORLD_BUTTON_HEIGHT, 4)
      graphics.fill()
      graphics.stroke()
    }

    const caption = new Node(`${name}_Caption`)
    caption.layer = node.layer
    node.addChild(caption)
    caption.addComponent(UITransform).setContentSize(width - 8, WORLD_BUTTON_HEIGHT - 4)
    const label = applySystemUiFont(caption.addComponent(Label))
    label.string = text
    label.color = COLOR_TEXT
    label.fontSize = 16
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    label.overflow = Label.Overflow.SHRINK
    return node
  }

  /**
   * 造一个池化节点：本体一块 Graphics，子节点一个 Label（等级 / 昵称）。
   *
   * <p>Label 放在子节点而不是本体上：Graphics 与 Label 都是渲染组件，
   * 挂在同一个节点上时两者的渲染顺序由引擎内部决定，压字的情况在编辑器里改不动。
   */
  private createMarker(): Node {
    const node = new Node('Marker')
    node.layer = this.node.layer
    node.addComponent(UITransform)
    const spriteNode = new Node('Art')
    spriteNode.layer = node.layer
    node.addChild(spriteNode)
    spriteNode.addComponent(UITransform)
    spriteNode.active = false
    const graphicsNode = new Node('FallbackGraphics')
    graphicsNode.layer = node.layer
    node.addChild(graphicsNode)
    graphicsNode.addComponent(UITransform)
    const graphics = graphicsNode.addComponent(Graphics)
    const plateNode = new Node('CaptionPlate')
    plateNode.layer = node.layer
    node.addChild(plateNode)
    plateNode.addComponent(UITransform)
    const plate = plateNode.addComponent(Graphics)
    const caption = new Node('Caption')
    caption.layer = node.layer
    node.addChild(caption)
    caption.addComponent(UITransform)
    const label = applySystemUiFont(caption.addComponent(Label))
    label.fontSize = 12
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    label.color = COLOR_TEXT
    this.refs.set(node, { spriteNode, graphicsNode, graphics, label, plate, size: 0 })
    return node
  }

  /** 触摸读取当前布局；resize 后不能继续用首次创建时的高度判断顶栏。 */
  private bindInput(): void {
    this.node.on('touch-start', (event: EventTouch) => {
      this.syncActiveTouches(event)
      if (this.activeTouches.size >= 2) {
        this.tapStart = null
        this.beginPinch()
        return
      }
      const start = event.getUILocation()
      this.tapStart = { x: start.x, y: start.y, at: sys.now() }
      this.pinchAnchorDistance = null
      const suppressed = this.suppressDragUntilRelease
      if (!suppressed) {
        this.suppressDragUntilRelease = false
      }
      // 落在 HUD 条带里的触摸不启动拖动，否则点按钮的同时会把地图拖走
      this.dragging = !suppressed
        && !(this.marchPanel?.isVisible ?? false)
        && worldMapInputAllowed(this.sceneLayout, event.getUILocation().y)
    }, this)
    this.node.on('touch-move', (event: EventTouch) => {
      this.syncActiveTouches(event)
      if (this.activeTouches.size >= 2) {
        if (this.pinchAnchorDistance === null) {
          this.beginPinch()
        }
        this.onPinch()
        return
      }
      if (this.suppressDragUntilRelease) {
        return
      }
      if (!this.dragging) {
        return
      }
      this.onDrag(event)
    }, this)
    const stop = (event: EventTouch): void => {
      this.syncActiveTouches(event)
      if (this.activeTouches.size >= 2) {
        if (this.pinchAnchorDistance === null) {
          this.beginPinch()
        }
        return
      }
      const wasDragging = this.dragging
      this.dragging = false
      // 一次"点击"= 单指、按下期间没被双指手势污染、位移小于 10px、没超过 400ms。
      // 判定放这里而不是 touch-end 单独监听：两条通道并存会出现"同一下既算拖又算点"。
      const tap = this.tapStart
      this.tapStart = null
      if (tap !== null && wasDragging && !this.pinchZoomTriggered
        && this.activeTouches.size === 0) {
        const end = event.getUILocation()
        const moved = Math.hypot(end.x - tap.x, end.y - tap.y)
        if (moved < 10 && sys.now() - tap.at < 400) {
          this.handleTap(end)
        }
      }
      // 双指抬起一根时，getAllTouches 仍可能只剩一根；必须等归零，否则剩余手指会立刻拖动。
      if (this.activeTouches.size === 0) {
        this.pinchAnchorDistance = null
        this.suppressDragUntilRelease = false
      }
    }
    this.node.on('touch-end', stop, this)
    this.node.on('touch-cancel', stop, this)
  }

  /**
   * 同步当前屏幕上仍有效的触点。
   *
   * <p>不能拿每帧的 `getDelta()` 来识别多指：两根手指分别移动时，每个事件都只有一个
   * delta，天然会被人当作单指拖动。触点数达到两个后必须立即锁住拖动，改用双指间距判定。
   */
  private syncActiveTouches(event: EventTouch): void {
    const next = new Map<number, GesturePoint>()
    for (const touch of event.getAllTouches()) {
      const id = touch.getID()
      if (id === null) {
        continue
      }
      const location = touch.getUILocation()
      next.set(id, { x: location.x, y: location.y })
    }
    this.activeTouches.clear()
    for (const [id, point] of next) {
      this.activeTouches.set(id, point)
    }
  }

  private beginPinch(): void {
    this.dragging = false
    this.suppressDragUntilRelease = true
    this.pinchZoomTriggered = false
    const points = Array.from(this.activeTouches.values())
    this.pinchAnchorDistance = !(this.marchPanel?.isVisible ?? false)
      && points.every((point) => worldMapInputAllowed(this.sceneLayout, point.y))
      ? pinchDistance(points) : null
  }

  /** 双指间距越过一档阈值才缩放；整个手势期间不把双指平移写成相机拖动。 */
  private onPinch(): void {
    const current = pinchDistance(Array.from(this.activeTouches.values()))
    const anchor = this.pinchAnchorDistance
    if (current === null || anchor === null) {
      return
    }
    const action = pinchZoomAction(current, anchor)
    if (action === null || this.pinchZoomTriggered) {
      return
    }
    this.pinchAnchorDistance = current
    this.pinchZoomTriggered = true
    if (action === 'zoom-in') {
      this.zoomIn()
    } else {
      this.zoomOut()
    }
  }

  private resetGesture(): void {
    this.dragging = false
    this.activeTouches.clear()
    this.pinchAnchorDistance = null
    this.pinchZoomTriggered = false
    this.suppressDragUntilRelease = false
  }

  /**
   * 拖动。累积到整格才推给模型：模型的 setCenter 只认整数格，
   * 而触摸位移是像素级的小数，直接取整会让慢速拖动完全不动（每次位移都被截断成 0）。
   * 小数部分留给渲染相机，画面因此是平滑的。
   */
  private onDrag(event: EventTouch): void {
    const model = worldModel()
    if (model === null) {
      return
    }
    const cell = cellPixels(model.currentZoom(), this.baseCell(model))
    const delta = event.getDelta()
    // 手指向右拖，地图跟着向右走 ⇒ 相机向左移，所以取负
    this.panAccumX += -delta.x / cell
    this.panAccumY += -delta.y / cell

    const stepX = wholeCells(this.panAccumX)
    const stepY = wholeCells(this.panAccumY)
    if (stepX === 0 && stepY === 0) {
      return
    }
    this.panAccumX -= stepX
    this.panAccumY -= stepY
    const center = model.center()
    model.setCenter({ x: center.x + stepX, y: center.y + stepY })
  }

  private bindModel(): void {
    // 模型可能晚于场景就绪（登录后才 initialize），所以每帧在 update 里补订阅
    const model = worldModel()
    if (model === null || this.modelSubscription !== null) {
      return
    }
    this.modelSubscription = model.subscribe(() => {
      // 数据变化时立刻重绘一帧，不等 update：否则「刚打掉的野怪」要过一帧才消失
      this.render(model.frame(sys.now()), model)
    })
    this.unsubscribes.push(() => {
      this.modelSubscription?.()
      this.modelSubscription = null
    })
  }

  // ---------- 请求 ----------

  /**
   * 把「该拉数据了」翻译成一次请求。
   *
   * <p>场景只表达意图，不碰传输：requester 由适配层通过 bindWorldRequester 注入。
   * 没有注入时（编辑器预览）本方法什么也不做，地图就停在已有数据上。
   */
  private pumpRequests(model: WorldViewModel): void {
    const requester = worldRequester()
    if (requester === null) {
      return
    }
    if (!this.marchesRequested) {
      requester.marches()
      this.marchesRequested = true
    }
    const req = model.nextRequest()
    if (req === null) {
      return
    }
    requester.viewport(req)
    // 立刻标记已发出：否则响应回来之前每帧都会重发同一个请求
    model.markRequested()
  }

  // ---------- 渲染 ----------

  /**
   * 当前画布下"铺满一屏"对应的一格像素。每次渲染现读可见尺寸，
   * 这样窗口尺寸、设计分辨率适配、以及以后加分屏/横竖屏切换都不用再回来改常量。
   */
  private baseCell(model: WorldViewModel): number {
    return baseCellPixels(this.sceneLayout.width, this.sceneLayout.mapHeight, model.chunkSize)
  }

  private render(frame: WorldFrame, model: WorldViewModel): void {
    this.syncViewport()
    this.bindModel()
    const zoom = frame.zoom
    const cell = cellPixels(zoom, this.baseCell(model))
    if (this.mapLayer === null) {
      return
    }
    const cameraX = frame.center.x + this.panAccumX
    const cameraY = frame.center.y + this.panAccumY
    const translation = worldMapTranslation(this.sceneLayout, cameraX, cameraY, cell)
    this.mapLayer.setPosition(new Vec3(translation.x, translation.y, 0))

    this.renderTiles(frame.tiles, model.chunkSize, cell)
    // 候选表每帧在实体与行军两侧**之前**清空（放在任何一侧里都会漏：那一侧提前 return 时，
    // 上一帧的 refs 会被当成这一帧的候选重画一遍）
    this.pendingPlates.length = 0
    this.renderEntities(frame.tiles, cell, zoom)
    this.renderMarches(frame.marches, cell)
    // 藏牌要**跨两个池**一起判：行军牌画在实体牌之后，各判各的就会留下"自家队伍的牌压在资源牌上"
    this.applyCaptions(cameraX, cameraY, cell)
    this.updateSelection()
    this.renderHud(frame)
    this.renderMarchPanel(frame.marches)
  }

  private renderTiles(tiles: readonly ChunkTile[], chunkSize: number, cell: number): void {
    const pool = this.tilePool
    if (pool === null) {
      return
    }
    this.paintBackdrop()
    const seen = new Set<string>()
    for (const tile of tiles) {
      seen.add(tile.key)
      let node = this.drawnTiles.get(tile.key)
      if (node === undefined) {
        node = pool.acquire()
        this.drawnTiles.set(tile.key, node)
      }
      const size = chunkSize * cell
      node.setPosition(new Vec3((tile.cx + 0.5) * size, (tile.cy + 0.5) * size, 0))
      // 节点盒子要跟上画出来的边长：地形按 size 画，UITransform 却停在池默认的 100×100。
      // 今天没有一条命中线读块盒子（点选是把 UI 坐标折进 mapLayer 后按半径找实体），
      // 但"可见范围与节点范围分家"这件事一旦哪天被裁剪或命中读到，就是个查不出来的缺陷。
      node.getComponent(UITransform)?.setContentSize(new Size(size, size))
      const refs = this.refs.get(node)
      if (refs === undefined) {
        continue
      }
      const terrainVariant = terrainVariantForChunk(tile.cx, tile.cy)
      const signature = `${tile.key}:${size}:${tile.fogged}:${tile.loaded}`
      if (tile.fogged) {
        // 没画未探索地形，露出的只有下方同一张不透明雾底，邻块之间没有纹样拼缝。
        refs.spriteNode.active = false
        refs.graphicsNode.active = false
        if (refs.tilePaintSignature !== signature) {
          refs.graphics.clear()
          refs.tilePaintSignature = signature
        }
        refs.label.string = ''
        continue
      }
      const terrainFrame = artFrame(terrainArtKey(terrainVariant))
      const grassFrame = artFrame('map.terrain.grass')
      const artApplied = !tile.fogged && tile.loaded
        && (applyTerrainSprite(refs.spriteNode, terrainVariant, size, size,
          worldTerrainTileScale(size, terrainFrame?.rect.width ?? 1))
          || applyTiledSprite(refs.spriteNode, 'map.terrain.grass', size, size,
            worldTerrainTileScale(size, grassFrame?.rect.width ?? 1)))
      refs.spriteNode.active = artApplied
      refs.graphicsNode.active = !artApplied
      if (artApplied) delete refs.tilePaintSignature
      if (!artApplied) {
        const graphics = refs.graphics
        graphics.enabled = true
        if (refs.tilePaintSignature !== signature) {
          refs.tilePaintSignature = signature
          graphics.clear()
          graphics.fillColor = tileColor(tile)
          graphics.rect(-size / 2, -size / 2, size, size)
          graphics.fill()
          if (tile.loaded) {
            graphics.strokeColor = COLOR_GROUND_GRID
            graphics.lineWidth = 1
            graphics.rect(-size / 2, -size / 2, size, size)
            graphics.stroke()
          }
        }
      }
      refs.label.string = ''
    }
    // 离开视野的块立刻归还池子 —— 这是验收 3「内存不随拖动增长」的落地点
    for (const [key, node] of Array.from(this.drawnTiles)) {
      if (!seen.has(key)) {
        pool.release(node)
        this.drawnTiles.delete(key)
      }
    }
  }

  private renderEntities(tiles: readonly ChunkTile[], cell: number, zoom: number): void {
    const pool = this.entityPool
    if (pool === null) {
      return
    }
    const seen = new Set<string>()
    for (const tile of tiles) {
      if (tile.fogged) {
        // 迷雾块的实体服务端本来就不下发，这里再挡一道：万一哪天下发了也不能画出来
        continue
      }
      for (const entity of tile.entities) {
        const key = `${entity.type}:${entity.id}`
        seen.add(key)
        const node = this.acquireInto(pool, this.drawnEntities, key)
        const x = (entity.x + 0.5) * cell
        const y = (entity.y + 0.5) * cell
        node.setPosition(new Vec3(x, y, 0))
        const size = entitySize(entity.type, cell)
        this.drawBody(node, entity.type, size, entityColor(entity.type))
        this.pendingPlates.push({
          refs: this.refs.get(node), key, type: entity.type, size, x, y,
          caption: zoom > 0 ? entityCaption(entity) : '',
        })
      }
    }
    this.recycle(pool, this.drawnEntities, seen)
  }

  /**
   * 一帧里所有名牌的候选：实体与行军**合在一起判**才藏得干净 ——
   * 行军牌画在实体牌之后，各判各的就留下"自家队伍的 `3分12秒` 压在资源牌上"这种形状。
   */
  private readonly pendingPlates: {
    refs: MarkerRefs | undefined
    key: string
    type: WorldEntityType
    size: number
    x: number
    y: number
    caption: string
  }[] = []

  /** 藏牌口径在 `game/world/WorldLabels.ts`（引擎无关、可单测），这里只负责"按结论画或不画"。 */
  private applyCaptions(cameraX: number, cameraY: number, cell: number): void {
    const viewport = worldCaptionViewport(this.sceneLayout, cameraX, cameraY, cell)
    const withCaption = this.pendingPlates.filter((entry) => entry.caption !== '')
    const visible = pickVisibleCaptions(withCaption.map((entry) => ({
      key: entry.key,
      type: entry.type,
      box: captionBox(entry.x, entry.y, entry.size, entry.caption),
      pinned: entry.key === this.selectedKey,
    })), viewport)
    for (const entry of this.pendingPlates) {
      if (entry.refs === undefined) {
        continue
      }
      this.drawCaptionPlate(entry.refs, visible.has(entry.key) ? entry.caption : '', entry.size)
    }
  }

  private renderMarches(marches: readonly MarchRender[], cell: number): void {
    const pool = this.marchPool
    if (pool === null) {
      return
    }
    const lines = this.marchLines
    const lineGraphics = lines !== null ? lines.getComponent(Graphics) : null
    if (lines !== null && lineGraphics !== null) {
      lines.active = marches.length > 0
      lineGraphics.clear()
    }
    const seen = new Set<string>()
    for (const march of marches) {
      seen.add(march.marchId)
      if (march.corrected) {
        // 一次性信号：记下要闪几帧。不做平滑过渡 —— 验收 10 要的是「立即纠偏」，
        // 平滑意味着在一段时间内继续显示错误位置
        this.flash.set(march.marchId, CORRECTED_FLASH_FRAMES)
      }
      const node = this.acquireInto(pool, this.drawnMarches, march.marchId)
      node.setPosition(new Vec3((march.x + 0.5) * cell, (march.y + 0.5) * cell, 0))
      if (lines !== null && lineGraphics !== null) {
        this.drawMarchTrail(lineGraphics, march, cell)
      }
      const remaining = this.flash.get(march.marchId) ?? 0
      if (remaining > 0) {
        this.flash.set(march.marchId, remaining - 1)
      } else {
        this.flash.delete(march.marchId)
      }
      const color = remaining > 0 ? COLOR_CORRECTED : COLOR_MARCH
      const size = cell * MARCH_SIZE_RATIO
      const x = (march.x + 0.5) * cell
      const y = (march.y + 0.5) * cell
      this.drawBody(node, 'MARCH', size, color)
      this.pendingPlates.push({
        refs: this.refs.get(node), key: `MARCH:${march.marchId}`, type: 'MARCH', size, x, y,
        caption: formatRemaining(march.remainingMs),
      })
    }
        for (const marchId of Array.from(this.flash.keys())) {
      if (!seen.has(marchId)) {
        this.flash.delete(marchId)
      }
    }
    this.recycle(pool, this.drawnMarches, seen)
  }

  private renderHud(frame: WorldFrame): void {
    if (this.coordLabel === null) {
      return
    }
    const cameraX = Math.round(frame.center.x + this.panAccumX)
    const cameraY = Math.round(frame.center.y + this.panAccumY)
    this.coordLabel.string = `(${cameraX}, ${cameraY}) · 缩放 ${frame.zoom}`
    const caption = frame.marches.length === 0 ? '行军' : `行军 ${frame.marches.length}`
    if (this.marchButtonCaption !== null && caption !== this.lastMarchButtonCaption) {
      this.lastMarchButtonCaption = caption
      this.marchButtonCaption.string = caption
    }
  }

  private toggleMarchPanel(): void {
    this.resetGesture()
    this.marchPanel?.toggle()
  }

  /** 面板打开时在数据变化后重画；关闭时不创建任何额外节点工作。 */
  private renderMarchPanel(marches: readonly MarchRender[]): void {
    const panel = this.marchPanel
    if (panel === null || !panel.isVisible) {
      return
    }
    panel.render(buildMarchPanel(marches, 3), this.marchRequesting)
  }

  private requestMarchAction(action: MarchPanelAction, marchId: string): void {
    const requester = worldRequester()
    if (requester === null || this.marchRequesting.has(marchId)) {
      return
    }
    this.marchRequesting.add(marchId)
    this.renderMarchPanel(worldModel()?.frame(sys.now()).marches ?? [])
    const request = action === 'collect'
      ? requester.collectGather(marchId)
      : requester.recall(marchId)
    void request.then(
      (result) => this.finishMarchAction(marchId, result.message),
      (error: unknown) => {
        console.error('[WorldMap] 行军操作失败', error)
        this.finishMarchAction(marchId, '操作失败，请稍后重试')
      },
    )
  }

  private finishMarchAction(marchId: string, message: string): void {
    this.marchRequesting.delete(marchId)
    this.actionHint = message
    this.actionHintUntil = sys.now() + 4_000
    this.showHint(message)
    const model = worldModel()
    if (model !== null) {
      this.render(model.frame(sys.now()), model)
    }
  }

  private acquireInto(pool: NodePool, drawn: Map<string, Node>, key: string): Node {
    const existing = drawn.get(key)
    if (existing !== undefined) {
      return existing
    }
    const node = pool.acquire()
    drawn.set(key, node)
    return node
  }

  private recycle(pool: NodePool, drawn: Map<string, Node>, seen: ReadonlySet<string>): void {
        for (const [key, node] of Array.from(drawn)) {
      if (!seen.has(key)) {
        pool.release(node)
        drawn.delete(key)
      }
    }
  }

  /** 只画图标那一块。名牌不在这里画 —— 它要等一整帧的候选凑齐后由 {@link applyCaptions} 统一判。 */
  private drawBody(node: Node, type: WorldEntityType, size: number, color: Color): void {
    const refs = this.refs.get(node)
    if (refs === undefined) {
      return
    }
    refs.size = size
    const art = entityArtKey(type)
    if (art !== null && applySimpleSprite(refs.spriteNode, art, size, size)) {
      refs.spriteNode.active = true
      refs.graphicsNode.active = false
      return
    }
    refs.spriteNode.active = false
    refs.graphicsNode.active = true
    const graphics = refs.graphics
    graphics.enabled = true
    graphics.clear()
    graphics.fillColor = color
    if (type === 'CITY' || type === 'BUILDING') {
      // 城与联盟建筑用方块，其余用圆角块：占位美术阶段先靠形状区分，正式美术换成图标
      graphics.rect(-size / 2, -size / 2, size, size)
    } else {
      graphics.roundRect(-size / 2, -size / 2, size, size, size / 4)
    }
    graphics.fill()
  }

  /**
   * 名牌：暗底 + 铜色描边的圆角小牌，文字浮在牌上 —— 同类 SLG 在大地图上标注
   * 昵称/等级的统一语言。以前文字直接压在草地上，缩放一档就糊进地形里读不出来。
   * 宽度按字数估（CJK 12px 字号 ≈ 每字 13px），上限 150，超长的昵称自然截断在牌内。
   */
  private drawCaptionPlate(refs: MarkerRefs, caption: string, size: number): void {
    const label = refs.label
    const plate = refs.plate
    const plateNode = plate.node
    const plateBox = plateNode.getComponent(UITransform)
    const y = size / 2 + 11
    if (caption === '') {
      label.string = ''
      plate.enabled = false
      // 板子盒子归零：池化节点会带着上一位的尺寸复用，留着旧盒子就等于"藏起来的牌还在占位"
      plateBox?.setContentSize(new Size(0, 0))
      return
    }
    plate.enabled = true
    label.string = caption
    label.node.setPosition(new Vec3(0, y, 0))
    const width = captionPlateWidth(caption)
    /**
     * 板子节点自己承担"画出来的那一块"的几何：节点移到 y、盒子设成 width×板高、图形在自身中心画。
     *
     * <p>原来节点停在 (0,0)、图形按 `y-9` 偏着画，于是 `CaptionPlate` 的盒子与实际板子分家；
     * 而 `Caption` 节点的 `UITransform` 由 `Label` 每帧按文字重写（实测 24×50 —— 那是**文字盒**，
     * 不是板子）。两处都不能用来判"两张牌压不压叠"，#275 第一次量出的"2 对压叠"就是拿文字盒量的。
     */
    plateBox?.setAnchorPoint(0.5, 0.5)
    plateBox?.setContentSize(new Size(width, CAPTION_PLATE_HEIGHT))
    plateNode.setPosition(new Vec3(0, y, 0))
    plate.clear()
    plate.fillColor = COLOR_PLATE
    plate.roundRect(-width / 2, -CAPTION_PLATE_HEIGHT / 2, width, CAPTION_PLATE_HEIGHT, 5)
    plate.fill()
    plate.strokeColor = COLOR_PLATE_EDGE
    plate.lineWidth = 1
    plate.roundRect(-width / 2, -CAPTION_PLATE_HEIGHT / 2, width, CAPTION_PLATE_HEIGHT, 5)
    plate.stroke()
  }

  /** 点击命中：把 UI 坐标折进 mapLayer 本地系，找半径内最近的实体。点空处清选中。 */
  private handleTap(uiLocation: { x: number; y: number }): void {
    const layer = this.mapLayer
    const transform = layer !== null ? layer.getComponent(UITransform) : null
    if (layer === null || transform === null) {
      return
    }
    const local = transform.convertToNodeSpaceAR(new Vec3(uiLocation.x, uiLocation.y, 0))
    let bestKey: string | null = null
    let bestDistance = Number.POSITIVE_INFINITY
    for (const [key, node] of this.drawnEntities) {
      const refs = this.refs.get(node)
      if (refs === undefined) {
        continue
      }
      const distance = Math.hypot(node.position.x - local.x, node.position.y - local.y)
      if (distance < refs.size * 0.62 + 6 && distance < bestDistance) {
        bestKey = key
        bestDistance = distance
      }
    }
    this.selectedKey = bestKey
  }

  /** 选中环：脚下铜圈 + 四向刻度。实体滑出视野时环跟着藏，滑回来还在 —— 键不清，只藏环。 */
  private updateSelection(): void {
    const ring = this.selectionRing
    if (ring === null) {
      return
    }
    const node = this.selectedKey === null ? undefined : this.drawnEntities.get(this.selectedKey)
    const refs = node === undefined ? undefined : this.refs.get(node)
    if (node === undefined || refs === undefined) {
      ring.active = false
      return
    }
    ring.active = true
    ring.setPosition(node.position)
    const graphics = ring.getComponent(Graphics)
    if (graphics === null) {
      return
    }
    const radius = refs.size * 0.62 + 6
    graphics.clear()
    graphics.strokeColor = COLOR_MARCH
    graphics.lineWidth = 2
    graphics.circle(0, 0, radius)
    graphics.stroke()
    for (const [cos, sin] of [[1, 0], [-1, 0], [0, 1], [0, -1]] as const) {
      graphics.moveTo(cos * (radius - 4), sin * (radius - 4))
      graphics.lineTo(cos * (radius + 5), sin * (radius + 5))
    }
    graphics.stroke()
  }

  /**
   * 一条行军的轨迹：出发→当前画压暗实线（走过的路），当前→目标画虚线（还要走的），
   * 目标点一个小环。同类 SLG 的行军可读性全靠这三件套 —— 没有线，玩家只剩一个
   * 不知道从哪来、往哪去的图标。数据全用现帧的插值位置，不另起一条时间线。
   */
  private drawMarchTrail(graphics: Graphics, march: MarchRender, cell: number): void {
    const fx = (march.from.x + 0.5) * cell
    const fy = (march.from.y + 0.5) * cell
    const cx = (march.x + 0.5) * cell
    const cy = (march.y + 0.5) * cell
    const tx = (march.to.x + 0.5) * cell
    const ty = (march.to.y + 0.5) * cell
    graphics.lineWidth = 2
    graphics.strokeColor = new Color(184, 134, 11, 110)
    graphics.moveTo(fx, fy)
    graphics.lineTo(cx, cy)
    graphics.stroke()
    graphics.strokeColor = new Color(226, 214, 190, 190)
    const dx = tx - cx
    const dy = ty - cy
    const length = Math.hypot(dx, dy)
    if (length > 1) {
      const step = 10
      const gap = 7
      let travelled = 0
      while (travelled < length) {
        const from = travelled / length
        const to = Math.min(1, (travelled + step) / length)
        graphics.moveTo(cx + dx * from, cy + dy * from)
        graphics.lineTo(cx + dx * to, cy + dy * to)
        travelled += step + gap
      }
      graphics.stroke()
    }
    graphics.strokeColor = new Color(226, 214, 190, 150)
    graphics.circle(tx, ty, Math.max(5, cell * 0.16))
    graphics.stroke()
  }

  /**
   * 每帧刷新流亡按钮的文字。
   *
   * <p>判定与文案全部来自 `game/world/ExileAction`（纯函数、有单测），场景只负责把它画出来。
   * 服务端当前时刻用「最后一次看到快照那一刻的本地时钟 + 本地流逝时间」推出来 ——
   * 场景里再写一份冷却算法，就是第二个事实来源，两端各算一份必然漂。
   */
  private refreshExileCaption(): void {
    if (this.exileCaption === null) {
      return
    }
    const snap = exileSnapshot()
    if (snap !== this.lastExileSnapshot) {
      this.lastExileSnapshot = snap
      this.exileSnapshotAt = sys.now()
    }
    const facts = {
      nextExileAt: snap.nextExileAt,
      troopsAway: snap.troopsAway,
      requesting: this.exileRequesting,
      serverNow: snap.serverNow + (sys.now() - this.exileSnapshotAt),
    }
    if (this.exileConfirmUntil !== 0 && sys.now() >= this.exileConfirmUntil) {
      this.exileConfirmUntil = 0   // 确认窗口过期：按钮自己回到常态，不需要玩家去取消
    }
    const text = this.exileConfirmUntil !== 0 ? '确认迁城?' : exileLabel(facts)
    if (text !== this.lastExileCaption) {
      this.lastExileCaption = text
      this.exileCaption.string = text
    }
  }

  /**
   * 按下流亡迁城。这是一次不可撤销的搬家（落点随机、免战 12 小时、3 天冷却、盟友暂时找不到你），
   * 所以用两段式确认：第一下只把按钮变成「确认迁城?」，5 秒内再按一下才真的发起。
   *
   * <p>本项目没有对话框系统，而为了一个按钮引入它是更大的口子。
   * 窗口过期由 {@link refreshExileCaption} 负责，玩家什么都不做就会回到常态。
   */
  private requestExile(): void {
    const requester = worldRequester()
    if (requester === null) {
      return   // 未连接时不响应：渲染与请求本来就是分开的两条线
    }
    const snap = exileSnapshot()
    const facts = {
      nextExileAt: snap.nextExileAt,
      troopsAway: snap.troopsAway,
      requesting: this.exileRequesting,
      serverNow: snap.serverNow + (sys.now() - this.exileSnapshotAt),
    }
    if (!exileCanRequest(facts)) {
      // 灰态下按它，按钮文字本身就是原因（「冷却 2 天 3 小时」「先召回队伍」），不另弹提示
      return
    }
    if (this.exileConfirmUntil === 0) {
      this.exileConfirmUntil = sys.now() + EXILE_CONFIRM_WINDOW_MS
      return
    }
    this.exileConfirmUntil = 0
    this.exileRequesting = true
    this.refreshExileCaption()
    void Promise.resolve()
      .then(() => requester.exile())
      .then(
        () => this.finishExileRequest(),
        (error: unknown) => {
          console.error('[WorldMap] 流亡迁城请求失败', error)
          this.finishExileRequest()
        },
      )
  }

  private finishExileRequest(): void {
    this.exileRequesting = false
    this.refreshExileCaption()
  }

  private showHint(text: string | null): void {
    if (this.hintLabel === null) {
      return
    }
    const next = text ?? '灰雾为未探索区域'
    if (next === this.lastHint) {
      return
    }
    this.lastHint = next
    this.hintLabel.string = next
  }
}

// ---------- 纯函数（表现层常量与格式化） ----------

function tileColor(tile: ChunkTile): Color {
  if (tile.fogged) {
    return COLOR_FOG
  }
  return tile.loaded ? COLOR_GROUND : COLOR_LOADING
}

function entityColor(type: WorldEntityType): Color {
  switch (type) {
    case 'CITY':
      return COLOR_CITY
    case 'MONSTER':
      return COLOR_MONSTER
    case 'RESOURCE':
      return COLOR_RESOURCE
    case 'MARCH':
      return COLOR_MARCH
    case 'BUILDING':
      return COLOR_BUILDING
    case 'EMPTY':
    default:
      return COLOR_GROUND_GRID
  }
}

function entityArtKey(type: WorldEntityType): ArtKey | null {
  switch (type) {
    case 'CITY':
      return 'map.entity.city'
    case 'MONSTER':
      return 'map.entity.monster'
    case 'RESOURCE':
      return 'map.entity.resource'
    case 'BUILDING':
      return 'map.entity.allianceBuilding'
    case 'MARCH':
      return 'map.entity.march'
    case 'EMPTY':
    default:
      return null
  }
}

/**
 * 地形图集有 8 个纯表现变体；服务端当前不区分地貌，客户端只按块坐标做稳定的无随机映射，
 * 避免重绘时贴图跳变，也不把视觉选择误当成玩法数据。
 */
function terrainVariantForChunk(cx: number, cy: number): number {
  const hash = Math.imul(cx + 17, 73_856_093) ^ Math.imul(cy + 31, 19_349_663)
  return (hash >>> 0) % 8
}

/** 城比野怪大一圈：占位美术阶段「谁更重要」只能靠尺寸表达。 */
function entitySize(type: WorldEntityType, cell: number): number {
  const ratio = type === 'CITY' || type === 'BUILDING' ? CITY_SIZE_RATIO : ENTITY_SIZE_RATIO
  return cell * ratio
}

/**
 * 把剩余毫秒格式化成「X分Y秒」。
 *
 * <p>只做展示格式化，绝不用它判断到达 —— 到达是服务端延迟队列的事
 * （B07 禁止项：不要用客户端定时器决定到达）。
 */
function formatRemaining(remainingMs: number): string {
  if (remainingMs <= 0) {
    return ''
  }
  const totalSeconds = Math.floor(remainingMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return minutes > 0 ? `${minutes}分${seconds}秒` : `${seconds}秒`
}

/** 取整数格部分（向零截断）。拖动累积器用它决定「这一步该移动几格」，余数留给渲染相机。 */
function wholeCells(value: number): number {
  return Math.trunc(value)
}

/** 计算前两个触点的距离。少于两个触点或坐标非法时返回 null。 */
function pinchDistance(points: readonly GesturePoint[]): number | null {
  const first = points[0]
  const second = points[1]
  if (first === undefined || second === undefined) {
    return null
  }
  const dx = second.x - first.x
  const dy = second.y - first.y
  const distance = Math.hypot(dx, dy)
  return Number.isFinite(distance) && distance > 0 ? distance : null
}

/** 根据当前距离相对手势起点的比例，给出至多一档缩放动作。 */
function pinchZoomAction(currentDistance: number, anchorDistance: number): PinchZoomAction {
  if (!Number.isFinite(currentDistance) || !Number.isFinite(anchorDistance)
      || currentDistance <= 0 || anchorDistance <= 0) {
    return null
  }
  if (currentDistance >= anchorDistance * PINCH_ZOOM_IN_RATIO) {
    return 'zoom-in'
  }
  if (currentDistance <= anchorDistance * PINCH_ZOOM_OUT_RATIO) {
    return 'zoom-out'
  }
  return null
}
