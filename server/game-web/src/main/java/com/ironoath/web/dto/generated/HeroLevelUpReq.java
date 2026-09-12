// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /hero/levelUp 请求体。经验书按 expItems 逐种消耗，服务端按 curve.HERO_LEVEL_EXP 连续升级（一次投喂多本可能连升数级）。
 */
public record HeroLevelUpReq(
        String requestId,
        String heroId,
        List<ItemCount> expItems)
{
}
