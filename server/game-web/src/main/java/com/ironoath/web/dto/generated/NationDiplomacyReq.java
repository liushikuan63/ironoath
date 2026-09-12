// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /nation/diplomacy 请求体：变更与另一个国家的外交关系。
 *
 * 权限走 role_permission 表的 MANAGE_DIPLOMACY（国王与外交官档，普通成员不行）。**单方面变更**：B13 §5 没有要求双方同意，所以盟约是可以被一方单方面宣布的 —— 这与现实外交不同，但要求双方确认会让「结盟」变成一次需要两人同时在线的操作，而那在小服里几乎不可能凑齐。
 */
public record NationDiplomacyReq(
        String requestId,   // 幂等键。重放一次「宣布敌对」不该产生两条外交日志，否则审计时看不出到底宣布了几次。
        String targetNationId,   // 对方国家 id。不能是自己 —— 与自己结盟没有意义，而与自己敌对会让 mayAttackNation 拒绝一切进攻，等于自废武功。
        DiplomacyRelation relation)   // 要设置的关系。
{
}
