/**
 * 职责：邮件面板（B12 §2）—— 一屏邮件、一键领取、点一封标已读。
 * 依赖：cc（渲染）、game/mail/MailPanel（展示组装，已单测）、scene/NodePool、scene/UiFont。
 *
 * <p><b>本场景不做任何判定</b>（铁律 2）：能不能领、读过没有、还剩几天，
 * 全部来自服务端下发的 `claimed` / `read` / `expireAt`；这里只把 {@link buildMailPanel}
 * 算好的字符串画出来。
 *
 * <p><b>没有「单封领取」按钮是刻意的</b>：B12 只给了「一键领取全部」，而多一个入口
 * 就是多一套「已领」判据（两处各判一次迟早分叉）。行上的点击只做一件事 —— 标已读。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：长列表的 ScrollView（超过一屏目前截断并写明）、
 * 正式美术与图标。列表 item 已按 B07 §4 池化。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { buildMailPanel, claimOutcomeText } from '../game/mail/MailPanel'
import { truncatedNotice } from '../game/ui/TruncatedList'
import type { MailPanelView as MailPanelData, MailRow } from '../game/mail/MailPanel'
import type { MailClaimAllResp, MailListResp } from '../net/generated/MailProtocol'
import { NodePool } from './NodePool'
import { applySystemUiFont, capWidth } from './UiFont'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性（与 QuestPanelView 同一套）。美术常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(20, 17, 15, 238)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_DIM = new Color(32, 29, 27, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_CLAIMABLE = new Color(120, 176, 96, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 66
const ROW_GAP = 6
const PADDING = 16
const HEADER_HEIGHT = 64
const BUTTON_HEIGHT = 40
/** 池的预热行数：真正画几行由 `render` 按可视高算（下面 `BOTTOM_RESERVED` 那条理由）。 */
const MAX_VISIBLE_ROWS = 7
/** 底部导航条占掉的高度，与 QuestPanelView / ArmyPanelView 同一个口径（#389 定下的写法）。 */
const BOTTOM_RESERVED = 68
/** 右槽（状态 / 到期）：「6 天后到期」13 号字占约 78，留 140 给更长的下发文案。 */
const RIGHT_SLOT = 140
/** 左槽（标题 / 附件）吃掉剩下的宽度，中间留 12 的缝 —— 两槽相碰就是量具那一维的"字形相碰"。 */
const LEFT_SLOT = PANEL_WIDTH - 2 * PADDING - RIGHT_SLOT - 12

@ccclass('MailPanelView')
export class MailPanelView extends Component {
  private panelData: MailPanelData | null = null
  private pendingList: MailListResp | null = null
  private pendingNow = 0

  private rowPool: NodePool | null = null
  private readonly drawnRows: Node[] = []
  /** 行节点 → 它当前代表哪一封。池化复用后靠它把点击派回正确的 mailId。 */
  private readonly rowMailIds = new Map<Node, string>()
  private headerLabel: Label | null = null
  private outcomeLabel: Label | null = null
  private overflowLabel: Label | null = null
  private claimButton: Node | null = null
  private claimCaption: Label | null = null

