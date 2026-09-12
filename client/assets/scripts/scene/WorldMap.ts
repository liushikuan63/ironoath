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

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, UITransform, Vec3, director, sys, view } from 'cc'
import { exileSnapshot, worldModel, worldRequester } from '../game/world/WorldContext'
import type { ExileSnapshot } from '../game/world/WorldContext'
import { exileCanRequest, exileLabel } from '../game/world/ExileAction'
import type { WorldViewModel } from '../game/world/WorldViewModel'
import type { WorldFrame, ChunkTile, MarchRender } from '../game/world/WorldViewModel'
import type { WorldEntity, WorldEntityType } from '../net/generated/WorldProtocol'
import { NodePool } from './NodePool'
import type { Unsubscribe } from '../core/EventBus'

const { ccclass } = _decorator

/**
 * 配色来自 B00「题材与调性」：铜金 + 暗红，写实厚重冷兵器乱世。
 * 这是美术方向常量而非游戏数值（铁律 1 约束的是时间/产量/攻击/掉落/冷却）。
 */
const COLOR_BACKGROUND = new Color(16, 14, 12, 255)
const COLOR_GROUND = new Color(52, 42, 33, 255)
const COLOR_GROUND_GRID = new Color(70, 57, 45, 255)
/** 迷雾：B07 §3 要求未探索区域为黑色遮罩。刻意用纯黑而不是半透明 —— 半透明等于给了透视的余地 */
const COLOR_FOG = new Color(6, 6, 8, 255)
/** 请求已发出但响应还没回来。必须与迷雾区分：一个是网络慢，一个是没探索过 */
const COLOR_LOADING = new Color(30, 30, 34, 255)
const COLOR_CITY = new Color(139, 26, 26, 255)
const COLOR_MONSTER = new Color(96, 88, 78, 255)
const COLOR_RESOURCE = new Color(72, 104, 62, 255)
const COLOR_MARCH = new Color(184, 134, 11, 255)
const COLOR_BUILDING = new Color(58, 86, 120, 255)
/** 纠偏闪光（B07 验收 10）：位置被服务端校正时短暂提亮，让玩家知道「刚才那一下不是卡了」 */
const COLOR_CORRECTED = new Color(255, 236, 180, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)

/**
 * 各缩放档位下「一格等于多少像素」。
 *
 * <p>档位 0 的取值让 3×3 块正好铺满一屏：3 块 × 32 格 × 8px = 768px ≈ 设计分辨率宽度。
 * 客户端手里永远只有 9 个块（B07 红线：绝不一次性下发整张地图），
 * 所以「世界档」的含义不是「看见全世界」，而是「看见自己这 9 块的全貌」。
 * 档位 1 放大 3 倍，一屏约一块，便于点选具体格子。档位 2 切换到城内场景，不画地图。
 */
const CELL_PIXELS_BY_ZOOM: readonly number[] = [8, 24]

/** 实体色块的边长占一格的比例。留出缝隙才能看清格子边界，也避免相邻实体糊成一片。 */
const ENTITY_SIZE_RATIO = 0.72
const CITY_SIZE_RATIO = 1.5
const MARCH_SIZE_RATIO = 0.9

/** 顶部 HUD 条带高度（像素）。落在这条带里的触摸不触发拖动，否则点按钮会同时把地图拖走。 */
const HUD_BAND_HEIGHT = 104
const HUD_BUTTON_SIZE = 84
const HUD_BUTTON_GAP = 12
/** 流亡迁城的二次确认窗口：过了就得重新按两下。宁短勿长 —— 拖着确认状态去干别的再回来点到，正是误操作的样子。 */
const EXILE_CONFIRM_WINDOW_MS = 5_000

/** 纠偏闪光持续的帧数。这是特效时长，不是游戏数值。 */
const CORRECTED_FLASH_FRAMES = 8

interface MarkerRefs {
  readonly graphics: Graphics
  readonly label: Label
}

@ccclass('WorldMap')
export class WorldMap extends Component {
  private readonly refs = new Map<Node, MarkerRefs>()
  /** 已画出的实体：键 → 节点。每帧与渲染帧做差集，决定谁复用、谁归还 */
  private readonly drawnEntities = new Map<string, Node>()
  private readonly drawnMarches = new Map<string, Node>()
  private readonly drawnTiles = new Map<string, Node>()
  /** 纠偏闪光的剩余帧数：键 → 还剩几帧 */
  private readonly flash = new Map<string, number>()
  private readonly unsubscribes: Unsubscribe[] = []

