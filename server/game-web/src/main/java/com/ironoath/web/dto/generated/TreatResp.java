// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 开始治疗 / 加速治疗 / 收割治疗的共用响应。returned 只在收割时非空。
 */
public record TreatResp(
        long woundedCount,
        boolean treating,
        Long treatFinishAt,
        long treatRemainingSeconds,
        List<ResourceAmount> cost,   // 开始治疗时是实际扣掉的资源；收割/查询时为空
        List<UnitReturned> returned,   // 本次归队的伤兵
        long serverNow)
{
}
