// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 基金档位领取结果。
 */
public record FundClaimResp(
        String tierId,   // 本次领掉的档位。
        List<PayRewardItem> rewards,   // 本次发出的奖励。
        long serverNow)   // 服务端当前时刻。
{
}
