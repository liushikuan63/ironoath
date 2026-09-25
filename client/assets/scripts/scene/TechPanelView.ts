/**
 * 职责：研究页的表现层（V03-a-S1 读侧）。
 * 依赖：cc（渲染）、game/tech/TechPanel（数据组装）。
 *
 * <p>铁律 2：本文件是纯表现层 —— 只读传入的视图，不发请求、不判"能不能研究"。
 * 「可研究 / 为什么不能」两列在编排层（{@code buildTechPanel}）就已经定好了，这里只画。
 *
 * <p><b>锚点显式给</b>：Label 的框按文字长度自己长，默认中心锚点会让左/右对齐的每一行
 * 随句子长短漂（收口清单 #199 与 #262 是同一个根因的两次），所以这里一律先 setAnchorPoint。
 *
 * <p>占位美术用 Graphics 画纯色块（与战力页同一套做法）：正式美术到位只换绘制部分。
 */
import { _decorator, Color, Component, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { techCancelText, techSpeedUpText } from '../game/tech/TechPanel'
import type { TechPanelView as TechViewData, TechRow } from '../game/tech/TechPanel'
import type { TechCancelResp, TechSpeedUpResp } from '../net/generated/TechProtocol'
import type { ResearchSpeedupChoice } from '../game/session/Choices'
import { ChoiceOverlay } from './ChoiceOverlay'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

const COLOR_SCRIM = new Color(0, 0, 0, 170)
const COLOR_BACKGROUND = new Color(24, 20, 18, 255)
const COLOR_ROW = new Color(40, 33, 28, 255)
const COLOR_ROW_ALT = new Color(48, 39, 33, 255)
const COLOR_ROW_LOCKED = new Color(32, 29, 26, 255)
const COLOR_BUTTON = new Color(62, 44, 26, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_HINT = new Color(120, 168, 196, 255)
const COLOR_GOOD = new Color(120, 176, 96, 255)

const CARD_WIDTH = 560
const CARD_HEIGHT = 600
/**
 * 行高按"11 项全部画得下"定的（实测：38 会挤掉最后一项，而这一页**没有滚动**，
 * 挤掉就等于那一项玩家永远够不到）。表里现在 11 行，再加行就得配翻页 —— 到那时改这里并让探针跟着改。
 */
const ROW_HEIGHT = 34
const GROUP_HEIGHT = 20
const PADDING = 16
/** 底部留给关闭按钮的高度：行画到这儿就停，剩下的如实报"还有几项"（不硬塞、不静默截断）。 */
const BOTTOM_RESERVED = 56

@ccclass('TechPanelView')
export class TechPanelView extends Component {

  private readonly rows: Node[] = []
  private viewData: TechViewData | null = null
  /** 玩家点了某一行的「研究」。发不发、能不能发由外层按服务端那份 `canResearch` 判 */
  onResearch: ((techId: string) => void) | null = null
  /** 玩家点了队列那一行的「取消研究」。返还多少由服务端算，本场景只把回执念出来 */
  onCancelResearch: (() => void) | null = null
  /** 玩家点了队列那一行的「加速」。用哪一张由外层筛（`effectKind`），本场景只回抛一个"我要加速" */
  onSpeedUpResearch: (() => void) | null = null

  /** 用哪一张研究加速。选项由外层按 `effectKind` 筛好，本面板只画与回抛整份选项。 */
  showSpeedupPicker(options: readonly ResearchSpeedupChoice[],
                    onPick: (choice: ResearchSpeedupChoice) => void): void {
    // 这一页是按需挂的（没有 onLoad 建节点那一步），所以弹层第一次要用时才建
    if (this.speedupPicker === null) {
      this.speedupPicker = new ChoiceOverlay(this.node, '用哪一张加速', 620)
    }
    const byId = new Map(options.map((option) => [option.id, option]))
    this.speedupPicker.show(options, (id) => {
      const choice = byId.get(id)
      if (choice !== undefined) {
        onPick(choice)
      }
    })
  }

  /** 一次加速的回执：减了多少、还剩多少、有没有因此完成。三个数都照服务端念。 */
  attachTechSpeedUp(resp: TechSpeedUpResp): void {
    this.receipt = techSpeedUpText(resp, this.nameOf(resp.techId))
    this.redraw()
  }

  private nameOf(techId: string): string {
    return this.viewData?.rows.find((row) => row.techId === techId)?.name ?? techId
  }
  /** 取消之后那句回执。占的是队列那一行的位置（取消完就没有在研项了），关掉这一页才清 */
  private receipt: string | null = null
  /** 「用哪一张加速」的弹层 */
  private speedupPicker: ChoiceOverlay | null = null

  /** 取消研究的回执。名字从当前那份列表里查（服务端只回 techId，中文名在行的 name 上）。 */
  attachTechCancelled(resp: TechCancelResp): void {
    this.receipt = techCancelText(this.nameOf(resp.techId), resp.refund)
    this.redraw()
  }

  /** 下发一份视图即显示。**每次都重画**：队列剩余时间会走，复用旧值会显示过期数字。 */
  render(view: TechViewData): void {
    this.viewData = view
    this.node.active = true
    this.redraw()
  }

  hide(): void {
    this.receipt = null
    this.node.active = false
  }

  private redraw(): void {
    this.clearRows()
    this.drawScrim()
    this.drawCard()

    let y = CARD_HEIGHT / 2 - PADDING - 10
    y = this.drawTitle(y)
    y = this.drawQueue(y)
    y = this.drawRows(y)
    this.drawClose()
  }

  /** 遮罩：这一页会盖在内城/地图/战斗回放几种完全不同的场景上，靠背景透出来读不清。 */
  private drawScrim(): void {
    const size = view.getVisibleSize()
    const node = new Node('scrim')
    this.node.addChild(node)
    node.addComponent(UITransform)
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_SCRIM
    graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
    graphics.fill()
    this.rows.push(node)
  }

  private drawCard(): void {
    const node = new Node('card')
    this.node.addChild(node)
    node.addComponent(UITransform)
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-CARD_WIDTH / 2, -CARD_HEIGHT / 2, CARD_WIDTH, CARD_HEIGHT)
    graphics.fill()
    this.rows.push(node)
  }

  private drawTitle(y: number): number {
    const view = this.viewData
    this.label('研 究', COLOR_COPPER_GOLD, 26, -CARD_WIDTH / 2 + PADDING, y - 12, 'left')
    if (view !== null && view.academyText !== '') {
      this.label(view.academyText, COLOR_TEXT_DIM, 18, CARD_WIDTH / 2 - PADDING, y - 12, 'right')
    }
    return y - 34
  }

  /** 队列行：正在研究哪一项、还剩多久。没有在研项时**不占这一行**（留白比一句"空闲"省地方）。 */
  private drawQueue(y: number): number {
    const text = this.viewData?.queueText ?? null
    if (text === null) {
      // 没有在研项时这一行留给"刚刚取消了什么、退回多少"—— 那是玩家按完最需要立刻看到的一句
      if (this.receipt !== null) {
        this.label(this.receipt, COLOR_GOOD, 16, -CARD_WIDTH / 2 + PADDING, y - 10, 'left')
        return y - 26
      }
      return y
    }
    this.label(text, COLOR_HINT, 18, -CARD_WIDTH / 2 + PADDING, y - 10, 'left')
    // 队列还在跑时回执画在它**下面一行** —— 抢掉队列那一行会让"还剩多久"看不见，
    // 而玩家刚用完一张加速，两件事都要看（清空只在 hide 时做）
    let consumed = 26
    if (this.receipt !== null) {
      this.label(this.receipt, COLOR_GOOD, 15, -CARD_WIDTH / 2 + PADDING, y - 30, 'left')
      // 多占一行：回执下面紧接着是学派分组标题，不挪的话两者叠在同一处（截图抓到）
      consumed = 46
    }
    // 「取消研究」左边再一颗「加速」：队列这一行说的两件事（反悔 / 提前）都该在这儿办完
    const speed = new Node('SpeedUpResearchButton')
    this.node.addChild(speed)
    speed.addComponent(UITransform).setContentSize(new Size(96, 26))
    speed.setPosition(new Vec3(CARD_WIDTH / 2 - PADDING - 152, y - 10, 0))
    const speedGraphics = speed.addComponent(Graphics)
    speedGraphics.fillColor = COLOR_BUTTON
    speedGraphics.rect(-48, -13, 96, 26)
    speedGraphics.fill()
    speed.on('touch-start', () => this.onSpeedUpResearch?.())
    this.rows.push(speed)
    this.label('加速', COLOR_TEXT, 14, CARD_WIDTH / 2 - PADDING - 152, y - 10, 'center')
    // 队列一占就再也动不了是这一页原来最大的坑（服务端有 `/tech/cancel`，客户端连方法都没有）。
    // 键放在队列那一行右侧：它取消的就是这一行说的那件事，位置要跟着那行出现与消失
    const button = new Node('CancelResearchButton')
    this.node.addChild(button)
    button.addComponent(UITransform).setContentSize(new Size(96, 26))
    button.setPosition(new Vec3(CARD_WIDTH / 2 - PADDING - 48, y - 10, 0))
    const graphics = button.addComponent(Graphics)
    graphics.fillColor = COLOR_BUTTON
    graphics.rect(-48, -13, 96, 26)
    graphics.fill()
    button.on('touch-start', () => this.onCancelResearch?.())
    this.rows.push(button)
    this.label('取消研究', COLOR_TEXT, 14, CARD_WIDTH / 2 - PADDING - 48, y - 10, 'center')
    return y - consumed
  }

  /**
   * 科技行。按学派的**顺序变化**插一条分组标题（不重排行 —— 顺序是服务端给的）。
   *
   * 放不下的行不画，但**如实说还有几项**：静默截断会让玩家以为科技树只有这么长。
   */
  private drawRows(startY: number): number {
    const view = this.viewData
    if (view === null || view.rows.length === 0) {
      this.label(view?.noticeText ?? '正在载入…', COLOR_HINT, 18, 0, startY - 12, 'center')
      return startY - 30
    }
    const floor = -CARD_HEIGHT / 2 + BOTTOM_RESERVED
    let y = startY
    let school = ''
    let drawn = 0
    for (const row of view.rows) {
      const needsGroup = row.schoolText !== school
      const height = ROW_HEIGHT + (needsGroup ? GROUP_HEIGHT : 0)
      if (y - height < floor) {
        break
      }
      if (needsGroup) {
        school = row.schoolText
        this.label(school, COLOR_COPPER_GOLD, 16, -CARD_WIDTH / 2 + PADDING, y - 8, 'left')
        y -= GROUP_HEIGHT
      }
      this.drawRow(row, y, drawn)
      y -= ROW_HEIGHT
      drawn += 1
    }
    const hidden = view.rows.length - drawn
    if (hidden > 0) {
      this.label(`还有 ${hidden} 项未显示（面板放不下，先研究前面的）`,
        COLOR_TEXT_DIM, 15, 0, floor + 14, 'center')
    }
    if (view.noticeText !== null && drawn > 0) {
      this.label(view.noticeText, COLOR_HINT, 15, -CARD_WIDTH / 2 + PADDING, floor + 14, 'left')
    }
    return y
  }

  private drawRow(row: TechRow, y: number, index: number): void {
    const usable = CARD_WIDTH - PADDING * 2
    const node = new Node(`tech-${row.techId}`)
    this.node.addChild(node)
    const transform = node.addComponent(UITransform)
    transform.setContentSize(usable, ROW_HEIGHT - 4)
    node.setPosition(new Vec3(0, y, 0))

    const graphics = node.addComponent(Graphics)
    graphics.fillColor = row.canResearch ? (index % 2 === 0 ? COLOR_ROW : COLOR_ROW_ALT) : COLOR_ROW_LOCKED
    graphics.rect(-usable / 2, -ROW_HEIGHT + 2, usable, ROW_HEIGHT - 4)
    graphics.fill()
    this.rows.push(node)

    const left = -usable / 2 + 10
    const right = usable / 2 - 10
    // 可研究那一行的成本要让开键位（键占右边 76 宽）：写在同一头会把"木材 600"压掉半截
    const costRight = row.canResearch ? right - 84 : right
    this.label(row.name, row.canResearch ? COLOR_TEXT : COLOR_TEXT_DIM, 18, left, y - 10, 'left')
    this.label(row.levelText, COLOR_TEXT_DIM, 14, left + 150, y - 10, 'left')
    this.label(row.costText, COLOR_TEXT_DIM, 14, costRight, y - 10, 'right')
    const second = row.effectText === null
      ? (row.timeText === null ? '' : `耗时 ${row.timeText}`)
      : `${row.effectText}${row.timeText === null ? '' : ` · 耗时 ${row.timeText}`}`
    this.label(second, COLOR_TEXT_DIM, 14, left, y - 25, 'left')
    if (row.canResearch) {
      // 「可研究」以前只是一句字 —— 玩家看得见这一行能做，但点下去什么都没有发生。
      // 现在它是一颗键，按下去发 POST /tech/research
      this.drawResearch(row.techId, right, y - 10)
    } else if (row.reasonText !== null) {
      this.label(row.reasonText, COLOR_TEXT_DIM, 15, right, y - 25, 'right')
    }
  }

  /** 行上的「研究」键。命名带 techId，运行时探针才按得到具体那一行。 */
  private drawResearch(techId: string, right: number, y: number): void {
    const node = new Node(`research-${techId}`)
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(76, 24)
    node.setPosition(new Vec3(right - 38, y, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BUTTON
    graphics.rect(-38, -12, 76, 24)
    graphics.fill()
    node.on('touch-start', () => this.onResearch?.(techId))
    this.rows.push(node)
    this.label('研究', COLOR_COPPER_GOLD, 15, right - 38, y, 'center')
  }

  private drawClose(): void {
    const bottom = -CARD_HEIGHT / 2 + 28
    const node = new Node('close')
    this.node.addChild(node)
    const transform = node.addComponent(UITransform)
    transform.setContentSize(160, 36)
    node.setPosition(new Vec3(0, bottom, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_BUTTON
    graphics.rect(-80, -18, 160, 36)
    graphics.fill()
    node.on('touch-start', () => this.hide())
    this.rows.push(node)
    this.label('关闭', COLOR_COPPER_GOLD, 18, 0, bottom, 'center')
  }

  /** 造一个左右对齐靠得住的 Label：**锚点先按对齐方式定**，再摆位置。 */
  private label(text: string, color: Color, size: number, x: number, y: number,
    align: 'left' | 'right' | 'center'): Label {
    const node = new Node('label')
    this.node.addChild(node)
    node.addComponent(UITransform).setAnchorPoint(
      align === 'left' ? 0 : align === 'right' ? 1 : 0.5, 0.5)
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = text
    label.color = color
    label.fontSize = size
    label.lineHeight = size + 6
    label.horizontalAlign = align === 'left'
      ? Label.HorizontalAlign.LEFT
      : align === 'right' ? Label.HorizontalAlign.RIGHT : Label.HorizontalAlign.CENTER
    node.setPosition(new Vec3(x, y, 0))
    this.rows.push(node)
    return label
  }

  private clearRows(): void {
    for (const row of this.rows) {
      row.destroy()
    }
    this.rows.length = 0
  }

  override onDestroy(): void {
    this.clearRows()
    this.onResearch = null
    this.onCancelResearch = null
    this.onSpeedUpResearch = null
  }
}
