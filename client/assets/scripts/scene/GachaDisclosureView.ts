/**
 * 职责：抽卡概率公示面板（B06 §6 合规、验收 3）—— 把 buildDisclosure 组装好的内容原样摆出来。
 * 依赖：cc（渲染）、game/gacha/GachaDisclosure（数据组装，已单测）。
 *
 * <p><b>本场景不做任何概率计算</b>：面板上的每个数字都由 buildDisclosure 从 gacha 表搬来，
 * 并且它内部已经断言过「四档之和恰为 100%」「公示文案非空」「计价方式恰好一种」。
 * 场景只负责把这些字符串画到屏幕上，一个字符都不改。
 *
 * <p><b>合规红线：disclosureText 必须原文完整呈现，不得删减、折叠或以图标替代</b>
 * （gacha 表明文要求）。所以本场景刻意不提供「精简版」「展开/收起」按钮 ——
 * 提供了就一定会有人用，而用了就是不合规。文本框用 RESIZE_HEIGHT 让它随内容长高，
 * 而不是用 SHRINK 把字缩小到看不清（缩小到不可读，在监管口径上等同于删减）。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：.scene / .prefab 资产、承载长文本的 ScrollView、
 * 正式美术。占位期用 Graphics 色块 + Label，与 MainCity / WorldMap 同一套做法。
 */

import { _decorator, Color, Component, Graphics, Label, Node, Size, UITransform, Vec3, view } from 'cc'
import type { GachaDisclosure, Rarity } from '../game/gacha/GachaDisclosure'

const { ccclass } = _decorator

