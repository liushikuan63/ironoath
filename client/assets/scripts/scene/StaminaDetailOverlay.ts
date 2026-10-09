/**
 * 职责：**体力详情弹层**（B09 §5）—— 点资源条上的「体力」那一行打开。
 * 依赖：Cocos、`game/ui/StaminaDetail` 的纯视图模型（本文件只负责把模型画出来，不做任何判定）。
 *
 * <p>为什么要有它：`GET /stamina` 与 `POST /stamina/buy` 早就齐了，客户端却**一处调用都没有**
 * （收口清单"客户端发送口缺口"里的 `staminaView` / `staminaBuy`）—— 玩家看得见体力条，
 * 却既看不到恢复节奏、也买不了体力。
 *
 * <p>画法照仓库既有 overlay 的惯例：自己建节点、自己画圆角底、`hide()` 收起。
 * **判定全在视图模型里**（协议要求的三条：倒计时用 `nextPointAt - serverNow`、满了不显示倒计时、
 * 到每日上限时按钮置灰而不是隐藏）。
 */
import {
  Color, EventTouch, Graphics, Label, Node, Size, Sprite, UITransform, Vec3,
} from 'cc'
import type { StaminaDetailView } from '../game/ui/StaminaDetail'
import { applySystemUiFont } from './UiFont'
import { applyIronButton, applySlicedSprite } from './ArtCatalog'
import { PANEL_IRON_INSET } from '../game/art/ArtFamilies'
import { DIALOG_SCRIM, applyDialogButton, fitExistingDialog } from './DialogStyle'

// 360×260 → 440×360：border 抬到 80·72 后，260 高的净区只剩 116px，
// 「已买次数」那一行被按钮盒压住（1:1 截图上是两行字叠在一起）。退路同礼包：抬高面板，
// 不把 border 调回去（调回去 = 切分线重新穿过角帽）。规格 §七 Q6 / 台账 #811。
const PANEL_W = 440
const PANEL_H = 360
const BUY_W = 180
const BUY_H = 36
const COLOR_BACKDROP = DIALOG_SCRIM
const COLOR_PANEL = new Color(38, 30, 22, 245)
const COLOR_BORDER = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_DIM = new Color(170, 158, 138, 255)
const COLOR_BUY = new Color(184, 134, 11, 255)
/** #805 定的置灰机制：贴图路径用 color 乘灰（引擎运行时有 `grayscale`，本仓 headless 的 cc 类型桩里没有）。 */
const COLOR_TINT_ON = new Color(255, 255, 255, 255)
const COLOR_TINT_OFF = new Color(104, 96, 88, 255)
const COLOR_BUY_OFF = new Color(70, 62, 52, 255)
/**
 * 贴图路径的置灰：`Sprite.color` 与贴图相乘，白＝原色。
 * 不用 `sprite.grayscale` —— 引擎运行时有，但本仓 headless 的 cc 类型桩里没有这个属性，
 * `check-client-typecheck` 会直接报 TS2339（实测）。
 */

export class StaminaDetailOverlay {
  readonly node: Node
  private readonly titleLabel: Label
  private readonly recoverLabel: Label
  private readonly nextLabel: Label
  private readonly boughtLabel: Label
  private readonly noteLabel: Label
  private readonly buyCaption: Label
  /**
   * 买体力键的两条绘制路径，二选一：素材上来了走 `buySprite`（置灰 = `grayscale`），
   * 没上来就退回 `buyBackground` 那块实心圆角矩形（置灰 = 换 fillColor）。
   * 两条都留着是因为「置灰而不是隐藏」是协议明写的要求 —— 换成贴图不能把它弄丢。
   */
  private readonly buySprite: Sprite | null
  private readonly buyBackground: Graphics | null
  private buyEnabled = false
  private view: StaminaDetailView | null = null
  private readonly layoutDialog: () => void

  onBuy: (() => void) | null = null

