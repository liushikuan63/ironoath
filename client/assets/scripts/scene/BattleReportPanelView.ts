/**
 * 职责：战报面板 —— 列表 + 点开一场进回放（B12 §3，台账第 21 条）。
 * 依赖：cc（渲染）、game/battle/BattleReportPanel（组装，已单测）、scene/BattlePlaybackView、scene/NodePool。
 *
 * <p><b>本场景不判胜负、不算时长</b>（铁律 2）：`won` 与 `totalRounds` 来自服务端简报，
 * 回放参数来自 `BattleReportResp.playback`（表里那两个数）。这里只画与递意图。
 *
 * <p><b>回放视图是面板的孩子，不是导航条的第 N 格</b>：`BattlePlaybackView` 自己建整屏背景，
 * 挂成同级会让玩家在同一格里看到两层背景；挂进来之后「列表 ← → 回放」是同一个面板内的两步，
 * 也就是 B12 §3「列表分页，详情可重放」的形状。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：长列表 ScrollView（超一屏目前截断并写明）、正式美术。
 */

import { _decorator, Color, Component, EventTouch, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import { buildReportList, buildScoutIntel } from '../game/battle/BattleReportPanel'
import {
  clampPage, contentPerPage, pageCount, pageNotice, pageWindow,
} from '../game/ui/PanelPaging'
import type { ReportListView, ReportRow, ScoutRow, ScoutListView } from '../game/battle/BattleReportPanel'
import { BattlePlaybackView } from './BattlePlaybackView'
import type { BattleReportListResp, BattleResultView } from '../net/generated/BattleProtocol'
import type { ScoutListResp } from '../net/generated/WorldProtocol'
import type { PlaybackOptions } from '../game/battle/BattlePlayback'
import { ChoiceOverlay } from './ChoiceOverlay'
import type { ShareChannelChoice } from '../game/session/Choices'
import { NodePool } from './NodePool'
import { applySystemUiFont, capWidth } from './UiFont'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(20, 17, 15, 238)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_ROW_LOST = new Color(38, 30, 30, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_WIN = new Color(120, 176, 96, 255)
const COLOR_LOSE = new Color(196, 92, 78, 255)
/** 分享失败那行提示用告警色（与其它面板同一套配色） */
const COLOR_WARNING = new Color(200, 96, 64, 255)

const PANEL_WIDTH = 680
const ROW_HEIGHT = 60
const ROW_GAP = 6
const PADDING = 16

/** 左边两行文字能给到的宽度：右边还要留给「有效 / 胜 / 败」那一格 */
const TEXT_WIDTH = PANEL_WIDTH - 2 * PADDING - 150
const HEADER_HEIGHT = 64
/** 池的预热行数；真正画几行由 `render` 按可视高算（与 MailPanelView #394 同一口径）。 */
const MAX_VISIBLE_ROWS = 8
/** 底部导航条占掉的高度，与 QuestPanelView / ArmyPanelView / MailPanelView 同一个口径。 */
const BOTTOM_RESERVED = 68
/** 右列（胜 / 败 / 有效 / 过期）的槽宽：`TEXT_WIDTH` 从行宽里扣掉的那一段，再留 12 的缝。 */
const OUTCOME_SLOT = PANEL_WIDTH - 2 * PADDING - TEXT_WIDTH - 12

@ccclass('BattleReportPanelView')
export class BattleReportPanelView extends Component {
  private list: ReportListView | null = null
  private pendingList: BattleReportListResp | null = null
  private pendingNow = 0

  private rowPool: NodePool | null = null
  private readonly drawnRows: Node[] = []
  private headerLabel: Label | null = null
  /** 敌情那一份列表（B26 S19）。与战报同一块面板、同一个行池，但字段是情报的字段 */
  private scouts: ScoutListView | null = null
  private intel: 'BATTLE' | 'SCOUT' = 'BATTLE'
  private battleTabLabel: Label | null = null
  private scoutTabLabel: Label | null = null
  private emptyLabel: Label | null = null
  private overflowLabel: Label | null = null
  /** 当前页（0 起）。换页签归零（两块数据集不同），重新 attach 只 clamp（见 attach 那条注释）。 */
  private page = 0
  private prevPageButton: Node | null = null
  private nextPageButton: Node | null = null
  private prevPageCaption: Label | null = null
  private nextPageCaption: Label | null = null
  private canPrev = false
  private canNext = false
  private backLabel: Label | null = null
  private listNode: Node | null = null
  private playbackNode: Node | null = null
  private playback: BattlePlaybackView | null = null
  /** 回放中…占位：详情请求在路上时先说一声，免得玩家以为点下去没反应 */
  private statusLabel: Label | null = null
  /** 正在看的那一场。分享要的正是它 —— 回放视图自己不认识战报 id（它只画战果）。 */
  private currentReportId: string | null = null
  /** 「分享」按钮（只在回放里显示）。 */
  private shareLabel: Label | null = null
  /** 目标频道选择器（与出战阵容同一个组件，B22 §一 2） */
  private sharePicker: ChoiceOverlay | null = null

  /** 玩家要点开哪一场（外层去拉详情，参数在详情响应里）。 */
  onReplayRequested: ((reportId: string) => void) | null = null
  /** 玩家点了回放里的「分享」：外层去问目标频道再发请求。 */
  onShareRequested: ((reportId: string) => void) | null = null

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
    this.onReplayRequested = null
    this.sharePicker?.hide()
    this.sharePicker = null
    this.onShareRequested = null
    this.currentReportId = null
    this.playback = null
  }

  /**
   * 走一次目标频道选择（B22 §一 2）。选项由编排层给出，这里只画和回调 ——
   * 与出战阵容选择器同一条分工。
   */
  showSharePicker(options: readonly ShareChannelChoice[],
                  onPick: (choice: ShareChannelChoice) => void): void {
    if (this.sharePicker === null) {
      this.sharePicker = new ChoiceOverlay(this.node, '分享到哪个频道', 620)
    }
    this.sharePicker.show(options, (id) => {
      const choice = options.find((option) => option.id === id)
      if (choice !== undefined) {
        onPick(choice)
      }
    })
  }

  /** 一次分享的结果（成功与失败都走这里）：回放页顶那行提示。 */
  showShareOutcome(text: string, warning: boolean): void {
    if (this.statusLabel !== null) {
      this.statusLabel.string = text
      this.statusLabel.color = warning ? COLOR_WARNING : COLOR_TEXT_DIM
    }
  }

  /** 装载列表。行序照搬服务端（它按 createdAt 倒序，最新的在前）。 */
  attach(resp: BattleReportListResp, now: number): void {
    if (this.rowPool === null) {
      this.pendingList = resp
      this.pendingNow = now
      return
    }
    this.showList()
    this.list = buildReportList(resp, now)
    // 故意不归零页号：看完一场回放回来会重新 attach，归零等于把玩家弹回第一页。
    // 列表变短时 render() 里的 clampPage 夹回最后一页（#450 关卡同一口径）。
    this.render()
  }

  /** 敌情那一份列表到了就换上去（B26 S19）。不在这个方法里判"该显示哪一页"，页签是谁选的就画谁。 */
  attachScouts(resp: ScoutListResp, now: number): void {
    this.scouts = buildScoutIntel(resp, now)
    this.render()
  }

  /** 切页签。侦察情报那页的行不可点：没有回放可看。 */
  private pickIntel(intel: 'BATTLE' | 'SCOUT'): void {
    this.intel = intel
    // 换页签归零：战报与敌情是两块数据集，行数不同，留着上一页会停在错位的位置上
    //（与 `attach` 相反：那里只 clamp，因为领完/看完重新 attach 不该把玩家弹回第一页）
    this.page = 0
    this.render()
  }

  private renderScoutRow(node: Node, row: ScoutRow): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      // 过期的情报压暗：它不能再拿来定打法，混在有效情报里比没有更危险（B07 验收 9）
      graphics.fillColor = row.expired ? COLOR_ROW_LOST : COLOR_ROW
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
      graphics.fill()
    }
    const title = node.children[0]?.getComponent(Label)
    const detail = node.children[1]?.getComponent(Label)
    const outcome = node.children[2]?.getComponent(Label)
    if (title !== undefined && title !== null) {
      title.string = row.title
    }
    if (detail !== undefined && detail !== null) {
      detail.string = row.detail
    }
    if (outcome !== undefined && outcome !== null) {
      outcome.string = row.outcome
      outcome.color = row.expired ? COLOR_LOSE : COLOR_WIN
    }
    node.off('touch-start')
  }

  /**
   * 把一场战果交给回放视图。回放组件是**第一次用到时才挂**的：
   * 它 onLoad 会建整屏背景与节点，没放过战报就不该有这些开销。
   */
  showReplay(reportId: string, result: BattleResultView, options: PlaybackOptions): void {
    this.currentReportId = reportId
    if (this.playback === null) {
      const host = new Node('Playback')
      host.layer = this.node.layer
      this.node.addChild(host)
      host.addComponent(UITransform).setContentSize(new Size(view.getVisibleSize().width,
        view.getVisibleSize().height))
      // 面板的节点树里各子节点是未激活状态，回放要自己显示出来才跑 onLoad
      host.active = true
      this.playbackNode = host
      this.playback = host.addComponent(BattlePlaybackView)
    }
    if (this.listNode !== null) {
      this.listNode.active = false
    }
    if (this.backLabel !== null) {
      this.backLabel.string = '← 返回战报'
    }
    if (this.shareLabel !== null) {
      // 分享按钮只在回放里出现：列表页分享"哪一场"还不明确
      this.shareLabel.string = '分享'
    }
    if (this.statusLabel !== null) {
      this.statusLabel.string = ''
    }
    this.playback.attach(result, options)
  }

  /** 详情请求还在路上：先给一句话，别让玩家对着列表以为刚才没点上。 */
  showReplayLoading(): void {
    if (this.statusLabel !== null) {
      this.statusLabel.string = '正在取这场战报的回放数据…'
    }
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
    const list = new Node('List')
    list.layer = this.node.layer
    this.node.addChild(list)
    this.listNode = list

    this.headerLabel = this.addLabel(list, 'Header', 0, height / 2 - PADDING - 20, COLOR_COPPER_GOLD, 22)
    this.battleTabLabel = this.addLabel(list, 'TabBattle', -70, height / 2 - PADDING - 48, COLOR_COPPER_GOLD, 15)
    this.battleTabLabel.string = '战报'
    this.battleTabLabel.node.on('touch-start', (_event: EventTouch) => this.pickIntel('BATTLE'), this)
    this.scoutTabLabel = this.addLabel(list, 'TabScout', 70, height / 2 - PADDING - 48, COLOR_TEXT_DIM, 15)
    this.scoutTabLabel.string = '侦察情报'
    this.scoutTabLabel.node.on('touch-start', (_event: EventTouch) => this.pickIntel('SCOUT'), this)
    this.emptyLabel = this.addLabel(list, 'Empty', 0, 40, COLOR_TEXT_DIM, 17)
    this.overflowLabel = this.addLabel(list, 'Overflow', 0,
      -height / 2 + PADDING + 18, COLOR_TEXT_DIM, 14)
    // 两颗翻页键与那句页码同一行、摆在两端。挂在 List 上而不是 this.node 上：
    // 回放那一屏靠 List.active 关掉，键跟着一起消失才不会浮在回放上。
    this.prevPageButton = this.buildPagerButton(list, 'PrevPageButton', -PANEL_WIDTH / 2 + 46)
    this.nextPageButton = this.buildPagerButton(list, 'NextPageButton', PANEL_WIDTH / 2 - 46)
    this.statusLabel = this.addLabel(this.node, 'Status', 0, 0, COLOR_TEXT_DIM, 16)
    this.backLabel = this.addLabel(this.node, 'Back', -PANEL_WIDTH / 2 + 56,
      height / 2 - PADDING - 20, COLOR_TEXT, 16)
    this.backLabel.string = ''
    const back = this.backLabel.node
    back.on('touch-start', (_event: EventTouch) => this.showList())

    // 「分享」摆在右上角（返回在左上角）：回放里唯一会写数据的动作，位置要稳定
    this.shareLabel = this.addLabel(this.node, 'Share', PANEL_WIDTH / 2 - 56,
      height / 2 - PADDING - 20, COLOR_COPPER_GOLD, 16)
    this.shareLabel.string = ''
    const share = this.shareLabel.node
    share.on('touch-start', (_event: EventTouch) => {
      const reportId = this.currentReportId
      if (reportId !== null) {
        this.onShareRequested?.(reportId)
      }
    })
  }

  /**
   * 一颗 64×28 的翻页键（照本面板行上动作键的画法）。父节点传进来：这一屏的键要挂在 List 上，
   * 点进回放时 List 整块关掉，浮在 this.node 上的键会留在回放里。
   */
  private buildPagerButton(parent: Node, name: string, x: number): Node {
    const node = new Node(name)
    node.layer = this.node.layer
    parent.addChild(node)
    node.addComponent(UITransform).setContentSize(new Size(64, 28))
    node.setPosition(new Vec3(x, 0, 0))
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.strokeColor = COLOR_COPPER_GOLD
    graphics.lineWidth = 1
    graphics.roundRect(-32, -14, 64, 28, 4)
    graphics.fill()
    graphics.stroke()
    const caption = this.addLabel(node, 'Caption', 0, 0, COLOR_TEXT, 13)
    caption.string = name === 'PrevPageButton' ? '上一页' : '下一页'
    // 建出来先收着：render() 在两份列表都没到时早退，不先收就会在"还没拉到战报"那一态
    // 露出两颗点了没反应的键（#345 口径，#449 与 #450 各修过一次同一族）
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
   * 翻一页。灰掉的那一侧直接不吃：`render()` 里的 `clampPage` 也会把越界页号夹回来，
   * 但"点了没反应"正是 #345 那条口径要挡的观感。
   */
  private turnPage(delta: number): void {
    if (delta < 0 && !this.canPrev) return
    if (delta > 0 && !this.canNext) return
    this.page += delta
    this.render()
  }

  private showList(): void {
    if (this.listNode !== null) {
      this.listNode.active = true
    }
    if (this.playbackNode !== null) {
      this.playbackNode.active = false
    }
    if (this.backLabel !== null) {
      this.backLabel.string = ''
    }
    if (this.shareLabel !== null) {
      this.shareLabel.string = ''
    }
    if (this.statusLabel !== null) {
      this.statusLabel.string = ''
      this.statusLabel.color = COLOR_TEXT_DIM
    }
    this.currentReportId = null
  }

  /** 左对齐标签：锚点挪到左中，宽度交给 `UiFont.capWidth`（盒高它钉 27，正好是"一行不被压小"的下限） */
  private sizeLeftLabel(label: Label, width: number): void {
    const transform = label.node.getComponent(UITransform)
    transform?.setAnchorPoint(0, 0.5)
    capWidth(label, width)
  }

  private createRow(): Node {
    const node = new Node('ReportRow')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH, ROW_HEIGHT))
    node.addComponent(Graphics)
    // 两个左对齐的标签必须有真实宽度和锚点：addLabel 给的 UITransform 是默认 100×100 居中锚点，
    // 于是长文案（敌情那一行的观测值 + 误差 + 剩余时间）会从盒子左边溢出、左半边被裁掉
    const title = this.addLabel(node, 'Title', -PANEL_WIDTH / 2 + PADDING, 12, COLOR_TEXT, 18)
    title.horizontalAlign = Label.HorizontalAlign.LEFT
    this.sizeLeftLabel(title, TEXT_WIDTH)
    const detail = this.addLabel(node, 'Detail', -PANEL_WIDTH / 2 + PADDING, -12, COLOR_TEXT_DIM, 14)
    detail.horizontalAlign = Label.HorizontalAlign.LEFT
    this.sizeLeftLabel(detail, TEXT_WIDTH)
    // 右列同样要限宽：它只有"胜 / 败 / 有效 / 过期"四个短值，但值是服务端装配的，
    // 不限宽就等于赌它一直短（锚点翻到右边，盒子往左长，才不会顶出卡片）
    const outcome = this.addLabel(node, 'Outcome', PANEL_WIDTH / 2 - PADDING, 0, COLOR_TEXT, 18)
    outcome.horizontalAlign = Label.HorizontalAlign.RIGHT
    outcome.node.getComponent(UITransform)?.setAnchorPoint(1, 0.5)
    capWidth(outcome, OUTCOME_SLOT)
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
    const list = this.list
    const pool = this.rowPool
    if ((this.intel === 'BATTLE' && list === null) || (this.intel === 'SCOUT' && this.scouts === null)
      || pool === null) {
      // 两份列表还没到齐：这一屏没东西可翻，两颗键整对收掉。从前只在这里 return，
      // 于是"战报 9 场已出键 → 点敌情而情报未回"那一态留着上一屏的亮键，点了只加页号不换屏
      this.canPrev = false
      this.canNext = false
      if (this.prevPageButton !== null) {
        this.prevPageButton.active = false
      }
      if (this.nextPageButton !== null) {
        this.nextPageButton.active = false
      }
      return
    }
    const scoutMode = this.intel === 'SCOUT'
    if (this.battleTabLabel !== null) {
      this.battleTabLabel.color = scoutMode ? COLOR_TEXT_DIM : COLOR_COPPER_GOLD
    }
    if (this.scoutTabLabel !== null) {
      this.scoutTabLabel.color = scoutMode ? COLOR_COPPER_GOLD : COLOR_TEXT_DIM
    }
    const height = view.getVisibleSize().height
    const source = scoutMode ? this.scouts : list
    if (source === null) {
      return
    }
    if (this.emptyLabel !== null) {
      this.emptyLabel.string = source?.emptyText ?? ''
      // 有行的时候必须藏起来：敌情页第一版就是两行情报下面还挂着「还没有敌情」，
      // 读数全绿（只判了文字内容），画面自相矛盾
      this.emptyLabel.node.active = source.rows.length === 0
    }

    pool.releaseAll(this.drawnRows)
    this.drawnRows.length = 0
    const topY = height / 2 - PADDING - HEADER_HEIGHT - ROW_HEIGHT / 2
    // 行数按实测可视高度算，不写死（与 MailPanelView #394 同一处修法）：写死 8 行时第 6~8 行
    // 连同"另有 N 场未显示"一起落到导航条底下 —— 量具现在看得见这一条了（#390 升成判据）。
    const navTop = -height / 2 + BOTTOM_RESERVED
    const capacity = Math.max(1,
      Math.floor((topY + ROW_HEIGHT / 2 - navTop) / (ROW_HEIGHT + ROW_GAP)))
    // 共几页、夹到哪一页、切哪一段用同一个 perPage（`PanelPaging` 那条原话）：从前这里
    // slice(0, maxRows) 一刀切，剩下的写成「另有 N 场未显示」就没有然后了 —— 那是 #307
    // 说的"功能看不见换了件衣服"。
    const total = source.rows.length
    const perPage = contentPerPage(total, capacity)
    const pages = pageCount(total, perPage)
    this.page = clampPage(this.page, total, perPage)
    const slice = pageWindow(total, this.page, perPage)
    const visible = source.rows.slice(slice.start, slice.end)
    visible.forEach((row: ReportRow | ScoutRow, index: number) => {
      const node = pool.acquire()
      node.setPosition(new Vec3(0, topY - index * (ROW_HEIGHT + ROW_GAP), 0))
      this.drawnRows.push(node)
      if (scoutMode) {
        this.renderScoutRow(node, row as ScoutRow)
      } else {
        this.renderRow(node, row as ReportRow)
      }
    })

    const paged = pages > 1
    const rowBottom = topY - visible.length * (ROW_HEIGHT + ROW_GAP) - 10
    this.canPrev = this.page > 0
    this.canNext = this.page < pages - 1
    if (this.headerLabel !== null) {
      this.headerLabel.string = source.headerText
    }
    // 页码借用 Overflow 那一格：它从前每帧被清空（那句「另有 N 场」挂表头是因为按 8 行摆会把
    // 提示挤到导航条底下）。现在装不下时让出一格行位，页码与两颗键就排在那一格上。
    if (this.overflowLabel !== null) {
      this.overflowLabel.string = paged
        ? `${pageNotice(this.page, pages)} · 共 ${total} ${scoutMode ? '份' : '场'}`
        : ''
      this.overflowLabel.node.setPosition(new Vec3(0, rowBottom, 0))
    }
    // 只有一页时两颗键整对收掉（#345：不留点了没反应的键）；多页时不可翻的那一侧按灰
    for (const [button, caption, usable] of [
      [this.prevPageButton, this.prevPageCaption, this.canPrev],
      [this.nextPageButton, this.nextPageCaption, this.canNext],
    ] as Array<[Node | null, Label | null, boolean]>) {
      if (button === null || caption === null) continue
      button.active = paged
      button.setPosition(new Vec3(button.position.x, rowBottom, 0))
      caption.color = usable ? COLOR_TEXT : COLOR_TEXT_DIM
    }
  }

  private renderRow(node: Node, row: ReportRow): void {
    const graphics = node.getComponent(Graphics)
    if (graphics !== null) {
      graphics.clear()
      // 赢的行亮一点、输的行压暗：一眼扫得出哪几场要复盘
      graphics.fillColor = row.won ? COLOR_ROW : COLOR_ROW_LOST
      graphics.roundRect(-PANEL_WIDTH / 2, -ROW_HEIGHT / 2, PANEL_WIDTH, ROW_HEIGHT, 6)
      graphics.fill()
    }
    const title = node.children[0]?.getComponent(Label)
    const detail = node.children[1]?.getComponent(Label)
    const outcome = node.children[2]?.getComponent(Label)
    if (title !== undefined && title !== null) {
      title.string = row.title
    }
    if (detail !== undefined && detail !== null) {
      detail.string = `${row.roundsText} · ${row.lossText} · ${row.expiresIn}过期`
    }
    if (outcome !== undefined && outcome !== null) {
      outcome.string = row.outcomeText
      outcome.color = row.won ? COLOR_WIN : COLOR_LOSE
    }
    node.off('touch-start')
    node.on('touch-start', (_event: EventTouch) => {
      this.showReplayLoading()
      this.onReplayRequested?.(row.reportId)
    })
  }
}