  private mapLayer: Node | null = null
  private hudLayer: Node | null = null
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
  private dragging = false
  private panAccumX = 0
  private panAccumY = 0
  private marchesRequested = false
  private modelSubscription: Unsubscribe | null = null
  private lastHint = ''

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.mapLayer = this.buildLayer('MapLayer')
    this.hudLayer = this.buildLayer('HudLayer')
    this.tilePool = new NodePool(this.mapLayer, () => this.createMarker(), 9)
    this.entityPool = new NodePool(this.mapLayer, () => this.createMarker())
    this.marchPool = new NodePool(this.mapLayer, () => this.createMarker())
    this.buildHud(size.width, size.height)
    this.bindInput(size.height)
    this.bindModel()
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
    this.tilePool = null
    this.entityPool = null
    this.marchPool = null
    this.refs.clear()
    this.drawnEntities.clear()
    this.drawnMarches.clear()
    this.drawnTiles.clear()
    this.flash.clear()
  }

  override update(): void {
    const model = worldModel()
    if (model === null) {
      this.showHint('未连接世界服务')
      return
    }
    this.showHint(null)
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
      director.loadScene('MainCity')
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

  /** 一键回城（B07 §1）。家坐标由调用方给：它在 MarchListResp.home 里，本场景不存权威数据。 */
  focusHome(homeX: number, homeY: number): void {
    this.focusCoord(homeX, homeY)
  }

  // ---------- 搭建 ----------

  private buildLayer(name: string): Node {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform)
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
  }

  /**
   * 顶部 HUD：坐标读数 + 三个按钮（放大 / 缩小 / 回城）。
   *
   * <p>「回城」按钮拿不到家坐标 —— 家坐标在 MarchListResp 里，由适配层落地。
   * 所以按钮的回调留空并加了说明，接入点已经存在（{@link WorldMap#focusHome}），
   * 等适配层把 home 存进 Store 之后把这一行补上即可。
   */
  private buildHud(width: number, height: number): void {
    const y = height / 2 - HUD_BAND_HEIGHT / 2

    this.coordLabel = this.createHudLabel('CoordLabel', '', width / 2 - 16, y + 18)
    this.coordLabel.horizontalAlign = Label.HorizontalAlign.RIGHT

    this.hintLabel = this.createHudLabel('HintLabel', '', width / 2 - 16, y - 18)
    this.hintLabel.horizontalAlign = Label.HorizontalAlign.RIGHT
    this.hintLabel.color = COLOR_TEXT_DIM
    this.hintLabel.fontSize = 16

    const buttons: Array<{ name: string; text: string; onTap: () => void }> = [
      { name: 'ZoomInButton', text: '放大', onTap: () => this.zoomIn() },
      { name: 'ZoomOutButton', text: '缩小', onTap: () => this.zoomOut() },
      // TODO(B07 表现层缺口): 家坐标要等适配层把 MarchListResp.home 落进 Store 才能取到，
      // 现在点了没反应。接入点 focusHome 已就位，补一行即可
      { name: 'HomeButton', text: '回城', onTap: () => undefined },
    ]
    const startX = -width / 2 + HUD_BUTTON_SIZE / 2 + HUD_BUTTON_GAP
    buttons.forEach((button, index) => {
      const node = this.createButton(button.name, button.text,
        startX + index * (HUD_BUTTON_SIZE + HUD_BUTTON_GAP), y)
      node.on('touch-start', button.onTap, this)
    })

    // 流亡迁城的按钮文字要显示冷却倒计时与确认状态，所以不走上面那个固定文案的数组
    const exileNode = this.createButton('ExileButton', '流亡',
      startX + buttons.length * (HUD_BUTTON_SIZE + HUD_BUTTON_GAP), y)
    this.exileCaption = exileNode.getChildByName('ExileButton_Caption')?.getComponent(Label) ?? null
    exileNode.on('touch-start', () => this.requestExile(), this)
  }

  private createHudLabel(name: string, text: string, x: number, y: number): Label {
    const node = new Node(name)
    node.layer = this.node.layer
    if (this.hudLayer !== null) {
      this.hudLayer.addChild(node)
    } else {
      this.node.addChild(node)
    }
    node.addComponent(UITransform)
    node.setPosition(new Vec3(x, y, 0))
    const label = node.addComponent(Label)
    label.string = text
    label.color = COLOR_TEXT
    label.fontSize = 20
    label.verticalAlign = Label.VerticalAlign.CENTER
    return label
  }

  private createButton(name: string, text: string, x: number, y: number): Node {
    const node = new Node(name)
    node.layer = this.node.layer
    if (this.hudLayer !== null) {
      this.hudLayer.addChild(node)
    } else {
      this.node.addChild(node)
    }
    node.setPosition(new Vec3(x, y, 0))
    const transform = node.addComponent(UITransform)
    transform.setContentSize(HUD_BUTTON_SIZE, HUD_BUTTON_SIZE)
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_GROUND_GRID
    graphics.strokeColor = COLOR_MARCH
    graphics.lineWidth = 2
    graphics.roundRect(-HUD_BUTTON_SIZE / 2, -HUD_BUTTON_SIZE / 2, HUD_BUTTON_SIZE, HUD_BUTTON_SIZE, 8)
    graphics.fill()
    graphics.stroke()

    const caption = new Node(`${name}_Caption`)
    caption.layer = node.layer
    node.addChild(caption)
    caption.addComponent(UITransform)
    const label = caption.addComponent(Label)
    label.string = text
    label.color = COLOR_TEXT
    label.fontSize = 20
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
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
    const graphics = node.addComponent(Graphics)
    const caption = new Node('Caption')
    caption.layer = node.layer
    node.addChild(caption)
    caption.addComponent(UITransform)
    const label = caption.addComponent(Label)
    label.fontSize = 12
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    label.color = COLOR_TEXT
    this.refs.set(node, { graphics, label })
    return node
  }

  /**
   * @param height 可见高度。getUILocation 返回的是 UI 空间坐标（原点在屏幕左下角、y 向上），
   *               所以 HUD 条带的下边界是 height - HUD_BAND_HEIGHT，不是 height/2 - …
   *               后者是节点空间（原点居中）的写法，两个空间混用会让按钮区判定整体偏移半屏
   */
  private bindInput(height: number): void {
    this.node.on('touch-start', (event: EventTouch) => {
      // 落在 HUD 条带里的触摸不启动拖动，否则点按钮的同时会把地图拖走
      this.dragging = event.getUILocation().y < height - HUD_BAND_HEIGHT
    }, this)
    this.node.on('touch-move', (event: EventTouch) => {
      if (!this.dragging) {
        return
      }
      this.onDrag(event)
    }, this)
    const stop = (): void => {
      this.dragging = false
    }
    this.node.on('touch-end', stop, this)
    this.node.on('touch-cancel', stop, this)
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
    const cell = cellPixels(model.currentZoom())
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

  private render(frame: WorldFrame, model: WorldViewModel): void {
    this.bindModel()
    const zoom = frame.zoom
    const cell = cellPixels(zoom)
    if (this.mapLayer === null) {
      return
    }
    const cameraX = frame.center.x + this.panAccumX
    const cameraY = frame.center.y + this.panAccumY
    this.mapLayer.setPosition(new Vec3(-(cameraX + 0.5) * cell, -(cameraY + 0.5) * cell, 0))

    this.renderTiles(frame.tiles, model.chunkSize, cell)
    this.renderEntities(frame.tiles, cell, zoom)
    this.renderMarches(frame.marches, cell)
    this.renderHud(frame)
  }

  private renderTiles(tiles: readonly ChunkTile[], chunkSize: number, cell: number): void {
    const pool = this.tilePool
    if (pool === null) {
      return
    }
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
      const refs = this.refs.get(node)
      if (refs === undefined) {
        continue
      }
      const graphics = refs.graphics
      graphics.clear()
      graphics.fillColor = tileColor(tile)
      graphics.rect(-size / 2, -size / 2, size, size)
      graphics.fill()
      // 已探索的块描一条边，让玩家看清 32×32 的分块边界（也便于核对视野确实是 3×3）
      if (!tile.fogged && tile.loaded) {
        graphics.strokeColor = COLOR_GROUND_GRID
        graphics.lineWidth = 1
        graphics.rect(-size / 2, -size / 2, size, size)
        graphics.stroke()
      }
      refs.label.string = ''
    }
    // 离开视野的块立刻归还池子 —— 这是验收 3「内存不随拖动增长」的落地点
    for (const [key, node] of [...this.drawnTiles]) {
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
        node.setPosition(new Vec3((entity.x + 0.5) * cell, (entity.y + 0.5) * cell, 0))
        this.drawMarker(node, entity.type, entitySize(entity.type, cell), entityColor(entity.type), zoom > 0 ? entityCaption(entity) : '')
      }
    }
    this.recycle(pool, this.drawnEntities, seen)
  }

  private renderMarches(marches: readonly MarchRender[], cell: number): void {
    const pool = this.marchPool
    if (pool === null) {
      return
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
      const remaining = this.flash.get(march.marchId) ?? 0
      if (remaining > 0) {
        this.flash.set(march.marchId, remaining - 1)
      } else {
        this.flash.delete(march.marchId)
      }
      const color = remaining > 0 ? COLOR_CORRECTED : COLOR_MARCH
      this.drawMarker(node, 'MARCH', cell * MARCH_SIZE_RATIO, color, formatRemaining(march.remainingMs))
    }
    for (const marchId of [...this.flash.keys()]) {
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
    for (const [key, node] of [...drawn]) {
      if (!seen.has(key)) {
        pool.release(node)
        drawn.delete(key)
      }
    }
  }

  private drawMarker(node: Node, type: WorldEntityType, size: number, color: Color, caption: string): void {
    const refs = this.refs.get(node)
    if (refs === undefined) {
      return
    }
    const graphics = refs.graphics
    graphics.clear()
    graphics.fillColor = color
    if (type === 'CITY' || type === 'BUILDING') {
      // 城与联盟建筑用方块，其余用圆角块：占位美术阶段先靠形状区分，正式美术换成图标
      graphics.rect(-size / 2, -size / 2, size, size)
    } else {
      graphics.roundRect(-size / 2, -size / 2, size, size, size / 4)
    }
    graphics.fill()
    refs.label.string = caption
    refs.label.node.setPosition(new Vec3(0, size / 2 + 8, 0))
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
      requesting: false,
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
      requesting: false,
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
    requester.exile()
  }

  private showHint(text: string | null): void {
    if (this.hintLabel === null) {
      return
    }
    const next = text ?? ''
    if (next === this.lastHint) {
      return
    }
    this.lastHint = next
    this.hintLabel.string = next
  }
}