  /** 玩家点了「一键领取」。领取是服务端的动作，本场景只递意图。 */
  onClaimAll: (() => void) | null = null
  /** 玩家点开一封未读的邮件（标已读）。已读的点了不做任何事。 */
  onReadRequested: ((mailId: string) => void) | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.rowPool = new NodePool(this.node, () => this.createRow(), MAX_VISIBLE_ROWS)
    this.buildHeader(size.height)
    if (this.pendingList !== null) {
      const pending = this.pendingList
      this.pendingList = null
      this.attach(pending, this.pendingNow)
    }
  }

  override onDestroy(): void {
    this.rowPool?.destroy()
    this.rowPool = null
    this.drawnRows.length = 0
    this.rowMailIds.clear()
    this.onClaimAll = null
    this.onReadRequested = null
  }

  /**
   * 装载收件箱。
   *
   * @param now 服务端时刻（相对时间要它算，不用本地墙钟）
   */
  attach(resp: MailListResp, now: number): void {
    if (this.rowPool === null) {
      this.pendingList = resp
      this.pendingNow = now
      return
    }
    this.panelData = buildMailPanel(resp, now)
    this.render()
  }

  /**
   * 领取结果的一句话（由 {@link claimOutcomeText} 生成）。领完不重开面板，只在这一格里说明白。
   *
   * <p>附件名直接取自响应：服务端下发的每一条都带着解析好的名字，
   * 让调用方再拼一遍就等于多一处可能拼错的地方。
   */
  showOutcome(resp: MailClaimAllResp): void {
    if (this.outcomeLabel === null) {
      return
    }
    this.outcomeLabel.string = claimOutcomeText(resp.claimed,
      resp.rewards.map(reward => `${reward.name} ×${reward.count}`), resp.failed)
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
    this.headerLabel = this.addLabel(this.node, 'Header', 0, height / 2 - PADDING - 20,
      COLOR_COPPER_GOLD, 22)
    this.outcomeLabel = this.addLabel(this.node, 'Outcome', 0, height / 2 - PADDING - 46,
      COLOR_TEXT, 15)

    const button = new Node('ClaimAllButton')
    button.layer = this.node.layer
    this.node.addChild(button)
    button.setPosition(new Vec3(0, height / 2 - PADDING - HEADER_HEIGHT - BUTTON_HEIGHT / 2 - 8, 0))
    button.addComponent(UITransform).setContentSize(new Size(180, BUTTON_HEIGHT))
    const graphics = button.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 1
    graphics.roundRect(-90, -BUTTON_HEIGHT / 2, 180, BUTTON_HEIGHT, 6)
    graphics.fill()
    graphics.stroke()
    this.claimCaption = this.addLabel(button, 'Caption', 0, 0, COLOR_TEXT, 17)
    this.claimCaption.string = '一键领取'
    // 点击在 render() 里挂：判据要看当前这一版面板数据（有没有可领的），
    // 在搭建期挂会拿到一份还没有数据的 panelData
    this.claimButton = button

    const rowsTop = height / 2 - PADDING - HEADER_HEIGHT - BUTTON_HEIGHT - 16
    this.overflowLabel = this.addLabel(this.node, 'Overflow', 0,
      rowsTop - MAX_VISIBLE_ROWS * (ROW_HEIGHT + ROW_GAP) - 10, COLOR_TEXT_DIM, 14)
  }

  private createRow(): Node {
    const node = new Node('MailRow')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
    graphics.fill()

    // 四个槽位各自限宽（军队/背包同一口径：锚点定边 + `capWidth`）。
    // 不限宽时 Label 在 `overflow = NONE` 下把盒子撑成文本那么宽、又绕节点中心铺开 ——
    // 长标题会往左顶出卡片甚至顶出屏幕（台账 #394 目视 `label-fit-verify/mail.png` 抓到的那一行）。
    const title = this.addLabel(node, 'Title', -PANEL_WIDTH / 2 + PADDING, 16, COLOR_TEXT, 19)
    title.horizontalAlign = Label.HorizontalAlign.LEFT
    title.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    capWidth(title, LEFT_SLOT)
    const detail = this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING, -8, COLOR_TEXT_DIM, 14)
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    detail.node.getComponent(UITransform)?.setAnchorPoint(0, 0.5)
    capWidth(detail, LEFT_SLOT)
    const status = this.addLabel(node, 'Status', PANEL_WIDTH / 2 - PADDING, 12, COLOR_TEXT, 15)
    status.horizontalAlign = Label.HorizontalAlign.RIGHT
    status.node.getComponent(UITransform)?.setAnchorPoint(1, 0.5)
    capWidth(status, RIGHT_SLOT)
    const expiry = this.addLabel(node, 'Expiry', PANEL_WIDTH / 2 - PADDING, -14, COLOR_TEXT_DIM, 13)
    expiry.horizontalAlign = Label.HorizontalAlign.RIGHT
    expiry.node.getComponent(UITransform)?.setAnchorPoint(1, 0.5)
    capWidth(expiry, RIGHT_SLOT)
    return node
  }

  private addLabel(parent: Node, name: string, x: number, y: number, color: Color, fontSize: number): Label {
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
    const data = this.panelData
    const pool = this.rowPool
    const height = view.getVisibleSize().height
    if (data === null || pool === null) {
      return
    }
    if (this.claimButton !== null && this.claimCaption !== null) {
      // 没有可领的东西时按钮灰着且点不动：亮着却拿到一个空响应会让玩家以为卡了
      this.claimButton.off('touch-start')
      this.claimButton.on('touch-start', (_event: EventTouch) => {
        if (this.panelData?.claimAllEnabled === true) {
          this.onClaimAll?.()
        }
      })
      this.claimCaption.color = data.claimAllEnabled ? COLOR_TEXT : COLOR_TEXT_DIM
    }

    const rowsTop = height / 2 - PADDING - HEADER_HEIGHT - BUTTON_HEIGHT - 16 - ROW_HEIGHT / 2
    // 行数按实测可视高度算，不写死（与 QuestPanelView #389 同一处修法）：写死 7 行时第 5~7 行
    // 连同"另有 N 封未显示"一起落到导航条底下 —— 那是"画了但玩家看不见"，比少画一行更难发现。
    // 这一格原本量不到：dev 新号邮箱是空的，横扫只读到 2 颗 Label（台账 #394）。
    const navTop = -height / 2 + BOTTOM_RESERVED
    const maxRows = Math.max(1,
      Math.floor((rowsTop + ROW_HEIGHT / 2 - navTop) / (ROW_HEIGHT + ROW_GAP)))
    pool.releaseAll(this.drawnRows)
    this.drawnRows.length = 0
    const visible = data.rows.slice(0, maxRows)
    visible.forEach((row, index) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, rowsTop - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnRows.push(node)
      this.renderRow(node, row)
    })

    // 「另有几封没画下」挂在表头那一行（军队/任务同一口径）：单独占一行要么吃掉一排的位置，
    // 要么落到导航条底下 —— 两种都是"玩家看不见这条提示"。
    const truncated = truncatedNotice('封', data.rows.length - visible.length)
    if (this.headerLabel !== null) {
      this.headerLabel.string = truncated === ''
        ? data.headerText
        : `${data.headerText} · ${truncated}`
    }
    if (this.overflowLabel !== null) {
      this.overflowLabel.string = ''
    }
  }

  private renderRow(node: Node, row: MailRow): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      // 未读的亮、已读的暗：扫一眼就该看出哪几封没看过
      graphics.fillColor = row.unread ? COLOR_ROW : COLOR_ROW_DIM
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
      graphics.fill()
    }
    const title = node.children[0]?.getComponent(Label)
    const detail = node.children[1]?.getComponent(Label)
    const status = node.children[2]?.getComponent(Label)
    const expiry = node.children[3]?.getComponent(Label)
    if (title !== undefined && title !== null) {
      title.string = (row.unread ? '● ' : '') + row.title
      title.color = row.unread ? COLOR_TEXT : COLOR_TEXT_DIM
    }
    if (detail !== undefined && detail !== null) {
      detail.string = row.attachmentText === '' ? row.body : row.attachmentText
    }
    if (status !== undefined && status !== null) {
      status.string = row.statusText
      status.color = row.claimable ? COLOR_CLAIMABLE : COLOR_TEXT_DIM
    }
    if (expiry !== undefined && expiry !== null) {
      expiry.string = row.expiresIn
    }
    this.rowMailIds.set(node, row.mailId)
    node.off('touch-start')
    node.on('touch-start', (_event: EventTouch) => {
      if (row.unread) {
        this.onReadRequested?.(row.mailId)
      }
    })
  }
}