  constructor(parent: Node) {
    this.node = new Node('StaminaDetail')
    this.node.layer = parent.layer
    parent.addChild(this.node)
    this.node.active = false

    // 背景板铺满：点它关掉（面板会吞掉自己的那一下，见下）
    const backdrop = new Node('Backdrop')
    backdrop.layer = this.node.layer
    this.node.addChild(backdrop)
    backdrop.addComponent(UITransform).setContentSize(new Size(4000, 4000))
    const backdropGraphics = backdrop.addComponent(Graphics)
    backdropGraphics.fillColor = COLOR_BACKDROP
    backdropGraphics.rect(-2000, -2000, 4000, 4000)
    backdropGraphics.fill()
    backdrop.on('touch-start', () => this.hide(), this)

    const panel = new Node('Panel')
    panel.layer = this.node.layer
    this.node.addChild(panel)
    panel.addComponent(UITransform).setContentSize(new Size(PANEL_W, PANEL_H))
    // 底板走 A 档九宫格（360×260 ⇒ 顶底 36 占高 28%，在 §二 的余量内）。
    // 加载失败退回原来的"填充 + 2px 描边"，两条路径都在屏上验过才算数。
    if (!applySlicedSprite(panel, 'ui.panel.iron', PANEL_W, PANEL_H)) {
      const panelGraphics = panel.addComponent(Graphics)
      panelGraphics.fillColor = COLOR_PANEL
      panelGraphics.strokeColor = COLOR_BORDER
      panelGraphics.lineWidth = 2
      panelGraphics.roundRect(-PANEL_W / 2, -PANEL_H / 2, PANEL_W, PANEL_H, 10)
      panelGraphics.fill()
      panelGraphics.stroke()
    }
    // **面板要吞掉自己的触摸**：它没有别的监听者，不吞的话点在面板内部会落到背景板上把弹层关掉
    panel.on('touch-start', () => {}, this)

    // 内容必须落在九宫格的**净区**里：铜边占掉上下各 PANEL_IRON_INSET.top/bottom、左右各 .left/.right。
    // 首版沿用旧的 `PANEL_H / 2 - 34` 排版，真截图上的后果是标题压在铜边内线上、
    // 底部那句被下铜边切掉半截 —— 换材质不等于自动就有安全区，这一圈要显式还给内容。
    const top = PANEL_H / 2 - PANEL_IRON_INSET.top
    const bottom = -PANEL_H / 2 + PANEL_IRON_INSET.bottom
    const innerW = PANEL_W - PANEL_IRON_INSET.left - PANEL_IRON_INSET.right

    this.titleLabel = this.addLabel(panel, 'Title', 0, top - 22, COLOR_BORDER, 20, innerW)
    this.recoverLabel = this.addLabel(panel, 'Recover', 0, top - 48, COLOR_DIM, 14, innerW)
    this.nextLabel = this.addLabel(panel, 'Next', 0, top - 70, COLOR_DIM, 14, innerW)
    this.boughtLabel = this.addLabel(panel, 'Bought', 0, top - 92, COLOR_DIM, 14, innerW)

    const buy = new Node('BuyButton')
    buy.layer = panel.layer
    panel.addChild(buy)
    buy.setPosition(new Vec3(0, bottom + 40, 0))
    buy.addComponent(UITransform).setContentSize(new Size(BUY_W, BUY_H))
    // C 档铁钮：先试贴图，失败才建 Graphics（`applySlicedSprite` 自己会 addComponent(Sprite)）。
    // 按下换 hover 那一态（更亮更饱和，触屏没有悬停、这张素材的语言就是"这一颗是当前项"）；
    // **置灰仍走 #805 定的 color 乘灰**（`tools/verify-ui-v25-runtime.mjs` 把那条钉成了判据）。
    this.buySprite = applyIronButton(buy, 'normal', BUY_W, BUY_H) ? buy.getComponent(Sprite) : null
    this.buyBackground = this.buySprite === null ? buy.addComponent(Graphics) : null
    this.buyCaption = this.addLabel(buy, 'Caption', 0, 0, COLOR_TEXT, 15, BUY_W - 12)
    buy.on('touch-start', (_event: EventTouch) => {
      if (this.buyEnabled) {
        // 按下给"更亮更饱和"那一态：触屏没有 hover，但这张素材本来就是"这一颗是当前项"的语言。
        applyIronButton(buy, 'hover', BUY_W, BUY_H)
        this.onBuy?.()
      }
    }, this)
    buy.on('touch-end', () => {
      if (this.buySprite !== null) {
        applyIronButton(buy, 'normal', BUY_W, BUY_H)
      }
    }, this)

    this.noteLabel = this.addLabel(panel, 'Note', 0, bottom + 12, COLOR_DIM, 12, innerW)

    const close = new Node('CloseButton')
    close.layer = panel.layer
    panel.addChild(close)
    // 关闭键留在净区内：角上 (190,156) 正好落在右上角铜帽与铆钉上（A 档角帽占宽 12.5%~19%）。
    close.setPosition(new Vec3(PANEL_W / 2 - PANEL_IRON_INSET.left - 20,
      PANEL_H / 2 - PANEL_IRON_INSET.top - 20, 0))
    close.addComponent(UITransform).setContentSize(new Size(40, 40))
    applyDialogButton(close, true, 40, 40)
    this.addLabel(close, 'Caption', 0, 0, COLOR_TEXT, 16, 40).string = '×'
    close.on('touch-start', () => this.hide(), this)
    close.setPosition(new Vec3(100, close.position.y, 0))
    buy.setPosition(new Vec3(-20, buy.position.y, 0))
    // 两个操作在下沿并排，短屏仍可取消；说明与恢复节奏完整进入滚动净区。
    buy.getComponent(UITransform)!.setContentSize(BUY_W, BUY_H)
    applyDialogButton(buy, true, BUY_W, BUY_H)
    this.layoutDialog = fitExistingDialog(this.node, panel,
      [this.titleLabel.node, this.recoverLabel.node, this.nextLabel.node, this.boughtLabel.node, this.noteLabel.node],
      [buy, close], 'ui.panel.iron', PANEL_W, PANEL_H)
  }

