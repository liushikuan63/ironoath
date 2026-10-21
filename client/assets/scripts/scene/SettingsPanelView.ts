/**
 * 职责：设置页的渲染与点击（上线检查清单 §二 8/9：客服与退款入口设置页一级可见）。
 * 依赖：Cocos 运行时、{@code game/settings/SettingsPanel} 的纯组装结果。
 *
 * <p><b>本视图不做任何判定</b>（铁律 2）：哪几行、能不能点、点下去是打开客服还是说一句
 * 「本环境未配置」，全部来自 {@code buildSettingsView} —— 那边是纯函数、有 node:test 盯着。
 * 这里只负责把结论画出来、把点击转成回调。
 *
 * <p><b>为什么未配置的入口也要画出来并且可点</b>：提审会查「有没有这个入口」，
 * 藏起来等于没有；而点了没反应又会被当成 bug 报上来。所以未配置时它照样可点，
 * 只是反应变成一句说明。
 */

import { _decorator, Color, Component, Graphics, Label, Node, Size, UITransform, Vec2, Vec3, view as ccView } from 'cc'
import { buildSettingsView } from '../game/settings/SettingsPanel'
import type { SettingsRow } from '../game/settings/SettingsPanel'
import type { NoticeView } from '../game/notice/NoticePanel'
import type { PrivacyPlan } from '../game/privacy/PrivacyConsent'
import type { AppVersionResp } from '../net/generated/OpsProtocol'
import { applySystemUiFont, oneLineFloorHeight } from './UiFont'
import { applyDialogButton, applyPanelSurface, createDialogScroll } from './DialogStyle'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(24, 20, 18, 255)
const COLOR_ROW = new Color(40, 33, 28, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(158, 146, 128, 255)

const PANEL_WIDTH = 520
const PANEL_HEIGHT = 420
const ROW_HEIGHT = 56
const PADDING = 20
@ccclass('SettingsPanelView')
export class SettingsPanelView extends Component {
  private readonly rows: Node[] = []
  private viewportKey = ''

  /**
   * 当前显示的是设置还是公告。
   *
   * <p>**为什么切模式而不是另开一个面板**：另开面板要动导航（17 项）+ 面板节点 +
   * 可见性那一族探针；而公告是**设置页里的信息类条目**（与「联系客服 / 隐私政策」同类），
   * 在同一页里切一份内容 ⇒ 不新增导航项、不新增面板节点、探针那一族不受影响。
   */
  private mode: 'settings' | 'notices' = 'settings'

  /** 已取到的公告视图；null = 还没取过（「公告」那一行此时给一句说明，不给假摘要）。 */
  private noticeView: NoticeView | null = null

  /** 上一次 render 的参数 —— 切模式时要按同一组参数重画。 */
  private lastRender: {
    resp: AppVersionResp | null
    clientVersion: string
    privacy: PrivacyPlan
    audioMuted: boolean
  } | null = null

  /** 点「联系客服」或「申请退款」时触发；参数是那一行。 */
  onSupport: ((row: SettingsRow) => void) | null = null

  render(resp: AppVersionResp | null, clientVersion: string,
    privacy: PrivacyPlan = { request: false, contractName: null, apiAvailable: false },
    audioMuted = false): void {
    this.lastRender = { resp, clientVersion, privacy, audioMuted }
    this.redraw()
  }

  /**
   * 公告数据到手时调用（AppRoot 接线）：存下视图、切到公告模式、重画。
   *
   * <p>**为什么这里才切模式**：点「公告」那一行时只发起了请求，数据还没到；
   * 等数据到了再切 ⇒ 玩家不会看到「点了但什么都没变」的那种空面板。
   */
  attachNotices(view: NoticeView, changed: boolean): void {
    if (this.mode === 'notices' && !changed) return
    this.noticeView = view
    this.mode = 'notices'
    this.redraw()
  }

  override update(): void {
    if (this.lastRender === null) return
    const size = ccView.getVisibleSize()
    if (`${size.width}:${size.height}` !== this.viewportKey) this.redraw()
  }

  /** 按当前模式重画。 */
  private redraw(): void {
    const visible = ccView.getVisibleSize()
    this.viewportKey = `${visible.width}:${visible.height}`
    this.node.setScale(new Vec3(1, 1, 1))
    this.clearRows()
    if (this.mode === 'notices') {
      this.drawNotices()
      return
    }
    const last = this.lastRender
    const view = buildSettingsView(last?.resp ?? null, last?.clientVersion ?? '',
      last?.privacy, last?.audioMuted ?? false, this.noticeView)
    const compact = visible.height < 520
    const width = compact ? Math.min(760, visible.width - 48) : PANEL_WIDTH
    const height = compact ? Math.min(PANEL_HEIGHT, visible.height - 120) : PANEL_HEIGHT
    this.drawBackground(width, height)
    this.drawTitle('设置', height / 2 - PADDING, width)

    const rowHeight = compact ? 68 : ROW_HEIGHT
    const rowWidth = compact ? (width - PADDING * 3) / 2 : width - PADDING * 2
    view.rows.forEach((row, index) => {
      const column = compact ? index % 2 : 0
      const line = compact ? Math.floor(index / 2) : index
      const x = compact ? (column === 0 ? -(rowWidth + PADDING) / 2 : (rowWidth + PADDING) / 2) : 0
      const y = height / 2 - 104 - line * (compact ? rowHeight + 8 : rowHeight)
      this.drawActionRow(row, x, y, rowWidth, rowHeight - 8)
    })

    this.drawVersion(view.versionText, width, height)
  }

  /**
   * 公告模式的画面：固定标题和返回入口，中间用滚动净区容纳完整正文。
   * 横屏先收窄背景高度；正文按真实换行高度排，不能缩到读不清。
   */
  private drawNotices(): void {
    const notice = this.noticeView
    const rows = notice?.rows ?? []
    const height = Math.min(PANEL_HEIGHT, Math.max(220, ccView.getVisibleSize().height - 120))
    this.drawBackground(PANEL_WIDTH, height)

    this.drawTitle('运营公告', height / 2 - PADDING, PANEL_WIDTH)
    const viewportHeight = height - 144
    const scroll = createDialogScroll(this.node, 'NoticeRows', PANEL_WIDTH - 52,
      viewportHeight, 0, height / 2 - 80)
    this.rows.push(scroll.node)
    const cardWidth = PANEL_WIDTH - 72
    const textWidth = cardWidth - 40
    let y = 12

    if (rows.length === 0) {
      const card = this.noticeCard(scroll.content, 'notice-empty', cardWidth, 64, y)
      const empty = this.createLabel('暂无公告', COLOR_TEXT, 17, textWidth)
      card.addChild(empty.node)
      empty.node.setPosition(new Vec3(0, 0, 0))
      empty.horizontalAlign = Label.HorizontalAlign.CENTER
      y += 76
    } else {
      rows.forEach((row) => {
        const title = this.createWrappedLabel(row.title, COLOR_COPPER_GOLD, 20, textWidth)
        const body = this.createWrappedLabel(row.body, COLOR_TEXT, 16, textWidth)
        const titleHeight = title.node.getComponent(UITransform)!.height
        const bodyHeight = body.node.getComponent(UITransform)!.height
        const cardHeight = 18 + titleHeight + 12 + bodyHeight + 20
        const card = this.noticeCard(scroll.content, `notice-entry-${row.id}`, cardWidth, cardHeight, y)
        card.addChild(title.node)
        title.node.setPosition(new Vec3(-textWidth / 2, cardHeight / 2 - 18, 0))
        card.addChild(body.node)
        body.node.setPosition(new Vec3(-textWidth / 2, cardHeight / 2 - 18 - titleHeight - 12, 0))
        y += cardHeight + 12
      })
    }
    scroll.content.getComponent(UITransform)!.setContentSize(new Size(PANEL_WIDTH - 52,
      Math.max(viewportHeight, y)))
    scroll.content.setPosition(new Vec3(0, viewportHeight / 2, 0))
    scroll.scroll.scrollToOffset(new Vec2(0, 0), 0)
    this.drawBackRow(height)
  }

  private noticeCard(parent: Node, name: string, width: number, height: number, top: number): Node {
    const card = new Node(name)
    card.layer = this.node.layer
    parent.addChild(card)
    card.addComponent(UITransform).setContentSize(width, height)
    card.setPosition(new Vec3(0, -top - height / 2, 0))
    applyPanelSurface(card, 'ui.plate.band', width, height, 'NoticeEntrySurface')
    return card
  }

  /** 底部「返回设置」：点它切回设置模式（公告区收起）。 */
  private drawBackRow(height: number): void {
    const node = new Node('row-notice-back')
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(180, 40)
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-90, -20, 180, 40, 6)
    graphics.fill()
    applyDialogButton(node, true, 180, 40)
    node.setPosition(new Vec3(0, -height / 2 + 38, 0))

    const label = this.createLabel('返回设置', COLOR_TEXT, 18)
    node.addChild(label.node)
    label.node.setPosition(new Vec3(0, 0, 0))
    label.horizontalAlign = Label.HorizontalAlign.CENTER

    node.on('touch-end', () => {
      this.mode = 'settings'
      this.redraw()
    }, this)

    this.rows.push(node)
  }

  private drawBackground(width: number, height: number): void {
    const background = new Node('background')
    this.node.addChild(background)
    const transform = background.addComponent(UITransform)
    transform.setContentSize(width, height)
    transform.setAnchorPoint(0.5, 0.5)
    const graphics = background.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
    applyPanelSurface(background, 'ui.panel.gilt', width, height, 'SettingsSurface')
    this.rows.push(background)
  }

  private clearRows(): void {
    for (const row of this.rows) {
      row.destroy()
    }
    this.rows.length = 0
  }

  private drawTitle(text: string, y: number, width: number): void {
    const label = this.createLabel(text, COLOR_COPPER_GOLD, 27, width - 64)
    this.node.addChild(label.node)
    label.node.setPosition(new Vec3(0, y - 20, 0))
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    this.rows.push(label.node)
    const divider = new Node('header-divider')
    divider.layer = this.node.layer
    this.node.addChild(divider)
    divider.addComponent(UITransform).setContentSize(width - 80, 2)
    divider.setPosition(new Vec3(0, y - 53, 0))
    const line = divider.addComponent(Graphics)
    line.fillColor = COLOR_COPPER_GOLD
    line.rect(-(width - 80) / 2, -1, width - 80, 2)
    line.fill()
    this.rows.push(divider)
  }

  /**
   * 一行可点的入口：标题在上、说明在下。
   *
   * <p>触摸事件挂在行节点上，行必须有 UITransform 且锚点在中心 —— 少了它这一行收不到触摸，
   * 表现就是「点了没反应」，而那与"未配置"长得一模一样（本项目在世界地图 HUD 上踩过一次）。
   */
  private drawActionRow(row: SettingsRow, x: number, y: number, width: number, height: number): void {
    const node = new Node(`row-${row.key}`)
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(width, height)
    node.setPosition(new Vec3(x, y, 0))
    applyPanelSurface(node, 'ui.plate.band', width, height, 'SettingsRowSurface')

    const title = this.createLabel(row.title, COLOR_TEXT, 20, width - 24)
    node.addChild(title.node)
    title.node.setPosition(new Vec3(0, 12, 0))
    title.horizontalAlign = Label.HorizontalAlign.CENTER

    const subtitle = this.createLabel(row.subtitle, COLOR_TEXT_DIM, 13, width - 24)
    node.addChild(subtitle.node)
    subtitle.node.setPosition(new Vec3(0, -14, 0))
    subtitle.horizontalAlign = Label.HorizontalAlign.CENTER

    node.on('touch-end', () => {
      this.onSupport?.(row)
    }, this)

    this.rows.push(node)
  }

  private drawVersion(text: string, width: number, height: number): void {
    const label = this.createLabel(text, COLOR_TEXT_DIM, 13, 150)
    this.node.addChild(label.node)
    label.node.setPosition(new Vec3(width / 2 - 100, height / 2 - 26, 0))
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    this.rows.push(label.node)
  }

  /** 多行 Label 按内容增高，正文完整留在可滚动净区。 */
  private createWrappedLabel(text: string, color: Color, size: number, width: number): Label {
    const node = new Node('wrapped-label')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(width, size + 8)
    node.getComponent(UITransform)!.setAnchorPoint(0, 1)
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = text
    label.fontSize = size
    label.lineHeight = size + 6
    label.color = color
    label.verticalAlign = Label.VerticalAlign.TOP
    label.overflow = Label.Overflow.RESIZE_HEIGHT
    label.enableWrapText = true
    label.horizontalAlign = Label.HorizontalAlign.LEFT
    label.updateRenderData(true)
    return label
  }

  private createLabel(text: string, color: Color, size: number, width = PANEL_WIDTH - PADDING * 2): Label {
    const node = new Node('label')
    node.layer = this.node.layer
    // 盒高用 `oneLineFloorHeight`：这里原本是 `size + 8`，而 SHRINK 的盒高就是字形缩放系数，
    // 于是每一行都常态性小一号（台账 #367 点名 10 行，最狠的 13 号字只画到 21/30）。
    // 同时**不再显式设 lineHeight** —— 单行 Label 用它只会让"自然行高"多一个变量，
    // 引擎默认值参与算出来的高度才与 `oneLineFloorHeight` 的实测口径一致。
    node.addComponent(UITransform).setContentSize(width, oneLineFloorHeight())
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = text
    label.fontSize = size
    label.color = color
    // 盒高变大了，对齐必须跟着钉成居中：这里原本是默认的 TOP 对齐，字形贴在盒子上沿，
    // 只抬盒高就会把整行字往上挪。居中锚 + CENTER 对齐才做到"改了盒子、字形一格都不动"。
    label.verticalAlign = Label.VerticalAlign.CENTER
    label.overflow = Label.Overflow.SHRINK
    return label
  }
}
