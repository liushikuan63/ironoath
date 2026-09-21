/**
 * 职责：战令面板 —— 赛季进度、档位列表与两条线的领取（B24 S-d-e）。
 * 依赖：cc（渲染）、game/battlePass/BattlePassPanel（展示数据组装，已单测）、scene/UiFont。
 *
 * <p><b>本场景不做任何判定</b>（铁律 2）：这一档达没达成、这条线领没领、付费线解锁了没有，
 * 全部来自服务端下发的那份状态。本文件只做两件表现层的事：把行画出来、把点击意图回抛。
 *
 * <p><b>无法领取的那颗按钮是灰的，但两行的原因都写在行里</b>：没达成与没买战令是两件事
 * （玩家要做的下一步完全不同），所以行上那两行小字分别说「还差 N 分」与「付费线未解锁」，
 * 而不是给一颗没有解释的灰按钮 —— 那种会被当成坏了。
 *
 * <p><b>行数按实测可视高度算</b>（与商店/军队/外观面板同一条纪律）：写死行数会在矮窗口里
 * 把最后一行压在底部导航条下面；画不下的档位由表头那句「第 a–b 档 / 共 20 档」交代，
 * 而且窗口起点跟着"接下来该领哪一档"走（见 `windowStartOf`）—— 玩家打开就能看见该点的那一行。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { buildBattlePassPanel, trackStateText } from '../game/battlePass/BattlePassPanel'
import type { BattlePassPanelView as BattlePassView, BattlePassRow } from '../game/battlePass/BattlePassPanel'
import type { BattlePassPanelData } from '../game/session/AppRoot'
import type { BattlePassTrack } from '../net/generated/BattlePassProtocol'
import { applySystemUiFont, keepOneLine } from './UiFont'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(22, 18, 16, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_LOCKED = new Color(34, 31, 28, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)
const COLOR_BUTTON_OFF = new Color(64, 55, 46, 255)

const PANEL_WIDTH = 700
const ROW_HEIGHT = 66
const ROW_GAP = 6
/** 标题 + 四行状态 + 进度条那一块占掉的高度 */
const HEADER_HEIGHT = 186
const PADDING = 16
const ROW_POOL_SIZE = 5
/** 屏幕底部要给导航条让出的高度（与其它面板同一个数：8 + 52 + 8）。 */
const BOTTOM_RESERVED = 68
const BUTTON_WIDTH = 84
const BUTTON_HEIGHT = 30

@ccclass('BattlePassPanelView')
export class BattlePassPanelView extends Component {
  private panel: BattlePassPanelData | null = null
  private headerLabel: Label | null = null
  private pointsLabel: Label | null = null
  private claimedLabel: Label | null = null
  private remainLabel: Label | null = null
  private noticeLabel: Label | null = null
  private readonly rowNodes: Node[] = []
  private readonly rowTier: Label[] = []
  private readonly rowReached: Label[] = []
  private readonly rowFree: Label[] = []
  private readonly rowPaid: Label[] = []
  private readonly freeButtons: Node[] = []
  private readonly paidButtons: Node[] = []
  private readonly freeCaptions: Label[] = []
  private readonly paidCaptions: Label[] = []
  /** 每一行当前对应哪一档、以及两条线此刻能不能领（点击时要知道点的是谁）。 */
  private readonly rowTiers: Array<number | null> = []
  private readonly rowFreeOk: boolean[] = []
  private readonly rowPaidOk: boolean[] = []

