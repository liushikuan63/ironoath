// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条待补发的奖励。字段与 game-core 的 RewardItem 一一对应（type 是裸字符串，与 PayRewardItem 同一处理，理由见 pay.schema.json 顶部第 4 条）。
 */
public record CompensationRewardView(
        String type,   // 奖励类型：RESOURCE / ITEM / HERO / HERO_FRAGMENT / STAMINA / PRIVILEGE。
        String id,   // 目标 id，语义随 type 变化。
        long count)   // 数量，恒为正。全程 64 位整数，禁止浮点（B04 禁止项）。
{
}
