// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一笔负债。只给跟进需要的字段：谁、哪一单、多少钱、试了几次、上次为什么没发出去。
 *
 * **刻意不给 transactionId**：那是渠道侧的支付凭证号，出现在一个运维列表里对它没有任何用处，而多一处出现就多一处泄露面。要对着渠道查账的人应该走渠道后台。
 */
public record DebtOrderView(
        String orderId,   // 订单号（本服生成）。
        String playerId,   // 该给谁发货。
        long cents,   // 这一单的金额（分），与 unfulfilledCents 同一口径。
        long paidAt,   // 确认收款的时刻（毫秒）。玩家已经付了多久 —— 这是排优先级的第一依据。
        int fulfillAttempts,   // 已尝试发货的次数。为 0 表示回调进来了但从没试过，非 0 表示试过且都失败 —— 两者的处理人不同。
        String failureReason)   // 最后一次失败的原因。可空：从未尝试过的时候没有原因可说（不是「原因为空字符串」）。
{
}
