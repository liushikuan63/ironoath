/**
 * 职责：外观面板 —— 头像框预览、清单与佩戴/卸下（B24 块③）。
 * 依赖：cc（渲染）、game/avatar/AvatarFramePanel（展示数据组装，已单测）、scene/UiFont。
 *
 * <p><b>本场景不做任何判定</b>（铁律 2）：哪几枚、拥有没拥有、现在戴着哪一枚、
 * 这一行能不能点，全部来自服务端下发的那份列表（`owned` / `worn`）。
 * 本文件只做两件表现层的事：把框画出来、把点击意图回抛（`onWear`）。
 *
 * <p><b>框是画出来的占位，不是贴图</b>：素材库里没有头像框这一族（`find client/assets
 * -iname "*frame*"` 零命中），2026-09-19 裁决走「占位拼一版，先把链打通」——
 * 颜色来自表里的 `placeholderColor`，形状是<b>四角包边</b>（只看外圈的画法与真框最接近）。
 * 素材到位后只改本文件的画法，判定与协议都不动。
 *
 * <p><b>行数按实测可视高度算</b>（与商店/军队面板同一条纪律）：写死行数会在矮窗口里
 * 把最后一行压在底部导航条下面；画不下的数量在表头说出来。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import type { AvatarFrameRow } from '../game/avatar/AvatarFramePanel'
import type { AvatarFramesView } from '../game/session/AppRoot'
import { applySystemUiFont, capWidth } from './UiFont'
import {
  clampPage, contentPerPage, pageCount, pageNotice, pageWindow,
} from '../game/ui/PanelPaging'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_LOCKED = new Color(34, 31, 28, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)

const PANEL_WIDTH = 620
const ROW_HEIGHT = 58
const ROW_GAP = 5
/** 标题 + 预览区 + 状态行占掉的高度（预览区是这一页的主体，比货架面板的头高） */
const HEADER_HEIGHT = 226
const PADDING = 16
const ROW_POOL_SIZE = 5
/** 屏幕底部要给导航条让出的高度（与商店/军队面板同一个数）。 */
const BOTTOM_RESERVED = 68
/** 预览头像的边长与框的外扩：框画在头像外面，包围盒 76 见方。 */
const AVATAR_SIZE = 60
const FRAME_SIZE = 76
/** 四角包边的臂长与线宽。 */
const CORNER_ARM = 22
const CORNER_WIDTH = 4
/** 四个角的符号（右上、左上、右下、左下）。 */
const CORNERS: ReadonlyArray<readonly [number, number]> = [[1, 1], [-1, 1], [1, -1], [-1, -1]]

@ccclass('AvatarFramePanelView')
export class AvatarFramePanelView extends Component {
  private panel: AvatarFramesView | null = null
  private headerLabel: Label | null = null
  private countLabel: Label | null = null
  private wornLabel: Label | null = null
  private noticeLabel: Label | null = null
  private avatarInitial: Label | null = null
  private frameGraphics: Graphics | null = null
  private readonly rowNodes: Node[] = []
  /** 当前页（0 起）。这一屏只有一份列表，重新 attach 只 clamp（见 attach）。 */
  private page = 0
  private prevPageButton: Node | null = null
  private nextPageButton: Node | null = null
  private prevPageCaption: Label | null = null
  private nextPageCaption: Label | null = null
  private canPrev = false
  private canNext = false
  private readonly rowSwatch: Graphics[] = []
  private readonly rowName: Label[] = []
  private readonly rowRarity: Label[] = []
  private readonly rowState: Label[] = []
  private readonly rowButton: Node[] = []
  private readonly rowButtonCaption: Label[] = []
  /** 每一行当前对应哪一枚框（池化节点复用时行数据会变，点击要知道点的是谁）。 */
  private readonly rowIds: Array<string | null> = []
  /** 每一行当前该发的动作（`wear` / `unwear` / null）—— 与 rowIds 一起记住点击的语义。 */
  private readonly rowActions: Array<'wear' | 'unwear' | null> = []

  /** 佩戴 / 卸下一枚框。`frameId` 为 null = 卸下（与协议的 `WearFrameReq` 同形）。 */
  onWear: ((frameId: string | null) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.buildHeader(size.height)
    this.node.on('touch-start', (_event: EventTouch) => {
      /* 面板本身不吃触摸，这一句只是防止事件穿透到地图 */
    }, this)
  }

  override onDestroy(): void {
    this.rowNodes.length = 0
    this.onWear = null
  }