  /** 领取某一档的某一条线。可不可领由服务端说了算，编排层会再挡一次并说明原因。 */
  onClaim: ((tier: number, track: BattlePassTrack) => void) | null = null

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
    this.onClaim = null
  }

  /** 装载整块战令视图（编排层组装好的，本文件不改其中任何判定）。 */
  attach(battlePass: BattlePassPanelData): void {
    this.panel = battlePass
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
    this.pointsLabel = this.addLabel('Points', 0, top - 48, COLOR_TEXT, 17)
    this.claimedLabel = this.addLabel('Claimed', 0, top - 72, COLOR_TEXT_DIM, 15)
    this.remainLabel = this.addLabel('Remain', 0, top - 96, COLOR_TEXT_DIM, 15)
    // 这五行都是居中的固定 y（间距只有 24~28），而 Label 会按文本把盒子撑高 ——
    // 撑到两行就直接叠在邻居身上（#363 在军队表头量出过同一形状，实测 hits 真出现）。
    // #364 当时用「SHRINK + 猜的一行高」去限，量具全绿，代价是整屏字被按比例缩小：
    // 页内实测落地 17/13/10/10/9 对设定 20/17/15/15/14（台账 #366 的迁移曲线）。
    // 现在改成直接关掉换行：盒子不可能撑成两行，字形也完全不碰。
    keepOneLine(this.headerLabel, 20)
    keepOneLine(this.pointsLabel, 17)
    keepOneLine(this.claimedLabel, 15)
    keepOneLine(this.remainLabel, 15)
    // 这一行两用：列表还没拉回来时的那句话，或（未解锁时）付费线为什么是灰的
    this.noticeLabel = this.addLabel('Notice', 0, top - 124, COLOR_TEXT_DIM, 14)
    keepOneLine(this.noticeLabel, 14)

    for (let index = 0; index < ROW_POOL_SIZE; index++) {
      const row = this.createRow(index)
      this.rowNodes.push(row.node)
      this.rowTier.push(row.tier)
      this.rowReached.push(row.reached)
      this.rowFree.push(row.free)
      this.rowPaid.push(row.paid)
      this.freeButtons.push(row.freeButton)
      this.paidButtons.push(row.paidButton)
      this.freeCaptions.push(row.freeCaption)
      this.paidCaptions.push(row.paidCaption)
      this.rowTiers.push(null)
      this.rowFreeOk.push(false)
      this.rowPaidOk.push(false)
    }
  }

  private createRow(index: number): {
    node: Node; tier: Label; reached: Label; free: Label; paid: Label
    freeButton: Node; freeCaption: Label; paidButton: Node; paidCaption: Label
  } {
    const node = new Node('PassRow')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
    graphics.fill()

    const tier = this.addLabel('Tier', -PANEL_WIDTH / 2 + PADDING, 14, COLOR_TEXT, 17, node)
    tier.horizontalAlign = Label.HorizontalAlign.LEFT
    tier.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    tier.node.getComponent(UITransform)?.setContentSize(new Size(200, 22))
    tier.overflow = Label.Overflow.SHRINK
    const reached = this.addLabel('Reached', -PANEL_WIDTH / 2 + PADDING, -13, COLOR_TEXT_DIM, 13, node)
    reached.horizontalAlign = Label.HorizontalAlign.LEFT
    reached.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    reached.node.getComponent(UITransform)?.setContentSize(new Size(200, 20))
    reached.overflow = Label.Overflow.SHRINK
    const free = this.addLabel('Free', 40, 14, COLOR_TEXT, 14, node)
    free.node.getComponent(UITransform)?.setContentSize(new Size(230, 20))
    free.overflow = Label.Overflow.SHRINK
    const paid = this.addLabel('Paid', 40, -13, COLOR_TEXT_DIM, 14, node)
    paid.node.getComponent(UITransform)?.setContentSize(new Size(230, 20))
    paid.overflow = Label.Overflow.SHRINK

    const freeButton = this.createButton(node, index, 'FREE', PANEL_WIDTH / 2 - 2 * (BUTTON_WIDTH + 6) + BUTTON_WIDTH / 2)
    const paidButton = this.createButton(node, index, 'PAID', PANEL_WIDTH / 2 - (BUTTON_WIDTH + 6) + BUTTON_WIDTH / 2)
    return {
      node, tier, reached, free, paid, freeButton: freeButton.node, freeCaption: freeButton.caption,
      paidButton: paidButton.node, paidCaption: paidButton.caption,
    }
  }

  private createButton(parent: Node, index: number, track: BattlePassTrack, x: number): {
    node: Node; caption: Label
  } {
    const button = new Node(`Claim_${track}`)
    button.layer = parent.layer
    parent.addChild(button)
    button.setPosition(new Vec3(x, 0, 0))
    button.addComponent(UITransform).setContentSize(new Size(BUTTON_WIDTH, BUTTON_HEIGHT))
    const graphics = button.addComponent(Graphics)
    this.paintButton(graphics, COLOR_BUTTON_OFF)
    const caption = this.addLabel('Caption', 0, 0, COLOR_TEXT, 14, button)
    button.on('touch-start', (_event: EventTouch) => {
      const tier = this.rowTiers[index] ?? null
      const ok = track === 'PAID' ? this.rowPaidOk[index] === true : this.rowFreeOk[index] === true
      if (tier !== null && ok) {
        this.onClaim?.(tier, track)
      }
    }, this)
    return { node: button, caption }
  }

  private paintButton(graphics: Graphics, fill: Color): void {
    graphics.clear()
    graphics.fillColor = fill
    graphics.roundRect(-BUTTON_WIDTH / 2, -BUTTON_HEIGHT / 2, BUTTON_WIDTH, BUTTON_HEIGHT, 5)
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
    const size = view.getVisibleSize()
    const topY = size.height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    const navTop = -size.height / 2 + BOTTOM_RESERVED
    const usable = topY + ROW_HEIGHT / 2 - navTop
    const maxRows = Math.max(1, Math.floor(usable / (ROW_HEIGHT + ROW_GAP)))

    // 视图按实测高度算好窗口，再让纯逻辑层组装这一屏该画哪几档（起点由 windowStartOf 给）
    const view2: BattlePassView = buildBattlePassPanel(panel.source, maxRows)

    if (this.headerLabel !== null) {
      // 零档位时 `rangeText` 是空串（`game/activity/BattlePassPanel.ts` 在 total === 0 时给空），
      // 无条件拼分隔符就会印出「赛季战令 · 」这种带尾巴的空分隔符（#349 目视抓到）。
      // 房规同 `ArmyPanelView:413`、`ShopPanelView:262`：可空的那一段非空才拼分隔符。
      this.headerLabel.string = view2.rangeText === ''
        ? '赛季战令'
        : `赛季战令 · ${view2.rangeText}`
    }
    if (this.pointsLabel !== null) {
      this.pointsLabel.string = view2.pointsText
    }
    if (this.claimedLabel !== null) {
      this.claimedLabel.string = view2.claimedText
    }
    if (this.remainLabel !== null) {
      this.remainLabel.string = view2.remainText
      this.remainLabel.color = view2.paidUnlocked ? COLOR_GOOD : COLOR_TEXT_DIM
    }
    if (this.noticeLabel !== null) {
      // 一行档位都没有时，这一格必须说句话：否则玩家看到的是"积分/已领/剩余"三行数字
      // 压着一整块空白（#344 那一族的最后一条）。句式照房规 `MarchPanelView:77`「暂无在外的队伍」。
      this.noticeLabel.string = view2.rows.length === 0
        ? '暂无档位'
        : (panel.notice ?? view2.noticeText ?? view2.paidHint ?? '')
      this.noticeLabel.color = panel.notice !== null ? COLOR_GOOD : COLOR_TEXT_DIM
    }

    this.rowNodes.forEach((node, index) => {
      const row: BattlePassRow | undefined = view2.rows[index]
      node.active = row !== undefined
      if (row === undefined) {
        this.rowTiers[index] = null
        this.rowFreeOk[index] = false
        this.rowPaidOk[index] = false
        return
      }
      // 行必须按序号往下排（池化节点建出来都在 y=0，不摆就是所有行叠在同一处 —— 商店面板踩过，见收口清单 #244）
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.rowTiers[index] = row.tier
      this.rowFreeOk[index] = row.freeClaimable
      this.rowPaidOk[index] = row.paidClaimable
      const graphics = node.getComponent(Graphics)
      if (graphics !== null) {
        graphics.clear()
        graphics.fillColor = row.reached ? COLOR_ROW : COLOR_ROW_LOCKED
        graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 5)
        graphics.fill()
      }
      this.rowTier[index]!.string = row.tierText
      this.rowTier[index]!.color = row.reached ? COLOR_TEXT : COLOR_TEXT_DIM
      this.rowReached[index]!.string = row.reachedText
      this.rowFree[index]!.string = `免费：${row.freeText} · ${trackStateText(row, 'FREE')}`
      this.rowFree[index]!.color = row.freeClaimed ? COLOR_TEXT_DIM
        : (row.freeClaimable ? COLOR_TEXT : COLOR_TEXT_DIM)
      this.rowPaid[index]!.string = `付费：${row.paidText} · ${trackStateText(row, 'PAID')}`
      this.rowPaid[index]!.color = row.paidClaimable ? COLOR_TEXT : COLOR_TEXT_DIM
      this.paintButton(this.freeButtons[index]!.getComponent(Graphics)!,
        row.freeClaimable ? COLOR_GOOD : COLOR_BUTTON_OFF)
      this.freeCaptions[index]!.string = row.freeClaimed ? '已领' : '领免费'
      this.freeCaptions[index]!.color = row.freeClaimable ? COLOR_BACKGROUND : COLOR_TEXT_DIM
      this.paintButton(this.paidButtons[index]!.getComponent(Graphics)!,
        row.paidClaimable ? COLOR_GOOD : COLOR_BUTTON_OFF)
      this.paidCaptions[index]!.string = row.paidClaimed ? '已领' : '领付费'
      this.paidCaptions[index]!.color = row.paidClaimable ? COLOR_BACKGROUND : COLOR_TEXT_DIM
    })
  }
}
