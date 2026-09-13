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
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(158, 146, 128, 255)
const COLOR_HINT = new Color(120, 168, 196, 255)

const PANEL_WIDTH = 520
const PANEL_HEIGHT = 620
const ROW_HEIGHT = 40
const PADDING = 20

@ccclass('PowerPanelView')
export class PowerPanelView extends Component {

  private readonly rows: Node[] = []

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
    this.clearRows()
    const view = buildPowerPanel(resp)
    this.drawBackground()

    let y = PANEL_HEIGHT / 2 - PADDING
    y = this.drawTitle('战力明细', y)

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