  /** 画一帧。**不判定**：能不能买、该说什么，都是视图模型算好的。 */
  render(view: StaminaDetailView): void {
    this.view = view
    this.titleLabel.string = view.titleText
    this.recoverLabel.string = view.recoverText
    this.nextLabel.string = view.nextText ?? ''
    this.boughtLabel.string = view.boughtText
    this.buyCaption.string = view.buyLabel
    this.noteLabel.string = view.noteText ?? ''
    this.buyEnabled = view.buyEnabled
    // 置灰而不是隐藏（协议明写理由）：到上限那一天玩家仍看得见「明天还能买」
    if (this.buySprite !== null) {
      // 贴图路径：置灰交给 color 乘灰，文字同步压暗 —— 只灰底不灰字会读成"还能点"。
      this.buySprite.color = view.buyEnabled ? COLOR_TINT_ON : COLOR_TINT_OFF
      this.buyCaption.color = view.buyEnabled ? COLOR_TEXT : COLOR_BUY_OFF
    } else if (this.buyBackground !== null) {
      this.buyBackground.clear()
      this.buyBackground.fillColor = view.buyEnabled ? COLOR_BUY : COLOR_BUY_OFF
      this.buyBackground.roundRect(-BUY_W / 2, -BUY_H / 2, BUY_W, BUY_H, 8)
      this.buyBackground.fill()
      this.buyCaption.color = COLOR_TEXT
    }
    this.node.active = true
    this.layoutDialog()
  }

  hide(): void {
    this.node.active = false
  }

  get visible(): boolean {
    // 用 `active` 而不是 `activeInHierarchy`：探针里读后者没问题（那是运行期 JS），
    // 但 headless 的 `cc` 类型声明里只有 `active` —— 门里的 tsc 会红（check-client-typecheck 抓到过）。
    return this.node.active === true
  }

  /** 探针读数用：当前画的是哪一帧。 */
  get currentView(): StaminaDetailView | null {
    return this.view
  }

  private addLabel(parent: Node, name: string, x: number, y: number,
                   color: Color, size: number, width: number): Label {
    const node = new Node(name)
    node.layer = parent.layer
    parent.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(width, size + 8))
    node.setPosition(new Vec3(x, y, 0))
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = ''
    label.color = color
    label.fontSize = size
    label.lineHeight = size + 6
    label.overflow = Label.Overflow.SHRINK
    return label
  }
}
