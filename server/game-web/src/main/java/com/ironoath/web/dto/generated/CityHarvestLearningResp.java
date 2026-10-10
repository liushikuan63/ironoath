// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * （Schema 未提供说明）
 */
public record CityHarvestLearningResp(
        List<ResourceLearningView> resources,
        boolean collectAllUnlocked,   // 主城等级与全部手动教学均完成
        String unlockReason,   // 面向玩家的解锁原因或已解锁说明
        int requiredMainLevel,   // 配置中的主城等级门槛
        long serverNow)
{
}