// ---------- 纯函数（表现层常量与格式化） ----------

/** 某缩放档位下一格的像素边长。档位 2 不画地图，退化到最大档避免除零。 */
function cellPixels(zoom: number): number {
  const index = Math.min(Math.max(zoom, 0), CELL_PIXELS_BY_ZOOM.length - 1)
  // noUncheckedIndexedAccess 让下标访问变成 number|undefined；常量表长度固定，兜底值只为类型收窄
  return CELL_PIXELS_BY_ZOOM[index] ?? CELL_PIXELS_BY_ZOOM[0] ?? 8
}

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

/** 城比野怪大一圈：占位美术阶段「谁更重要」只能靠尺寸表达。 */
function entitySize(type: WorldEntityType, cell: number): number {
  const ratio = type === 'CITY' || type === 'BUILDING' ? CITY_SIZE_RATIO : ENTITY_SIZE_RATIO
  return cell * ratio
}

/**
 * 实体上的文字。只搬运服务端已经下发的字段，不做任何加工判定：
 * 等级、昵称、资源类型都照原样显示。
 */
function entityCaption(entity: WorldEntity): string {
  const level = entity.level === null ? '' : `Lv${entity.level}`
  switch (entity.type) {
    case 'CITY':
      return entity.ownerName ?? level
    case 'MONSTER':
      return level
    case 'RESOURCE':
      return entity.resourceType ?? ''
    case 'BUILDING':
      return entity.allianceTag ?? level
    case 'MARCH':
      return entity.load === null ? '' : `${entity.load}`
    case 'EMPTY':
    default:
      return ''
  }
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
