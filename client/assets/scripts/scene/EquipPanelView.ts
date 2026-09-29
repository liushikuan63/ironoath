/**
 * 职责：装备实例页的表现层（V03-b-S1 读侧）。
 * 依赖：cc（渲染）、game/equip/EquipPanel（数据组装）。
 *
 * <p>铁律 2：本文件是纯表现层 —— 只读传入的视图，不发请求、不判"能不能强化"。
 *
 * <p><b>锚点显式给</b>：Label 的框按文字长度自己长，默认中心锚点会让左右对齐的每一行随句子长短漂
 * （收口清单 #199 与 #262 是同一个根因的两次），所以这里一律先 `setAnchorPoint` 再摆位置。
 * 行逐条 `setPosition`（池化行叠在 y=0 那次事故的教训）；放不下时如实说还有几件，不静默截断。
 */
import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3, view } from 'cc'
import type { EquipPanelView as EquipViewData, EquipRow } from '../game/equip/EquipPanel'
import type { EquipSlot } from '../net/generated/EquipProtocol'
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
const COLOR_GOOD = new Color(120, 176, 96, 255)
const COLOR_HINT = new Color(120, 168, 196, 255)

const CARD_WIDTH = 620
const CARD_HEIGHT = 600
const ROW_HEIGHT = 46
const PADDING = 16
/** 行右端的「强化」键区宽度：行的触摸命中区到它左沿为止（几何分离的理由写在 `drawRow` 里）。 */
const FORGE_ZONE = 104
const BOTTOM_RESERVED = 56

@ccclass('EquipPanelView')
export class EquipPanelView extends Component {

  private readonly rows: Node[] = []
  private viewData: EquipViewData | null = null

  /**
   * 行内动作回调：`takeOff=false` 表示穿上这一件，`true` 表示卸下该槽位。
   * 表现层只喊一声，请求由编排层发（铁律 2）。
   */
  onRowAction: ((uid: string, slot: EquipSlot, takeOff: boolean) => void) | null = null

  /**
   * 强化某一件（V11）。表现层只喊一声，请求由编排层发（铁律 2）。
   *
   * <p>能不能强化**不在这里判**：`row.canForge` 与 `row.reasonText` 都是服务端
   * `canForge`/`blockReason` 的同一次计算，客户端只负责画成亮键还是灰键。
   */
  onForge: ((uid: string) => void) | null = null

  /** 下发一份视图即显示。**每次都重画**：穿戴与强化都会改这里的数据。 */
  render(view: EquipViewData): void {
    this.viewData = view
    this.node.active = true
    this.redraw()
  }

  hide(): void {
    this.node.active = false
  }

