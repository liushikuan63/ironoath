/**
 * 职责：礼包购买的状态机（B19 S3-iv 的"支付 + 轮询"半段）—— 下单 → 拉起支付 → 轮询 → 结果视图。
 * 依赖：注入的三件套（下单 / 查单 / 拉起支付）+ 时钟、延迟、埋点；**不 import 'cc'**，所以能在 node 里真跑。
 *
 * <p><b>为什么把状态机单独成类</b>：面板（`GiftPopupView`）要画的是"此刻该显示哪句话"，
 * 而那句话由订单状态、支付结果、补单标记三样共同决定 —— 写进面板就会与图形代码纠缠、只能靠肉眼验。
 * 这个类只回答"现在是什么阶段、该显示什么"，用例覆盖每条分支（含"钱可能已扣但货还在路上"）。
 *
 * <p><b>三条不可退让的口径</b>（B19 §四 禁止项）：
 * <ol>
 *   <li><b>`retryQueued=true` 绝不许显示"已到账"</b>：钱收了、货还没发，说已到账会引发退款投诉；
 *       要说的是"发货处理中"并给客服入口</li>
 *   <li><b>轮询超时也不许报失败</b>：那一刻钱可能已经扣了，但服务端还没确认，
 *       要说「支付状态待确认」；只有服务端回 SUCCESS 且奖励未到，才说「发货处理中」</li>
 *   <li><b>浏览器/编辑器里不能是死按钮</b>：拉不起支付时要显示"开发期说明"，而不是点了没反应</li>
 * </ol>
 *
 * <p><b>支付结果只认服务端</b>：拉起支付的返回值只说"渠道这一步成没成"（用户取消 / 拉起成功），
 * 发货与否一律以 `GET /pay/order` 为准 —— 客户端不许自己推断"付过了"。
 */
import type { NetOutcome } from '../../net/NetModule'
import type { CreateOrderResp, OrderStatusResp, PayParams } from '../../net/generated/PayProtocol'
import { TRACK_EVENTS } from '../track/TrackEvents'
// 结局类型住在支付桥里（net 层），流程只消费它 —— 反过来让 net 依赖 game 就把分层搞倒了
import type { PaymentOutcome } from '../../net/MidasPayment'
export type { PaymentOutcome }


/** 面板要显示的阶段。 */
export type PayPhase = 'paying' | 'delivered' | 'processing' | 'failed' | 'cancelled'

/** 一次购买的结果视图。面板只负责把它画出来，不做任何判断。 */
export interface PayView {
  readonly phase: PayPhase
  /** 给玩家的一句话（服务端给了 msg 就用服务端的，它是给玩家写的）。 */
  readonly title: string
  /** 补充说明（可空）。 */
  readonly detail: string | null
  /** 服务端确认发放的奖励（只有 `delivered` 时非空）——原样来自订单查询，客户端不加工。 */
  readonly rewards: OrderStatusResp['rewards']
  /** 支付结果待确认或发货处理中时显示礼包内的客服入口。 */
  readonly showSupport: boolean
  /**
   * 服务端随**下单回执**给的未成年付费额度提示，原样搬运（客户端不算额度、不改写、不翻译）。
   * null = 本次无需提示（成年、或年龄未知）。
   *
   * <p>这一列是那句提示**唯一的送达路径**：#489 裁决把限额从"硬拦"改成"提示"，
   * `PAY_MINOR_LIMIT(15002)` 从此不再抛出，而动态文案放 `Result.detail` 玩家永远看不到
   * （prod 被置 null）—— 谁不读这一列，那条合规提示就整条落空，而屏上什么都不会报错。
   */
  readonly minorNotice: string | null
}

export interface PayFlowDeps {
  createOrder(productId: string, requestId?: string): Promise<NetOutcome<CreateOrderResp>>
  orderStatus(orderId: string): Promise<NetOutcome<OrderStatusResp>>
  invokePayment(params: PayParams): Promise<PaymentOutcome>
  now(): number
  delay(ms: number): Promise<void>
  track(name: string, params: Record<string, string>): void
}

export interface PayFlowConfig {
  /** 查单轮询次数；pollIntervalMs 也用于失联下单同键重投前的短暂等待。 */
  readonly maxPolls: number
  readonly pollIntervalMs: number
}

export const DEFAULT_PAY_CONFIG: PayFlowConfig = { maxPolls: 5, pollIntervalMs: 1_000 }

export class GiftPayFlow {
  private readonly deps: PayFlowDeps
  private readonly config: PayFlowConfig

  constructor(deps: PayFlowDeps, config: PayFlowConfig = DEFAULT_PAY_CONFIG) {
    this.deps = deps
    this.config = config
  }

