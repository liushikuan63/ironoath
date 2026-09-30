// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条国策（`nation_policy.json` 的一行）。全量下发，顺序 = 表序，客户端不知道有几行。
 */
public record NationPolicyView(
        String policyId,   // `nation_policy.json` 的行 id，提案时原样回传（服务端按它查表，不认下标）。
        String name,   // 表里的中文名，服务端下发。客户端不硬编码国策名 —— 改一次文案不该要改客户端。
        NationPolicyEffectAttr effectAttr,   // 改的是哪个数。客户端按它决定这一行画在哪个分组下，但**不自己算合成**（那是服务端的事，铁律 3）。
        long effectValueFixed,   // 幅度（定点万分比：1500 = +15%）。**允许为负** —— 国策是「全国性增益**或减益**」（`role_permission` 那行 `perm_nation_set_national_policy` 的 why 原话），协议不能把它锁成非负。
        String targetUnitName,   // 作用到的兵种中文名，服务端从 `unit.json` 查好下发；不针对特定兵种的国策（坚壁/丰收/征伐）为 null。 **下发中文名而不是 `targetUnit` id**：客户端不抄配置表（数值与中文名一律来自服务端），而玩家要看到的是「轻骑兵 T1」而不是 `unit_cavalry_t1`。
        String effectText)   // 一句可直接上屏的效果说明（服务端拼好的，如「轻骑兵 T1 攻击 +15%」）。有了它，客户端就不必把 `effectAttr` × `effectValueFixed` × `targetUnitName` 自己拼一遍 —— 那正是「第二个家」的形状（改文案要改客户端，而且拼错的版本没人能发现）。
{
}
