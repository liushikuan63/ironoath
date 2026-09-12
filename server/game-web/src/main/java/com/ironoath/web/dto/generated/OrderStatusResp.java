// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /pay/order 响应：订单当前状态与已发放的奖励。
 *
 * **客户端在支付返回后必须轮询这个端点**，不能凭 `wx.requestMidasPayment` 的成功回调直接发货显示 —— 那个回调只表示「渠道侧完成了」，而发货是服务端在收到渠道服务器回调之后才做的。凭客户端回调直接显示「已到账」，会在补单场景下变成一句谎话。
 */
public record OrderStatusResp(
        OrderStatus status,   // 订单状态。
        List<PayRewardItem> rewards,   // 已发放的奖励。SUCCESS 但 rewards 为空表示「钱收到了、货还在补单队列里」—— 这是一种必须能表达的状态，否则客户端只能显示「已购买」而玩家什么都还没拿到。
        Boolean retryQueued)   // 是否已进补单队列。为 true 时客户端应当显示「发货处理中」并给出客服入口，而不是显示失败 —— 钱已经收了，说失败会引发退款投诉。
{
}