  /**
   * 买一档礼包。**永远返回一个视图**（不抛）：面板不需要 try/catch，
   * 而"失败"本身也是一种要显示的状态。
   */
  async buy(productId: string): Promise<PayView> {
    // 用字典里的常量而不是字面量：埋点覆盖率卡口（check-track-coverage）认的是
    // `TRACK_EVENTS.xxx` 的引用，写字面量会让 `pay_click` 一直挂在"字典里尚无调用点"的清单上，
    // 而后人据此以为这一环真的没人发
    this.deps.track(TRACK_EVENTS.payClick, { productId })

    let order = await this.deps.createOrder(productId)
    if (order.kind === 'network' && !order.queued && order.requestId?.trim()) {
      // 只沿用这次网络结果里的原键重投一次：第一枪可能已落库但回执丢了。
      // 旧后端可能回「重复请求」；此时仍保持未知，不能把它说成已下单或已扣款。
      await this.deps.delay(this.config.pollIntervalMs)
      const replay = await this.deps.createOrder(productId, order.requestId)
      if (replay.kind === 'ok' || replay.kind === 'network') {
        order = replay
      }
    }
    if (order.kind === 'biz') {
      // 服务端的 msg 是写给玩家的（15011 今天买过了 / 15012 报价过期），原样用它
      return failed(order.msg, order.detail, null)
    }
    if (order.kind === 'network') {
      // 网络错误也可能是服务端已建单、回执却丢了；本次没有拿到支付参数，所以未拉起支付。
      return failed('下单结果未确认',
        '下单回执未返回，支付未拉起；请勿反复下单，如需核查请联系客服', null, true)
    }

    // 下单成功才有这一列：它说的是"本月还剩多少"，与后面的支付结果无关（超限也照常下单），
    // 所以取一次、带到底，四种结局都把它捎上 —— 而不是只在"成功"那一支显示。
    const minorNotice = order.data.minorNotice

    const paid = await this.deps.invokePayment(order.data.payParams)
    if (paid === 'unsupported') {
      // 浏览器/编辑器：不给死按钮，明确说清这一步在开发环境里做不了
      return failed('当前环境不支持支付', '请在微信小游戏里打开本游戏；浏览器仅供开发调试', minorNotice)
    }
    if (paid === 'failed') {
      // 渠道 fail 回调不是订单终态：先向本人订单查一次，避免已经到账却显示失败。
      // PENDING 或查不到仍保持未知，不延长这次失败回执的等待时间。
      const status = await this.deps.orderStatus(order.data.orderId)
      if (status.kind === 'ok') {
        if (status.data.status === 'SUCCESS') {
          return this.successView(status.data, minorNotice)
        }
        if (status.data.status === 'FAILED') {
          return failed('支付未成功',
            '当前订单未确认付款；如看到扣款或奖励未到账，请联系客服', minorNotice, true)
        }
      }
      return failed('支付结果未确认',
        '请勿立即重复付款；若已扣款或奖励未到账，请联系客服', minorNotice, true)
    }
    if (paid === 'cancelled') {
      // 桥的取消回调不等于后端订单 CANCELLED；目前服务端也没有该回调的取消写口。
      // 先查本人订单，迟到的成功回调若已入账就不能再画「取消」。
      const status = await this.deps.orderStatus(order.data.orderId)
      if (status.kind === 'ok') {
        if (status.data.status === 'SUCCESS') {
          return this.successView(status.data, minorNotice)
        }
        if (status.data.status === 'FAILED') {
          return failed('支付未成功',
            '当前订单未确认付款；如看到扣款或奖励未到账，请联系客服', minorNotice, true)
        }
      }
      return {
        phase: 'cancelled',
        title: '支付流程已退出',
        detail: '订单状态待确认，请勿立即重复付款；如看到扣款或奖励未到账，请联系客服',
        rewards: [],
        showSupport: true,
        minorNotice,
      }
    }

    return this.poll(order.data.orderId, minorNotice)
  }

  /** 轮询订单状态，直到拿到确定结果或用完次数（用完**不报失败**，见类注释第 2 条）。 */
  private async poll(orderId: string, minorNotice: string | null): Promise<PayView> {
    for (let attempt = 0; attempt < this.config.maxPolls; attempt += 1) {
      if (attempt > 0) {
        await this.deps.delay(this.config.pollIntervalMs)
      }
      const status = await this.deps.orderStatus(orderId)
      if (status.kind === 'network') {
        continue // 弱网：下一轮再问，绝不因为"问不到"就下结论
      }
      if (status.kind === 'biz') {
        // 查单被业务拒绝（订单不存在等）：这是真失败，且钱那一侧由服务端账本说了算
        return failed(status.msg, status.detail, minorNotice)
      }

      const data = status.data
      if (data.status === 'FAILED') {
        // 未付订单会惰性过期；迟到且验签通过的成功回调仍可重开，不能保证绝对未扣款。
        return failed('支付未成功', '当前订单未确认付款；如看到扣款或奖励未到账，请联系客服',
          minorNotice, true)
      }
      if (data.status === 'SUCCESS') {
        return this.successView(data, minorNotice)
      }
      // PENDING：继续问
    }
    return processingUnconfirmed(minorNotice)
  }

  private successView(data: OrderStatusResp, minorNotice: string | null): PayView {
    if (data.rewards.length > 0) {
      return {
        phase: 'delivered',
        title: '已到账',
        detail: null,
        rewards: data.rewards,
        showSupport: false,
        minorNotice,
      }
    }
    // 钱到了、货还在补单队列里 —— 这一支必须与"已到账"分开表达。
    return processingPaid(minorNotice)
  }
}

function failed(title: string, detail: string | null, minorNotice: string | null,
                showSupport = false): PayView {
  return { phase: 'failed', title, detail, rewards: [], showSupport, minorNotice }
}

function processingPaid(minorNotice: string | null): PayView {
  return {
    phase: 'processing',
    title: '发货处理中',
    detail: '款项已收到，奖励正在发放；稍后在邮件里查收，也可联系客服',
    rewards: [],
    showSupport: true,
    minorNotice,
  }
}

function processingUnconfirmed(minorNotice: string | null): PayView {
  return {
    phase: 'processing',
    title: '支付状态待确认',
    detail: '暂未确认支付结果，请勿立即重复付款；若已扣款或奖励未到账，请联系客服',
    rewards: [],
    showSupport: true,
    minorNotice,
  }
}
