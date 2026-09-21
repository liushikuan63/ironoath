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
  CITY_GRID_HEIGHT, CITY_GRID_WIDTH, buildCityGrid, buildCityPanel, cancelMessage, collectMessage,
} from '../game/city/CityPanel'
import type { BuildingRow, CityGrid, CityPanelView as CityPanelData } from '../game/city/CityPanel'
import { buildBuildChoices } from '../game/session/Choices'
import type {
  CityCancelResp, CityCollectResp, CityListResp, SpeedUpSource,
} from '../net/generated/CityProtocol'
import { ChoiceOverlay } from './ChoiceOverlay'
import {
  applyAnyIconSprite, applyCommandButton, applyIconSprite, applySlicedSprite,
  buildingIconKey, ensureFamily, familyFrame,
} from './ArtCatalog'
import { buildingArtKey, PANEL_FRAME_BAND } from '../game/art/ArtFamilies'
import { applySystemUiFont } from './UiFont'
import { DISTRICT_TINT_RGB, projectSceneLayout } from '../game/city/CitySceneAnchors'
import type { ProjectedPlate, SceneDistrict } from '../game/city/CitySceneAnchors'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_BUILDING = new Color(58, 46, 36, 255)
const COLOR_BUILDING_UPGRADING = new Color(82, 61, 30, 255)
const COLOR_BUILDING_DONE = new Color(48, 74, 48, 255)
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

const CELL_WIDTH = 86
const CELL_HEIGHT = 46

/**
 * 五区地皮色。色值真源在 `CitySceneAnchors.DISTRICT_TINT_RGB`（引擎无关层），
 * 这里只把它包成 `cc.Color` 给 Graphics 用 —— "同区同色、不成棋盘"是数据属性，
 * 放在能跑 node:test 的那一层才真的会失败（见 `tests/CityGroundTint.test.ts`）。
 *
 * <p>区**只决定这块地长什么样，不否决能建什么** —— 老号把农田建在台基上，就照画农田 + 台基地皮。
 */
const GROUND_BY_DISTRICT = Object.fromEntries(
  (Object.keys(DISTRICT_TINT_RGB) as SceneDistrict[])
    .map((district) => [district, new Color(...DISTRICT_TINT_RGB[district], 255)]),
) as Readonly<Record<SceneDistrict, Color>>
const CELL_GAP = 4
const CONTENT_WIDTH = CITY_GRID_WIDTH * CELL_WIDTH + (CITY_GRID_WIDTH - 1) * CELL_GAP
const GRID_HEIGHT = CITY_GRID_HEIGHT * CELL_HEIGHT + (CITY_GRID_HEIGHT - 1) * CELL_GAP
/**
 * 36 格地皮的**落点**：由 A16 的锚点表等比投影进这块内容区，不再是均匀棋盘。
 *
 * <p>内容区尺寸仍按"6×6 每格 86×46"算 —— 那是卡片留给这一层的框，不是格子的排法。
 * 投影本身在引擎无关层（`CitySceneAnchors.projectSceneLayout`），
 * 判据（全在框内 / 互不重叠 / 不成棋盘）在 `tests/CitySceneProjection.test.ts`。
 */
const SCENE_LAYOUT = projectSceneLayout(CONTENT_WIDTH, GRID_HEIGHT, 6)
/**
 * 面板框的四角尺寸：**切分几何的唯一真源是 `ui/generated/ui/panel-kingdom-v1.png.meta` 的 border***，
 * 这里的常量只是把它交给布局用（两者由 `tests/ArtFamilies.test.ts` 对账，不一致就红）。
 * 九宫格只固定四角，所以卡片里任何内容（标题、资源行、格子列、收割按钮）都得让开这一圈，
 * 否则会压在角饰与侧栏下面 —— 之前按 16px 内缩排，正好被 44px 的角饰吃掉 28px。
 */
