// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /rally/join 与 /rally/quit 的请求体。
 */
public record RallyJoinReq(
        String requestId,   // 幂等键。加入会锁定兵力，重放会重复锁
        String rallyId,   // 集结 id
        List<RallyTroop> troops,   // 本次承诺出征的兵力（按 unitId → 数量，与行军同一口径）
        List<String> heroes)   // 该成员随军的武将 id，可为空。武将位按加入顺序抢，满了或与他人重复会落选（见 RallyView.heroSlots）。
{
}
