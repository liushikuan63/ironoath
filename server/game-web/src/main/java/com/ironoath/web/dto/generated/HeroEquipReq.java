// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /hero/equip 请求体。equipId 为 null 表示卸下该槽位（B06 验收 10：卸下后加成必须消失）。
 */
public record HeroEquipReq(
        String requestId,
        String heroId,
        EquipSlot slot,
        String equipId)
{
}
