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

/** 拉起支付的四种桥接回调。`failed` 与 `cancelled` 分开保留；两者都不能代替本人订单状态。 */
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
        // 当前桥把 -2 归为用户取消；微信小游戏专用文档和真机回执尚未核实，不能称此映射已通过正式渠道验收。
        // 其余归为渠道 fail；资金和发货结局由本人订单查询判定。
        resolve(err.errCode === -2 ? 'cancelled' : 'failed')
      },
    })
  })
}
