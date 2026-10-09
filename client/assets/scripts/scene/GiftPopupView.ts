/**
 * 职责：礼包弹窗（B19 S3-iv 的界面半边）—— 把 `/gift/popup` 的答案与购买结果画出来。
 * 依赖：cc（渲染）、生成的协议类型、`PayView`（状态机给的显示内容）。本视图**不做任何判断**：
 * 弹不弹、能买不能买、该显示哪句话都由服务端与 `GiftPayFlow` 决定。
 *
 * <p><b>宿主节点由代码建</b>：本工程的场景文件里没有任何面板节点（十五个面板都是
 * `PanelNav` 按清单建出来的），而礼包弹窗不在导航条上，所以它的宿主由
 * `GameBootstrap.mountGiftPopup()` 建 —— 原注释写的「在编辑器里补 `giftPopup` 节点」
 * 在这个工程里行不通，而缺了它的症状是**这一屏永远不出现**（`/gift/popup` 的答案没地方画），
 * 不是崩溃：单测全绿、启动自检行里那句 `missing="giftPopup"` 才是唯一线索。
 * 占位期节点内全用 Graphics 色块 + Label，与其它面板同一套做法；正式美术另开一格。
 *
 * <p><b>倒计时读服务端时刻</b>（`offerExpireAt - serverNow`）：客户端时钟可以改，
 * 用它算倒计时会让"还剩几分钟"变成一句随时会错的话。
 */
import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3, type EventTouch } from 'cc'
import type { GiftPopupResp } from '../net/generated/PayProtocol'
import type { PayView } from '../game/pay/GiftPayFlow'
import { applySystemUiFont } from './UiFont'
import { applySlicedSprite } from './ArtCatalog'
import { PANEL_IRON_INSET } from '../game/art/ArtFamilies'
import { DIALOG_SCRIM, fitExistingDialog } from './DialogStyle'

const { ccclass } = _decorator