/** 配色沿用 B00「铜金 + 暗红」的题材调性。美术方向常量，不是游戏数值。 */
const COLOR_BACKGROUND = new Color(20, 17, 15, 255)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_ROW = new Color(52, 43, 35, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
/** 稀有度配色。SSR 用铜金，往下依次降饱和度 —— 玩家扫一眼就知道哪档值钱 */
const COLOR_SSR = new Color(232, 190, 92, 255)
const COLOR_SR = new Color(168, 122, 196, 255)
const COLOR_R = new Color(96, 140, 196, 255)
const COLOR_N = new Color(140, 134, 124, 255)

const PANEL_WIDTH = 620
const HEADER_HEIGHT = 64
const ROW_HEIGHT = 40
const FOOTER_LINE_HEIGHT = 26
const PADDING = 20

@ccclass('GachaDisclosureView')
export class GachaDisclosureView extends Component {
  private disclosure: GachaDisclosure | null = null
  private pending: GachaDisclosure | null = null

  private readonly rowNodes: Node[] = []
  private titleLabel: Label | null = null
  private footerLabel: Label | null = null
  private disclosureLabel: Label | null = null
  private panelGraphics: Graphics | null = null

  override onLoad(): void {
    this.buildPanel()
    if (this.pending !== null) {
      const pending = this.pending
      this.pending = null
      this.attach(pending)
    }
  }

  /**
   * 装载一个卡池的公示内容。
   *
   * @param disclosure buildDisclosure 的产物。<b>不要在这里重新组装</b> ——
   *                   组装逻辑（含四档求和断言）只有一份，在 game/gacha/GachaDisclosure.ts
   */
  attach(disclosure: GachaDisclosure): void {
    if (this.panelGraphics === null) {
      this.pending = disclosure
      return
    }
    this.disclosure = disclosure
    this.render()
  }

  // ---------- 搭建 ----------

  private buildPanel(): void {
    const size = view.getVisibleSize()
    const background = new Node('Background')
    background.layer = this.node.layer
    this.node.addChild(background)
    background.addComponent(UITransform).setContentSize(new Size(size.width, size.height))
    const backgroundGraphics = background.addComponent(Graphics)
    backgroundGraphics.fillColor = COLOR_BACKGROUND
    backgroundGraphics.rect(-size.width / 2, -size.height / 2, size.width, size.height)
    backgroundGraphics.fill()

    const panel = new Node('Panel')
    panel.layer = this.node.layer
    this.node.addChild(panel)
    panel.addComponent(UITransform)
    this.panelGraphics = panel.addComponent(Graphics)

    this.titleLabel = this.addLabel(panel, 'Title', 0, 0, COLOR_COPPER_GOLD, 26)

    // 四档概率行。档位数由 GachaDisclosure.tiers 决定，这里按上限建好再按需显隐：
    // 每次 attach 都重建节点会造成不必要的分配，而档位数量在协议里是固定的四档
    for (let index = 0; index < 4; index++) {
      const row = new Node(`TierRow_${index}`)
      row.layer = panel.layer
      panel.addChild(row)
      row.addComponent(UITransform).setContentSize(new Size(PANEL_WIDTH - PADDING * 2, ROW_HEIGHT))
      const graphics = row.addComponent(Graphics)
      graphics.fillColor = COLOR_ROW
      graphics.rect(-(PANEL_WIDTH - PADDING * 2) / 2, -ROW_HEIGHT / 2, PANEL_WIDTH - PADDING * 2, ROW_HEIGHT)
      graphics.fill()
      const rarity = this.addLabel(row, 'Rarity', -(PANEL_WIDTH - PADDING * 2) / 2 + 16, 0, COLOR_TEXT, 20)
      rarity.horizontalAlign = Label.HorizontalAlign.LEFT
      const rate = this.addLabel(row, 'Rate', (PANEL_WIDTH - PADDING * 2) / 2 - 16, 0, COLOR_TEXT, 20)
      rate.horizontalAlign = Label.HorizontalAlign.RIGHT
      this.rowNodes.push(row)
    }

    this.footerLabel = this.addLabel(panel, 'Footer', 0, 0, COLOR_TEXT_DIM, 18)

    // 合规原文：左对齐 + RESIZE_HEIGHT，让它随内容长高而不是被压缩或截断
    this.disclosureLabel = this.addLabel(panel, 'DisclosureText', 0, 0, COLOR_TEXT, 16)
    this.disclosureLabel.horizontalAlign = Label.HorizontalAlign.LEFT
    this.disclosureLabel.verticalAlign = Label.VerticalAlign.TOP
    this.disclosureLabel.overflow = Label.Overflow.RESIZE_HEIGHT
    this.disclosureLabel.node.addComponent(UITransform).setContentSize(
      new Size(PANEL_WIDTH - PADDING * 2, ROW_HEIGHT))
  }

  private addLabel(parent: Node, name: string, x: number, y: number, color: Color, fontSize: number): Label {
    const node = new Node(name)
    node.layer = parent.layer
    parent.addChild(node)
    node.addComponent(UITransform)
    node.setPosition(new Vec3(x, y, 0))
    const label = node.addComponent(Label)
    label.string = ''
    label.color = color
    label.fontSize = fontSize
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    return label
  }

  // ---------- 渲染 ----------

  private render(): void {
    const disclosure = this.disclosure
    if (disclosure === null || this.titleLabel === null || this.footerLabel === null
        || this.disclosureLabel === null || this.panelGraphics === null) {
      return
    }

    this.titleLabel.string = `${disclosure.poolName} · 概率公示`

    disclosure.tiers.forEach((tier, index) => {
      const row = this.rowNodes[index]
      if (row === undefined) {
        return
      }
      row.active = true
      const rarity = row.children[0]?.getComponent(Label)
      const rate = row.children[1]?.getComponent(Label)
      if (rarity !== undefined && rarity !== null) {
        rarity.string = tier.rarity
        rarity.color = tierColor(tier.rarity)
      }
      if (rate !== undefined && rate !== null) {
        // text 是 buildDisclosure 用整数运算算好的，直接展示；
        // 这里绝不再拿 rateFixed 自己除一遍 —— 那会引入 double，0.02 会变成 1.9999999%
        rate.string = tier.text
      }
    })
    // 协议只有四档，但如果哪天配置里少了档位，多出来的行必须藏起来而不是显示空白
    for (let index = disclosure.tiers.length; index < this.rowNodes.length; index++) {
      const row = this.rowNodes[index]
      if (row !== undefined) {
        row.active = false
      }
    }

    this.footerLabel.string = [
      `保底：SSR ${disclosure.ssrPity} 抽 / SR ${disclosure.srPity} 抽`,
      `单抽消耗：${disclosure.costText}`,
      disclosure.lifetimeLimitText,
    ].join('\n')

    // 原文照搬，不做任何加工
    this.disclosureLabel.string = disclosure.disclosureText

    this.layout(disclosure)
  }

  /**
   * 按内容实际高度排版。
   *
   * <p>面板高度必须由 disclosureText 的行数决定而不是写死：合规原文长短由策划填，
   * 写死高度的结果是长文案被裁掉 —— 那就是「删减」，直接违规。
   */
  private layout(disclosure: GachaDisclosure): void {
    const graphics = this.panelGraphics
    if (graphics === null) {
      return
    }
    const disclosureHeight = estimateTextHeight(disclosure.disclosureText, PANEL_WIDTH - PADDING * 2, 16)
    const footerHeight = FOOTER_LINE_HEIGHT * 3
    const total = HEADER_HEIGHT + PADDING
      + this.rowNodes.length * ROW_HEIGHT + PADDING
      + footerHeight + PADDING
      + disclosureHeight + PADDING

    const panel = graphics.node
    panel.getComponent(UITransform)?.setContentSize(new Size(PANEL_WIDTH, total))
    graphics.clear()
    graphics.fillColor = COLOR_PANEL
    graphics.roundRect(-PANEL_WIDTH / 2, -total / 2, PANEL_WIDTH, total, 10)
    graphics.fill()

    let cursor = total / 2 - PADDING
    this.titleLabel?.node.setPosition(new Vec3(0, cursor - HEADER_HEIGHT / 2, 0))
    cursor -= HEADER_HEIGHT + PADDING

    for (const row of this.rowNodes) {
      cursor -= ROW_HEIGHT / 2
      row.setPosition(new Vec3(0, cursor, 0))
      cursor -= ROW_HEIGHT / 2
    }
    cursor -= PADDING

    if (this.footerLabel !== null) {
      cursor -= footerHeight / 2
      this.footerLabel.node.setPosition(new Vec3(0, cursor, 0))
      this.footerLabel.node.getComponent(UITransform)?.setContentSize(
        new Size(PANEL_WIDTH - PADDING * 2, footerHeight))
      this.footerLabel.verticalAlign = Label.VerticalAlign.TOP
      cursor -= footerHeight / 2 + PADDING
    }

    if (this.disclosureLabel !== null) {
      this.disclosureLabel.node.getComponent(UITransform)?.setContentSize(
        new Size(PANEL_WIDTH - PADDING * 2, disclosureHeight))
      cursor -= disclosureHeight / 2
      this.disclosureLabel.node.setPosition(new Vec3(0, cursor, 0))
    }
  }
}

function tierColor(rarity: Rarity): Color {
  switch (rarity) {
    case 'SSR': return COLOR_SSR
    case 'SR': return COLOR_SR
    case 'R': return COLOR_R
    case 'N': return COLOR_N
    default: return COLOR_TEXT
  }
}

/**
 * 估算一段文本在给定宽度下需要多高。
 *
 * <p>这是<b>排版</b>估算，不是游戏数值：中文字符按字号见方，其余按半宽。
 * 估小了会让合规原文被裁掉（违规），所以刻意向上取整并多留一行余量 ——
 * 宁可面板长一点，也不能少显示一个字。
 */
function estimateTextHeight(text: string, width: number, fontSize: number): number {
  const lineHeight = Math.ceil(fontSize * 1.4)
  let lines = 0
  for (const paragraph of text.split('\n')) {
    let columnWidth = 0
    let count = 1
    for (const character of paragraph) {
      const charWidth = character.charCodeAt(0) > 0x2e80 ? fontSize : Math.ceil(fontSize / 2)
      if (columnWidth + charWidth > width) {
        count++
        columnWidth = charWidth
      } else {
        columnWidth += charWidth
      }
    }
    lines += count
  }
  return (lines + 1) * lineHeight
}
