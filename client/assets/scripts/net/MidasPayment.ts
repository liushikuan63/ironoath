/**
 * 职责：米大师支付的薄桥（B19 S3-iv）—— 把 `wx.requestMidasPayment` 的几种结局收敛成一个枚举。
 * 依赖：全局 `wx`（类型声明见 client/types/cc.d.ts）。
 *
 * <p><b>它只报"渠道侧这一步成没成"，不报"货发没发"</b>：发货由服务端在收到渠道回调之后做，
 * 客户端必须去查订单（契约里那句注释就是这条纪律）。把渠道回调当发货凭据，在补单场景下会变成一句谎话。
 *
 * <p><b>`unsupported` 存在的意义</b>：浏览器/编辑器里没有这个 API，而"点了没反应的按钮"是禁止项 ——
 * 面板拿到这个结局要显示开发期说明。
 */
import type { PayParams } from './generated/PayProtocol'

/** 拉起支付的四种结局。`failed` 与 `cancelled` 必须分开：前者是"没付成"（余额不足/风控），后者是"用户自己退的"。 */
export type PaymentOutcome = 'ok' | 'cancelled' | 'unsupported' | 'failed'

export function requestMidasPayment(params: PayParams): Promise<PaymentOutcome> {
  if (typeof wx === 'undefined' || typeof wx.requestMidasPayment !== 'function') {
    return Promise.resolve('unsupported')
  }
  return new Promise<PaymentOutcome>((resolve) => {
    wx.requestMidasPayment({
      mode: params.mode,
      offerId: params.offerId,
      buyQuantity: params.buyQuantity,
      env: params.env,
      currencyType: params.currencyType,
      success: () => resolve('ok'),
      fail: (err) => {
        // -2 = 用户取消（官方口径，接入真实渠道时须按文档复核）。
        // 其余一律按"没付成"：把余额不足当成"取消"会让玩家以为什么都没发生
        resolve(err.errCode === -2 ? 'cancelled' : 'failed')
      },
    })
  })
}