const COLOR_MASK = DIALOG_SCRIM
const COLOR_PANEL = new Color(40, 33, 27, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_TEXT_DIM = new Color(150, 140, 124, 255)
const COLOR_BUTTON = new Color(122, 82, 24, 255)

// 460×300 → 520×360：A 档 border 抬到 80·72 之后，300 高只剩 156px 净区，
// 装不下「标题 + 显示名 + 倒计时 + 分隔线 + 按钮 + 结果」这六段（1:1 截图上分隔线与按钮盒相接）。
// 规格 §七 Q6 给的退路就是抬高这一档面板，而不是把 border 调回去（调回去等于重新让切分线穿过角帽）。
const PANEL_WIDTH = 520
const PANEL_HEIGHT = 360

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
  private layoutDialog: (() => void) | null = null
  /**
   * 这一档要下单的**商品 id**（`pay_product.id`，服务端随弹窗下发）。
   * 只用来把 `onBuy` 的入参递回去，**绝不上屏**（把 `gift_stuck_supply` 印给玩家是 #255/#268 那一族）。
   */
  private productId: string | null = null

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
      // 显示名由服务端下发（`pay_product.name`，如「落成贺礼」）——客户端不查表，
      // 也**绝不**退回印 `productId`：把「商品 gift_building_celebration」印给玩家
      // 是 #255/#268 同族的缺陷（2026-09-21 复检在建造完成弹窗上抓到的形态）。
      // 旧服务端不下发这一位时留空，用一句空话代替一句内部编号。
      this.subtitle.string = resp.productName ?? ''
    }
    // 下单入参只能取自本次弹窗（常驻挂件式的"记住上一个"会被频控绕开）
    this.productId = resp.productId
    if (this.result !== null) {
      this.result.string = ''
    }
    this.setBuyEnabled(true)
    this.updateCountdown(resp)
    this.node.active = true
    this.layoutDialog?.()
  }

  /** 购买结果：由 `GiftPayFlow` 给出的那句话，原样显示。 */
  renderResult(view: PayView): void {
    if (!this.node.active) {
      return
    }
    if (this.result !== null) {
      const lines: string[] = [view.title]
      if (view.detail !== null) {
        lines.push(view.detail)
      }
      // 未成年付费额度提示：服务端给了就原样念一句，没给就**一行都不加**
      // （不写「本月无额度限制」这类客户端自造的话 —— 成年与"年龄未知"在服务端是两种态，
      //  客户端替它合并就造出了第二个真相）。
      if (view.minorNotice !== null && view.minorNotice !== '') {
        lines.push(view.minorNotice)
      }
      this.result.string = lines.join('\n')
    }
    // 只有"已到账"和"失败/取消"才是终点；处理中也要把按钮收起来，避免重复下单
    this.setBuyEnabled(false)
    this.layoutDialog?.()
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
    this.mask.on('touch-start', () => { this.hide(); this.onClose?.() }, this)

    const panel = new Node('panel')
    panel.addComponent(UITransform).setContentSize(PANEL_WIDTH, PANEL_HEIGHT)
    root.addChild(panel)

    // 底板单独一个子节点：`applySlicedSprite` 会把它所挂节点上的 Graphics 清空并停用，
    // 底板与内容不同节点才不会顺手把标题、倒计时的绘制一起关掉（V25-c 的关键约束）。
    // 素材没加载成功就退回原来那块实心矩形 —— 兜底用的仍是局部常量，删它的前提是这一屏截图验收通过。
    const plate = new Node('plate')
    plate.addComponent(UITransform).setContentSize(PANEL_WIDTH, PANEL_HEIGHT)
    // 礼包属"奖励"语义 ⇒ 用鎏金底板（规格 §一 语义映射：奖励/礼包 → 金链绶带 + 火漆印 + 铜鎏框）。
    if (!applySlicedSprite(plate, 'ui.panel.gilt', PANEL_WIDTH, PANEL_HEIGHT)) {
      const bg = plate.addComponent(Graphics)
      bg.fillColor = COLOR_PANEL
      bg.fillRect(-PANEL_WIDTH / 2, -PANEL_HEIGHT / 2, PANEL_WIDTH, PANEL_HEIGHT)
    }
    panel.addChild(plate)
    plate.on('touch-start', () => {}, this)

    // 标题匾额是**整图装饰件**（meta 里 border 全 0 ⇒ SLICED 等价于整幅，不会被切开拉伸）。
    // 先挂它、后挂 Label ⇒ 文字在匾额之上。240×108 就是素材实测比例 2.213，不压扁。
    const banner = new Node('banner')
    banner.addComponent(UITransform).setContentSize(240, 108)
    // 匾额压在顶带中线上（§一 第 3 条），坐标从 inset 推：bandCenter = 180 - 72/2 = 144
    const bandCenter = PANEL_HEIGHT / 2 - PANEL_IRON_INSET.top / 2
    banner.setPosition(new Vec3(0, bandCenter, 0))
    applySlicedSprite(banner, 'ui.banner.crest', 240, 108)
    panel.addChild(banner)

    // 顶饰：奖励语义的"金链 + 小冠 + 空牌"，规格 §一 第 4 条要求按语义换、只出材质不出文字。
    // 放在匾额**正上方且不重叠**：匾额上沿在 y=158（104 + 108/2），顶饰取 52×64（实测比例 207:256）
    // 中心 y=190 ⇒ 下沿正好落在 158，两者相切不相盖，也就无需靠挂载顺序压 z 序。
    const crest = new Node('crest')
    crest.addComponent(UITransform).setContentSize(52, 64)
    crest.setPosition(new Vec3(0, PANEL_HEIGHT / 2 + 6, 0))
    applySlicedSprite(crest, 'ui.crest.reward', 52, 64)
    panel.addChild(crest)

    // 内容必须落在九宫格**净区**内：A 档 border 实测抬到 80·72 之后，460×300 这块最小底板
    // 上下各被铜边吃掉 72px ⇒ 净区只剩 y∈[-78,78]（原先按 48·36 排的字会压进边带）。
    // 这一圈是规格 §二 那条"换材质不等于自动有安全区"的现跑版本，坐标一律从 inset 推，不写死。
    const contentTop = PANEL_HEIGHT / 2 - PANEL_IRON_INSET.top
    const contentBottom = -PANEL_HEIGHT / 2 + PANEL_IRON_INSET.bottom

    // 分隔线：把"礼包名/倒计时"与"报价/按钮"分成两段，替掉原先靠空行硬撑的读法。
    // 512×40 是实测比例 ⇒ 260×20 不压扁；装饰件永不拉伸，整幅贴就够。
    const divider = new Node('divider')
    divider.addComponent(UITransform).setContentSize(260, 20)
    divider.setPosition(new Vec3(0, contentTop - 82, 0))
    applySlicedSprite(divider, 'ui.divider.rope', 260, 20)
    panel.addChild(divider)

    // 纵向排布：标题 / 显示名 / 倒计时 / 分隔线 / 按钮 / 结果，**逐行不重叠且全部落在净区内**。
    // 2026-09-21 复检抓到「剩 59:59」被「立即购买」按钮压住（倒计时 y=24、按钮盒 y∈[-28,28]）；
    // 报价过期时间是这一屏唯一的时效信息，被按钮盖掉等于玩家看不见它。
    this.title = this.label(panel, '礼包', 0, PANEL_HEIGHT / 2 - PANEL_IRON_INSET.top / 2, 26, COLOR_COPPER_GOLD)
    this.subtitle = this.label(panel, '', 0, contentTop - 36, 18, COLOR_TEXT)
    this.countdown = this.label(panel, '', 0, contentTop - 60, 16, COLOR_TEXT_DIM)
    this.result = this.label(panel, '', 0, contentBottom + 38, 16, COLOR_TEXT)
    this.result.node.getComponent(UITransform)!.setAnchorPoint(0.5, 1)

    this.buyButton = new Node('buy')
    this.buyButton.addComponent(UITransform).setContentSize(200, 36)
    this.buyButton.setPosition(new Vec3(0, contentTop - 122, 0))
    // C 档铁钮：200×56 走九宫格（border 6·4 ⇒ 顶底占高 14%，在 §二 的 [7%, 60%] 区间内）。
    if (!applySlicedSprite(this.buyButton, 'ui.button.iron', 200, 36)) {
      const buttonBg = this.buyButton.addComponent(Graphics)
      buttonBg.fillColor = COLOR_BUTTON
      buttonBg.fillRect(-100, -18, 200, 36)
    }
    // label() 自己把节点挂到 parent 上并返回 Label 组件 —— 再 addChild 一次挂的就是
    // 一个组件而不是节点（真机上是 addChild 直接抛错，而这一步只有真正弹过窗才会走到）
    this.label(this.buyButton, '立即购买', 0, 0, 20, COLOR_TEXT)
    panel.addChild(this.buyButton)
    // 「立即购买」以前**只画不接**：`onBuy` 声明在这里、GameBootstrap 也赋了值（`giftPopup.onBuy = ...`），
    // 但整个文件没有任何触摸注册 ⇒ 玩家点它什么都不会发生，整条 B19 支付链（下单 → 支付 → 轮询 →
    // 结果文案 → 未成年额度提示）在玩家侧不可达。`check-client-send-paths` 抓不到它：
    // 那道门数的是 GameApi 发送口的调用点，而 `createPayOrder` 的调用点在 AppRoot 里（作为 flow 的 deps），
    // 断的是 UI 这一层 —— 门禁的覆盖面止于发送口，管不到"视图有没有把玩家的点击交出去"。
    this.buyButton.on('touch-start', (_event: EventTouch) => {
      const productId = this.productId
      if (productId !== null) {
        this.onBuy?.(productId)
      }
    }, this)

    const close = new Node('close')
    close.addComponent(UITransform).setContentSize(36, 36)
    // 关闭键从"角上"挪进净区：A 档角帽实测占图宽 15%~19%，460 宽下右上角铜帽覆盖 x∈[150,230]、
    // y∈[78,150] —— 原先 (202,122) 那颗 × 正好压在铜帽与铆钉上（截图读成"角上有个脏点"）。
    // 净区右上角 = (PANEL_WIDTH/2 - inset.left - 18, PANEL_HEIGHT/2 - inset.top - 18)。
    close.setPosition(new Vec3(PANEL_WIDTH / 2 - PANEL_IRON_INSET.left - 18,
      PANEL_HEIGHT / 2 - PANEL_IRON_INSET.top - 18, 0))
    // C 档薄边铁片：× 仍由 Label 画（规格 §八 第 3 条禁止把符号烘进素材），贴图只给"这是一颗键"的载体。
    applySlicedSprite(close, 'ui.chip.close', 36, 36)
    this.label(close, '×', 0, 0, 22, COLOR_TEXT_DIM)
    panel.addChild(close)
    // 「×」与「立即购买」同族：`onClose` 也只是声明着、没人调用过。
    // 点关闭要先把自己藏起来（`hide()`），再通知编排层 —— 顺序反了会留下"宿主以为还开着"的态
    close.on('touch-start', (_event: EventTouch) => {
      this.hide()
      this.onClose?.()
    }, this)

    this.buyButton.setPosition(new Vec3(-28, this.buyButton.position.y, 0))
    close.setPosition(new Vec3(122, close.position.y, 0))
    // 语义饰件与正文一同进入可滚动内容，短屏不让顶饰冲出可用区。
    this.layoutDialog = fitExistingDialog(root, plate,
      [banner, crest, divider, this.title.node, this.subtitle.node, this.countdown.node, this.result.node],
      [this.buyButton, close], 'ui.panel.gilt', PANEL_WIDTH, PANEL_HEIGHT)

    this.node.active = false
  }

  private label(parent: Node, text: string, x: number, y: number, size: number,
                color: Color): Label {
    const node = new Node('label')
    node.addComponent(UITransform).setContentSize(PANEL_WIDTH - PANEL_IRON_INSET.left - PANEL_IRON_INSET.right, size + 8)
    node.getComponent(UITransform)!.setAnchorPoint(0.5, 0.5)
    node.setPosition(new Vec3(x, y, 0))
    const label = node.addComponent(Label)
    label.string = text
    label.fontSize = size
    label.lineHeight = Math.round(size * 1.4)
    label.color = color
    label.overflow = Label.Overflow.RESIZE_HEIGHT
    label.enableWrapText = true
    applySystemUiFont(label)
    parent.addChild(node)
    return label
  }
}
