// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一档上的那份奖励。**只允许资源与道具**（与 `product_reward` 表同一套词汇）—— 理由是战令的奖励必须进得了邮件：赛季结束未领的档位要按档补发，而邮件附件只能是 `RewardType` 表达得了的东西。外观不在其中：它随购买立即到账，见 `battle_pass_season.json`。
 */
public record BattlePassRewardView(
        BattlePassRewardType rewardType,
        String rewardId,   // 奖励 id：`RESOURCE` 时是 `resource` 表的行 id（如 `GOLD`），`ITEM` 时是 `item` 表的行 id。**不下发跨表解析后的对象**：客户端只拿它去查本地图集与名字，判定与发放都在服务端。
        String name,   // 显示名，服务端从对应的表里查出后下发 —— 改个名字不该发一次版。
        long count)   // 份数。
{
}