  /** 装载整块外观视图（编排层组装好的，本文件不改其中任何判定）。 */
  attach(framesView: AvatarFramesView): void {
    // 故意不归零页号：换上一个框会重新 attach，归零等于把玩家刚选中的那一枚弹走。
    // 页号越界由 render() 里那一次 clampPage 夹回来 —— 只那一处，perPage 也只在那一处算得出
    this.panel = framesView
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
    this.headerLabel = this.addLabel('Header', 0, top - 20, COLOR_COPPER_GOLD, 20)
    this.countLabel = this.addLabel('Count', 0, top - 46, COLOR_TEXT_DIM, 15)
    // 两颗翻页键与那句页码同一行、摆在两端，y 由 render() 跟着最后一行走
    this.prevPageButton = this.buildPagerButton('PrevPageButton', -PANEL_WIDTH / 2 + 46)
    this.nextPageButton = this.buildPagerButton('NextPageButton', PANEL_WIDTH / 2 - 46)
  }

  /** 一颗 64×28 的翻页键，照本文件行上那颗动作键的画法。 */
  private buildPagerButton(name: string, x: number): Node {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(64, 28))
    node.setPosition(new Vec3(x, 0, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 1
    graphics.roundRect(-32, -14, 64, 28, 4)
    graphics.fill()
    graphics.stroke()
    const caption = this.addLabel('Caption', 0, 0, COLOR_TEXT, 13, node)
    caption.string = name === 'PrevPageButton' ? '上一页' : '下一页'
    // 建出来先收着：不先收会在"数据没到"那一态露出两颗点了没反应的键
    //（#345 口径，#449 与 #450 各修过一次同一族）
    node.active = false
    if (name === 'PrevPageButton') {
      this.prevPageCaption = caption
      node.on('touch-start', () => this.turnPage(-1), this)
    } else {
      this.nextPageCaption = caption
      node.on('touch-start', () => this.turnPage(1), this)
    }
    return node
  }

  /**
   * 翻一页。灰掉的那一侧直接不吃：`clampPage` 也会把越界页号夹回来，
   * 但"点了没反应"正是 #345 那条口径要挡的观感。
   */
  private turnPage(delta: number): void {
    if (delta < 0 && !this.canPrev) return
    if (delta > 0 && !this.canNext) return
    this.page += delta
    this.render()
  }

  /** 两颗键跟着这一屏让出来的那一格走；只有一页时整对收掉（#345）。 */
  private paintPager(pages: number, rowY: number): void {
    const paged = pages > 1
    this.canPrev = this.page > 0
    this.canNext = this.page < pages - 1
    for (const [button, caption, usable] of [
      [this.prevPageButton, this.prevPageCaption, this.canPrev],
      [this.nextPageButton, this.nextPageCaption, this.canNext],
    ] as Array<[Node | null, Label | null, boolean]>) {
      if (button === null || caption === null) continue
      button.active = paged
      button.setPosition(new Vec3(button.position.x, rowY, 0))
      caption.color = usable ? COLOR_TEXT : COLOR_TEXT_DIM
    }
  }

    // 预览区：一块底板 + 头像 + 框。位置写在一处常量里，渲染时只改颜色与文字
    const previewY = top - 46 - 62
    const plate = new Node('PreviewPlate')
    plate.layer = this.node.layer
    this.node.addChild(plate)
    plate.setPosition(new Vec3(0, previewY, 0))
    plate.addComponent(UITransform).setContentSize(new Size(FRAME_SIZE + 24, FRAME_SIZE + 24))
    const plateGraphics = plate.addComponent(Graphics)
    plateGraphics.fillColor = COLOR_PANEL
    plateGraphics.roundRect(-(FRAME_SIZE + 24) / 2, -(FRAME_SIZE + 24) / 2, FRAME_SIZE + 24, FRAME_SIZE + 24, 8)
    plateGraphics.fill()

    const avatar = new Node('AvatarPlate')
    avatar.layer = this.node.layer
    this.node.addChild(avatar)
    avatar.setPosition(new Vec3(0, previewY, 0))
    avatar.addComponent(UITransform).setContentSize(new Size(AVATAR_SIZE, AVATAR_SIZE))
    const avatarGraphics = avatar.addComponent(Graphics)
    avatarGraphics.fillColor = COLOR_ROW
    avatarGraphics.roundRect(-AVATAR_SIZE / 2, -AVATAR_SIZE / 2, AVATAR_SIZE, AVATAR_SIZE, 6)
    avatarGraphics.fill()
    this.avatarInitial = this.addLabel('Initial', 0, previewY, COLOR_TEXT, 26)

    const frameNode = new Node('FrameOverlay')
    frameNode.layer = this.node.layer
    this.node.addChild(frameNode)
    frameNode.setPosition(new Vec3(0, previewY, 0))
    frameNode.addComponent(UITransform).setContentSize(new Size(FRAME_SIZE, FRAME_SIZE))
    this.frameGraphics = frameNode.addComponent(Graphics)

    this.wornLabel = this.addLabel('Worn', 0, previewY - FRAME_SIZE / 2 - 20, COLOR_TEXT, 16)
    // 这一行两用：列表还没拉回来时的那句话，或上一次操作的结果
    this.noticeLabel = this.addLabel('Notice', 0, previewY - FRAME_SIZE / 2 - 44, COLOR_TEXT_DIM, 14)
    capWidth(this.noticeLabel, PANEL_WIDTH - 2 * PADDING)

    for (let index = 0; index < ROW_POOL_SIZE; index++) {
      const row = this.createRow(index)
      this.rowNodes.push(row.node)
      this.rowSwatch.push(row.swatch)
      this.rowName.push(row.name)
      this.rowRarity.push(row.rarity)
      this.rowState.push(row.state)
      this.rowButton.push(row.button)
      this.rowButtonCaption.push(row.buttonCaption)
      this.rowIds.push(null)
      this.rowActions.push(null)
    }
  }

