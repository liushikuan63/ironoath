/**
 * 职责：米大师道具直购薄桥（B19 S3-iv）—— 把 `wx.requestMidasPaymentGameItem` 的几种结局收敛成一个枚举。
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
  if (typeof wx === 'undefined' || typeof wx.requestMidasPaymentGameItem !== 'function') {
    return Promise.resolve('unsupported')
  }
  const outTradeNo = params.outTradeNo
  const signData = params.signData
  const paySig = params.paySig
  const signature = params.signature
  if (!outTradeNo || !/^[0-9a-f]{32}$/.test(outTradeNo)
    || typeof signData !== 'string' || !signData
    || typeof paySig !== 'string' || !/^[0-9a-f]{64}$/.test(paySig)
    || typeof signature !== 'string' || !/^[0-9a-f]{64}$/.test(signature)) {
    // Legacy orders have no frozen signed GameItem payload; never invent a new platform identity or fall back to game currency.
    return Promise.resolve('unsupported')
  }
  let parsed: { mode?: unknown; outTradeNo?: unknown }
  try {
    parsed = JSON.parse(signData) as { mode?: unknown; outTradeNo?: unknown }
  } catch {
    return Promise.resolve('unsupported')
  }
  if (parsed.mode !== 'goods' || parsed.outTradeNo !== outTradeNo) {
    return Promise.resolve('unsupported')
  }
  return new Promise<PaymentOutcome>((resolve) => {
    wx.requestMidasPaymentGameItem({
      signData,
      paySig,
      signature,
      success: () => resolve('ok'),
      // The current GameItem API docs don't define cancellation codes; query the server order for every fail.
      fail: () => resolve('failed'),
    })
  })
}
