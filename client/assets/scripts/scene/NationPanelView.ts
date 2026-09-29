/**
 * 职责：国家面板（B13 · V13-S1）—— 「我现在有没有国家、能做什么、国库这本账怎么样」。
 * 依赖：cc（渲染）、game/nation/NationPanel（数据组装，已单测）、scene/UiFont。
 *
 * <p>铁律 2：本文件只读传入的视图、点击只喊一声。灰不灰、为什么灰、显示几个字全在
 * `game/nation/NationPanel.ts` 里，这里不重算一遍。
 *
 * <p><b>遮罩要吞掉自己的触摸</b>：整屏已经压暗了，点暗处却穿透打到底下的联盟成员行上，
 * 玩家会在一个"看不见"的面板上退出联盟（#403 那一族）。
 *
 * <p><b>三处不许自己算</b>：
 * ① 「能不能花国库」是服务端 `myOffice` 的结论（客户端没有 role_permission 表）；
 * ② 余额/上限/流水条数都照服务端下发的数念；
 * ③ 支给谁与谁做的走 `payeeLabel` / `operatorLabel` 换过的词 —— 这里**永远不印 id**。
 */
import { _decorator, Color, Component, EditBox, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import type {
  NationPanelView as NationPanelData, PayeeType, SpendDraft,
} from '../game/nation/NationPanel'
import { SPEND_AMOUNT_PRESETS, amountText, spendDraftBlocker, spendSinkOptions } from '../game/nation/NationPanel'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

const COLOR_MASK = new Color(0, 0, 0, 190)
const COLOR_CARD = new Color(28, 23, 20, 255)
const COLOR_ROW = new Color(40, 33, 28, 255)
const COLOR_FIELD = new Color(22, 18, 15, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_DIM = new Color(150, 140, 124, 255)
const COLOR_GOLD = new Color(184, 134, 11, 255)
const COLOR_HINT = new Color(120, 168, 196, 255)
const COLOR_WARN = new Color(198, 96, 72, 255)

const PANEL_WIDTH = 720
const ROW_HEIGHT = 34
const BUTTON_HEIGHT = 30
const PADDING = 16
/** 顶部留给 HUD 顶栏的高度（与抽卡记录层同一份口径）。 */
const TOP_RESERVE = 24
const TITLE_BAND = 66
const SUMMARY_LINE = 22
const FIELD_HEIGHT = 30
/** 输入框字符上限。**必须显式设置**（`EditBox` 默认 20）：国名走服务端敏感词与长度校验，24 字够用。 */
const NAME_MAX_LENGTH = 12
const REASON_MAX_LENGTH = 24
/** 输入框默认宽度。**显式给每一处**，而不是共用一个：国名 / 坐标 / 用途的合理长度不同。 */
const DEFAULT_FIELD_WIDTH = 150
/**
 * 标签列宽：所有输入框的左沿都排在 `left + LABEL_COL` 之后。
 *
 * <p>不这么排的话，标签会正好压在输入框的左沿上（第一轮截图：「国名」两个字骑在框边上）。
 * 标签最长的是「都城坐标」四个 13px 字，约 56px，留 100px 是宽裕的。
 */
const LABEL_COL = 100
/** 支出表单打开时流水只留这么几条：一屏装不下全部 + 表单（截掉几条由数据层报出来）。 */
const LOG_ROWS_WHEN_ARMED = 2

/** 临时的输入态。**不属于存档**：`beginSession` 每次打开都重置，免得"关掉再开还留着上次的字"。 */
type DraftField = 'nationName' | 'capitalX' | 'capitalY' | 'reason'

interface PanelDraft {
  nationName: string
  capitalX: string
  capitalY: string
  armed: boolean
  amount: number
  reason: string
  payeeType: PayeeType
  payeeId: string | null
  sink: string | null
}

function freshDraft(): PanelDraft {
  return {
    nationName: '', capitalX: '', capitalY: '',
    armed: false, amount: SPEND_AMOUNT_PRESETS[0] ?? 0, reason: '',
    payeeType: 'SINK', payeeId: null, sink: 'NATIONAL_TECH',
  }
}

/** 坐标只接受整数（协议是 int64 格子号）。小数、空串、带字的一律算"没填"。 */
function parseCoord(text: string): number | null {
  const trimmed = text.trim()
  if (!/^\d+$/.test(trimmed)) {
    return null
  }
  const value = Number(trimmed)
  return Number.isSafeInteger(value) ? value : null
}

@ccclass('NationPanelView')
export class NationPanelView extends Component {
  private data: NationPanelData | null = null
  private pending: NationPanelData | null = null
  private readonly nodes: Node[] = []
  private built = false
  private draft: PanelDraft = freshDraft()
  /**
   * **输入框不进 `nodes`**：红一画就把全部子节点毁掉重建，而正在输入的那个 `EditBox`
   * 被销毁 = 键盘焦点当场丢一个字（`SocialCreateOverlay` 头注里记过这个坑）。
   * 所以输入框按名字缓存，重画时只挪位置、不重建。
   */
  private readonly inputs = new Map<string, Node>()
  /** 输入框名字 → 它写进草稿的哪个字段（回调按名字查，不闭包绑死旧草稿）。 */
  private readonly inputFields = new Map<string, DraftField>()
  /** 可选的收款人（id → 名字）。**id 只用于发请求，永不上屏**。 */
  private payees: { readonly id: string; readonly name: string }[] = []

  onClose: (() => void) | null = null
  onFound: ((name: string, capitalX: number, capitalY: number) => void) | null = null
  onJoin: ((nationId: string) => void) | null = null
  onLeave: (() => void) | null = null
  onDisband: (() => void) | null = null
  onSpend: ((draft: SpendDraft) => void) | null = null
  /** 收款人名单（联盟成员：只有他们能作为「发给谁」的目标）。 */
  onRequestPayees: (() => void) | null = null

  override onLoad(): void {
    this.buildMask()
    this.buildCard()
    this.built = true
    if (this.pending !== null) {
      const pending = this.pending
      this.pending = null
      this.attach(pending)
    }
  }

  override onDestroy(): void {
    this.built = false
    this.nodes.length = 0
    this.inputs.clear()
    this.onClose = null
    this.onFound = null
    this.onJoin = null
    this.onLeave = null
    this.onDisband = null
    this.onSpend = null
    this.onRequestPayees = null
  }

  /** 每次打开都重置输入态（"关掉再开还留着上次的字"是本仓记过的形态）。 */
  beginSession(): void {
    this.draft = freshDraft()
    this.payees = []
  }

  /** 递一份可收款名单（由编排层在拉完联盟成员后调用）。 */
  attachPayees(payees: readonly { id: string; name: string }[]): void {
    this.payees = [...payees]
    this.redraw()
  }

  /** 装载整块视图（由 `AppRoot` 下发，每次操作后都重发一份完整的）。 */
  attach(data: NationPanelData): void {
    this.data = data
    if (!this.built || !this.isValid) {
      this.pending = data
      return
    }
    this.redraw()
  }

  private buildMask(): void {
    const size = view.getVisibleSize()
    const node = new Node('NationMask')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(size.width, size.height))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_MASK
    graphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
    graphics.fill()
    // 吞掉自己的触摸：这一屏是模态的，暗处不该穿透到联盟页的成员行上
    node.on('touch-start', (_event: EventTouch) => { /* 刻意什么都不做 */ }, this)
  }

  /**
   * 卡片**只建一次**，不跟 `redraw` 一起重建。
   *
   * <p>原因不是性能，是层级：输入框按名字缓存（`this.inputs`，重画时不重建），
   * 而卡片如果每次重画都新建，它就会变成**输入框之后添加的兄弟节点** ——
   * 于是第二帧起卡片压住输入框，三个输入框凭空消失（第一轮截图里真的一样：只剩标签没有框，
   * 而当时探针 68 条全绿）。把卡片挪到最前面建一次，输入框的层级就永远在它之上。
   */
  private buildCard(): void {
    const size = view.getVisibleSize()
    const cardHeight = size.height - TOP_RESERVE * 2
    const node = new Node('NationCard')
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, cardHeight))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_CARD
    graphics.rect(-PANEL_WIDTH / 2, -cardHeight / 2, PANEL_WIDTH, cardHeight)
    graphics.fill()
  }

  private redraw(): void {
    for (const node of this.nodes) {
      node.destroy()
    }
    this.nodes.length = 0
    const data = this.data
    if (data === null) {
      return
    }
    const size = view.getVisibleSize()
    const top = size.height / 2 - TOP_RESERVE
    const left = -PANEL_WIDTH / 2 + PADDING
    const innerWidth = PANEL_WIDTH - PADDING * 2

    this.label(data.title, COLOR_GOLD, 22, left, top - 18, 'left')
    this.button('CloseButton', '关闭', PANEL_WIDTH / 2 - PADDING - 38, top - 18, 76, true,
      () => this.onClose?.())
    this.label(data.headline, COLOR_HINT, 14, left, top - 44, 'left')

    let cursor = top - TITLE_BAND
    if (data.mode === 'NONE') {
      cursor = this.drawFoundForm(left, cursor)
      cursor = this.drawCandidates(left, innerWidth, cursor, data)
    } else {
      // 支出表单开着的时候**收起概况那六行**：一屏 600 逻辑高塞不下「概况 + 国库 + 操作 + 表单」，
      // 而表单开着时玩家要的正是"我这笔钱花给谁"，概况里那几行此时是重复信息（标题与身份那句仍在）。
      if (!this.draft.armed) {
        cursor = this.drawSummary(left, cursor, data)
      }
      cursor = this.drawTreasury(left, innerWidth, cursor, data)
      cursor = this.drawActions(left, cursor, data)
    }
    if (data.notice !== null) {
      this.label(data.notice, COLOR_WARN, 14, left, cursor - 12, 'left')
    }
  }

  // ---------- 无国家：创建 + 可加入列表 ----------

  private drawFoundForm(left: number, top: number): number {
    const draft = this.draft
    let y = top - 18
    this.label('国名', COLOR_DIM, 13, left, y, 'left')
    this.input('NationNameInput', 'nationName', left + LABEL_COL + DEFAULT_FIELD_WIDTH / 2, y,
      draft.nationName, DEFAULT_FIELD_WIDTH, NAME_MAX_LENGTH)
    y -= 34
    this.label('都城坐标', COLOR_DIM, 13, left, y, 'left')
    this.input('CapitalXInput', 'capitalX', left + LABEL_COL + 55, y, draft.capitalX, 110, 6)
    this.input('CapitalYInput', 'capitalY', left + LABEL_COL + 8 + 110 + 55, y, draft.capitalY, 110, 6)
    y -= 34
    const name = draft.nationName.trim()
    const x = parseCoord(draft.capitalX)
    const yCoord = parseCoord(draft.capitalY)
    // 只卡"填没填 / 是不是整数"这种协议字段本身；建国前置（等级、开服天数、有没有联盟）全在服务端
    const blocker = name === ''
      ? '先给国名'
      : (x === null || yCoord === null ? '都城坐标要填两个非负整数' : null)
    const width = 168
    this.button('FoundButton', '创建国家', left + width / 2, y, width, blocker === null,
      () => {
        if (blocker !== null || x === null || yCoord === null) {
          return
        }
        this.onFound?.(name, x, yCoord)
      })
    if (blocker !== null) {
      this.label(blocker, COLOR_DIM, 13, left + width + 12, y, 'left')
    } else {
      this.label('条件没满足时，界面会写明缺哪一条', COLOR_DIM, 13, left + width + 12, y, 'left')
    }
    return y - 26
  }

  private drawCandidates(left: number, innerWidth: number, top: number, data: NationPanelData): number {
    let y = top
    if (data.candidatesNotice !== null) {
      this.label(data.candidatesNotice, COLOR_DIM, 13, left, y, 'left')
      y -= 24
    }
    for (const candidate of data.candidates.slice(0, 6)) {
      this.label(candidate.name, COLOR_TEXT, 16, left + 6, y, 'left')
      this.button(`Join-${candidate.key}`, candidate.actionText, left + innerWidth - 70, y, 130,
        candidate.enabled, () => this.onJoin?.(candidate.key))
      y -= ROW_HEIGHT
    }
    if (data.candidates.length > 6) {
      this.label(`另有 ${data.candidates.length - 6} 个国家未显示`, COLOR_DIM, 13, left, y, 'left')
      y -= 24
    }
    return y
  }

  // ---------- 有国家：概况 + 国库 + 操作 ----------

  private drawSummary(left: number, top: number, data: NationPanelData): number {
    let y = top - 10
    for (const line of data.summary) {
      this.label(line.text, COLOR_TEXT, 15, left, y, 'left')
      y -= SUMMARY_LINE
    }
    return y - 6
  }

  private drawTreasury(left: number, innerWidth: number, top: number, data: NationPanelData): number {
    const box = data.treasury
    if (box === null) {
      this.label('国库这一次没读到（与"账上没有流水"是两回事）', COLOR_WARN, 14, left, top - 10, 'left')
      return top - 34
    }
    let y = top
    this.label(`国库余额 ${box.balanceText} / ${box.capText}`, COLOR_GOLD, 16, left, y, 'left')
    y -= 26
    const visible = this.draft.armed ? Math.min(box.logs.length, LOG_ROWS_WHEN_ARMED) : box.logs.length
    if (box.emptyText !== null) {
      this.label(box.emptyText, COLOR_DIM, 14, left, y, 'left')
      y -= 30
    }
    for (let index = 0; index < visible; index += 1) {
      const row = box.logs[index]
      if (row === undefined) {
        break
      }
      const width = innerWidth
      const plate = this.surface(`LogRow-${row.key}`, 0, y - ROW_HEIGHT / 2 + 2, width, ROW_HEIGHT - 6)
      plate.fillColor = COLOR_ROW
      plate.rect(-width / 2, -(ROW_HEIGHT - 6) / 2, width, ROW_HEIGHT - 6)
      plate.fill()
      this.label(row.headText, COLOR_TEXT, 15, left + 10, y - 6, 'left')
      this.label(row.balanceText, COLOR_GOLD, 14, left + width - 10, y - 6, 'right')
      this.label(row.detailText, COLOR_DIM, 12, left + 10, y - 22, 'left')
      y -= ROW_HEIGHT
    }
    // 截断只说一次，并说清"为什么只剩这些"（两条提示叠着印会互相解释不清）
    const trimmed = this.draft.armed ? box.logs.length - visible : 0
    if (box.hiddenCount > 0) {
      const why = trimmed > 0 ? `（支出表单开着，只留最近 ${visible} 条）` : ''
      this.label(`另有 ${box.hiddenCount + trimmed} 条更早的流水未显示${why}`, COLOR_DIM, 13, left, y, 'left')
      y -= 22
    }
    return y
  }

  private drawActions(left: number, top: number, data: NationPanelData): number {
    const draft = this.draft
    const width = 128
    const gap = 10
    const y = top - 18
    this.button('LeaveButton', data.leave.text, left + width / 2, y, width, data.leave.enabled,
      () => this.onLeave?.())
    this.button('DisbandButton', data.disband.text, left + width + gap + width / 2, y, width, data.disband.enabled,
      () => this.onDisband?.())
    this.button('SpendButton', data.spend.text, left + (width + gap) * 2 + width / 2, y, width, data.spend.enabled,
      () => {
        // 灰态（没有官职）与"国库这一次没读到"都不许开表单：前者是权限，
        // 后者连余额与上限都还没拿到 —— 在这两种情况下画出表单只会让玩家填一堆发不出去的东西
        if (!data.spend.enabled || data.treasury === null) {
          return
        }
        draft.armed = !draft.armed
        this.redraw()
      })
    let cursor = y - 26
    // 灰掉的每一颗都要写明为什么（验收 ③）。**逐条印，不合并**：三颗键可能同时灰着
    // （不是国王 + 没有官职），只印第一条会让玩家把 A 的原因安到 B 头上
    for (const action of [data.leave, data.disband, data.spend]) {
      if (action.reason !== null) {
        this.label(action.reason, COLOR_DIM, 13, left, cursor, 'left')
        cursor -= 20
      }
    }
    if (data.treasury === null) {
      return cursor
    }
    if (draft.armed) {
      cursor = this.drawSpendForm(left, cursor)
    }
    return cursor
  }

  private drawSpendForm(left: number, top: number): number {
    const draft = this.draft
    let y = top
    const presetWidth = 96
    SPEND_AMOUNT_PRESETS.forEach((preset, index) => {
      const x = left + presetWidth / 2 + index * (presetWidth + 8)
      this.button(`Amount-${preset}`, amountText(preset), x, y, presetWidth, draft.amount === preset,
        () => { draft.amount = preset; this.redraw() })
    })
    y -= 32
    const sinkWidth = 108
    this.button('PayeePlayer', '发给成员', left + sinkWidth / 2, y, sinkWidth, draft.payeeType === 'PLAYER',
      () => {
        draft.payeeType = 'PLAYER'
        if (this.payees.length === 0) {
          this.onRequestPayees?.()
        }
        this.redraw()
      })
    spendSinkOptions().forEach((option, index) => {
      const x = left + sinkWidth + 8 + index * (sinkWidth + 8) + sinkWidth / 2
      this.button(`Sink-${option.key}`, option.label, x, y, sinkWidth, draft.sink === option.key,
        () => {
          draft.payeeType = 'SINK'
          draft.sink = option.key
          this.redraw()
        })
    })
    y -= 32
    if (draft.payeeType === 'PLAYER') {
      if (this.payees.length === 0) {
        this.label('还没有可选的成员（国库只能发给本盟成员）', COLOR_DIM, 13, left, y, 'left')
        y -= 22
      }
      this.payees.slice(0, 4).forEach((payee, index) => {
        const x = left + sinkWidth / 2 + index * (sinkWidth + 8)
        this.button(`Payee-${payee.id}`, payee.name, x, y, sinkWidth, draft.payeeId === payee.id,
          () => { draft.payeeId = payee.id; this.redraw() })
      })
      y -= 32
    }
    this.label('用途', COLOR_DIM, 13, left, y, 'left')
    this.input('SpendReasonInput', 'reason', left + LABEL_COL + 130, y, draft.reason, 260, REASON_MAX_LENGTH)
    y -= 34
    const blocker = spendDraftBlocker({
      amount: draft.amount, reason: draft.reason, payeeType: draft.payeeType,
      payeeId: draft.payeeId, sink: draft.payeeType === 'SINK' ? draft.sink : null,
    })
    const width = 140
    this.button('SpendConfirm', '确认支出', left + width / 2, y, width, blocker === null,
      () => {
        if (blocker !== null) {
          return
        }
        this.onSpend?.({
          amount: draft.amount,
          reason: draft.reason.trim(),
          payeeType: draft.payeeType,
          payeeId: draft.payeeType === 'PLAYER' ? draft.payeeId : null,
          sink: draft.payeeType === 'SINK' ? draft.sink : null,
        })
      })
    this.button('SpendCancel', '取消', left + width + 10 + 60, y, 110, true,
      () => { draft.armed = false; this.redraw() })
    y -= 26
    // 那句「还差什么」放**键下面独占一行**：与键同一行时它会顶出卡片右沿
    // （第一轮截图里就是这样 —— 探针当时全绿，是目视抓到的）
    if (blocker !== null) {
      this.label(blocker, COLOR_DIM, 13, left, y, 'left')
      y -= 20
    }
    return y
  }

  // ---------- 基础件 ----------

  /** 按钮：灰掉时**不吃触摸**（点了也不会发请求），与招募面板同一份做法。 */
  private button(name: string, text: string, x: number, y: number, width: number, enabled: boolean,
    onClick: () => void): void {
    const graphics = this.surface(name, x, y, width, BUTTON_HEIGHT)
    graphics.fillColor = enabled ? COLOR_ROW : COLOR_FIELD
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 6)
    graphics.fill()
    graphics.strokeColor = enabled ? COLOR_GOLD : COLOR_DIM
    graphics.lineWidth = 1
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 6)
    graphics.stroke()
    if (enabled) {
      graphics.node.on('touch-start', onClick)
    }
    this.label(text, enabled ? COLOR_GOLD : COLOR_DIM, 15, x, y, 'center')
  }

  /**
   * 一个单行输入框。**同一名字只造一次**，之后每次重画只挪位置与回显 ——
   * 正在输入的那一个必须活着，否则每敲一个字焦点就没了。
   *
   * @param field 写进草稿的字段名。回显只在 `box.string` 与目标值不同时才赋值 ——
   *        赋值会把光标弹回末尾，于是"从中间改一个字"会被顶到后面。
   */
  private input(name: string, field: DraftField, x: number, y: number, text: string, width: number,
    maxLength: number): void {
    let node = this.inputs.get(name)
    if (node === undefined) {
      node = new Node(name)
      node.layer = this.node.layer
      this.node.addChild(node)
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = COLOR_FIELD
      graphics.roundRect(-width / 2, -FIELD_HEIGHT / 2, width, FIELD_HEIGHT, 5)
      graphics.fill()
      graphics.strokeColor = COLOR_GOLD
      graphics.lineWidth = 1
      graphics.roundRect(-width / 2, -FIELD_HEIGHT / 2, width, FIELD_HEIGHT, 5)
      graphics.stroke()
      const box = node.addComponent(EditBox)
      box.inputMode = EditBox.InputMode.SINGLE_LINE
      box.maxLength = maxLength
      // 占位期清空：EditBox 自己会造一个 placeholder Label，默认串是 "label" ——
      // 三个输入框就会在屏上并排印出三个 "label"（首跑截图里真的出现了）
      box.placeholder = ''
      if (box.textLabel !== null) {
        applySystemUiFont(box.textLabel)
        box.textLabel.fontSize = 15
        box.textLabel.color = COLOR_TEXT
        box.textLabel.overflow = Label.Overflow.SHRINK
        box.textLabel.verticalAlign = Label.VerticalAlign.CENTER
        box.textLabel.horizontalAlign = Label.HorizontalAlign.LEFT
        // **把文字钉在框里**（不钉的话它会落在框的上沿外侧 —— 第一轮截图里打出来的
        // 「试炼国」飘在国名框上面）。EditBox 只在自己的 resize 事件里重算这块矩形，
        // 而这里全程没有 resize，所以这一次定位就是最终定位。
        const text = box.textLabel.getComponent(UITransform)
        if (text !== null) {
          text.setAnchorPoint(0, 0.5)
          text.setContentSize(new Size(width - 20, FIELD_HEIGHT))
          box.textLabel.node.setPosition(new Vec3(10, 0, 0))
        }
      }
      this.inputFields.set(name, field)
      box.node.on(EditBox.EventType.TEXT_CHANGED, () => {
        this.draft = { ...this.draft, [field]: box.string }
        this.redraw()
      }, this)
      this.inputs.set(name, node)
    }
    node.getComponent(UITransform)?.setContentSize(new Size(width, FIELD_HEIGHT))
    node.setPosition(new Vec3(x, y, 0))
    const box = node.getComponent(EditBox)
    if (box !== null && box.string !== text) {
      box.string = text
    }
  }

  /** 造一块画布子节点：位置、**尺寸**与登记一次做完（默认 100×100 会让触摸区域错位）。 */
  private surface(name: string, x: number, y: number, width: number, height: number): Graphics {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(width, height))
    node.setPosition(new Vec3(x, y, 0))
    this.nodes.push(node)
    return node.addComponent(Graphics)
  }

  /** 造一个 Label：**锚点先按对齐方式定**再摆位置（默认中心锚点会让左对齐的边界参差）。 */
  private label(text: string, color: Color, size: number, x: number, y: number,
    align: 'left' | 'right' | 'center'): void {
    if (text === '') {
      return
    }
    const node = new Node('label')
    node.layer = this.node.layer
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
    this.nodes.push(node)
  }
}
