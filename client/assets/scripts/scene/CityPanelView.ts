/**
 * 职责：城建面板 —— 6×6 城内网格、建筑详情、升级、加速、收割（B03 §2/§3/§4）。
 * 依赖：cc（渲染）、game/city/CityPanel（展示数据组装，已单测）。
 *
 * <p>平面列表回答不了“我的城现在长什么样”，所以建筑按服务端下发的 gridX/gridY 放置。
 * 本场景仍然不做任何数值判断：升级、加速、收割全部由现有回调交给 AppRoot 和服务端裁定。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, sys, view } from 'cc'
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
  applyCommandButton, applyIconSprite, applySlicedSprite, buildingIconKey,
} from './ArtCatalog'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_BUILDING = new Color(58, 46, 36, 255)
const COLOR_BUILDING_UPGRADING = new Color(82, 61, 30, 255)
const COLOR_BUILDING_DONE = new Color(48, 74, 48, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_WARNING = new Color(200, 96, 64, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)

const CELL_WIDTH = 86
const CELL_HEIGHT = 46
const CELL_GAP = 4
const CONTENT_WIDTH = CITY_GRID_WIDTH * CELL_WIDTH + (CITY_GRID_WIDTH - 1) * CELL_GAP
const GRID_HEIGHT = CITY_GRID_HEIGHT * CELL_HEIGHT + (CITY_GRID_HEIGHT - 1) * CELL_GAP
const CARD_WIDTH = CONTENT_WIDTH + 32
const HEADER_HEIGHT = 128
const ACTION_HEIGHT = 76
const CARD_HEIGHT = HEADER_HEIGHT + GRID_HEIGHT + ACTION_HEIGHT + 108
const PADDING = 16
const PANEL_MARGIN = 12
const CARD_OFFSET = 24
const MAX_SCALE = 1.4
const ACTION_BUTTON_WIDTH = 82
const ACTION_BUTTON_HEIGHT = 32

type RowAction = 'upgrade' | 'speedAd' | 'speedGold' | 'collect'

interface GridTileRefs {
  readonly node: Node
  readonly graphics: Graphics
  readonly icon: Node
  readonly nameLabel: Label
  readonly levelLabel: Label
  readonly statusLabel: Label
}

@ccclass('CityPanelView')
export class CityPanelView extends Component {
  private resp: CityListResp | null = null
  private panel: CityPanelData | null = null
  private offsetMs = 0
  private lastRenderedSecond = -1
  private pending: CityListResp | null = null

  private card: Node | null = null
  private frameGraphics: Graphics | null = null
  private headerLabel: Label | null = null
  private queueLabel: Label | null = null
  private readonly resourceLabels: Label[] = []
  private messageLabel: Label | null = null

  private readonly gridTiles: GridTileRefs[] = []
  private selectedId: string | null = null
  private selectedTitle: Label | null = null
  private selectedStatus: Label | null = null
  private readonly actionButtons = new Map<Node, RowAction>()
  private buildPicker: ChoiceOverlay | null = null

  onUpgrade: ((configId: string, gridX?: number, gridY?: number) => void) | null = null
  onSpeedUp: ((buildingId: string, source: SpeedUpSource) => void) | null = null
  onCollect: ((buildingId: string | null) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    const card = new Node('Card')
    card.layer = this.node.layer
    this.node.addChild(card)
    card.addComponent(UITransform).setContentSize(new Size(CARD_WIDTH, CARD_HEIGHT))
    this.card = card
    this.buildCard()
    this.layoutCard()
    this.buildPicker = new ChoiceOverlay(card, '选择要建造的建筑', CARD_WIDTH - 24)
    this.renderGrid(buildCityGrid([]))
    this.renderSelection(null)

    if (this.pending !== null) {
      const pending = this.pending
      this.pending = null
      this.attach(pending, this.offsetMs)
    }
  }

  override onDestroy(): void {
    this.gridTiles.length = 0
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
    node.addComponent(UITransform).setContentSize(new Size(width, height))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
  }

  private buildCard(): void {
    const card = this.card
    if (card === null) {
      return
    }
    const frame = new Node('CardFrame')
    frame.layer = card.layer
    card.addChild(frame)
    frame.addComponent(UITransform).setContentSize(new Size(CARD_WIDTH, CARD_HEIGHT))
    this.frameGraphics = applySlicedSprite(frame, 'ui.panel.kingdom', CARD_WIDTH, CARD_HEIGHT)
      ? null
      : frame.addComponent(Graphics)

    const top = CARD_HEIGHT / 2 - PADDING
    this.headerLabel = this.addLabel(card, 'Header', 0, top - 22, COLOR_COPPER_GOLD, 22)
    this.queueLabel = this.addLabel(card, 'Queue', 0, top - 54, COLOR_TEXT, 16)

    const columnWidth = CONTENT_WIDTH / 3
    for (let row = 0; row < 2; row++) {
      for (let column = 0; column < 3; column++) {
        const x = -CONTENT_WIDTH / 2 + columnWidth * (column + 0.5)
        const y = top - 84 - row * 20
        this.resourceLabels.push(this.addLabel(
          card, `Resource-${row}-${column}`, x, y, COLOR_TEXT_DIM, 14))
      }
    }

    this.buildGrid(card)
    this.buildActionBar(card)
    this.messageLabel = this.addLabel(card, 'Message', 0, -CARD_HEIGHT / 2 + 18, COLOR_WARNING, 15)
  }

  private buildGrid(parent: Node): void {
    const grid = new Node('CityGrid')
    grid.layer = parent.layer
    parent.addChild(grid)
    grid.addComponent(UITransform).setContentSize(new Size(CONTENT_WIDTH, GRID_HEIGHT))
    grid.setPosition(new Vec3(0, CARD_HEIGHT / 2 - PADDING - HEADER_HEIGHT - GRID_HEIGHT / 2, 0))
    this.buildGround(grid)
    for (let index = 0; index < CITY_GRID_WIDTH * CITY_GRID_HEIGHT; index++) {
      const tile = new Node(`Grid-${index}`)
      tile.layer = grid.layer
      grid.addChild(tile)
      const column = index % CITY_GRID_WIDTH
      const rowFromTop = Math.floor(index / CITY_GRID_WIDTH)
      tile.setPosition(new Vec3(
        -CONTENT_WIDTH / 2 + CELL_WIDTH / 2 + column * (CELL_WIDTH + CELL_GAP),
        GRID_HEIGHT / 2 - CELL_HEIGHT / 2 - rowFromTop * (CELL_HEIGHT + CELL_GAP),
        0,
      ))
      tile.addComponent(UITransform).setContentSize(new Size(CELL_WIDTH, CELL_HEIGHT))
      const graphics = tile.addComponent(Graphics)
      const icon = new Node('BuildingIcon')
      icon.layer = tile.layer
      tile.addChild(icon)
      icon.setPosition(new Vec3(-23, 0, 0))
      icon.addComponent(UITransform).setContentSize(new Size(32, 32))
      const nameLabel = this.addLabel(tile, 'Name', 17, 10, COLOR_TEXT, 10)
      const levelLabel = this.addLabel(tile, 'Level', 17, -5, COLOR_TEXT_DIM, 10)
      const statusLabel = this.addLabel(tile, 'Status', 17, -17, COLOR_TEXT_DIM, 9)
      for (const label of [nameLabel, levelLabel, statusLabel]) {
        label.node.getComponent(UITransform)?.setContentSize(new Size(50, 14))
        label.overflow = Label.Overflow.SHRINK
      }
      this.gridTiles.push({ node: tile, graphics, icon, nameLabel, levelLabel, statusLabel })
    }
  }

  /**
   * 棋盘地基：同类 SLG 的城界语言 —— 先有"地皮"，建筑才是"盖在上面"。
   * 之前格子直接浮在面板底色上，读起来像表格而不像一座城。
   */
  private buildGround(grid: Node): void {
    const ground = new Node('Ground')
    ground.layer = grid.layer
    grid.addChild(ground)
    ground.addComponent(UITransform)
    const graphics = ground.addComponent(Graphics)
    const cellW = CELL_WIDTH + CELL_GAP
    const cellH = CELL_HEIGHT + CELL_GAP
    for (let row = 0; row < CITY_GRID_HEIGHT; row++) {
      for (let column = 0; column < CITY_GRID_WIDTH; column++) {
        const x = -CONTENT_WIDTH / 2 + column * cellW
        const y = GRID_HEIGHT / 2 - (row + 1) * cellH
        graphics.fillColor = (row + column) % 2 === 0
          ? new Color(36, 30, 25, 255) : new Color(32, 27, 22, 255)
        graphics.rect(x, y, cellW, cellH)
        graphics.fill()
      }
    }
    graphics.strokeColor = new Color(120, 92, 40, 120)
    graphics.lineWidth = 2
    graphics.roundRect(-CONTENT_WIDTH / 2 - 6, -GRID_HEIGHT / 2 - 6,
      CONTENT_WIDTH + 12, GRID_HEIGHT + 12, 10)
    graphics.stroke()
  }

  private buildActionBar(parent: Node): void {
    const bar = new Node('SelectionBar')
    bar.layer = parent.layer
    parent.addChild(bar)
    bar.addComponent(UITransform).setContentSize(new Size(CONTENT_WIDTH, ACTION_HEIGHT))
    bar.setPosition(new Vec3(0, -CARD_HEIGHT / 2 + 106, 0))
    const background = new Node('SelectionBarBackground')
    background.layer = bar.layer
    bar.addChild(background)
    background.addComponent(UITransform).setContentSize(new Size(CONTENT_WIDTH, ACTION_HEIGHT))
    const graphics = background.addComponent(Graphics)
    graphics.fillColor = COLOR_PANEL
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 1
    graphics.roundRect(-CONTENT_WIDTH / 2, -ACTION_HEIGHT / 2, CONTENT_WIDTH, ACTION_HEIGHT, 8)
    graphics.fill()
    graphics.stroke()

    const left = -CONTENT_WIDTH / 2 + 12
    this.selectedTitle = this.addLabel(bar, 'SelectedTitle', left, 16, COLOR_COPPER_GOLD, 16, true, 220)
    this.selectedStatus = this.addLabel(bar, 'SelectedStatus', left, -8, COLOR_TEXT_DIM, 13, true, 220)
    this.selectedStatus.overflow = Label.Overflow.SHRINK

    this.createActionButton(bar, 'DetailUpgradeButton', '升级', -12, 'upgrade')
    this.createActionButton(bar, 'DetailCollectButton', '收割', -12, 'collect')
    this.createActionButton(bar, 'DetailSpeedAdButton', '广告加速', 78, 'speedAd')
    this.createActionButton(bar, 'DetailSpeedGoldButton', '金币加速', 168, 'speedGold')

    const collectAll = new Node('CollectAllButton')
    collectAll.layer = parent.layer
    parent.addChild(collectAll)
    collectAll.setPosition(new Vec3(CONTENT_WIDTH / 2 - 76, CARD_HEIGHT / 2 - PADDING - 22, 0))
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
    card.getComponent(UITransform)?.setContentSize(new Size(CARD_WIDTH, CARD_HEIGHT))
    const frame = this.frameGraphics
    if (frame !== null) {
      frame.clear()
      frame.fillColor = COLOR_PANEL
      frame.strokeColor = COLOR_COPPER_GOLD
      frame.lineWidth = 2
      frame.roundRect(-CARD_WIDTH / 2, -CARD_HEIGHT / 2, CARD_WIDTH, CARD_HEIGHT, 10)
      frame.fill()
      frame.stroke()
      applySlicedSprite(frame.node, 'ui.panel.kingdom', CARD_WIDTH, CARD_HEIGHT)
    }
    const size = view.getVisibleSize()
    const scale = Math.min(MAX_SCALE,
      (size.width - 2 * PANEL_MARGIN) / CARD_WIDTH,
      (size.height - 2 * PANEL_MARGIN - 2 * CARD_OFFSET) / CARD_HEIGHT)
    card.setScale(new Vec3(scale, scale, 1))
    card.setPosition(new Vec3(0, CARD_OFFSET, 0))
  }

  private render(): void {
    const panel = this.panel
    if (panel === null) {
      return
    }
    this.lastRenderedSecond = Math.floor(sys.now() / 1000)
    const grid = buildCityGrid(panel.rows)

    const selected = panel.rows.find((row) => row.id === this.selectedId)
      ?? panel.rows[0] ?? null
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

    this.renderGrid(grid)
    this.renderSelection(selected)
  }

  private renderGrid(grid: CityGrid): void {
    this.gridTiles.forEach((tile, index) => {
      const row = grid.cells[index] ?? null
      tile.node.off('touch-start')
      this.paintTile(tile, row)
      if (row === null) {
        const panel = this.panel
        if (panel !== null && panel.buildOptions.length > 0) {
          const gridX = index % CITY_GRID_WIDTH
          const gridY = Math.floor(index / CITY_GRID_WIDTH)
          tile.node.on('touch-start', (_event: EventTouch) => {
            this.openBuildPicker(gridX, gridY)
          }, this)
        }
        return
      }
      tile.node.on('touch-start', (_event: EventTouch) => {
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
    this.buildPicker?.show(options, (configId) => {
      this.onUpgrade?.(configId, gridX, gridY)
    })
  }

  private paintTile(tile: GridTileRefs, row: BuildingRow | null): void {
    const selected = row !== null && row.id === this.selectedId
    const graphics = tile.graphics
    graphics.clear()
    const left = -CELL_WIDTH / 2 + 2
    const bottom = -CELL_HEIGHT / 2 + 2
    const width = CELL_WIDTH - 4
    const height = CELL_HEIGHT - 4
    if (row === null) {
      // 空地：虚线框 + 中央加号。同类 SLG 一眼可读的"这里能盖东西"；
      // 之前是灰块写着「空地」，玩家要读字才知道那是可建造位
      graphics.strokeColor = selected ? COLOR_COPPER_GOLD : new Color(96, 82, 66, 220)
      graphics.lineWidth = 1
      const dash = 5
      for (const [x0, y0, x1, y1] of [
        [left, bottom + height, left + width, bottom + height],
        [left + width, bottom + height, left + width, bottom],
        [left + width, bottom, left, bottom],
        [left, bottom, left, bottom + height],
      ] as const) {
        let from = 0
        const length = Math.hypot(x1 - x0, y1 - y0)
        while (from < length) {
          const t0 = from / length
          const t1 = Math.min(1, (from + dash) / length)
          graphics.moveTo(x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0)
          graphics.lineTo(x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1)
          from += dash * 2
        }
      }
      graphics.stroke()
      graphics.strokeColor = new Color(120, 106, 90, 200)
      graphics.lineWidth = 2
      graphics.moveTo(-6, 0)
      graphics.lineTo(6, 0)
      graphics.moveTo(0, -6)
      graphics.lineTo(0, 6)
      graphics.stroke()
      tile.nameLabel.string = ''
      tile.levelLabel.string = ''
      tile.statusLabel.string = ''
      tile.icon.active = false
      return
    }
    graphics.fillColor = row.collectable ? COLOR_BUILDING_DONE
      : row.upgrading ? COLOR_BUILDING_UPGRADING : COLOR_BUILDING
    graphics.roundRect(left, bottom, width, height, 6)
    graphics.fill()
    // 纵深两笔：下缘投影 + 上缘高光，格子从"贴纸"变成"墩台"
    graphics.fillColor = new Color(12, 9, 7, 130)
    graphics.rect(left + 3, bottom, width - 6, 3)
    graphics.fill()
    graphics.fillColor = new Color(255, 236, 200, 22)
    graphics.rect(left + 3, bottom + height - 2, width - 6, 2)
    graphics.fill()
    graphics.strokeColor = selected ? COLOR_COPPER_GOLD : COLOR_PANEL
    graphics.lineWidth = selected ? 2 : 1
    graphics.roundRect(left, bottom, width, height, 6)
    graphics.stroke()

    // 等级圆徽：右上角小铜圈里的数字，同类 SLG 的等级通用落位
    const badgeX = CELL_WIDTH / 2 - 12
    const badgeY = CELL_HEIGHT / 2 - 11
    graphics.fillColor = new Color(16, 13, 11, 235)
    graphics.circle(badgeX, badgeY, 9)
    graphics.fill()
    graphics.strokeColor = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
    graphics.lineWidth = 1
    graphics.circle(badgeX, badgeY, 9)
    graphics.stroke()
    tile.levelLabel.string = `Lv${row.level}`
    tile.levelLabel.color = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
    tile.levelLabel.node.setPosition(new Vec3(badgeX, badgeY, 0))
    tile.levelLabel.getComponent(UITransform)?.setContentSize(new Size(20, 14))

    tile.nameLabel.string = shortName(row.configId)
    tile.statusLabel.string = tileStatus(row)
    tile.nameLabel.color = COLOR_TEXT
    tile.statusLabel.color = row?.paused ? COLOR_WARNING
      : row?.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
    const iconVisible = applyIconSprite(tile.icon, buildingIconKey(row.configId), 30, 30)
    tile.icon.active = iconVisible

    if (row?.upgrading) {
      const ratio = row.collectable ? 1 : Math.min(1, Math.max(0, Number.parseInt(row.progressText ?? '0', 10) / 100))
      const barWidth = CELL_WIDTH - 16
      graphics.fillColor = COLOR_PANEL
      graphics.rect(-barWidth / 2, -CELL_HEIGHT / 2 + 4, barWidth, 3)
      graphics.fill()
      graphics.fillColor = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
      graphics.rect(-barWidth / 2, -CELL_HEIGHT / 2 + 4, barWidth * ratio, 3)
      graphics.fill()
    }
  }

  private renderSelection(row: BuildingRow | null): void {
    if (this.selectedTitle !== null) {
      this.selectedTitle.string = row?.title ?? '点击建筑查看详情'
      this.selectedTitle.color = row?.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
    }
    if (this.selectedStatus !== null) {
      this.selectedStatus.string = row === null
        ? '升级、加速、收割都在下方操作'
        : selectionStatus(row)
      this.selectedStatus.color = row?.paused ? COLOR_WARNING : COLOR_TEXT_DIM
    }
    this.wireActionButtons(row)
  }

  private wireActionButtons(row: BuildingRow | null): void {
    for (const [button, kind] of Array.from(this.actionButtons)) {
      button.off('touch-start')
      const visible = row !== null && (kind === 'collect' ? row.collectable
        : kind === 'upgrade' ? !row.upgrading && !row.collectable && !row.paused
          : row.upgrading && !row.collectable)
      button.active = visible
      if (!visible || row === null) {
        continue
      }
      button.on('touch-start', (_event: EventTouch) => {
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
      label.overflow = Label.Overflow.CLAMP
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

function shortName(configId: string): string {
  const name = configId.replace(/^building_/, '').replace(/_/g, ' ')
  return name.length > 12 ? `${name.slice(0, 11)}…` : name
}

function tileStatus(row: BuildingRow): string {
  if (row.collectable) {
    return '可收割'
  }
  if (row.upgrading) {
    return row.progressText === null ? '升级中' : `升级 ${row.progressText}`
  }
  if (row.paused) {
    return '已暂停'
  }
  return '空闲'
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