const FRAME_BAND = PANEL_FRAME_BAND
const CARD_INSET = FRAME_BAND + 4
const CARD_WIDTH = CONTENT_WIDTH + CARD_INSET * 2
const HEADER_HEIGHT = 128
const ACTION_HEIGHT = 76
const CARD_HEIGHT = HEADER_HEIGHT + GRID_HEIGHT + ACTION_HEIGHT + 108
const PADDING = 16
const PANEL_MARGIN = 12
const CARD_OFFSET = 24
const MAX_SCALE = 1.4
const ACTION_BUTTON_WIDTH = 82
const ACTION_BUTTON_HEIGHT = 32
/** 右上角那颗「一键收割」的宽与中心 x（中心由 76 这个贴边量决定）—— 头部那一块要让到它的左沿之前 */
const CORNER_KEY_WIDTH = 132
const CORNER_KEY_CENTER_X = CARD_WIDTH / 2 - FRAME_BAND - 76
/**
 * 标题与队列行能用的宽度：内容区左沿到「一键收割」左沿，再留 8 的缝。
 *
 * <p>这两个数不是估的：世界矩形实测「一键收割」占 x 130..262，队列行 317 宽居中排时
 * 右端顶到 159 —— 被压住 29px（#335 的截图才看出来：机器全绿、眼睛看得见）。
 */
const HEADER_TEXT_WIDTH = CONTENT_WIDTH / 2 + CORNER_KEY_CENTER_X - CORNER_KEY_WIDTH / 2 - 8