  private redraw(): void {
    this.clearRows()
    this.drawScrim()
    this.drawCard()
    let y = CARD_HEIGHT / 2 - PADDING - 10
    y = this.drawTitle(y)
    this.drawRows(y)
    this.drawClose()
  }

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
    this.label('装 备', COLOR_COPPER_GOLD, 26, -CARD_WIDTH / 2 + PADDING, y - 12, 'left')
    const view = this.viewData
    if (view?.targetText !== null && view?.targetText !== undefined) {
      // 换装模式：右边写"给谁换"，汇总行另起一行（这两件事都要看得见，谁都不该把谁顶掉）
      this.label(view.targetText, COLOR_TEXT, 18, CARD_WIDTH / 2 - PADDING, y - 12, 'right')
      this.label(view.summaryText, COLOR_TEXT_DIM, 15, -CARD_WIDTH / 2 + PADDING, y - 34, 'left')
      return y - 52
    }
    const summary = view?.summaryText ?? ''
    if (summary !== '') {
      this.label(summary, COLOR_TEXT_DIM, 18, CARD_WIDTH / 2 - PADDING, y - 12, 'right')
    }
    return y - 40
  }

  /**
   * 一件一行。**顺序照抄服务端**（不按穿没穿重排：重排是用客户端口径覆盖服务端的编排，
   * 与科技树/榜单同一条纪律）；放不下的不画，但如实说还有几件。
   */
  private drawRows(startY: number): void {
    const view = this.viewData
    if (view === null || view.rows.length === 0) {
      this.label(view?.noticeText ?? '正在载入…', COLOR_HINT, 18, 0, startY - 12, 'center')
      return
    }
    const floor = -CARD_HEIGHT / 2 + BOTTOM_RESERVED
    let y = startY
    let drawn = 0
    for (const row of view.rows) {
      if (y - ROW_HEIGHT < floor) {
        break
      }
      this.drawRow(row, y, drawn)
      y -= ROW_HEIGHT
      drawn += 1
    }
    const hidden = view.rows.length - drawn
    if (hidden > 0) {
      this.label(`还有 ${hidden} 件未显示（面板放不下）`, COLOR_TEXT_DIM, 15, 0, floor + 14, 'center')
    }
    if (view.noticeText !== null) {
      this.label(view.noticeText, COLOR_HINT, 15, -CARD_WIDTH / 2 + PADDING, floor + 14, 'left')
    }
  }

  private drawRow(row: EquipRow, y: number, index: number): void {
    const usable = CARD_WIDTH - PADDING * 2
    // **命中区与「强化」键区几何分开**：键是兄弟节点，而 Cocos 的触摸会派发给所有命中的节点
    // （引擎声明里没有 `propagationStopped` 可用来阻断，本轮已核 `client/temp/declarations/cc.d.ts` 零命中），
    // 所以行的命中区必须真的不覆盖键区。渲染面仍是整行：锚点挪到左端 + 位置左移，让
    // "底板照画满整行"与"命中区只到键区左沿"同时成立。
    const node = new Node(`equip-${row.uid}`)
    this.node.addChild(node)
    const transform = node.addComponent(UITransform)
    transform.setAnchorPoint(0, 0.5)
    transform.setContentSize(usable - FORGE_ZONE, ROW_HEIGHT - 4)
    node.setPosition(new Vec3(-usable / 2, y, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = row.canForge ? (index % 2 === 0 ? COLOR_ROW : COLOR_ROW_ALT) : COLOR_ROW_LOCKED
    graphics.rect(0, -ROW_HEIGHT + 2, usable, ROW_HEIGHT - 4)
    graphics.fill()
    this.rows.push(node)

    const left = -usable / 2 + 10
    // 右列文字整体让开键区：**第二行的费用也要让** —— 它在键下沿附近，最右对齐时会顶到键上
    // （首跑截图抓到的：读数全绿，而"强化消耗 铁矿 120"被键压掉一半）
    const right = usable / 2 - 10 - FORGE_ZONE
    this.label(`${row.name}  ${row.rarityText}`, row.canForge ? COLOR_TEXT : COLOR_TEXT_DIM,
      18, left, y - 12, 'left')
    this.label(`${row.slotText} · ${row.forgeText}`, COLOR_TEXT_DIM, 14, left + 210, y - 12, 'left')
    this.label(row.statsText, COLOR_TEXT_DIM, 14, left, y - 31, 'left')
    // 右列第一行：有换装动作时先给动作（那是玩家进来要干的事），否则给强化状态
    if (row.actionText !== null) {
      this.label(row.actionText, COLOR_COPPER_GOLD, 17, right, y - 12, 'right')
    } else {
      const tail = row.canForge ? '可强化' : (row.reasonText ?? '')
      if (tail !== '') {
        this.label(tail, row.canForge ? COLOR_GOOD : COLOR_TEXT_DIM, 15, right, y - 12, 'right')
      }
    }
    const cost = row.worn ? `${row.wornText}${row.costText === null ? '' : ` · ${row.costText}`}`
      : (row.costText ?? row.wornText)
    this.label(cost, COLOR_TEXT_DIM, 14, right, y - 31, 'right')
    // 强化键与"能不能装备"是两件事：前者看这一件自己的 canForge，后者看有没有选武将
    this.forgeButton(row, y, usable / 2 - FORGE_ZONE / 2)

    // 只有**给得出动作**的行才吃触摸：没有动作的行不该长得像能点（点了没反应比不给按钮更糟）
    if (row.actionText !== null) {
      const takeOff = row.actionText === '卸下'
      node.on('touch-start', () => this.onRowAction?.(row.uid, row.slot, takeOff))
    }
  }

  /**
   * 「强化」键（V11）。
   *
   * <p><b>灰键不吃触摸</b>（与招募面板同一条纪律）：点了没反应比不给按钮更糟。
   * 灰的原因不去这里复述 —— 右侧那一行本来就写着 `row.reasonText`（"已满级" / "铁不够"），
   * 在按钮上再写一遍就是同一句话的两个产地。
   */
  private forgeButton(row: EquipRow, y: number, x: number): void {
    const width = FORGE_ZONE - 10
    const height = ROW_HEIGHT - 12
    const node = new Node(`forge-${row.uid}`)
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(width, height)
    node.setPosition(new Vec3(x, y, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = row.canForge ? COLOR_ROW_ALT : COLOR_ROW_LOCKED
    graphics.roundRect(-width / 2, -height / 2, width, height, 6)
    graphics.fill()
    graphics.strokeColor = row.canForge ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
    graphics.lineWidth = 1
    graphics.roundRect(-width / 2, -height / 2, width, height, 6)
    graphics.stroke()
    if (row.canForge) {
      node.on('touch-start', () => this.onForge?.(row.uid))
    }
    this.rows.push(node)
    this.label('强化', row.canForge ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM, 16, x, y, 'center')
  }

  private drawClose(): void {
    const bottom = -CARD_HEIGHT / 2 + 28
    const node = new Node('close')
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(160, 36)
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
  }
}
