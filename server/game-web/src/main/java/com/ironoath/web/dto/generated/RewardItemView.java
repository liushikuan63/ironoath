// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条奖励。id 的含义由 type 决定：RESOURCE 时是资源类型（WOOD/GOLD...），ITEM 时是 item 表的行 id，HERO 与 HERO_FRAGMENT 时是 hero 表的行 id。name 由服务端从配置表解析后下发，客户端不得自行翻译（与 BagItem.name 同一口径）。
 */
public record RewardItemView(
        RewardType type,
        String id,
        long count,   // 聚合后的数量。批量开箱 100 次抽到 30 次木材时这里是 30 次的总量，不是 30 行。
        String name)
{
}
