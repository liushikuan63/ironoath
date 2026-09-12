// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 三选一里的一个候选。**id 与 name 成对下发**：不给两条平行数组（ids 与 names）是因为它们迟早会不同长，而不同长的表现是「点了第三个选项却领到第一个武将」，全链路不报错。
 * name 由服务端从 hero 表解析，客户端不得自行翻译（与 QuestReward.name 同一口径）。
 */
public record HeroChoice(
        String heroId,   // hero 表的行 id。选它之后随 claim 的 heroChoice 回传。
        String name)   // 武将名（hero 表的 name）。
{
}
