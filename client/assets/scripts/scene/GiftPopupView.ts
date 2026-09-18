/**
 * 职责：礼包弹窗（B19 S3-iv 的界面半边）—— 把 `/gift/popup` 的答案与购买结果画出来。
 * 依赖：cc（渲染）、生成的协议类型、`PayView`（状态机给的显示内容）。本视图**不做任何判断**：
 * 弹不弹、能买不能买、该显示哪句话都由服务端与 `GiftPayFlow` 决定。
 *
 * <p><b>必须在 Cocos 编辑器里补的部分</b>：场景里的 `giftPopup` 节点（GameBootstrap 按名字找它）
 * 与正式美术。占位期用 Graphics 色块 + Label，与其它面板同一套做法 —— 节点没建之前本面板
 * 只是"没挂上"，不会让启动失败。
 *
 * <p><b>倒计时读服务端时刻</b>（`offerExpireAt - serverNow`）：客户端时钟可以改，
 * 用它算倒计时会让"还剩几分钟"变成一句随时会错的话。
 */
import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3 } from 'cc'
import type { GiftPopupResp } from '../net/generated/PayProtocol'
import type { PayView } from '../game/pay/GiftPayFlow'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

const COLOR_MASK = new Color(0, 0, 0, 160)
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_BUTTON = new Color(122, 82, 24, 255)

const PANEL_WIDTH = 460
const PANEL_HEIGHT = 300

@ccclass('GiftPopupView')
export class GiftPopupView extends Component {
  /** 点"购买"：把商品 id 交给编排层（`AppRoot.buyGift`）。 */
  onBuy: ((productId: string) => void) | null = null
  /** 点"关闭"或遮罩：面板自己藏起来，不打扰编排层。 */
  onClose: (() => void) | null = null

  private mask: Node | null = null
  private title: Label | null = null
  private subtitle: Label | null = null
  private countdown: Label | null = null
  private result: Label | null = null
  private buyButton: Node | null = null

  /** 服务端说"这一刻要弹"：画出来并显示。 */
  attach(resp: GiftPopupResp): void {
    if (!resp.popup) {
      this.hide()
      return
    }
    this.ensureNodes()
    if (this.title !== null) {
      this.title.string = '限时礼包'
    }
    if (this.subtitle !== null) {
      // 价格与内容都以服务端下发的这一档为准，客户端不内置
      this.subtitle.string = `商品 ${resp.productId ?? ''}`
    }
    if (this.result !== null) {
      this.result.string = ''
    }
    this.setBuyEnabled(true)
    this.updateCountdown(resp)
    this.node.active = true
  }

  /** 购买结果：由 `GiftPayFlow` 给出的那句话，原样显示。 */
  renderResult(view: PayView): void {
    if (!this.node.active) {
      return
    }
    if (this.result !== null) {
      this.result.string = view.detail === null ? view.title : `${view.title}\n${view.detail}`
    }
    // 只有"已到账"和"失败/取消"才是终点；处理中也要把按钮收起来，避免重复下单
    this.setBuyEnabled(false)
  }

  /** 面板藏起来（关闭按钮、或服务端说这一屏不弹）。 */
  hide(): void {
    this.node.active = false
  }

  private updateCountdown(resp: GiftPopupResp): void {
    if (this.countdown === null || resp.offerExpireAt === null) {
      return
    }
    const remainMs = resp.offerExpireAt - resp.serverNow
    if (remainMs <= 0) {
      // 报价过期＝这一屏不该再显示购买入口（服务端也会拒单，这里只是不骗玩家）
      this.countdown.string = '本次报价已过期'
      this.setBuyEnabled(false)
      return
    }
    const minutes = Math.floor(remainMs / 60_000)
    const seconds = Math.floor((remainMs % 60_000) / 1_000)
    this.countdown.string = `剩 ${minutes}:${seconds.toString().padStart(2, '0')}`
  }

  private setBuyEnabled(enabled: boolean): void {
    if (this.buyButton !== null) {
      this.buyButton.active = enabled
    }
  }

  /** 占位期节点全用代码建（场景里只留一个挂本组件的空节点）。 */
  private ensureNodes(): void {
    if (this.mask !== null) {
      return
    }
    const root = this.node
    root.addComponent(UITransform).setContentSize(PANEL_WIDTH, PANEL_HEIGHT)

    this.mask = new Node('mask')
    this.mask.addComponent(UITransform).setContentSize(2_000, 2_000)
    const maskBg = this.mask.addComponent(Graphics)
    maskBg.fillColor = COLOR_MASK
    maskBg.fillRect(-1_000, -1_000, 2_000, 2_000)
    root.addChild(this.mask)

    const panel = new Node('panel')
    panel.addComponent(UITransform).setContentSize(PANEL_WIDTH, PANEL_HEIGHT)
    const bg = panel.addComponent(Graphics)
    bg.fillColor = COLOR_PANEL
    bg.fillRect(-PANEL_WIDTH / 2, -PANEL_HEIGHT / 2, PANEL_WIDTH, PANEL_HEIGHT)
    root.addChild(panel)

    this.title = this.label(panel, '礼包', 0, 96, 26, COLOR_COPPER_GOLD)
    this.subtitle = this.label(panel, '', 0, 56, 18, COLOR_TEXT)
    this.countdown = this.label(panel, '', 0, 24, 16, COLOR_TEXT_DIM)
    this.result = this.label(panel, '', 0, -20, 16, COLOR_TEXT)

    this.buyButton = new Node('buy')
    this.buyButton.addComponent(UITransform).setContentSize(200, 56)
    const buttonBg = this.buyButton.addComponent(Graphics)
    buttonBg.fillColor = COLOR_BUTTON
    buttonBg.fillRect(-100, -28, 200, 56)
    // label() 自己把节点挂到 parent 上并返回 Label 组件 —— 再 addChild 一次挂的就是
    // 一个组件而不是节点（真机上是 addChild 直接抛错，而这一步只有真正弹过窗才会走到）
    this.label(this.buyButton, '立即购买', 0, 0, 20, COLOR_TEXT)
    panel.addChild(this.buyButton)

    const close = new Node('close')
    close.addComponent(UITransform).setContentSize(36, 36)
    close.setPosition(new Vec3(PANEL_WIDTH / 2 - 28, PANEL_HEIGHT / 2 - 28, 0))
    this.label(close, '×', 0, 0, 22, COLOR_TEXT_DIM)
    panel.addChild(close)

    this.node.active = false
  }

  private label(parent: Node, text: string, x: number, y: number, size: number,
                color: Color): Label {
    const node = new Node('label')
    node.setPosition(new Vec3(x, y, 0))
    const label = node.addComponent(Label)
    label.string = text
    label.fontSize = size
    label.lineHeight = Math.round(size * 1.4)
    label.color = color
    applySystemUiFont(label)
    parent.addChild(node)
    return label
  }
}
