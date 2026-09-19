/**
 * 职责：战力明细面板的表现层（B08 §1「UI 必须能点开看明细」）。
 * 依赖：cc（渲染）、game/power/PowerPanel（数据组装）、生成的协议类型。
 *
 * <p>铁律 2：本文件是纯表现层，只<b>读</b>传入的响应，不发请求、不改数据、不做任何数值判定。
 * 删掉这个文件，游戏逻辑不受任何影响 —— 这是判断「表现层有没有越界」的标准。
 *
 * <p><b>所有文案与数字都来自 {@link buildPowerPanel}，本文件不自己算任何一个值</b>。
 * 尤其是「峰值记忆托底」那段解释：面板必须让玩家能自己复算出 matchPower，
 * 而那个复算口径（max(当前实际, 峰值×比率)）属于逻辑层，
 * 表现层再算一遍就会出现「面板写的和逻辑层算的不一样」，
 * 而玩家对「我为什么是这个战力」极度敏感（B08 §1 原话），对不上一次就再也信不过了。
 *
 * <p>占位美术用 Graphics 画纯色块，与 MainCity 同一套做法：
 * 正式美术到位后只需替换绘制部分，行结构与数据绑定不用动。
 */

import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3 } from 'cc'
import { buildPowerPanel } from '../game/power/PowerPanel'
import { RANK_TABS } from '../game/power/RankBoard'
import type { RankBoardView, RankTabKey } from '../game/power/RankBoard'
import type { SeasonPanelView } from '../game/season/SeasonPanel'
import type { PowerDetailResp } from '../net/generated/Protocol'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

/**
 * 配色沿用 B00「题材与调性」：铜金 + 暗红，写实厚重冷兵器乱世。
 * 这是美术方向常量而不是游戏数值，所以可以写在代码里
 * （铁律 1 约束的是时间/产量/攻击/掉落/冷却这类会影响平衡的数）。
 */
