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

@ccclass('SettingsPanelView')
export class SettingsPanelView extends Component {
  private readonly rows: Node[] = []

  /** 点「联系客服」或「申请退款」时触发；参数是那一行。 */
  onSupport: ((row: SettingsRow) => void) | null = null

  render(resp: AppVersionResp | null, clientVersion: string,
    privacy: PrivacyPlan = { request: false, contractName: null, apiAvailable: false },
    audioMuted = false): void {
    this.clearRows()
    const view = buildSettingsView(resp, clientVersion, privacy, audioMuted)
    this.drawBackground()

    let y = PANEL_HEIGHT / 2 - PADDING
    y = this.drawTitle('设置', y)

    view.rows.forEach((row, index) => {
      y = this.drawActionRow(row, index % 2 === 0 ? COLOR_ROW : COLOR_ROW_ALT, y)
    })

    this.drawVersion(view.versionText)
  }

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

  private createLabel(text: string, color: Color, size: number): Label {
    const node = new Node('label')
    node.layer = this.node.layer
    // 盒高用 `oneLineFloorHeight`：这里原本是 `size + 8`，而 SHRINK 的盒高就是字形缩放系数，
    // 于是每一行都常态性小一号（台账 #367 点名 10 行，最狠的 13 号字只画到 21/30）。
    // 同时**不再显式设 lineHeight** —— 单行 Label 用它只会让"自然行高"多一个变量，
    // 引擎默认值参与算出来的高度才与 `oneLineFloorHeight` 的实测口径一致。
    node.addComponent(UITransform).setContentSize(PANEL_WIDTH - PADDING * 2, oneLineFloorHeight(size))
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
