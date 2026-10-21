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

import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3 } from 'cc'
import { buildSettingsView } from '../game/settings/SettingsPanel'
import type { SettingsRow } from '../game/settings/SettingsPanel'
import type { NoticeView } from '../game/notice/NoticePanel'
import type { PrivacyPlan } from '../game/privacy/PrivacyConsent'
import type { AppVersionResp } from '../net/generated/OpsProtocol'
import { applySystemUiFont, oneLineFloorHeight } from './UiFont'

const { ccclass } = _decorator

const COLOR_BACKGROUND = new Color(24, 20, 18, 255)
const COLOR_ROW = new Color(40, 33, 28, 255)
const COLOR_ROW_ALT = new Color(48, 39, 33, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(158, 146, 128, 255)

const PANEL_WIDTH = 520
const PANEL_HEIGHT = 420
const ROW_HEIGHT = 72
const PADDING = 20
/** 一条公告正文占的高度（多行盒高）。 */
const NOTICE_BODY_HEIGHT = 56

@ccclass('SettingsPanelView')
export class SettingsPanelView extends Component {
  private readonly rows: Node[] = []

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
  attachNotices(view: NoticeView): void {
    this.noticeView = view
    this.mode = 'notices'
    this.redraw()
  }

  /** 按当前模式重画。 */
  private redraw(): void {
    this.clearRows()
    if (this.mode === 'notices') {
      this.drawNotices()
      return
    }
    const last = this.lastRender
    const view = buildSettingsView(last?.resp ?? null, last?.clientVersion ?? '',
      last?.privacy, last?.audioMuted ?? false, this.noticeView)
    this.drawBackground(PANEL_HEIGHT)

    let y = PANEL_HEIGHT / 2 - PADDING
    y = this.drawTitle('设置', y)

    view.rows.forEach((row, index) => {
      y = this.drawActionRow(row, index % 2 === 0 ? COLOR_ROW : COLOR_ROW_ALT, y)
    })

    this.drawVersion(view.versionText)
  }

  /**
   * 公告模式的画面：标题 + 每条公告（标题单行 + 正文多行）+ 底部「返回设置」。
   *
   * <p>正文用**多行** Label（`enableWrapText`）：公告正文长度不固定，单行会被压成极小的字。
   * 没有公告时画一句「暂无公告」—— 而不是画一块空白（空面板与「没加载」长得一样）。
   */
  private drawNotices(): void {
    const view = this.noticeView
    const rows = view?.rows ?? []
    const bodyHeight = rows.length === 0 ? 0 : rows.length * NOTICE_BODY_HEIGHT
    const height = Math.max(PANEL_HEIGHT, 140 + bodyHeight)
    this.drawBackground(height)

    let y = height / 2 - PADDING
    y = this.drawTitle('公告', y)

    if (rows.length === 0) {
      const empty = this.createLabel('暂无公告', COLOR_TEXT_DIM, 15)
      this.node.addChild(empty.node)
      empty.node.setPosition(new Vec3(0, y - 30, 0))
      empty.horizontalAlign = Label.HorizontalAlign.CENTER
      this.rows.push(empty.node)
      y -= 60
    } else {
      rows.forEach((row) => {
        const title = this.createLabel(row.title, COLOR_TEXT, 18)
        this.node.addChild(title.node)
        title.node.setPosition(new Vec3(0, y - 20, 0))
        title.horizontalAlign = Label.HorizontalAlign.CENTER
        this.rows.push(title.node)
        y -= 34

        const body = this.createWrappedLabel(row.body, COLOR_TEXT_DIM, 13, NOTICE_BODY_HEIGHT)
        this.node.addChild(body.node)
        body.node.setPosition(new Vec3(0, y - NOTICE_BODY_HEIGHT / 2 + 4, 0))
        body.horizontalAlign = Label.HorizontalAlign.LEFT
        this.rows.push(body.node)
        y -= NOTICE_BODY_HEIGHT
      })
    }

    this.drawBackRow(y)
  }

  /** 底部「返回设置」：点它切回设置模式（公告区收起）。 */
  private drawBackRow(y: number): void {
    const node = new Node('row-notice-back')
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(PANEL_WIDTH - PADDING * 2, ROW_HEIGHT - 8)
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = COLOR_ROW
    graphics.roundRect(-(PANEL_WIDTH - PADDING * 2) / 2, -(ROW_HEIGHT - 8) / 2,
      PANEL_WIDTH - PADDING * 2, ROW_HEIGHT - 8, 6)
    graphics.fill()
    node.setPosition(new Vec3(0, y - ROW_HEIGHT / 2, 0))

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

  private drawBackground(height: number): void {
    const background = new Node('background')
    this.node.addChild(background)
    const transform = background.addComponent(UITransform)
    transform.setContentSize(PANEL_WIDTH, height)
    transform.setAnchorPoint(0.5, 0.5)
    const graphics = background.addComponent(Graphics)
    graphics.fillColor = COLOR_BACKGROUND
    graphics.rect(-PANEL_WIDTH / 2, -height / 2, PANEL_WIDTH, height)
    graphics.fill()
    this.rows.push(background)
  }

  private clearRows(): void {
    for (const row of this.rows) {
      row.destroy()
    }
    this.rows.length = 0
  }

  private drawTitle(text: string, y: number): number {
    const label = this.createLabel(text, COLOR_COPPER_GOLD, 26)
    this.node.addChild(label.node)
    label.node.setPosition(new Vec3(0, y - 20, 0))
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    this.rows.push(label.node)
    return y - 56
  }

  /**
   * 一行可点的入口：标题在上、说明在下。
   *
   * <p>触摸事件挂在行节点上，行必须有 UITransform 且锚点在中心 —— 少了它这一行收不到触摸，
   * 表现就是「点了没反应」，而那与"未配置"长得一模一样（本项目在世界地图 HUD 上踩过一次）。
   */
  private drawActionRow(row: SettingsRow, background: Color, y: number): number {
    const node = new Node(`row-${row.key}`)
    this.node.addChild(node)
    node.addComponent(UITransform).setContentSize(PANEL_WIDTH - PADDING * 2, ROW_HEIGHT - 8)
    const graphics = node.addComponent(Graphics)
    graphics.fillColor = background
    graphics.roundRect(-(PANEL_WIDTH - PADDING * 2) / 2, -(ROW_HEIGHT - 8) / 2,
      PANEL_WIDTH - PADDING * 2, ROW_HEIGHT - 8, 6)
    graphics.fill()
    node.setPosition(new Vec3(0, y - ROW_HEIGHT / 2, 0))

    const title = this.createLabel(row.title, COLOR_TEXT, 20)
    node.addChild(title.node)
    title.node.setPosition(new Vec3(0, 12, 0))
    title.horizontalAlign = Label.HorizontalAlign.CENTER

    const subtitle = this.createLabel(row.subtitle, COLOR_TEXT_DIM, 13)
    node.addChild(subtitle.node)
    subtitle.node.setPosition(new Vec3(0, -14, 0))
    subtitle.horizontalAlign = Label.HorizontalAlign.CENTER

    node.on('touch-end', () => {
      this.onSupport?.(row)
    }, this)

    this.rows.push(node)
    return y - ROW_HEIGHT
  }

  private drawVersion(text: string): void {
    const label = this.createLabel(text, COLOR_TEXT_DIM, 13)
    this.node.addChild(label.node)
    label.node.setPosition(new Vec3(0, -PANEL_HEIGHT / 2 + PADDING + 6, 0))
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    this.rows.push(label.node)
  }

  /**
   * 多行 Label（公告正文用）：`enableWrapText` 让长文本换行，`SHRINK` 在盒高内缩字号。
   *
   * <p>为什么不用 `createLabel`：它是**单行**盒子（`oneLineFloorHeight()`），长正文会被压成极小的字。
   */
  private createWrappedLabel(text: string, color: Color, size: number, boxHeight: number): Label {
    const node = new Node('wrapped-label')
    node.layer = this.node.layer
    node.addComponent(UITransform).setContentSize(PANEL_WIDTH - PADDING * 2, boxHeight)
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = text
    label.fontSize = size
    label.color = color
    label.verticalAlign = Label.VerticalAlign.TOP
    label.overflow = Label.Overflow.SHRINK
    label.enableWrapText = true
    return label
  }

  private createLabel(text: string, color: Color, size: number): Label {
    const node = new Node('label')
    node.layer = this.node.layer
    // 盒高用 `oneLineFloorHeight`：这里原本是 `size + 8`，而 SHRINK 的盒高就是字形缩放系数，
    // 于是每一行都常态性小一号（台账 #367 点名 10 行，最狠的 13 号字只画到 21/30）。
    // 同时**不再显式设 lineHeight** —— 单行 Label 用它只会让"自然行高"多一个变量，
    // 引擎默认值参与算出来的高度才与 `oneLineFloorHeight` 的实测口径一致。
    node.addComponent(UITransform).setContentSize(PANEL_WIDTH - PADDING * 2, oneLineFloorHeight())
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
