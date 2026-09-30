// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 下单结果：订单号 + 调起支付所需的参数。
 */
public record CreateOrderResp(
        String orderId,   // 服务端订单号。**回调验签与补单都以它为唯一键** —— 用渠道的 transactionId 做键是不行的，因为取消后重新支付会产生新的 transactionId，而那是同一笔订单。
        String minorNotice,   // 未成年付费额度的**提示**，不是错误：**超限也照常下单**，这一句只告诉玩家「本月还剩多少」。**可空**（null/缺省 = 本次无需提示：成年、或年龄未知）。 **为什么单独一列而不是复用错误码**：B15 §3 明写「❌ 不要让未成年限额提示变成硬拦截（体验友好优先）」，而 `Result.detail` 在 prod 被置 null —— 动态文案放在那里玩家永远看不到。所以提示必须有一列自己的送达路径。
        PayParams payParams)   // 调起支付所需参数。
{
}
