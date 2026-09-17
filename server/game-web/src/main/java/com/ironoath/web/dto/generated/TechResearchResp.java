// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 开始研究的结果。扣掉的资源与算出的时长一并回，界面就不必再拉一次列表才更新得准。
 */
public record TechResearchResp(
        String techId,
        int level,   // 本次研究的**目标**等级（完成后就是它的新等级）。
        long finishAt,   // 完成时刻（毫秒）。
        List<ResourceAmount> cost,
        long timeSec)   // 本次研究时长（秒）。必须 ≥ 1 —— 缩短时长的加成一律 `ceil`（§五④），0 秒完成等于没有队列。
{
}
