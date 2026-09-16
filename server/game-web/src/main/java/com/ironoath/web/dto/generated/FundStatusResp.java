// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /pay/fund 响应：成长基金档位与解锁状态（B19 §一.1b）。
 */
public record FundStatusResp(
        boolean purchased,   // 是否买过基金。没买过 ⇒ 所有档位只展示不可领，`claimable` 一律 false。
        List<FundTier> tiers,   // 档位列表，按主城等级升序。来源是 `product_reward` 里挂在 growth_fund 上的行 —— 表加一档，这里就多一档，不在代码里写死六档。
        int mainCityLevel,   // 玩家当前主城等级（判门槛用的是它，不是任何客户端上报的值）。
        long serverNow)   // 服务端当前时刻。
{
}