type RowAction = 'upgrade' | 'speedAd' | 'speedGold' | 'collect' | 'cancel'

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
  private frameGraphics: Graphics | null = null
  private headerLabel: Label | null = null
  /** 资源行数超过那 6 颗固定 Label 时，多出来的部分在这里显式说一句，不静默丢 */
  private resourceOverflowLabel: Label | null = null
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
  /** 玩家点了「取消」这一行的建造。返还多少由服务端算，本场景只把回执念出来 */
  onCancelBuild: ((buildingId: string) => void) | null = null
  /** 卡片左下角那颗「学院 · 研究」：打开全局研究页（V03-a-S1 的读侧 + #323 的写侧都在那一页） */
  onOpenTech: (() => void) | null = null

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
    // 建筑正稿族按需拉取：先画一帧图集小图标，正稿到了补一帧；拉不到就停在图集上。
    // 不进启动预载 —— 15 张 107.6KB 全在分包里，内城不是所有人的第一屏。
    ensureFamily('building').then((loaded) => {
      if (loaded > 0 && this.isValid) {
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

  override onDestroy(): void {
    this.gridTiles.length = 0
    this.actionButtons.clear()
    this.buildPicker?.hide()
    this.buildPicker = null
    this.onUpgrade = null
    this.onSpeedUp = null
    this.onCollect = null
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

    // 头部整块从"框的四角带"下面开始排（原来从卡片外沿往下 16px 起排，标题正好压在角饰上）。
    // **左对齐 + 限定宽度**：居中排时队列行的右端顶进右上角那颗「一键收割」里 29px（实测），
    // 而这一行最长的那句（队列 + 可开启 + 坐标异常）会随服务端给的数变长 —— 让位给键，长句交给 SHRINK。
    const top = CARD_HEIGHT / 2 - FRAME_BAND
    this.headerLabel = this.addLabel(card, 'Header', -CONTENT_WIDTH / 2, top - 14,
      COLOR_COPPER_GOLD, 22, true, HEADER_TEXT_WIDTH)
    this.queueLabel = this.addLabel(card, 'Queue', -CONTENT_WIDTH / 2, top - 38,
      COLOR_TEXT, 16, true, HEADER_TEXT_WIDTH)

    const columnWidth = CONTENT_WIDTH / 3
    for (let row = 0; row < 2; row++) {
      for (let column = 0; column < 3; column++) {
        // 左对齐 + 限定列宽：数值位数由服务端算，不可控（"1000000" 和 "200" 同栏）。
        // 中心锚点的长值会一路顶到九宫格的右侧装饰带里 —— 量具在真实数据下实测到右压带 17.3px。
        this.resourceLabels.push(this.addLabel(
          card, `Resource-${row}-${column}`,
          -CONTENT_WIDTH / 2 + columnWidth * column, top - 62 - row * 18,
          COLOR_TEXT_DIM, 14, true, columnWidth - 8))
      }
    }

    // 资源行超过 6 项时的兜底那一行（平时是空串，不占视觉）
    this.resourceOverflowLabel = this.addLabel(
      card, 'ResourceOverflow', -CONTENT_WIDTH / 2, top - 98, COLOR_TEXT_DIM, 12, true, CONTENT_WIDTH)

    this.buildGrid(card)
    this.buildActionBar(card)
    this.messageLabel = this.addLabel(card, 'Message', 0,
      -CARD_HEIGHT / 2 + FRAME_BAND + 10, COLOR_WARNING, 15)
  }

  private buildGrid(parent: Node): void {
    const grid = new Node('CityGrid')
    grid.layer = parent.layer
    parent.addChild(grid)
    grid.addComponent(UITransform).setContentSize(new Size(CONTENT_WIDTH, GRID_HEIGHT))
    grid.setPosition(new Vec3(0, CARD_HEIGHT / 2 - PADDING - HEADER_HEIGHT - GRID_HEIGHT / 2, 0))
    this.buildGround(grid)
    // 按格位排序后再挂：Cocos 按子节点次序绘制，靠城门（y 大）的格子要后画才压得住前面的
    const ordered = [...SCENE_LAYOUT.plates]
      .sort((a, b) => (a.gridY * CITY_GRID_WIDTH + a.gridX) - (b.gridY * CITY_GRID_WIDTH + b.gridX))
    for (const plate of ordered) {
      const index = plate.gridY * CITY_GRID_WIDTH + plate.gridX
      const tile = new Node(`Grid-${index}`)
      tile.layer = grid.layer
      grid.addChild(tile)
      tile.setPosition(new Vec3(plate.x, plate.y, 0))
      tile.addComponent(UITransform).setContentSize(new Size(plate.width, plate.height))
      const graphics = tile.addComponent(Graphics)
      // 描边先挂、正稿后挂 ⇒ 同一父节点下描边在正稿之后绘制不到它上面去（Cocos 按子节点次序画）
      const iconRim = new Node('BuildingRim')
      iconRim.layer = tile.layer
      tile.addChild(iconRim)
      iconRim.setPosition(new Vec3(0, -plate.height / 2, 0))
      const rimBox = iconRim.addComponent(UITransform)
      rimBox.setAnchorPoint(0.5, 0)
      const icon = new Node('BuildingIcon')
      icon.layer = tile.layer
      tile.addChild(icon)
      // 图标**底边贴在脚印下沿**、向上长出格子 —— 等距城景里建筑是"立在地上"的，
      // 居中塞进脚印会把屋顶压扁。压叠由上面的绘制次序兜住。
      icon.setPosition(new Vec3(0, -plate.height / 2, 0))
      const iconBox = icon.addComponent(UITransform)
      iconBox.setAnchorPoint(0.5, 0)
      iconBox.setContentSize(new Size(plate.width, plate.width))
      const levelLabel = this.addLabel(tile, 'Level', 0, 0, COLOR_TEXT_DIM, 10)
      levelLabel.node.getComponent(UITransform)?.setContentSize(new Size(20, 14))
      levelLabel.overflow = Label.Overflow.SHRINK
      // 名字压在脚印下沿：正稿是"往上长"的，脚印下沿那一条本来就是房基，
      // 9px 的一行字盖在房基上比盖在屋顶上可读，也不会去撞前一排的建筑。
      const nameLabel = this.addLabel(tile, 'Name', 0, -plate.height / 2 + 6, COLOR_TEXT, 9)
      nameLabel.node.getComponent(UITransform)?.setContentSize(new Size(plate.width, 12))
      nameLabel.overflow = Label.Overflow.SHRINK
      this.gridTiles.push({ node: tile, graphics, iconRim, icon, levelLabel, nameLabel, plate })
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
    const graphics = ground.addComponent(Graphics)
    for (const plate of SCENE_LAYOUT.plates) {
      // 地皮颜色按**所在区**给、落点按**锚点投影**给：按奇偶交替就是棋盘，
      // 而规格 §3.3 明令"不允许把 36 个锚点仍均匀排成棋盘，再称为完成城景化"。
      graphics.fillColor = GROUND_BY_DISTRICT[plate.district]
      graphics.rect(plate.x - plate.width / 2, plate.y - plate.height / 2, plate.width, plate.height)
      graphics.fill()
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
    // 「取消」与「升级」互斥（升级中才谈得上取消），所以共用同一个槽位 ——
    // 按钮条只有 536 宽，升级中那一行已经排了两颗加速键，再加第三颗就溢出条外
    this.createActionButton(bar, 'DetailCancelButton', '取消', -12, 'cancel')
    this.createActionButton(bar, 'DetailSpeedAdButton', '广告加速', 78, 'speedAd')
    this.createActionButton(bar, 'DetailSpeedGoldButton', '金币加速', 168, 'speedGold')

    const collectAll = new Node('CollectAllButton')
    collectAll.layer = parent.layer
    parent.addChild(collectAll)
    // 落在四角带之内：贴着内容区右上，不压角饰
    collectAll.setPosition(new Vec3(
      CORNER_KEY_CENTER_X, CARD_HEIGHT / 2 - FRAME_BAND - 20, 0))
    collectAll.addComponent(UITransform).setContentSize(new Size(CORNER_KEY_WIDTH, 34))
    if (!applyCommandButton(collectAll, 'normal', CORNER_KEY_WIDTH, 34)) {
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
    // 落点是**卡片底部左角**。顶部那一行放不下第二颗常驻键：标题 161 + 队列行 317 + 两颗键 272 = 750，
    // 而内容区只有 532 —— 这是几何问题，不是把 x 挪一挪能解决的（#335 的截图量出来的世界矩形：
    // 标题被压住 91px）。底部那一条只有居中的回执（约 230 宽），左角本来就空着。
    // y 取"贴内容区下沿、下半截进四角带"：再往上 5px 就压到选中详情条（它的下沿在 −236，
    // 而这颗键有 34 高，详情条与内容区下沿之间只剩 24px，塞不下）—— 让开可点的详情条，宁可压装饰带。
    const tech = new Node('TechOpenButton')
    tech.layer = parent.layer
    parent.addChild(tech)
    tech.setPosition(new Vec3(
      -CARD_WIDTH / 2 + FRAME_BAND + 76, -CARD_HEIGHT / 2 + FRAME_BAND + 2, 0))
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
        if (panel !== null && panel.buildOptions.length > 0) {
          const gridX = tile.plate.gridX
          const gridY = tile.plate.gridY
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
    const plate = tile.plate
    const left = -plate.width / 2 + 1
    const bottom = -plate.height / 2 + 1
    const width = plate.width - 2
    const height = plate.height - 2
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
      tile.levelLabel.string = ''
      tile.nameLabel.string = ''
      tile.icon.active = false
      tile.iconRim.active = false
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
    const badgeX = plate.width / 2 - 10
    const badgeY = plate.height / 2 - 9
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

    // 暂停没有文字可写了（名字与状态都收进下面的选择栏），所以给它一枚实心琥珀点。
    // 少这一个记号就等于"暂停与升级中在城景里长得一样"，而玩家下一步要做的两件事不同。
    if (row.paused) {
      graphics.fillColor = COLOR_WARNING
      graphics.circle(badgeX - 22, badgeY, 4)
      graphics.fill()
    }

    // 格子上只留"名字 + 等级 + 要不要处理"，状态文字撤掉：可收割 / 升级中 / 已暂停各有
    // 非文字的写法（描边绿、描边铜 + 底部进度条、实心琥珀点），而名字没有别的表达方式 ——
    // 36 格全靠图分辨种类，认不出就得有个名字。选择栏那两行照旧，是"点中之后看详情"。
    tile.nameLabel.string = row.name
    tile.nameLabel.color = row.collectable ? COLOR_GOOD : COLOR_TEXT
    const iconSide = Math.max(26, plate.width * 0.92)
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
      rim.color = COLOR_ART_RIM
      rim.enabled = true
      tile.iconRim.getComponent(UITransform)
        ?.setContentSize(new Size(iconSide * 1.12, iconSide * 1.12))
      tile.iconRim.active = true
    }

    if (row.upgrading) {
      const ratio = row.collectable ? 1 : Math.min(1, Math.max(0, Number.parseInt(row.progressText ?? '0', 10) / 100))
      const barWidth = plate.width - 10
      graphics.fillColor = COLOR_PANEL
      graphics.rect(-barWidth / 2, -plate.height / 2 + 3, barWidth, 3)
      graphics.fill()
      graphics.fillColor = row.collectable ? COLOR_GOOD : COLOR_COPPER_GOLD
      graphics.rect(-barWidth / 2, -plate.height / 2 + 3, barWidth * ratio, 3)
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
        : kind === 'cancel' ? row.upgrading
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
      transform.setContentSize(new Size(maxWidth, fontSize * 1.6))
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
