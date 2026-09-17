// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 取消结果：取消了什么、退回来什么。
 */
public record TechCancelResp(
        String techId,   // 被取消的那一行（客户端据此把行上的标记摘掉）。
        List<ResourceAmount> refund)   // 返还的资源。比例与城建一致（`city_rule.city_rule_cancel_refund_ratio`，现值 0.60）—— B20 §一 明写「取消返还（比例与城建一致）」，两处各配一个数字迟早会让玩家问「为什么取消建造返 60% 取消研究返 40%」。
{
}
