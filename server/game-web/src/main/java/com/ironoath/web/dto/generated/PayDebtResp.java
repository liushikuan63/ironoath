// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /ops/pay/debt 响应：钱收了、货没发出去的负债（只读，需运维令牌）。
 *
 * **为什么必须有出口**：#27 那条 ERROR 日志的原话是「这笔钱已经收了，必须有人跟进」，而 `unfulfilledCents()` 与 `retryQueue()` 此前**生产调用点为零** —— 只有测试在读。日志喊了但没人能查账，等于没有账。
 */
public record PayDebtResp(
        long unfulfilledCents,   // 未发货负债总额（分）。对账口径：**这个数必须最终归零**，它变大就是负债积压，该报警而不是该优化查询。金额一律 64 位（本仓库所有「分」都是）。
        long unfulfilledOrders,   // 未发货的订单笔数（与端口 {@code unfulfilledOrderCount()} 同为 64 位）。与总额一起看才分得清「一笔大的」和「一堆小的」这两种完全不同的成因。
        int listed,   // 本响应实际带出的笔数（受 limit 约束）。**listed 小于 unfulfilledOrders 就说明还有没列出来的**，所以不把两者合并成一个数 —— 合并了运维就会以为看到的就是全部。
        List<DebtOrderView> orders)   // 待补发的订单明细，按存储层的顺序。
{
}