  private createRow(index: number): {
    node: Node; swatch: Graphics; name: Label; rarity: Label; state: Label
    button: Node; buttonCaption: Label
  } {
    const node = new Node('FrameRow')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
    graphics.fill()

    // 左侧色块：这一枚的颜色。玩家扫一眼就知道哪个是哪个
    const swatchNode = new Node('Swatch')
    swatchNode.layer = node.layer
    node.addChild(swatchNode)
    swatchNode.setPosition(new Vec3(-PANEL_WIDTH / 2 + 30, 0, 0))
    swatchNode.addComponent(UITransform).setContentSize(new Size(28, 28))
    const swatch = swatchNode.addComponent(Graphics)

    const name = this.addLabel('Name', -PANEL_WIDTH / 2 + PADDING + 36, 10, COLOR_TEXT, 17, node)
    name.horizontalAlign = Label.HorizontalAlign.LEFT
    name.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    capWidth(name, 260)
    const rarity = this.addLabel('Rarity', -PANEL_WIDTH / 2 + PADDING + 36, -12, COLOR_TEXT_DIM, 14, node)
    rarity.horizontalAlign = Label.HorizontalAlign.LEFT
    rarity.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    capWidth(rarity, 260)
    const state = this.addLabel('State', 90, 0, COLOR_TEXT_DIM, 15, node)

    const button = new Node('WearButton')
    button.layer = node.layer
    node.addChild(button)
    button.setPosition(new Vec3(PANEL_WIDTH / 2 - 54, 0, 0))
    button.addComponent(UITransform).setContentSize(new Size(88, 32))
    const buttonGraphics = button.addComponent(Graphics)
    this.paintButton(buttonGraphics, COLOR_GOOD)
    const buttonCaption = this.addLabel('Caption', 0, 0, COLOR_BACKGROUND, 15, button)
    button.on('touch-start', (_event: EventTouch) => {
      const action = this.rowActions[index] ?? null
      const frameId = this.rowIds[index] ?? null
      if (action === 'unwear') {
        this.onWear?.(null)
      } else if (action === 'wear' && frameId !== null) {
        this.onWear?.(frameId)
      }
    }, this)
    return { node, swatch, name, rarity, state, button, buttonCaption }
  }

  private paintButton(graphics: Graphics, fill: Color): void {
    graphics.clear()
    graphics.fillColor = fill
    graphics.roundRect(-44, -16, 88, 32, 5)
    graphics.fill()
  }

