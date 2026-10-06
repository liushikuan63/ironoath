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
  NationPanelView as NationPanelData, NationTabKey, PayeeType, SpendDraft,
} from '../game/nation/NationPanel'
import { NATION_TABS, SPEND_AMOUNT_PRESETS, TREASURY_LOG_ROWS, amountText, spendDraftBlocker, spendSinkOptions } from '../game/nation/NationPanel'
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
/**
 * 国策页一屏画几条提案 / 几条候选（B13 §4 的公示是明文要求可查项，所以截断要说清剩几条）。
 *
 * <p>取 3 是版式推导：一屏 600 逻辑高减掉标题、页签、倒计时、槽位说明之后剩约 340，
 * 每条提案占两行 + 门禁理由一行 ≈ 74，3 条 ≈ 222，候选区再留一屏的一半。
 * 刻意不取「能放多少放多少」：多画一条就把生效状态推出屏外，而那一行是玩家最该先看见的。
 */
const POLICY_PROPOSAL_ROWS = 3
const POLICY_CANDIDATE_ROWS = 4

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
  /** 切页签（V13-S2）。切到「科技」时才发 `/nation/tech` 那一枪。 */
  onSelectTab: ((tab: NationTabKey) => void) | null = null
  /** 研究一级国家科技。传的是 `techId`（**只用于发请求，永不上屏**）。 */
  onResearchTech: ((techId: string) => void) | null = null
  /** 记一条外交关系。 */
  onSetRelation: ((targetNationId: string, relation: string) => void) | null = null
  /** 任命一名成员。 */
  onAppoint: ((playerId: string, office: string) => void) | null = null
  /** 外交页当前选中的目标国（`nationId`；null = 还没选）。 */
  private diproTarget: string | null = null
  private diproRelation = 'ALLIED'
  /** 任命页当前选中的人。 */
  private appointTarget: string | null = null
  /** 提案：把一条国策放进本轮提案池（`policyId` 只用于发请求，永不上屏）。 */
  onProposePolicy: ((policyId: string) => void) | null = null
  /**
   * 投票（`proposalId` / `support`）。**只喊出去，不判** ——
   * 能不能投、是不是已经投过，全部来自 `policyRow.voteGate`。
   */
  onVotePolicy: ((proposalId: string, support: boolean) => void) | null = null

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
    this.onSelectTab = null
    this.onResearchTech = null
    this.onSetRelation = null
    this.onAppoint = null
  }

  /** 每次打开都重置输入态（"关掉再开还留着上次的字"是本仓记过的形态）。 */
  beginSession(): void {
    this.draft = freshDraft()
    this.payees = []
    this.diproTarget = null
    this.diproRelation = 'ALLIED'
    this.appointTarget = null
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
      cursor = this.drawTabs(left, innerWidth, cursor, data)
      switch (data.tab) {
        case 'TREASURY':
          // 支出表单开着的时候**收起概况那六行**：一屏 600 逻辑高塞不下「概况 + 国库 + 操作 + 表单」，
          // 而表单开着时玩家要的正是"我这笔钱花给谁"，概况里那几行此时是重复信息（标题与身份那句仍在）。
          if (!this.draft.armed) {
            cursor = this.drawSummary(left, cursor, data)
          }
          cursor = this.drawTreasury(left, innerWidth, cursor, data)
          cursor = this.drawActions(left, cursor, data)
          break
        case 'TECH':
          cursor = this.drawTech(left, innerWidth, cursor, data)
          break
        case 'DIPLO':
          cursor = this.drawDiplomacy(left, innerWidth, cursor, data)
          break
        case 'WAR':
          cursor = this.drawWar(left, innerWidth, cursor, data)
          break
        case 'OFFICE':
          cursor = this.drawAppoint(left, cursor, data)
          break
        case 'POLICY':
          cursor = this.drawPolicy(left, innerWidth, cursor, data)
          break
      }
    }
    if (data.notice !== null) {
      // **成功是金色、失败是红色**：这一屏同时承载公共资产的操作，
      // 把"研究完成"染成警告红，玩家读到的就是"出错了"。
      this.label(data.notice, data.noticeTone === 'ok' ? COLOR_GOLD : COLOR_WARN, 14,
        left, cursor - 12, 'left')
    }
  }

  // ---------- S2 的四个页签 ----------

  /** 页签条。当前页签点亮，**点哪一颗都只是"切过去看"**（真正的动作在各自那一页里）。 */
  private drawTabs(left: number, innerWidth: number, top: number, data: NationPanelData): number {
    const width = Math.floor(innerWidth / NATION_TABS.length) - 8
    NATION_TABS.forEach((tab, index) => {
      const x = left + width / 2 + index * (width + 8)
      const active = tab.key === data.tab
      this.button(`Tab_${tab.key}`, tab.label, x, top - 16, width, true, () => this.onSelectTab?.(tab.key))
      if (active) {
        // 当前页签加一道底线：不靠"按下去有反应"这种回执当唯一指示
        const mark = this.surface(`TabMark_${tab.key}`, x, top - 16 - BUTTON_HEIGHT / 2 - 1, width, 2)
        mark.fillColor = COLOR_GOLD
        mark.rect(-width / 2, -1, width, 2)
        mark.fill()
      }
    })
    return top - 36
  }

  private drawTech(left: number, innerWidth: number, top: number, data: NationPanelData): number {
    const section = data.sections?.tech ?? null
    if (section === null) {
      this.label('国家科技这一次没拉到', COLOR_WARN, 14, left, top - 12, 'left')
      return top - 32
    }
    let y = top - 12
    if (section.headerText !== null) {
      this.label(section.headerText, COLOR_GOLD, 15, left, y, 'left')
      y -= 26
    }
    if (section.emptyText !== null) {
      this.label(section.emptyText, COLOR_DIM, 14, left, y, 'left')
      return y - 26
    }
    const actionWidth = 110
    section.rows.slice(0, TREASURY_LOG_ROWS).forEach(row => {
      const plate = this.surface(`TechRow-${row.key}`, 0, y - 14, innerWidth, 46)
      plate.fillColor = COLOR_ROW
      plate.rect(-innerWidth / 2, -23, innerWidth, 46)
      plate.fill()
      this.label(row.titleText, COLOR_TEXT, 15, left + 10, y - 4, 'left')
      this.label(row.detailText, COLOR_DIM, 13, left + 10, y - 20, 'left')
      if (row.effectText !== null) {
        this.label(row.effectText, COLOR_HINT, 13, left + innerWidth - actionWidth - 16, y - 4, 'right')
      }
      this.button(`TechResearch-${row.key}`, row.actionText, left + innerWidth - actionWidth / 2 - 8, y - 10,
        actionWidth, row.enabled, () => this.onResearchTech?.(row.key))
      if (row.reason !== null) {
        this.label(row.reason, COLOR_DIM, 12, left + innerWidth - actionWidth - 16, y - 20, 'right')
      }
      y -= 50
    })
    const hidden = section.rows.length - TREASURY_LOG_ROWS
    if (hidden > 0) {
      this.label(`另有 ${hidden} 行未显示`, COLOR_DIM, 13, left, y, 'left')
      y -= 22
    }
    return y
  }

  private drawDiplomacy(left: number, innerWidth: number, top: number, data: NationPanelData): number {
    const section = data.sections?.diplomacy ?? null
    if (section === null) {
      this.label('外交这一页没拉到', COLOR_WARN, 14, left, top - 12, 'left')
      return top - 32
    }
    let y = top - 12
    // ① 当前关系表（变更之后才有）
    if (section.emptyText !== null) {
      this.label(section.emptyText, COLOR_DIM, 13, left, y, 'left')
      y -= 26
    } else {
      section.rows.slice(0, 4).forEach(row => {
        this.label(row.name, COLOR_TEXT, 15, left + 6, y, 'left')
        this.label(row.relationText ?? '未记录', row.relationText === null ? COLOR_DIM : COLOR_GOLD, 14,
          left + innerWidth - 90, y, 'right')
        this.button(`DiproSet-${row.key}`, '改关系', left + innerWidth - 40, y, 74, section.gate.enabled,
          () => { this.diproTarget = row.key; this.redraw() })
        y -= ROW_HEIGHT
      })
      y -= 6
    }
    // ② 选目标国
    this.label('选一个国家', COLOR_DIM, 13, left, y, 'left')
    y -= 26
    const targetWidth = 110
    section.targets.slice(0, 6).forEach((target, index) => {
      const x = left + targetWidth / 2 + index * (targetWidth + 8)
      // **常亮可选**，"选中"另用一态画（见 `button` 的三态说明）
      this.button(`DiproTarget-${target.key}`, target.name, x, y, targetWidth, true,
        () => { this.diproTarget = target.key; this.redraw() },
        this.diproTarget === target.key)
    })
    y -= 32
    // ③ 四种关系
    this.label('把关系记为', COLOR_DIM, 13, left, y, 'left')
    y -= 26
    const optionWidth = 92
    section.options.forEach((option, index) => {
      const x = left + optionWidth / 2 + index * (optionWidth + 8)
      // 两颗门都要过：权限位（能不能改）与"选了目标国没有"
      this.button(`DiproOption-${option.key}`, option.label, x, y, optionWidth,
        section.gate.enabled && this.diproTarget !== null,
        () => {
          if (this.diproTarget === null) {
            return
          }
          this.diproRelation = option.key
          this.onSetRelation?.(this.diproTarget, option.key)
        })
    })
    y -= 26
    const note = section.gate.reason
      ?? section.options.find(option => option.key === this.diproRelation)?.note ?? ''
    this.label(note, section.gate.reason === null ? COLOR_DIM : COLOR_WARN, 13, left, y, 'left')
    return y - 22
  }

  /**
   * 国战那一页（B13 §一 §7 / V18 的客户端承接）。
   *
   * <p><b>只读</b>：宣战那颗键在下一片（它要选目标、二次确认、还要一个幂等键）——
   * 这一片先把"这场仗现在什么样"画出来，因为在那之前玩家连"有没有仗"都看不到。
   *
   * <p><b>三态各有各的话</b>（没拉到 / 拉到了确无仗 / 有仗）：合成一句"暂无国战"会让断网
   * 看起来像"国战系统没开"。见 `buildWarSection` 的三分支。
   */
  private drawWar(left: number, innerWidth: number, top: number, data: NationPanelData): number {
    const section = data.sections?.war ?? null
    if (section === null) {
      this.label('国战这一页没拉到', COLOR_WARN, 14, left, top - 12, 'left')
      return top - 32
    }
    let y = top - 12
    if (!section.hasWar) {
      this.label(section.emptyText ?? '现在没有正在打的国战', COLOR_DIM, 13, left, y, 'left')
      y -= 30
      // 即便没有仗，全服进度也是玩家会关心的一行（B13 §7：不打国战的人的贡献也算）——
      // 有就画，没有（这一次没读到）就不画，不填 0 冒充
      if (section.goalText !== null) {
        this.label(section.goalText, COLOR_TEXT, 14, left, y, 'left')
        y -= 24
      }
      if (section.fatigueText !== null) {
        this.label(section.fatigueText, COLOR_DIM, 13, left, y, 'left')
        y -= 24
      }
      return y
    }
    // 有仗：状态两行 + 王城一行
    this.label(section.headline ?? '', COLOR_DIM, 13, left, y, 'left')
    y -= 24
    if (section.phaseText !== null) {
      this.label(section.phaseText, COLOR_GOLD, 15, left, y, 'left')
      y -= 24
    }
    if (section.remainingText !== null) {
      this.label(section.remainingText, COLOR_TEXT, 15, left, y, 'left')
      y -= 24
    }
    if (section.capitalText !== null) {
      this.label(section.capitalText, COLOR_TEXT, 15, left, y, 'left')
      y -= 28
    }
    section.rows.forEach(row => {
      this.label(`${row.rankText} ${row.name}`, COLOR_TEXT, 15, left + 6, y, 'left')
      this.label(row.gatesText, COLOR_DIM, 13, left + innerWidth - 150, y, 'right')
      this.label(row.scoreText, COLOR_GOLD, 15, left + innerWidth - 40, y, 'right')
      y -= ROW_HEIGHT
      if (row.qualifiedText !== null) {
        this.label(row.qualifiedText, COLOR_DIM, 12, left + 18, y, 'left')
        y -= 20
      }
    })
    y -= 6
    if (section.goalText !== null) {
      this.label(section.goalText, COLOR_TEXT, 14, left, y, 'left')
      y -= 24
    }
    if (section.fatigueText !== null) {
      this.label(section.fatigueText, COLOR_DIM, 13, left, y, 'left')
      y -= 24
    }
    return y
  }

  private drawAppoint(left: number, top: number, data: NationPanelData): number {
    const section = data.sections?.appoint ?? null
    if (section === null) {
      this.label('任命这一页没拉到', COLOR_WARN, 14, left, top - 12, 'left')
      return top - 32
    }
    let y = top - 12
    if (section.notice !== null) {
      this.label(section.notice, COLOR_DIM, 14, left, y, 'left')
      return y - 26
    }
    this.label('选一个人', COLOR_DIM, 13, left, y, 'left')
    y -= 26
    const nameWidth = 104
    section.rows.slice(0, 6).forEach((row, index) => {
      const x = left + nameWidth / 2 + index * (nameWidth + 8)
      this.button(`AppointTarget-${row.key}`, row.name, x, y, nameWidth, true,
        () => { this.appointTarget = row.key; this.redraw() },
        this.appointTarget === row.key)
    })
    y -= 32
    this.label('任命为', COLOR_DIM, 13, left, y, 'left')
    y -= 26
    const officeWidth = 96
    section.offices.forEach((office, index) => {
      const x = left + officeWidth / 2 + index * (officeWidth + 8)
      // 两颗门：权限位（能不能任命）与"选了人没有"
      this.button(`AppointOffice-${office.key}`, office.label, x, y, officeWidth,
        section.gate.enabled && this.appointTarget !== null,
        () => {
          if (this.appointTarget === null) {
            return
          }
          this.onAppoint?.(this.appointTarget, office.key)
        })
    })
    y -= 28
    if (section.gate.reason !== null) {
      this.label(section.gate.reason, COLOR_WARN, 13, left, y, 'left')
      y -= 20
    }
    this.label('国王与议员没有任命入口（那是席位，不是任出来的）', COLOR_DIM, 12, left, y, 'left')
    return y - 20
  }

  /**
   * 国策那一页（B13 §4）。
   *
   * <p><b>`innerWidth` 是形参，不是 `window.innerWidth`</b>：这个函数曾经把形参删掉过一次，
   * 于是表达式里的 `innerWidth` 静默解析到 DOM 全局（DOM lib 声明了同名全局，所以
   * 类型检查一声不响），把「提案」键推到 x≈1341 —— 卡片只有 -360..+360，
   * 键全跑到屏外：探针按节点名 emit 所以点得到，而屏上一个都看不见。
   * 探针里那条「键数 + 左边缘」的判据就是为这件事留的，别删。
   *
   * <p><b>候选区单列</b>，与科技页、任命页同一套排版：两列的第一版两处叠着毛病
   * （键压在第二列文字上；改成一列一键后第二列又画出卡片右边界）。
   *
   * <p><b>这一页只画 {@code buildPolicyPanel} 给的东西</b>：能不能提、能不能投、为什么灰、
   * 倒计时、槽位说明、公示名单，全部是服务端下发的那一位（铁律 2）。
   * 灰键不挂 touch-start ⇒ 点了零请求。
   *
   * <p><b>版式取舍</b>：提案区每条占两行（国策名 + 票数与名单），
   * 一屏放得下 3 条；再多的只画前 3 条并说清还剩几条 ——
   * 公示是 B13 §4 明文要求的可查项，**不能说"更多"就把它省掉**。
   */
  private drawPolicy(left: number, innerWidth: number, top: number, data: NationPanelData): number {
    const policy = data.sections?.policy ?? null
    if (policy === null) {
      this.label('国策这一次没拉到', COLOR_WARN, 14, left, top - 12, 'left')
      return top - 32
    }
    let y = top - 18
    this.label(policy.header, COLOR_GOLD, 15, left, y, 'left')
    y -= 24
    this.label(policy.countdownText, COLOR_DIM, 13, left, y, 'left')
    y -= 20
    this.label(policy.activeText, COLOR_HINT, 13, left, y, 'left')
    y -= 24

    // ---------- 提案区 ----------
    this.label(`本轮提案（${policy.proposals.length}）`, COLOR_DIM, 13, left, y, 'left')
    y -= 24
    if (policy.proposals.length === 0) {
      this.label('本轮还没有提案 —— 国王或官员可以从下面挑一条提上来', COLOR_DIM, 13, left, y, 'left')
      y -= 24
    }
    const shown = policy.proposals.slice(0, POLICY_PROPOSAL_ROWS)
    for (const row of shown) {
      this.label(row.name, COLOR_TEXT, 14, left, y, 'left')
      this.label(row.tallyText, COLOR_DIM, 12, left + 150, y, 'left')
      this.button(`PolicyYes-${row.proposalId ?? 'x'}`, '赞成', left + 420, y, 74, row.voteGate.enabled,
        () => { if (row.proposalId !== null) this.onVotePolicy?.(row.proposalId, true) })
      this.button(`PolicyNo-${row.proposalId ?? 'x'}`, '反对', left + 500, y, 74, row.voteGate.enabled,
        () => { if (row.proposalId !== null) this.onVotePolicy?.(row.proposalId, false) })
      y -= 20
      this.label(`赞成：${row.supporters}`, COLOR_DIM, 12, left, y, 'left')
      y -= 18
      this.label(`反对：${row.opponents}`, COLOR_DIM, 12, left, y, 'left')
      y -= 18
      // 灰键的理由**印在屏上**（不只在点不动时）：玩家要能读出"为什么我不能投"
      if (!row.voteGate.enabled && row.voteGate.reason !== null && row.voteGate.reason !== '') {
        this.label(row.voteGate.reason, COLOR_WARN, 12, left, y, 'left')
        y -= 18
      }
      y -= 6
    }
    const hiddenProposals = policy.proposals.length - shown.length
    if (hiddenProposals > 0) {
      this.label(`另有 ${hiddenProposals} 条提案没显示`, COLOR_DIM, 12, left, y, 'left')
      y -= 20
    }

    // ---------- 候选区（提案用） ----------
    this.label('可提的国策', COLOR_DIM, 13, left, y, 'left')
    y -= 24
    // **单列，不是两列**。两列的第一版有两个叠在一起的毛病：键压在第二列文字上，
    // 改成两列各自带键之后，第二列的文字又跑出卡片右边界（截图里 T2/T4 那两行直接画到了
    // 卡片外面）。这里退回与科技页、任命页同一套已被验证的排版：
    // 文字贴 `left`，键排在右侧固定一处 —— 一列一行，键与文字不可能相压，也不可能出界。
    const proposeButtonX = left + innerWidth - 40
    const candidates = policy.candidates.slice(0, POLICY_CANDIDATE_ROWS)
    candidates.forEach((row, index) => {
      const cy = y - index * 28
      this.label(row.effectText, COLOR_TEXT, 12, left, cy, 'left')
      this.button(`PolicyPropose-${row.policyId}`, '提案', proposeButtonX, cy, 70, row.proposeGate.enabled,
        () => this.onProposePolicy?.(row.policyId))
    })
    y -= candidates.length * 28 + 2
    const hiddenCandidates = policy.candidates.length - candidates.length
    if (hiddenCandidates > 0) {
      this.label(`另有 ${hiddenCandidates} 条国策没显示`, COLOR_DIM, 12, left, y, 'left')
      y -= 20
    }
    // 提案的门禁理由**只显一次**（八行候选各自印一遍会把屏刷满）
    const proposeReason = candidates.find(row => !row.proposeGate.enabled)?.proposeGate.reason ?? ''
    if (proposeReason !== null && proposeReason !== '') {
      this.label(proposeReason, COLOR_WARN, 12, left, y, 'left')
      y -= 20
    }
    this.label(policy.slotNote, COLOR_DIM, 12, left, y, 'left')
    return y - 20
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
      this.button(`Amount-${preset}`, amountText(preset), x, y, presetWidth, true,
        () => { draft.amount = preset; this.redraw() }, draft.amount === preset)
    })
    y -= 32
    const sinkWidth = 108
    this.button('PayeePlayer', '发给成员', left + sinkWidth / 2, y, sinkWidth, true,
      () => {
        draft.payeeType = 'PLAYER'
        if (this.payees.length === 0) {
          this.onRequestPayees?.()
        }
        this.redraw()
      }, draft.payeeType === 'PLAYER')
    spendSinkOptions().forEach((option, index) => {
      const x = left + sinkWidth + 8 + index * (sinkWidth + 8) + sinkWidth / 2
      this.button(`Sink-${option.key}`, option.label, x, y, sinkWidth, true,
        () => {
          draft.payeeType = 'SINK'
          draft.sink = option.key
          this.redraw()
        }, draft.payeeType === 'SINK' && draft.sink === option.key)
    })
    y -= 32
    if (draft.payeeType === 'PLAYER') {
      if (this.payees.length === 0) {
        this.label('还没有可选的成员（国库只能发给本盟成员）', COLOR_DIM, 13, left, y, 'left')
        y -= 22
      }
      this.payees.slice(0, 4).forEach((payee, index) => {
        const x = left + sinkWidth / 2 + index * (sinkWidth + 8)
        this.button(`Payee-${payee.id}`, payee.name, x, y, sinkWidth, true,
          () => { draft.payeeId = payee.id; this.redraw() }, draft.payeeId === payee.id)
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

  /**
   * 一颗键。三态，而且**这三态的分工是这一格最贵的一课**：
   * ① `enabled = false` = 灰，**不挂 touch-start**（点了零请求）；
   * ② `selected` = 选中，画成金底 —— **它必须与 enabled 解耦**；
   * ③ 其余 = 常亮。
   *
   * <p><b>为什么必须解耦</b>：把「选中」直接当 `enabled` 用，等于把每一颗**选择键**的入口在
   * 「还没选中」时关掉 —— 于是玩家第一次点它没反应、永远选不中、后面那颗也永远不会亮。
   * 这是探针当场抓到的：S2 的目标国键与任命人键第一版都写成 `enabled: 当前选中的是我`，
   * 结果四颗关系键一直灰着、点了零请求。支出表单的金额预设与落点键是同族，一并拆开。
   */
  private button(name: string, text: string, x: number, y: number, width: number, enabled: boolean,
    onClick: () => void, selected = false): void {
    const graphics = this.surface(name, x, y, width, BUTTON_HEIGHT)
    const active = enabled && selected
    graphics.fillColor = active ? COLOR_GOLD : (enabled ? COLOR_ROW : COLOR_FIELD)
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 6)
    graphics.fill()
    graphics.strokeColor = enabled ? COLOR_GOLD : COLOR_DIM
    graphics.lineWidth = 1
    graphics.roundRect(-width / 2, -BUTTON_HEIGHT / 2, width, BUTTON_HEIGHT, 6)
    graphics.stroke()
    if (enabled) {
      graphics.node.on('touch-start', onClick)
    }
    this.label(text, active ? COLOR_FIELD : (enabled ? COLOR_GOLD : COLOR_DIM), 15, x, y, 'center')
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