const COLOR_BACKGROUND = new Color(24, 20, 18, 255)
const COLOR_ROW = new Color(40, 33, 28, 255)
const COLOR_ROW_ALT = new Color(48, 39, 33, 255)
const COLOR_TOTAL = new Color(62, 44, 26, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
/** 我自己那一行：与其它行的底色区分开（玩家一眼就能找到自己） */
const COLOR_ROW_SELF = new Color(72, 52, 24, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(158, 146, 128, 255)
const COLOR_HINT = new Color(120, 168, 196, 255)

const PANEL_WIDTH = 520
const PANEL_HEIGHT = 600
const ROW_HEIGHT = 36
const PADDING = 20

@ccclass('PowerPanelView')
export class PowerPanelView extends Component {

  private readonly rows: Node[] = []
  /** 明细那份响应与榜单那份视图各存一份：它们来自两个端点，一个回来不该把另一个抹掉 */
  private powerResp: PowerDetailResp | null = null
  private rankView: RankBoardView | null = null

  /**
   * 渲染一次面板。
   *
   * <p>整块重建而不是逐行更新：明细固定五行 + 总计 + 匹配战力区块，
   * 节点数量是个位数，重建的开销远低于「维护一份行节点索引、判断哪一行要改」的复杂度。
   * 而后者一旦漏判某一行，玩家看到的就是上一次的数字 ——
   * 在一个专门用来解释「我为什么是这个战力」的面板上显示旧数字，
   * 比不显示更糟。
   */
  render(resp: PowerDetailResp): void {
    this.powerResp = resp
    this.redraw()
  }

  /**
   * 榜单那一块（B23 §一 3）。**整块视图由编排层组装好**：本文件不排页签顺序、不算名次、
   * 不判断能不能翻页 —— 它只把 {@link RankBoardView} 画出来。
   *
   * <p>与 {@link render} 分开是两个数据源（明细来自 `/player/power`，榜来自 `/rank/list`），
   * 其中一个回来时不能把另一个抹掉：合成一个入口的话，每次拉榜都会把明细画没。
   */
  renderRank(view: RankBoardView): void {
    this.rankView = view
    this.redraw()
  }

  /** 页签点击回调，由编排层注入（表现层不认识任何端点）。 */
  onRankTab: ((key: RankTabKey) => void) | null = null
  /** 翻页回调：-1 上一页 / +1 下一页。能不能翻由视图里的 canNext/canPrev 决定（服务端说了算） */
  onRankPage: ((delta: number) => void) | null = null

  /** 赛季页那一份视图（V04-S1）。与明细、榜各存一份：三个数据源来自三个端点，互相不覆盖。 */
  private seasonView: SeasonPanelView | null = null

  /**
   * 赛季页（第六个页签）。**整块视图由编排层组装好**：本文件不判断赛季开没开、
   * 不把相位翻成中文、不算还剩几天 —— 它只把 {@link SeasonPanelView} 画出来。
   */
  renderSeason(view: SeasonPanelView): void {
    this.seasonView = view
    this.redraw()
  }

  /**
   * 整块重画。
   *
   * <p>三个数据源各存一份、每次重画都从它们合成：这样"榜回来了但明细还没回来"不会互相覆盖。
   */
  private redraw(): void {
    this.clearRows()
    this.drawBackground()

    const active = this.rankView?.activeKey ?? 'DETAIL'
    let y = PANEL_HEIGHT / 2 - PADDING
    y = this.drawTitle(this.titleOf(active), y)
    y = this.drawTabs(y)

    if (active === 'SEASON') {
      this.drawSeasonArea(this.seasonView, y)
      return
    }
    if (this.rankView !== null && active !== 'DETAIL') {
      this.drawRankArea(this.rankView, y)
      return
    }
    if (this.powerResp === null) {
      this.drawHint('正在载入…', y)
      return
    }
    this.drawDetail(this.powerResp, y)
  }

  /** 一页一个标题：明细/榜/赛季是三件不同的事，标题必须说清现在在哪一页。 */
  private titleOf(active: RankTabKey): string {
    if (active === 'SEASON') {
      return this.seasonView?.titleText ?? '赛季'
    }
    return active === 'DETAIL' ? '战力明细' : '排行榜'
  }

  /**
   * 赛季那一页：阶段与倒计时、三条闸门、我的名次与荣耀、以及保留项说明。
   *
   * <p><b>未启用赛季时整块收起</b>（`visible=false`）：只留一行说明，绝不画"第 0 天"——
   * 那会让玩家以为赛季坏了，比什么都不显示更糟。
   */
  private drawSeasonArea(view: SeasonPanelView | null, startY: number): void {
    if (view === null) {
      this.drawHint('正在载入…', startY)
      return
    }
    if (!view.visible) {
      this.drawHint(view.noticeText ?? '本服尚未启用赛季', startY)
      return
    }
    let y = startY
    y = this.drawRow('阶段', view.phaseText, COLOR_TOTAL, COLOR_COPPER_GOLD, y)
    // 三条闸门各占一整行：句子要写清"什么时候能用"，塞进 label/value 的窄格子里会被截断，
    // 而截断后的「中央王城尚未开放（问…」比不写更让人困惑
    for (const gate of view.gates) {
      y = this.drawWideLine(gate.text, gate.allowed ? COLOR_TEXT : COLOR_TEXT_DIM, y)
    }
    y = this.drawSeparator(y)
    if (view.rankText !== null) {
      y = this.drawWideLine(view.rankText, COLOR_COPPER_GOLD, y)
    }
    if (view.gloryText !== null) {
      y = this.drawWideLine(view.gloryText, COLOR_TEXT, y)
    }
    y = this.drawSeparator(y)
    // 保留项永远画：玩家最怕的是"我攒的东西会不会没"，这一句是他愿意读完的全部理由
    y = this.drawHint(view.keepText, y)
    if (view.noticeText !== null) {
      y = this.drawHint(view.noticeText, y)
    }
    void y
  }

  /** 整行左对齐的一行（不分成 label/value 两格：句子里本来就带了自己的主语）。 */
  private drawWideLine(text: string, color: Color, y: number): number {
    const label = this.createLabel(text, color, 18)
    this.node.addChild(label.node)
    label.node.setPosition(new Vec3(-(PANEL_WIDTH - PADDING * 2) / 2 + 12, y - 12, 0))
    label.horizontalAlign = Label.HorizontalAlign.LEFT
    this.rows.push(label.node)
    return y - 24
  }

  /** 明细那一页：五行 + 总计 + 三个总览数（原来就是这个页面，一行没少）。 */
  private drawDetail(resp: PowerDetailResp, startY: number): void {
    const view = buildPowerPanel(resp)
    let y = startY

    // 五行明细：顺序由逻辑层决定（建筑/部队/武将/科技/装备），表现层不重排。
    // 交替底色只是为了让五行在色块占位美术下还能分清行，正式美术会换成描边
    view.lines.forEach((line, index) => {
      y = this.drawRow(line.label, line.text, index % 2 === 0 ? COLOR_ROW : COLOR_ROW_ALT,
        COLOR_TEXT, y)
    })

    // 总计必须与逐项相加一致 —— 逻辑层已经保证，这里只是照实显示。
    // 用高亮底色把它与明细行分开：玩家的第一动作就是「把五行加起来看看对不对」
    y = this.drawRow('总计', view.totalText, COLOR_TOTAL, COLOR_COPPER_GOLD, y)
    y = this.drawSeparator(y)

    y = this.drawRow('展示战力', view.displayPowerText, COLOR_ROW, COLOR_TEXT, y)
    y = this.drawRow('匹配战力', view.matchPowerText, COLOR_TOTAL, COLOR_COPPER_GOLD, y)
    y = this.drawRow('历史峰值', view.peakPowerText, COLOR_ROW, COLOR_TEXT_DIM, y)

    // 峰值记忆生效时必须解释，否则玩家看到的是「兵都卸了匹配战力怎么还这么高」，
    // 而正确答案（防压分托底）对他其实是有利的 —— 不解释就会被理解成数值造假
    if (view.peakMemoryActive) {
      y = this.drawHint(view.peakMemoryHint, y)
    }
    void y
  }

  /** 榜那一页：置顶的我的名次 + 行 + 翻页 + 说明。 */
  private drawRankArea(view: RankBoardView, startY: number): void {
    let y = startY
    if (view.mine !== null) {
      // 我的名次**恒在顶部**：玩家打开榜的第一个动作就是找自己，而自己在第 37 名
      // 意味着要翻好几页 —— 置顶这一行让他一眼看到（数值全部来自服务端下发的 myRank/myValue）
      y = this.drawRow(`我的名次 ${view.mine.rankText}`,
        `${view.mine.valueLabel} ${view.mine.valueText}`,
        COLOR_TOTAL, COLOR_COPPER_GOLD, y)
    } else if (view.notRankedText !== null) {
      y = this.drawHint(view.notRankedText, y)
    }

    for (const row of view.rows) {
      // 只画 name：组织榜的名字里**服务端已经拼进了缩写**（`铁血盟[TTX]`），
      // 再把 tag 附一次就成了「铁血盟[TTX][TTX]」—— tag 字段留着给正式美术做角标
      y = this.drawRow(`${row.rankText}  ${row.name}`, row.valueText,
        row.mine ? COLOR_ROW_SELF : COLOR_ROW, row.mine ? COLOR_COPPER_GOLD : COLOR_TEXT, y)
    }
    if (view.emptyText !== null) {
      y = this.drawHint(view.emptyText, y)
    }
    if (view.noticeText !== null) {
      y = this.drawHint(view.noticeText, y)
    }
    void y
    this.drawPager(view)
  }

  /**
   * 翻页：两个按钮 + 页号。灰掉的那一个不吃触摸（点了也不会发请求）。
   *
   * <p>纵向位置是**量出来的、不是按面板高度推的**：底部导航条占 -262..-210（与聊天面板那次
   * 同一条实测，见收口清单 #200），画在 -250 时两个按钮整块压在导航条后面 —— 玩家点不到，
   * 而「按钮画出来了」这类读数照样全绿。所以这里落在 -190（导航条上方 20）。
   */
  private drawPager(view: RankBoardView): void {
    const bottom = -190
    const label = this.createLabel(view.pageText, COLOR_TEXT_DIM, 18)
    this.node.addChild(label.node)
    label.node.setPosition(new Vec3(0, bottom, 0))
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    this.rows.push(label.node)

    this.drawPagerButton('上一页', -140, bottom, view.canPrev, () => this.onRankPage?.(-1))
    this.drawPagerButton('下一页', 140, bottom, view.canNext, () => this.onRankPage?.(1))
  }

  private drawPagerButton(text: string, x: number, y: number, enabled: boolean,
    onClick: () => void): void {
    const button = new Node('pager')
    this.node.addChild(button)
    const transform = button.addComponent(UITransform)
    transform.setContentSize(120, 36)
    button.setPosition(new Vec3(x, y, 0))
    const graphics = button.addComponent(Graphics)
    graphics.fillColor = enabled ? COLOR_ROW : COLOR_ROW_ALT
    graphics.rect(-60, -18, 120, 36)
    graphics.fill()

    const label = this.createLabel(text, enabled ? COLOR_TEXT : COLOR_TEXT_DIM, 18)
    button.addChild(label.node)
    label.node.setPosition(new Vec3(0, 0, 0))
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    if (enabled) {
      button.on('touch-start', () => onClick())
    }
    this.rows.push(button)
  }

  /** 页签条：五个页签等宽排开，当前那个用高亮底色（点击回调交给编排层）。 */
  private drawTabs(y: number): number {
    const tabs = this.rankView?.tabs ?? RANK_TABS.map(tab => ({
      key: tab.key, label: tab.label, active: tab.key === 'DETAIL',
    }))
    const width = (PANEL_WIDTH - PADDING * 2) / tabs.length
    tabs.forEach((tab, index) => {
      const node = new Node(`tab-${tab.key}`)
      this.node.addChild(node)
      const transform = node.addComponent(UITransform)
      transform.setContentSize(width - 4, 34)
      node.setPosition(new Vec3(
        -(PANEL_WIDTH - PADDING * 2) / 2 + width * index + width / 2, y - 17, 0))
      const graphics = node.addComponent(Graphics)
      graphics.fillColor = tab.active ? COLOR_TOTAL : COLOR_ROW
      graphics.rect(-(width - 4) / 2, -17, width - 4, 34)
      graphics.fill()

      const label = this.createLabel(tab.label, tab.active ? COLOR_COPPER_GOLD : COLOR_TEXT, 17)
      node.addChild(label.node)
      label.horizontalAlign = Label.HorizontalAlign.CENTER
      node.on('touch-start', () => this.onRankTab?.(tab.key))
      this.rows.push(node)
    })
    return y - 34 - 10
  }

  /**
   * 面板底板。
   *
   * <p>弹层必须自己画底板，不能依赖场景背景：这个面板会盖在城建、世界地图、
   * 战斗回放几种完全不同的场景上，靠场景背景透出来的话，
   * 文字在某些场景下会直接糊掉 —— 而这是一个专门用来解释数值的面板，读不清就等于没做。
   */
  private drawBackground(): void {
    const background = new Node('background')
    this.node.addChild(background)
    const transform = background.addComponent(UITransform)
    transform.setContentSize(PANEL_WIDTH, PANEL_HEIGHT)
    transform.setAnchorPoint(0.5, 0.5)
    const graphics = background.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-PANEL_WIDTH / 2, -PANEL_HEIGHT / 2, PANEL_WIDTH, PANEL_HEIGHT)
    graphics.fill()
    this.rows.push(background)
  }

  /** 清空上一次渲染的行节点。 */
  private clearRows(): void {
    for (const row of this.rows) {
      row.destroy()
    }
    this.rows.length = 0
  }

  private drawTitle(text: string, y: number): number {
    const label = this.createLabel(text, COLOR_COPPER_GOLD, 26)
    this.node.addChild(label.node)
    label.node.setPosition(new Vec3(0, y - ROW_HEIGHT / 2, 0))
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    this.rows.push(label.node)
    return y - ROW_HEIGHT - 6
  }

  private drawRow(labelText: string, valueText: string, background: Color,
    foreground: Color, y: number): number {
    const row = new Node('row')
    this.node.addChild(row)
    const transform = row.addComponent(UITransform)
    transform.setContentSize(PANEL_WIDTH - PADDING * 2, ROW_HEIGHT)
    transform.setAnchorPoint(0.5, 1)
    row.setPosition(new Vec3(0, y, 0))

    const graphics = row.addComponent(Graphics)
    graphics.fillColor = background
    graphics.rect(-(PANEL_WIDTH - PADDING * 2) / 2, -ROW_HEIGHT, PANEL_WIDTH - PADDING * 2, ROW_HEIGHT)
    graphics.fill()

    const name = this.createLabel(labelText, foreground, 20)
    row.addChild(name.node)
    name.node.setPosition(new Vec3(-(PANEL_WIDTH - PADDING * 2) / 2 + 12, -ROW_HEIGHT / 2, 0))
    name.horizontalAlign = Label.HorizontalAlign.LEFT

    const value = this.createLabel(valueText, foreground, 20)
    row.addChild(value.node)
    value.node.setPosition(new Vec3((PANEL_WIDTH - PADDING * 2) / 2 - 12, -ROW_HEIGHT / 2, 0))
    value.horizontalAlign = Label.HorizontalAlign.RIGHT

    this.rows.push(row)
    return y - ROW_HEIGHT - 4
  }

  private drawSeparator(y: number): number {
    const line = new Node('separator')
    this.node.addChild(line)
    const transform = line.addComponent(UITransform)
    transform.setContentSize(PANEL_WIDTH - PADDING * 2, 2)
    line.setPosition(new Vec3(0, y - 4, 0))
    const graphics = line.addComponent(Graphics)
    graphics.fillColor = COLOR_COPPER_GOLD
    graphics.rect(-(PANEL_WIDTH - PADDING * 2) / 2, -1, PANEL_WIDTH - PADDING * 2, 2)
    graphics.fill()
    this.rows.push(line)
    return y - 14
  }

  /**
   * 峰值记忆的解释文案。
   *
   * <p>按固定宽度粗分行：占位美术阶段没有富文本组件，而一段不换行的长文案会直接溢出面板。
   * 正式美术接入后应当换成引擎的自动换行，这里只保证「不溢出、能读完」。
   */
  private drawHint(text: string, y: number): number {
    const perLine = 22
    const lines: string[] = []
    for (let i = 0; i < text.length; i += perLine) {
      lines.push(text.slice(i, i + perLine))
    }
    for (const line of lines) {
      const label = this.createLabel(line, COLOR_HINT, 17)
      this.node.addChild(label.node)
      label.node.setPosition(new Vec3(0, y - 12, 0))
      label.horizontalAlign = Label.HorizontalAlign.CENTER
      this.rows.push(label.node)
      y -= 22
    }
    return y - 6
  }

  /**
   * 造一个 Label 但<b>不挂到任何父节点</b>：挂到哪儿由调用方决定
   * （明细行的两个 Label 属于行节点，标题与提示属于面板本身）。
   * 在这里就挂到 this.node 的话，调用方还得再挪一次，
   * 而 Cocos 的 addChild 会改变父节点，多一次挪动就多一次布局抖动。
   */
  private createLabel(text: string, color: Color, fontSize: number): Label {
    const node = new Node('label')
    node.addComponent(UITransform)
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = text
    label.color = color
    label.fontSize = fontSize
    label.lineHeight = fontSize + 6
    return label
  }

  override onDestroy(): void {
    this.clearRows()
  }
}

/** 面板尺寸对外暴露，供上层布局居中；不是游戏数值，只是美术常量。 */
export const POWER_PANEL_SIZE = { width: PANEL_WIDTH, height: PANEL_HEIGHT, rowHeight: ROW_HEIGHT }
