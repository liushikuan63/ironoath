// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 成长基金的一个返还档位。
 */
public record FundTier(
        String tierId,   // `product_reward` 的行 id（形如 pr_fund_t1）。领取时原样回传 —— 用行 id 而不用「第几档」的序号，是因为序号会在表里插行时整体错位，而一次错位的后果是玩家领到别档的钱。
        int requireMainLevel,   // 解锁所需主城等级。
        long count,   // 该档发的金币数（`product_reward.count`）。
        boolean claimed,   // 是否已领过。**已领的档位永久不再出现可领状态**，基金本身不过期（B19 §五②d：一次性付费的权益不设过期）。
        boolean claimable)   // 现在能不能领（= 已购买 且 等级达标 且 未领过）。分开给两个布尔而不是只给一个，是因为客户端要把「还没到」和「已经领过」画成两种不同的样子。
{
}