  private addLabel(name: string, x: number, y: number, color: Color, fontSize: number,
                   parent: Node = this.node): Label {
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
    if (panel === null) {
      return
    }
    if (this.avatarInitial !== null) {
      this.avatarInitial.string = panel.initialText
    }
    if (this.frameGraphics !== null) {
      this.paintFrame(this.frameGraphics, parseColor(panel.previewColor))
    }
    if (this.wornLabel !== null) {
      this.wornLabel.string = panel.wornText
      this.wornLabel.color = panel.wornFrameId === null ? COLOR_TEXT_DIM : COLOR_COPPER_GOLD
    }
    if (this.countLabel !== null) {
      this.countLabel.string = panel.ownedCountText
    }
    if (this.noticeLabel !== null) {
      // 上一次操作的结果优先；没有就显示"列表还没拉回来"那句或空框引导
      this.noticeLabel.string = panel.notice ?? panel.noticeText ?? panel.emptyText ?? ''
      this.noticeLabel.color = panel.notice !== null ? COLOR_GOOD : COLOR_TEXT_DIM
    }

    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    const navTop = -size.height / 2 + BOTTOM_RESERVED
    const usable = topY + ROW_HEIGHT / 2 - navTop
    // 容量取"按窗口算出来的行数"与"实际建出来的行数"里小的那一个（与商店同一处修法）
    const capacity = Math.max(1,
      Math.min(Math.floor(usable / (ROW_HEIGHT + ROW_GAP)), this.rowNodes.length))
    const total = panel.rows.length
    // 共几页、夹到哪一页、切哪一段用同一个 perPage：从前只画第一屏再补一句
    // 「另有 N 枚未显示」，下面那些框玩家一枚也换不了（#307）
    const perPage = contentPerPage(total, capacity)
    const pages = pageCount(total, perPage)
    this.page = clampPage(this.page, total, perPage)
    const slice = pageWindow(total, this.page, perPage)
    const rows = panel.rows.slice(slice.start, slice.end)
    const drawn = rows.length

    if (this.headerLabel !== null) {
      this.headerLabel.string = `外观 · ${drawn}/${total} 枚`
        + (pages > 1 ? ` · ${pageNotice(this.page, pages)}` : '')
    }
    this.paintPager(pages, topY - drawn * (ROW_HEIGHT + ROW_GAP) - 8)

    this.rowNodes.forEach((node, index) => {
      const row: AvatarFrameRow | undefined = rows[index]
      node.active = index < drawn && row !== undefined
      if (row === undefined || index >= drawn) {
        this.rowIds[index] = null
        this.rowActions[index] = null
        return
      }
      // 行按序号往下排（池化节点建出来都在 y=0，不摆就是叠在同一处；商店面板踩过同一个坑，
      // 见收口清单 #244）
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.rowIds[index] = row.frameId
      this.rowActions[index] = row.action
      const graphics = node.getComponent(Graphics)
      if (graphics !== null) {
        graphics.clear()
        // 没拥有的一行暗一档：拥有与否是这一页最重要的那一眼
        graphics.fillColor = row.owned ? COLOR_ROW : COLOR_ROW_LOCKED
        graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
        graphics.fill()
      }
      this.paintSwatch(this.rowSwatch[index]!, parseColor(row.color))
      this.rowName[index]!.string = row.name
      this.rowName[index]!.color = row.owned ? COLOR_TEXT : COLOR_TEXT_DIM
      this.rowRarity[index]!.string = `${row.rarityText} · 外观`
      this.rowState[index]!.string = row.stateText
      this.rowState[index]!.color = row.worn ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
      // 未拥有的行**不画按钮**：客户端没有「在哪买」这一位，给它一颗按钮就是造一个假指向
      // （`actionText` 为 null 时按钮整个隐藏，而不是画一颗点不动的灰的）
      this.rowButton[index]!.active = row.actionText !== null
      if (row.actionText !== null) {
        this.paintButton(this.rowButton[index]!.getComponent(Graphics)!, COLOR_GOOD)
        this.rowButtonCaption[index]!.string = row.actionText
      }
    })
  }

  /** 行首色块：一枚小圆角方块，颜色就是这一枚框的颜色。 */
  private paintSwatch(graphics: Graphics, color: Color): void {
    graphics.clear()
    graphics.fillColor = color
    graphics.roundRect(-14, -14, 28, 28, 5)
    graphics.fill()
  }

  /**
   * 画框：四角包边 + 一条淡描边。
   *
   * <p>只画四角而不是一个完整矩形，是为了让它在头像周围读起来像"框"而不是"选中态"；
   * 素材到位后换成贴图时，只改这一个函数。
   */
  private paintFrame(graphics: Graphics, color: Color): void {
    graphics.clear()
    const half = FRAME_SIZE / 2
    graphics.lineWidth = CORNER_WIDTH
    graphics.strokeColor = color
    // 不用 for-of 解构元组：目标运行时（小游戏 SWC）对迭代器/解构的支持不保证，
    // 与"不用迭代器 spread"同一条纪律
    for (let index = 0; index < CORNERS.length; index++) {
      const sx = CORNERS[index]![0]
      const sy = CORNERS[index]![1]
      graphics.moveTo(sx * half, sy * half - sy * CORNER_ARM)
      graphics.lineTo(sx * half, sy * half)
      graphics.lineTo(sx * half - sx * CORNER_ARM, sy * half)
    }
    graphics.stroke()
  }
}

/** `#RRGGBB` → Color。纯逻辑层已经把写坏的值归一成中性色，这里只做最后的解析。 */
function parseColor(hex: string): Color {
  const value = Number.parseInt(hex.slice(1), 16)
  if (!Number.isFinite(value)) {
    return new Color(107, 98, 87, 255)
  }
  return new Color((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF, 255)
}
